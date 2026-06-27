# M? · 里程碑基准报告:<里程碑名>

> **里程碑 ☑ 的前置产物**(见 roadmap §3)。功能一致性 + 性能基准 + 差距分析,均以 **Ignite 2.18.0**(`vendors/ignite`)为 oracle。
> 复制为 `specs/benchmarks/M?-<短名>-report.md`。方法见 `specs/assets/benchmarking-against-ignite.md`。完成后跑 `scripts/check-milestone-report.sh`。

**里程碑**:M? · <名>(对应 roadmap)
**覆盖 session**:S?? ~ S??
**日期 / 我方版本**:<YYYY-MM-DD> / `ignite-gogogo/s??-*/`

## 1. 环境
- 硬件 / OS / JDK / 堆:
- **Ignite oracle**:2.18.0;配置(单节点?集群?local cache + 持久化?**关闭哪些以对齐我方当前能力**):
- **我方**:`ignite-gogogo/s??-*/`;配置:
- 负载工具 / 参数(warmup、线程数、操作数):

## 2. 功能一致性(conformance)
> 同一组测试用例,分别打 **我方** 与 **Ignite**,结果必须一致。Ignite 行为 = 标准答案。
| 用例 | 我方结果 | Ignite 结果 | 一致? |
|---|---|---|---|
| <用例> | ... | ... | ✅ / ❌ |
- ❌ 逐条分析:是 bug 还是有意简化?

## 3. 性能基准(perf)
> 同负载、同公平条件(warmup 后、同并发)。至少 3 个 workload。
| workload | 指标 | 我方 | Ignite | 比值 |
|---|---|---|---|---|
| 顺序 put | 吞吐 ops/s | ... | ... | …x |
| 随机 get | p99 延迟 | ... | ... | …x |
| ... | ... | ... | ... | ... |

## 4. 差距分析
> 每条显著差距给**原因** + **学到什么** + 是否在后续 session 补。
- ...

## 5. 自检
- [ ] 功能一致性:全部 ✅(或 ❌ 已逐条分析)
- [ ] 性能表已填(≥ 3 workload)
- [ ] 差距分析已写
- [ ] 环境配置对齐(公平对比)
