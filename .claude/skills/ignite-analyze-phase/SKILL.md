---
name: ignite-analyze-phase
description: 从零手搓 Ignite —— 为某 phase 产出"源码分析文档"(stage ①)。当用户要开始新 phase、或说"分析 phase N"时使用。深读 vendors/ignite 源码、按模板写出 specs/phases/PNN-*-analysis.md(§6 含 v级列、含修订记录、含引用路径附录),跑 cited-paths lint(取代自报勾选)。调用 /ignite-analyze-phase <N>。
---

# ignite-analyze-phase &lt;N&gt;

为 **phase N** 产出**源码分析文档**(该 phase 各 session 执行规格的 grounded 输入)。

## 流水线位置
stage ①:**源码分析 → 执行规格 → 代码**。先于该 phase 的 session 执行规格。

## 输入
- `<N>`:phase 编号(1–14)。在 `specs/ignite-complete-learning-roadmap.md`(Phase 表 / 依赖 DAG)查子系统、镜像包、覆盖 session。

## 步骤
1. **查清范围**:子系统 + 镜像源码包 + 覆盖的 session(roadmap + `specs/assets/package-layout.md`)。
2. **grounding —— 派 Explore 深读源码**:向相关包派 2~3 个 Explore agent(very thorough),索要真实类名、`file:line` 锚点、数据/控制流 trace、设计 why、依赖边界。入口见 `specs/assets/reading-ignite-source.md`。**不凭记忆写**。
3. **起草**:复制 `specs/phases/_TEMPLATE-analysis.md` → `specs/phases/PNN-<短名>-analysis.md`,填 8 节。要点:
   - §6 拆分表**必须含「v级」列**(每个 session 标 v1/v2/v3);
   - 末尾加「修订记录」节(初始留空,session 代码若证伪分析就回填一条);
   - 末尾加**引用路径附录**(```cited-paths 块,含本分析引用的全部 `vendors/ignite` 路径)。
4. **防幻觉门**:跑 `scripts/check-cited-paths.sh specs/phases/PNN-<短名>-analysis.md`,直到全 OK(取代旧 §8 自报勾选)。
5. **更新**:`specs/PROGRESS.md` 该 phase"分析文档"勾 ☑。

## 纪律
- **依赖锚点**:排序主张须与 roadmap §依赖锚点一致(Discovery⊥Communication、持久化隔离、Affinity 纯数学)。
- **保真规则**:Ignite 自写层纯 JDK;Ignite 用第三方库处注明同用。
- 保留 `file:line` 锚点(2.18.0,标注"非强约束")。

## 完成 =
8 节填满 + §6 含 v级 + 修订记录节 + cited-paths lint 全 OK + PROGRESS 更新。
