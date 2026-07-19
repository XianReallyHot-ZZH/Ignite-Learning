# S08 · 执行规格:PageMemory v1(页模型)

> **Phase 3 · 页内存 PageMemory · v1**(Phase 3 开篇 —— 存储地基支线 S8~S15 的起点)
> 执行约束规格(瘦)。**教学法见 `docs-learn/S08-page-memory-v1.md`**(由 session-code 建后产)。
> **SoT**:范围/顺序看 roadmap S8 块;拆分/grounding 看 `P03-page-memory-analysis.md` §6;本规格 = 细化 + 契约 + 验收。
> 代码 `ignite-gogogo/s08-page-memory/`。lint:`scripts/check-cited-paths.sh`。

## 1. 范围与位置
- **roadmap S 块**:Session S8(权威范围/前置/实现要点/验收)。
- **phase §6 行**:P03 §6 · S8 = **v1**(最小可运行页模型)。
- **本 session 做**:① **页 id 编码模型**——`PageIdUtils` 纯位运算(`pageId(partId,flag,pageIdx)` 打包 + `pageIndex/partId/flag/effectivePageId` 拆解 + `rotatePageId`)+ `FullPageId`(`equals`/`hashCode` 只认 `effectivePageId`+`grpId`,**rotation-blind**);② **分配器契约**——`PageIdAllocator` 接口(`FLAG_DATA/FLAG_IDX/FLAG_AUX`、`MAX_PARTITION_ID`、`META_PAGE_ID`)+ `PageSupport` 接口(acquire/release + 锁契约,**全部以裸 `long page` 指针为参**)+ `PageMemory` 顶层接口(`extends PageIdAllocator, PageSupport`,裁掉 `metrics()` 上漏);③ **`OffHeap` 助手**(包 `sun.misc.Unsafe`:allocateMemory/freeMemory/putLong/getLong/putByte/getByte/wrapPointer,把裸 Unsafe 收敛到一处);④ **纯内存实现 `PageMemoryNoStoreImpl`**(裁剪版):`start()` 经 `OffHeap.allocateMemory` 拿**一块单段**堆外内存,按指针算术切成 N 页;页头 24B(`PAGE_MARKER`+`pageId`+`lock` 占位);`acquirePage` 返回裸 `long` 句柄、`pageBuffer(long)` 包成 `ByteBuffer`、`readLock/writeLock` 粗粒度占位、`allocatePage` 单段 bump 分配、`releasePage` 近 no-op。
- **本 session 不做**(划出):
  - **全局 Treiber free-list**(页回收复用:侵入式 next + ABA 计数器)→ **S9**;
  - **多段惰性增长**(`initialSize` 起 / `maxSize` 上限 / `addSegment` 扩段)→ **S9**;
  - **条带 `OffheapReadWriteLock`**(页内 8 字节锁字 + `tag` 防陈旧 + upgrade)→ **S9**(S8 锁用方法级 `synchronized` 占位,契约先立);
  - **`DataRegion` 容器** + `DirectMemoryProvider` 抽象 + lifecycle 装配 → **S9**(S8 直接 `new PageMemoryNoStoreImpl(...).start()`);
  - **页驱逐**(LRU/CLOCK,`PageEvictionTracker`)→ **deferred**(`PageMemoryNoStoreImpl` 本身零驱逐逻辑);
  - **持久化变体 `PageMemoryImpl`**(PageStore/WAL/checkpoint buffer)→ **Phase 4~5**;
  - **`PageIO` 40B 公共页头**(type/version/crc/pageId… 页**内容**格式层)→ **S12+**(S8 把页当裸字节);
  - **`freelist/` 包的行级 free space**(`CacheFreeList`/`PagesList`)→ **S14/S16**(P03 §4.1 澄清:与页回收无关);
  - **WAL delta**、**CRC**、**加密**、**压缩** → 下游/deferred。
- **现实校准(覆盖 roadmap 字面措辞)**:roadmap S8 写"用 `DirectByteBuffer`(堆外)分配;页的 acquire/release **引用计数**"。但真实 Ignite 两者都不是(P03 §1/§4):① 内存经 **`sun.misc.Unsafe.allocateMemory`** 按 segment 分块拿(从不用 `ByteBuffer.allocateDirect`);页句柄是**裸 `long` 指针**(`pageBuffer(long)` 才按需 `wrapPointer` 包成 `ByteBuffer`)。② `PageMemoryNoStoreImpl.releasePage` 几乎是个 **no-op**(无 per-page refcount、无 `Page` 对象、无 pin 计数);"并发安全"靠 S9 的**页内 8 字节锁字**(`OffheapReadWriteLock`),不是数值引用计数。**学习版 S8/S9 按真实形态镜像**(裸 `long` + Unsafe + S9 的 Treiber free-list + 页内锁字),把 roadmap 的"DirectByteBuffer/引用计数"措辞按本校准落地。
- **分配材质拍板(P03 §4.7 把选择留给执行规格)**:**S8 采用 `sun.misc.Unsafe`**(保真首选——S9 的无锁 free-list 与页内锁字都依赖裸地址写)。JDK 17/21 里 `sun.misc.Unsafe` 位于 `jdk.unsupported` 模块,默认对 unnamed module 可读,**`import sun.misc.Unsafe` 直接可用,无需 `--add-exports`/surefire argLine**。包进一个小 `OffHeap` 助手(镜像 `GridUnsafe`),把裸 Unsafe 用法收敛到一处,后续可整体换 FFM(`java.lang.foreign.MemorySegment`,纯标准 JDK)——**FFM 列为实现期备选,S8 不采纳**。
- **前置 session**:S1(并发原语热身——为 S9 的并发铺底;S8 本身单段 + 粗锁,并发量极轻)。**Phase 3 是新支,不依赖 Phase 1/2(NIO/Direct/Marshaller)的任何代码**——core 下不带 NIO 代码。

## 2. 对外接口契约(API contract)
> DAG 出边:**S8 → S9**(PageMemory v2:在 v1 接口上加 free-list/多段/条带锁)、**S8 → S10**(WAL v1:页每次改写产 WAL 记录,需 `acquirePage/pageBuffer/writeLock/releasePage` 契约)。下游据此构建;改接口必须回填下游。

| 类型/方法 | 签名 / 语义 | 供下游 session |
|---|---|---|
| `PageIdUtils` | static 纯位运算:`long pageId(int partId, byte flag, long pageIdx)`、`long pageIndex(long pageId)`、`int partId(long pageId)`、`byte flag(long pageId)`、`long effectivePageId(long link)`、`long link(long pageId, int itemId)`、`long rotatePageId(long pageId)` + 常量(`PAGE_IDX_SIZE=32`/`PART_ID_SIZE=16`/`FLAG_SIZE=8`/`OFFSET_SIZE=8`/`ROTATION_ID_OFFSET=56`/`MAX_ITEMID_NUM=0xFE` 及各 mask) | S9/S12+(页身份编解码,纯函数) |
| `FullPageId` | 值类:`FullPageId(long pageId, int grpId)`、`long effectivePageId()`、`long pageId()`、`int groupId()`、`equals`/`hashCode`(**rotation-blind**:只认 effectivePageId+grpId)、`static final FullPageId NULL_PAGE` | S9/S12+(页身份键;用作 map key) |
| `PageIdAllocator` | 接口:常量 `FLAG_DATA=1`/`FLAG_IDX=2`/`FLAG_AUX=4`、`MAX_PARTITION_ID=65500`、`INDEX_PARTITION=0xFFFF`、`long META_PAGE_ID`;`long allocatePage(int grpId, int partId, byte flags)`、`void freePage(int grpId, long pageId)` | S9(实现 free-list 复用)、S10+(谁分配/释放页都用它) |
| `PageSupport` | 接口(全裸 `long` 参):`long acquirePage(int grpId, long pageId)`、`void releasePage(int grpId, long pageId, long page)`、`long readLock(int grpId, long pageId, long page)`、`void readUnlock(...)`、`long writeLock(...)`、`boolean tryWriteLock(...)`、`void writeUnlock(..., boolean dirty)`(锁失败返回 `0L`,调用方据此重读) | S9(细锁实现)、S10/S12+(改页前先 writeLock) |
| `PageMemory` | 接口 `extends PageIdAllocator, PageSupport`:`void start()`、`void stop()`、`int pageSize()`、`int systemPageSize()`、`ByteBuffer pageBuffer(long page)`、`long loadedPages()`(**裁掉** Ignite 的 `metrics()` 上漏——`DataRegionMetricsImpl` 依赖,留给 S9 自定 metrics) | S9(v2 扩展)、S10/S12/S13/S15(所有上层经此触页) |
| `OffHeap` | 助手(包 `sun.misc.Unsafe`):`long allocateMemory(long bytes)`、`void freeMemory(long address)`、`void putLong(long addr, long v)`、`long getLong(long addr)`、`void putByte/byte getByte`、`ByteBuffer wrapPointer(long addr, int size)`(`putLongVolatile/getLongVolatile/compareAndSwapLong` **留给 S9** 加,S8 暂不放) | S9(CAS free-list/锁字要用)、本 session 内部 |
| `PageMemoryNoStoreImpl` | `PageMemory` 的纯内存实现(S8 裁剪版,单段):ctor `(int sysPageSize, long regionSize[, IgniteLogger-like])`;头常量 `PAGE_MARKER=0xBEEAAFDEADBEEF01L`、`PAGE_ID_OFFSET=8`、`LOCK_OFFSET=16`、`PAGE_OVERHEAD=24`;`start()` 单段 `OffHeap.allocateMemory` → 切 N 页;`allocatePage` 单段 bump(`AtomicInteger nextIdx`,满抛 OOM);`acquirePage→裸 long`、`releasePage`(v1 no-op)、`readLock/writeLock`(粗粒度 `synchronized` 占位,返回 `page+PAGE_OVERHEAD`)、`pageBuffer(page)`=`OffHeap.wrapPointer(page+PAGE_OVERHEAD, pageSize-PAGE_OVERHEAD)`、`loadedPages`=已分配数 | S9(v2 在此基础上加 free-list/多段/条带锁) |

> **契约稳定性提示**:S8 立的接口刻意对齐 Ignite(`PageIdUtils`/`FullPageId`/`PageMemory` 真实签名),S9 在**不改对外契约**的前提下给实现加 free-list/多段/条带锁——下游(S10+)面向接口编程,不感知实现升级。

## 3. Ignite 源码导读(`file:line`,2.18.0)
> 复用 P03 §2/§3 已核验锚点。**镜像这些(复现,不 import)**。
1. **页 id 位运算**:`internal/pagemem/PageIdUtils.java`(`PAGE_IDX_SIZE=32` :29、`PART_ID_SIZE=16` :32、`FLAG_SIZE=8` :35、`ROTATION_ID_OFFSET=56` :64、`MAX_ITEMID_NUM=0xFE` :70、`pageId(part,flag,idx)` :161、`pageIndex` :105、`partId` :182、`flag` :174、`effectivePageId` :123、`link` :92、`rotatePageId` :197)—— 纯函数,立即单测。
2. **复合键(rotation-blind)**:`internal/pagemem/FullPageId.java`(`NULL_PAGE` :53、字段 :56-62、ctor :116、equals :145、hashCode :158、Stafford `mix64` :105、MH3 `mix32` :89)—— 学习版用 `Objects.hash`/手写均可,关键是 equals/hashCode 只认 effectivePageId+grpId。
3. **分配器契约**:`internal/pagemem/PageIdAllocator.java`(`FLAG_DATA=1` :32、`FLAG_IDX=2` :39、`FLAG_AUX=4` :45、`MAX_PARTITION_ID=65500` :48、`INDEX_PARTITION=0xFFFF` :51、`META_PAGE_ID` :54、`allocatePage` :63、`freePage` :71)。
4. **acquire/release + 锁契约**:`internal/pagemem/PageSupport.java`(`acquirePage→long` :37/:50、`releasePage` :58、`readLock→long` :67、`readUnlock` :86、`writeLock→long` :96、`tryWriteLock` :106、`writeUnlock` :118)—— 注意"没有 `Page` 类型",全裸 `long`。
5. **顶层接口**:`internal/pagemem/PageMemory.java`(`extends PageIdAllocator, PageSupport` :26、`start` :30、`stop` :38、`pageSize` :43、`systemPageSize` :54、`pageBuffer(long)→ByteBuffer` :60、`loadedPages` :65)—— **学习版裁掉 `metrics()` 上漏**。
6. **纯内存实现(只取 S8 切片)**:`internal/pagemem/impl/PageMemoryNoStoreImpl.java`(类 :77、`PAGE_MARKER=0xBEEAAFDEADBEEF01L` :79、`PAGE_ID_OFFSET=8` :97、`LOCK_OFFSET=16` :100、`PAGE_OVERHEAD=24` :106、`start` 分块 :239-280、`allocatePage` :309、`acquirePage` :503、`releasePage` :518、`readLock/writeLock` :529-593、`Segment.absolute(idx)=pagesBase+idx*sysPageSize` :838)—— S8 **只镜像单段**:start 拿一块、切页、头布局、acquire/release/pageBuffer;**忽略** `freePageListHead`(:136)、`borrowFreePage`(:678)、`releaseFreePage`(:642)、`addSegment`(:718)、`SEG_CNT=16`(:112)—— 这些是 S9。
7. **Unsafe 包装(包进 `OffHeap`)**:`modules/unsafe/src/main/java/org/apache/ignite/internal/util/GridUnsafe.java`(`allocateMemory=UNSAFE.allocateMemory` :1235-1237、`putLong`/`getLong`/`wrapPointer`)—— 学习版只取这几个,包成静态助手。
- **阅读顺序**:PageIdUtils(吃透位运算)→ FullPageId(rotation-blind equals/hashCode)→ PageIdAllocator(flag 常量/契约)→ PageSupport + PageMemory(裸 long 契约,无 `Page` 类型)→ PageMemoryNoStoreImpl 头部 + start + Segment.absolute(单段切页)→ GridUnsafe(allocateMemory/putLong/getLong/wrapPointer)。

## 4. 实现步骤(本 session = v1 级)
1. **建工程**:复制 `s01-skeleton/`(干净多模块 Maven 骨架)→ `s08-page-memory/`,改 artifactId=`s08-page-memory`/name/parent ref;父 pom `maven.compiler.release=17`(跟 s07 基线,`sun.misc.Unsafe` 在 17 可用);core/pom 留 JUnit5。`rm -rf core/target`。**不复制 s03~s07 的 NIO/Direct/Marshaller 代码**(Phase 3 是新支)。
2. **新建 `learning/internal/pagemem/` 包**(`org.apache.ignite.learning.internal.pagemem`):
   - `PageIdUtils`:照 §3 锚点 1 实现位运算。64-bit 布局(LSB→MSB):`pageIdx` bits0-31 / `partId` bits32-47 / `flag` bits48-55 / `rotation` bits56-63。打包顺序 `flag→partId→pageIdx` 逐段左移。`effectivePageId(link)=link & ~(-1L<<48)`(抹 flag+rotation)。`rotatePageId`:顶 8 位 `(rot+1)`,绕 `0xFE→1`(**永不为 0**,防与"未分配"混淆)。常量与 mask 全 public 供下游。
   - `FullPageId`:`(long pageId, int grpId)`;`effectivePageId()`;`equals`/`hashCode` 只用 `effectivePageId`+`grpId`(rotation-blind);`NULL_PAGE=new FullPageId(-1L,-1)`。
   - `PageIdAllocator` 接口:flag 常量 + `MAX_PARTITION_ID`/`INDEX_PARTITION`/`META_PAGE_ID` + `allocatePage`/`freePage`。
   - `PageSupport` 接口:全裸 `long` 参的 acquire/release + 锁契约。
   - `PageMemory` 接口:`extends PageIdAllocator, PageSupport`;`start/stop/pageSize/systemPageSize/pageBuffer/loadedPages`;**不写 `metrics()`**。
   - `OffHeap` 助手:`static Unsafe UNSAFE`(`Unsafe.getUnsafe()` 经反射拿 field——JDK 17 `Unsafe.getUnsafe()` 按 caller 判定可能抛异常,标准做法是反射 `Field theUnsafe` + `setAccessible(true)`);`allocateMemory/freeMemory/putLong/getLong/putByte/getByte/wrapPointer`(后者 `UNSAFE.allocateMemory` 拿的裸地址 → 经 `ByteBuffer.allocateDirect` 镜像 GridUnsafe.wrapPointer,或直接 `LongBuffer`/`Unsafe` 走 —— 学习版选简单可控的一种,讲义说明取舍)。
3. **`PageMemoryNoStoreImpl`(单段裁剪版)**:
   - 字段:`sysPageSize`/`pageSize`(默认 4096,可配)、`regionSize`、`pagesBase`(`OffHeap.allocateMemory(regionSize)` 返回)、`nextIdx`(`AtomicInteger`,单段 bump)、`pageCnt`(总页数=`regionSize/sysPageSize`)。
   - 头布局常量:`PAGE_MARKER=0xBEEAAFDEADBEEF01L`、`PAGE_ID_OFFSET=8`、`LOCK_OFFSET=16`、`PAGE_OVERHEAD=24`。
   - `start()`:分配 `regionSize` 堆外 → `pagesBase`;遍历每页头写 `PAGE_MARKER`(`OffHeap.putLong(base+idx*sysPageSize, PAGE_MARKER)`)。
   - `absolute(idx)=pagesBase + idx*sysPageSize`。
   - `allocatePage(grpId,partId,flags)`:`idx=nextIdx.getAndIncrement()`;`idx>=pageCnt` → 抛 `IgniteOutOfMemoryException`(学习版自建简单异常,提示"段满,S8 无 free-list,等 S9");`absPtr=absolute(idx)`;`id=PageIdUtils.pageId(partId,flags,idx)`;`OffHeap.putLong(absPtr+PAGE_ID_OFFSET, id)`;返回 `id`。
   - `acquirePage(grpId,pageId)`:由 `pageIndex(pageId)` 算 `absPtr=absolute(idx)` → 返回裸 `long absPtr`。
   - `releasePage(...)`:**v1 no-op**(镜像 Ignite `trackAcquiredPages=false`)。
   - `readLock/writeLock/...`:**v1 粗粒度占位**——方法级 `synchronized`(或返回 `page+PAGE_OVERHEAD` 不做锁逻辑);契约先立,S9 才上页内锁字 + tag。
   - `pageBuffer(page)`:`OffHeap.wrapPointer(page+PAGE_OVERHEAD, pageSize-PAGE_OVERHEAD)` → 返回数据区 `ByteBuffer`。
   - `loadedPages()`:`nextIdx.get()`。
4. **demo + 测试**(见 §5):pageId 编解码往返(各种 partId/flag/pageIdx + 边界)、`effectivePageId` 抹 flag+rotation、`FullPageId` rotation-blind 相等、`NULL_PAGE` 哨兵、`rotatePageId` 永不为 0、分配 N 页→`pageBuffer` 写字节→读回一致、段满抛 OOM、页头 PAGE_MARKER/pageId 布局。

## 5. 验收 = 具名测试
> `/ignite-session-code` 在 `mvn test` 绿后核验这些具名测试存在且绿。

| 验收点 | 测试 |
|---|---|
| `pageId(partId,flag,pageIdx)` 打包 → `pageIndex/partId/flag` 拆解往返一致 | `PageIdUtilsTest#encodeDecodeRoundtrip` |
| 边界值:partId=0/`MAX_PARTITION_ID`、flag=0/MAX、pageIdx=0/MAX 不溢出、不丢位 | `PageIdUtilsTest#boundaryValues` |
| `effectivePageId` 抹掉 flag+rotation,保留 pageIdx+partId | `PageIdUtilsTest#effectivePageIdStripsFlagAndRotation` |
| `rotatePageId` 绕 `0xFE→1`,**永不为 0** | `PageIdUtilsTest#rotatePageIdNeverZero` |
| `FullPageId` 同 pageIdx+partId+grpId、不同 rotation → 相等(rotation-blind) | `FullPageIdTest#rotationBlindEquality` |
| `FullPageId.NULL_PAGE` 哨兵可用、与正常 id 不等 | `FullPageIdTest#nullPageSentinel` |
| `allocatePage` → `acquirePage` → `pageBuffer` 写字节 → 读回一致 | `PageMemoryNoStoreImplTest#allocateAndPageBufferRoundtrip` |
| 分配多页,`loadedPages()` 计数正确,各页 `pageBuffer` 不重叠 | `PageMemoryNoStoreImplTest#allocateManyPages` |
| 页头布局:每页头 8B=`PAGE_MARKER`、`+PAGE_ID_OFFSET`=pageId(PAGE_OVERHEAD=24) | `PageMemoryNoStoreImplTest#pageHeaderLayout` |
| 单段满(`pageCnt` 用尽)抛 OOM(S8 无 free-list) | `PageMemoryNoStoreImplTest#outOfMemoryWhenFull` |
- 可运行 demo:`start()` 一块 4KB×N 的堆外内存 → 分配若干页 → 用 `pageBuffer` 读写 `long`/`byte` → 打印每页地址与页头(直观感受"裸 long 指针 + 按指针算术切页");段满时打印 OOM 提示。

## 6. 引用路径(lint 核验对象)
```cited-paths
internal/pagemem/PageIdUtils.java
internal/pagemem/FullPageId.java
internal/pagemem/PageIdAllocator.java
internal/pagemem/PageSupport.java
internal/pagemem/PageMemory.java
internal/pagemem/impl/PageMemoryNoStoreImpl.java
modules/unsafe/src/main/java/org/apache/ignite/internal/util/GridUnsafe.java
```

---
**工时**:⭐⭐⭐ / 3~5 天  **产出物**:`PageMemory` 纯内存实现 v1(`PageIdUtils` 位运算 + `FullPageId` rotation-blind + 裸 `long` 句柄接口契约 + `OffHeap`(Unsafe)单段按页读写 + 页头 24B 布局)—— Phase 3 存储地基的第一块砖。S9 在**不改对外契约**的前提下加全局 Treiber free-list + 多段 + 条带锁。
