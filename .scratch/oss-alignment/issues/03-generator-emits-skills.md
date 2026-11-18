# 03: 生成器的产出物改成技能（或整层下掉）

**Status:** resolved（2026-11-18，用户裁决「先整层删掉」＝选 B）

**Type:** decision

**依赖：** 无（票 02 已收，缺口是它留下的）

## 问题（事实，不是猜测）

票 02 把脚本包降级成"技能目录 + 脚本"之后，`SkillGenerator` 还在按旧形态产出：
往暂存区写 `package.properties` + 入口脚本，人审通过后 `register()` 进 `ScriptPackageRegistry`。

但**注册进那个账本已经没有任何意义**：`run_script` 工具删了，`ScriptPackageAutoConfiguration`
只产账本、不产工具，`ExecutionListeners` 那条"被执行时留一条审计"的链也删了。
今天一个通过人审的包，谁也跑不起来——它是死数据，而链路（五步、审计、人审）看起来全绿。

用户侧的可见差异：模型调 `propose_script` 交草稿 → 人签字 → 什么都发生不了。
**这比没有这个功能更坏**：它承诺了一次"能力变多了"。

## 两条路

| | A. 产出物改成技能（对齐 OpenClaw 的 Skill Workshop） | B. 整层下掉 |
| --- | --- | --- |
| 做什么 | 生成器写 `SKILL.md`（front matter + 用法正文 + 把脚本放进 `scripts/`），人审通过后落到 `claw4j.skills.directory` 下的新目录；权限动作从 `draft_script` 改成 `draft_skill`（OpenClaw 侧是 `skill_library`） | 删 `capability/generator` 整包、`propose_script`、`claw4j.generator.*`、`claw4j.scripts.*` 与生成器那几条测试；ADR-0013 标 superseded |
| 动多少 | `SkillGenerator`（551 行）+ `ScriptStaticCheck`、`ReviewGate`、`GenerationAudit`、`IsolationSpec`、`GeneratorSandbox` 的形状都要跟着改；测试 30+ 条 | 删约 2500 行 + 6 个测试文件 |
| 保住什么 | "能自己长出新能力"这条定位；人审与审计的链路 | 少一层要维护的东西 |
| 丢掉什么 | —— | 定位里"技能生成"这一块 |

## 判据（A 路，若选它）

- `SkillGeneratorTest` 里"发布出来的是一份技能"：目录下有 `SKILL.md` 与 `scripts/`，
  且 `AgentSkills.names(目录)` 能读到它；
- 端到端：交草稿 → 人签字 → **模型能在下一轮用 `Skill` 载入它、用 `Bash` 跑起来**
  （今天这条链断在"没有人能跑"）；
- 未人审的目录不出现在 `AgentSkills.names` 里（与现在的 fingerprint 语义一致）。

## 为什么先不开工

改产出物等于重做 `SkillGenerator` 的五个环节（静态检查的对象、自测怎么跑、审计 stage 的名字、
人审标记的摘要范围），那是另一张完整的票，不是票 02 的收尾。而且它有一个前置裁决：
**这个仓库要不要保留"模型自己写技能"这个能力**。ADR-0013 当时给的理由是"开放扩展的定位"，
OpenClaw 现在确实有 Skill Workshop —— 所以在"对齐 OpenClaw"这条线上，A 是站得住的；
但它是产品决定，不是实现细节。

## Answer：选 B，整层删掉（2026-11-18）

判据不是"它没用"，是**它现在是一条断链**：链路越完整，越容易让人相信它能用。
重建的前提是"模型写出来的脚本会被反复用到"，那是产品判断，不该由一行代码替人决定。

**删掉的：**

- `capability/generator` 整包（11 个类，约 2500 行）：`SkillGenerator`、`SkillGeneratorToolProvider`、
  `SkillGeneratorToolCallback`、`GeneratorAutoConfiguration`、`GeneratorProperties`、`GeneratorSandbox`、
  `GenerationAudit`、`IsolationSpec`、`ReviewGate`、`ScriptStaticCheck`、`ScriptLanguage`。
- `capability/script` 整包（7 个类）——它只剩"给生成器记账"这一个用途：
  `ScriptPackage`、`ScriptPackageCatalog`、`ScriptPackageRegistry`、`ScriptPackageProperties`、
  `ScriptPackageAutoConfiguration`、`SandboxPolicy`、`SandboxViolationException`。
- `ExternalContent.Source.SCRIPT`（唯一的生产者是已删的执行链）。
- 两段配置（`claw4j.generator.*`、`claw4j.scripts.*`）与两条自动装配登记。
- 测试：`SkillGeneratorTest`（30+ 条）、`ToolFilterBindingTest` 里那段生成器/账本绑定、
  `BuildContractTest` 的 `GeneratorSandbox.java` shell 豁免名额。

**没删的（判断过的）：**

- `capability/sandbox`（`SandboxProperties` / `SandboxBackends` / `SandboxAutoConfiguration`）——
  它服务的是 exec 的容器后端，与生成器无关。
- `ExternalContent.Source` 这个类型本身：网页、搜索、知识库三个来源还在用。
- `ToolProfileTest` 里的 `run_script` / `propose_script` 两个名字：它们是档位数学的桩名，
  不指向任何真实工具；改它们只会让一张 22 条的用例表多一次无意义的重排。

**文档：** ADR-0013 标 `superseded` 并写明重建形状（Skill Workshop）；ADR-0002 / 0003 / 0030
各补一句"生成器已删"；`capability-layers.md` 那一节改成删除记录；`GLOSSARY.md` 删掉
「Skill Generator」「draft_script」「第 ② 类沙箱」「静态检查」「两个执行位置」五条，
「人审」改成描述技能审批；`docs/capability-tests.md` 删掉 `propose_script` 那一行并标注 5 号位已空；
`P18a`（doctor）的目录检查项去掉两个已不存在的键。

**门禁：** `./scripts/verify.sh` 四步全绿。

**要重建的话**：按 Skill Workshop 的形状——产物是 `SKILL.md` + `scripts/`，写进技能目录，
审批复用 `SkillTrust`（目录摘要）、执行复用 `Bash`。**不要**再建第二套执行入口。
