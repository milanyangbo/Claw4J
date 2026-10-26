# 01: 子 Agent 白名单：空结果不再等于全部权限

**What to build:** 定义文件里 `tools:` 不管写成 YAML 列表（`- querySales`）还是逗号分隔（`querySales, other`），
挑出来的工具集都一样；**没写 `tools:`** 仍然是"继承全部可用工具"（并照旧减掉委派工具自己）；
**写了但一个工具名都解析不出来**是配置错误，当场失败并说清是这节解析不出名字。

判据是"不让一个空结果被当成许可"，不是"多支持一种 YAML 写法"。现在示例里那个只该看一条 SQL 的
复核员，实际拿到了整套工具（含发邮件与跑脚本），而失败是静默的。

**Blocked by:** None (can start immediately)

**Status:** resolved

**Demo:** 拿 `examples/subagents/sql-reviewer.md` 跑一次委派，它挑出来的工具集里没有 `mail_send`，
也没有 `run_script`；再把同一份定义的 `tools:` 只留节名不写内容，委派直接失败并点名是这节的问题。

- [x] `tools:` 写成 `- a` 与 `a, b` 两种形态解析结果一致（同一个断言吃两段输入）
- [x] 只写 `tools:` 不给任何内容 → 定义错误，错误话说清"这节解析不出工具名"（不是"工具不存在"）
- [x] 完全没写 `tools:` 仍走"继承全部"，且委派工具自己被减掉（旧行为不能被改坏）
- [x] `disallowedTools` 与 `tools:` 同口径解析，两者并存时白名单优先（现有语义不变）
- [x] 示例定义的实际工具集里没有 `mail_send` / `run_script`（断言指向示例文件本身，防示例被改坏）
- [x] 白名单点名一个没装上的工具 → 首次委派失败并点名缺哪个（现有行为，保持）
- [x] 变异：把"空结果即继承"改回原写法 → 第 2、5 条变红

**落点:** `SubagentDefinition` 的 front matter 解析改为本项目内实现，只取 `tools` / `disallowedTools`
两个键（社区那套逐行 `key: value` 解析器分不清"没写"与"写了读不出"）。测试加进
`SubagentDelegationTest`，不新增文件。

**注意:** 只加两个键的解析不等于"支持 YAML"。不要为一个列表引入 YAML 库，也不要把不认识的键变成错误。

**不要做:** 通用 front matter 支持；嵌套结构的 `tools`（对象形式的参数说明）；多个委派目标并行。

## 落点

- 新增包内类 `FrontMatter`：自己解析 front matter（键值、列表项、正文），三条判据写在类注释里。
  只认调用方声明的列表键，其余键不接受续行——写歪的配置当场报错，而不是被折成一段看着正常的值。
- `SubagentDefinition`：`tools` / `disallowedTools` 走新的 `names()`；`splitInline` 认 `a, b` 与
  `[a, b]`；写了却一个名字都没有时抛 `IllegalStateException` 并给出两种正确写法。
  "没写这一节"仍是 `List.of()`，语义不变。
- 测试 `SubagentDelegationTest` 15 → 18 条（三种写法一致、空节报错、示例定义的实际工具集），
  不新增文件。该文件的 `definition()` helper 顺带改了：不再写空的 `disallowedTools:` 行——
  按新规则那同样是"写了但空着"。
- 变异（同时施加两项）：把列表项收集那行删掉（模拟旧解析器整行丢弃）+ 去掉空名单检查 →
  三条同时红：`toolListFormsParseToTheSameSelection`、`declaredButEmptyToolSectionIsRejected`、
  `exampleReviewerOnlySeesItsOwnWhitelist`。还原后 18 条全绿。
