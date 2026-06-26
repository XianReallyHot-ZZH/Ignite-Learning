---
name: ignite-resume
description: 从零手搓 Ignite 学习项目 —— 恢复/进入工作状态,做到无缝衔接。读 specs/PROGRESS.md 汇报"现在做到哪、下一步是什么",并建议(或直接启动)下一个 skill。当用户说 继续/接着做/现在做到哪了/恢复进度/我们做到哪 时使用。调用方式 /ignite-resume。
---

# ignite-resume

快速进入/恢复 Ignite 手搓项目的工作状态。不凭记忆判断进度——以工件为准。

## 步骤
1. 读 `specs/PROGRESS.md`(尤其顶部「当前位置/下一步」);必要时 `git log --oneline -8` 看最近提交、`git status --short` 看有无未提交改动。
2. 汇报(简洁):
   - **已完成**:哪些 Phase 分析 ☑、哪些 Session(文档/代码/测试)☑。
   - **当前位置 + 下一步**:具体到 skill + 参数,如"下一步 = `/ignite-session-doc 05`(S5 NIO v3 recovery+背压)"。
   - **未提交改动**:若有,提醒先处理(避免状态漂移)。
3. 问用户:**直接启动下一步**,还是先做别的。

## 纪律
- 进度以 `PROGRESS.md` 为准;若它与 git/实际文件不一致,**以实际为准并修正 PROGRESS**。
- 建议下一步时给出**确切的 skill + 参数**(别只说"继续做 S5")。
- 不要顺手开干——先汇报 + 确认,除非用户明确说"直接继续"。

## 完成 =
清晰汇报 当前位置 + 下一步(带 skill 参数),并询问是否启动。
