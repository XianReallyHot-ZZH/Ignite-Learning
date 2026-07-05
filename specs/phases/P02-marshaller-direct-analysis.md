# Phase 2 · 源码分析:Direct 消息编解码 + Marshaller(镜像 `internal/direct/` + `marshaller/`)

> 本文档是 **phase 源码分析**层产物(见 roadmap §3 文档体系),为 Phase 2 的 Session 执行规格(S6~S7)提供 grounded 输入。
> 参考实现:Direct 部分在 `vendors/ignite/modules/core`(镜像包 `internal/direct/` + `plugin/extensions/communication/`);Marshaller 部分在 **2.18.0 已迁出 core**,落在独立模块 `modules/binary/{api,impl}`(详见 §1 的「现实校准」)。
> 行号为 2.18.0 锚点,供对照阅读,非强约束。

---

## 1. 概览

Phase 2 解决一个根本问题:**节点之间在网上传的到底是什么字节?** 答案是两套**各司其职**的编解码子系统:

- **Direct(`internal/direct/`)**:Ignite **自研的固定协议消息编解码**。每条协议消息实现 `Message` 接口、声明一个 2 字节 `directType`,字段**按声明顺序、自描述地**逐个读写(原语/数组/String/UUID/嵌套 Message),不用 Java 序列化。它是 Phase 1 NIO 引擎之上的**传输层 framing**。
- **Marshaller(`marshaller/` SPI)**:**任意用户对象**的通用序列化(缓存值、计算闭包、discovery 自定义数据……)。对象↔`byte[]`,反射驱动 + 元数据缓存提速。

**二者在 `GridIoManager` 汇合**:信封(envelope,topic/ordered/plc…)经 **Direct** 编码;用户载荷(payload)先经 **Marshaller** 变成 `byte[]`,再作为 Direct 消息的一个 `writeByteArray` 字段传输。⇒ **Direct = 传输层 framing,Marshaller = 载荷层对象编解码**,永不重叠(§3.3)。

**为什么排在路线的这个位置**:Phase 2 在 Phase 1(NIO 引擎,S3~S5,**消息帧已就绪**)之后——Direct 是从「S4 的 `byte[]` 载荷」升级到「结构化 `Message` 载荷」的那一步(§3.1 的 seam 即 `GridDirectParser`);Marshaller 又紧随其后,让载荷能承载任意对象。二者都在集群(Discovery/Communication,S18~S21)**之前**——通信层既出,就同时需要 Direct(信封)和 Marshaller(载荷)。关联依赖锚点:本 phase 无集群依赖,纯字节编解码,可独立单测。

- **覆盖 Session**:S6(Direct 编解码 v1)、S7(Marshaller v2)。
- **本 phase 明确不做**(边界,详见 §4 与 deferred.md):
  - **Direct**:codegen `MessageSerializer` + `@Order` 注解处理器(2.18.0 新写法,编译期生成,v1 用手写 `writeTo`/`readFrom`);跨 partial-read 的完整 resume 状态机(`DirectByteBufferStream` 的 `tmpArrOff`/`arrOff`/`prim`/`uuidState` 等逐字段游标——v1 假定消息在 buffer 内完整);`CacheObject`/`KeyCacheObject`/`AffinityTopologyVersion`/`IgniteCacheObjectProcessor` 这些把 `internal/direct` 向上耦合到 cache 子系统的一等公民字段类型(v1 裁剪)。
  - **Marshaller**:binary-object 协议(`BinaryObjectImpl`,2.18.0 的现代默认 marshaller,是另一套巨大子系统,不在本 phase);集群级 `MarshallerContextImpl` 的 mapping transport / peer class loading(无集群阶段无意义);`sun.misc.Unsafe.allocateInstance` 绕过构造器反序列化(危险,学习版用反射或 `Objenesis`-式简化);安全过滤器 `IgniteObjectInputFilter`/`IgniteMarshallerClassFilter`(JEP 290);`.NET`/C++ 跨平台 interop(`DOTNET_ID`)。

> **现实校准(重要,别按记忆写)**:roadmap / package-layout 假设 Marshaller 在 `core/.../internal/marshaller/`,但 **2.18.0 已把它从 core 迁出**:公共 SPI(`Marshaller`/`AbstractMarshaller`/`MarshallerContext`/`Marshallers`/`MarshallersFactory`/`OptimizedMarshaller` 配置接口/`JdkMarshaller`)在 `modules/binary/api`;实现(`OptimizedMarshallerImpl`/`OptimizedObject*Stream`/`OptimizedClassDescriptor`/`JdkMarshallerImpl`)在 `modules/binary/impl`,经 Java `ServiceLoader` 装配。core 里只剩 `MarshallerContextImpl`(集群注册表实现)、`MarshallerUtils`、安全过滤器、和一个**空占位** `internal/marshaller/optimized/package-info.java`。**学习版 S7 仍按 package-layout §2.3 把它放在 `learning/internal/marshaller/`**,镜像的是 **SPI 契约 + 经典 Optimized 实现的形态**(独立、可单测),而非 binary-module 拆分或 BinaryObject 协议。

---

## 2. 核心类与包清单

### 2.1 Direct 公共契约(`plugin/extensions/communication/`)
| 类 | 职责 | 锚点 |
|---|---|---|
| `Message` | 所有协议消息的根接口:`DIRECT_TYPE_SIZE=2` + `directType():short`;`writeTo`/`readFrom` 在 2.18.0 是 **`@Deprecated` 默认抛 `UnsupportedOperationException` 的兜底方法**(新消息走 codegen `MessageSerializer`) | `plugin/extensions/communication/Message.java`(:25,`DIRECT_TYPE_SIZE` :27,`directType` :60) |
| `MessageWriter` | 写侧契约:`writeByte/Short/Int/Long/Float/Double/Char/Boolean/ByteArray/.../String/UUID/Message/CacheObject/...`,每个返回 `boolean lastFinished`;带 `writeHeader`/`state()`/`incrementState()`/`beforeInnerMessageWrite` | `MessageWriter.java`(:38) |
| `MessageReader` | 读侧契约:`readXxx()` + `isLastRead()` + `state()/incrementState()` + `beforeInnerMessageRead/afterInnerMessageRead` | `MessageReader.java`(:38) |
| `MessageSerializer` | **2.18.0 的真编解码钩子**:`writeTo(Message, MessageWriter)`/`readFrom(Message, MessageReader)`;新消息 codegen 生成,旧消息由 `DEFAULT_SERIALIZER` 桥接回 `Message.writeTo/readFrom` | `MessageSerializer.java`(:23) |
| `MessageFactory` | 类型注册表契约:`register(short, Supplier)` / `register(short, Supplier, MessageSerializer)` / `create(short)` / `serializer(short)` | `MessageFactory.java`(:26) |
| `MessageCollectionItemType` | 数组/集合/Map 的多态元素类型标签(驱动 `write(type,val,...)` 分发) | `MessageCollectionItemType.java` |

### 2.2 Direct 实现(`internal/direct/`)
| 类 | 职责 | 锚点 |
|---|---|---|
| `DirectMessageWriter` | `implements MessageWriter`:薄 facade,每个 `writeXxx` 路由到底层 `DirectByteBufferStream` 并返回 `stream.lastFinished()` | `internal/direct/DirectMessageWriter.java`(:46,`writeHeader`→`stream.writeShort` :80) |
| `DirectMessageReader` | `implements MessageReader`:薄 facade,持有 `DirectMessageState`(嵌套消息读栈)+ `lastRead` 标志;构造需 `MessageFactory` + `IgniteCacheObjectProcessor` | `internal/direct/DirectMessageReader.java`(:47,构造 :62,`isLastRead` :407) |
| `DirectByteBufferStream` | **真正的字节引擎**:对每种字段类型直接读写 `ByteBuffer`(经 `GridUnsafe`);持有全部 resume 游标(`tmpArrOff`/`arrOff`/`prim`/`uuidState`/`cacheObjState`/`topVerState`…) | `internal/direct/stream/DirectByteBufferStream.java`(:63,`writeInt` varint+zigzag :431,`writeByteArray`+`writeArrayLength` :562/:1838,`writeString` UTF-8 :687,`writeUuid` :711,`writeMessage` 嵌套 :883,`readMessage` :1522,`lastFinished` :313) |
| `DirectMessageState<T>` / `DirectMessageStateItem` | 嵌套消息读/写的可增长栈(`forward/backward/reset`),让父消息读到一半切到子消息再切回 | `internal/direct/state/DirectMessageState.java`(:29)、`DirectMessageStateItem.java`(:23) |

### 2.3 类型注册表(`internal/managers/communication/`)
| 类 | 职责 | 锚点 |
|---|---|---|
| `IgniteMessageFactoryImpl` | 运行时注册表:**两个 `2^16` 数组**,以 `(short)directType + 32768` 为下标(故负 type 合法)映射到 `Supplier<Message>` 与 `MessageSerializer`;`DEFAULT_SERIALIZER` 把 v1 消息桥接回 `msg.writeTo/readFrom` | `IgniteMessageFactoryImpl.java`(:38,`OFF=32768` :40,数组 :69/:72,构造→`registerAll` :91,`create` :138,`serializer` :152,`DEFAULT_SERIALIZER` :46-66) |
| `GridIoMessageFactory` | core 模块的 `MessageFactoryProvider`:`registerAll` 巨型块,注册 core 的全部消息(混用 codegen 三参 与 v1 两参 两种形态) | `GridIoMessageFactory.java`(:328,`registerAll` :330) |
| `GridIoMessage` | **v1 手写样例**(directType=8):`writeTo`/`readFrom` 是 `switch(state)` + **case 贯穿**(无 break)的状态机——`writeXxx` 返回 false 即跳出、下次同 state 重入 | `GridIoMessage.java`(:36,`writeTo` :188,`readFrom` :253,`directType` :327) |
| `HandshakeMessage` | **codegen 样例**(directType=-3):无 `writeTo`/`readFrom`,字段标 `@Order(N)`,由注解处理器生成 `HandshakeMessageSerializer` | `spi/communication/tcp/messages/HandshakeMessage.java`(:29,`@Order` 字段 :37/:41/:45/:49) |
| `NodeIdMessage` | 同 codegen 形态(directType=-1,单字段 `UUID nodeId`) | `spi/communication/tcp/messages/NodeIdMessage.java`(:29) |
| `TcpCommunicationSpi` | 预留 type ID 常量(`NODE_ID_MSG_TYPE=-1`/`RECOVERY_LAST_ID_MSG_TYPE=-2`/`HANDSHAKE_MSG_TYPE=-3`)+ `makeMessageType(b0,b1)`(2 字节→short) | `spi/communication/tcp/TcpCommunicationSpi.java`(:268/:271/:274/:1183) |

### 2.4 Marshaller 公共 SPI(`modules/binary/api/.../marshaller/`)
| 类 | 职责 | 锚点 |
|---|---|---|
| `Marshaller` | **SPI 契约**:`marshal(Object,OutputStream)`/`marshal(Object):byte[]`/`unmarshal(InputStream,ClassLoader)`/`unmarshal(byte[],ClassLoader)`,全抛 `IgniteCheckedException` | `modules/binary/api/.../marshaller/Marshaller.java`(:68,marshal :91/:100,unmarshal :112/:123) |
| `AbstractMarshaller` | 基类:仅持 `MarshallerContext ctx` + `DFLT_BUFFER_SIZE=512` 常量(类名→ID 映射已移入 `OptimizedMarshallerImpl.clsMap`) | `AbstractMarshaller.java`(:26) |
| `AbstractNodeNameAwareMarshaller` | 模板方法基类:包裹每次调用以 set/restore `currentIgniteName`,使反序列化代码能调 `Ignition.localIgnite()` | `AbstractNodeNameAwareMarshaller.java`(:29,抽象 `marshal0/unmarshal0` :101/:110) |
| `MarshallerContext` | **集群级 `(platformId,typeId)↔className` 注册表**契约:`registerClassName`/`getClass(typeId,ldr)`/`getClassName`/`isSystemType`/`jdkMarshaller()` | `MarshallerContext.java`(:27) |
| `MarshallerExclusions` | 决定某类是否跳过序列化(序列化为 `null`):`INCL_CLASSES`/`EXCL_CLASSES` 两集 + 有界缓存(原 `GridMarshallerExclusions`) | `MarshallerExclusions.java`(:28,`isExcluded` :83) |
| `Marshallers` / `MarshallersFactory` | 静态门面 `Marshallers.jdk()/optimized()`;经 `ServiceLoader` 找 `MarshallersFactory` | `Marshallers.java`(:37,:60/:73)、`MarshallersFactory.java`(:35) |
| `JdkMarshaller`(api)/ `OptimizedMarshaller`(api) | 公共**配置接口**(2.18.0 已非类):`JdkMarshaller` 是空 marker;`OptimizedMarshaller` 暴露 `setRequireSerializable`/`setIdMapper`/`setPoolSize` | `.../marshaller/jdk/JdkMarshaller.java`(:25)、`.../internal/marshaller/optimized/OptimizedMarshaller.java`(:72) |

### 2.5 Marshaller 实现(`modules/binary/impl/.../internal/marshaller/optimized/` + `.../marshaller/jdk/`)
| 类 | 职责 | 锚点 |
|---|---|---|
| `OptimizedMarshallerImpl` | Optimized 实现:持有每 marshaller 的 `clsMap`(Class→`OptimizedClassDescriptor`)+ 流池;每次 `marshal0/unmarshal0` 借一个流、挂上 clsMap+ctx+mapper、委托 `writeObject/readObject` | `OptimizedMarshallerImpl.java`(:84,`clsMap` :95,`registry` :98,`marshal0(Object,OutputStream)` :148,`unmarshal0` :207,`requireSer=true` :89) |
| `OptimizedClassDescriptor` | **元数据缓存条目**(原 `OptimizedMetadata`):每类一次预计算 `type` 标签字节 / `FieldInfo`(缓存 `GridUnsafe.objectFieldOffset`)/ 反射 `writeObject`·`readObject`·`writeReplace`·`readResolve` / SHA-1 截断 `checksum` / `typeId`;持有按类型分发的 `write/read` | `OptimizedClassDescriptor.java`(:97,构造分发 :181,字段 `fields` :144,`checksum` :117,`write` :640,`read` :865,`verifyChecksum` :892) |
| `OptimizedFieldType` | `FieldInfo` 内驱动 Unsafe 读/写的小枚举:`BYTE/SHORT/INT/LONG/FLOAT/DOUBLE/CHAR/BOOLEAN/OTHER` | `OptimizedFieldType.java`(:23) |
| `OptimizedMarshallerUtils` | 类型标签常量(`NULL=0`/`BYTE=1`…`HANDLE=-1`/`JDK=-2`/`ENUM=100`/`EXTERNALIZABLE=101`/`SERIALIZABLE=102`)+ `classDescriptor` 双向查找 + `resolveTypeId` + `computeSerialVersionUid` + Unsafe 包装 | `OptimizedMarshallerUtils.java`(:45,常量 :50-152,`classDescriptor(by Class)` :193,`resolveTypeId` :236) |
| `OptimizedObjectOutputStream` | `extends ObjectOutputStream`:`writeObject0` 按 `desc.type` 标签分发;持 `GridHandleTable`(环/去重 back-ref)+ `GridDataOutput`;`Throwable`/`Enum` 走 JDK 兜底 | `OptimizedObjectOutputStream.java`(:67,`writeObject0` :177,`writeFields` Unsafe :485) |
| `OptimizedObjectInputStream` | `extends ObjectInputStream`:`readObject0` 按同样标签分发;持 `HandleTable`;`readSerializable` 用 **`GridUnsafe.allocateInstance`** 绕过构造器 | `OptimizedObjectInputStream.java`(:102,`readObject0` :222,`resolveClass` :543,`readSerializable` :602) |
| `OptimizedObjectStreamRegistry` + `Shared/Pooled` | 流池:默认 `Shared`(per-thread 复用一个流,可重入);`Pooled`(`poolSize>0`)用两个 `LinkedBlockingQueue` 限界 | `OptimizedObjectStreamRegistry.java`(:29)、`OptimizedObjectSharedStreamRegistry.java`(:25)、`OptimizedObjectPooledStreamRegistry.java`(:28) |
| `JdkMarshallerImpl` | JDK 兜底实现:字面 `new ObjectOutputStream` + `writeObject`,及逆过程;`replaceObject` 经 `MarshallerExclusions` | `.../marshaller/jdk/JdkMarshallerImpl.java`(:68,`marshal0` :89,`unmarshal0` :114) |

### 2.6 集群注册表(core)+ 消费者边界(下游,Phase 2 只读不实现)
| 类 | 职责 | 锚点 |
|---|---|---|
| `MarshallerContextImpl` | `MarshallerContext` 实现:per-platform `ConcurrentMap<typeId,MappedName>` 缓存 + 文件存储 + `MarshallerMappingTransport`(集群级 propose/accept,**Phase 2 略读**) | `modules/core/.../internal/MarshallerContextImpl.java`(:80,`registerClassName` :303,`getClass` :432) |
| `GridDirectParser` | **Phase-1→Phase-2 seam**(已在 P01 §2.4 出现):读 2 字节 type → `msgFactory.create` → 查 `MessageSerializer` → `readFrom`,未读完把部分 `Message` 暂存 session meta | `internal/util/nio/GridDirectParser.java`(:37,`decode` :68,`MSG_META_KEY`/`READER_META_KEY` :39/:42) |
| `GridIoManager`(消费者) | 在此 **Direct 信封 + Marshaller 载荷 汇合**(§3.3) | `internal/managers/communication/GridIoManager.java`(`U.marshal`/`U.unmarshal` 调用点 :1206/:1253/:1980…) |

---

## 3. 关键数据/控制流 trace

### 3.1 Direct 编/解码往返(经 `GridDirectParser` 与 Phase 1 的 seam)
```
[wire→app 解码]  worker: OP_READ → processRead(读字节进 readBuf)
 → filterChain.onMessageReceived → GridDirectParser.decode(GridDirectParser.java:68)
   ├─ 取/建 session 内缓存的 DirectMessageReader(READER_META_KEY)
   ├─ 无部分消息且 buf≥2 字节:读 2 字节 → makeMessageType(b0,b1) → msgFactory.create(type)
   ├─ msgFactory.serializer(type) → reader.setBuffer(buf) → msgSer.readFrom(msg, reader)
   │     └─ (v1 消息) DEFAULT_SERIALIZER 桥接 → msg.readFrom(buf, reader)
   │           └─ reader.readXxx() → DirectByteBufferStream.readXxx → GridUnsafe 直读 ByteBuffer
   │                 嵌套消息:readMessage 读子消息的 2 字节 type → 递归 serializer.readFrom(beforeInnerMessageRead 压栈)
   └─ finished? 否 → 部分消息暂存 MSG_META_KEY,返回 null 等下次;是 → reader.reset() 返回 Message

[app→wire 编码]  send 路径取 session 的 DirectMessageWriter → setBuffer(buf)
 → msgSer.writeTo(msg, writer) → (v1) msg.writeTo(buf, writer)
   ├─ writer.writeHeader(directType()) → DirectByteBufferStream.writeShort(2 字节 type,仅一次,经 isHeaderWritten 门控)
   └─ switch(state): writer.writeXxx(...) → DirectByteBufferStream → GridUnsafe 直写 ByteBuffer
       writeXxx 返回 false → 本次 buffer 满,发出去、 refill、同 state 重入(游标保留)
```
**要点**:生产协议**无总长度前缀**——前 2 字节是消息 type,消息体**自描述**(每字段读取器只读自己需要的字节),靠 `state()` 计数器 + `lastFinished` 标志实现**跨 partial-read 的逐字段 resume**,零中间拷贝。学习版 v1 假定消息在 buffer 内完整(由 S3~S5 长度前缀帧装配),把 resume 机制列为 out-of-scope。

### 3.2 Marshaller marshal/unmarshal(对象↔字节)
```
[marshal]  OptimizedMarshallerImpl.marshal0(obj, out)(OptimizedMarshallerImpl.java:148)
 → registry.out() 借线程局部/池化 OptimizedObjectOutputStream → context(clsMap, ctx, mapper, requireSer)
 → writeObject(obj) → writeObjectOverride → writeObject0(:177)
   ├─ obj==null → writeByte(NULL)
   ├─ Throwable/Enum → writeByte(JDK) → ctx.jdkMarshaller().marshal(兜底,绕 JVM 已知坑)
   ├─ desc = classDescriptor(clsMap, cls, …)(:202)  // 命中 clsMap 元数据缓存;miss 则 ctx.registerClassName 注册
   ├─ desc.excluded() → writeByte(NULL)             // MarshallerExclusions
   ├─ obj0 = desc.replace(obj)                      // writeReplace 钩子
   ├─ handle = handles.putIfAbsent(obj) ≥0 → writeByte(HANDLE)+writeInt(handle)  // 已写过,back-ref 去重
   └─ desc.write(this, obj0) → writeByte(type) + 按类型分支
         SERIALIZABLE:writeTypeData(typeId[, className]) + writeShort(checksum) + writeFields(Unsafe 逐字段 / 用户 writeObject)

[unmarshal]  OptimizedObjectInputStream.readObject0()(:222)
 → ref = readByte()  // 读标签
   ├─ NULL→null; HANDLE→handles.lookup(readInt()); JDK→ctx.jdkMarshaller().unmarshal
   ├─ 原语/数组/STR/UUID/集合 → 直接构造
   └─ ENUM/EXTERNALIZABLE/SERIALIZABLE:typeId=readInt()(==0 则 readUTF(className))
         desc = classDescriptor(clsMap, typeId, ldr, …)  // ctx.getClass(typeId) 反查 Class
         desc.read(this) → verifyChecksum(readShort) + readSerializable(GridUnsafe.allocateInstance 免构造器 + 逐字段 / 用户 readObject)
```
**要点**:与 `java.io.ObjectOutputStream` 的核心差异——① 元数据(`OptimizedClassDescriptor`)在 `clsMap` 里**跨调用缓存**,线上只传 `(typeId, checksum)` 而非完整类描述符;② 字段读写用**缓存的 Unsafe 偏移**直取,免 `Field.get` 反射开销;③ 独立 `HandleTable` 做环/去重;④ 标签字节前缀每个值;⑤ `Serializable` 反序列化用 `Unsafe.allocateInstance` **绕过所有构造器**。

### 3.3 二者在 `GridIoManager` 汇合(Direct 信封 + Marshaller 载荷)—— Phase 2 的灵魂
```
发送方:  userObj(任意对象)
 → U.marshal(marsh, userObj) → byte[] payload         // Marshaller:对象→byte[](GridIoManager.java:1980…)
 → new GridIoMessage(topic, payload, plc, …)           // byte[] 成为 Direct 消息的一个字段
 → GridIoMessage.writeTo(writer):writeMessage+writeByteArray(payload)+…  // Direct:逐字段编码
 → 经 TcpCommunicationSpi/NioServer 上网

接收方:  GridDirectParser 解出 GridIoMessage(Direct 解码)
 → GridIoMessage.readFrom 拿回 payload byte[]
 → U.unmarshal(marsh, payload, ldr) → userObj          // Marshaller:byte[]→对象
```
**要点**:**Direct 永远不知道用户对象长什么样**——它只看到 `byte[]`。用户对象的知识完全在 Marshaller 侧。这条 seam 让「固定协议」与「任意载荷」解耦,二者可独立演化、独立测试,正是 Phase 2 拆成两个 session 的依据。

---

## 4. 关键设计与算法(为什么这么设计)

1. **自描述字段流 + state 计数器(无总长度前缀)**:消息体不在外层包长度,而是**逐字段、按声明顺序、自描述**。每个 `readXxx/writeXxx` 返回 `lastFinished`,配合消息内的 `switch(state)` 计数器,可在**任意 NIO 读边界**处挂起、续读,**零中间缓冲**——一字节直接从 socket `ByteBuffer` 落到字段最终位置。学习版 v1 假定消息完整入 buffer,简化掉 resume。
2. **2 字节 `directType`(short)+ 数组注册表**:`Message.DIRECT_TYPE_SIZE=2`,65 536 个类型槽(Ignite 全家用了约 250 个);`IgniteMessageFactoryImpl` 用 `directType+32768` 下标两个 `2^16` 数组,**O(1)、无反射、无 Map 查找**,且能容纳负 type(`NODE_ID=-1` 等);`MessageFactory` 仅注册期可写,建成后 `register` 抛异常。
3. **varint + zigzag 编码 int/long**:`writeInt/readInt` 用 base-128 varint + `MAX↔MIN` 特判(Ignite 的 zigzag-1,非 protobuf 的 `(v<<1)^(v>>31)`)。让小数值(数组长度、ordinal、timeout)压成 1 字节——因为每个数组/集合/Map 都先写长度 varint。
4. **Direct vs Marshaller 分工(传输 framing vs 载荷对象编解码)**:Direct 处理**固定、编译期已知形状**的协议消息(快、零开销、白名单 type);Marshaller 处理**任意、运行期才知形状**的用户对象(反射 + 元数据缓存)。二者在 `GridIoManager` 以 `byte[]` 字段 seam 汇合(§3.3),**永不重叠**。⇒ Phase 2 拆 S6/S7 的根本依据。
5. **`OptimizedClassDescriptor` 元数据缓存(原 `OptimizedMetadata`)**:每 Java 类一次预计算——`type` 标签字节、按名排序的 `FieldInfo`(各缓存 `GridUnsafe.objectFieldOffset`)、按超类收集的 `writeObject/readObject`、`writeReplace/readResolve`、SHA-1 截断 `checksum`。序列化时只查缓存,**免 JDK 的 per-stream `ObjectStreamClass` 重建 + 免 `Field.get` 反射**,号称约 20× 于 JDK 默认(见 `OptimizedMarshaller.java:24-31` Javadoc)。这是 Optimized 的核心提速。
6. **`typeId` + `checksum`(线传哈希而非全类描述符)**:线格式只带 4 字节 `typeId`(`className.hashCode()` 或 `IdMapper` 覆盖),`typeId==0` 时才回退写全类名;`checksum`(显式 `serialVersionUID` 或 SHA-1 截断)在 read 时 `verifyChecksum` 防版本漂移。读侧经 `MarshallerContext.getClass(typeId,ldr)` 反查 Class。
7. **`HandleTable`(环/重复对象去重)**:写侧 `GridHandleTable`、读侧 `HandleTable`,把已写对象记成 back-ref(`HANDLE` 标签 + int 下标),对象图里的环与重复引用安全且省空间。
8. **`Unsafe.allocateInstance` 绕过构造器 + `requireSerializable` 兜底**:`readSerializable` 用 `Unsafe.allocateInstance(cls)` **不调用任何构造器**地实例化(因为 Java 「找首个非 Serializable 祖先的无参构造器」规则对 Ignite 内部类常不可满足)。但**免构造器会跳过不变量初始化**——故 `requireSerializable=true` 默认强制 `Serializable`,非 Serializable 显式 `setRequireSerializable(false)` 才放行。学习版因 Unsafe 危险,改用反射或简化策略,并把这点列入 deferred。
9. **`MarshallerExclusions`(跳过 Ignite 内部类)**:`MBeanServer`/`ExecutorService`/`ClassLoader`/`Thread`/Spring `ApplicationContext`/`IgniteLogger`/`ComputeTaskSession`/`Marshaller` 自身等被排除(序列化为 `null`),`GridLoggerProxy`/`GridExecutorService` 被 `include` 保留。`INCL_CLASSES` 优先于 `EXCL_CLASSES`。避免把整个 Ignite 内核图意外拖进序列化流。
10. **可插拔 SPI(`Marshaller` 接口 + `ServiceLoader` 工厂)**:`IgniteConfiguration.setMarshaller(Marshaller)` 接受任意实现;`MarshallersFactory` 经 `ServiceLoader` 装配,第三方发行版可替换。2.18.0 默认是 binary marshaller(BinaryObject),`Optimized`/`Jdk` 是显式配置的备选——学习版只复现 SPI 契约 + Optimized + Jdk 对照。

---

## 5. 依赖与边界

- **Direct 上游**:**JDK** + `plugin/extensions/communication`(Message 全家)+ `internal/util`(`GridUnsafe` + endian 处理)。**注意(现实校准)**:真实 `internal/direct` 还**向上耦合** `internal.processors.cache.{CacheObject,KeyCacheObject}`、`internal.processors.affinity.AffinityTopologyVersion`、`internal.processors.cacheobject.IgniteCacheObjectProcessor`(作一等公民字段类型,`DirectByteBufferStream` 直接 `writeCacheObject`/`writeAffinityTopologyVersion`)。⇒ 学习版 v1 **裁剪**这些,只支持原语/数组/String/UUID/嵌套 Message,把 Direct 退回**纯 JDK + Message** 边界,cache-object 字段列为 out-of-scope(下游 cache session 真用到时再补)。
- **Direct 下游/消费者**:`GridDirectParser`(Phase 1 seam,已在 P01)、`TcpCommunicationSpi`、`GridIoManager`(信封)。
- **Marshaller 上游**:**纯 JDK** + `internal/util`(`GridUnsafe`、`GridHandleTable`、`GridDataInput/Output`)+ `MarshallerContext` **接口**。grep 确认 `internal/marshaller/optimized/` **不依赖** `internal.direct`/`internal.util.nio`/`internal.processors.{cache,cluster,communication}`。⇒ **可独立单测**(依赖锚点:Marshaller ⊥ Direct/NIO)。其 `MarshallerContextImpl` 实现(在 core)确触及集群 transport,但那是单向注入,学习版用进程内 context 即可。
- **Marshaller 下游/消费者**:`GridIoManager`(payload byte[],§3.3)、`IgniteCacheObjectProcessor`、discovery/compute/checkpoint SPI 等。
- **契约**:Direct 对外是 `Message`/`MessageWriter`/`MessageReader`/`MessageFactory`;Marshaller 对外是 `Marshaller` 接口(`marshal/unmarshal`)+ `MarshallerContext`。二者均不认 `ClusterNode`/cache 实体。

---

## 6. 拆成 Session 的依据(S6 / S7)

按 **"隔离度 × 复杂度"递增**,每步都有可运行产物 + 单测。注意:Phase 2 含**两个独立子系统**(Direct、Marshaller),非单一子系统的 v1→v2→v3 阶梯;v 级标记该 session 的**目标保真度**。

| Session | v级 | 范围(本 phase 内) | 镜像要点 | 可运行验收 |
|---|---|---|---|---|
| **S6** | v1 | **Direct 编解码 v1**:`Message` 接口(`directType`/`writeTo`/`readFrom`)+ **类型注册表**(`MessageFactory`,short↔工厂,+32768 数组)+ `MessageWriter/Reader`(原语/数组/String/UUID/嵌套 Message,逐字段、带 state 计数器);**经 S4 NioServer 收发自定义 `PingMessage`**(由 S3~S5 长度前缀帧装配完整消息,v1 不做跨 partial-read resume) | `Message`/`MessageWriter`/`MessageReader`/`DirectByteBufferStream`/`IgniteMessageFactoryImpl`/`GridDirectParser` seam;`GridIoMessage` 的 `switch(state)` 手写模式 | 两个 JVM 经 NioServer 收发结构化消息;单测覆盖多种字段类型往返一致性(粘包/半包由 Phase 1 帧兜底) |
| **S7** | v2 | **Marshaller v2**:可插拔 `Marshaller` SPI 接口 + **`OptimizedMarshaller`**(对象↔`byte[]`:原语/数组/String/嵌套对象/`Serializable`,带 `OptimizedClassDescriptor` 元数据缓存 + handle 表)+ **进程内 `MarshallerContext`**(typeId↔Class,无集群 transport)+ `JdkMarshaller` 对照 | `Marshaller`/`AbstractMarshaller`/`OptimizedMarshallerImpl`/`OptimizedObject*Stream`/`OptimizedClassDescriptor`/`MarshallerContextImpl`(只取进程内注册表部分) | 单测覆盖对象往返 + 嵌套/环 + **体积对比 Java 序列化**;与 S6 分工验证(用户对象经 Marshaller→byte[]→Direct 字段) |

**为什么这么切**:S6 是 v1——Direct 的**最小可运行**(注册表 + 字段流编解码 + 一条消息往返);Ignite 的「自描述字段流 + 跨 partial-read resume + codegen serializer + CacheObject 字段」是性能/工程核心,逐项列为 out-of-scope/deferred。S7 是 v2——Marshaller 的**功能完整**(可插拔 SPI + 优化 impl + 元数据缓存);集群级 context transport、BinaryObject、`Unsafe.allocateInstance` 列为 out-of-scope(无集群阶段无意义 / Unsafe 危险 / 另一套巨大子系统)。S6 先行因它是 Marshaller 载荷的**传输载体**(§3.3 seam),且依赖 S4(消息帧);二者加起来 = Phase 2 全部。**任一步停下都有可运行成果**——符合北辰式 + 保真阶梯。

---

## 7. 源码阅读路线(由外到内,由简到难)

**Direct 侧:**
1. `Message` 接口 —— 先看契约(`DIRECT_TYPE_SIZE`/`directType`,及 `writeTo`/`readFrom` 已 `@Deprecated` 的现实)
2. `MessageWriter` / `MessageReader` —— 字段读写 API 全集(每个返回 `lastFinished`/`isLastRead`)
3. `DirectByteBufferStream` —— 底层字节引擎(看 `writeInt` varint+zigzag、`writeByteArray`+`writeArrayLength`、`writeMessage` 嵌套)
4. `DirectMessageWriter` / `DirectMessageReader` —— 薄 facade + `DirectMessageState` 嵌套栈
5. `IgniteMessageFactoryImpl` —— 类型注册表(`+32768` 数组、`DEFAULT_SERIALIZER` 桥接)
6. `GridIoMessage.writeTo`/`readFrom` —— v1 手写 `switch(state)` 贯穿样例(照着写自己的 `PingMessage`)
7. `GridDirectParser.decode` —— Phase-1→Phase-2 seam(已在 P01 读过,这里重读 wiring)
8. `HandshakeMessage` + `@Order` —— codegen 形态(选读,了解迁移方向)

**Marshaller 侧:**
9. `Marshaller` 接口 + `AbstractMarshaller` —— SPI 契约(`marshal`/`unmarshal` 全抛 `IgniteCheckedException`)
10. `OptimizedMarshallerImpl.marshal0`/`unmarshal0` —— 借流→挂 context→委托的骨架
11. `OptimizedObjectOutputStream.writeObject0` / `OptimizedObjectInputStream.readObject0` —— 标签分发的编解码核心
12. `OptimizedClassDescriptor` —— 元数据缓存灵魂(构造期 type 分发、`FieldInfo` Unsafe 偏移、`checksum`)
13. `JdkMarshallerImpl` —— 对照(`ObjectOutputStream` 兜底,理解 Optimized 省了什么)
14. `MarshallerContextImpl` —— 注册表(选读;集群 transport 部分 Phase 2 略)

---

## 8. 自检

- [x] **引用路径**:`scripts/check-cited-paths.sh` 对本文档全 OK(39 条,见 §10 附录)。
- [x] **依赖主张与锚点一致**:Direct 依赖 Phase 1 NIO seam(`GridDirectParser`);Marshaller ⊥ Direct/NIO/cache/cluster(纯 JDK + utils + context 接口)——与 roadmap 依赖锚点一致,二者均在集群之前、可独立单测。
- [x] **§6 每个 session 标了 v 级**(S6=v1 / S7=v2)。
- [x] **覆盖 S6/S7**:二者加起来 = Phase 2 全部(Direct 编解码 + Marshaller)。
- [x] **每步可运行可测**:S6 经 NioServer 收发结构化消息 / S7 对象往返 + 体积对比。
- [x] **CS 学生曲线**:S6 用手写 `writeTo`/`readFrom`(非 codegen)降低门槛;S7 先 SPI 接口再 Optimized 实现;resume/Unsafe/集群 context 逐项 deferred,无单点过载。
- [x] **现实校准已标注**:Marshaller 在 2.18.0 已迁出 core 到 `modules/binary`(§1),学习版按 package-layout 镜像 SPI 形态。

## 9. 修订记录

> session 代码若证伪本分析(发现真实结构与文中不符),在此回填并就地修正正文。
- (初始为空)

## 10. 引用路径(lint 核验对象)

```cited-paths
internal/direct/DirectMessageReader.java
internal/direct/DirectMessageWriter.java
internal/direct/stream/DirectByteBufferStream.java
internal/direct/state/DirectMessageState.java
internal/direct/state/DirectMessageStateItem.java
plugin/extensions/communication/Message.java
plugin/extensions/communication/MessageWriter.java
plugin/extensions/communication/MessageReader.java
plugin/extensions/communication/MessageSerializer.java
plugin/extensions/communication/MessageFactory.java
plugin/extensions/communication/MessageFactoryProvider.java
plugin/extensions/communication/MessageCollectionItemType.java
internal/managers/communication/IgniteMessageFactoryImpl.java
internal/managers/communication/GridIoMessageFactory.java
internal/managers/communication/GridIoMessage.java
internal/util/nio/GridDirectParser.java
spi/communication/tcp/messages/HandshakeMessage.java
spi/communication/tcp/messages/NodeIdMessage.java
spi/communication/tcp/TcpCommunicationSpi.java
modules/binary/api/src/main/java/org/apache/ignite/marshaller/Marshaller.java
modules/binary/api/src/main/java/org/apache/ignite/marshaller/AbstractMarshaller.java
modules/binary/api/src/main/java/org/apache/ignite/marshaller/AbstractNodeNameAwareMarshaller.java
modules/binary/api/src/main/java/org/apache/ignite/marshaller/MarshallerContext.java
modules/binary/api/src/main/java/org/apache/ignite/marshaller/MarshallerExclusions.java
modules/binary/api/src/main/java/org/apache/ignite/marshaller/Marshallers.java
modules/binary/api/src/main/java/org/apache/ignite/marshaller/MarshallersFactory.java
modules/binary/api/src/main/java/org/apache/ignite/marshaller/jdk/JdkMarshaller.java
modules/binary/api/src/main/java/org/apache/ignite/internal/marshaller/optimized/OptimizedMarshaller.java
modules/binary/impl/src/main/java/org/apache/ignite/marshaller/jdk/JdkMarshallerImpl.java
modules/binary/impl/src/main/java/org/apache/ignite/internal/marshaller/optimized/OptimizedMarshallerImpl.java
modules/binary/impl/src/main/java/org/apache/ignite/internal/marshaller/optimized/OptimizedClassDescriptor.java
modules/binary/impl/src/main/java/org/apache/ignite/internal/marshaller/optimized/OptimizedFieldType.java
modules/binary/impl/src/main/java/org/apache/ignite/internal/marshaller/optimized/OptimizedMarshallerUtils.java
modules/binary/impl/src/main/java/org/apache/ignite/internal/marshaller/optimized/OptimizedObjectInputStream.java
modules/binary/impl/src/main/java/org/apache/ignite/internal/marshaller/optimized/OptimizedObjectOutputStream.java
modules/binary/impl/src/main/java/org/apache/ignite/internal/marshaller/optimized/OptimizedObjectStreamRegistry.java
modules/binary/impl/src/main/java/org/apache/ignite/internal/marshaller/optimized/OptimizedObjectSharedStreamRegistry.java
modules/core/src/main/java/org/apache/ignite/marshaller/MarshallerUtils.java
modules/core/src/main/java/org/apache/ignite/internal/MarshallerContextImpl.java
```

> 写完后:据此**顺序产出**各 session 执行规格(`specs/sessions/S06-direct-codec.md`、`S07-marshaller.md`,从 `_TEMPLATE-spec.md`),并在 roadmap 对应 S6/S7 块挂 `**执行规格**:` 链接。
