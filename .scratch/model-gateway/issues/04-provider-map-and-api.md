# 票 04：provider 改成 id 键的 map，加 api 与 auth

状态：**待做**。裁决：`docs/adr/0035-model-gateway-module.md` 第二节；修订 ADR-0031。

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
