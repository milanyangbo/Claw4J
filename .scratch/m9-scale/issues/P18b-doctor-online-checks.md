# P18b: `doctor --online`——真正出去一次，验 key 与外部 server

**What to build:** P18a 把"不用出去就能查"的项查完了，剩下两项只能真的发一次请求：
**模型 key 到底能不能用**、**外部 MCP server 到底连不连得上**。

这一票的关键不是这两项怎么做，是**什么时候做**：它们**默认不跑**，必须显式加 `--online`
（或 `claw4j.doctor.online=true`）。理由三条，任何一条都够：

- 会**花钱**：一次最小请求也是钱，接进部署脚本里天天跑就是账单。
- 会**因为网络抖动而红**：doctor 红了要有人看，假红三次之后就没人看了。
- 会**打到别人的 server**：`claw4j.mcp.servers.*` 里可能有第三方端点，随手探测等于给人家发流量。

两项的具体做法：

**① 模型 key 有效性。** 发一次最小的、最便宜的请求（`max_tokens` 拉到 1，一句话），
把结果分成四类而不是"成功/失败"：`401/403` → key 无效；`404` → 模型名不对；
`429` → 限流；连不上 → 网络/DNS。**分类不清的 doctor 等于没做**：都报"请求失败"的话，
运维会去换 key，而实际是模型名写错了。

**② 外部 MCP server 连通。** 对 `claw4j.mcp.servers.*` 逐个握手 + 列工具。要专门报两种
"看起来通了、其实没用"的情况：

- **连上了但 0 个工具**：`McpToolAutoConfiguration` 的注释里写着这种不算配置错误（记 DOWN 等重试），
  但对 doctor 来说它必须是一条 FAIL 或至少 WARN —— 用户配了一个 server，结果一个工具都拿不到，
  这正是他要查的事。
- **连上了但 `toolFilter` 把工具全过滤掉了**：配置合法、server 正常，清单却是空的。这一条
  只有把"过滤前/过滤后"两个数都报出来才看得见。

**Blocked by:** P18a（报告格式、退出码、`--doctor` 入口都在那张票里）。

**Type:** task

**Status:** ready-for-agent

**裁决来源：** ADR-0020 §M9 · P18 里的"检查 key 有效性、外部 server 是否连得上"两项。
"默认不跑"是本票的判断，理由见上；ADR 只说查什么，没说什么时候查。

## 要动的地方（预计）

| 位置 | 改什么 |
| --- | --- |
| `SecretHandleCheck` 之后新 `ModelKeyCheck` | 发一次最小请求；按状态码分类；**不打印 key 本身**（只打句柄名） |
| 新 `McpConnectivityCheck` | 逐 server 握手 + `toolNames()`；同时报过滤前/过滤后两个数 |
| `DoctorApplication` | `--online` 开关；未加时这两项输出 SKIP 并写明"加 --online 才查" |
| 超时与重试 | 每项一个明确的超时（默认 5 秒），**不重试**：doctor 不是探针，它要的是"现在这一次通不通" |

## TDD 用例清单

| 判什么行为 | 用例名 | 先红的样子 |
| --- | --- | --- |
| 不加 `--online` 时这两项是 SKIP，且报告里写明怎么打开 | `DoctorOnlineChecksTest#skippedWithoutFlag` | 无 |
| `401` 报"key 无效"，不是"请求失败" | `#unauthorizedSaysKeyIsInvalid` | 无 |
| `404` 报"模型名不对"，不是"key 无效" | `#notFoundBlamesTheModelName` | 无 |
| `429` 报"限流"，并说"过一会儿再试，不是配错" | `#rateLimitedIsNotAConfigError` | 无 |
| 连不上报网络/DNS，带 host 与超时值 | `#unreachableReportsHostAndTimeout` | 无 |
| MCP 连上了但 0 个工具 → 不算 PASS | `#serverWithZeroToolsIsNotAPass` | 现在启动时只记 DOWN，用户看不到 |
| MCP 工具被 `toolFilter` 全过滤 → 报出"过滤前 5 个、过滤后 0 个" | `#allToolsFilteredOutIsVisible` | 这种在运行期完全静默 |
| 报告里不出现 key 明文 | `#reportNeverContainsTheKey` | 无；这条防的是排障时顺手把密钥打进日志 |

真请求的用例同样按 `real-cli` / `real-mcp` 的老规矩处理：默认不进回归（`verify.sh` 要求跳过为 0，
也不许 `assume`），上面这些用例全部用**假 provider / 假 HttpServer** 做，断言的是"分类与文案"，
不是"真的连得上"。真连一次的验法写在验收判据里，手动跑。

**标记：整张表未复核（票还没开始）。** 收票时逐行改成 `✅` / `❌` / `⏳`，规则见 `AGENTS.md` 第 2 条。

## 验收判据

| 判什么 | 判据 |
| --- | --- |
| key 真的能用 | `--doctor --online` 对着一份好配置跑，模型那项 PASS |
| 错 key 说人话 | 换一个坏 key 再跑：报 `401`，文案里点名句柄名，不出现明文 |
| MCP 真连一次 | 用 `examples/mcp/demo_mcp_server.py` 起一个，doctor 报"3 个工具"，与手工 `tools/list` 一致 |
| 全过滤能看见 | 给那个 server 配 `toolFilter.include: ["不存在的"]`，doctor 报"过滤前 3、过滤后 0" |
| 默认安静 | 不加 `--online` 时，doctor 全程零出站（用 `netstat` 或断网跑一遍验证） |

## 缺口

- **查不出额度/余额**：只有 429 或 402 能间接反映，doctor 不为此多发请求。
- **多 provider 只查 primary**：`claw4j.model.primary` 指哪家就查哪家；其余家配了但没启用，
  查了也是白花钱。真要全查，加 `--online=all`，留给需要时再说。
- **MCP 的"工具能用"验不了**：doctor 只验"连得上、列得出"，不逐个真调。真调会改人家的状态，
  而且每个 server 的工具副作用不同，不能由 doctor 统一决定。
