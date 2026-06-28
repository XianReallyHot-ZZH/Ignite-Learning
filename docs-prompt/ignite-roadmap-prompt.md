# Ignite 学习路线生成 · 提示词(经 /grill-me 三轮验证定稿)

> ⚠ **已过时(v1 时代)**:本提示词描述的是 v1 pipeline(单份教学文档、同一份代码库、无 lint/契约/门)。当前 pipeline 以 **skills(`.claude/skills/`)+ CLAUDE.md + roadmap §3** 为准(执行规格+讲义分离、独立工程、cited-paths/check-handouts/里程碑门、注释纪律、API 契约)。仅留作历史参考。

> 本文件是生成 `specs/ignite-complete-learning-roadmap.md` 的**源提示词**,可直接复用/迭代。
> 设计依据见同级目录的 roadmap 文档头部「范围与保真规则」与「设计决策」小节。

---

深入阅读分析 @vendors/ignite (tag 2.18.0) 源码。注意:不要"先完全掌握再开始"——而是边研读、边用源码事实验证、边规划。

任务:为一名 **CS 在校生(Java 与分布式系统都需铺垫)**,制定一条"从零手搓、增量建成、最终架构 ≈ Apache Ignite(core 范围)"的学习路线,写入 @specs/ignite-complete-learning-roadmap.md(内容多则分多次写入,保持稳定的阶段/Session 编号)。

## 保真规则(忠实镜像 Ignite)
- Ignite 自己手写的层,用**纯 Java + JDK 从零手写复现**(NIO / ServerSocket / 文件 IO,不引 Netty 等)。
- Ignite 依赖的第三方库,我们**同样依赖**(如 SQL 按 Ignite 那样集成 H2/Calcite + 手写 cache↔SQL 桥、索引下推、分布式查询)。
- ⇒ "完整 SQL" ≠ 从零写 SQL 优化器;而是像 Ignite 那样接入 H2 并手写集成层。

## 范围
- **纳入(完整保真):** NIO 引擎+Marshaller / Discovery / Communication / Kernal 生命周期 / PageMemory / WAL+PageStore+B+树+Checkpoint / 本地缓存 / Affinity / PME / DHT+副本 / 分布式事务(2PC+MVCC+死锁+恢复) / SQL(H2/Calcite 集成) / Compute(map-reduce+failover+负载均衡)
- **后置(可选扩展):** Service Grid / Continuous Query / Data Streamer / 数据结构 / Thin client / ODBC / JDBC
- **排除:** 平台(.NET/C++ 互操作)、安全·认证·加密、ML、集成、benchmark

## 主线 = 存储优先,严格按真实依赖边排序
先核对并锚定这些**结构事实**(已核实):
- **Discovery 与 Communication 是并列兄弟**:Discovery 用裸 `ServerSocket`,不依赖 `GridNioServer`/`GridIoManager`;仅 `GridIoManager` 单向读 Discovery 的拓扑来寻址。
- **持久化层(PageMemory+WAL+B+树)完全隔离**:不需集群/通信/缓存 KV,只用文件 IO,可最先独立构建并单测。
- **Affinity 是纯拓扑数学**(`RendezvousAffinityFunction`),只读 Discovery 视图。

按"一个初学实现者真正能顺着依赖把系统重建出来的顺序"排,**不是源码文件出现的顺序**。

## 结构 = Session 化增量
- 每个 **Session 基于上一个 Session 的实现往下做**(同一份代码库逐步长大)。
- 每个 Session:内容合理、学习曲线平滑、不过载;**必须可运行**;**必须有匹配的单元测试**;必要时配一个 demo。
- 每个子系统按**保真阶梯** v1(最小可运行)→ v2(功能完整)→ v3(忠实)递进。
- 达到**阶段性里程碑**时:补关键路径端到端测试 + demo + 与 Ignite 的性能/正确性基准对比。
- 最后一个 Session 完成后,产物 ≈ Apache Ignite(core 范围)。

## 每个 Session 必含字段
1. 目标 / 学到的概念
2. 所镜像的 `vendors/ignite` 具体源码路径
3. 前置依赖(Session 编号)
4. 实现要点(v1 → v2 → v3)
5. 验收(可运行 demo + 单测)
6. 预估难度 / 工时
7. 产出物

## 质量门 & 防幻觉
- 任何阶段/Session 的排序,必须给出**真实依赖边理由**;不得按目录/import 顺序臆排。
- 每条源码引用必须**真实存在**于 `vendors/ignite`;不确定先核实再写。
- 全量自检:所有 Session 串起来,是否覆盖了"纳入"的全部核心功能、是否每步可运行可测、CS 学生是否真能顺着走完。

> 一句话记住:最好的教学顺序,不是源码文件出现的顺序,而是一个初学实现者真正能顺着依赖关系把系统重建出来的顺序。
