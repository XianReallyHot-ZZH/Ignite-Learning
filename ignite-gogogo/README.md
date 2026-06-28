# ignite-gogogo/ — Ignite 手搓工程(所有 Session)

本目录是"从零手搓 Ignite"学习路线(`specs/ignite-complete-learning-roadmap.md`)的**全部代码落地处**。

## 约定

- **每个 Session = 一个独立、可单独打开运行的多模块 Maven 工程**,目录名 `sNN-短名/`(短名对齐 `specs/sessions/SNN-短名.md`)。
- 例:`s01-skeleton/`、`s03-nio-engine/`、`s12-btree/` ……
- **增量方式**:新 Session 通常**复制上一 Session 的工程目录**作为起点,再扩展(不是同一份代码库原地长大)。
- **模块划分对齐 Ignite**:早期只有 `core` 子模块;后期(Session 引入 SQL 等)按 Ignite 风格新增 feature 子模块(如 `indexing`)。

## 单个 Session 工程的骨架(多模块)

```
sNN-短名/
├─ pom.xml                                 # 父 pom(aggregator)
└─ core/                                   # 对照 Ignite 的 modules/core
   ├─ pom.xml
   ├─ src/main/java/org/apache/ignite/learning/   # Java 根包(对照 org/apache/ignite/)
   └─ src/test/java/org/apache/ignite/learning/
```

## 相关文档
- 学习路线与 Session 列表:`specs/ignite-complete-learning-roadmap.md`
- 包 ↔ Ignite ↔ Session 映射:`specs/assets/package-layout.md`
- 术语表 / 源码导读 primer:`specs/assets/`
- 各 Session 执行规格:`specs/sessions/SNN-短名.md`

> 目前为空:第一个工程由 **S1(项目骨架)** 创建为 `s01-skeleton/`。
