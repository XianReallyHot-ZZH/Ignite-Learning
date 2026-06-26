# 如何读 Ignite 源码(Reading Primer)

> 全局参考。Ignite 源码体量极大(`core` 约 4,241 个 Java 文件),没有阅读策略会迷路。本 primer 给你一条"主线 + 入口 + 策略"。
> 路径相对 `vendors/ignite/modules/core/src/main/java/org/apache/ignite/`(下称 `…/`)。所有路径/行号均已核实。

## 1. 先建立总体地图:三层结构

Ignite core 源码分三层,**先分清你在哪一层**:

1. **public API 层**(顶层包 `cache/`、`compute/`、`transactions/`、`cluster/`…):用户调的接口。薄、稳定。→ 从这**入门**,知道"对外长什么样"。
2. **internal 内部层**(`internal/managers/` + `internal/processors/`):真正干活的地方。Manager=跨切面服务,Processor=功能引擎。→ **大部分时间花在这**。
3. **SPI 层**(`spi/`):可插拔契约(discovery/communication/…)。→ 读 **接口** 就懂"要满足什么契约",实现是其中一个样例。

> 记住比例:`internal/processors/` 一个目录就占了 core 近一半代码;其中 `processors/cache/`(910 文件)又占大头。**别想一次读懂 cache。**

## 2. 主线:从 `IgniteKernal.start()` 看启动顺序

一个节点的所有组件在 `IgniteKernal.start()` 里依次构造/启动。**这条启动序是最好的"目录"**——按它出现的顺序读,就是组件被激活的真实次序。已核实行号(`internal/IgniteKernal.java`):

| 行 | 调用 | 说明 |
|---|---|---|
| ~984 | `startProcessor(new GridClosureProcessor(ctx))` | 计算(先构造,后用) |
| ~1004 | `startManager(new GridIoManager(ctx))` | **通信路由**中枢 |
| ~1016 / ~1045 | `new GridDiscoveryManager(ctx)` | **发现**(服务端/客户端两条路径) |
| ~1059 | `startProcessor(new GridAffinityProcessor(ctx))` | 分片 |
| ~1070 | `startProcessor(new GridCacheProcessor(ctx))` | **缓存**(最重) |

**注意一个反直觉点**:构造序里 `GridIoManager`(通信)在 `GridDiscoveryManager`(发现)**之前**构造——但这只是构造,二者都要等节点 join 完成(发现跑通)才真正激活。**别据此以为通信依赖发现的反向也成立**——实际是 `GridIoManager` 单向**读** Discovery 拓扑来寻址(见 roadmap 依赖锚点)。

**怎么用这条主线**:在 IDE/Grep 里打开 `internal/IgniteKernal.java`,跳到 `start()`,顺着这些 `startProcessor/startManager` 调用一个个点进去,你就把整个 kernal 的组件装配过了一遍。

## 3. 阅读策略(四条原则)

1. **先读契约(SPI/接口),再读实现。** 例:想懂发现,先读 `spi/discovery/DiscoverySpi.java`(它定义"一个发现必须能做什么"),再看 `TcpDiscoverySpi` 怎么实现。这样你抓住"不变量",不被实现细节淹没。
2. **先读 public API,再追 internal。** 例:从 `compute/IgniteCompute.java` 的 `call/affinityCall` 顺着实现追到 `internal/IgniteComputeImpl` → `GridClosureProcessor` → 经 `GridIoManager` 发送。一条调用链串起多个子系统。
3. **追一条消息/数据的流,而不是平铺读目录。** 例:追一个 `cache.put(k,v)` 从 API → `GridCacheProcessor` → (分布式时)经 `GridIoManager` → 对端 `GridCacheProcessor` → 落 `PageMemory`/WAL。**一条流胜过读十个类**。
4. **用 test 类当用法样例。** `internal/...` 的同名 test(或 `ignite-core` 的 test 源码集)是最好的"这玩意儿怎么用"的文档。卡住时去找对应 test。

## 4. 各子系统的"入口类"清单(读到这就找到了门)

| 子系统 | 入口类(按这个顺序读) |
|---|---|
| 生命周期 | `Ignition` → `internal/IgniteKernal` → `internal/GridKernalContext` |
| 发现 | `spi/discovery/DiscoverySpi`(接口)→ `spi/discovery/tcp/TcpDiscoverySpi` → `ServerImpl`/`ClientImpl` → `managers/discovery/GridDiscoveryManager` + `DiscoCache` |
| 通信 | `internal/util/nio/GridNioServer`(底座)→ `spi/communication/tcp/TcpCommunicationSpi` → `managers/communication/GridIoManager` + `internal/GridTopic` |
| 页内存 | `internal/pagemem/PageMemory`(接口)→ `PageMemoryNoStoreImpl` → `PageIdAllocator`/`PageIdUtils` |
| 持久化 | `processors/cache/persistence/GridCacheDatabaseSharedManager`(协调)→ `wal/FileWriteAheadLogManager`、`tree/`(B+树)、`checkpoint/`、`metastorage/` |
| 缓存 | `processors/cache/GridCacheProcessor`(总管)→ `GridCacheContext`(接线台)→ `CacheObjectImpl` |
| 分片 | `cache/affinity/rendezvous/RendezvousAffinityFunction`(算法)→ `processors/affinity/GridAffinityProcessor` |
| PME | `processors/cache/` 下 `*PartitionExchange*`(`GridDhtPartitionsExchangeFuture`、`ExchangeWorker`) |
| DHT | `processors/cache/distributed/dht/`(`GridDht*`)、`distributed/near/` |
| 事务 | `processors/cache/transactions/`(`IgniteInternalTx` 及协调/prepare/commit future) |
| SQL | `processors/query/GridQueryProcessor` → `modules/indexing/`(H2 集成);`modules/calcite/`(新引擎,选读) |
| 计算 | `internal/IgniteComputeImpl` → `processors/closure/GridClosureProcessor` → `managers/failover`/`loadbalancer`/`collision` |

## 5. 实操技巧

- **Grep 是主力**:在 `vendors/ignite/modules/core` 里按类名/消息类型/topic 直接 grep;IDE 的"查找用法"定位调用方。
- **先抓 lifecycle**:每个 manager/processor 都有 `start()/stop()`(实现 `GridComponent`/`Lifecycled`)。读 `start()` 能快速知道它依赖谁、初始化了什么。
- **看消息工厂**:`GridIoMessageFactory` / 各 SPI 的 message 工厂里,类型 ID ↔ 消息类的映射,是理解"网上传什么"的速查表。
- **画时序图**:读到分布式交互(PME/2PC/发现 join),随手画节点间时序,比纯读代码高效十倍。

## 6. 心态

- **不要追求"读完"。** 这不是一个能读完的代码库。目标是"按你需要实现的那块,读懂它的设计与数据流"——这正是本学习路线 Session 化的意义:每个 Session 只逼你读懂一小块。
- **读不懂就先放,往后做。** 很多设计要在你亲手实现相邻层后才豁然开朗。边做边回头读,比硬啃有效。
