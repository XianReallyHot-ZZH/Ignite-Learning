# Ignite 术语表(Glossary)

> 跨 Session 复用的全局参考。Ignite 行话 → 通俗解释 + 源码位置。
> 路径相对 `vendors/ignite/modules/core/src/main/java/org/apache/ignite/`(下简称 `…/`)。所有路径均已核实存在。
> 对应学习路线:`specs/ignite-complete-learning-roadmap.md`。

## 1. 生命周期与架构

| 术语 | 通俗解释 | 源码位置 |
|---|---|---|
| **IgniteKernal / Kernal** | 一个 Ignite 节点的"引擎",掌管生命周期、启动/停止所有组件 | `…/internal/IgniteKernal.java` |
| **Ignition** | 启动入口/工厂(`Ignition.start()`) | `…/Ignition.java` |
| **GridKernalContext** | 全局上下文:持有所有 manager/processor 的引用,组件间靠它互相拿 | `…/internal/GridKernalContext.java` |
| **SPI (Service Provider Interface)** | 可插拔组件的契约(发现/通信/检查点/…),让实现可替换 | `…/spi/` |
| **Manager vs Processor** | Manager=跨切面的长生命周期服务(discovery/communication);Processor=某功能的引擎(cache/compute/query) | `…/internal/managers/`、`…/internal/processors/` |

## 2. 发现与通信(Discovery / Communication)

| 术语 | 通俗解释 | 源码位置 |
|---|---|---|
| **Discovery / DiscoverySpi** | 集群成员发现:谁在线、拓扑如何、谁加入/离开 | `…/spi/discovery/` |
| **TcpDiscoverySpi** | 默认发现实现:基于 TCP 环形心跳(**裸 `ServerSocket`,不用 GridNioServer**) | `…/spi/discovery/tcp/TcpDiscoverySpi.java` |
| **ServerImpl / ClientImpl** | 发现协议的服务端/客户端实现 | `…/spi/discovery/tcp/ServerImpl.java`、`ClientImpl.java` |
| **IpFinder** | 节点初次发现彼此用的共享地址表 | `…/spi/discovery/tcp/ipfinder/` |
| **GridDiscoveryManager** | 发现管理器:维护拓扑、对外发 `DiscoveryEvent` | `…/internal/managers/discovery/GridDiscoveryManager.java` |
| **DiscoCache** | 当前拓扑的缓存视图——**其他所有层读的"拓扑真相"** | `…/internal/managers/discovery/DiscoCache.java` |
| **Topology / AffinityTopologyVersion** | 拓扑版本,每次成员变更递增;affinity/PME 都按它对齐 | `…/internal/processors/affinity/AffinityTopologyVersion.java` |
| **Communication / CommunicationSpi** | 节点间点对点消息传输 | `…/spi/communication/` |
| **TcpCommunicationSpi** | 默认通信实现:基于 TCP + **自研 NIO 引擎(GridNioServer)** | `…/spi/communication/tcp/TcpCommunicationSpi.java` |
| **GridIoManager** | 消息路由中枢:按 **topic** 把入站消息分发给处理器 | `…/internal/managers/communication/GridIoManager.java` |
| **GridTopic** | 消息主题枚举(路由键) | `…/internal/GridTopic.java` |
| **GridNioServer** | Ignite 自研异步 NIO 框架(通信传输底座) | `…/internal/util/nio/GridNioServer.java` |
| **Marshaller** | 对象↔字节序列化(可插拔) | `…/modules/binary/{api,impl}/.../marshaller/`(2.18.0 迁出 core) |
| **Direct message** | 紧凑二进制协议消息(`DirectMessageReader/Writer` 按字段读写) | `…/internal/direct/` |

## 3. 存储与持久化(PageMemory / WAL / B+树)

| 术语 | 通俗解释 | 源码位置 |
|---|---|---|
| **PageMemory** | 堆外按固定大小"页"管理的内存——Ignite 性能的心脏 | `…/internal/pagemem/PageMemory.java` |
| **Page / FullPageId / PageIdAllocator** | 页、页的全局 id、页分配器 | `…/internal/pagemem/` |
| **DataRegion** | 一段可配大小的堆外内存区域(页从这分配) | `…/internal/pagemem/` |
| **WAL (Write-Ahead Log)** | 预写日志:改页前先写日志,崩溃后用它恢复 | `…/internal/pagemem/wal/`、`…/internal/processors/cache/persistence/wal/FileWriteAheadLogManager.java` |
| **Checkpoint** | 把脏页刷盘 + 写一条 checkpoint 记录(此后旧 WAL 可截断) | `…/internal/processors/cache/persistence/checkpoint/` |
| **PageStore** | 页落盘存储(每个 cache/partition 一个文件,按页偏移读写) | `…/internal/pagemem/store/` |
| **B+Tree** | 持久化索引结构,建在"页"上(节点=页) | `…/internal/processors/cache/persistence/tree/` |
| **FreeList** | 空闲页/空闲槽的回收复用 | `…/internal/processors/cache/persistence/freelist/` |
| **MetaStorage** | 元数据存储(存页分配游标等) | `…/internal/processors/cache/persistence/metastorage/` |
| **GridCacheDatabaseSharedManager** | 存储引擎协调者:管理存储生命周期与崩溃恢复 | `…/internal/processors/cache/persistence/GridCacheDatabaseSharedManager.java` |

## 4. 缓存与分片(Cache / Affinity / PME / DHT)

| 术语 | 通俗解释 | 源码位置 |
|---|---|---|
| **Cache / IgniteCache** | 对外的 KV 缓存 API;实现见 processors/cache | `…/internal/processors/cache/` |
| **CacheObject** | 缓存值的内部表示(持 byte[] + 类型) | `…/internal/processors/cache/CacheObjectImpl.java` |
| **GridCacheContext** | 单个 cache 的"中央接线台":持有该 cache 用到的所有依赖 | `…/internal/processors/cache/GridCacheContext.java` |
| **CacheObjectContext / CacheGroupContext** | 值序列化上下文 / 多 cache 共享的存储组 | `…/internal/processors/cache/` |
| **CacheConfiguration** | 缓存配置(名、备份数、过期等) | `…/configuration/` |
| **Affinity / AffinityFunction** | 数据分片函数:key → 分区 → 节点 | `…/cache/affinity/`、`…/internal/processors/affinity/` |
| **RendezvousAffinityFunction** | 默认一致性哈希分片函数(节点增减时迁移最小) | `…/cache/affinity/rendezvous/RendezvousAffinityFunction.java` |
| **Partition** | 数据分片单元(Ignite 默认把一个 cache 切成很多分区) | `…/internal/processors/cache/` |
| **Primary / Backup** | 某分区的**主节点 / 备份节点** | `…/internal/processors/cache/distributed/` |
| **PME (Partition Map Exchange)** | 拓扑变化后,节点间重新对齐"谁有哪些分区"的交换流程 | `…/internal/processors/cache/`(`*PartitionExchange*`) |
| **CacheAffinitySharedManager** | 统一管理多个 cache 的 affinity | `…/internal/processors/cache/CacheAffinitySharedManager.java` |
| **DHT (Distributed Hash Table)** | 分布式哈希表:数据按 affinity 散在多节点 | `…/internal/processors/cache/distributed/` |
| **Near / DHT / Colocated cache** | 客户端本地缓存 / 主干分布式缓存 / 与数据共置的缓存 | `…/internal/processors/cache/distributed/near/`、`dht/` |

## 5. 事务(Transaction)

| 术语 | 通俗解释 | 源码位置 |
|---|---|---|
| **Transaction / IgniteInternalTx** | 事务对象(begin/commit/rollback) | `…/transactions/`、`…/internal/processors/cache/transactions/` |
| **2PC (Two-Phase Commit)** | 两阶段提交:prepare(各参与者预提交)→ commit/rollback | `…/internal/processors/cache/transactions/` |
| **Isolation level** | 隔离级别:READ_COMMITTED / REPEATABLE_READ / SERIALIZABLE | `…/transactions/TransactionIsolation.java` |
| **MVCC** | 多版本并发控制:写产生新版本,读不阻塞写 | `…/internal/processors/cache/`(MVCC 相关类) |
| **Deadlock detection** | 死锁检测(构建等待图、发现环即回滚) | `…/internal/processors/cache/transactions/` |

## 6. 计算(Compute)

| 术语 | 通俗解释 | 源码位置 |
|---|---|---|
| **Compute / IgniteCompute** | 计算网格 public API(`compute/` 包) | 实现:`…/internal/IgniteComputeImpl.java` |
| **ComputeTask / ComputeJob** | 任务/作业(支持 map-reduce 模型) | `…/compute/` |
| **GridClosureProcessor** | 计算编排器:派发、回收、failover | `…/internal/processors/closure/GridClosureProcessor.java` |
| **Failover / LoadBalancer / Collision** | 失败转移 / 负载均衡 / 本地作业碰撞(排队限流)管理 | `…/internal/managers/failover/`、`loadbalancer/`、`collision/` |

## 7. SQL

| 术语 | 通俗解释 | 源码位置 |
|---|---|---|
| **SQL via H2 / Calcite** | Ignite **不自己写** SQL 优化器:集成 H2(并在向 Calcite 迁移) | `vendors/ignite/modules/indexing/`、`modules/calcite/` |
| **GridQueryProcessor** | 查询编排器 | `…/internal/processors/query/` |
| **Indexing (H2)** | 二级索引(也建在 PageMemory/B+树上) | `…/internal/processors/query/h2/` |

## 8. 客户端与扩展(后置/参考)

| 术语 | 通俗解释 | 源码位置 |
|---|---|---|
| **Thin client** | 轻量客户端(不加入集群,走二进制线协议) | `…/internal/client/` |
| **ODBC / JDBC** | SQL 线协议驱动 | `…/internal/processors/odbc/`、`…/internal/jdbc/`、`jdbc2/` |
| **Service Grid** | 服务网格:部署长生命周期服务(集群单例/亲和) | `…/internal/processors/service/` |
| **Continuous Query** | 持续查询:数据变更的订阅/通知 | `…/internal/processors/continuous/` |
| **Data Streamer** | 高吞吐批量写入(自动按分区汇聚) | `…/internal/processors/datastreamer/` |
| **Data structures** | 分布式原子量(`IgniteAtomicLong`/`Sequence`/`Lock`…) | `…/internal/processors/datastructures/` |
