---
name: ignite-session-code
description: 从零手搓 Ignite 学习项目 —— 为某个 session 写真实代码(stage ③)。当该 session 教学文档已存在、用户要"写代码 / 实现 S?? / 建 sNN 工程"时使用。在 ignite-gogogo/sNN-*/ 建独立多模块 Maven 工程,对齐 Ignite 设计,单测跑绿(mvn)。调用方式 /ignite-session-code <NN>。
---

# ignite-session-code &lt;NN&gt;

为 **session NN** 实现**代码工程**并让测试跑绿。

## 前置
该 session 的教学文档(`specs/sessions/SNN-*.md`)必须存在。没有则先 `/ignite-session-doc <NN>`。

## 在流水线中的位置
stage ③:**分析 → 教学文档 → [代码]**。

## 输入
- `<NN>`:session 编号。

## 步骤
1. **建工程**:`ignite-gogogo/sNN-<短名>/`(父 pom + `core` 子模块;`maven.compiler.release=17`;JUnit5)。Java 根包 `org.apache.ignite.learning.*`,对齐 `specs/assets/package-layout.md`。
   - 非 S3 的 session:通常**复制上一个 session 的工程目录**作为起点再扩展(增量)。
2. **实现**:按教学文档的 v1→v3 步骤 + 代码骨架;在注释里标注所镜像的 Ignite 类。Ignite 自写层纯 JDK;Ignite 用第三方库处我们同用(如 SQL→H2)。
3. **写单测**:覆盖教学文档的验收(关键路径 + 边界;如 NIO 的帧粘包/半包)。优先确定性纯逻辑测试,再加一个端到端集成测试。
4. **跑绿**:执行 `mvn -f ignite-gogogo/sNN-<短名>/pom.xml test`,直到 **BUILD SUCCESS、0 失败**。
5. **更新进度**:`specs/PROGRESS.md` 把该 session 的"代码工程""测试"勾 ☑,并记录"N passed"。
6. **不要提交构建产物**:仓库根 `.gitignore` 已忽略 `target/`;只提交源码 + pom + 文档。

## 纪律
- **没真正跑 `mvn test` 见到 BUILD SUCCESS,就不能声称绿**。
- **不要在编译期依赖 `vendors/ignite`**:是"复现"不是"import"。
- 包结构对齐 `package-layout.md`,让各 session 工程能衔接。

## 完成 =
工程构建通过 + 测试绿(mvn)+ PROGRESS 更新。
