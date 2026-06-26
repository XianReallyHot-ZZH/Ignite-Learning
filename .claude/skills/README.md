# Ignite 手搓项目 · Skill 集

把"从零手搓 Ignite"试点(Phase 1 / S3)验证过的**三段流水线**固化成 3 个 skill。每个 phase 按下面顺序串起来用:

```
① /ignite-analyze-phase <N>      # 产 phase 源码分析(每 phase 一次)
        ↓
② /ignite-session-doc <NN>       # 产 session 教学文档(每 session 一次,顺序出)
        ↓
③ /ignite-session-code <NN>      # 写 session 代码 + 测试跑绿(每 session 一次)
```

## 三个 skill
| Skill | 产物 | 模板 | 防幻觉 |
|---|---|---|---|
| `/ignite-analyze-phase <N>` | `specs/phases/PNN-*-analysis.md` | `specs/phases/_TEMPLATE-analysis.md` | 派 Explore 深读源码 + 核验所有引用路径 |
| `/ignite-session-doc <NN>` | `specs/sessions/SNN-*.md` | `specs/sessions/_TEMPLATE.md` | 只引用 phase 分析里已核验的锚点 |
| `/ignite-session-code <NN>` | `ignite-gogogo/sNN-*/`(多模块 Maven)+ 测试绿 | — | 必须真跑 `mvn test` 见 BUILD SUCCESS |

## 共同纪律(三个 skill 都遵守)
- **grounding 优先**:分析/文档靠 Explore 读 `vendors/ignite` 真实源码;代码靠真跑 `mvn test`。不凭记忆。
- **保真规则**:Ignite 自写层纯 JDK;Ignite 用第三方库处我们同用(如 SQL→H2)。
- **依赖锚点**:顺序主张须与 roadmap §依赖锚点一致。
- **进度同步**:每个 skill 完成后更新 `specs/PROGRESS.md`(roadmap=计划,PROGRESS=状态)。

## 节奏建议
- 一个 phase:`/ignite-analyze-phase` **一次**;然后该 phase 下每个 session 按 `session-doc → session-code` **顺序**推进(一次一个,边做边校准)。
- 不知道做到哪了?看 `specs/PROGRESS.md` 顶部的"当前位置/下一步"。

## 状态(v1)
这套 skill **抽象自单一试点(Phase 1 NIO / S3)**,是 v1。随着做更多 phase(存储/事务/SQL…),模板与步骤会暴露需要调整的地方——做完 1~2 个 phase 后应回头修订。已知可能需要细化的点:存储 phase(B+树/WAL)的测试策略、SQL phase(H2 依赖引入)、事务 phase(并发测试)。
