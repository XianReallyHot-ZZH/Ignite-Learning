# S06 · 执行规格:Direct 消息编解码 v1

> **Phase 2 · Direct + Marshaller · v1**
> 执行约束规格(瘦)。**教学法见 `docs-learn/S06-direct-codec.md`**(由 session-code 建后产)。
> **SoT**:范围/顺序看 roadmap S6 块;拆分看 `P02-marshaller-direct-analysis.md` §6;本规格 = 细化 + 契约 + 验收。
> 代码 `ignite-gogogo/s06-direct-codec/`(从 s05 复制扩展)。lint:`scripts/check-cited-paths.sh`。

## 1. 范围与位置
- **roadmap S 块**:Session S6(权威范围/前置/实现要点/验收)。
- **phase §6 行**:P02 §6 · S6 = **v1**。
- **本 session 做**:① **`Message` 接口**(`directType():short` + `writeTo(MessageWriter)` + `readFrom(MessageReader)`,手写、非 codegen);② **类型注册表 `MessageFactory`**(`register(short, Supplier)` / `create(short)`,`+32768` 数组下标,容纳负 type);③ **`MessageWriter`/`MessageReader`** + `DirectMessageWriter`/`DirectMessageReader` + 底层 `DirectByteBufferStream`(原语/数组/String/UUID/嵌套 Message,**逐字段、带 `state()` 计数器**);④ **seam `MessageCodecFilter`**(`byte[]↔Message`,叠在 Phase 1 `CodecFilter` 长度前缀帧之上);⑤ **泛化 `NioServer<T>` / `NioServerListener<T>`**(让结构化 `Message` 流经 NioServer,而非 `byte[]`);⑥ **`PingMessage` demo** + 经 NioServer 两端收发。
- **本 session 不做**(划出):
  - codegen `MessageSerializer` + `@Order` 注解处理器(v1 用手写 `writeTo`/`readFrom`,照 `GridIoMessage`);
  - **跨 partial-read 的 resume 状态机**(`DirectByteBufferStream` 的 `tmpArrOff`/`arrOff`/`uuidState`/`prim` 等逐字段游标)—— v1 由 Phase 1 `FrameCodec` 保证消息在 buffer 内完整,`readFrom` 总一次过(`state()` 计数器保留协议形态,但 `lastFinished` 总为 true);
  - **varint+zigzag 编码 int/long**(v1 用定宽;省字节是 Ignite 优化,deferred);
  - `CacheObject`/`KeyCacheObject`/`AffinityTopologyVersion` 字段类型(把 Ignite `internal/direct` 向上耦合到 cache 子系统,v1 裁剪);
  - `Collection`/`Map` 字段(v1 只支持原语数组 `byte[]/int[]/long[]`);
  - `MessageFormatter` SPI(v1 只一种 reader/writer)。
- **前置 session**:S4(消息帧 `FrameCodec` + 过滤链 `FilterChain`)。工程上从 **s05**(最新)复制扩展。

## 2. 对外接口契约(API contract)
> DAG 出边:**S6 → S7**(Marshaller 载荷以 `writeByteArray` 字段搭车)、**S6 → S20**(Communication 收发 `Message`)、**S6 → S21**(GridIoManager 的 `GridIoMessage` 即一条 `Message`)。下游据此构建;改接口必须回填下游。

| 类型/方法 | 签名 / 语义 | 供下游 session |
|---|---|---|
| `Message` | `interface { short directType(); boolean writeTo(MessageWriter w); boolean readFrom(MessageReader r); }` | S20/S21(每条协议消息实现它) |
| `MessageWriter` | `writeByte/Short/Int/Long/Boolean`、`writeByteArray/IntArray/LongArray`、`writeString`、`writeUuid`、`writeMessage(Message)`、`writeHeader(short)`、`state()/incrementState()`(皆返回 `boolean lastFinished`) | S20/S21(消息写字段) |
| `MessageReader` | 镜像 `readXxx()`(返回值)+ `isLastRead()` + `state()/incrementState()` | S20/S21(消息读字段) |
| `MessageFactory` | `register(short, Supplier<Message>)`、`Message create(short)`(未知 type 抛异常) | S20/S21(注册消息类型;codec 按 type 实例化) |
| `MessageCodecFilter`(seam) | inbound `byte[]→Message`(读 2 字节 type→`create`→`readFrom`);outbound `Message→byte[]`(`writeHeader`+`writeTo`);叠在 `CodecFilter` 帧之上 | S20(装进 NioServer 过滤链收发 Message) |
| `NioServer<T>` / `NioServerListener<T>` | `send(NioSession, T)`、`onMessage(NioSession, T)`(从 Phase 1 的 `byte[]` 泛化) | S20/S21(以 `Message` 为消息单位) |

> **与 S7 的 seam**(P02 §3.3):Marshaller 把用户对象编成 `byte[]`,作为 `MessageWriter.writeByteArray(...)` 字段搭进 Direct 信封。故 `writeByteArray` 是 S7 的依赖契约,本 session 必须提供。

## 3. Ignite 源码导读(`file:line`,2.18.0)
> 复用 P02 已核验锚点。**镜像这些(复现,不 import)**。
1. **`Message` 契约**:`plugin/extensions/communication/Message.java`(:25,`DIRECT_TYPE_SIZE=2` :27,`directType` :60)—— v1 不走其 `@Deprecated` 兜底,直接在手写消息里实现 `writeTo/readFrom`。
2. **字段读写 API**:`MessageWriter.java`(:38)、`MessageReader.java`(:38)—— v1 只实现其中的原语/数组/String/UUID/Message 子集。
3. **字节引擎**:`internal/direct/stream/DirectByteBufferStream.java`(:63,`writeInt` :431、`writeByteArray`+`writeArrayLength` :562/:1838、`writeMessage` 嵌套 :883、`readMessage` :1522)—— v1 简化:定宽原语 + 完整 buffer(无 resume 游标)。
4. **reader/writer facade**:`DirectMessageWriter.java`(:46)、`DirectMessageReader.java`(:47,持 `DirectMessageState` 嵌套栈)。
5. **类型注册表**:`IgniteMessageFactoryImpl.java`(:38,`OFF=32768` :40、数组 :69/:72、`create` :138、`DEFAULT_SERIALIZER` 桥接 :46)—— 照此写 `MessageFactory`(`+32768` 数组、负 type 合法)。
6. **手写消息样例**:`internal/managers/communication/GridIoMessage.java`(:36,`writeTo` :188、`readFrom` :253、`directType=8` :327)—— **照它的 `switch(state)` 贯穿写 `PingMessage`**。
7. **Phase-1 seam**:`internal/util/nio/GridDirectParser.java`(:37,`decode` 读 2 字节 type→`create`→`readFrom` :68)—— 学习版 seam 是 `MessageCodecFilter`,但读 type→create→readFrom 的步骤一致。
- **阅读顺序**:Message → MessageWriter/Reader → DirectByteBufferStream → DirectMessageWriter/Reader → IgniteMessageFactoryImpl → GridIoMessage(照写 PingMessage)→ GridDirectParser(seam 对照)。

## 4. 实现步骤(本 session = v1 级;从 s05 复制扩展)
1. **建工程**:复制 `s05-nio-v3/` → `s06-direct-codec/`,改 artifactId/pom;`rm -rf core/target`。
2. **泛化 NioServer 到 `<T>`**:`NioServerListener<T>`(`onMessage(NioSession, T)`)、`NioServer<T>`(`send(NioSession, T)`、`listener` 字段);`TailFilter` 透传。过滤链 `Filter.onInbound/onOutbound` 已是 `Object`,无需改。现有 echo 测试改用 `NioServerListener<byte[]>` 保持绿(回归 Phase 1)。
3. **新建 `learning/internal/direct/`**:
   - `Message` 接口(`directType`/`writeTo`/`readFrom`);
   - `MessageWriter` 接口 + `DirectMessageWriter`(`ByteBuffer`-backed,每个 `writeXxx` 路由到 stream,返回 `lastFinished`);
   - `MessageReader` 接口 + `DirectMessageReader`(镜像,持 `state` + `lastRead`);
   - `DirectByteBufferStream`:定宽原语(byte/short/int/long/boolean)+ `byte[]/int[]/long[]`(写长度 int + 载荷)+ `String`(UTF-8 `byte[]`)+ `UUID`(1 null 标志 + 16 字节)+ 嵌套 `Message`(先写子消息 2 字节 type,再 `writeTo`/`readFrom`)。**保留 `state()` 计数器 + `lastFinished` 协议形态**,但 v1 buffer 始终完整故总一次过;
   - `MessageFactory`:`Supplier<Message>[]`(大小 `2^16`)+ `register(short, Supplier)` / `create(short)`(下标 `type + 32768`,未知 type 抛 `IllegalStateException`)。
4. **seam `MessageCodecFilter extends Filter`**(nio 包,因 `Filter` 包私有):
   - inbound:`byte[]`(来自 `CodecFilter`)→ 包成 `ByteBuffer` → 读 2 字节 type → `factory.create(type)` → `reader.setBuffer(buf)` → `msg.readFrom(reader)` → `proceedIn(ses, msg)`;
   - outbound:`Message` → `writer.setBuffer` → `msg.writeTo(writer)` → 取出 `byte[]` → `proceedOut(ses, bytes)`。
   - 过滤链装配:`Head → CodecFilter(帧 ByteBuffer↔byte[]) → MessageCodecFilter(byte[]↔Message) → Tail(listener<Message>)`。
5. **`PingMessage`**(`long id`、`String payload`、`byte[] data`,`directType` 自选如 `1`):照 `GridIoMessage` 写 `writeTo`/`readFrom`(`switch(state)` 贯穿 + `incrementState`);在 demo/测试里 `factory.register((short)1, PingMessage::new)`。
6. **demo + 测试**:起两个 `NioServer<Message>`(或同进程两端),一端 `send(ping)`,对端 `listener.onMessage` 收到字段等价的 `PingMessage`。

## 5. 验收 = 具名测试
> `/ignite-session-code` 在 `mvn test` 绿后核验这些具名测试存在且绿。

| 验收点 | 测试 |
|---|---|
| 注册表 register/create 往返;`+32768` shift 容纳负 type;未知 type 抛异常 | `MessageFactoryTest#createByDirectType` |
| reader/writer 原语(byte/short/int/long/boolean)往返一致 | `DirectMessageRoundtripTest#primitiveFieldsRoundtrip` |
| 数组(byte[]/int[]/long[])+ String + UUID 往返一致 | `DirectMessageRoundtripTest#complexFieldsRoundtrip` |
| 嵌套 Message 往返(父消息含子消息字段) | `DirectMessageRoundtripTest#nestedMessageRoundtrip` |
| `PingMessage` 经 NioServer 两端收发,字段等价 | `PingMessageOverNioTest#echoRoundtrip` |
- 可运行 demo:两个 JVM(或两 NioServer)互发 `PingMessage`,对端打印出相同的 `id/payload/data`;回归 Phase 1 echo(byte[] 模式)仍绿。

## 6. 引用路径(lint 核验对象)
```cited-paths
plugin/extensions/communication/Message.java
plugin/extensions/communication/MessageWriter.java
plugin/extensions/communication/MessageReader.java
internal/direct/stream/DirectByteBufferStream.java
internal/direct/DirectMessageWriter.java
internal/direct/DirectMessageReader.java
internal/managers/communication/IgniteMessageFactoryImpl.java
internal/managers/communication/GridIoMessage.java
internal/util/nio/GridDirectParser.java
```

---
**工时**:⭐⭐⭐ / 3~4 天  **产出物**:`DirectMessage` 编解码(接口 + 类型注册表 + reader/writer + NioServer seam)+ `PingMessage` 往返 —— Phase 2 的传输 framing 就位,载荷层(Marshaller)留 S7。
