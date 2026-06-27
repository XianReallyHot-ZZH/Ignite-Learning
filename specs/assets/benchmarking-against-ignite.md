# 如何与 Ignite 做基准对比

> 里程碑报告(`specs/benchmarks/M?-report.md`)的方法依据。**Ignite 2.18.0(`vendors/ignite`)= oracle(标准答案)**。

## 原则
- **同版本**:oracle 用 `vendors/ignite` 的 2.18.0(与我们镜像的版本一致)。
- **同负载**:一份 benchmark 脚本/测试,分别打 **我方 impl** 和 **Ignite**。
- **公平**:warmup + JVM 预热 + 关闭无关功能 + **对齐配置**(都单节点、同 cache 模式等)。
- **双维度**:**功能一致性(conformance)** + **性能(perf)**——两者都要。

## 起 Ignite oracle
- 用 `vendors/ignite` 起一个节点(`mvn` 构建运行,或发行包,或嵌入 `Ignition.start()` 的最小 Java 进程)。
- **配置对齐我方当前能力**:如 M1 只比"单节点 local cache + 持久化",关闭集群 / SQL / 计算;M2 才比多节点。
- 把 Ignite 配置记进报告 §1。

## 功能一致性(conformance)
- 写一组**功能测试用例**(同一份语义),分别跑:
  - 我方:`mvn -f ignite-gogogo/sNN-*/pom.xml test`
  - Ignite:用同样语义的操作打 Ignite 节点
- Ignite 的行为 = 标准答案;我方不一致 = **bug 或有意简化**(报告 §2 标注)。

## 性能(perf)
- 同 benchmark 工具/脚本(JMH 或自写计时),warmup 后测稳态。
- 指标:**吞吐(ops/s)**、**延迟(p50/p99)**、**特定耗时**(PME、join、failover 等)。
- 至少 3 个 workload;记录比值(我方 / Ignite)。

## 注意事项
- warmup 足够(JIT 编译),避免冷启动偏差。
- 先单线程公平对比 → 再测多线程。
- **我方是"学习版",慢是正常的**——重点是**分析差距原因**(directed learning),而非追平 Ignite。
- 记录所有配置,使结果可复现。

## 与里程碑的对应(每个 M 量什么)
| 里程碑 | 功能一致性重点 | 性能重点 |
|---|---|---|
| M1 单节点可恢复 KV | put/get/delete + 重启恢复正确性 | 顺序/随机 put、get 吞吐与延迟 |
| M2 集群组网+消息 | 拓扑/心跳/消息可达 | join 时延、心跳频率、消息往返延迟 |
| M3 分片 KV | 路由正确性、affinity 均匀度 | PME 耗时、数据倾斜下分布 |
| M4 带副本缓存 | 副本一致性、故障切换 | 副本写入吞吐(同步/异步)、故障切换耗时 |
| M5 ACID 事务 | 事务隔离行为等价、死锁 | 事务吞吐/延迟、死锁检测时延 |
| M6 分布式 SQL | 查询结果、执行计划等价 | 查询延迟、索引命中率、reduce 耗时 |
| M7 分布式计算 | map-reduce 结果、failover 一致性 | 完成时延、failover 恢复时延 |
