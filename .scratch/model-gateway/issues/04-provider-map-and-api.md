# 票 04：provider 改成 id 键的 map，加 api 与 auth

状态：**完成**（2026-11-22）。裁决：`docs/adr/0035-model-gateway-module.md` 第二节；修订 ADR-0031。

## 一、目标

现在「一家供应商」既是配置里的一行、又是 Java 里一个 bean（`ADAPTERS` 写死 deepseek/qwen），加一家要改代码。
改成 OpenClaw 的形状：provider 以 **id 为键**，用 `api` 字段选适配器，加一家只改配置。

## 二、范围

| # | 改什么 | 要点 |
| --- | --- | --- |
| 1 | `ModelProperties.providers`：`List<Provider>` → `Map<String, Provider>` | `Provider.name` 字段删除；重名校验随结构消失 |
| 2 | 新增 `api`，取值只认 `deepseek` / `openai-completions` | 未知值启动失败并列出可选；**这是选 Spring AI 适配器的键，不对齐 OpenClaw 的 12 种协议** |
| 3 | 新增 `auth: none` | 显式声明无凭据端点；`requireConfigured` 改成"缺 key 且没写 `auth: none` 才失败"；不做地址自动探测 |
| 4 | `api-keys: []`（复数） | 本票**只解析与校验，只用第一把**；轮换记缺口 |
| 5 | 删 `DeepSeekModelCondition` / `QwenModelCondition` 与 `ADAPTERS` | 装配改成"遍历 map，按 `api` 建"，协议→bean 一张小表 |
| 6 | 日志与报错改口径 | "配了哪几家" 取 `keySet()`；错误信息里用 `provider/model` 引用 |
| 7 | `application.yml` 与 `application-local.yml.example` 同步 | 旧形状启动即抛并指出怎么改 |

## 三、对照（OpenClaw）

| 它的形态 | 位置 |
| --- | --- |
| `models.providers.<id>` 是 map，字段 `baseUrl` / `api` / `auth` / `models[]` | `docs/concepts/model-providers/custom-providers.md:36-52` |
| `auth: "none"` 表示不发 `Authorization`，仅对 `openai-completions` 生效 | 同上 `:44-56` |
| 它自己的 DeepSeek 也声明 `api: "openai-completions"` | `extensions/deepseek/openclaw.plugin.json:31-34` |

## TDD 用例清单

| 判什么行为 | 用例名 | 先红的样子 |
| --- | --- | --- |
| map 形状能绑上，id 就是键 | `ModelProviderShapeTest#providersBindById` | 仍按 list 绑 |
| 未知 `api` 启动失败并列出可选值 | `ModelProviderShapeTest#unknownApiFailsAtStartup` | 静默跳过这家 |
| `auth: none` 时没有 key 也能起 | `ModelProviderShapeTest#authNoneSkipsCredential` | 仍报"没有配置任何 API key" |
| 没写 `auth: none` 且没 key 仍启动失败 | `ModelProviderShapeTest#missingKeyStillFailsWithoutAuthNone` | 起得来 |
| 加一家只改配置就能进候选链 | `ModelProviderShapeTest#newProviderNeedsNoJavaChange` | 报"没有对应的适配器" |
| 旧形状（list）启动即抛并给出改法 | `ModelProviderShapeTest#legacyListShapeIsRejected` | 兼容分支存在 |

## 缺口

- 多把钥匙的轮换（只在限流类失败时换、每家最多 1 次）不在本票：需要冷却先落地（票 07），另开票。
- `headers`、`apiKey` 的确切键名与 `models.mode: replace` 不做——我们没有 provider 插件与目录合并。

## 实施记录

- `ModelProperties.providers` 改成 `Map<String, Provider>`；键就是 id，`validateShape()` 负责
  归一大小写、拒空键、拒**数字键**（旧列表写法 `providers[0]` 会绑成键 `"0"`，那里直接给出改法）。
- `api` 只认 `deepseek` / `openai-completions`，未知值启动失败并列出两者。
- `api-keys` 是列表，本票只用第一把（`apiKey()` 取 `[0]`）；轮换仍记缺口。
- 装配改成 `ModelConfiguration#providerChatModels` 一个 `@Bean`，遍历表按 `api` 建，
  返回 `ProviderModels`（id → ChatModel）。`ADAPTERS`、`DeepSeekModelCondition`、
  `RealChatModelCondition` 全部删除；`QwenModelCondition` 留下（向量模型与语义缓存还靠它判），
  改成直接读这张 map。
- **`auth: none` 有一处偏差**：Spring AI 的 `OpenAiChatModel` 走 `openai-java` 的 `ClientOptions`，
  它的 `build()` 要求"至少一个凭据来源"，而"自己实现认证器"的入口是 `@JvmSynthetic`
  （Java 看不见，`javac` 报找不到方法）。所以 `auth: none` 实际发的是空值
  `Authorization: Bearer`，不是"不发这个头"。不校验头部的端点（Ollama / vLLM / 内网网关）照常工作；
  严格语义要等适配器给口子。写进 ADR-0035 的偏差。
- 一条行为变化：**配进表里就必须可用**。以前某家没 key 会静默跳过，现在是启动失败并点名
  （不想用就删掉那一行）。这一条由 `ModelProviderShapeTest#missingKeyStillFailsWithoutAuthNone` 钉着。
- `primary` 必须在表里，否则绑定后就抛（键改了、primary 忘了改的那种错）。
- 同步改动：`application.yml`（新形状）、`CONTRIBUTING.md` 的句柄示例、rag 那段"复用 name=qwen"的注释。
- 旧的 `ModelAndToolSelectionTest#providerTableBindsByName` 随列表形状下线，内容搬成
  `ModelProviderShapeTest`（六条）；`.scratch/config-regroup/issues/03` 的引用同步改了名。
