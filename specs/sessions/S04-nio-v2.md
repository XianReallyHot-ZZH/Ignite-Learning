# S04 · 教学文档:NIO 引擎 v2(多 worker + 过滤链)

> **Phase 1 · 异步 NIO 引擎** · 本 Session = NIO 子系统的 **v2(并发 + 可扩展)**
> 驱动输入:`specs/phases/P01-nio-analysis.md`(§2.2 worker 模型、§2.4 过滤链、§4 设计 why、§6 拆分依据)
> 代码工程:`ignite-gogogo/s04-nio-v2/`(从 `s03-nio-engine` 复制后扩展)

---

## 1. 教学目标
学完本 Session,你应当能够:
- 把 v1 的单 worker 拆成 **1 个 AcceptWorker + N 个 ClientWorker**(各一个 Selector),并用 **Balancer** 把新会话分配给某个 worker
- 实现一条**双向过滤链**(`HeadFilter ← filters ← TailFilter`),让"编解码 / 日志 / SSL / 追踪"成为可插拔层
- 讲清两个关键不变量:**同一会话只属于一个 worker**(会话内无锁);**`OP_WRITE`/注册必须在 owning worker 线程做**

## 2. 本 Session 在全局的位置
- **上游**:承接 **S3**(`NioServer` v1:单 worker + 会话 + 长度前缀帧)
- **下游**:解锁 **S5**(recovery + 背压);本 v2 是后续 **Communication(S20)** 的传输根
- 依赖 DAG:S4 入边 = S3,出边 = S5
- **不在本 Session**:recovery、背压、SSL 完整实现、session 迁移(MOVE)——均留 S5 或选做

## 3. 前置回顾
- **S3**:`NioServer`(单 worker,accept+read+write 同一 Selector)、`NioSession`(写队列 + 帧解码器)、`FrameCodec`(长度前缀)、pull-based 写(OP_WRITE 按需开关)
- 本 Session **复制 `s03-nio-engine/` → `s04-nio-v2/`** 作为起点

## 4. 核心概念与设计

### 4.1 多 worker 模型
```
                    ┌─ AcceptWorker(1 线程,独占 Selector,只 accept)
新连接 ─accept─►    │   选 worker = Balancer.roundRobin() ──投递"注册请求"──► ClientWorker[k]
                    └─ ClientWorker[0..N-1]:各独占一个 Selector,跑自己会话的 read+write
```
- **AcceptWorker** 只负责 accept,然后把新 channel 包装成"注册请求"投递给某个 **ClientWorker**(在**该 worker 线程**完成 `register(OP_READ)` + 建 session)。
- **Balancer**:轮询选 worker(Ignite `offerBalanced` 还可选"奇偶读/写分离",v2 先做朴素轮询)。
- **不变量**:一个会话从注册起绑定唯一 worker,它的 read/write 全在那个 worker 线程串行 → **会话内无锁**;并发只在**跨会话**间发生。

### 4.2 过滤链(双向)
```
入站 (wire → app):  bytes ─► [HeadFilter] ─► [CodecFilter] ─► [LogFilter] ─► [TailFilter] ─► listener.onMessage
                                      (沿 prevFilter 向 app)
出站 (app → wire):  listener.send ─► [TailFilter] ─► [LogFilter] ─► [CodecFilter] ─► [HeadFilter] ─► 写队列 ─► worker OP_WRITE
                                                (沿 nextFilter 向 wire)
```
- `HeadFilter`:靠 wire 一侧,**入站起点 / 出站终点**(出站在这里把消息落进写队列)。
- `TailFilter`:靠 app 一侧,**入站终点**(`onMessage` → `listener`)、**出站起点**(`send` 从这进入链)。
- 中间过滤器按需插:`CodecFilter`(字节↔消息)、`LogFilter`(记录)、将来 `SslFilter`/`TracerFilter`。
- 每个 `NioSession` 持有自己的链(v1 的直连 `listener` 被链取代)。

## 5. 关键原理(为什么)

- **为什么多 worker**:单 worker 在连接多 / 单次 read 重时成瓶颈;多 worker 让 IO 并行,而"每会话绑一个 worker"保留了**会话内单线程无锁**的优点——并发在会话之间,不在会话之内。
- **为什么过滤链**:把"编解码 / 日志 / SSL / 追踪"从主循环解耦成**可插拔层**;加新协议层不用改 `NioServer` 核心。**双向**语义让入站(解码、解密)和出站(编码、加密)都能被过滤。
- **为什么 send 要路由到 owning worker**(多 worker 新增难点):`SelectionKey.interestOps` 非线程安全,`OP_WRITE` 的开关、channel 的写,**都必须在 owning worker 线程**做。所以 `send` 入队后要 wake **该会话的** worker(会话记住 `myWorker`),而不是任意 worker——否则 OP_WRITE 永远不被处理。
- **为什么注册也在 worker 线程**:`SocketChannel.register(selector, …)` 必须对着该 worker 的 selector、且 selector 在 select 时才能安全注册;故 accept 后"投递注册请求"给目标 worker,由它在自己线程注册。

## 6. Ignite 源码导读(`file:line`,2.18.0)

1. `GridNioFilter` / `GridNioFilterAdapter`(`nio/GridNioFilter.java`:29;`GridNioFilterAdapter.java`:86-158)—— 过滤器契约 + **双向 proceed**(`proceedXxx` inbound 走 `prevFilter`、outbound 走 `nextFilter`)
2. `GridNioFilterChain`(`nio/GridNioFilterChain.java`:30;内部 `TailFilter` :242)—— 双向链装配:`TailFilter`(=listener)← filters ← `HeadFilter`
3. `HeadFilter`(`GridNioServer.java`:3729)—— wire 一侧,出站终点(把消息交给 `send0` 入队)
4. `offerBalanced(...)`(`GridNioServer.java`:1086)—— 会话→worker 分配(轮询;可选奇偶读/写分离)
5. `AbstractNioClientWorker`(`GridNioServer.java`:1878)+ `GridNioAcceptWorker`(:3033)—— worker 主循环 + accept 线程
6. `GridNioCodecFilter`(`nio/GridNioCodecFilter.java`:34)—— 协议过滤器样例(内部包 `GridNioParser`)
7. (选读)其他过滤器:`GridNioAsyncNotifyFilter.java`(把 listener offload 到线程池)、`GridNioTracerFilter.java`(注入 trace span)、`GridConnectionBytesVerifyFilter.java`(字节统计)

## 7. 实现步骤(v2;从 s03 复制扩展)

> v2 目标:N 个 worker 并行,16 条连接并发 echo 不串号;且"加一个 LogFilter 就能记录每条出入站消息"。

1. **复制** `s03-nio-engine/` → `s04-nio-v2/`,改 artifactId(`s04-nio-v2` / `-core`)、README
2. **拆 worker**:抽出 `ClientWorker`(各持一个 `Selector` + 私有读缓冲 + 自己的会话集);`NioServer` 持 `ClientWorker[] workers` + 一个 accept 线程
3. **Balancer**:`int next = roundRobin(); workers[next].register(channel)` —— `register` 把 channel 包装成"注册请求"投递给该 worker(它在自己线程 `register(OP_READ)` + 建 `NioSession`,`session.myWorker = this`)
4. **Filter 抽象**:`Filter` 接口(`onMessageReceived(ses,msg)`+`proceedMessageReceived`;`onSessionWrite(ses,msg)`+`proceedSessionWrite`)+ `FilterChain`(双向链,`HeadNode`/`TailNode`)+ 每个 `NioSession` 持一条链
5. **改读写路径**:read → `ses.chain.onMessageReceived(buf)`(head→tail→listener);`send` → `ses.chain.onSessionWrite(msg)`(tail→head→`HeadNode` 入写队列 + `ses.myWorker.wakeup()`)
6. **实现两个过滤器**:`CodecFilter`(包 `FrameCodec`:入站 byte[] 解出、出站编成 ByteBuffer)、`LogFilter`(记录每条 in/out,演示可插拔)
7. **验证**:echo 走链;确认 `LogFilter` 打印、`CodecFilter` 正确解编

```java
// AcceptWorker → 选 worker → 投递注册请求(在目标 worker 线程注册)
void accept() throws IOException {
    SocketChannel ch = serverCh.accept();
    ch.configureBlocking(false);
    ClientWorker w = workers[balancer.next()];      // 轮询
    w.submit(() -> w.register(ch));                  // 注册必须在 owning worker 线程
}
// send 路由到 owning worker(出站从 tail 进入链,到 head 入队 + wake 该 worker)
void send(NioSession s, byte[] msg) {
    s.chain().onSessionWrite(s, msg);                // tail → ... → head → s.writeQueue.offer(...)
    s.myWorker().wakeup();                           // 关键:wake owning worker,不是任意 worker
}
```

## 8. 常见陷阱

- **在错误线程注册 channel / 改 interestOps**:`register`、`interestOps` 都必须在 owning worker 线程;accept 线程别直接注册,要"投递注册请求"。
- **send wake 错 worker**:会话的 OP_WRITE 永远不被处理 → 消息卡在队列。`send` 必须 `ses.myWorker().wakeup()`。
- **过滤链顺序错了**:codec 必须在"靠 wire"侧(先把字节变成消息,再给上层逻辑);顺序反了,上层过滤器拿到的是 `ByteBuffer` 而非业务消息。
- **过滤器阻塞 NIO 线程**:inbound 过滤器里别做慢 IO/阻塞;Ignite 用 `GridNioAsyncNotifyFilter` 把 listener offload 到线程池(v2 可选,先不做)。
- **AcceptWorker 单线程**:accept 慢会拖累建连速率;v2 先不优化(选做:accept 也可多线程)。

## 9. 验收与自测

- **可运行 demo**:16 条连接并发向 server 各发若干消息,每条连接的 echo **顺序正确、不串号**;终端能看到 `LogFilter` 记录的出入站。
- **单元测试**:
  - `FilterChainTest`:构造 `Head → A → B → Tail` 双向链,断言 inbound 顺序(`Head→A→B→Tail→listener`)与 outbound 顺序(`listener→Tail→B→A→Head`)。
  - `CodecFilterTest`:经 `CodecFilter` 后 ByteBuffer↔message 正确往返。
  - `MultiWorkerEchoTest`:N≥2 worker + 多连接 echo 正确;并断言"不同连接被分配到了不同 worker"(可在 `NioSession` 暴露 `workerId` 做观测)。
- **自测题**:
  1. 为什么同一会话不能被两个 worker 同时处理?
  2. `send` 为什么要 wake **owning** worker 而非任意 worker?
  3. 过滤链为什么是**双向**的?只做单向会丢什么能力?
  4. 想加一个"统计每连接字节量"的过滤器,该插在链的哪一侧、哪个位置?

## 10. 与 Ignite 对照
> 本 Session 不触发里程碑,简单对照:Ignite `offerBalanced` 支持轮询与"奇偶读/写分离"两种;Ignite 的过滤链常装 `CodecFilter(GridDirectParser)` + `ConnectionBytesVerifyFilter` + 可选 `SslFilter`/`TracerFilter`。差距:Ignite 还有 **session 迁移(MOVE)** 跨 worker 做负载均衡(`SizeBasedBalancer` 在 accept 线程跑)——留作选做。

---
**预估难度 / 工时**:⭐⭐⭐⭐ / 5~7 天
**产出物**:`NioServer` v2(N 个 worker + 可插拔双向过滤链)
