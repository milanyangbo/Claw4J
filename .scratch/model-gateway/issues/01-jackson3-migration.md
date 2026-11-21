# 票 01：全仓迁到 Jackson 3，删掉 Jackson 2 的显式依赖

状态：**完成**（2026-11-22）。裁决：`docs/adr/0035-model-gateway-module.md` 第八节。

## 一、目标

Jackson 2 与 3 并存是「坏 JSON 认不出来」的根因之一（`ModelFailures` 得同时认两个异常类型）。先把版本统一，
后面模型层的分类器才可能只有一种 `JacksonException`。

## 二、范围

| # | 改什么 | 要点 |
| --- | --- | --- |
| 1 | 13 个文件换 import | agent 9（`FrozenPlanJson`、`JdbcApprovals`、`ToolCallAudit`、`SessionGoalJson`、`JdbcInboundStore`、`ModelFailures`、`SubagentDelegation`、`WebFetchGuard` + 1 测试）、rag 3（`RagAutoConfiguration`、`RerankDocumentPostProcessor` + 1 测试）、channel 1（`ApprovalController`） |
| 2 | `ObjectMapper` → `tools.jackson.databind.ObjectMapper`，构造用 `JsonMapper.builder().build()` | 不 new ObjectMapper 的默认实例，编解码配置集中一处 |
| 3 | `JsonProcessingException` / `JsonParseException` → `tools.jackson.core.JacksonException` | 它是 **unchecked**；`catch` 块保留，方法签名上的 `throws` 删掉 |
| 4 | `@JsonProperty` / `@JsonCreator` / `@JsonIgnoreProperties` **不动** | `jackson-databind-3.1.7.pom`：3.x 仍依赖 annotations 2.x，包名没变 |
| 5 | 删 `claw4j-rag/pom.xml` 的 `com.fasterxml.jackson.core:jackson-databind` | Boot 4 已带 3.x |
| 6 | `java.time` 不再注册 `JavaTimeModule` | `tools/jackson/databind/ext/javatime/` 内置 |

## 三、对照（OpenClaw）

不参与对齐：它是 TS，没有 Jackson。这条由本项目技术栈决定。

## TDD 用例清单

| 判什么行为 | 用例名 | 先红的样子 |
| --- | --- | --- |
| 各模块主代码里没有 Jackson 2 的 databind | `BuildContractTest#noJacksonTwoDatabindImports` | 在 `claw4j-agent/src/main` 放一个 import 它的类，扫描命中该行（已实测红过） |
| 审批回调报文在 Jackson 3 下仍按注解绑定，缺字段落默认值 | `ApprovalRequestBodyTest#minimalBodyBindsToDefaults` / `#fullBodyKeepsEveryField` | `@JsonCreator` 不生效 → 构造失败；或 `always`/`reason` 变必填 → 400 |

## 实施记录（两处与票面不一致，按实际改）

**1. 「运行时 classpath 上没有 Jackson 2 的 databind」做不到，用例删掉。**
`openai-java-core`（`com.openai:openai-java-core:4.80.0`，`spring-ai-openai` 带进来）自己就用
`com.fasterxml.jackson.databind.json.JsonMapper` 解析 HTTP 响应体，它是硬依赖。删了 SDK 就跑不起来。

连带结论：`ModelFailures` 仍要认 `com.fasterxml.jackson.core.JsonProcessingException`——那不是"我们还在用
Jackson 2"，是 SDK 解析响应体失败时把它包进 `OpenAIInvalidDataException` 抛出来的异常类型。
删掉这一支，这类失败会落到"认不出来 → 不重试"的默认值上，是一次静默的行为变化。
javadoc 已改成这个说法（原先写的"本仓自己用 Jackson 2"已不成立）。

**2. 「`Instant` / `Duration` 不需注册模块」不写用例。**
本仓没有任何一处直接序列化 `java.time` 类型：`SessionGoalJson` 与 `JdbcInboundStore` 都存 epoch 毫秒。
写这条等于测 Jackson 自己，删掉。

**3. 「goal 存读往返不变」不新写用例。**
`TurnFlowTest`（目标读回、resume、预算）、`ExecApprovalTest`（冻结件与批准兑现）、`PersistenceSchemaTest`
已经在真库上把这几条 JSON 写进去读出来了，重复一份只会多一处要跟着改的断言。

## 落地清单

- agent：8 个类换包 + `new ObjectMapper()` → `JsonMapper.builder().build()`；
  `ToolCallAudit` 的 `fieldNames()` 在 3.x 已改名 `propertyNames()`（`Iterator` → `Collection`），改了一行。
- rag：2 个类换包；`RagAutoConfiguration` 里关于"Jackson 2 没有 bean"的那段 javadoc 已重写（前提消失了）；
  删 `jackson-databind` 依赖。
- channel：`ApprovalController` 只有注解 import，包名没变，**一行没改**（这正是要验的：注解仍被 3.x 认）。
- 新增两条测试：`BuildContractTest#noJacksonTwoDatabindImports`（扫描；并进既有的构建契约类，
  因为 `claw4j-app` 的测试文件名额正好卡在 14 个）、`ApprovalRequestBodyTest`（注解绑定 + 缺字段默认值）。

## 缺口

- 序列化器配置（大小写、时间格式）如与 Jackson 2 默认不同，要在迁移里显式固定；本票不改变现有报文格式，
  跑一遍现有集成测试确认。
- `claw4j-channel` 里 9 处 `new ObjectMapper()`（飞书/钉钉/Slack/企微的翻译器与发送器）本来就已是
  Jackson 3，不在本票的 13 个文件里，没动。它们同样可以换成 `JsonMapper.builder().build()`，留作后续统一。
