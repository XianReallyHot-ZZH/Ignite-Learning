---
name: ignite-session-doc
description: 从零手搓 Ignite —— 为某 session 产出"执行规格"(stage ②,约束 AI 的瘦规格)。当某 phase 已分析完、用户要"写 session 文档/规格/教 S??"时使用。基于 phase 分析,从 _TEMPLATE-spec.md 产出 specs/sessions/SNN-*.md(范围/对外接口契约/源码导读/实现步骤/具名验收/引用路径附录),跑 cited-paths lint,挂 roadmap 链接。讲义改由 /ignite-session-code 在代码完成后产出(描述实际产物)。调用 /ignite-session-doc <NN>。
---

# ignite-session-doc &lt;NN&gt;

为 **session NN** 产出**执行规格**(约束 AI 的瘦规格)。**讲义不在此步**——讲义描述"实际建出的产物",由 `/ignite-session-code` 在代码完成后写,避免推测性的图/对照。

## 前置
该 session 所属 phase 已有源码分析(`specs/phases/PNN-*-analysis.md`)。没有则先 `/ignite-analyze-phase <N>`。

## 流水线位置
stage ②:**分析 → [执行规格] → 代码 → 讲义**。一次一个 session(顺序出)。

## 唯一事实源(SoT)
- 范围/顺序 = **roadmap 本 S 块**(权威)。
- 拆分/grounding = **phase 分析 §6**(权威)。
- 本执行规格 = **细化 + 对外接口契约 + 具名验收**。冲突按 roadmap→分析→规格 优先级,**就地修正上游**。

## 步骤
1. **定位**:roadmap 的 session NN 块 + phase 分析 §6 本 session 行(含 v 级)。
2. **起草**:复制 `specs/sessions/_TEMPLATE-spec.md` → `specs/sessions/SNN-<短名>.md`,填 6 节:
   - §1 范围与位置(标 **v 级**、显式"不做");
   - §2 **对外接口契约**:本 session 暴露的 public 类型/方法 + 供哪个下游(来源 = 依赖 DAG **出边**);
   - §3 源码导读(`file:line`,复用 phase 分析已核验锚点);
   - §4 实现步骤(v1→v3);
   - §5 **验收 = 具名测试**(每条 → `TestClass#method`);
   - §6 **引用路径附录**(```cited-paths 块)。
3. **防幻觉门**:跑 `scripts/check-cited-paths.sh specs/sessions/SNN-<短名>.md` 直到全 OK。
4. **挂链接**:roadmap 本 S 块末尾加 `**教学文档**:[SNN-短名](sessions/SNN-短名.md)`。
5. **更新**:`specs/PROGRESS.md` 该 session"执行规格"勾 ☑。

## 纪律
- 范围只覆盖 phase §6 划给本 session 的切片。
- §2 契约必须覆盖**所有 DAG 出边**下游依赖的接口(防下游返工)。
- §5 每条验收点名测试;§6 附录含本规格引用的全部 `vendors/ignite` 路径。
- **讲义不在本 skill**:讲义描述实际产物,归 `/ignite-session-code`(建后写)。

## 完成 =
执行规格 6 节填满 + cited-paths lint 全 OK + roadmap 链接 + PROGRESS 更新。(讲义由 /ignite-session-code 建后产出。)
