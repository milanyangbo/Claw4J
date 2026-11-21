# 票 01：全仓迁到 Jackson 3，删掉 Jackson 2 的显式依赖

状态：**待做**。裁决：`docs/adr/0035-model-gateway-module.md` 第八节。

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
| 源码里不再出现 Jackson 2 的 databind import | `Jackson3MigrationTest#noJacksonTwoImportsRemain` | 留一个 `com.fasterxml.jackson.databind.ObjectMapper`，扫描命中 |
| 运行时 classpath 上没有 Jackson 2 的 databind | `Jackson3MigrationTest#jacksonTwoDatabindNotOnClasspath` | `Class.forName("com.fasterxml.jackson.databind.ObjectMapper")` 成功 |
| 已有的 JSON 往返行为不变（goal 存读） | `Jackson3MigrationTest#goalRoundTrips` | 字段名或时间格式变了 |
| `Instant` / `Duration` 不需要额外注册模块 | `Jackson3MigrationTest#javaTimeRoundTripsWithoutModule` | 抛 `InvalidDefinitionException` |
| 入站报文与审批回调仍能解析 | `ApprovalFlowTest#approvalCallbackStillParses` | 解析失败或字段丢失 |

## 缺口

- 序列化器配置（大小写、时间格式）如与 Jackson 2 默认不同，要在迁移里显式固定；本票不改变现有报文格式，
  跑一遍现有集成测试确认。
