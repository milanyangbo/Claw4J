# 03: 生成器的产出物改成技能（或整层下掉）

**Status:** draft（等裁决：改产出物 / 下掉这一层）

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
