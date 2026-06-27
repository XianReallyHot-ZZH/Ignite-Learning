# S05 · 执行规格:NIO 引擎 v3(recovery + 背压 + SSL 槽)

> **Phase 1 · NIO · v3** · Phase 1 收官(本 session 后 NIO 子系统完成)
> 执行约束规格(瘦)。**教学法见 `docs-learn/S05-nio-v3.md`**(按需)。
> **SoT**:范围/顺序看 roadmap S5 块;拆分看 `P01-nio-analysis.md` §6;本规格 = 细化 + 契约 + 验收。
> 代码 `ignite-gogogo/s05-nio-v3/`(从 s04 复制扩展)。lint:`scripts/check-cited-paths.sh`。

## 1. 范围与位置
- **roadmap S 块**:Session S5(权威范围/前置/实现要点/验收)。
- **phase §6 行**:P01 §6 · S5 = **v3**。
- **本 session 做**:① **Recovery**(`RecoveryDescriptor`:单调计数器去重 + 有界未确认队列 + `resend` 重放);② **双重背压**(发送:有界写队列 + 信号量;接收:`MessageTracker` 暂停/恢复 `OP_READ`);③ **SSL 槽**(过滤器占位接口,不实现真实加密)。
- **本 session 不做**:真实 SSL/TLS 加密(只留接口)、slow-client 策略(消费者 S20 侧)、session 跨 worker 迁移(MOVE)、**handshake/重连编排**(归 S20 —— 本 session 只提供机制 API,测试里模拟)。
- **前置**:S4(复制 s04 → s05 作为起点:多 worker + 过滤链)。

## 2. 对外接口契约
> DAG 出边:S5 → S20(Communication)。下游复用的 public 契约:

| 类型/方法 | 签名 / 语义 | 供下游 session |
|---|---|---|
| `RecoveryDescriptor` | 每 connection:计数器(`sentCnt`/`acked`/`rcvCnt`)+ 有界 `unacked` 队列;`add(req)`、`ackReceived(rcv)`、`onHandshake(rcv)`、`resend(ses)`、`release(nodeLeft)` | S20(拥有 descriptor,handshake 后接入 session) |
| `NioServer.send` 经 recovery | 非 `skipRecovery` 的消息计入 `unacked`;重连后 `resend` 重放;**去重靠单调计数器**(无 per-message 去重集) | S20(at-least-once 可靠投递) |
| 发送背压 | 写队列满 → `send` 阻塞(信号量);message-thread 旁路防自死锁 | S20 |
| `MessageTracker`(接收背压) | 未处理达上限 → `pauseReads`(关 `OP_READ`);处理完 → `resumeReads` | S20 |
| SSL 槽 | `SslFilter` 过滤器占位(本 session 不实现真实加密) | 后续 |

## 3. Ignite 源码导读(`file:line`,2.18.0)
1. **Recovery 状态**:`GridNioRecoveryDescriptor`(`internal/util/nio/GridNioRecoveryDescriptor.java`:38)—— 计数器 + `msgReqs`;`add`(:193,溢出返回 false)、`ackReceived`(:214)、`onHandshake`(:315,重连对齐 + 设 `resendCnt`)、`release`(:378,**节点离开→drain+fail;仍活→保留待重发**)。
2. **resend**:`GridNioServer.resend(ses)`(:749),在 `REGISTER` 时触发(:2753);`skipRecovery` 标记(:3373)排除控制消息。
3. **发送背压**:`GridSelectorNioSessionImpl.sem`(:66,Semaphore `sndQueueLimit`);`offerFuture`(:354,生产者阻塞)、`pollFuture`(:402,释放);溢出→`close()` 重连(:412);message-thread 旁路 `GridNioBackPressureControl`(:28)。
4. **接收背压**:`GridNioMessageTracker`(`:104` 暂停 / `:58` 恢复)→ `pauseReads`/`resumeReads` → `GridNioServer` 翻 `OP_READ`(`PAUSE_READ` :2204 / `RESUME_READ` :2224)。
5. **SSL(占位)**:`ssl/GridNioSslFilter`(:48,包 `SSLEngine`;本 session 只读其位置,不实现)。

## 4. 实现步骤(v3;从 s04 复制扩展)
1. 复制 `s04-nio-v2/` → `s05-nio-v3/`,改 artifactId;`rm -rf core/target`。
2. `RecoveryDescriptor`:`sentCnt`/`acked`/`rcvCnt` + `Deque<WriteRequest> unacked`(容量 `queueLimit`);`add(req)`(非 skipRecovery 才入队,满则返回 false)、`ackReceived(rcv)`(弹出已确认)、`onHandshake(rcv)`(对齐 + 标记待重发)、`resend(ses)`(把 `unacked` 改绑新 session 重放)、`release(nodeLeft)`(true→drain+fail;false→保留)。
3. 接入发送路径:`NioSession` 持可选 `RecoveryDescriptor`;`send` 时若存在,`add(req)` 计入未确认。
4. `resend(ses)`:供消费者重连后调用;测试里直接调用模拟。
5. **发送背压**:`NioSession.writeQueue` 改有界 + `Semaphore`;`send` 在满时 `acquireUninterruptibly()`(message-thread 旁路:用 `ThreadLocal` 标记,防自死锁)。
6. **接收背压**:`MessageTracker`(每 session `msgCnt` + `limit`);`onMessageReceived`→`++`达限则 `ses.pauseReads()`;处理完回调 `onProcessed`→`--`低于限则 `ses.resumeReads()`;`pauseReads/resumeReads` 经 worker 翻 `OP_READ`(复用 S4 的 worker 线程模型)。
7. **SSL 槽**:在过滤链预留 `SslFilter` 占位(空实现/直通),真实加密不做。

## 5. 验收 = 具名测试

| 验收点 | 测试 |
|---|---|
| 计数器 + 未确认队列基本正确 | `RecoveryDescriptorTest#countersAndUnackedQueue` |
| `ackReceived` 弹出已确认 | `RecoveryDescriptorTest#ackReceivedDropsAcked` |
| `onHandshake` 对齐 + 标记重发 | `RecoveryDescriptorTest#onHandshakeAligns` |
| 队列溢出触发(返回 false / 重连) | `RecoveryDescriptorTest#overflowTriggersReconnect` |
| 节点离开 → drain+fail;仍活 → 保留 | `RecoveryDescriptorTest#releaseSemantics` |
| 断线重连重放 + 接收侧计数器去重 | `RecoveryResendTest#reconnectResendsAndDedups` |
| 发送背压:队列满 `send` 阻塞,排空恢复 | `SendBackpressureTest#blocksWhenFullThenDrains` |
| 接收背压:达限 `pauseReads`,处理后 `resumeReads` | `ReceiveBackpressureTest#pausesAndResumesAtLimit` |
- demo:两个 JVM,中途模拟一端断连重连,消息**不丢不重**;过载时接收侧能暂停对端读。

## 6. 引用路径(lint 核验对象)
```cited-paths
internal/util/nio/GridNioRecoveryDescriptor.java
internal/util/nio/GridNioMessageTracker.java
internal/util/nio/GridNioBackPressureControl.java
internal/util/nio/GridNioServer.java
internal/util/nio/GridSelectorNioSessionImpl.java
internal/util/nio/ssl/GridNioSslFilter.java
```

---
**工时**:⭐⭐⭐⭐⭐ / 7~10 天(Phase 1 难度峰)  **产出物**:`NioServer` v3(recovery + 双重背压 + SSL 槽)—— NIO 子系统完成
