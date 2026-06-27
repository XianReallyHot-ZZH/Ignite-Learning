# S01 · 执行规格:项目骨架与测试基础设施

> **Phase 0 · 前置基础 · v1** · 无里程碑
> 执行约束规格(瘦)。**Phase 0 不镜像 Ignite 源码**(前置基础)→ §3/§6 标 N/A,cited-paths lint 跳过。
> 代码 `ignite-gogogo/s01-skeleton/`(后续 session 的复制源)。讲义 `docs-learn/S01-skeleton.md`。

## 1. 范围与位置
- **roadmap S 块**:Session S1(权威范围/前置/实现要点/验收)。
- **phase**:Phase 0(前置基础,无 Ignite 子系统)。
- **本 session 做**:多模块 Maven 工程骨架(父 pom + `core` 子模块)+ JUnit5 工作流 + 一个 `HelloTest` 跑绿。
- **本 session 不做**:任何 Ignite 镜像(无源码导读);后续子系统全部留后面。
- **前置**:无(Phase 0 起点)。

## 2. 对外"接口契约"(工程约定)
> Phase 0 无 API 契约;这里固化**工程约定**,下游每个 session 复制此骨架:

| 约定项 | 内容 | 供下游 session |
|---|---|---|
| 包根 | `org.apache.ignite.learning.*`(对照 `vendors/ignite` 的 `org/apache/ignite/`) | 全部 session |
| 工程结构 | 父 pom(aggregator)+ `core` 子模块;`maven.compiler.release=17` | 全部 session |
| 测试门槛 | JUnit5;每 session `mvn -f .../pom.xml test` 绿才算完 | 全部 session |
| 代码家 | `ignite-gogogo/sNN-<短名>/`(每 session 独立工程) | 全部 session |

## 3. Ignite 源码导读
**N/A** —— Phase 0 是前置基础,不镜像 Ignite 源码。(仅可参考 `vendors/ignite/pom.xml`、`vendors/ignite/parent/` 的多模块组织思路,**不照抄**。)

## 4. 实现步骤(v1)
1. 建 `ignite-gogogo/s01-skeleton/`(父 pom `packaging=pom`,module `core`)。
2. `core/pom.xml`:`maven.compiler.release=17` + JUnit5。
3. `core/src/main/java/org/apache/ignite/learning/Hello.java`:最小类(`greet()` 返回一句话)。
4. `core/src/test/java/.../HelloTest.java`:断言 `greet()`,跑绿。

## 5. 验收 = 具名测试

| 验收点 | 测试 |
|---|---|
| 骨架可编译 + 测试跑绿 | `HelloTest#greet` |

- demo:`mvn -f ignite-gogogo/s01-skeleton/pom.xml test` → BUILD SUCCESS。

## 6. 引用路径(lint 核验对象)
**N/A**(Phase 0 不引用 Ignite 源码;cited-paths lint 跳过)。

---
**工时**:⭐ / 0.5 天  **产出物**:可编译可测试的多模块 Maven 骨架(后续 session 复制源)
