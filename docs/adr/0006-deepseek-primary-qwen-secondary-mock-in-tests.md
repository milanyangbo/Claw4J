# 模型供应商：DeepSeek 为主，通义走 OpenAI 兼容端点，单测全部 mock

---
status: accepted
date: 2026-10-22
---

真实调用接两家国内供应商：DeepSeek 用 Spring AI 2.x 的一等公民适配器，通义（Qwen）走 OpenAI 兼容端点配置，两者在同一套多模型路由下按用途分流。所有单元测试与 CI 一律 mock 模型调用，不烧 token、不依赖网络。

> **2026-10-23 取代**：上面这条已作废，见 **ADR-0011**（测试连真实模型，`MockChatModel` 已删除）。
> 各节里凡涉及 mock 兜底与 `claw4j.model.mock` 三态的部分，只有"厂商适配与降级规则"仍然有效；
> "按 API key 有无自动降级到 mock"那一节整体失效——没 key 现在是启动失败，不再静默降级。

## Considered Options

**只接一家供应商**
否决。多模型路由是这次重建要演示的能力之一（主对话用强模型，摘要压缩与子 Agent 用便宜模型），只有一家就无从分流，也讲不出成本权衡。

**默认走 Ollama 本地模型**
否决。本地模型能跑通但能力偏弱，Text-to-SQL 与工具调用的成功率不足以演示真实场景；且面试场景里"接的是哪家、怎么适配"本身就是考点，本地模型答不出这个问题。

**真实调用进 CI**
否决。CI 里跑真实调用既慢又花钱，还会因为供应商限流随机变红，掩盖真正的回归。

## Consequences

- 模型名与端点全部走配置，代码里不出现硬编码模型名；供应商切换只改配置。
- 单元测试对 `ChatModel` 打桩，集成测试才连真实端点，且集成测试默认不打 CI，用 profile 隔离。
- 多模型路由通过具名 `ChatClient.Builder` 注册实现，子 Agent 与摘要压缩走便宜模型一路。
- 结构化输出统一开 `validateSchema()` 自纠错，因为国内供应商返回近似 JSON 的概率并不低；这条能力不支持流式路径，流式接口不返回结构化实体。
- 没有可用 API key 时，应用必须能启动并以 mock 模型跑通全链路，否则本地开发会被卡死。

### 按 API key 有无自动选择模型（2026-10-23 修订）

**"有没有 key"决定装不装真实模型，不需要改代码，也不需要 Maven profile 重新构建。**

原因是要绕开 Spring AI 自动配置的一个坑：`DeepSeekChatAutoConfiguration` 的条件是
`@ConditionalOnProperty(name = "spring.ai.model.chat", havingValue = "deepseek", matchIfMissing = true)`，
**跟 api key 无关**。只要 classpath 里有这个 starter，缺 key 时它照样激活，
并在构造 `DeepSeekChatModel` 时抛 "API key must be set"，应用根本起不来。
早先为此把依赖塞进 Maven profile `real-model`，代价是换模型要重新构建。

现在的做法：

- 依赖用纯模型包 `spring-ai-deepseek`，**不用 starter**，厂商自动配置根本不参与竞争。
- `ModelConfiguration` 自己按 key 装配，条件是一个自定义 `SpringBootCondition`
  （`RealChatModelCondition`）。用自定义 Condition 而不是 `@ConditionalOnProperty`，
  是因为后者只能判断"等于某值"，判断不了"这个字符串非空"；
  写成 `@ConditionalOnProperty(name = "...api-key")` 时空字符串也算属性存在，条件照样通过。
- `claw4j.model.mock` 三态：留空 = 有 key 用真实、没 key 自动降级 mock；
  `true` = 强制 mock（CI 与本地演示）；`false` = 强制真实（无 key 时启动失败，便于发现配置漏了）。
- mock 兜底放在单独的自动配置类并用 `after` 排序，让 `@ConditionalOnMissingBean` 的判定稳定。
- 用了哪个模型必须在启动日志里写明，否则"到底连没连上真实模型"只能靠猜。

**一个必须显式设置的坑**：`DeepSeekChatModel` 的默认 options 里 model 是 `null`，
请求不带 model 时 DeepSeek 返回空内容且**不报错**。所以必须显式
`DeepSeekChatOptions.builder().model(...)`，否则表现为"调用成功但回答永远为空"。

### 模型路由落在 ChatModel 层（2026-10-23 修订）

原决策写的是"多模型路由通过具名 `ChatClient.Builder` 注册实现"。落地时改成了
**在 `ChatModel` 层做主备**：`ModelConfiguration` 按 key 给每家装一个具名 bean，
`RoutingModelConfiguration` 把它们排成主备并暴露一个带 `@Primary` 的 `RoutingChatModel`，
业务侧（`ChatClient`、`AgentPort`）按类型拿到的永远是这个路由。

**为什么改**：Advisor 链、工具调用这些只有一份，挂在 `ChatClient` 上。分流的实体若是
`ChatClient`，就等于把 Advisor 链复制两份 —— 鉴权与脱敏要各配一次、也各错一次。
而真正需要"换一家"的时刻是**模型调用失败那一刻**，那正好是 `ChatModel` 的职责边界。

**降级规则**：

- 同步：任何异常都换下一家；全挂了抛 `ModelRoutingException`，每一次失败挂在 `getSuppressed()` 上
  （只看最外层容易误以为主模型的问题就是全部原因）。
- 流式：只在**第一个块吐出去之前**才降级。吐过之后再转投备用模型，
  客户端会收到"半句主 + 全句备"的重复内容，在 IM 里比直接报错更难看。
- 降级条件刻意做得粗暴：任何异常都降级。代价是 400 类参数错误也会白跑一次备用模型，
  换来的是不必在此处解析两套完全不同的厂商异常体系。被限流打疼了再收窄成"只对超时与 5xx 降级"。

**切换与校验**：`claw4j.model.primary` 取 `deepseek` / `qwen`，另一家是备。
主模型那家没配 key 时**启动失败**而不是悄悄拿备的顶上——悄悄顶上的话，
"主模型其实一直没配好"这件事永远不会暴露。`primary` 拼错同样 fail fast 并列出可选值。

**通义这一侧的适配代价（要记账）**：通义没有 Spring AI 官方适配器，走它自己提供的
OpenAI 兼容端点（`/compatible-mode/v1`），因此用 `OpenAiChatModel`。它把
`openai-java-core` 与 `okhttp` 带进了依赖树 —— 不是我们想引第二套 HTTP 客户端，
而是厂商 SDK 自带；DeepSeek 一侧仍用 JDK HttpClient，两侧互不影响。另外两处显式偏离默认：

- `maxRetries(0)`：SDK 内部重试与路由降级叠加会把"换一家试试"拖成"在这一家上死等三轮"，
  超时预算也跟着翻倍。重试只留一层。
- `checkJacksonVersionCompatibility(false)`：openai-java 会校验 Jackson 版本，
  Spring Boot 4 带的是 Jackson 3，两边共存但版本号对不上，这项检查开着会在启动时报错。

**跨厂商降级必须换参数类型（一个跑真实链路才暴露的 bug）**：
各家 `ChatOptions` 互不兼容，厂商的 ChatModel 直接把 `prompt.getOptions()` 强转成自己那一型。
于是主模型是通义、备是 DeepSeek 时，降级会把 `OpenAiChatOptions` 递给 DeepSeek，
当场 `ClassCastException` —— 救命的那条路径反而必崩。
`RoutingChatModel` 因此在把 Prompt 交给某一家之前，先以这一家的默认参数为底、
把厂商无关的参数（温度、topP、maxTokens…）搬过去、model 一律用这一家的
（把主模型的 model 名带给备，等于让 DeepSeek 去跑 qwen-plus）。

**端到端怎么验的**：起一个假的 OpenAI 兼容端点（返回固定内容与 SSE 流），
把 `CLAW4J_MODEL_PRIMARY=qwen` 与两家 base-url 指过去，用 curl 打同步与流式两个接口。
再把主模型的 base-url 指到一个没人听的端口，验降级：请求落到 DeepSeek 那一路，
且请求体里的 model 是 `deepseek-chat` 而不是主模型的 `qwen-plus`。

**真实 key 闭环（2026-10-23）**：拿到真实通义 key 后补跑了三条真链路，
假端点阶段"没打过真请求"的记账到此销掉：

- `CLAW4J_MODEL_PRIMARY=qwen` 打真实 DashScope：同步与流式都正常返回，
  SSE 分片与假端点一致（一个字一个 `chunk` 事件，末尾 `done`）。
- 把主模型的 base-url 指到没人听的端口，降级落到真实 DeepSeek 并正常作答 ——
  跨厂商那次 `ChatOptions` 强转在真 SDK 上同样没有复现，第二节的参数转换确实生效。
- 两家的 key 都在 `.env`（已忽略），`CLAW4J_MODEL_PRIMARY` 一改即可换主。

**剩下的未验项**：降级触发条件目前是"任何异常"，真实场景下 4xx 也会白跑一次备用模型，
被限流打疼了再收窄成"只对超时与 5xx 降级"。供应商侧的限流与配额行为没压过。

### 真实闭环跑通后修掉的两个降级缺陷（2026-10-23 修订）

M1-07 用真实 key + 真库把两家各跑了一遍。**两个 bug 都只在真实链路上出现**——
假模型既不会真的失败，也不会真的需要工具，所以前面任何一张测试都拦不住它们。

**一、换供应商时工具会丢。** 上面那段"跨厂商降级必须换参数类型"只搬了温度、topP 这类标量，
而**工具回调不在标量里**。漏掉它的表现不是报错，是备模型看不到 `querySales`：
真实链路上的回答变成"我无法直接访问数据库，请确认口径"。没崩、没超时，只是再也查不到数，
主备降级因此只剩"还能说话"而不再是"还能干活"。
现在工具回调与工具上下文跟着一起搬（`RoutingChatModel#copyTools`），
`RoutingChatModelTest` 里钉着——断言落在"备模型收到的参数里有没有工具"，
而不是"它有没有回答"。

**二、模型内部的重试把降级拖成分钟级。** Spring AI 默认给 `DeepSeekChatModel` 套的重试模板是
10 次、2 秒起、×5 递增、上限 180 秒；上层还有路由在"换一家"，两层相乘的实测结果是
**等了 4 分 40 秒还没轮到备**，用户看到的是一直转圈——降级写出来等于没写。
现在模型内部只留短重试（`RetryUtils.SHORT_RETRY_TEMPLATE`：10 次 × 100ms，合计约 1 秒），
超时预算花在换供应商上而不是在同一家身上等；通义那侧 `maxRetries(0)` 是同一个理由。
同一条链路修完之后是 6.4 秒拿到答案。

**两家的真实表现**（同一句话"上周华东区成交额多少"，真库真数 128,400.00）：
两家都能跑通完整闭环（工具调用 → 查库 → 答出数字），主备降级后同样拿得到数。
但**备模型的回答质量不保证与主模型一致**：同一句话落到通义上时，它有过一次不调工具、
直接反问口径的表现。这不改决策——降级只在"主失败"时发生，不在"主答得不好"时发生，
但它意味着降级后的回答要能被看出"没拿到数"，而不是编一个像样的数字。

**真实调用怎么隔离**：`RealModelIT` 以 IT 结尾（surefire 默认不收），只在 `-Preal-model` 下跑，
主模型由 `CLAW4J_MODEL_PRIMARY` 决定，与运行时同一个开关。它不进 CI（ADR-0008）。
