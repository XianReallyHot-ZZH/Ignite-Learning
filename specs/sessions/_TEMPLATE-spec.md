# [SNN-短名] · 执行规格(execution spec)

> **约束 AI 执行的硬规格**(瘦)。教学法(概念图 / why / 陷阱 / 自测题 / 对照)放讲义 `docs-learn/SNN-短名.md`(按需,可选)。
> **唯一事实源**:范围/顺序看 roadmap 本 S 块;拆分/grounding 看 phase 分析 §6;本规格 = **细化 + 契约 + 验收**。
> 复制本文件为 `specs/sessions/SNN-短名.md`。写完:**跑 `scripts/check-cited-paths.sh specs/sessions/SNN-短名.md`**,并在 roadmap 本 S 块挂 `**教学文档**:` 链接。

**Session**:SNN · <标题>  **Phase**:Phase N · <子系统>  **v 级**:v1/v2/v3  **里程碑**:通向 M?

---

## 1. 范围与位置
- **roadmap S 块**:见 `specs/ignite-complete-learning-roadmap.md` 的 Session SNN(权威范围/前置/实现要点/验收)。
- **phase §6 行**:见 `specs/phases/PNN-*-analysis.md` §6(权威拆分)。
- **本 session 做**:<列出>
- **本 session 不做**(划到别的 session):<列出,显式划界>
- **前置 session**:S??(其对外接口见 §2 / 其执行规格)。

## 2. 对外接口契约(API contract)
> 本 session 对下游暴露的 public 类型/方法。来源 = roadmap 依赖 DAG 的**出边**(谁依赖本 session)。
> 下游 session 据此构建;改接口必须回填下游。

| 类型/方法 | 签名 | 供下游 session |
|---|---|---|
| `Foo` | `class Foo { void bar(); }` | S??(用在哪) |

## 3. Ignite 源码导读
> 读哪些类、看哪段(`file:line`,2.18.0)。**镜像这些**(复现,不 import)。
- `internal/.../Xxx.java`(:行) —— 关注 ……
- 阅读顺序:……

## 4. 实现步骤(v1 → v2 → v3)
> 展开成可执行小步(可附代码骨架)。**本 session 的目标 v 级** = ……
- 步骤 ……

## 5. 验收 = 具名测试
> 每条验收**点名一个测试**。`/ignite-session-code` 在 `mvn test` 绿后核验这些具名测试存在。

| 验收点 | 测试 |
|---|---|
| <要验证什么> | `TestClass#method` |
- 可运行 demo:……

## 6. 引用路径(lint 核验对象)
```cited-paths
internal/.../Xxx.java
```

---
**工时**:⭐~⭐⭐⭐⭐⭐ / <周数>  **产出物**:<一句话>
