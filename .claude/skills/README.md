# Ignite 手搓项目 · Skill 集

把"从零手搓 Ignite"的三段流水线固化成 skill。每个 phase 按顺序串起来用:

```
① /ignite-analyze-phase <N>   # phase 源码分析(每 phase 一次)
        ↓
② /ignite-session-doc <NN>    # session 执行规格(每 session 一次,顺序出)
        ↓
③ /ignite-session-code <NN>   # session 代码 + 测试跑绿 + 核验具名测试(每 session 一次)
```
恢复进度:`/ignite-resume`。

## skill
| Skill | 产物 | 模板 | 防幻觉 |
|---|---|---|---|
| `/ignite-analyze-phase <N>` | `specs/phases/PNN-*-analysis.md`(§6 含 v级 + 修订记录 + 引用附录) | `specs/phases/_TEMPLATE-analysis.md` | 派 Explore 深读 + **cited-paths lint** |
| `/ignite-session-doc <NN>` | `specs/sessions/SNN-*.md`(执行规格)+ 可选 `docs-learn/` 讲义 | `specs/sessions/_TEMPLATE-spec.md` / `docs-learn/_TEMPLATE-handout.md` | **cited-paths lint** |
| `/ignite-session-code <NN>` | `ignite-gogogo/sNN-*/`(多模块 Maven)+ 测试绿 | — | 真跑 `mvn test` + 核验 §5 **具名测试** |
| `/ignite-resume` | 读 PROGRESS 汇报当前位置 + 下一步 | — | 以工件为准 |

## 文档体系(唯一事实源 SoT)
- **范围/顺序** = `roadmap.md` 的 S 块(权威)。
- **拆分/grounding** = phase 分析 §6(权威)。
- **细化 + 对外接口契约 + 具名验收** = session 执行规格。
- 冲突按 roadmap→分析→规格 优先级,**就地修正上游**。

## 两份文档(session)
- **执行规格** `specs/sessions/SNN-*.md`:瘦,约束 AI(范围/契约/源码导读/实现步骤/具名验收/引用附录)。
- **学习者讲义** `docs-learn/SNN-*.md`(可选):教学法(概念图/why/陷阱/自测题/对照)。

## 共同纪律
- **grounding 优先**:分析/规格靠 Explore 读 `vendors/ignite` 真实源码 + **`scripts/check-cited-paths.sh`** 机器核验;代码靠真跑 `mvn test` + 核验具名测试。不凭记忆、不自报勾选。
- **保真**:Ignite 自写层纯 JDK;Ignite 用第三方库处同用(如 SQL→H2)。
- **依赖锚点**:顺序主张与 roadmap §依赖锚点一致。
- **进度同步**:每个 skill 完成后更新 `specs/PROGRESS.md`。
- **里程碑门**:M1~M7 的 ☑ 前置 = 产出 `specs/benchmarks/M?-report.md`(功能一致性 + 性能对比 Ignite)+ `scripts/check-milestone-report.sh` 通过(方法见 `specs/assets/benchmarking-against-ignite.md`)。
- **讲义门**:每个 session 执行规格都要有配套讲义(`docs-learn/SNN-*.md`,**必写**),`scripts/check-handouts.sh` 把关。

## 节奏
- 一个 phase:`/ignite-analyze-phase` **一次**;然后每 session 按 `session-doc → session-code` **顺序**推进。
- 忘了做到哪?`/ignite-resume` 或看 `specs/PROGRESS.md` 顶部。

## 状态(v2)
v1 抽象自 Phase 1/S3 试点;**v2**(本次)按 grilling 加固:拆执行规格/讲义、加对外接口契约、cited-paths lint、具名测试、SoT 声明。随做更多 phase 仍需回头修订(存储/事务/SQL phase 的测试与依赖各有特点)。
