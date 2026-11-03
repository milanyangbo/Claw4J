# P08: 凭据句柄——配置里只有名字

**What to build:** 把"真值"从配置里挪出去，只留句柄。配置项（模型 key、MCP 的 `env` 与
`headers`）里写的是句柄名，真值在**出口那一刻**才被解析：发给模型的请求头、起 MCP 子进程时的
环境、发出的 http header。模型在任何路径上看到的都是句柄，日志与审计里也只有句柄。

这不是"再加一层间接"：现在 `${ENV}` 由 Spring 在配置绑定时解析，真值进入
`ModelProperties.Provider#apiKey` 这类 String 字段并活到进程结束，任何能读到配置的组件都能
随手拿到——也答不出"这次调用用的是哪把钥匙"。要改的是**真值的生命周期**，
从"整个进程"缩到"出口调用"。

**Blocked by:** None (can start immediately)——不 gate 在任何 M7 的票上

**Status:** resolved

- [x] 配置与示例里只有句柄名：模型两家 key、MCP 的 `env` 与 `headers` 在
      `application.yml`、`application-local.yml.example`、`docs/design/extension-points.md`
      三处都写句柄；出现非句柄形状的值时启动失败并点名是哪个配置项
      （形状由实现定，但必须可被校验，不能靠"看着像"）
      —— 形状定在 `SecretValue.parse`：`secret:<名字>`，或 `plain:<文本>`
      （**只有** MCP 的 `env` / `headers` 接受 `plain:`，模型 key 一律只认句柄）。
      `application.yml` → `api-key: secret:deepseek_api_key` / `secret:qwen_api_key`；
      示例里的头 → `Authorization: "Bearer secret:deepwiki_token"`。
      证据：`SecretValueTest#plainCredentialIsRefusedWithoutEchoingIt` / `#uppercaseHandleIsRefused`、
      `McpServersConfigTest#envValueMustBeAHandle`（报错点名到 `servers.demo.env.MCP_TOKEN`）。
- [x] 真值只在三个出口解析：模型 HTTP 请求头、MCP 子进程环境、MCP http header。
      配置类不再持有真值——没有任何 API 能在任意时刻取到它
      —— `ModelProperties.Provider#apiKey()` 返回 `SecretValue`，**没有** String getter；
      模型出口：`ModelConfiguration` 给 `DeepSeekApi` 一个每次请求执行的 `ApiKey` lambda、
      给 `OpenAIClient` 一个 `BearerTokenCredential.create(Supplier)`（
      `CacheAutoConfiguration` 的向量模型同一条接缝）；
      MCP stdio 出口：`ProcessStdioMcpEndpoint#openTransport` → `environment(...)` 里 render；
      MCP http 出口：`HttpMcpEndpoint#requestCustomizer` 每次请求 render。
      证据：`McpServersConfigTest#headersKeepHandlesUntilTheExit`（真调 customizer，
      断言请求头里是真值）；`ChatPipelineTest` 两条真模型用例（真请求带上了 key）；
      本机另跑过一次 qwen-plus 真调用验证 `BearerTokenCredential` 这条接缝（探针跑完即删，未入库）。
- [x] 模型可见面搜不到真值（**判据按下面 Comments 第一条改写过**）：
      模型能填的只有工具入参，而它从没见过真值；审计摘要与指纹都只吃入参
      （`ToolCallAudit#summaryOf` / `#digestOf`）
- [x] 审计与日志里只有句柄名：`agent_step.args_summary` / `args_digest`、应用日志、
      审批卡片三者都验，且能答出"这次用的是哪个句柄"
      —— 日志：`ModelConfiguration#logModel` 打 `凭据=secret:qwen_api_key`、
      `CacheAutoConfiguration` 的向量模型日志同款、
      `McpEndpoints#handleValues` 打 "MCP server X 的 env 用了句柄 [...]"；
      审计行与审批卡片不含凭据（入参里没有真值，摘要是脱敏后截断）
- [x] 缺句柄时启动失败，报错只说句柄名，不回显值、不回显片段
      —— `MissingSecretException` 的消息只有：句柄名、环境变量名、配置项名。
      启动期三处探测：`ModelConfiguration#requireConfigured`、`McpEndpoints#handleValues`、
      `RealChatModelCondition#resolvable`（只问在不在，不取值）
- [x] 脚本侧不退化：`SandboxPolicy.environment()` 仍然只给 `PATH`
      —— 这个类一行没改；`ScriptPackageTest#scriptEnvironmentCarriesOnlyPath`
      断言子进程环境只有 `PATH`，且宿主环境里有 `DEEPSEEK_API_KEY` 时也不给脚本
- [x] `./scripts/verify.sh` 全绿，跳过 0
      —— 478 条用例全过（P08 前是 465），跳过 0；
      行覆盖 core 87.3% / capability 84.9% / agent 62.0%，均不低于 60%

## Comments

- 2026-11-03 拆票。依据 ADR-0020 的 P08 段原文：「现在 MCP 的 env 与模型 key 都是明文配置，
  脚本只给 PATH（那一条是对的）。改成 secret handle：配置项里只写句柄名，出口前替换成真值，
  模型在任何路径上看到的都是句柄。」判据原文：「在模型的入参与工具返回值里搜不到任何真 key；
  日志与审计记录里也只有句柄。」
- **现状核过，票面的"现在是什么"照着代码写**（别把它写成比代码更糟的样子）：
  模型 key 是 `application.yml:56` 的 `${DEEPSEEK_API_KEY:}`，一家 key 都没有时由装配方法
  fail fast（判定在 `RealChatModelCondition`），yml 里本来就没有真值；MCP 的 `env` 是
  `McpServerProperties` 的 `Map<String, String>`，`McpEndpoints` 直接 `env.putAll(declared.env())`
  交给子进程；`headers` 的 javadoc 要求写 `${ENV_VAR}` 占位符（`McpServerProperties:99`），
  但它在配置绑定时就被解析成真值，`McpEndpoints` 拿到的是解析后的字符串。
  所以真正的缺口不是"仓库里有明文"，而是**真值进了进程就成为可随手取用的字符串**，
  且 `env` 那条允许直写。
- **留给实现的三个决定**（票不替它选）：
  1. 句柄的形状与来源（`secret:name` 这类前缀、还是独立的 `claw4j.secrets` 段、
     还是沿用占位符但延迟解析）——只要求可校验、可点名到配置项；
  2. 解析失败时的行为：启动期失败还是出口期失败。倾向**启动期**——一个跑起来之后才在
     第 200 次请求上失败的系统，比起不来的系统更难查；
  3. 解析结果的缓存策略：每次出口都解析（简单、可轮换）还是缓存（省一次查找）。
     倾向每次都解析——凭据轮换是这类机制存在的理由之一，缓存把那个理由削弱了。
- **不做**：外部 secrets manager 接入（Vault / KMS 这类）、OAuth 授权码流。
  后者是 README「还没做的」里另一条限制（MCP 只有静态请求头），不是这张票的范围。
- **与 P07 相邻但不重叠**：审计已经落参数摘要与指纹，但那解决的是"不落完整参数"；
  这张票解决的是"真值根本不进配置与上下文"。两者都要，缺一个都答不出合规那句"凭据有主"。
- 拆票说明：M8 的其余八张（P09–P16）尚未拆，见 `.scratch/m8-durable/spec.md` 的 Comments。
- 2026-11-03 收票，**改了第三条验收**。原文是"让工具回显它的入参与环境、让 MCP 的 `echo`
  回显参数，断言输出里没有真值"——这条按字面做不到：给 MCP 子进程的凭据**就是**它环境里的
  那个变量，server 主动回显就一定会出现。能守的是"模型与我们的记录里没有"：模型能填的只有
  工具入参（它从没见过真值），审计吃的是入参，日志打的是句柄。所以判据改成这一条，
  **不再承诺"子进程看不到自己的凭据"**——那句话不成立。
- 顺手收紧一处：MCP 的 `env` 原来允许裸写明文（`HOME: /tmp/x` 那种），现在必须写
  `plain:/tmp/x`。理由正是本票的立题——"允许直写"就是那个口子；把非凭据常量也显式标出来之后，
  配置里再出现裸值一律是错。代价是写 MCP env 时多一个前缀，三处文档都写明了。
- 三个留给实现的点，这次是这么定的：
  1. **形状**＝`secret:<名字>`，名字是环境变量名的小写写法（`secret:deepseek_api_key` →
     `DEEPSEEK_API_KEY`）。没有新开 `claw4j.secrets` 段，也没有沿用占位符：占位符在配置绑定期
     就被解析成真值，正是要改掉的东西。
  2. **缺失在启动期失败**：`requireConfigured` / `handleValues` / `RealChatModelCondition`
     各探一次"在不在"，只问不取。
  3. **不缓存**：每次出口都问一遍（一次属性查找），轮换凭据不必重启。
- 落点交代：接口与值对象在 `claw4j-core` 的 `io.github.claw4j.secret`（那个模块不许有 Spring）；
  读 Spring `Environment` 的实现 `SpringSecretResolver` 在 `claw4j-capability`——
  选它是因为 agent 依赖 capability，而 `.env` 是被 `spring.config.import` 当**属性文件**
  读进来的，直接 `System.getenv` 会让"用 .env 本机跑"这条既定路径静默失效。
  它**不是 bean**：容器里没有可注入的解析器，就没有"某段业务顺手取一下真值"这条写法。
