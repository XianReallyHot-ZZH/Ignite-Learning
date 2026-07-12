# Phase 3 · 源码分析:页内存 PageMemory(镜像 `internal/pagemem/`)

> 本文档是 **phase 源码分析**层产物(见 roadmap §3 文档体系),为 Phase 3 的 Session 执行规格(S8~S9)提供 grounded 输入。
> 参考实现:`vendors/ignite/modules/core`(镜像包 `internal/pagemem/` + `internal/pagemem/impl/` + `internal/mem/`),外加跨模块的 `internal/util/GridUnsafe`(在 `modules/unsafe`)。
> 行号为 2.18.0 锚点,供对照阅读,非强约束。

---

## 1. 概览

Phase 3 解决一个根本问题:**怎么把数据放进"按页管理的堆外内存"里?** Ignite 的答案是一个**自研的页内存引擎**——把一段堆外内存切成固定大小的**页(page)**,每页一个 64-bit `pageId`,所有后续子系统(WAL / PageStore / B+树 / Checkpoint)都建在"页"之上。**这是 Ignite 性能的心脏,且完全隔离:不依赖集群、不依赖网络**(关联依赖锚点「持久化层完全隔离」),故可最先独立构建并单测。

本 phase 镜像 Ignite 的 **`PageMemory` + 纯内存实现 `PageMemoryNoStoreImpl`**(无 PageStore、无 WAL 的内存变体)。三件事定义了它:

1. **页 id 编码**(`PageIdUtils`/`FullPageId`/`PageIdAllocator`):把 `(partId, flag, pageIdx, rotation)` 打包进一个 `long`,并提供 rotation-blind 的 `effectivePageId`。
2. **堆外按页分配**(`PageMemoryNoStoreImpl`):用 `sun.misc.Unsafe.allocateMemory` 拿**分块(segment)**堆外内存,按指针算术切成页;**页"句柄"是裸 `long`(绝对地址指针),不是 `Page` 对象、不是 `ByteBuffer`**。
3. **页级 free list(回收复用)**:一个**全局无锁 Treiber 栈**,把"next 指针"**直接写在被释放页的前 8 字节**(复用 in-use 标记位),配 ABA 计数器,实现释放页复用。

- **覆盖 Session**:S8(PageMemory v1:页模型)、S9(PageMemory v2:DataRegion + free list)。
- **本 phase 明确不做**(边界,详见 §4 与 deferred.md):
  - **持久化变体 `PageMemoryImpl`**(带 PageStore/WAL/checkpoint buffer 的那个——它是 `internal/processors/cache/persistence/pagemem/` 下的另一文件,本 phase 只镜像 `PageMemoryNoStoreImpl` 的**内存**形态;持久化在 Phase 4~5)。
  - **页驱逐 / 页替换**(LRU/CLOCK):`PageMemoryNoStoreImpl` **本身一行驱逐逻辑都没有**(全文仅 OOM 报错信息提到 "eviction");驱逐策略是 `DataRegion` 之外的 `PageEvictionTracker` 协作者(NoOp / RANDOM_LRU / RANDOM_2_LRU),学习版列为 deferred。
  - **`freelist/` 包的 `CacheFreeList`/`PagesList`**(§4.1 重点澄清):那是**数据页内行级空闲空间**的管理(给变长行找有空间的页),**不是页回收 free list**;它是下游(S14/S16)的产物。
  - **`PageIO` 40 字节公共页头**(type/version/crc/pageId…):那是**页内容**的格式层(由 B+树/数据页解释),属 S12+;本 phase 把页当**裸字节**看待,只用 PageMemory 自身的 24 字节管理头。
  - **WAL delta 记录**、**checkpoint buffer**、**加密**、**压缩**、**CRC 校验**:全部下游/deferred。

> **现实校准(重要,别按 roadmap 字面写)**:roadmap S8 写的是"用 `DirectByteBuffer`(堆外)分配;页的 acquire/release **引用计数**"。但真实 Ignite 两者都不是:① 内存经 **`sun.misc.Unsafe.allocateMemory`** 按 segment 分块拿(`UnsafeMemoryAllocator`→`GridUnsafe.allocateMemory`=`Unsafe.allocateMemory`),**从不用 `ByteBuffer.allocateDirect`**;页句柄是**裸 `long` 指针**(`pageBuffer(long)` 才按需 `GridUnsafe.wrapPointer` 包成 `ByteBuffer`)。② `PageMemoryNoStoreImpl.releasePage` **几乎是个 no-op**(`trackAcquiredPages=true` 时只减一个计数器,**测试专用**),**没有 per-page refcount、没有 `Page` 对象、没有 pin 计数**——"并发安全"靠的是 S9 的**带 tag 的页内 8 字节 R/W 锁字**(`OffheapReadWriteLock`),不是数值引用计数。学习版 S8/S9 **按真实形态镜像**(裸 `long` + Unsafe + Treiber free-list + 页内锁字),把 roadmap 的"DirectByteBuffer/引用计数"措辞按本节校准落地。**分配材质**:`sun.misc.Unsafe`(JDK 内部,Ignite 自写层用它)最能保真(无锁 free-list 与页内锁字都依赖裸地址写);若想避开内部 API,JDK 21 的 `java.lang.foreign.MemorySegment`(FFM)是纯 JDK 等价物,列为本 phase 的实现期可选项(见 §4.7)。

---

## 2. 核心类与包清单

### 2.1 页 id 模型(`internal/pagemem/`,纯 JDK)
| 类 | 职责 | 锚点 |
|---|---|---|
| `PageIdUtils` | 纯位运算:打包/拆解 `pageId` 与 `link`;只 import `U`/`SB`,**零 Ignite 运行时依赖** | `internal/pagemem/PageIdUtils.java`(`PAGE_IDX_SIZE=32` :29,`PART_ID_SIZE=16` :32,`FLAG_SIZE=8` :35,`OFFSET_SIZE=8` :38,`ROTATION_ID_OFFSET=56` :64,`MAX_ITEMID_NUM=0xFE` :70,`pageId(part,flag,idx)` :161,`pageIndex` :105,`partId` :182,`flag` :174,`effectivePageId` :123,`link` :92,`rotatePageId` :197) |
| `FullPageId` | 复合键 `(grpId, pageId, effectivePageId)`;equals/hashCode **只看 `effectivePageId`+`grpId`**(rotation-blind) | `internal/pagemem/FullPageId.java`(`NULL_PAGE=(-1,-1)` :53,字段 :56-62,ctor :116,equals :145,hashCode :158,Stafford `mix64` :105,MH3 `mix32` :89) |
| `PageIdAllocator` | 接口:定义 flag 常量 `FLAG_DATA/FLAG_IDX/FLAG_AUX`、`META_PAGE_ID`、`INDEX_PARTITION`;`allocatePage`/`freePage` | `internal/pagemem/PageIdAllocator.java`(`FLAG_DATA=1` :32,`FLAG_IDX=2` :39,`FLAG_AUX=4` :45,`MAX_PARTITION_ID=65500` :48,`INDEX_PARTITION=0xFFFF` :51,`META_PAGE_ID` :54,`allocatePage` :63,`freePage` :71) |
| `PageSupport` | 接口:**页 acquire/release + 读/写锁**契约,**全部以裸 `long page` 指针为参**,锁失败返回 `0L` | `internal/pagemem/PageSupport.java`(`acquirePage→long` :37/:50,`releasePage` :58,`readLock→long` :67,`readLockForce` :77,`readUnlock` :86,`writeLock→long` :96,`tryWriteLock` :106,`writeUnlock` :118,`isDirty` :127) |
| `PageMemory` | 顶层接口,**`extends PageIdAllocator, PageSupport`** + 生命周期/尺寸/metrics | `internal/pagemem/PageMemory.java`(extends :26,`start` :30,`stop` :38,`pageSize` :43,`systemPageSize` :54,`pageBuffer(long)→ByteBuffer` :60,`loadedPages` :65,`metrics` :75) |

### 2.2 纯内存实现(`internal/pagemem/impl/`)
| 类 | 职责 | 锚点 |
|---|---|---|
| `PageMemoryNoStoreImpl` | **`internal/pagemem/` 里唯一的 PageMemory 实现**(无 PageStore/WAL);持 segment 数组 + 全局 Treiber free-list + 条带 R/W 锁 facade | `internal/pagemem/impl/PageMemoryNoStoreImpl.java`(类 :77,`PAGE_MARKER=0xBEEAAFDEADBEEF01L` :79,`PAGE_ID_OFFSET=8` :97,`LOCK_OFFSET=16` :100,`PAGE_OVERHEAD=24` :106,`SEG_CNT=16` :112,`freePageListHead` :136,`start` 分块 :239-280,`allocatePage` :309,`borrowFreePage` :678,`releaseFreePage` :642,`acquirePage` :503,`releasePage` :518,`readLock/writeLock` :529-593,`Segment` :755,`Segment.allocateFreePage` CAS :875) |
| `PageMemoryNoStoreImpl.Segment` | 一块堆外内存按指针算术切成等大页;**无锁 bump 分配器**(CAS 站内 `lastAllocatedIdxPtr`) | 同上(:755-927,`pagesBase` :769,`lastAllocatedIdxPtr` :766,`absolute(idx)=pagesBase+idx*sysPageSize` :838) |

### 2.3 堆外内存抽象(`internal/mem/`,跨切面工具)
| 类 | 职责 | 锚点 |
|---|---|---|
| `DirectMemoryProvider` | 接口:`initialize(long[])`/`nextRegion()`/`shutdown(boolean)`——**惰性**产出堆外 chunk | `internal/mem/DirectMemoryProvider.java` |
| `UnsafeMemoryProvider` | 默认 provider:每次 `nextRegion()` 经 `UnsafeMemoryAllocator` 拿一块 | `internal/mem/unsafe/UnsafeMemoryProvider.java` |
| `UnsafeMemoryAllocator` | 真正的分配:`return GridUnsafe.allocateMemory(size)` | `internal/mem/unsafe/UnsafeMemoryAllocator.java`(:25-27) |
| `DirectMemoryRegion` | `(address, size)` 指针对,一个 chunk 的返回值 | `internal/mem/DirectMemoryRegion.java` |
| `OffheapReadWriteLock` | **条带 R/W 锁**;每页头内嵌 8 字节锁字,`ReentrantLock[lockConcLvl]` 条带 | `internal/util/OffheapReadWriteLock.java`(4×16-bit 状态 :29-35,spin→条带 lock+Condition :131-161,`upgradeToWriteLock` :359) |
| `GridUnsafe` | `sun.misc.Unsafe` 包装(`allocateMemory`/`putLong`/`getLong`/`compareAndSwapLong`/`wrapPointer`) | `modules/unsafe/.../internal/util/GridUnsafe.java`(`allocateMemory=UNSAFE.allocateMemory` :1235-1237) |

### 2.4 DataRegion 容器(`internal/processors/cache/persistence/`)
| 类 | 职责 | 锚点 |
|---|---|---|
| `DataRegion` | **~85 行 POJO 容器**:`PageMemory` + `DataRegionConfiguration` + `DataRegionMetricsImpl` + `PageEvictionTracker`;四个 getter,**自身无生命周期代码** | `internal/processors/cache/persistence/DataRegion.java`(ctor :45-55,`pageMemory` :60,`config` :67,`metrics` :74,`evictionTracker` :81) |
| `DataRegionConfiguration` | 配置 bean:name、`initialSize`、`maxSize`、`pageEvictionMode`、`persistenceEnabled`、`lazyMemoryAllocation`… | `configuration/DataRegionConfiguration.java`(`initialSize/maxSize` 默认 :90-94) |
| `DataRegionMetricsImpl` | metrics 持有者 + 反指 PageMemory | `internal/processors/cache/persistence/DataRegionMetricsImpl.java`(:51) |
| `IgniteCacheDatabaseSharedManager` | 建 `DataRegion` 实例并 lifecycle `pageMemory().start()`(学习版只读其装配序) | `internal/processors/cache/persistence/IgniteCacheDatabaseSharedManager.java`(`startDataRegions` :289-296,`createPageMemory→new PageMemoryNoStoreImpl` :1386-1400,`createPageEvictionTracker` :1326-1345) |

### 2.5 持久化变体 + 消费者边界(下游,Phase 3 只读不实现)
| 类 | 职责 | 锚点 / 后续 |
|---|---|---|
| `PageMemoryEx` | `extends PageMemory` 的持久化扩展:checkpoint/restore/eviction 钩子 | `internal/processors/cache/persistence/pagemem/PageMemoryEx.java`(extends :36,`beginCheckpoint` :123,`checkpointWritePage` :143) — **Phase 4~5** |
| `PageMemoryImpl` | 带 PageStore/WAL 的持久 PageMemory(本 phase 不镜像) | `internal/processors/cache/persistence/pagemem/PageMemoryImpl.java`(无 `FreeList` 字段;站内 free-page 链注释 :109) — **Phase 4~5** |
| `CacheFreeList`/`AbstractFreeList`/`PagesList` | **行级** free space(数据页内放变长行);与页回收无关 | `internal/processors/cache/persistence/freelist/*` — **S14/S16** |
| `PageIO` / `PageHandler` | 页**内容**格式层(40B 公共头);锁下回调安全触页 | `.../persistence/tree/io/PageIO.java`、`.../tree/util/PageHandler.java` — **S12+** |
| `PageStore`/`IgnitePageStoreManager` | 页落盘 | `internal/pagemem/store/*` — **S13** |
| `IgniteWriteAheadLogManager` | WAL facade | `internal/pagemem/wal/IgniteWriteAheadLogManager.java` — **Phase 4 (S10~S11)** |

---

## 3. 关键数据/控制流 trace

### 3.1 pageId 编/解码往返(纯位运算,可单测)
```
[打包 pageId(partId, flag, pageIdx)]  PageIdUtils.pageId(:161)
  long id = flag & FLAG_MASK;                       // flag 入 bits 0-7
  id = (id << PART_ID_SIZE) | (partId & PART_ID_MASK);   // partId 入 bits 0-15,flag 上移到 16-23
  id = (id << PAGE_IDX_SIZE) | (pageIdx & PAGE_IDX_MASK); // pageIdx 入 bits 0-31

  最终 64-bit 布局(LSB→MSB):
  ┌──────────┬──────────┬───────────────┬──────────────────────────┐
  │ rotation │  flag    │    partId     │         pageIdx          │
  │  8 bit   │  8 bit   │    16 bit     │          32 bit          │
  │ bits63-56│ bits55-48│  bits47-32    │        bits31-0          │
  └──────────┴──────────┴───────────────┴──────────────────────────┘
   ROTATION_ID_OFFSET=56       EFFECTIVE_PAGE_ID_MASK=~(-1L<<48)(bits0-47)
   PAGE_ID_MASK=~(-1L<<56)(bits0-55)

[拆解]  pageIndex(id)=id & PAGE_IDX_MASK(:105)
        partId(id)  =(id >>> 32) & PART_ID_MASK(:182)
        flag(id)    =(id >>> 48) & FLAG_MASK(:174)
        effectivePageId(link)=link & EFFECTIVE_PAGE_ID_MASK(:123)   // 抹掉 flag+rotation
        rotationId  =(id >>> 56)(:189)
```
**要点**:① `pageIdx` 占低 32 位 ⇒ 单 partition 最多 ~2^32 页;`partId` 16 位 ⇒ 最多 65500(`MAX_PARTITION_ID`);② `effectivePageId` **只保留 pageIdx+partId**,抹掉 flag+rotation——这是 `FullPageId` 做 equals/hashCode 的依据,**rotation 变化不影响"同一逻辑页"判定**;③ 顶 8 位 rotation 在数据页里兼作"页内 item 偏移"(`link(pageId,itemId)`),在索引/aux 页里作"回收代"(每次回收 +1,`rotatePageId` 绕 0xFE→1 **永不为 0**)——让陈旧指针能识别"页已被复用"。

### 3.2 allocate / free 页(全局 Treiber free-list + segment bump)
```
[allocatePage(grpId, partId, flags)]  PageMemoryNoStoreImpl.allocatePage(:309)
  ┌─ ① borrowFreePage(grpId)(:678)   // 先问全局 free-list
  │    while: head=freePageListHead.get(); relPtr=head & ADDRESS_MASK
  │      relPtr==INVALID_REL_PTR → 返回(空,走 ②)
  │      seg=segment(pageIndex(relPtr)); absPtr=seg.absolute(idx)
  │      nextRelPtr=GridUnsafe.getLong(absPtr) & ADDRESS_MASK   // next 指针就在该页前 8 字节!
  │      cnt=(head & COUNTER_MASK)+COUNTER_INC                  // ABA 计数器 +1
  │      CAS(head → nextRelPtr|cnt) 成功 → putLong(absPtr,PAGE_MARKER)(标 in-use),返回 relPtr
  ├─ ② free-list 空 → segments[len-1].allocateFreePage(flags)(:875)
  │    lastIdx=GridUnsafe.getLongVolatile(null,lastAllocatedIdxPtr)
  │    CAS(lastAllocatedIdxPtr, lastIdx, lastIdx+1) → 返回新 pageIdx(站内 bump)
  ├─ ③ 末段满 → addSegment(seg0)(:718,synchronized+DCL) 拉 next chunk,再 bump
  └─ ④ 都失败 → IgniteOutOfMemoryException(:347,提示开 eviction)
  组装 id=PageIdUtils.pageId(partId,flags,pageIdx);writePageId(absPtr,id)(写进头 PAGE_ID_OFFSET)

[freePage / releaseFreePage(grpId, pageId)]  (:642)
  idx=pageIndex(pageId); relPtr=PageIdUtils.pageId(0,(byte)0,idx)   // 清掉 flag/tag
  absPtr=segment(idx).absolute(idx); writePageId(absPtr,relPtr)
  while: head=freePageListHead.get()
    GridUnsafe.putLong(absPtr, head & RELATIVE_PTR_MASK)   // 把旧 head 写进本页前 8 字节 = "我指向旧 head"
    CAS(head → relPtr) 成功 → 入栈完成(本页成新 head)
```
**要点**:free-list 是**侵入式(intrusive)**的——被释放页的**前 8 字节本身**就是链表 next 指针(与 in-use 时该位置放 `PAGE_MARKER` 复用),**零额外内存**;单个 `AtomicLong freePageListHead`(56 位地址 + 8 位 ABA 计数器)**全局跨所有 segment**,故释放的页可落在任意 segment,`segment(idx)` 由 id 高位解码定位(`fromSegmentIndex`);bump 分配器**只在末段**做,段满才 `addSegment` 拉新 chunk(`maxSize` 是上限,`initialSize` 起)。

### 3.3 页读/写锁(条带 + 页内 8 字节锁字 + tag 防陈旧)
```
[读]  pagePtr = pageMem.acquirePage(grpId, pageId)         // 返回裸 absPtr(头)
      dataPtr = pageMem.readLock(grpId, pageId, pagePtr)   // (:529)
        rwLock.readLock(pagePtr+LOCK_OFFSET, tag=PageIdUtils.tag(pageId))
          读 pagePtr+16 处 8 字节锁字 → 校验 tag==锁字里的 tag
          tag 不符 → return false → readLock 返回 0L(调用方知"我缓存的页已陈旧")
          通过 → LOCK_CNT +1,return dataPtr = pagePtr+PAGE_OVERHEAD(数据区指针)
      ... 用 dataPtr 读写页字节(GridUnsafe.getLong/putLong 或 pageBuffer)...
      pageMem.readUnlock(grpId, pageId, pagePtr)

[写]  dataPtr = writeLock(...)   // LOCK_CNT 0 → -1(独占);>0(有读者)→ 阻塞/失败
      ... 改页 ...
      writeUnlock(..., walPlc, dirtyFlag)   // dirty 标脏(下游 checkpoint/WAL 用)

[锁字 8 字节布局]  OffheapReadWriteLock(:29-35)
  ┌─────────────────┬─────────────────┬──────────┬──────────┐
  │ WRITE_WAIT_CNT  │ READ_WAIT_CNT   │   TAG    │ LOCK_CNT  │
  │     16 bit      │     16 bit      │  16 bit  │  16 bit   │
  └─────────────────┴─────────────────┴──────────┴──────────┘
  LOCK_CNT=0 自由;=-1 写锁(独占);>0 N 个读者。tag 变化 → 陈旧检测。
```
**要点**:① 锁**状态存在页本身**(`pagePtr+16`),不是外挂 map——pageId 的 `tag` 段每次回收换页就变,锁时校验 `tag` 即可发现"use-after-free / 页已被复用成别的东西";② 锁失败返回 `0L` 而非抛异常,调用方据此重读;③ `OffheapReadWriteLock` 自旋 `SPIN_CNT=32` 次再退化到条带 `ReentrantLock+Condition`,条带数 `nearestPow2(4*cores)`;④ 还有 `upgradeToWriteLock`(读者独占时升级 1→-1)。**学习版 S9 才做完整锁;S8 可用粗粒度锁或单线程简化。**

---

## 4. 关键设计与算法(为什么这么设计)

1. **裸 `long` 指针而非 `Page` 对象/`ByteBuffer`**:Ignite 全程用 `long absPtr` 当页句柄,数据访问用 `GridUnsafe.getLong(absPtr+off)`。**为什么**:页内存是热路径(每次缓存读写都触页),对象包装/`ByteBuffer` 视图会引入分配与间接;裸指针让"锁字、free-list next、pageId"全部**就地嵌在页头字节里**,零额外堆对象、零间接。代价:需要 `Unsafe`(JDK 内部)。⇒ 学习版按此镜像(§1 校准)。
2. **pageId 64 位编码 + `effectivePageId`**:把 `(partId,flag,pageIdx,rotation)` 压进一个 `long`,**一物多用**——既是 partition/flag/pageIdx 的身份,顶 8 位 rotation 又兼作回收代/项内偏移;`effectivePageId`(抹 flag+rotation)让"逻辑同一页"判定与回收代解耦,`FullPageId` 的 equals/hashCode 只认 effective——**页被回收复用后,旧 FullPageId 自然失效**(因 tag/rotation 变了),配合 §4.4 的 tag 锁实现安全。
3. **全局无锁 Treiber free-list(侵入式 next + ABA 计数器)**:回收复用是 S9 的核心。next 指针**写在被释放页前 8 字节**(复用 `PAGE_MARKER` 槽)→ 零额外内存;`freePageListHead` 一个 `AtomicLong`,高 8 位是 ABA 计数器(`COUNTER_INC` 每次 pop+1)——**为什么需要 ABA**:无锁栈经典问题,头 A→B→A 会被误判未变,计数器保证每次 pop 都让 head 值单调变。**为什么全局而非 per-segment**:简化 + 跨段复用,segment 由 id 高位解码定位。
4. **segment 分块 + 站内 bump CAS 分配**:不是一次 `allocateMemory(maxSize)` 大 slab,而是 `SEG_CNT=16` 个 segment、首块 `initialSize`、余块 `max((maxSize-initial)/15, 256MiB)`、**惰性**(`DirectMemoryProvider.nextRegion()`)。**为什么**:① 支持配置 `lazyMemoryAllocation`(用多少拿多少);② 末段 bump 分配只需 CAS 一个站内 `lastAllocatedIdxPtr`(也在堆外),无 Java 锁;③ 段满 `addSegment`(`synchronized`+DCL)扩容。学习版 S8 可先做单段,S9 再做多段+惰性增长。
5. **页内 8 字节锁字 + tag 防陈旧(use-after-free 安全)**:R/W 锁状态(`LOCK_CNT/TAG/READ_WAIT/WRITE_WAIT`)直接存在 `pagePtr+16`,条带外层 `ReentrantLock` 只用于争用时的 park/wake。**`tag` 机制是精髓**:锁时校验 `tag(pageId)==锁字里的 tag`,tag 随回收换页而变 ⇒ **陈旧指针(指向已被复用成别页的内存)能被安全检出**(锁返回 `0L`,不读到脏数据)。这是"无 GC 的堆外内存"做安全并发的关键不变量。
6. **`DataRegion` 是薄容器,驱逐是外挂协作者**:`PageMemoryNoStoreImpl` **零驱逐逻辑**(只 OOM 报错串提及);`DataRegion` 仅 POJO 装配(PageMemory+config+metrics+`PageEvictionTracker`);驱逐策略(NoOp/RANDOM_LRU/RANDOM_2_LRU)由 `IgniteCacheDatabaseSharedManager.createPageEvictionTracker` 按配置选,**回调**进 PageMemory 的 `freePage/allocatePage` 做实际回收。⇒ **学习版 S9 只做"PageMemory + 全局 free-list",不做驱逐也能正确**(驱逐列为 deferred 协作者)。
7. **分配材质:Unsafe vs FFM(实现期可选项)**:Ignite 用 `sun.misc.Unsafe.allocateMemory`(JDK 内部,2.18.0 仍可用,JDK 21 仅限制 object-unsafe 方法,memory 方法不受影响)。**保真首选 Unsafe**(无锁 free-list 与页内锁字都依赖裸地址写)。若要避开内部 API,JDK 21 的 `java.lang.foreign.MemorySegment`(FFM,纯标准 JDK)等价:`MemorySegment.allocateNative` + `ValueLayout` + `compareAndSet` 可实现同样设计,但 API 更繁。**建议**:S8/S9 用 Unsafe 包进一个小 `OffHeap` 助手(镜像 `GridUnsafe`),把裸 Unsafe 用法收敛到一处,可测且后续可整体换 FFM。这个选择留给 session-code 执行规格拍板(见 deferred)。

### 4.1 「free list」澄清(Phase 3 最易踩的坑)

Ignite 里**两个不相关的"free list"**,名字撞车但语义完全不同:

| | **页回收 free list**(S9 要做的) | **行级 free space**(下游 S14/S16) |
|---|---|---|
| 位置 | **`PageMemoryNoStoreImpl` 内部** | `internal/processors/cache/persistence/freelist/`(`CacheFreeList`/`PagesList`) |
| 管什么 | **整页**的回收复用(一个空闲页框池) | **数据页内**放变长行的空闲空间(按剩余字节数分桶) |
| 数据结构 | 全局 Treiber 栈,next 指针侵入式存页前 8 字节 | 256 桶 doubly-linked list(`PagesListNodeIO`),末桶 `REUSE_BUCKET=255` 兼做整页回收 |
| 接口 | `allocatePage/freePage`(返回 `long pageId`) | `FreeList<T extends Storable>.insertDataRow/removeDataRowByLink` |
| 谁用 | PageMemory 自己(给所有上层分配页) | 数据存储/B+树叶页(给变长 `CacheDataRow` 找家) |

**roadmap S9 "free list(释放的页可复用)" = 左列**。`freelist/` 包是右列,属下游(S14/S16),本 phase 只作为**边界只读**(§5)。`AbstractFreeList` 末尾的 `REUSE_BUCKET` 也兼整页回收,但那是给数据行的 freelist 自用的,不替代 PageMemory 的页回收 free-list。

---

## 5. 依赖与边界

- **上游依赖(应尽量少)**:
  - **页 id 模型**(`PageIdUtils`/`FullPageId`/`PageIdAllocator`/`PageSupport`/`PageMemory` 接口)= **纯 JDK + `internal/util.typedef.internal.{U,SB}`**(`PageIdUtils`、`FullPageId` 只 import 这些)。⇒ **可零运行时单测**。`PageMemory` 接口有一处轻微"上漏":`metrics()` 返回 `DataRegionMetricsImpl`(仅返回类型引用);学习版裁掉或换自己的 metrics 接口。
  - **实现**(`PageMemoryNoStoreImpl`)= JDK + `internal/mem`(堆外分配抽象)+ `internal/util`(`GridUnsafe`[unsafe 模块]、`OffheapReadWriteLock`)+ `internal/processors/cache.*`(`GridCacheSharedContext`[只取 logger+failure handler,可 null]、`DataRegionMetricsImpl`、`PageMetrics`、`PageIO`)。⇒ **无 discovery/communication/cluster**(依赖锚点「持久化层完全隔离」),`GridCacheSharedContext` 在学习版里可省或换成 `IgniteLogger`。
- **下游/消费者**:
  - **WAL**(Phase 4 S10~S11):页每次改写要产 WAL 记录;
  - **PageStore**(S13):页落盘;
  - **B+树 / PageIO**(S12/S14):把"页"当节点容器(用 40B 公共头解释页内容);
  - **行级 freelist** `CacheFreeList`(S14/S16):在数据页内放行;
  - **Checkpoint / `PageMemoryImpl`**(Phase 4~5/S15):脏页刷盘 + 启动恢复;
  - **本地缓存**(S16):值的最终落脚点。
- **对外契约**(供下游 session 用,本 phase 先立):`PageMemory` 接口(`allocatePage/acquirePage/releasePage/readLock/writeLock/pageSize/pageBuffer`)、`PageIdUtils`/`FullPageId`(页身份)、`DataRegion`(存储组容器)。下游不认 `ClusterNode`/cache 实体——本 phase 是纯存储地基。

---

## 6. 拆成 Session 的依据(S8 / S9)

按 **"隔离度 × 复杂度"递增**,每步都有可运行产物 + 单测。S8→S9 是 PageMemory 的 **v1→v2** 保真阶梯(v1 最小可运行 / v2 功能)。

| Session | v级 | 范围(本 phase 内) | 镜像要点 | 可运行验收 |
|---|---|---|---|---|
| **S8** | v1 | **PageMemory v1:页模型**——`PageIdUtils` 位运算(pageIdx/partId/flag/rotation 编解码 + `effectivePageId`)+ `FullPageId`(rotation-blind equals/hashCode)+ `PageIdAllocator` 接口(`FLAG_DATA/FLAG_IDX/FLAG_AUX`、`META_PAGE_ID`)+ `PageMemory` 接口(裁剪版:allocate/acquire/release/pageSize/pageBuffer)+ 一个**纯内存实现**:`OffHeap` 助手(包 `sun.misc.Unsafe.allocateMemory`)拿一块堆外内存切成 N 页,按 `pageId` 读写页字节,页头 24B(marker+pageId+lock,锁先占位/粗粒度)。**单段、无 free-list、无驱逐** | `PageIdUtils`/`FullPageId`/`PageIdAllocator`/`PageMemory`/`PageSupport`/`PageMemoryNoStoreImpl`(只取 start/acquire/release/pageBuffer/单段分配)、`GridUnsafe`(包进 `OffHeap`) | 单测:pageId 编解码往返(effectivePageId/flag/partId 各种值);分配 N 页→写字节→读回一致;`FullPageId` 相等性(rotation 变不影响) |
| **S9** | v2 | **PageMemory v2:DataRegion + free list + 并发**——① **全局 Treiber free-list**(页回收复用:next 指针侵入式存页前 8 字节 + ABA 计数器 + CAS,`freePage` 入栈 / `allocatePage` 先 pop);② **多段惰性增长**(`initialSize` 起,`maxSize` 上限,末段满 `addSegment`);③ **`DataRegion` 容器**(POJO:PageMemory+config+metrics,外部 lifecycle start);④ **条带 `OffheapReadWriteLock`**(页内 8 字节锁字 + `tag` 防陈旧 + read/write/upgrade);⑤ 并发安全分配/释放 | `PageMemoryNoStoreImpl`(borrowFreePage/releaseFreePage/addSegment/segment bump CAS)、`OffheapReadWriteLock`、`DataRegion`/`DataRegionConfiguration`/`IgniteCacheDatabaseSharedManager`(只读装配序)、`DirectMemoryProvider`/`UnsafeMemoryProvider` | demo:分配大量页→释放→再分配,观测**物理内存不无限增长**(free-list 复用);单测:free-list 复用正确、并发分配/释放无丢失/重复、R/W 锁互斥与 tag 陈旧检测 |

**为什么这么切**:S8 是 v1——**最小可运行页模型**(位运算 + 堆外按页读写),把"DirectByteBuffer/引用计数"按 §1 校准成"Unsafe + 裸 long";多段、free-list、驱逐、细锁逐项留给 S9/deferred。S9 是 v2——**功能完整**(回收复用 + 惰性多段 + 并发锁),把 Ignite 的「全局 Treiber free-list + 页内 tag 锁」忠实复现;驱逐策略与行级 freelist 列为 out-of-scope(下游)。S8 先行因它是 S9 的地基(没"页"谈不上"回收页");S9 把"并发安全的分配/释放"做到位。**任一步停下都有可运行成果**——符合北辰式 + 保真阶梯。

---

## 7. 源码阅读路线(由外到内,由简到难)

1. `PageIdUtils` —— 先吃透位运算(pageId 打包/拆解、effectivePageId、link、rotatePageId);这是纯函数,可立即单测。
2. `FullPageId` —— 看 effectivePageId 如何让 equals rotation-blind、Stafford/MH3 hash。
3. `PageIdAllocator` 接口 —— flag 常量、`META_PAGE_ID`、`INDEX_PARTITION`(分配"身份"语义)。
4. `PageSupport` + `PageMemory` 接口 —— 全部以裸 `long` 为参的契约(acquire/release/lock/pageBuffer);注意"没有 `Page` 类型"。
5. `PageMemoryNoStoreImpl` 头部 + `start()` —— 看分块(segment)、`PAGE_MARKER`/`PAGE_ID_OFFSET`/`LOCK_OFFSET`/`PAGE_OVERHEAD` 头布局。
6. `Segment` + `allocateFreePage` —— 站内 bump CAS(末段分配的本质)。
7. `borrowFreePage` / `releaseFreePage` —— **S9 灵魂**:侵入式 Treiber free-list + ABA。
8. `OffheapReadWriteLock` —— 页内 8 字节锁字、tag 防陈旧、条带。
9. `UnsafeMemoryAllocator` / `DirectMemoryProvider` —— `Unsafe.allocateMemory` 的实际落点(体会"按 chunk 而非 slab")。
10. `DataRegion` + `IgniteCacheDatabaseSharedManager.startDataRegions`/`createPageMemory` —— 容器装配与 lifecycle(只读,理解"谁拥有谁")。
11. *(边界只读)* `freelist/AbstractFreeList` §4.1 澄清点;`tree/io/PageIO` 40B 头(知下游怎么解释页内容);`pagemem/PageMemoryImpl`(知持久变体长啥样、为何本 phase 不做)。

---

## 8. 自检

- [x] **引用路径**:`scripts/check-cited-paths.sh` 对本文档全 OK(29 条,见 §10 附录)。
- [x] **依赖主张与锚点一致**:PageMemory 依赖 JDK + `internal/mem` + `internal/util`(Unsafe/锁),**无 discovery/communication/cluster**——与 roadmap 依赖锚点「持久化层完全隔离」一致,S8/S9 可完全独立单测。
- [x] **§6 每个 session 标了 v 级**(S8=v1 / S9=v2)。
- [x] **覆盖 S8/S9**:二者加起来 = Phase 3 全部(页模型 + DataRegion/free-list)。
- [x] **每步可运行可测**:S8 pageId 往返 + 按页读写;S9 free-list 复用 + 并发 + R/W 锁。
- [x] **CS 学生曲线**:S8 用裁剪接口 + 单段 + Unsafe 助手降门槛;S9 才上 Treiber free-list + 条带锁;驱逐/持久变体/行级 freelist 逐项 deferred,无单点过载。
- [x] **现实校准已标注**:roadmap 的"DirectByteBuffer/引用计数"按 §1 校准为"Unsafe + 裸 long + tag 锁";"free list"按 §4.1 澄清为页回收 Treiber 栈(非 `freelist/` 包)。

## 9. 修订记录

> session 代码若证伪本分析(发现真实结构与文中不符),在此回填并就地修正正文。
- (初始为空)

## 10. 引用路径(lint 核验对象)

```cited-paths
internal/pagemem/PageMemory.java
internal/pagemem/PageIdUtils.java
internal/pagemem/PageIdAllocator.java
internal/pagemem/FullPageId.java
internal/pagemem/PageSupport.java
internal/pagemem/impl/PageMemoryNoStoreImpl.java
internal/mem/DirectMemoryProvider.java
internal/mem/unsafe/UnsafeMemoryProvider.java
internal/mem/unsafe/UnsafeMemoryAllocator.java
internal/util/OffheapReadWriteLock.java
internal/processors/cache/persistence/pagemem/PageMemoryEx.java
internal/processors/cache/persistence/pagemem/PageMemoryImpl.java
internal/processors/cache/persistence/DataRegion.java
internal/processors/cache/persistence/DataRegionMetricsImpl.java
internal/processors/cache/persistence/IgniteCacheDatabaseSharedManager.java
configuration/DataRegionConfiguration.java
internal/processors/cache/persistence/freelist/FreeList.java
internal/processors/cache/persistence/freelist/AbstractFreeList.java
internal/processors/cache/persistence/freelist/CacheFreeList.java
internal/processors/cache/persistence/freelist/PagesList.java
internal/processors/cache/persistence/freelist/io/PagesListNodeIO.java
internal/processors/cache/persistence/freelist/io/PagesListMetaIO.java
internal/processors/cache/persistence/tree/io/PageIO.java
internal/processors/cache/persistence/tree/util/PageHandler.java
internal/pagemem/store/PageStore.java
internal/pagemem/store/IgnitePageStoreManager.java
internal/pagemem/wal/IgniteWriteAheadLogManager.java
modules/unsafe/src/main/java/org/apache/ignite/internal/util/GridUnsafe.java
modules/unsafe/src/main/java/org/apache/ignite/internal/pagemem/PageUtils.java
```

> 写完后:据此**顺序产出**各 session 执行规格(`specs/sessions/S08-page-memory-v1.md`、`S09-data-region-freelist.md`,从 `_TEMPLATE-spec.md`),并在 roadmap 对应 S8/S9 块挂 `**执行规格**:` 链接。
