---
name: ignite-session-doc
description: 从零手搓 Ignite 学习项目 —— 为某个 session 产出"教学文档"(stage ②)。当某 phase 已分析完、用户要"写 session 教学文档 / 教学 / 教 S?? "时使用。基于 phase 源码分析,从模板写出聚焦详细的 specs/sessions/SNN-*.md,并在 roadmap 挂链接。调用方式 /ignite-session-doc <NN>。
---

# ignite-session-doc &lt;NN&gt;

为 **session NN** 产出**教学文档**。

## 前置
该 session 所属 phase 必须已有源码分析文档(`specs/phases/PNN-*-analysis.md`)。没有则先 `/ignite-analyze-phase <N>`。

## 在流水线中的位置
stage ②:**分析 → [教学文档] → 代码**。按既定决策**一次只产一个 session** 教学文档(顺序出),每个尽量被前一个 session 的真实代码校准。

## 输入
- `<NN>`:session 编号(01–35,零填充)。在 roadmap 查该 session 块(目标/镜像源码/前置/实现要点/验收/工时)及其 phase 分析文档。

## 步骤
1. **定位**:roadmap 中 session NN 的块 + 其 phase 分析文档(尤其 §6 拆分依据、§2 类清单、§4 设计 why)。
2. **起草**:复制 `specs/sessions/_TEMPLATE.md` → `specs/sessions/SNN-<短名>.md`。要比 phase 分析**更聚焦更详细**:放大到本 session 的切片,给 step-by-step 构建(v1→v3)、代码骨架、`file:line` 源码导读、具体陷阱、自测题。
3. **只引用已核验路径**:复用 phase 分析里已核验的锚点;新增的必须先核验存在。
4. **挂链接**:在 roadmap 该 session 块末尾追加 `**教学文档**:[SNN-短名](sessions/SNN-短名.md)`。
5. **更新进度**:`specs/PROGRESS.md` 把该 session 的"教学文档"勾 ☑。

## 纪律
- 范围只覆盖 phase 分析 §6 给本 session 划定的切片,不要把别的 session 的范围拉进来。
- v1→v3 阶梯必须与 roadmap 该 session 的"实现要点"一致。
- 验收(可运行 demo + 单测)要具体可执行。

## 完成 =
教学文档写好(聚焦详细)+ roadmap 链接挂上 + PROGRESS 更新。
