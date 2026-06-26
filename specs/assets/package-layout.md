# 学习者实现 · 包结构总览(Package Layout)

> 全局参考:给你"从零手搓 Ignite"的实现一个**对齐 Ignite 的代码家**。每个 Session 的代码往哪写、新增哪些包/类,都在这里对号入座。
> 原则:**包名对齐 Ignite**(便于对照 `vendors/ignite` 源码),但根包用自己的命名空间,避免与 vendored 源码混淆。

## 1. 顶层结构:每个 Session 一个独立多模块工程

所有手搓代码放在仓库根的 `ignite-gogogo/` 下;**每个 Session = 一个独立、可单独打开运行的多模块 Maven 工程**,目录 `ignite-gogogo/sNN-短名/`(短名对齐 `specs/sessions/SNN-短名.md`)。新 Session 通常**以上一 Session 的工程为起点复制再扩展**(增量)。

```
ignite-gogogo/                                # 所有 Session 工程的容器
├─ README.md                                  # 本目录约定
├─ s01-skeleton/                              # ← S1:独立多模块 Maven 工程
│  ├─ pom.xml                                 #   父 pom(aggregator,对照 Ignite 根 pom)
│  └─ core/                                   #   子模块(对照 Ignite 的 modules/core)
│     ├─ pom.xml
│     ├─ src/main/java/org/apache/ignite/learning/   # ← 我们的 Java 根包
│     └─ src/test/java/org/apache/ignite/learning/
├─ s02-nio-warmup/                            # ← S2:复制 s01 → 扩展
├─ s03-nio-engine/                            # ← S3
└─ …                                          # 后期 Session 可新增 feature 子模块(如 …/indexing/)
```

> - **模块划分对齐 Ignite**:早期 Session 只需 `core` 子模块;后期(引入 SQL 等)再按 Ignite 风格新增 feature 子模块(如 `indexing`)。
> - **对照参考**:每个工程内的 Java 包根 `org/apache/ignite/learning/` 对照 `vendors/ignite/modules/core/src/main/java/org/apache/ignite/`。下文 §2 的"对应 Ignite"列即指此根下的相对路径。
> - 详细包 ↔ Ignite ↔ Session 映射见 §2;每个 Session 的具体代码骨架落点见其教学文档「执行前分析」。

## 2. 包 ↔ Ignite ↔ Session 映射

> 下表"学习者包"指**每个 Session 工程内** `core/src/main/java/org/apache/ignite/` 下的相对包路径。"填充 Session"列指向 `specs/ignite-complete-learning-roadmap.md` 的 Session 编号。空 = 跨多 Session / 基础设施。

### 2.1 public API 层(薄、稳定,先定义契约)

| 学习者包 | 对应 Ignite | 职责 | 填充 Session |
|---|---|---|---|
| `learning/` 根(`Ignite.java`、`Ignition.java`) | `Ignite.java`、`Ignition.java` | 节点入口 | S0+ / 渐进 |
| `learning/cache/`(`IgniteCache`、`Cache`) | `cache/` | KV 缓存 public API | S16 |
| `learning/cache/affinity/`(`AffinityFunction`、`Rendezvous…`) | `cache/affinity/` | 分片函数 | S22 |
| `learning/compute/`(`ComputeTask`、`ComputeJob`) | `compute/` | 计算 public API | S33 |
| `learning/transactions/`(`Transaction`、`TransactionIsolation`) | `transactions/` | 事务 public API | S26 |
| `learning/configuration/`(`CacheConfiguration`…) | `configuration/` | 配置 | S17 |

### 2.2 SPI 层(可插拔契约 + 默认实现)

| 学习者包 | 对应 Ignite | 职责 | 填充 Session |
|---|---|---|---|
| `learning/spi/discovery/`(接口) + `…/tcp/`(`TcpDiscoverySpi`、`ServerImpl`、`ClientImpl`、`ipfinder/`) | `spi/discovery/`、`spi/discovery/tcp/` | 集群发现(裸 ServerSocket) | S18~S19 |
| `learning/spi/communication/`(接口) + `…/tcp/`(`TcpCommunicationSpi`) | `spi/communication/`、`spi/communication/tcp/` | 节点通信(基于自研 NIO) | S20 |

### 2.3 internal 基础设施层

| 学习者包 | 对应 Ignite | 职责 | 填充 Session |
|---|---|---|---|
| `learning/internal/IgniteKernal.java`、`GridKernalContext.java` | `internal/IgniteKernal`、`GridKernalContext` | 生命周期 + 全局上下文 | S0+ / 渐进 |
| `learning/internal/util/` | `internal/util/` | 并发原语、工具 | S1~S2 |
| `learning/internal/util/nio/`(`NioServer`、`FilterChain`、`Worker`) | `internal/util/nio/` | **异步 NIO 引擎** | S3~S5 |
| `learning/internal/marshaller/`(`Marshaller` 接口 + optimized) | `internal/marshaller/` | 对象序列化 | S7 |
| `learning/internal/direct/`(`DirectMessageReader/Writer`) | `internal/direct/` | 协议消息编解码 | S6 |
| `learning/internal/GridTopic.java` | `internal/GridTopic` | 消息主题 | S21 |

### 2.4 internal 存储与持久化层

| 学习者包 | 对应 Ignite | 职责 | 填充 Session |
|---|---|---|---|
| `learning/internal/pagemem/`(`PageMemory`、`PageIdAllocator`、`DataRegion`) | `internal/pagemem/` | **页内存** | S8~S9 |
| `learning/internal/pagemem/store/`(`PageStore`) | `internal/pagemem/store/` | 页落盘 | S13 |
| `learning/internal/pagemem/wal/`(WAL 接口) | `internal/pagemem/wal/` | WAL 抽象 | S10 |
| `learning/internal/processors/cache/persistence/`(`GridCacheDatabaseSharedManager`) | `processors/cache/persistence/` | 存储协调 + 恢复 | S15 |
| `…/persistence/wal/`(`FileWriteAheadLogManager`) | `…/persistence/wal/` | WAL 实现 + 回放 | S10~S11 |
| `…/persistence/tree/`(B+树) | `…/persistence/tree/` | 持久 B+树 | S12、S14 |
| `…/persistence/freelist/` | `…/persistence/freelist/` | 空闲页回收 | S9、S14 |
| `…/persistence/checkpoint/` | `…/persistence/checkpoint/` | Checkpoint | S15 |
| `…/persistence/metastorage/`(`MetaStorage`) | `…/persistence/metastorage/` | 元数据存储 | S15 |

### 2.5 internal 缓存/分片/分布式层

| 学习者包 | 对应 Ignite | 职责 | 填充 Session |
|---|---|---|---|
| `…/processors/cache/`(`GridCacheProcessor`、`GridCacheContext`、`CacheObjectImpl`) | `processors/cache/` | **缓存总管 + 接线台** | S16~S17 |
| `…/processors/affinity/`(`GridAffinityProcessor`) | `processors/affinity/` | 分片管理 | S22 |
| `…/processors/cache/`(`CacheAffinitySharedManager`、`*PartitionExchange*`) | `processors/cache/` | **PME** | S23 |
| `…/processors/cache/distributed/dht/`(`GridDht*`) | `processors/cache/distributed/dht/` | DHT 主干 | S24~S25 |
| `…/processors/cache/distributed/near/` | `processors/cache/distributed/near/` | Near cache | S24 |
| `…/processors/cache/transactions/`(`IgniteInternalTx`、2PC futures) | `processors/cache/transactions/` | **分布式事务** | S26~S29 |

### 2.6 internal managers(跨切面)

| 学习者包 | 对应 Ignite | 职责 | 填充 Session |
|---|---|---|---|
| `…/managers/discovery/`(`GridDiscoveryManager`、`DiscoCache`) | `managers/discovery/` | 发现管理 + 拓扑视图 | S18~S19 |
| `…/managers/communication/`(`GridIoManager`) | `managers/communication/` | 消息路由 | S21 |
| `…/managers/failover/`、`loadbalancer/`、`collision/` | `managers/failover/` 等 | 计算 failover/负载/碰撞 | S35 |

### 2.7 查询与计算(栈顶)

| 学习者包 | 对应 Ignite | 职责 | 填充 Session |
|---|---|---|---|
| `…/processors/query/`(`GridQueryProcessor` + H2 桥) | `processors/query/`、`modules/indexing/` | **SQL**(集成 H2) | S30~S32 |
| `…/processors/closure/`(`GridClosureProcessor`) | `processors/closure/` | **计算**编排 | S33~S35 |
| `learning/internal/IgniteComputeImpl.java` | `internal/IgniteComputeImpl` | 计算实现 | S33 |

## 3. 使用方式

1. **建工程**:开始一个 Session 时,在 `ignite-gogogo/` 下建 `sNN-短名/`——通常**复制上一 Session 的工程目录**作为起点(第一个 Session 从零建父 pom + `core`)。增量就落在这些独立工程上。
2. **执行前分析**:在本 Session 教学文档里,明确"本次在 §2 哪个包新增/修改哪些类"——即代码骨架落点。
3. **对照阅读**:写到 `learning/internal/pagemem/PageMemory.java` 时,并行打开 `vendors/.../internal/pagemem/PageMemory.java` 对照设计(不是抄)。
4. **渐进生长**:`GridKernalContext` / `IgniteKernal` 这种"接线"类会随 Session 增多逐步填充——别一开始就写满,用到谁加谁(随工程复制带到后续 Session)。
