# 进度清单(PROGRESS)

> 本文件是**活的进度追踪**:`ignite-complete-learning-roadmap.md` 是"计划",本文件是"状态"。
> 每完成一个产物(分析文档 / 执行规格 / 讲义 / 代码工程 / 测试)就更新对应行;将来由 skill 自动维护。
> 图例:☐ 未开始 · ◐ 进行中 · ☑ 完成

## 当前位置
- **最近完成**:**Phase 3 源码分析** —— `specs/phases/P03-page-memory-analysis.md`(8 节 + §6 v级拆分 + §4.1「free list」澄清 + §1 现实校准:`Unsafe`+裸 `long` 非 DirectByteBuffer/引用计数;`freelist/` 包是行级非页回收);`check-cited-paths` **29/29 OK**。上一步:S7 Marshaller v2(`s07-marshaller/`、`mvn test` **31 passed**)→ **Phase 2 收官 ✅**。
- **下一步**:**Phase 3 存储支(S8~S9)** —— `/ignite-session-doc 08`(页内存 v1:PageIdUtils 位运算 + 纯内存实现)→ `/ignite-session-code 08` → S9(DataRegion + Treiber free-list + 条带 R/W 锁)。**完全隔离,无需集群;M1 要到 S15。**
- **试点**:Phase 1(NIO)流水线验证中;Phase 0(S1~S2)试点期间暂越过(真做课程时 Phase 0 先行)。

## 基础设施(已建立)
- ☑ roadmap + 依赖 DAG + 文档体系(`specs/ignite-complete-learning-roadmap.md`)
- ☑ 全局资产:glossary / reading-ignite-source / package-layout(`specs/assets/`)
- ☑ 执行规格模板(`_TEMPLATE-spec.md`)+ 讲义模板(`docs-learn/_TEMPLATE-handout.md`)
- ☑ phase 分析模板(`specs/phases/_TEMPLATE-analysis.md`)
- ☑ 代码工程约定(`ignite-gogogo/`,每 Session 独立多模块工程)

## Phase 源码分析
| Phase | 子系统 | 分析文档 | 状态 |
|---|---|---|---|
| 0 | 前置基础 | N/A(不镜像 Ignite) | ☑(S1/S2 已补) |
| 1 | NIO 引擎 | `phases/P01-nio-analysis.md` | ☑ |
| 2 | Marshaller + Direct | `phases/P02-marshaller-direct-analysis.md` | ☑ |
| 3 | 页内存 PageMemory | `phases/P03-page-memory-analysis.md` | ☑ |
| 4 | WAL | — | ☐ |
| 5 | PageStore + B+树 + Checkpoint | — | ☐ |
| 6 | 本地缓存 | — | ☐ |
| 7 | Discovery | — | ☐ |
| 8 | Communication + GridIoManager | — | ☐ |
| 9 | Affinity | — | ☐ |
| 10 | PME | — | ☐ |
| 11 | DHT + 副本 | — | ☐ |
| 12 | 分布式事务 | — | ☐ |
| 13 | SQL | — | ☐ |
| 14 | Compute | — | ☐ |

## Session 进度
> 列:**执行规格**(session-doc 产)/ **代码 + 测试 + 讲义**(session-code 产;讲义描述实际产物)。

| # | Session | 执行规格 | 代码 | 测试 | 讲义 | 状态 |
|---|---|---|---|---|---|---|
| **S1** | **项目骨架** | ☑ `S01-skeleton.md` | ☑ `s01-skeleton/` | ☑ 1 passed | ☑ | ☑ |
| **S2** | **NIO/并发热身** | ☑ `S02-nio-warmup.md` | ☑ `s02-nio-warmup/` | ☑ 1 passed | ☑ | ☑ |
| **S3** | **NIO v1(单worker+会话+帧)** | ☑ `S03-nio-engine.md` | ☑ `s03-nio-engine/` | ☑ 6 passed | ☑ | ☑ |
| **S4** | **NIO v2(多worker+过滤链)** | ☑ `S04-nio-v2.md` | ☑ `s04-nio-v2/` | ☑ 10 passed | ☑ | ☑ |
| **S5** | **NIO v3(recovery+背压)** | ☑ `S05-nio-v3.md` | ☑ `s05-nio-v3/` | ☑ 18 passed | ☑ | ☑ |
| **S6** | **Direct 编解码 v1** | ☑ `S06-direct-codec.md` | ☑ `s06-direct-codec/` | ☑ 25 passed | ☑ | ☑ |
| **S7** | **Marshaller v2** | ☑ `S07-marshaller.md` | ☑ `s07-marshaller/` | ☑ 31 passed | ☑ | ☑ |
| S8 | 页内存 v1 | ☐ | ☐ | ☐ | ☐ | ☐ |
| S9 | DataRegion + free list | ☐ | ☐ | ☐ | ☐ | ☐ |
| S10 | WAL v1 | ☐ | ☐ | ☐ | ☐ | ☐ |
| S11 | WAL 回放 | ☐ | ☐ | ☐ | ☐ | ☐ |
| S12 | 内存 B+树 | ☐ | ☐ | ☐ | ☐ | ☐ |
| S13 | PageStore | ☐ | ☐ | ☐ | ☐ | ☐ |
| S14 | 持久 B+树 | ☐ | ☐ | ☐ | ☐ | ☐ |
| S15 | Checkpoint + 恢复 | ☐ | ☐ | ☐ | ☐ | ☐ |
| S16 | 本地缓存 | ☐ | ☐ | ☐ | ☐ | ☐ |
| S17 | 配置 + 驱逐 | ☐ | ☐ | ☐ | ☐ | ☐ |
| S18 | Discovery v1 | ☐ | ☐ | ☐ | ☐ | ☐ |
| S19 | DiscoCache | ☐ | ☐ | ☐ | ☐ | ☐ |
| S20 | Comm v1 | ☐ | ☐ | ☐ | ☐ | ☐ |
| S21 | GridIoManager | ☐ | ☐ | ☐ | ☐ | ☐ |
| S22 | Affinity | ☐ | ☐ | ☐ | ☐ | ☐ |
| S23 | PME | ☐ | ☐ | ☐ | ☐ | ☐ |
| S24 | DHT v1 | ☐ | ☐ | ☐ | ☐ | ☐ |
| S25 | DHT + 副本 | ☐ | ☐ | ☐ | ☐ | ☐ |
| S26 | 本地事务 | ☐ | ☐ | ☐ | ☐ | ☐ |
| S27 | 分布式 2PC | ☐ | ☐ | ☐ | ☐ | ☐ |
| S28 | 死锁检测 | ☐ | ☐ | ☐ | ☐ | ☐ |
| S29 | MVCC + 恢复 | ☐ | ☐ | ☐ | ☐ | ☐ |
| S30 | H2 本地 SQL | ☐ | ☐ | ☐ | ☐ | ☐ |
| S31 | 索引 + 下推 | ☐ | ☐ | ☐ | ☐ | ☐ |
| S32 | 分布式 SQL | ☐ | ☐ | ☐ | ☐ | ☐ |
| S33 | Compute v1 | ☐ | ☐ | ☐ | ☐ | ☐ |
| S34 | map-reduce | ☐ | ☐ | ☐ | ☐ | ☐ |
| S35 | failover | ☐ | ☐ | ☐ | ☐ | ☐ |

## 里程碑
> ☑ **前置**:产出 `specs/benchmarks/M?-report.md` 且 `scripts/check-milestone-report.sh` 通过(方法见 `specs/assets/benchmarking-against-ignite.md`)。

| 里程碑 | 内容 | 到达? |
|---|---|---|
| M1 | 单节点可恢复持久化 KV | ☐ |
| M2 | 多节点集群组网 + 消息 | ☐ |
| M3 | 分片分布式 KV | ☐ |
| M4 | 带副本的分布式缓存 | ☐ |
| M5 | ACID 分布式事务 | ☐ |
| M6 | 分布式 SQL | ☐ |
| M7 | 分布式计算(map-reduce + failover) | ☐ |

## 已验证的工程
- `ignite-gogogo/s03-nio-engine/`:`mvn test` → **6 passed, 0 failed**(FrameCodec 5 + NioServerEcho 1),Java 21 + Maven 3.9.6。
- `ignite-gogogo/s04-nio-v2/`:`mvn test` → **10 passed, 0 failed**(FrameCodec 5 + FilterChain 2 + CodecFilter 2 + MultiWorkerEcho 1)。
- `ignite-gogogo/s05-nio-v3/`:`mvn test` → **18 passed, 0 failed**(继承 10 + RecoveryDescriptor 5 + RecoveryResend 1 + SendBackpressure 1 + ReceiveBackpressure 1)。
- `ignite-gogogo/s06-direct-codec/`:`mvn test` → **25 passed, 0 failed**(继承 18 + DirectMessageRoundtrip 5 + MessageFactory 1 + PingMessageOverNio 1;NioServer 泛化 `<T>` + Direct 编解码 seam 叠 CodecFilter 帧)。
- `ignite-gogogo/s07-marshaller/`:`mvn test` → **31 passed, 0 failed**(继承 25 + OptimizedMarshaller 4[pojo/嵌套数组/环/体积对比] + MarshallerContext 1 + MarshallerViaDirect 1;自定义紧凑格式 + handle 环检测 + 反射;**Phase 2 收官**)。
- `ignite-gogogo/s01-skeleton/`:`mvn test` → **1 passed**(HelloTest;多模块骨架,后续复制源)。
- `ignite-gogogo/s02-nio-warmup/`:`mvn test` → **1 passed**(EchoTest#echoRoundtrip;单线程 Selector echo 往返)。
