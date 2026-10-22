# 模型供应商：DeepSeek 为主，通义走 OpenAI 兼容端点，单测全部 mock

---
status: accepted
date: 2026-10-22
---

真实调用接两家国内供应商：DeepSeek 用 Spring AI 2.x 的一等公民适配器，通义（Qwen）走 OpenAI 兼容端点配置，两者在同一套多模型路由下按用途分流。所有单元测试与 CI 一律 mock 模型调用，不烧 token、不依赖网络。

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
