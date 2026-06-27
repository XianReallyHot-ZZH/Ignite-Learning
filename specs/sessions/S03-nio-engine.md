# S03 · 执行规格:NIO 引擎 v1(单 worker + 会话 + 长度前缀帧)

> **Phase 1 · NIO · v1** · 通向 M2(本 session 仅 NIO 基座,不触发里程碑)
> 执行约束规格(瘦)。**教学法见 `docs-learn/S03-nio-engine.md`**。
> **SoT**:范围/顺序看 roadmap S3 块;拆分看 `P01-nio-analysis.md` §6;本规格 = 细化 + 契约 + 验收。
> 代码 `ignite-gogogo/s03-nio-engine/`(本 session 创建)。lint:`scripts/check-cited-paths.sh`。

## 1. 范围与位置
- **roadmap S 块**:Session S3(权威范围/前置/实现要点/验收)。
- **phase §6 行**:P01 §6 · S3 = **v1**。
- **本 session 做**:单 selector worker 的 `NioServer`(accept+read+write)、`NioSession`、长度前缀 `FrameCodec`、`NioServerListener`;pull-based 写、`OP_WRITE` 按需开关。
- **本 session 不做**:多 worker、过滤链、recovery、背压、SSL(S4/S5)。
- **前置**:S2(Selector/ByteBuffer 热身)、S1(Maven 骨架)。

## 2. 对外接口契约
> DAG 出边:S3 → S4 / S5 / S20。下游复用的 public 契约:
| 类型/方法 | 签名 / 语义 | 供下游 session |
|---|---|---|
| `FrameCodec.encode(byte[])` / `FrameCodec.Decoder.decode(ByteBuffer)` | 长度前缀帧 `[4B 大端长度][载荷]`;decode 状态机处理粘包/半包 | S4(`CodecFilter` 直接复用)、S5、S20 |
| `NioServerListener` | `onConnected` / `onMessage(NioSession, byte[])` / `onDisconnected` | S4 / S5 / S20(listener 契约不变) |
| (实现,**非**跨 session 契约)`NioServer` / `NioSession` 单 worker 版 | — | S4 **重写**为多 worker;不作契约,改动不需回填下游 |

## 3. Ignite 源码导读(`file:line`,2.18.0)
1. 装配:`GridNioServer.Builder` + ctor + `start()`(`internal/util/nio/GridNioServer.java`:513 / :332 / :520)
2. accept→注册:`GridNioAcceptWorker`(:3067)、`AbstractNioClientWorker.bodyInternal()`(:2082,selector 主循环)
3. 读写:`ByteBufferNioClientWorker.processRead()`(:1187 → :1235)、`processWrite()`(:1255)、`send/send0`(:642 / :673)、`registerWrite`/`stopPollingForWrite`(:2386 / :1134)
4. 会话:`GridNioSessionImpl`(:40)、`GridSelectorNioSessionImpl`(:53)
5. 帧:`GridBufferedParser`(:36)、`GridNioServerBuffer.read()`(:70)
> 注:`GridNioAcceptWorker`/`AbstractNioClientWorker`/`ByteBufferNioClientWorker` 是 `GridNioServer.java` 的**内部类**(非独立文件)。

## 4. 实现步骤(v1)
1. 工程骨架 `s03-nio-engine/`(父 pom + `core`;包 `org.apache.ignite.learning.internal.util.nio`)
2. `NioSession`(读写缓冲、`send(byte[])`、`close()`、meta)
3. `FrameCodec`(length-prefix encode;`Decoder` 状态机,一次返回 0~N 条消息)
4. `NioServer`(单 worker):Selector 主循环;`accept`→`register(OP_READ)`;`read`→`decode`→`listener.onMessage`;`send`→入队+`wakeup`;`write`→`channel.write`,队列空则关 `OP_WRITE`
5. echo demo + 测试

```java
void send(NioSession s, byte[] msg) {
    s.writeQueue().offer(FrameCodec.encode(msg));   // 入队,不写 socket
    s.needWrite = true; selector.wakeup();           // pull-based:写交给 worker 线程
}
```

## 5. 验收 = 具名测试
| 验收点 | 测试 |
|---|---|
| 帧往返 | `FrameCodecTest#roundtripSingle` |
| 粘包(多条拼一次到达) | `FrameCodecTest#stickyPackets` |
| 半包(一条分多次到达) | `FrameCodecTest#halfPacketsAcrossCalls` |
| 空载荷 | `FrameCodecTest#emptyPayload` |
| 逐字节极端分片 | `FrameCodecTest#byteByByteFeed` |
| 端到端 echo 往返 | `NioServerEchoTest#echoRoundtrip` |
- demo:两 JVM echo,顺序正确。

## 6. 引用路径(lint 核验对象)
```cited-paths
internal/util/nio/GridNioServer.java
internal/util/nio/GridNioServerListener.java
internal/util/nio/GridNioSessionImpl.java
internal/util/nio/GridSelectorNioSessionImpl.java
internal/util/nio/GridBufferedParser.java
internal/util/nio/GridNioServerBuffer.java
```

---
**工时**:⭐⭐⭐ / 3~5 天  **产出物**:`NioServer` v1(单 worker + 会话 + 长度前缀帧,可 echo)
