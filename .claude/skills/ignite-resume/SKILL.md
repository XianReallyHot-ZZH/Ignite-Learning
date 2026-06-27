---
name: ignite-resume
description: 从零手搓 Ignite 学习项目 —— 恢复/进入工作状态,做到无缝衔接。读 specs/PROGRESS.md 汇报"现在做到哪、下一步是什么",检查里程碑基准报告是否补齐(☑ 前置),并建议(或直接启动)下一个 skill。当用户说 继续/接着做/现在做到哪了/恢复进度/我们做到哪 时使用。调用方式 /ignite-resume。
---

# ignite-resume

快速进入/恢复 Ignite 手搓项目的工作状态。不凭记忆判断进度——以工件为准。

## 步骤
1. 读 `specs/PROGRESS.md`(尤其顶部「当前位置/下一步」);必要时 `git log --oneline -8` 看最近提交、`git status --short` 看有无未提交改动。
2. 汇报(简洁):
   - **已完成**:哪些 Phase 分析 ☑、哪些 Session(文档/代码/测试)☑。
   - **当前位置 + 下一步**:具体到 skill + 参数,如"下一步 = `/ignite-session-doc 05`(S5 NIO v3 recovery+背压)"。
   - **未提交改动**:若有,提醒先处理(避免状态漂移)。
3. **里程碑门检查**:对"触发 session 已 ☑"的里程碑,核验基准报告。触发映射:**M1←S15、M2←S21、M3←S23、M4←S25、M5←S29、M6←S32、M7←S35**。
   - 若触发 session 已 ☑:检查 `specs/benchmarks/M?-report.md` 是否存在,并跑 `bash scripts/check-milestone-report.sh specs/benchmarks/M?-report.md`。
   - **报告缺 / 门未过** → 在汇报里**标红**:"⚠ 里程碑 M? 待补基准报告(☑ 前置)——先按 `specs/assets/benchmarking-against-ignite.md` 写报告过门,再勾 ☑"。这通常是当前最该先做的事。
   - 报告已过门但 PROGRESS 未勾 → 提示修正 PROGRESS 把 M? 勾 ☑。
4. 问用户:**直接启动下一步**,还是先做别的。

## 纪律
- 进度以 `PROGRESS.md` 为准;若它与 git/实际文件不一致,**以实际为准并修正 PROGRESS**。
- 建议下一步时给出**确切的 skill + 参数**(别只说"继续做 S5")。
- **里程碑 ☑ 前置**:触发 session(S15/S21/S23/S25/S29/S32/S35)完成后,必须产出 `specs/benchmarks/M?-report.md` 且 `scripts/check-milestone-report.sh` 通过,才能在 PROGRESS 勾里程碑 ☑。报告缺/门未过 = 当前阻塞项,要标红提醒。
- 不要顺手开干——先汇报 + 确认,除非用户明确说"直接继续"。

## 完成 =
清晰汇报 当前位置 + 下一步(带 skill 参数),并询问是否启动。
