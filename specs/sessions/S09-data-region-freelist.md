# S09 · 执行规格:PageMemory v2(DataRegion + free-list + 并发锁)

> **Phase 3 · 页内存 PageMemory · v2**(Phase 3 收官 —— 本 session 后 Phase 3 完成)
> 执行约束规格(瘦)。**教学法见 `docs-learn/S09-data-region-freelist.md`**(由 session-code 建后产)。
> **SoT**:范围/顺序看 roadmap S9 块;拆分/grounding 看 `P03-page-memory-analysis.md` §6;本规格 = 细化 + 契约 + 验收。
> 代码 `ignite-gogogo/s09-data-region/`(从 s08 复制扩展)。lint:`scripts/check-cited-paths.sh`。

## 1. 范围与位置
- **roadmap S 块**:Session S9(权威范围/前置/实现要点/验收)。
- **phase §6 行**:P03 §6 · S9 = **v2**(功能完整:回收复用 + 惰性多段 + 并发锁)。
- **本 session 做**(5 项核心,在 S8 v1 基础上升级):
  1. **全局 Treiber free-list(页回收复用)**——`AtomicLong freePageListHead`(低 56 位 relative pointer + 高 8 位 ABA 计数器);**侵入式 next**(被释放页的 **offset 0** 前 8 字节 = next 指针,复用 `PAGE_MARKER` 槽);`borrowFreePage`(CAS pop:读 offset 0 的 next + 计数器 `+COUNTER_INC` + CAS)、`releaseFreePage`(CAS push:写旧 head 到 offset 0 + CAS);`allocatePage` 先 pop free-list、空则段 bump。
  2. **多段惰性增长**——`Segment[]`(初始 `initialSize` 一段,`maxSize` 上限,末段 bump CAS 满 → `addSegment`);pageId 高 `SEG_BITS=4` 位编码 segment index(`SEG_CNT=16`、`IDX_BITS=28`),free-list relPtr 跨段(`segment(idx)` 高位解码定位);`addSegment`(`synchronized` + DCL,扩 `Segment[]` + `DirectMemoryProvider.nextRegion()`)。
  3. **条带 `OffheapReadWriteLock`**——页内 8 字节锁字(`LOCK_CNT` 16-bit:0 自由/-1 写锁/>0 读者数;`TAG` 16-bit;`READ_WAIT_CNT`/`WRITE_WAIT_CNT` 各 16-bit);**tag 防陈旧**(`readLock`/`writeLock` 校验 `tag(pageId)==锁字 TAG`,不符返回 `0L`/false → 调用方知"页已被复用");`readLock`/`writeLock`/`readUnlock`/`writeUnlock`/`tryWriteLock`;spin `SPIN_CNT=32` 后退化到条带 `ReentrantLock[]`+Condition park;`upgradeToWriteLock`(基础 CAS 版:reader 1 → writer -1)。替换 S8 的 `synchronized` 粗锁占位。
  4. **`DataRegion` 容器**——POJO(`PageMemory` + `DataRegionConfiguration` + `DataRegionMetricsImpl`),外部 lifecycle `pageMemory().start()`/`stop()`。
  5. **`DirectMemoryProvider` + `UnsafeMemoryProvider`**——惰性产出堆外 chunk(`nextRegion()` → `OffHeap.allocateMemory`),`DirectMemoryRegion`(address,size)。
- **本 session 不做**(划出):
  - **页驱逐**(`PageEvictionTracker`:NoOp/RANDOM_LRU/RANDOM_2_LRU)→ **deferred**(Ignite NoStoreImpl 本身零驱逐,驱逐是外挂协作者);
  - **持久化变体 `PageMemoryImpl`**(PageStore/WAL/checkpoint buffer)→ **Phase 4~5**;
  - **`PageIO` 40B 公共页头**(页内容格式层)→ **S12+**;
  - **行级 freelist**(`CacheFreeList`/`PagesList`)→ **S14/S16**(P03 §4.1:数据页内行级空闲空间,非页回收);
  - **锁的完整公平调度**(write-waiter 优先抢占读)+ **随机唤醒策略**(`IGNITE_OFFHEAP_RANDOM_RW_POLICY`)+ 完整 Condition 唤醒链 → **deferred**(学习版只 spin + 基础条带 park);
  - **`trackAcquiredPages` 测试计数器** → deferred(Ignite 测试专用);
  - **WAL delta / checkpoint buffer / CRC / 加密 / 压缩** → 下游/deferred。
- **现实校准(覆盖 roadmap 字面措辞)**:roadmap S9 写"DataRegion(一段连续堆外内存)"。真实 Ignite 的 DataRegion 是 **~85 行 POJO 容器**(P03 §2.4),自身无生命周期代码;"一段连续堆外内存"是 `PageMemoryNoStoreImpl` 内的 `Segment[]`(多段,**非单一连续 slab**)。学习版按真实镜像:DataRegion=薄容器,堆外在 PageMemory 的 Segment[]。
- **前置 session**:**S8**(本 session 从 s08 工程复制扩展;S8 的 `PageIdUtils`/`FullPageId`/`PageMemory`/`PageSupport`/`PageIdAllocator` 接口 + `OffHeap` 全部继承,S9 升级 `PageMemoryNoStoreImpl` 实现 + 新增锁/容器/provider)。

## 2. 对外接口契约(API contract)
> DAG 出边:**S9 → S13**(PageStore:落盘需完整 PageMemory + DataRegion)、**S9 → S16**(本地缓存:值落脚于 DataRegion.pageMemory())、**S9 → S15**(Checkpoint:脏页刷盘经 PageMemory)。下游据此构建。
>
> **契约稳定性**:S9 **不改 S8 的 `PageMemory`/`PageIdUtils`/`FullPageId` 对外契约**,只升级 `PageMemoryNoStoreImpl` 内部实现(加 free-list/多段/锁)+ 新暴露 `DataRegion` 容器。下游(S13/S16/S15)面向 `PageMemory` 接口编程,S8→S9 升级对它们透明。

| 类型/方法 | 签名 / 语义 | 供下游 session |
|---|---|---|
| `PageMemory`(S8 已立) | 接口不变(S8 §2 契约):`allocatePage`/`acquirePage`/`releasePage`/`readLock`/`writeLock`/`pageBuffer`/`pageSize`/`loadedPages`。**S9 升级实现语义**:`readLock`/`writeLock` 现真正做页内锁(返回数据区指针或 `0L` 陈旧);`allocatePage` 先 pop free-list(回收复用);`freePage` 真正入 free-list | S13/S15/S16(面向接口,升级透明) |
| `DataRegion` | POJO 容器:`DataRegion(PageMemory, DataRegionConfiguration, DataRegionMetricsImpl)` + getter `pageMemory()`/`config()`/`metrics()`;自身无 lifecycle 代码(外部 `pageMemory().start()`) | S13/S16(存储组入口:经此拿 PageMemory + config) |
| `DataRegionConfiguration` | 配置 bean:`name`、`initialSize`(默认)、`maxSize`(默认)、`pageSize`(默认 4096);可选 `persistenceEnabled`/`lazyMemoryAllocation` | S13/S16(配 DataRegion) |
| `DataRegionMetricsImpl` | 轻量 metrics:`totalPages()`/`usedPages()` 等计数(学习版简化为 `AtomicLong`,反指 PageMemory 可省) | S16(metrics 查询) |
| `OffheapReadWriteLock` | **内部实现类**(不直接供下游):`OffheapReadWriteLock(int concLvl)`、`init(long lockAddr, int tag)`、`readLock(addr,tag)→boolean`、`readUnlock(addr)`、`writeLock(addr,tag)→boolean`、`tryWriteLock(addr,tag)`、`writeUnlock(addr,dirty)`、`upgradeToWriteLock(addr,tag)→boolean`(锁失败/tag 陈旧返回 false) | 本 session 内部(封装于 PageMemoryNoStoreImpl) |
| `DirectMemoryProvider` / `UnsafeMemoryProvider` | provider 抽象:`initialize(long[] chunkSizes)`、`DirectMemoryRegion nextRegion()`(惰性)、`shutdown(boolean)`;`UnsafeMemoryProvider.nextRegion()` → `OffHeap.allocateMemory` | 本 session 内部(PageMemory 多段扩容用) |

> **与 S8 的 seam**:S8 的 `PageMemoryNoStoreImpl`(单段、粗锁、无 free-list)在 S9 被重写为 v2(多段、细锁、free-list)。`OffHeap` 助手(S8 立)被 S9 的 `UnsafeMemoryProvider` 和 `OffheapReadWriteLock` 复用(`allocateMemory`/`putLong`/`getLong`/`getLongVolatile`/`compareAndSwapLong`——S9 需给 `OffHeap` 补 volatile/CAS 方法,见 §4)。

## 3. Ignite 源码导读(`file:line`,2.18.0)
> 复用 P03 §2/§3 已核验锚点。**镜像这些(复现,不 import)**。
1. **free-list 常量 + 字段**:`internal/pagemem/impl/PageMemoryNoStoreImpl.java`(`RELATIVE_PTR_MASK=0xFFFFFFFFFFFFFFL` :82、`INVALID_REL_PTR` :85、`ADDRESS_MASK` :88、`COUNTER_MASK=~ADDRESS_MASK` :91、`COUNTER_INC=ADDRESS_MASK+1` :94、`freePageListHead:AtomicLong` :136、`SEG_BITS=4` :109、`SEG_CNT=16` :112、`IDX_BITS=PAGE_IDX_SIZE-SEG_BITS=28` :115、`SEG_MASK` :118、`IDX_MASK` :121)。
2. **releaseFreePage(CAS push)**:同文件(:642-673)—— relPtr=pageId(0,0,pageIdx) 清 flag/tag、`writePageId(absPtr,relPtr)`(offset 8)、`putLong(absPtr, freePageRelPtr)`(offset 0 = next 槽,复用 PAGE_MARKER)、`CAS(freePageListHead, head→relPtr)`、`allocatedPages--`。
3. **borrowFreePage(CAS pop + ABA)**:同文件(:678-708)—— head=get、`relPtr=head&ADDRESS_MASK`、`INVALID_REL_PTR`→返回空、`nextRelPtr=getLong(absPtr)&ADDRESS_MASK`(读 offset 0 next)、`cnt=(head&COUNTER_MASK)+COUNTER_INC`、`CAS(head→nextRelPtr|cnt)`、`putLong(absPtr,PAGE_MARKER)`(标 in-use)、`allocatedPages++`。
4. **allocatePage**:同文件(:309-374)—— `borrowFreePage` 先;空则 `segment(...).allocateFreePage(flags)`;末段满 `addSegment`;组装 pageId、`writePageId`、清零数据区。
5. **addSegment(synchronized+DCL)**:同文件(:718-740)—— `segments==oldRef` DCL、`directMemoryProvider.nextRegion()`、扩 `Segment[]`。
6. **Segment + bump CAS**:同文件(`Segment` :755、`pagesBase` :769、`lastAllocatedIdxPtr` :766、`absolute(idx)=pagesBase+idx*sysPageSize` :838、`allocateFreePage` CAS :875-901 —— CAS `lastAllocatedIdxPtr`、`writePageId`、`putLong(absPtr,PAGE_MARKER)`、`rwLock.init`)。
7. **OffheapReadWriteLock**:`internal/util/OffheapReadWriteLock.java`(锁字 4×16-bit 布局 :29-35、`LOCK_SIZE=8` :64、`MAX_WAITERS=0xFFFF` :67、条带 `ReentrantLock[]` ctor :87-106、`init(lock,tag)`=`putLong(lock,(long)tag<<16)` :111、`readLock(lock,tag)` :122[volatile 读→checkTag→CAS reader+1→spin32→条带 park]、`readUnlock` :166、`writeLock`/`writeUnlock`、`upgradeToWriteLock` :359)。
8. **DirectMemoryProvider / UnsafeMemoryProvider**:`internal/mem/DirectMemoryProvider.java`(`initialize(long[])`/`nextRegion()`/`shutdown`)、`internal/mem/unsafe/UnsafeMemoryProvider.java`(每次 `nextRegion()` 经 allocator 拿一块)、`internal/mem/unsafe/UnsafeMemoryAllocator.java`(`GridUnsafe.allocateMemory(size)` :25-27)、`internal/mem/DirectMemoryRegion.java`((address,size) 对)。
9. **DataRegion 容器**:`internal/processors/cache/persistence/DataRegion.java`(ctor :45-55、`pageMemory` :60、`config` :67、`metrics` :74—— ~85 行 POJO);`configuration/DataRegionConfiguration.java`(`initialSize/maxSize` 默认 :90-94);`internal/processors/cache/persistence/DataRegionMetricsImpl.java`(:51)。
10. **(只读)装配序**:`internal/processors/cache/persistence/IgniteCacheDatabaseSharedManager.java`(`startDataRegions` :289-296、`createPageMemory→new PageMemoryNoStoreImpl` :1386-1400)—— 学习版只理解"谁拥有谁",不镜像装配。
- **阅读顺序**:PageMemoryNoStoreImpl free-list 常量 → releaseFreePage/borrowFreePage(侵入式 Treiber 灵魂)→ allocatePage → addSegment/Segment bump CAS → OffheapReadWriteLock(锁字 + tag + 条带)→ DirectMemoryProvider/UnsafeMemoryProvider → DataRegion POJO → IgniteCacheDatabaseSharedManager(只读装配序)。

## 4. 实现步骤(本 session = v2 级;从 s08 复制扩展)
1. **建工程**:复制 `s08-page-memory/` → `s09-data-region/`,改 artifactId/pom;**继承 S8 全部源码**(`PageIdUtils`/`FullPageId`/`PageIdAllocator`/`PageSupport`/`PageMemory`/`OffHeap`/`PageMemoryOutOfMemoryException`);`rm -rf core/target`。
2. **扩展 `OffHeap`**:补 volatile/CAS 方法(供 free-list + 锁用):`putLongVolatile(addr,v)`、`getLongVolatile(addr)`、`compareAndSwapLong(addr,expected,update)→boolean`(`UNSAFE.compareAndSwapLong(null, addr, expected, update)`)。
3. **新建 `learning/internal/pagemem/impl/OffheapReadWriteLock.java`**(镜像 Ignite 同名):
   - 锁字 8B 布局常量 + bit 操作(`lockCount`/`tag`/`readersWaitCount`/`writersWaitCount` 的移位取段、`updateState(lockCnt,readWait,writeWait)` 拼回 long);
   - `OffheapReadWriteLock(int concLvl)`(power-of-2,条带 `ReentrantLock[]`+`Condition[]` read/write);
   - `init(addr, tag)`:`putLong(addr, (long)tag<<16)`(LOCK_CNT 初始 0);
   - `readLock(addr,tag)`:volatile 读 state → `checkTag`(不符 return false)→ spin `SPIN_CNT=32`(CAS LOCK_CNT +1,要求无写锁)→ 失败退化条带 lock+Condition park;
   - `readUnlock(addr)`:CAS LOCK_CNT -1;
   - `writeLock(addr,tag)`/`tryWriteLock`:CAS LOCK_CNT 0→-1(校验 tag);
   - `writeUnlock(addr,dirty)`:CAS LOCK_CNT -1→0;
   - `upgradeToWriteLock(addr,tag)`:CAS LOCK_CNT 1→-1(读者独占升级);
   - **简化**:不做 write-waiter 公平优先抢占 / 随机唤醒策略 / 完整 Condition 唤醒链(标 deferred);条带 park 用简单 `await`/`signal`。
4. **重写 `PageMemoryNoStoreImpl` 为 v2**(替换单段 synchronized 版):
   - 多段字段:`Segment[] segments`、`DirectMemoryProvider`、`DataRegionConfiguration`、`freePageListHead:AtomicLong(INVALID_REL_PTR)`、`SEG_BITS=4`/`SEG_CNT=16`/`IDX_BITS=28`/`SEG_MASK`/`IDX_MASK`、`OffheapReadWriteLock rwLock`;
   - `Segment`(内嵌类):`pagesBase`、`lastAllocatedIdxPtr`(堆外 bump 指针)、`absolute(idx)`、`allocateFreePage(flags)`(CAS bump)、`size()`;`segment(idx)` 由 pageIdx 高 SEG_BITS 位解码;
   - `start()`:`directMemoryProvider.initialize(chunks)`(chunks[0]=initialSize,余段按 maxSize 分配)→ `addSegment(null)`;`stop()`:`directMemoryProvider.shutdown(true)`;
   - `allocatePage`:`borrowFreePage` 优先 → 空 `segments[len-1].allocateFreePage` → 末段满 `addSegment` → 都失败抛 `PageMemoryOutOfMemoryException`;组装 pageId + tag(`PageIdUtils.tag`)+ `writePageId` + `rwLock.init(addr+LOCK_OFFSET, tag)`;
   - `freePage`/`releaseFreePage`:CAS push 入 free-list;
   - `acquirePage`/`readLock`/`writeLock`/...:委托 `rwLock`(校验 tag,陈旧返回 `0L`/false);`releasePage` v2 仍近 no-op(Ignite 同款,无 per-page refcount)。
5. **新建 `learning/internal/mem/`**:`DirectMemoryProvider` 接口 + `UnsafeMemoryProvider`(`nextRegion()` → `OffHeap.allocateMemory` 包成 `DirectMemoryRegion`)+ `DirectMemoryRegion`(address,size)。
6. **新建 `learning/internal/pagemem/DataRegion` + `DataRegionConfiguration` + `DataRegionMetricsImpl`**(镜像 P03 §2.4):DataRegion POJO 四字段 + getter;Configuration(name/initialSize/maxSize/pageSize);Metrics 轻量(AtomicLong 计数)。
7. **demo + 测试**:分配→释放→再分配看 pageIdx 复用;多线程并发 alloc/free 无丢失/重复;读写锁互斥 + tag 陈旧检测 + upgrade;多段惰性增长;**demo 观测物理内存不无限增长**(分配大量页→释放→再分配,free-list 复用,堆外不持续涨)。

## 5. 验收 = 具名测试
> `/ignite-session-code` 在 `mvn test` 绿后核验这些具名测试存在且绿。

| 验收点 | 测试 |
|---|---|
| 释放后再分配**复用**同一 pageIdx(free-list 回收) | `FreeListTest#reuseAfterFree` |
| free-list pop 让 head 的 ABA 计数器单调 +1(防 ABA) | `FreeListTest#abaCounterMonotonic` |
| 多线程并发 alloc/free,**无丢失/重复**页(每 pageIdx 唯一) | `PageMemoryConcurrencyTest#noLostOrDuplicate` |
| 写锁持有时,读锁失败/阻塞(读写互斥) | `PageLockTest#readWriteMutex` |
| tag 变化(rotatePageId 后)`readLock` 返回 false(陈旧检测) | `PageLockTest#tagStaleDetection` |
| 读锁可升级为写锁(upgrade,1 reader → writer 独占) | `PageLockTest#upgradeToWriteLock` |
| `DataRegion` 经 config 装配 + lifecycle(`pageMemory().start()`/`stop()`),initialSize/maxSize 传递 | `DataRegionTest#lifecycleAndConfig` |
| 分配超过 initialSize 触发 `addSegment`(多段惰性增长) | `MultiSegmentTest#lazyGrowthBeyondInitial` |
| **demo**:分配大量页→释放→再分配,**堆外内存不无限增长**(free-list 复用) | `PageMemoryDemoTest#memoryNotUnbounded` |
- 可运行 demo:`start()` 一段 initialSize → 分配 N 页 → 全部 `freePage` → 再分配 N 页(观测 pageIdx 复用、堆外不再增长)→ 多线程压测 alloc/free 打印吞吐 + 一致性。

## 6. 引用路径(lint 核验对象)
```cited-paths
internal/pagemem/impl/PageMemoryNoStoreImpl.java
internal/util/OffheapReadWriteLock.java
internal/mem/DirectMemoryProvider.java
internal/mem/unsafe/UnsafeMemoryProvider.java
internal/mem/unsafe/UnsafeMemoryAllocator.java
internal/mem/DirectMemoryRegion.java
internal/processors/cache/persistence/DataRegion.java
configuration/DataRegionConfiguration.java
internal/processors/cache/persistence/DataRegionMetricsImpl.java
internal/processors/cache/persistence/IgniteCacheDatabaseSharedManager.java
```

---
**工时**:⭐⭐⭐⭐ / 4~6 天  **产出物**:`PageMemory` v2 完整实现(全局 Treiber free-list[侵入式 next + ABA] + 多段惰性增长 + 条带 `OffheapReadWriteLock`[8B 锁字 + tag 防陈旧 + upgrade] + `DataRegion` 容器 + `DirectMemoryProvider`)—— **Phase 3 收官**。PageMemory 接口契约不变(下游 S13/S16/S15 透明升级);自此"可管理、并发安全的堆外页内存"就位,Phase 4(WAL)/Phase 5(PageStore+B+树+Checkpoint→M1)建其上。
