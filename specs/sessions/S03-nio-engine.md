# S03 · 教学文档:NIO 引擎 v1(单 worker + 会话 + 长度前缀帧)

> **Phase 1 · 异步 NIO 引擎** · 本 Session = NIO 子系统的 **v1(最小可运行)**
> 驱动输入:`specs/phases/P01-nio-analysis.md`(全貌已讲清;本文档聚焦 v1 切片,更细)
> 代码工程:`ignite-gogogo/s03-nio-engine/`(本 Session 创建:父 pom + `core` 子模块)

---

## 1. 教学目标
学完本 Session,你应当能够:
- 用纯 JDK NIO 手写一个**单 selector worker** 的异步服务器(accept + read + write 主循环)
- 实现 **`GridNioSession` 等价**的会话对象(每连接读写缓冲 + meta)
- 实现**长度前缀帧**(4 字节大端长度 + 载荷),正确处理粘包/半包
- 讲清 Ignite 为什么用"**pull-based 写 + OP_WRITE 按需开**",而不是"`send` 直接写 socket"

## 2. 本 Session 在全局的位置
- **上游**:承接 **S2**(Selector / ByteBuffer / 线程池热身已具备)
- **下游**:解锁 **S4**(多 worker + 过滤链)、**S5**(recovery + 背压);本 v1 是后续 **Communication(S20)** 的传输根
- 依赖 DAG:`roadmap.md` 中 S3 入边 = S2,出边 = S4/S5
- **不在本 Session**:多 worker、过滤链、recovery、背压、SSL(均留 S4/S5)

## 3. 前置回顾
- **S2**:单线程 Selector echo 原型 —— 本 Session 把它"产品化"(可复用会话 + 正确帧 + pull-based 写)
- **S1**:Maven 工程 —— 本 Session 在 `s03-nio-engine/` 建父 pom + `core` 子模块,Java 包根 `org.apache.ignite.learning.internal.util.nio`

## 4. 核心概念与设计

```
                 ┌──────────── NioServer(单 worker)────────────┐
   新连接 ─accept─►│  Selector 主循环:select → 遍历 ready keys    │
                 │   isAcceptable → accept → register(OP_READ)  │
                 │   isReadable   → read(ses)  → FrameCodec → listener.onMessage
                 │   isWritable   → write(ses) → channel.write  │
                 └──────────────────────────────────────────────┘
   任意线程: ses.send(msg) ──► 入 ses.queue + 置 needWrite + selector.wakeup()
                                          (pull-based:不直接写 socket)
```

- **单 selector worker 模型**:一个线程 + 一个 `Selector`,所有已连接会话注册在它上面。(Ignite 用单独的 `GridNioAcceptWorker` 做 accept,v1 可简化为同一 worker。)
- **`NioSession`**:每连接一个会话对象,持有读写缓冲、meta(数组,非 Map)、关闭状态。**同一会话的所有 IO 由其 owning worker 线程串行处理 → 该会话内无锁**。
- **长度前缀帧**:每条消息 = `[4 字节大端长度][载荷]`;接收侧维护"先凑齐 4 字节长度 → 再按长度凑齐载荷"状态机。
- **pull-based 写**:`send(msg)` 只入队 + 标记需要写;真正 `channel.write` 在 worker 线程、由 `OP_WRITE` 就绪驱动。

## 5. 关键原理(为什么)

- **为什么单 worker 先做**:隔离并发复杂度,先把"selector 主循环 + 会话 + 帧"吃透;多 worker 是 S4 的增量。
- **为什么写是 pull-based(`send0` 不直接写)**:
  ① 保证 channel 只被 owning worker 线程访问(线程安全,免加锁);
  ② OS 通过 `buf.remaining()>0` 自然背压(socket 缓冲满时 write 返回 < remaining);
  ③ 可用 `procWrite` CAS 合并多次 `send` 为一次 worker 唤醒。
  若 `send` 直接 `channel.write`,要么加锁、要么有线程安全问题。
- **长度前缀如何解决粘包/半包**(小演算):
  发送 `[00 00 00 05][H E L L O][00 00 00 03][A B C]`;
  接收可能一次到 `[00 00 00 05][HELLO][00 00]`(第三条长度只到一半)或两条粘在一次 read 里。
  状态机:**① 凑齐 4 字节长度 → ② 按长度凑齐载荷 → ③ 切出一条消息 → ④ 继续循环**。参 Ignite `GridNioServerBuffer.read()`。

## 6. Ignite 源码导读(`file:line`,2.18.0)

1. `GridNioServer.Builder` + 构造器 + `start()`(`nio/GridNioServer.java`:builder :513、ctor :332、start :520)—— 看装配
2. `GridNioAcceptWorker.body()/accept()`(:3067 / :3124)—— accept 如何把新 socket 投递给 client worker(`addRegistrationRequest`)
3. `AbstractNioClientWorker.bodyInternal()`(:2082)—— **selector 主循环**(spin 几次 `selectNow` → 阻塞 `select`)
4. `ByteBufferNioClientWorker.processRead()`(:1187 → :1235 调 `onMessageReceived`)与 `processWrite()`(:1255 → `pollFuture`)—— v1 读到这里即可(过滤链 S4 细化,先直连 listener)
5. `GridNioServer.send / send0`(:642 / :673)+ `registerWrite`(:2386)/ `stopPollingForWrite`(:1134)—— 写入队 + 按需开/关 `OP_WRITE`
6. `GridNioSessionImpl`(:40,meta 数组 :42)+ `GridSelectorNioSessionImpl`(:53,queue :55、procWrite :87)—— 会话状态
7. `GridBufferedParser`(:36,encode :73)+ `GridNioServerBuffer.read()`(:70-115)—— **长度前缀帧**

## 7. 实现步骤(v1;v2/v3 留 S4/S5)

> v1 目标:两个 JVM 用你的 `NioServer` 互发长度前缀消息并 echo 回去。

1. **工程骨架**:`s03-nio-engine/`(父 pom + `core` 子模块,JUnit5;包 `org.apache.ignite.learning.internal.util.nio`)
2. **`NioSession`**:读写缓冲、`send(byte[])`、`close()`、meta 数组、`accepted()`
3. **`FrameCodec`**:length-prefix encode/decode;decode 用状态机,一次返回 0~N 条消息
4. **`NioServer`**(单 worker):Selector 主循环;`accept`→`register(OP_READ)`;`processRead`→`FrameCodec.decode`→`listener.onMessage`;`send`→入队 + 置 `needWrite` + `wakeup`;`processWrite`→`channel.write`,队列空则关 `OP_WRITE`
5. **echo demo + 单测**

```java
// 主循环(单 worker)
while (!stopped) {
    selector.select(timeout);
    for (SelectionKey k : selector.selectedKeys()) {
        if (k.isAcceptable()) accept();              // -> register(OP_READ) + new NioSession
        if (k.isReadable())  read((NioSession) k.attachment());
        if (k.isWritable())  write((NioSession) k.attachment());
    }
}
// send(可在任意线程调用)
void send(NioSession s, byte[] msg) {
    s.queue.offer(FrameCodec.encode(msg));           // 入队,不写 socket
    s.needWrite = true;
    selector.wakeup();                               // pull-based:交给 worker 线程写
}
```

## 8. 常见陷阱

- **`OP_WRITE` 常开会烧 CPU**:`OP_WRITE` 几乎总是就绪,常开会让 `select` 空转。**只在队列非空时开、写空就关**(Ignite:`registerWrite`/`stopPollingForWrite`)。
- **粘包/半包**:别假设"一次 read = 一条消息";必须用状态机帧解码器。
- **跨线程访问 channel**:`send` 可能在非 worker 线程调用——**千万别在那直接 `channel.write`**;只入队 + `wakeup`,写交给 worker(Ignite 的 pull-based 正为此)。
- **DirectByteBuffer vs heap**:v1 用 heap `ByteBuffer` 即可;direct 留到性能优化(S5+)。

## 9. 验收与自测

- **可运行 demo**:两 JVM,client 发若干消息 → server echo 回 → client 收齐且顺序正确。
- **单元测试**:`FrameCodecTest`——粘包(多条拼一次到达)、半包(一条分多次到达)、空载荷、大载荷;`NioServerTest`——echo 往返。
- **自测题**:
  1. 为什么 `send` 不能直接 `channel.write`?
  2. `OP_WRITE` 为什么要按需开关?
  3. 长度前缀相比分隔符帧,优劣各是什么?
  4. 单 worker 的瓶颈在哪?(→ 引出 S4 多 worker)

## 10. 与 Ignite 对照
> 本 Session 不触发里程碑,简单对照即可:同等 echo 负载下对比你的 v1 与 Ignite 单连接的吞吐/延迟,记录差距。差距主要来自:Ignite 用 direct 模式 + `SelectedSelectionKeySet` 反射优化 + 多 worker——**这些正是 S4/S5 要补的**。

---
**预估难度 / 工时**:⭐⭐⭐ / 3~5 天
**产出物**:`NioServer` v1(单 worker + 会话 + 长度前缀帧,可 echo)
