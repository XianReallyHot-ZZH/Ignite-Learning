# S04 · 执行规格:NIO 引擎 v2(多 worker + 过滤链)

> **Phase 1 · NIO · v2** · 通向 M2
> 执行约束规格(瘦)。**教学法见 `docs-learn/S04-nio-v2.md`**。
> **SoT**:范围/顺序看 roadmap S4 块;拆分看 `P01-nio-analysis.md` §6;本规格 = 细化 + 契约 + 验收。
> 代码 `ignite-gogogo/s04-nio-v2/`(从 s03 复制扩展)。lint:`scripts/check-cited-paths.sh`。

## 1. 范围与位置
- **roadmap S 块**:Session S4(权威范围/前置/实现要点/验收)。
- **phase §6 行**:P01 §6 · S4 = **v2**。
- **本 session 做**:1 AcceptWorker + N ClientWorker(各独占 Selector)+ 轮询 Balancer;双向 `FilterChain`(Head/Codec/.../Tail);`CodecFilter` + `LogFilter`(可插拔);send 路由 owning worker。
- **本 session 不做**:recovery、背压、SSL 完整实现、session 迁移 MOVE(S5 / 选做)。
- **前置**:S3(复制 s03 → s04 作为起点)。

## 2. 对外接口契约
> DAG 出边:S4 → S5 / S20。下游复用的 public 契约:
| 类型/方法 | 签名 / 语义 | 供下游 session |
|---|---|---|
| `NioServer(InetSocketAddress, int workerCount, NioServerListener, Supplier<List<Filter>>)` | 多 worker 服务器;`send(NioSession, byte[])` 经链出站 | S5(加 recovery/背压)、S20(Communication 注册 listener + filters) |
| `Filter` / `FilterChain` | 双向过滤链,可插拔;`Filter.onInbound/onOutbound` + `proceedIn/proceedOut` | S5(RecoveryFilter/BackpressureFilter)、S20(消息过滤) |
| `NioSession.chain()` / `myWorker()` / `writeQueue()` / `workerId()` | 会话暴露其链、所属 worker、写队列 | S5(recovery 挂写队列/owning worker)、S20 |
| `CodecFilter`(包 `FrameCodec`) | 长度前缀编解码(复用 S3 的 `FrameCodec`) | S5 / S20 |

## 3. Ignite 源码导读(`file:line`,2.18.0)
1. 过滤器契约:`GridNioFilter`(`:29`) / `GridNioFilterAdapter`(`:86-158`,双向 proceed)
2. 链:`GridNioFilterChain`(`:30`,内部 `TailFilter` :242)+ `HeadFilter`(`GridNioServer.java`:3729)
3. worker 分配:`offerBalanced`(`GridNioServer.java`:1086,轮询/奇偶)
4. worker 模型:`AbstractNioClientWorker`(`:1878`)+ `GridNioAcceptWorker`(`:3033`)(均 `GridNioServer.java` 内部类)
5. 协议过滤样例:`GridNioCodecFilter`(`:34`)
6. (选读)其他过滤器:`GridNioAsyncNotifyFilter`、`GridNioTracerFilter`、`GridConnectionBytesVerifyFilter`

## 4. 实现步骤(v2;从 s03 复制扩展)
1. 复制 `s03-nio-engine/` → `s04-nio-v2/`,改 artifactId(`s04-nio-v2`/`-core`);`rm -rf core/target`
2. 拆 `ClientWorker`(各持一个 Selector + 私有读缓冲 + 会话集);`NioServer` 持 `ClientWorker[]` + accept 线程
3. Balancer 轮询;accept 后把 channel 投递"注册请求"给目标 worker(在其线程 `register(OP_READ)` + 建 `NioSession`,`session.myWorker=this`)
4. `Filter`/`FilterChain`(双向链,Head/Tail);每 `NioSession` 持一条链
5. read → `chain.fireInbound`(head→tail→listener);send → `chain.fireOutbound`(tail→head→HeadFilter 入队 + `myWorker.wakeup()`)
6. 实现 `CodecFilter`(包 `FrameCodec`)+ `LogFilter`(可插拔演示)

```java
void accept() throws IOException {
    SocketChannel ch = serverCh.accept(); ch.configureBlocking(false);
    workers[Math.floorMod(balancer.getAndIncrement(), workers.length)].register(ch); // 在目标 worker 线程注册
}
public void send(NioSession s, byte[] msg) { s.chain().fireOutbound(s, msg); } // → head 入队 + wake owning worker
```

## 5. 验收 = 具名测试
| 验收点 | 测试 |
|---|---|
| 过滤链入站顺序(wire→app) | `FilterChainTest#inboundWalksWireToApp` |
| 过滤链出站顺序(app→wire) | `FilterChainTest#outboundWalksAppToWire` |
| CodecFilter 解码(ByteBuffer→byte[]) | `CodecFilterTest#inboundDecodesBytesToMessage` |
| CodecFilter 编码(byte[]→ByteBuffer) | `CodecFilterTest#outboundEncodesMessageToBytes` |
| 多 worker echo + 跨 worker 分配 | `MultiWorkerEchoTest#multiWorkerEchoAndDistribution` |
| (继承自 S3)帧编解码仍绿 | `FrameCodecTest#*`(5 个) |
- demo:16 连接并发 echo 不串号 + `LogFilter` 记录出入站。

## 6. 引用路径(lint 核验对象)
```cited-paths
internal/util/nio/GridNioServer.java
internal/util/nio/GridNioFilter.java
internal/util/nio/GridNioFilterAdapter.java
internal/util/nio/GridNioFilterChain.java
internal/util/nio/GridNioCodecFilter.java
internal/util/nio/GridNioAsyncNotifyFilter.java
internal/util/nio/GridNioTracerFilter.java
internal/util/nio/GridConnectionBytesVerifyFilter.java
```

---
**工时**:⭐⭐⭐⭐ / 5~7 天  **产出物**:`NioServer` v2(N worker + 可插拔双向过滤链)
