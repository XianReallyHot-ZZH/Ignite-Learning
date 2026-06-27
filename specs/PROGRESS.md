# 进度清单(PROGRESS)

> 本文件是**活的进度追踪**:`ignite-complete-learning-roadmap.md` 是"计划",本文件是"状态"。
> 每完成一个产物(分析文档 / 教学文档 / 代码工程 / 测试)就更新对应行;将来由 skill 自动维护。
> 图例:☐ 未开始 · ◐ 进行中 · ☑ 完成

## 当前位置
- **最近完成**:**S5(NIO v3)全流程 —— 18 passed;Phase 1(NIO)收官**(2026-06-27)。
- **下一步**:**Phase 2(Marshaller + Direct,S6~S7)** —— `/ignite-analyze-phase 2` → `/ignite-session-doc 06` → `/ignite-session-code 06` …(Phase 1 无里程碑,M1 要到 S15)
- **试点**:Phase 1(NIO)流水线验证中;Phase 0(S1~S2)试点期间暂越过(真做课程时 Phase 0 先行)。

## 基础设施(已建立)
- ☑ roadmap + 依赖 DAG + 文档体系(`specs/ignite-complete-learning-roadmap.md`)
- ☑ 全局资产:glossary / reading-ignite-source / package-layout(`specs/assets/`)
- ☑ 教学文档模板(`specs/sessions/_TEMPLATE.md`)
- ☑ phase 分析模板(`specs/phases/_TEMPLATE-analysis.md`)
- ☑ 代码工程约定(`ignite-gogogo/`,每 Session 独立多模块工程)

## Phase 源码分析
| Phase | 子系统 | 分析文档 | 状态 |
|---|---|---|---|
| 0 | 前置基础 | — | ☐(试点越过) |
| 1 | NIO 引擎 | `phases/P01-nio-analysis.md` | ☑ |
| 2 | Marshaller + Direct | — | ☐ |
| 3 | 页内存 PageMemory | — | ☐ |
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
> 列:**教学文档** / **代码工程**(`ignite-gogogo/sNN-*/`) / **测试**。

| # | Session | 教学文档 | 代码工程 | 测试 | 状态 |
|---|---|---|---|---|---|
| S1 | 项目骨架 | ☐ | ☐ | ☐ | ☐(试点越过) |
| S2 | NIO/并发热身 | ☐ | ☐ | ☐ | ☐(试点越过) |
| **S3** | **NIO v1(单worker+会话+帧)** | ☑ `S03-nio-engine.md` | ☑ `s03-nio-engine/` | ☑ 6 passed | ☑ |
| **S4** | **NIO v2(多worker+过滤链)** | ☑ `S04-nio-v2.md` | ☑ `s04-nio-v2/` | ☑ 10 passed | ☑ |
| **S5** | **NIO v3(recovery+背压)** | ☑ `S05-nio-v3.md` | ☑ `s05-nio-v3/` | ☑ 18 passed | ☑ |
| S6 | Direct 编解码 | ☐ | ☐ | ☐ | ☐ |
| S7 | Marshaller | ☐ | ☐ | ☐ | ☐ |
| S8 | 页内存 v1 | ☐ | ☐ | ☐ | ☐ |
| S9 | DataRegion + free list | ☐ | ☐ | ☐ | ☐ |
| S10 | WAL v1 | ☐ | ☐ | ☐ | ☐ |
| S11 | WAL 回放 | ☐ | ☐ | ☐ | ☐ |
| S12 | 内存 B+树 | ☐ | ☐ | ☐ | ☐ |
| S13 | PageStore | ☐ | ☐ | ☐ | ☐ |
| S14 | 持久 B+树 | ☐ | ☐ | ☐ | ☐ |
| S15 | Checkpoint + 恢复 | ☐ | ☐ | ☐ | ☐ |
| S16 | 本地缓存 | ☐ | ☐ | ☐ | ☐ |
| S17 | 配置 + 驱逐 | ☐ | ☐ | ☐ | ☐ |
| S18 | Discovery v1 | ☐ | ☐ | ☐ | ☐ |
| S19 | DiscoCache | ☐ | ☐ | ☐ | ☐ |
| S20 | Comm v1 | ☐ | ☐ | ☐ | ☐ |
| S21 | GridIoManager | ☐ | ☐ | ☐ | ☐ |
| S22 | Affinity | ☐ | ☐ | ☐ | ☐ |
| S23 | PME | ☐ | ☐ | ☐ | ☐ |
| S24 | DHT v1 | ☐ | ☐ | ☐ | ☐ |
| S25 | DHT + 副本 | ☐ | ☐ | ☐ | ☐ |
| S26 | 本地事务 | ☐ | ☐ | ☐ | ☐ |
| S27 | 分布式 2PC | ☐ | ☐ | ☐ | ☐ |
| S28 | 死锁检测 | ☐ | ☐ | ☐ | ☐ |
| S29 | MVCC + 恢复 | ☐ | ☐ | ☐ | ☐ |
| S30 | H2 本地 SQL | ☐ | ☐ | ☐ | ☐ |
| S31 | 索引 + 下推 | ☐ | ☐ | ☐ | ☐ |
| S32 | 分布式 SQL | ☐ | ☐ | ☐ | ☐ |
| S33 | Compute v1 | ☐ | ☐ | ☐ | ☐ |
| S34 | map-reduce | ☐ | ☐ | ☐ | ☐ |
| S35 | failover | ☐ | ☐ | ☐ | ☐ |

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
