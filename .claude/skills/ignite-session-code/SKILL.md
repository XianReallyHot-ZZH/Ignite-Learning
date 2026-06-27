---
name: ignite-session-code
description: 从零手搓 Ignite —— 为某 session 写真实代码(stage ③)。当该 session 执行规格已存在、用户要"写代码/实现 S??/建 sNN 工程"时使用。按执行规格 §4 实现、§2 对外契约、§5 具名测试;在 ignite-gogogo/sNN-*/ 建独立多模块 Maven 工程,mvn 跑绿并核验具名测试。调用 /ignite-session-code <NN>。
---

# ignite-session-code &lt;NN&gt;

为 **session NN** 实现代码工程并跑绿。

## 前置
执行规格 `specs/sessions/SNN-*.md` 存在。没有则先 `/ignite-session-doc <NN>`。

## 流水线位置
stage ③:**分析 → 执行规格 → [代码]**。

## 步骤
1. **建工程**:`ignite-gogogo/sNN-<短名>/`(父 pom + `core`;`maven.compiler.release=17`;JUnit5)。包 `org.apache.ignite.learning.*`,对齐 `specs/assets/package-layout.md`。
   - 非 S3 的 session:复制上一个 session 工程;复制后 `rm -rf core/target`,要改的文件**先删再写**(或先 Read 再 Write —— Write 无法覆盖未读文件)。
2. **实现**:按规格 §4 实现步骤;**对外 public API 严格符合规格 §2 契约**(供下游使用,改契约必须回填下游);注释标注镜像的 Ignite 类。Ignite 自写层纯 JDK;Ignite 用第三方库处同用(如 SQL→H2)。
3. **写测试**:至少覆盖规格 §5 的每个**具名测试**(关键路径 + 边界 + 一个端到端集成)。
4. **跑绿**:`mvn -f ignite-gogogo/sNN-<短名>/pom.xml test`,直到 BUILD SUCCESS、0 失败。
5. **核验具名测试**:规格 §5 点名的每个测试类/方法**必须存在于 src/test 且在通过集**;缺失/未绿即视为未达标,回去补。
6. **补注释**(代码绿后再补,见下"注释纪律"):类 Javadoc + 关键行内 + 方法 Javadoc;补完**再跑一次 `mvn test`** 确认纯注释(没误改代码)。
7. **更新**:`specs/PROGRESS.md` 该 session"代码/测试"勾 ☑ + 记录"N passed"。不提交 `target/`(已 .gitignore)。

## 纪律
- **没真跑 `mvn test` 见 BUILD SUCCESS,不能声称绿;具名测试缺失/未绿不能声称达标。**
- **不在编译期依赖 `vendors/ignite`**:是"复现"不是"import"。
- **注释时机(重要)**:**代码先行 → mvn 绿 → 趁新鲜补注释**(同一 session,别拖)。**别边写代码边写注释**——易"注释锚定代码"(先写注释、再凑出"符合注释但非最优/略有偏差"的代码,LLM 真实坑);也别全写完隔很久再补(漂移)。补注释同时是一次**自检**(re-read 能抓 bug)。
- **注释标准**:类级 Javadoc(中文:职责 + 关键设计 / 不变量)+ **非显然逻辑**的行内注释(并发 / 边界 / why)+ public 方法 Javadoc;**不写显而易见的废话注释**(如 `// x = 5`)。
- **注释描述"代码实际做什么",不是"我当初的意图"**;补完**再跑一次 `mvn test`** 确认纯注释(没误改代码)。

## 完成 =
工程构建 + 测试绿(mvn)+ §5 具名测试全部存在且绿 + **注释齐全(标准见上,补完复跑绿)** + PROGRESS 更新。
