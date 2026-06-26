---
name: ignite-analyze-phase
description: 从零手搓 Ignite 学习项目 —— 为某个 phase 产出"源码分析文档"(stage ①)。当用户要开始一个新 phase、或说"分析 phase N / 做 phase N 的源码分析"时使用。深读 vendors/ignite 源码、核验路径、按模板写出 specs/phases/PNN-*-analysis.md。调用方式 /ignite-analyze-phase <N>。
---

# ignite-analyze-phase &lt;N&gt;

为 Ignite 学习路线的 **phase N** 产出**源码分析文档**。

## 在流水线中的位置
这是每个 phase 三段流水线的 **stage ①**:**源码分析 → 教学文档 → 代码**。必须先于该 phase 的 session 教学文档运行。

## 输入
- `<N>`:phase 编号(1–14)。在 `specs/ignite-complete-learning-roadmap.md`(Phase 表 / 依赖 DAG)查该 phase 的:子系统、镜像的 Ignite 包、覆盖的 Session。

## 步骤
1. **查清范围**:该 phase 子系统 + 镜像源码包 + 覆盖的 Session(roadmap + `specs/assets/package-layout.md`)。
2. **grounding —— 派 Explore agent 深读源码**:向 `vendors/ignite/modules/core/...` 的相关包派 2~3 个 Explore agent(very thorough),各自索要:真实类名、`file:line` 锚点、数据/控制流 trace、设计 why、依赖边界。入口与阅读策略见 `specs/assets/reading-ignite-source.md`。
3. **起草**:复制 `specs/phases/_TEMPLATE-analysis.md` → `specs/phases/PNN-<短名>-analysis.md`,据 agent 结论填满 8 节。**不要凭记忆写**——只引用 agent 实际找到的东西。
4. **防幻觉门**:核验**每一条**引用路径/类名真实存在(`[ -e ... ]` / `git -C vendors/ignite ls-files`)。不存在就改或删。在 §8 自检记录核验条数。
5. **更新进度**:`specs/PROGRESS.md` 把该 phase 的"分析文档"勾 ☑。

## 纪律
- **依赖锚点**(roadmap §依赖锚点):排序主张必须与之吻合(Discovery⊥Communication、持久化隔离、Affinity 纯数学)。
- **保真规则**:Ignite 自写层 = 纯 JDK;Ignite 用第三方库处(如 SQL→H2)注明我们也镜像。
- 保留 `file:line` 锚点(2.18.0,版本相关,标注"非强约束")。

## 完成 =
分析文档 8 节填满 + 引用路径全部核验 + PROGRESS 更新。
