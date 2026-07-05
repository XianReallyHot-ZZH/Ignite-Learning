# S07 · 执行规格:Marshaller v2

> **Phase 2 · Direct + Marshaller · v2**(Phase 2 收官 —— 本 session 后 Phase 2 完成)
> 执行约束规格(瘦)。**教学法见 `docs-learn/S07-marshaller.md`**(由 session-code 建后产)。
> **SoT**:范围/顺序看 roadmap S7 块;拆分看 `P02-marshaller-direct-analysis.md` §6;本规格 = 细化 + 契约 + 验收。
> 代码 `ignite-gogogo/s07-marshaller/`(从 s06 复制扩展)。lint:`scripts/check-cited-paths.sh`。

## 1. 范围与位置
- **roadmap S 块**:Session S7(权威范围/前置/实现要点/验收)。
- **phase §6 行**:P02 §6 · S7 = **v2**。
- **本 session 做**:① **可插拔 `Marshaller` SPI 接口**(`marshal`/`unmarshal` 对象↔`byte[]`,全抛 `IgniteCheckedException`)+ `AbstractMarshaller` 基类(持 ctx);② **进程内 `MarshallerContext`**(`typeId`↔`className`↔`Class` 注册表,`ConcurrentHashMap`,**无集群 transport / 无文件存储 / 无 peer class loading**);③ **`OptimizedMarshaller`** —— 自定义紧凑二进制格式(type tag 分发 + `OptimizedClassDescriptor` 元数据缓存 + `HandleTable` 环检测 + 反射字段读写/实例化);④ **`JdkMarshaller`** 对照(包 `ObjectOutputStream`,作为对照与兜底);⑤ **与 S6 Direct 分工验证**(用户对象 → Marshaller → `byte[]` → Direct `writeByteArray` 字段)。
- **本 session 不做**(划出):
  - **BinaryObject 协议**(`BinaryObjectImpl`,2.18.0 现代默认 marshaller,另一套巨大子系统);
  - **集群级 `MarshallerContextImpl` 的 mapping transport / peer class loading / 文件存储**(无集群阶段无意义;本 session 用进程内 map);
  - **`sun.misc.Unsafe`**(`allocateInstance` 绕构造器 + 字段偏移直访)—— 危险;学习版用**反射**(`Field.get/set` + 无参构造器实例化,对无无参构造器的类抛清晰异常);
  - `Externalizable` 支持、自定义 `writeObject`/`readObject`/`writeReplace`/`readResolve` 钩子(v2 只反射非 transient 字段);
  - 特定集合特化(`ArrayList`/`HashMap` 等专用分支;v2 当普通对象反射);
  - 流池(`OptimizedObjectStreamRegistry`)、`checksum`/`serialVersionUID` 校验、安全过滤器(`IgniteObjectInputFilter`/`IgniteMarshallerClassFilter`,JEP 290)、`ServiceLoader` 工厂装配、`.NET`/C++ 跨平台 interop(`DOTNET_ID`)。
- **现实校准**:Ignite 2.18.0 已把 Marshaller **迁出 core** 到 `modules/binary/{api,impl}`(经 `ServiceLoader` 装配);core 只剩 `MarshallerContextImpl` + 工具类。**学习版按 package-layout §2.3 放 `learning/internal/marshaller/`**,镜像的是 **SPI 契约 + 经典 Optimized 形态**(独立、可单测),而非 binary-module 拆分 / ServiceLoader / BinaryObject(详见 P02 §1)。
- **前置 session**:S6(Direct 编解码 —— `MessageWriter.writeByteArray` 是 Marshaller 载荷搭进 Direct 信封的 seam;且工程从 s06 复制)。

## 2. 对外接口契约(API contract)
> DAG 出边:**S7 → S16**(本地缓存,序列化 cache 值)、**S7 → S18**(Discovery,协议自定义数据)、**S7 → S30**(SQL,值字段)。下游据此构建;改接口必须回填下游。

| 类型/方法 | 签名 / 语义 | 供下游 session |
|---|---|---|
| `Marshaller` | `byte[] marshal(Object)`、`Object unmarshal(byte[], ClassLoader)`、`marshal(Object, OutputStream)`、`unmarshal(InputStream, ClassLoader)`(皆抛 `IgniteCheckedException`) | S16/S18/S30(任意用户对象↔字节) |
| `AbstractMarshaller` | 基类:持 `MarshallerContext ctx`(`setContext`) | S16+ 注入 context 时 |
| `MarshallerContext` | `registerClassName(typeId, className)`、`Class getClass(typeId, ClassLoader)`、`boolean isSystemType(String)`(进程内,简化) | S16+ 配置时(可选) |
| `OptimizedMarshaller` | `Marshaller` 的默认实现;`new OptimizedMarshaller()` 即可用(可选 `setRequireSerializable(boolean)`) | S16/S18/S30(默认 marshaller) |
| `JdkMarshaller` | `Marshaller` 的 JDK 兜底实现(包 `ObjectOutputStream`) | 对照 / 兜底 |

> **与 S6 的 seam**(P02 §3.3):下游把用户对象 `marsh.marshal(obj)` → `byte[]`,作为 S6 `MessageWriter.writeByteArray(...)` 字段搭进 Direct 信封;对端 `MessageReader.readByteArray()` 取出 → `marsh.unmarshal(bytes, ldr)` → 对象。**Direct 信封 + Marshaller 载荷**解耦,二者可独立单测。

## 3. Ignite 源码导读(`file:line`,2.18.0)
> 复用 P02 已核验锚点(注意 Marshaller 在 2.18.0 已迁出 core 到 `modules/binary`)。**镜像这些(复现,不 import)**。
1. **SPI 契约**:`modules/binary/api/.../marshaller/Marshaller.java`(:68,`marshal` :91/:100、`unmarshal` :112/:123)—— `Marshaller` 接口全抛 `IgniteCheckedException`。
2. **基类**:`.../marshaller/AbstractMarshaller.java`(:26,持 `ctx` :31)—— 学习版照此持 context。
3. **注册表契约**:`.../marshaller/MarshallerContext.java`(:27,`registerClassName`/:`getClass`/:`isSystemType`)—— 学习版只取**进程内**语义(无 transport)。
4. **Optimized 骨架**:`.../internal/marshaller/optimized/OptimizedMarshallerImpl.java`(:84,`marshal0` :148、`unmarshal0` :207)—— 借 stream → 挂 ctx → 委托。
5. **写引擎**:`.../internal/marshaller/optimized/OptimizedObjectOutputStream.java`(:67,`writeObject0` type-tag 分发 :177、`writeFields` 反射 :485、handle :69)。
6. **读引擎**:`.../internal/marshaller/optimized/OptimizedObjectInputStream.java`(:102,`readObject0` 分发 :222、`readSerializable` 实例化 :602[Ignite 用 Unsafe.allocateInstance,学习版改反射])。
7. **元数据缓存**:`.../internal/marshaller/optimized/OptimizedClassDescriptor.java`(:97,构造期 type 分发 :181、`FieldInfo` 缓存字段偏移、按名排序 :549、`checksum` :117)—— 学习版用反射 `Field`(不缓存 Unsafe 偏移),其余结构(每类缓存 + 按名排序)照搬。
8. **类型常量 + 查找**:`.../internal/marshaller/optimized/OptimizedMarshallerUtils.java`(:45,`NULL=0`/`BYTE=1`…/`HANDLE=-1`/`JDK=-2`/`ENUM=100`/`EXTERNALIZABLE=101`/`SERIALIZABLE=102` :50-152、`classDescriptor` 双向查找 :193、`resolveTypeId=className.hashCode()` :236)。
9. **JDK 对照**:`.../marshaller/jdk/JdkMarshallerImpl.java`(:68,`marshal0` :89 字面 `new ObjectOutputStream`)—— 学习版 JdkMarshaller 照此(用 `ObjectOutputStream`/`ObjectInputStream`)。
- **阅读顺序**:Marshaller 接口 → AbstractMarshaller → MarshallerContext → OptimizedMarshallerImpl 骨架 → OptimizedObjectOutputStream/InputStream(读写引擎)→ OptimizedClassDescriptor(元数据缓存)→ OptimizedMarshallerUtils(类型常量)→ JdkMarshallerImpl(对照)。

## 4. 实现步骤(本 session = v2 级;从 s06 复制扩展)
1. **建工程**:复制 `s06-direct-codec/` → `s07-marshaller/`,改 artifactId/pom;`rm -rf core/target`。
2. **新建 `learning/internal/marshaller/`**:
   - `Marshaller` 接口(`marshal`/`unmarshal` 4 个重载,抛 `IgniteCheckedException`);
   - `AbstractMarshaller`(持 `MarshallerContext ctx` + `setContext`);
   - `MarshallerContext` 接口 + `MarshallerContextImpl`(进程内 `ConcurrentHashMap<Integer,String>` typeId↔className + `getClass` 经 `Class.forName`;`isSystemType` 简化为前缀判定或恒 false;**无 transport / 无文件**);
   - `OptimizedClassDescriptor`:每类反射收集**非 static、非 transient** 字段,**按名排序**(读写一致),赋一个 `type` tag(Byte/Short/.../String/UUID/数组/Object);
   - 写引擎 + 读引擎(可合为一个 `OptimizedStream` 或分 `OptimizedObjectOutput`/`Input`):type tag 前缀每个值;`NULL`/`HANDLE`(环 back-ref)/原语 tag/`STRING`(UTF-8)/`UUID`/数组/Object;Object 写 [class 描述(首次全类名 + 分配 class handle,之后 handle)][各字段递归];
   - `HandleTable`(对象实例 + class 描述的 back-ref 索引,检测环/重复);
   - `OptimizedMarshaller extends AbstractMarshaller`:`marshal0`/`unmarshal0` 走写/读引擎;`setRequireSerializable`(默认 true:非 `Serializable` 抛异常);**实例化用反射**(找层级中首个可访问无参构造器 `setAccessible(true)`;找不到抛清晰异常,文档注明与 Ignite `Unsafe.allocateInstance` 的差距);
   - `JdkMarshaller extends AbstractMarshaller`:包 `ObjectOutputStream`/`ObjectInputStream`(`writeObject`/`readObject`),作对照。
3. **demo + 测试**:POJO 往返(原语/String)+ 嵌套对象 + 数组 + **对象图环**;**体积对比 Java 序列化**(`JdkMarshaller` 输出 vs `OptimizedMarshaller` 输出,断言 Optimized 更小);**经 Direct 字段往返**(对象 → marshal → byte[] → S6 `Message` 的 writeByteArray 字段 → NioServer/或纯 codec 往返 → readByteArray → unmarshal → 对象)。

## 5. 验收 = 具名测试
> `/ignite-session-code` 在 `mvn test` 绿后核验这些具名测试存在且绿。

| 验收点 | 测试 |
|---|---|
| 基本对象往返(POJO:原语 + String + 字段值等价) | `OptimizedMarshallerTest#pojoRoundtrip` |
| 嵌套对象 + 数组往返(对象含对象字段 + 数组字段) | `OptimizedMarshallerTest#nestedAndArrayRoundtrip` |
| 对象图**环**(self-ref / 互引)往返不栈溢出、引用保持 | `OptimizedMarshallerTest#cyclicGraphRoundtrip` |
| **体积对比**:Optimized 输出 < `JdkMarshaller`(Java 序列化)输出 | `OptimizedMarshallerTest#smallerThanJdkSerialization` |
| `MarshallerContext` 注册/typeId 查询/`isSystemType` | `MarshallerContextTest#registerAndResolve` |
| 与 S6 分工:对象 → marshal → byte[] → Direct `writeByteArray` 字段 → 往返 → unmarshal → 对象等价 | `MarshallerViaDirectTest#objectOverDirectMessage` |
- 可运行 demo:`JdkMarshaller` vs `OptimizedMarshaller` 序列化同一个 POJO,打印两者字节数(直观感受优化);一个 `Serializable` 用户对象经 Marshaller→Direct 字段 seam 完整往返。

## 6. 引用路径(lint 核验对象)
```cited-paths
modules/binary/api/src/main/java/org/apache/ignite/marshaller/Marshaller.java
modules/binary/api/src/main/java/org/apache/ignite/marshaller/AbstractMarshaller.java
modules/binary/api/src/main/java/org/apache/ignite/marshaller/MarshallerContext.java
modules/binary/impl/src/main/java/org/apache/ignite/internal/marshaller/optimized/OptimizedMarshallerImpl.java
modules/binary/impl/src/main/java/org/apache/ignite/internal/marshaller/optimized/OptimizedObjectOutputStream.java
modules/binary/impl/src/main/java/org/apache/ignite/internal/marshaller/optimized/OptimizedObjectInputStream.java
modules/binary/impl/src/main/java/org/apache/ignite/internal/marshaller/optimized/OptimizedClassDescriptor.java
modules/binary/impl/src/main/java/org/apache/ignite/internal/marshaller/optimized/OptimizedMarshallerUtils.java
modules/binary/impl/src/main/java/org/apache/ignite/marshaller/jdk/JdkMarshallerImpl.java
```

---
**工时**:⭐⭐⭐ / 3~5 天  **产出物**:可插拔 `Marshaller`(SPI 接口 + `OptimizedMarshaller` 自定义紧凑格式 + `JdkMarshaller` 对照 + 进程内 `MarshallerContext`)—— Phase 2 收官。载荷层就位,后续 cache/discovery/SQL 用它序列化任意用户对象。
