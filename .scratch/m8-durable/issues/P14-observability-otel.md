# P14 · 可观测与 OTel 导出：一条 run 连到它的每个 step 与每次工具调用

**Status:** ready-for-agent
**Blocked by:** P07（审计口径已落地，`agent_step` 的六列是这张票要对齐的那份事实）

## 背景

ADR-0020 的原话：「现在只有 Micrometer 的 `claw4j.tool.calls{tool,outcome}`，以及独立的运维端口。
补 trace 导出（一条 run 能连到它的每个 step 与每次工具调用）。注意审计 ≠ 评测 ≠ 观测。」

三件事的分界已经写在 spec 里，这里只重复一句最容易混的：**审计回答「谁让谁做了什么」（合规、落库可查），
观测回答「现在系统怎么样」（Ops、可聚合）**。所以这张票不往 `agent_step` 上再加列，
也不让 trace 成为「审计的第二份副本」——trace 里没有参数、没有原文、没有产出。

### 现状（实测，不是回忆）

指标这一侧已经有一批，都在 `ObservabilityTest`（16 条）里钉着：

| 指标 | 出处 | 标签 |
| --- | --- | --- |
| `claw4j.tool.calls` | `MeteredToolCallback#count` | `tool` / `outcome`（`ok` `error` `budget_refused` `denied` `awaiting_approval`） |
| `claw4j.model.tokens` | `MeteredChatModel#tokens` | `kind`（prompt / completion） |
| `claw4j.model.calls` | `MeteredChatModel#calls` | `route` / `outcome` |
| `claw4j.model.duration` | `MeteredChatModel#duration` | `route` / `stream` |
| `claw4j.cache.hits` / `misses` / `expired` | `CacheAdvisor` / `SemanticCacheCleanup` | — |
| `claw4j.run.errors` | `JdbcRunStore` 的降级路径 | — |

但**没有 trace**：一次 run 在时间轴上只有 `agent_run.created_at / updated_at` 与
`agent_step.created_at` 三列时间戳，跨线程、跨进程的顺序只能靠时间戳去拼。
拼不出来的是这三件：一次 run 里哪几次工具调用属于哪一轮模型回答、
一次工具调用到底花了多久（`agent_step` 没有 duration 列，也不该有）、
以及「这一轮为什么慢」到底是模型慢还是工具慢。

## 必须先回答的四个结构问题

### 1. 用哪套 SDK：直连 OpenTelemetry，不用 Boot 的 starter

Boot 4.1.2 对这条路的支持是齐的（`spring-boot-dependencies-4.1.2.pom` 第 150、151、160 行）：

```
<micrometer.version>1.17.2</micrometer.version>
<micrometer-tracing.version>1.7.2</micrometer-tracing.version>
<opentelemetry.version>1.62.0</opentelemetry.version>
```

而且它管着 `spring-boot-starter-opentelemetry`、`spring-boot-micrometer-tracing-opentelemetry`、
`spring-boot-micrometer-tracing-brave` 三个坐标（第 1893、1903、2648 行）。

**但本地仓库里一个都没有**（`ls ~/.m2/repository/org/springframework/boot/` 里没有
`spring-boot-starter-opentelemetry`、没有 `spring-boot-micrometer-tracing*`），
也没有 `io/micrometer/micrometer-tracing-bridge-otel`；`micrometer-tracing` 只有 **1.5.6**，
而 BOM 要 **1.7.2**。也就是说：**引 starter 就等于把构建绑到一次联网解析上**，
而本项目的门禁是 `./mvnw -o`（离线）跑的。

反面是够用的：`opentelemetry-sdk:1.35.0` + `opentelemetry-exporter-otlp:1.35.0` 的整条链
在离线模式下**解析成功**。用一个临时 pom（`/tmp/otel-probe-135`，验完即删）实测的依赖树：

```
+- io.opentelemetry:opentelemetry-sdk:jar:1.35.0:compile
|  +- io.opentelemetry:opentelemetry-api:jar:1.35.0:compile
|  |  \- io.opentelemetry:opentelemetry-context:jar:1.35.0:compile
|  +- io.opentelemetry:opentelemetry-sdk-common:jar:1.35.0:compile
|  +- io.opentelemetry:opentelemetry-sdk-trace:jar:1.35.0:compile
|  |  \- io.opentelemetry:opentelemetry-extension-incubator:jar:1.35.0-alpha:runtime
|  \- io.opentelemetry:opentelemetry-sdk-logs:jar:1.35.0:compile
|     \- io.opentelemetry:opentelemetry-api-events:jar:1.35.0-alpha:runtime
\- io.opentelemetry:opentelemetry-exporter-otlp:jar:1.35.0:compile
   +- io.opentelemetry:opentelemetry-exporter-otlp-common:jar:1.35.0:runtime
   |  \- io.opentelemetry:opentelemetry-exporter-common:jar:1.35.0:runtime
   +- io.opentelemetry:opentelemetry-exporter-sender-okhttp:jar:1.35.0:runtime
   |  \- com.squareup.okhttp3:okhttp:jar:4.12.0:runtime
```

两条要留意的细节：

- `sdk-trace` 的 incubator 依赖写的是 **`1.35.0-alpha`**（不是 `1.35.0`）。看着像缺件，
  实际本地有 jar；第一次核的时候我以为缺，是把 `awk` 的字段对齐看错了——**以依赖树为准**。
- **版本要显式写 `1.35.0`**，否则 Boot BOM 会把它顶成 `1.62.0`，而本地没有 1.62.0 的任何构件。

**结论：自己装配 `SdkTracerProvider`，显式版本。** 理由与 P11 不用 `mcp-server` starter 同一条：
starter 会带回一整套控制不了的装配（它的 `ToolCallbackConverterAutoConfiguration` 就绕过过三道门），
而离线仓库在这里也不支持它。

### 2. gRPC 还是 HTTP：HTTP

`opentelemetry-exporter-otlp:1.35.0` 的 jar 里 `OtlpGrpcSpanExporter` 与 `OtlpHttpSpanExporter`
都在，但 **运行时依赖只有 HTTP 那一半**：依赖树里带的是 `opentelemetry-exporter-sender-okhttp`，
没有 gRPC sender。也就是说 `OtlpGrpcSpanExporter.builder()` 编译得过、启动时会因为找不到 sender 而炸
——这是那种「本地跑得起来、换台机器就炸」的错。

用 `OtlpHttpSpanExporter`，默认路径 `/v1/traces`。它的 API 已用 `javap` 对着 1.35.0 的 jar 核过：

```
public static io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter getDefault();
public static io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporterBuilder builder();
public io.opentelemetry.sdk.common.CompletableResultCode export(java.util.Collection<SpanData>);
public io.opentelemetry.sdk.common.CompletableResultCode flush();
public io.opentelemetry.sdk.common.CompletableResultCode shutdown();
```

### 3. span 的根是 run，不是请求

五个通道（HTTP、飞书、企微、钉钉、Slack）都用 `ThreadPoolTaskExecutor` 把 `agent.reply` 丢进池里执行
（`HttpChannelAutoConfiguration` 第 67 行、`WecomChannelAutoConfiguration` 第 77 行、
`SlackChannelAutoConfiguration` 第 80 行等）。请求的 trace 在入池那一刻就断了：池里那条线程
没有调用方的 Context。

而 **run 是系统里「一件事」的身份**（ADR-0016）：它跨通道、跨重试、跨进程重启（resume 时还是同一个 runId）。
把根放在 run 上，trace 的边界与库里那张表的边界是同一件事；放在请求上，一次 resume 的 run
会变成两个互不相干的 trace，而「这件事一共花了多久」永远答不出来。

**所以根 span = 一次 run 的执行体**（`ChatClientAgentPort#answer` 与 `#stream`），
属性带 `claw4j.run.id`。请求级 trace（W3C `traceparent` 透传）**不做**，见「不做的事」第 1 条。

另外一条实测依据：**子 agent 不会长出第二棵树**。`SubagentRunner` 直接调 `chatClient`，
它手里没有 `AgentPort` 也没有 `RunStore`（第 124 行的注释就写着「子这边没有 AgentPort，只能自己补」），
所以子 agent 的模型调用天然挂在调用它的 tool span 下面。

### 4. step 不是 span，是 span 上的属性

「一条 run 能连到它的每个 step」有两条实现路，选第二条：

- **给每一行 step 开一个 span**：那这个 span 的 duration 就是一次 JDBC INSERT 的时间，
  对「这次为什么慢」零信息量；而 `StepKind.APPROVAL` 那类 step 要等用户点按钮，
  给它挂 span 会挂十分钟到一天（`RunStore#suspend` 的注释写的正是「不是挂着的线程」）。
- **每个动作 span 在关闭前把 `append` 返回的 `seq` 写进属性**：span 的边界仍然是动作
  （模型一轮、一次工具调用），而 `claw4j.step.seq` 让 trace 里的每个 span 都能按
  `(run_id, seq)` 反查到库里那一行。

代价写在明处：`RunStore#append` 已经返回 `Optional<Step>`，但两个调用点都把返回值丢了
（`ChatClientAgentPort#closeTurn` 第 464 行、`ToolCallAudit#executed` 内部）；
要拿 seq 就得接收它，而 `ToolCallAudit#executed` 现在是 `void`，得改成返回 `Optional<Step>`。
改签名会影响 `MeteredToolCallback` 一个调用点与它的用例——这一处改动是必要的，因为它正是
「审计行」与「观测 span」唯一能对上号的地方。

## 落点

```
claw4j-agent/src/main/java/io/github/claw4j/agent/observability/
    RunTracer.java            接口：open(name, attrs) -> SpanScope；NONE 常量
    OtelRunTracer.java        唯一碰 io.opentelemetry 的类
    ObservabilityAutoConfiguration.java   按 claw4j.observability.* 装配或退让
```

`RunTracer` 的形态（草案，实现可微调，但**不能让 otel 类型漏到接口外**）：

```java
public interface RunTracer {
    RunTracer NONE = ...;                       // 默认，零开销
    SpanScope open(String name, Map<String, String> attributes);
    interface SpanScope extends AutoCloseable {
        void attribute(String key, String value);
        void failed(Throwable cause);
        @Override void close();
    }
}
```

用 try-with-resources 嵌套，父 span 由 OTel 的 Context（ThreadLocal）自动接上——
`answer`、`MeteredToolCallback` 与工具的 delegate 都在同一条线程上，不需要显式传 Context。

三个埋点：

| 位置 | span 名 | 属性 |
| --- | --- | --- |
| `ChatClientAgentPort#answer` / `#stream` | `run` | `claw4j.run.id`、`claw4j.owner`（**只用指纹，不用 userId**） |
| 同上，包住 `call()` + `closeTurn` | `model.turn` | `claw4j.step.seq`、`gen_ai.route`、`claw4j.tokens` |
| `MeteredToolCallback#call`（两个重载） | `tool.<工具名>` | `claw4j.step.seq`、`claw4j.tool`、`claw4j.outcome`、`claw4j.error.type` |

`model.turn` 包住 `closeTurn` 是刻意的：seq 要先写库才拿得到，而 span 的 duration 因此含那一次 INSERT
（几十毫秒）。反过来（先关 span 再写 seq）会让属性丢掉——OTel 的 span 在 `end()` 之后的
`setAttribute` 是 no-op，**不报错**。

配置键（`claw4j.observability.*`）：

| 键 | 默认 | 说明 |
| --- | --- | --- |
| `enabled` | `false` | 关着就是 `RunTracer.NONE`，不建线程、不建 provider |
| `endpoint` | 空 | OTLP HTTP 的 base，例如 `http://localhost:4318`。**空着即便 enabled=true 也不导**（并在启动日志里说一句），否则就是「以为开了」 |
| `service-name` | `claw4j` | resource 属性 |
| `sample-ratio` | `1.0` | `TraceIdRatioBased` + `ParentBased`，见下 |
| `timeout` | `PT10S` | exporter 超时 |
| `max-queue-size` | `2048` | Batch 队列上限 |

采样必须是 **`ParentBased(TraceIdRatioBased(ratio))`** 而不是裸的 ratio：裸 ratio 在父 span 已采样的
情况下仍可能把子 span 丢掉，表现是「trace 里有 run、没有 tool」——而这种缺口看起来像工具没被调。

生命周期：provider 在 `@PreDestroy` 里 `forceFlush()` 再 `close()`。不 flush 的话
Batch 队列里最后几条在进程退出时直接消失，而丢的恰好是「出事前的那几秒」。

## 验收判据

- [ ] **层级是真的父子**：一条 run 产生一个 trace，`tool.*` 的 parentSpanId 等于它所在那一轮
      `model.turn` 的 spanId，`model.turn` 的 parent 是 `run`。
      断言的是 id 关系，不是「有 N 个 span」——只数个数的话，把三个 span 都挂在 root 下也能绿。
- [ ] **step 能反查**：每个动作 span 上的 `claw4j.step.seq` 加上 `claw4j.run.id`，能在
      `RunStore#stepsOf(runId)` 里找到对应那一行，且 `kind` 与 span 名对得上
      （`model.turn` ↔ `MODEL_TURN`，`tool.*` ↔ `TOOL_CALLS`）。反例：seq 写死成 1 → 红。
- [ ] **失败也留痕**：工具抛异常时，`tool.*` span 的 status 是 `ERROR` 且带 exception 事件，
      `run` span 也是 `ERROR`。反例：只在成功路径 `close()` → 失败时 span 没有 error 状态。
- [ ] **默认不导**：不配 `claw4j.observability.enabled` 时，容器里没有 `SdkTracerProvider`，
      `RunTracer` 是 `NONE`，跑一轮**不产生任何出站请求**。
- [ ] **开了但没配 endpoint**：`enabled=true` + `endpoint` 空 → 仍然不建 exporter，
      并且启动日志里点名缺哪一项（「开了却没配」必须能自己说出来，否则运维会去查网络）。
- [ ] **导出真的发出**：起一个假的 OTLP collector（JDK `HttpServer`，收 `/v1/traces`），
      断言收到 ≥1 个请求、body 非空、`Content-Type` 是 `application/x-protobuf`。
      反例：把 exporter 换成 `SpanExporter.composite()` 空集合 → 这条红。
- [ ] **退出前 flush**：`close()` 之后假 collector 收到的 span 数等于 `close()` 之前内存里的数量
      （Batch 队列没有留在手里）。
- [ ] **采样生效**：`sample-ratio=0` → 0 条；`1.0` → 全量；父子一致（不会出现「有子无父」）。
- [ ] **观测不写业务库**：开 trace 前后各跑一条同样的问题，`agent_step` 的行数与内容不变
      （trace 不是审计的第二份副本）。
- [ ] **不重复造指标**：不新增任何 `claw4j.*` 计数器——同一次工具调用不该同时被 counter 与 span
      各记一遍，而两份口径迟早会漂。
- [ ] **模型与工具的现有行为不变**：`ObservabilityTest` 原有的 16 条一条不改地通过
      （trace 是在外面套一层，不是改里面的逻辑）。
- [ ] `./scripts/verify.sh` 全绿，跳过 0，行覆盖率不低于 60%。

## 不做的事

1. **不做请求级 `traceparent` 透传**。通道入站（HTTP header / 飞书事件 / Slack 回调）抽 traceparent、
   再在线程池任务里 attach，是另一件事，而且它与「根是 run」这条裁决会打架
   （同一个 run 被两次请求碰到时，第二次会变成第一个的子 span，而它们其实是并列的两次执行）。
   重估条件：有人要从网关那侧看到端到端链路。
2. **不做 metrics 的 OTLP 导出**。指标仍走 actuator 与 Prometheus，trace 只走 OTLP。
   两套导出器各配一遍端点是运维负担，而 metrics 那条路已经能用。
3. **不装 `ObservationRegistry`**。`AgentAutoConfiguration#chatClient` 现在给的是
   `ObservationRegistry.NOOP`（第 141 行 `getIfUnique(() -> NOOP)`），也就是框架层的 observation
   全关着。把它打开会让 Spring AI 内部开始产 observation，而要变成 span 还需要一个
   `ObservationHandler`——本地没有 `micrometer-tracing-bridge-otel`，自己写一个 handler 会把
   「框架版本升级」变成一个不报错的漂移源（observation 名的变化不会让任何用例红）。
   重估条件：真要拿到框架内部的 HTTP client / MCP 往返细节时，再评估自写 handler。
4. **不把 prompt / response 放进 span**。trace 会进第三方后端，而模型输入输出是 PII
   （`PiiRedactionAdvisor` 只管出站文本，不管 trace）。span 上只放路由、token 数、工具名、结果档位。
5. **不做日志与 trace 的 MDC 关联**。工具循环内部在一条线程上，MDC 注入只是几行；但通道线程池
   与 `RunDelivery` 的异步投递两处要各自传，第一版不做。重估条件：有人抱怨「日志里搜不到 traceId」。
6. **不给 `agent_step` 加 trace_id 列**。那是把观测塞进审计表，正是 ADR-0020 说的「三件事合并」。

## 门禁余量

**两个模块都已经 10 个测试文件，而且最紧的两个文件都顶格 20 条**，所以本票必须先腾槽：

- `claw4j-agent`：`ObservabilityTest` **16 → 20**（trace 的 4 条：层级、seq 反查、失败留痕、默认不导）。
  不新增文件——它本来就是「观测」这个主题的家，`ScheduleToolsTest` 那边是工具入参校验，主题不同，
  硬合会得到 30 条超限。
- `claw4j-app`：`ApplicationAssemblyTest` 与 `RunSchemaSqlTest` 都是 20 条顶格，加不进去。
  **合并 `RunBudgetTest`(6) + `RunDeliveryTest`(6) → `RunLifecycleTest`(12)**，腾出的槽给
  `TraceExportTest`（真 collector 的端到端：导出成功、flush、采样三件，都在这里）。
  合并的理由：两份守的都是「一次 run 的周边——花多少、送到哪」，setup 是同一套 runner，
  差别只在断言的是额度还是地址。
- 依赖加在 `claw4j-agent/pom.xml`：`opentelemetry-sdk` 与 `opentelemetry-exporter-otlp`，
  **显式 `<version>1.35.0</version>`**。
- 结构规则：`RunTracer` 在 `agent` 包内，otel 只出现在 `OtelRunTracer` 与装配里。
  `claw4j-core` 一行都不碰（它的 ArchRule 只允许 JDK + slf4j + domain）。

## 已知局限（写在这里，不要留到实现中途发现）

- **Boot BOM 的 1.62.0 与我们要用的 1.35.0 并存**。显式版本能压住，但升级 Boot 时要一起看：
  BOM 升到某个版本之后，1.35.0 的 api 可能与新的 Spring 生态不兼容（目前只有一个来源用它，风险低）。
- **`model.turn` 的 duration 含落库**。理由见上，量级是几十毫秒；如果哪天它变成主要耗时，
  说明库慢了，而那本来就是该看见的信号。
- **trace 里没有审批等待**。`APPROVAL` 类 step 不产生 span（它跨请求），只会以
  `claw4j.outcome=awaiting_approval` 的工具 span 结尾——「等了多久」要靠审批表自己的时间戳。
- **采样是进程内的**。多副本部署时每个副本各自按 ratio 采样，所以「同一件事」不会因为换了
  副本而换采样决定（run 不会漂到另一个副本上执行，除非有人手动 resume）。
- **Batch 队列满了会丢**。默认 2048，丢的时候 OTel 自己会打日志；我们不额外加计数器（见判据 10）。

## Comments

