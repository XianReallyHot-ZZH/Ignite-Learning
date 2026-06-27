# S01 · 学习者讲义:项目骨架与测试基础设施

> **教学法**(给人看)。**执行约束以 `specs/sessions/S01-skeleton.md`(执行规格)为准**。Phase 0 · 前置基础。

## 教学目标
- 建立可长期成长的多模块 Maven 工程
- 固定"每段代码都有测试"的工作流

## 核心概念与设计
- **多模块 Maven**:父 pom(aggregator / 统一版本)+ `core` 子模块。对照 Ignite 也是多模块(parent + `modules/core` + feature 模块)。
- **包根 `org.apache.ignite.learning`**:对照 `vendors/ignite` 的 `org/apache/ignite/`,便于一对一参照。
- **JUnit5 工作流**:每个 session 以 `mvn test` 绿为完成门槛(贯穿全路线)。

## 关键原理(为什么)
- **为什么多模块而非单工程**:对齐 Ignite 结构;后续 SQL 等可加 feature 子模块(如 `indexing`);分离契约与实现。
- **为什么"每段代码都有测试"**:这是全路线的硬门槛(session-code skill 校验具名测试绿)。

## 常见陷阱
- `release` vs `source/target`:用 `maven.compiler.release=17`(不是 `source/target`)。
- 包名别直接用 `ignite`(会和 `vendors/ignite` 混淆)——用 `learning` 子包。

## 自测题(你真的懂了吗)
1. 父 pom 的 `packaging` 是什么?为什么?
2. 为什么用 `release` 而非 `source/target`?
3. 后续每个 session 为什么要各自独立工程(而非一份代码库长大)?

## 与 Ignite 对照
看 `vendors/ignite/pom.xml` + `parent/`:Ignite 用多模块(parent + `modules/core` + feature 模块)。我们 Phase 0 先只 parent + `core`,后期按需加。
