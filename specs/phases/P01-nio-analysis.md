# Phase 1 · 源码分析:NIO 引擎(镜像 `internal/util/nio/`)

> 本文档是 **phase 源码分析**层产物(见 roadmap §3 文档体系),为 Phase 1 的 Session 教学文档(S3~S5)提供 grounded 输入。
> 参考实现:`vendors/ignite/modules/core/src/main/java/org/apache/ignite/internal/util/nio/`(下称 `nio/`)。
> **所有引用路径/类名均已核验存在**(25 条 OK,见 §8)。行号为 2.18.0 锚点,供对照阅读,非强约束。

---

## 1. 概览

Ignite 的网络底座是一个**自研的异步 NIO 框架**(非 Netty):基于 `java.nio` Selector,以 **selector worker 线程 + 双向过滤链 + 会话** 组织。节点间通信(`TcpCommunicationSpi`)、客户端协议都建在它之上;**但它对集群/compute/cache 一无所知**——这正是它能作为**第一个独立子系统**先行构建的原因(见依赖锚点:持久化隔离、NIO 是通信根)。

- **覆盖 Session**:S3(NIO v1:单 worker + 会话 + 帧)、S4(v2:多 worker + 过滤链)、S5(v3:recovery + 背压)。
- **本 phase 不做**:SSL 的完整实现(S5 只留接口)、slow-client 策略(属消费者 SPI 侧)、direct-mode 的全部细节(S3/S4 先用 ByteBuffer 模式更易懂)。

---

## 2. 核心类与包清单

### 2.1 服务端核心
| 类 | 职责 | 锚点 |
|---|---|---|
| `GridNioServer<T>` | 异步 NIO 服务器:builder 构造;accept/connect;读写管道入口 | `nio/GridNioServer.java`(class :115,builder :513/:3824,`send0` :673,`register` :2712) |
| `GridNioServerListener<T>` | 消费者注册的监听器(`onMessage`/`onConnected`/`onDisconnected`/`onMessageSent`) | `nio/GridNioServerListener.java` |

### 2.2 Worker 线程模型
| 类 | 职责 | 锚点 |
|---|---|---|
| `GridNioAcceptWorker` | **单线程**,独占一个 Selector,**只 accept**;兼跑负载均衡器 | `GridNioServer.java`(:3033) |
| `AbstractNioClientWorker` | 选择器 worker:每实例独占一个 Selector,**同时跑 read+write** | `GridNioServer.java`(:1878) |
| `ByteBufferNioClientWorker` / `DirectNioClientWorker` | 两种 worker 子类(按 `directMode` 选) | `GridNioServer.java`(:1155 / :1331) |
| `GridNioWorker` | worker 的极简接口(`offer`/`clearSessionRequests`) | `nio/GridNioWorker.java`(:27) |
| `offerBalanced(...)` | 会话→worker 的分配(轮询;可选奇偶读/写分离) | `GridNioServer.java`(:1086) |

### 2.3 会话
| 类 | 职责 | 锚点 |
|---|---|---|
| `GridNioSession` | 会话接口(地址、字节计数、meta、send/close、暂停/恢复读) | `nio/GridNioSession.java`(:31) |
| `GridNioSessionImpl` | 基类:**meta 是数组(枚举序号)而非 Map**;关闭 CAS | `nio/GridNioSessionImpl.java`(:40,meta :42) |
| `GridSelectorNioSessionImpl` | 绑 selector 的实现:写队列 `queue`、背压 `sem`、`procWrite`、`key`、`worker` | `nio/GridSelectorNioSessionImpl.java`(:53,queue :55,sem :66,procWrite :87) |
| `GridNioKeyAttachment` | `SelectionKey` 附件接口(同一对象即附件) | `nio/GridNioKeyAttachment.java` |

### 2.4 过滤链与编解码
| 类 | 职责 | 锚点 |
|---|---|---|
| `GridNioFilter` / `GridNioFilterAdapter` | 过滤器契约:`onXxx`(收事件)/ `proceedXxx`(转发);**双向**(inbound 走 `prevFilter` 向 app,outbound 走 `nextFilter` 向 wire) | `nio/GridNioFilter.java`、`GridNioFilterAdapter.java`(:86-158) |
| `GridNioFilterChain` | 双向链:`TailFilter`(=listener,sink)← filters ← `HeadFilter`(靠 wire) | `nio/GridNioFilterChain.java`(:30,TailFilter :242) |
| `HeadFilter` | 链尾(wire 一侧,写入口;direct 模式触发 `send`) | `GridNioServer.java`(:3729) |
| `GridNioCodecFilter` | 协议过滤,内部包一个 `GridNioParser` | `nio/GridNioCodecFilter.java`(:34) |
| `GridNioParser` | `decode(ByteBuffer)`/`encode(msg)` 接口 | `nio/GridNioParser.java`(:32) |
| `GridBufferedParser` | **4 字节长度前缀**帧,解码出 `byte[]`(简单协议) | `nio/GridBufferedParser.java`(:36) |
| `GridDirectParser` | **2 字节 direct type + 自描述字段**,解码出 `Message`(**生产用**,无长度前缀) | `nio/GridDirectParser.java`(:37) |
| `GridNioServerBuffer` | 长度前缀的增量读取缓冲 | `nio/GridNioServerBuffer.java`(:70) |

### 2.5 Recovery
| 类 | 职责 | 锚点 |
|---|---|---|
| `GridNioRecoveryDescriptor` | 每节点每连接的恢复状态:单调计数器(`sentCnt`/`acked`/`rcvCnt`)+ 有界未确认队列 `msgReqs`;`onHandshake` 对齐;`release` 保留队列待重发 | `nio/GridNioRecoveryDescriptor.java`(:38,`onHandshake` :315,`ackReceived` :214) |
| `GridNioServer.resend(ses)` | 新会话注册后重放未确认消息 | `GridNioServer.java`(:749) |

### 2.6 背压
| 类 | 职责 | 锚点 |
|---|---|---|
| `GridSelectorNioSessionImpl.sem` | **发送端**:信号量(队列满则阻塞生产者) | `GridSelectorNioSessionImpl.java`(:66) |
| `GridNioMessageTracker` | **接收端**:按未处理消息数暂停/恢复 `OP_READ` | `nio/GridNioMessageTracker.java`(:104 暂停 / :58 恢复) |
| `GridNioBackPressureControl` | `ThreadLocal`:message-thread 旁路信号量,防死锁 | `nio/GridNioBackPressureControl.java`(:28) |

### 2.7 SSL
| 类 | 职责 | 锚点 |
|---|---|---|
| `GridNioSslFilter` / `GridNioSslHandler` | SSL/TLS 过滤,包 `SSLEngine`;位于 codec 与 wire 之间,只处理 `ByteBuffer` | `nio/ssl/GridNioSslFilter.java`(:48) |

### 2.8 消费者边界(下游,Phase 1 只读不实现)
| 类 | 职责 | 锚点 |
|---|---|---|
| `TcpCommunicationSpi` | 通信 SPI:**拥有** `GridNioServer` | `spi/communication/tcp/TcpCommunicationSpi.java` |
| `GridNioServerWrapper` | 建 `GridNioServer` + 注册 listener/filters + 连接池 + handshake | `spi/communication/tcp/internal/GridNioServerWrapper.java`(build :934,filters :910,handshake :498) |
| `ConnectionKey` | `(nodeId, connIdx)` 连接键 | `spi/communication/tcp/internal/ConnectionKey.java` |
| `Message` | 流经过滤链的消息接口 | `plugin/extensions/communication/Message.java` |

---

## 3. 关键数据/控制流 trace

### 3.1 发送一条消息(app → wire)
```
app: session.send(msg)
 → GridNioFilterChain.onSessionWrite(...)        // 从 tail 进入,沿 nextFilter 向 wire
 → [GridNioCodecFilter: encode 成 ByteBuffer]     // 非直连模式
 → [GridNioSslFilter: encrypt]                    // 若启用 SSL
 → HeadFilter.onSessionWrite → GridNioServer.send → send0   // GridNioServer.java:642/673
 → ses.offerFuture(req) + CAS procWrite=true → 给 worker 投递 REQUIRE_WRITE
 → worker 主循环:OP_WRITE 就绪 → processWrite → pollFuture
 → (direct 模式) writeToBuffer: MessageSerializer.writeTo 序列化 → channel.write
 → onMessageSent 回调
```
**要点**:`send0` **不直接写 socket**,只入队 + 唤醒 worker;真正 `socketChannel.write` 在** owning worker 线程**完成 → channel 单线程访问,天然线程安全。

### 3.2 接收一条消息(wire → app)
```
worker: OP_READ 就绪 → processRead(读字节进 readBuf)
 → filterChain.onMessageReceived(ses, readBuf)    // 从 head 进入,沿 prevFilter 向 app
 → GridNioSslFilter: unwrap 成明文 ByteBuffer
 → GridNioCodecFilter: 循环 parser.decode
     ├─ GridDirectParser: 读 2 字节 direct type → msgFactory.create(type)
     │   → DirectMessageReader 逐字段 readFrom 直到完成(不完整则存 session meta 等下一块)
     └─ 返回 Message
 → TailFilter.onMessageReceived → lsnr.onMessage(ses, msg)
```
**要点**:生产协议**无长度前缀**——前 2 字节是消息类型,消息体**自描述**(每个字段读取器只读自己需要的字节)。

### 3.3 断线重连重发(可靠性核心)
```
连接断开 → recoveryDesc.release():connected=false
   ├─ 节点已离开:drain msgReqs,逐条 fail("node left")
   └─ 节点仍活:保留 msgReqs(未确认消息)待重发
重连 → handshake:双方交换 rcvCnt(已收数)
 → recoveryDesc.onHandshake(rcvCnt):丢弃 ≤ rcvCnt 的已确认部分,设 resendCnt = 剩余
 → 新会话注册 → GridNioServer.resend(ses):把 msgReqs 里剩余消息改绑新 session 后重发
```
**要点**:**没有 per-message 去重集**——单调递增的计数器(`sentCnt`/`acked`/`rcvCnt`)就是去重机制;握手把双方对齐到最后共同序列号。

---

## 4. 关键设计与算法(为什么这么设计)

1. **单线程-per-session**:同一 worker 串行处理该 session 的 read+write → 该 session 内**无锁**;代价是慢写会阻塞同 session 的读 → 用 `NioOperation.MOVE` 把"热"会话迁移到别的 worker(`SizeBasedBalancer` 在 accept 线程跑)。
2. **写是 pull-based**:`send0` 只入队 + 投递 `REQUIRE_WRITE`;真正 `write` 由 `OP_WRITE` 就绪驱动。保证所有 socket IO 都在 owning selector 线程,且 OS 通过 `buf.remaining()>0` 自然背压。
3. **`procWrite` 合并**:一个 `AtomicBoolean` 把多次 `send0` 合并成**一次** worker 唤醒(CAS false→true 仅赢一次),减少 wakeup。
4. **过滤链双向**:inbound(`onXxx`/`prevFilter`)走向 app,outbound(`proceedXxx`/`nextFilter`)走向 wire;`TailFilter` 是 inbound 终点(→ listener),`HeadFilter` 是 outbound 终点(→ wire)。这让 codec/SSL/tracer 可任意插拔。
5. **两种帧格式可配**:`GridBufferedParser`(4 字节长度前缀 + `byte[]`,简单)vs `GridDirectParser`(2 字节 type + 自描述 `Message`,生产)。**学习时先用前者**(S3),生产语义留到对照。
6. **Recovery = 单调计数器 + 有界队列**:不存去重集合,靠握手对齐 + 重放未确认;`queueLimit` 溢出直接触发重连(`pollFuture` 里 `outRecovery.add` 返回 false → `close()`)。
7. **双重背压**:发送端信号量 `sem`(`sndQueueLimit`,队列满阻塞生产者)+ 接收端 `GridNioMessageTracker`(未处理消息达上限 → `pauseReads` 关 `OP_READ`,处理完 → `resumeReads`)。`GridNioBackPressureControl` 让"正在处理消息的线程"回复时**旁路**信号量,避免自死锁。
8. **meta 用数组(枚举序号)**:零分配、无装箱,热路径上频繁用(SSL handler、recovery desc、写缓冲等)。
9. **反射替换 selector 内部 selectedKeys**(`SelectedSelectionKeySet`,Netty 式):消除 `HashSet` 迭代分配,说明这是**性能调优过**的实现,非教科书 NIO。

---

## 5. 依赖与边界

- **上游依赖**:**无**。只依赖 `java.nio` + `internal/util`(线程/并发原语)。不依赖集群/通信/缓存/持久化任何东西。⇒ **可最先独立构建并单测**(依赖锚点:NIO 是通信根,持久化隔离)。
- **下游/消费者**:`TcpCommunicationSpi` 经 `GridNioServerWrapper` 拥有它——注册 `GridNioServerListener`、装过滤链(`GridNioCodecFilter(GridDirectParser)` + `GridConnectionBytesVerifyFilter` + 可选 `GridNioSslFilter`/`GridNioTracerFilter`)、维护 `(nodeId,connIdx)` 连接池、跑 handshake 并接 `GridNioRecoveryDescriptor`。
- **契约**:`GridNioServer` 只认 `Message`/`GridNioSession`/filter/`GridNioServerListener`,不认 `ClusterNode`(`GridNioRecoveryDescriptor` 为日志/拓扑检查取了 `ClusterNode`,但 server 主体是泛型的)。

---

## 6. 拆成 Session 的依据(S3 / S4 / S5)

按 **"隔离度 × 复杂度"递增**,每步都有可运行产物 + 单测:

| Session | 范围(本 phase 内) | 镜像要点 | 可运行验收 |
|---|---|---|---|
| **S3 v1** | 单 selector worker + `GridNioSession` + **长度前缀帧**(`GridBufferedParser` 路线) | `GridNioServer` 核心、单 worker、`processRead/Write`、简单 codec | 两个 JVM echo,单测覆盖粘包/半包 |
| **S4 v2** | 多 worker(`offerBalanced` 轮询/奇偶)+ **完整过滤链**(`GridNioFilterChain` 双向 + `HeadFilter`/`TailFilter`) | worker 模型、filter chain 双向、可插拔 filter | 多连接并发压测不串号 |
| **S5 v3** | **Recovery**(`GridNioRecoveryDescriptor` 计数器去重 + `resend`)+ **双重背压**(发送信号量 + 接收 `GridNioMessageTracker` 暂停/恢复)+ SSL 留接口 | recovery 状态机、背压两端 | 断线重连后消息不丢不重 |

**为什么这么切**:v1 给"最小可运行"(先吃透 selector+会话+帧);v2 引入并发与可扩展性(worker 模型 + 过滤链);v3 引入可靠性(recovery + 背压)。每一步都是上一层的自然深化,且**任一步停下都有可运行成果**——符合北辰式 + 保真阶梯。

---

## 7. 源码阅读路线(由外到内,由简到难)

1. `GridNioServerListener` —— 先看契约(消费者要什么:`onMessage` 等)
2. `GridNioSession` / `GridNioSessionImpl` / `GridSelectorNioSessionImpl` —— 会话状态
3. `GridNioServer.Builder` + 构造器 + `start/stop` —— 装配
4. `GridNioAcceptWorker` + `AbstractNioClientWorker.bodyInternal` —— selector 主循环(spin-then-block select)
5. `processRead` / `processWrite` / `send0` —— 读写路径
6. `GridNioFilterChain` + `GridNioFilter`/`Adapter` + `HeadFilter`/`TailFilter` —— 过滤链
7. `GridNioCodecFilter` + `GridDirectParser`/`GridBufferedParser` —— 编解码
8. `GridNioRecoveryDescriptor` + `resend` —— 恢复
9. `GridSelectorNioSessionImpl.sem` + `GridNioMessageTracker` —— 背压
10. `GridNioSslFilter`(选读)
11. `GridNioServerWrapper`(消费者,选读)—— 看真实装配与 handshake

---

## 8. 自检

- [x] **引用路径全部存在**:本文引用的 25 条路径/类已核验 OK(含 `nio/` 全部新类 + `spi/communication/tcp/internal/` 消费者 + `Message` 接口)。
- [x] **依赖主张与锚点一致**:NIO 无集群依赖,消费者是 SPI(单向)——与 roadmap 依赖锚点"持久化隔离 / NIO 是通信根"一致。
- [x] **覆盖 S3/S4/S5**:§6 给出拆分依据,三者加起来 = Phase 1 全部(NIO v1→v3)。
- [x] **每步可运行可测**:S3 echo / S4 多连接压测 / S5 断线重连不丢不重,均有明确验收。
- [x] **CS 学生曲线**:S3 用最简长度前缀帧(非 direct 协议)降低门槛;复杂度逐层加。

> 本文档同时作为 **phase-analysis 模板**的样例。若形态通过审阅,我将抽取为 `specs/phases/_TEMPLATE-analysis.md`。
