# Ignite-Learning —— 从零手搓 Apache Ignite(学习项目)

> 目标:以"从零到一实现"的方式学习 Ignite。参考实现 `vendors/ignite`(tag 2.18.0,只读对照,不编译依赖)。

## ⭐ 进入工作:第一步永远先做
读 **`specs/PROGRESS.md`** 顶部的「当前位置/下一步」——它告诉你现在做到哪、接着做什么。
(或直接 `/ignite-resume`,它会读 PROGRESS 并汇报 + 建议下一步。)

## 项目地图
- `vendors/ignite` —— 参考实现(tag 2.18.0)。**只读对照,不要在编译期 import 它**;是"复现"不是"调用"。
- `specs/ignite-complete-learning-roadmap.md` —— 总路线(35 Session + 依赖 DAG + 文档体系)。
- `specs/PROGRESS.md` —— 进度状态(roadmap = 计划,PROGRESS = 状态)。
- `specs/phases/`、`specs/sessions/`、`specs/assets/` —— phase 源码分析 / session 教学文档 / 全局资产(术语表、源码导读、包结构)。
- `ignite-gogogo/sNN-<短名>/` —— 每个 Session 一个**独立多模块 Maven** 工程(父 pom + core 子模块)。
- `.claude/skills/` —— 工作流 skill(见下)。

## 工作流(三段流水线,已固化为 skill)
每个 phase:
1. `/ignite-analyze-phase <N>` —— 产 phase 源码分析(**每 phase 一次**,先于该 phase 的 session)。
2. 该 phase 下每个 session,按 **顺序**:`/ignite-session-doc <NN>` → `/ignite-session-code <NN>`(文档→代码,一次一个)。

调用前看 PROGRESS 确认 N/NN。

## 铁律
- **保真**:Ignite 自写层纯 JDK 手写;Ignite 用第三方库处我们同用(如 SQL→H2)。
- **grounding**:分析/文档靠 Explore 读 `vendors/ignite` 真实源码 + **核验每条引用路径**;代码靠**真跑** `mvn -f ignite-gogogo/sNN-*/pom.xml test` 见 `BUILD SUCCESS` 才算绿。
- **依赖锚点 / 包结构**:见 `specs/assets/reading-ignite-source.md`、`specs/assets/package-layout.md`;Java 根包 `org.apache.ignite.learning.*`。
- **工具链**:Java 21 + Maven 3.9.6。
- **不提交构建产物**:`.gitignore` 已忽略 `target/`。

## 会话结束前
更新 `specs/PROGRESS.md` 的「当前位置/下一步」并 commit —— 这是下次"无缝衔接"的保证。
