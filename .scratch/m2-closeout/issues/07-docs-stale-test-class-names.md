# 07: 文档里的测试类名已经普遍失效

**What to build:** `docs/` 里指"证据在哪"的测试类名，有 25 个已经不存在了（现存测试类 22 个）。
把它们改到"现在承载那段行为的类与方法"，或者改成描述行为而不点类名。ADR 与设计文档里
"这条由 `XxxTest` 盯着"是本仓库最常用的一句论证——读者照着它去仓库里找，找不到，那句话就退化成口号。

**Blocked by:** 无

**Status:** ready-for-agent

**实测（2026-10-26，扫 `docs/**/*.md` + 根目录 md 里的 `*Test` 记号，与
`claw4j-*/src/test/**/*.java` 的文件名比对）：**

| 失效类名 | 出现在 |
| --- | --- |
| `TextToSqlLoopTest`、`ToolCallBudgetTest` | ADR-0008、`design/text-to-sql.md` |
| `AuthorizationOverHttpTest` | ADR-0011、`design/authorization.md` |
| `SalesQueryEndToEndTest`、`HttpChannelEndToEndTest` | ADR-0011 |
| `SubagentRunnerTest`、`SubagentEndToEndTest` | ADR-0012、`design/capability-layers.md` |
| `AgentSkillsTest`、`AgentSkillsEndToEndTest`、`ScriptPackageRunnerTest`、`SandboxPolicyTest`、`SandboxedExecBackendTest`、`RealCliMcpEndToEndTest` | `design/capability-layers.md` |
| `BudgetAcrossLayersTest`、`PermissionsAcrossLayersTest`、`RealCliMcpEndToEndTest` | ADR-0014 |
| `AppArchitectureTest`、`LocalProcessRunnerTest`、`NoHandBuiltShellCommandTest` | ADR-0002 |
| `AdvisorBothPathsTest`、`AdvisorNotSkippedOverHttpTest`、`AgentArchitectureTest` | ADR-0007 |
| `RoutingChatModelTest`、`RealModelTest` | ADR-0006 |
| `SchemaMigrationOnStartupTest` | ADR-0005 |
| `SessionMemoryDegradationTest` | `design/session-memory.md` |

**方向定死：文档跟代码走，不是代码跟文档走。** 这些类多半是 M1/M2 里被合并进更大测试文件的
（现在是"一个功能一个文件、一个文件多个方法"，见 `CONTRIBUTING.md`）。所以改的是文档里的指针；
**不许为了对上文档去把方法拆回成类**——那会让票面记录与 git 历史对不上，也违反"拆功能，别拆文件"。

- [ ] 逐个失效类名，先按它描述的**断言**在现存测试里定位承载者（例：ADR-0011 说的"第二轮提示词里带着
      `128400.00`"在 `SalesQueryTest`；ADR-0007 说的"只实现 `CallAdvisor` 会被流式跳过"在
      `ChatPipelineTest`），改字段落里的类名
- [ ] 定位不到承载者的，**不许改成相近的名字**：那说明那段行为根本没有测试。就地标 `⚠ 无测试`，
      并在票尾列出来——这种情况下是行为缺证据，不是文档写错，另开票
- [ ] 一个类名承载多段行为、改完读起来别扭的地方，改成描述行为（"由 `XxxTest` 里那条『…』用例盯着"）
      或直接去掉类名，不新增段落
- [ ] ADR-0010「什么时候该拆模块」那句"全仓库 Java 不到 400 行"：实测 main 10,140 行、
      test 4,357 行，**只改数字**。数字一改，"当前不满足任何一条"就不再自明了——
      要不要拆 `claw4j-capability`（5,092 行、四个变化原因：工具 / 脚本 / 技能 / MCP）是决策，
      **本票不做**，在票尾写"结论待重新评估"交给人
- [ ] 收尾时把"失效类名数"再跑一遍，归零（或只剩写清 `⚠ 无测试` 的那几条）

**落点:** `docs/adr/0002,0005,0006,0007,0008,0010,0011,0012,0014`、`docs/design/{text-to-sql,authorization,capability-layers,session-memory}.md`。

**注意:** 只换"去哪找"的指针。判断与论证一个字不改——"为什么这么设计"是 ADR 的主体，
本票不碰。改完读一遍：**换了名字之后那句话还得成立**（承载者真的在验这件事，而不是名字像）。

**不要做:** 重命名/拆分测试类去迎合文档；把没有测试的行为写成"由某测试盯着"；顺手改论证段落；
在本票里决定 `claw4j-capability` 拆不拆。
