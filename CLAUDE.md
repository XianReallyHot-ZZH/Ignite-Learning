# Ignite-Learning —— 从零手搓 Apache Ignite(学习项目)

> 目标:以"从零到一实现"的方式学习 Ignite。参考实现 `vendors/ignite`(tag 2.18.0,只读对照,不编译依赖)。

## ⭐ 进入工作:第一步永远先做
读 **`specs/PROGRESS.md`** 顶部的「当前位置/下一步」——它告诉你现在做到哪、接着做什么。
(或直接 `/ignite-resume`,它会读 PROGRESS 并汇报 + 建议下一步。)

## 项目地图
- `vendors/ignite` —— 参考实现(tag 2.18.0)。**只读对照,不要在编译期 import 它**;是"复现"不是"调用"。
- `specs/ignite-complete-learning-roadmap.md` —— 总路线(35 Session + 依赖 DAG + 文档体系)。
- `specs/PROGRESS.md` —— 进度状态(roadmap = 计划,PROGRESS = 状态)。
- `specs/phases/` —— phase 源码分析(模板 `_TEMPLATE-analysis.md`)。
- `specs/sessions/` —— **执行规格**(模板 `_TEMPLATE-spec.md`:约束 AI 的瘦规格 + 具名测试 + 引用附录)。
- `docs-learn/` —— 学习者讲义(模板 `_TEMPLATE-handout.md`:教学法,可选)。
- `specs/assets/` —— 全局资产(术语表 / 源码导读 / 包结构)。
- `ignite-gogogo/sNN-<短名>/` —— 每个 Session 一个**独立多模块 Maven** 工程(父 pom + core 子模块)。
- `scripts/check-cited-paths.sh` —— 引用路径 lint(防幻觉门)。
- `.claude/skills/` —— analyze-phase / session-doc / session-code / resume。

## 文档体系(唯一事实源 SoT)
- **范围/顺序** = `roadmap.md` 的 S 块(权威)。
- **拆分/grounding** = phase 分析 §6(权威)。
- **细化 + 对外接口契约 + 具名验收** = session 执行规格。
- 冲突按 **roadmap → 分析 → 规格** 优先级,**就地修正上游**。

## 工作流(三段流水线,已固化为 skill)
每个 phase:
1. `/ignite-analyze-phase <N>` —— phase 源码分析(**每 phase 一次**,先于该 phase 的 session)。
2. 该 phase 下每个 session,按 **顺序**:`/ignite-session-doc <NN>`(产执行规格)→ `/ignite-session-code <NN>`(写代码 + 具名测试跑绿)。

调用前看 PROGRESS 确认 N/NN。

## 铁律
- **保真**:Ignite 自写层纯 JDK 手写;Ignite 用第三方库处我们同用(如 SQL→H2)。
- **grounding**:分析/规格靠 Explore 读 `vendors/ignite` 真实源码 + **`scripts/check-cited-paths.sh`** 机器核验(不凭记忆、不自报勾选);代码靠**真跑** `mvn -f ignite-gogogo/sNN-*/pom.xml test` 见 `BUILD SUCCESS`,且 §5 具名测试全部存在且绿。
- **依赖锚点 / 包结构**:见 `specs/assets/reading-ignite-source.md`、`specs/assets/package-layout.md`;Java 根包 `org.apache.ignite.learning.*`。
- **里程碑门**:到达 M1~M7 时产出 `specs/benchmarks/M?-report.md`(与 Ignite 的**功能 + 性能**基准对比,方法见 `specs/assets/benchmarking-against-ignite.md`);`scripts/check-milestone-report.sh` 通过才许在 PROGRESS 勾里程碑 ☑。
- **工具链**:Java 21 + Maven 3.9.6。`.gitignore` 已忽略 `target/`。

## 会话结束前
更新 `specs/PROGRESS.md` 的「当前位置/下一步」并 commit —— 这是下次"无缝衔接"的保证。
