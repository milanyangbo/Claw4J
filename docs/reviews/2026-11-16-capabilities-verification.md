# README-adding-capabilities.md 八项能力实测（2026-11-16）

**怎么测的**：`./mvnw -DskipTests package` 出 fat jar，按 `docs/capability-tests.md` 的方式起实例
（`local` profile + 三个能力目录 + `.env` 里的真 key），用真模型（DeepSeek）提问，判据只认两条——
**回答内容对得上**，以及 **`claw4j.tool.calls{tool=…,outcome=…}` 计数涨了**。模型的自述不算证据
（见文末"模型会编"那一节，这次又抓到两次）。

共起了 8 个实例（A–H），每个只改一项配置，用来把"是配置的问题"与"是代码的问题"分开。

## 结论表

| # | 能力（README 的节） | 结果 | 判据 / 证据 |
| --- | --- | --- | --- |
| 1 | Java 工具 | **通** | `querySales/ok=1`，回答里有真实口径（`sum()/CANCELLED/半开区间`）与库里的种子值 99,999.00 |
| 2 | MCP server | **通（两种传输都验过）** | stdio：`demo`/`github`/`mail` 三个 `UP`，`demo_echo/ok=1`；http：`deepwiki → UP(工具 3 个)` |
| 3 | 脚本包 | **机制通，示例两条都不可直接用** | `summarize/ok=1`（摘要与文件内容一致）；`weather-forecast/error=1`（详见下） |
| 4 | Skill | **通** | `Skill/ok=1`，回答是 SKILL.md 正文里那句"先问按哪个口径"，不是编的 |
| 5 | 子 Agent | **没通** | 三次尝试 `Task` 计数 0/0/0、日志无派发记录；但 `allow=Task` 时清单恰好 1 个、其余 19 个被挡 → `Task` **在清单里**，卡在别处（详见下） |
| 6 | agent 人设 | **通** | 启动日志 `agent 是 sales…工具档位 MESSAGING（来自人设文件的 tier）`；问"跑脚本"被明确拒绝 |
| 7 | 知识库 | **默认配置起不来；关掉重排能用** | 默认（重排 `true`）启动失败；`--claw4j.rag.rerank.enabled=false` 后起来，检索带 `[1] [2]` 出处（详见下） |
| 8 | 通道 | **HTTP + 飞书通（各验一半）** | HTTP 通道承载了全部对话；飞书日志 `connected to wss://msg-frontier.feishu.cn` + `飞书通道就绪`；企微/钉钉/Slack 没凭据，未验 |

## 逐项说明

### 3. 脚本包：机制没问题，两个示例各有原因

- `summarize`：放进包目录的样本成功摘要（`summarize/ok=1`）。但**它只能读包目录内的相对路径**——
  沙箱把工作目录锁在包目录上，`SandboxPolicy.resolveInside` 明确**拒绝绝对路径和包外路径**。
  所以"给一个本地文件"这句话，实际用法是"把文件放进 `examples/scripts/summarize/`"。
  README 里那条示例没写这个限制 → 需要补一句。
- `weather-forecast`：进程起来了、参数也到了，退出码 1。手工复现的根因是本机到天气 API
  的 `SSL handshake timed out`——**是网络，不是代码**。它顺带证明了"失败会变成一条失败的工具结果
  并带退出码"，这条是对的。

### 5. 子 Agent：不是没注册，是调用没落到执行

排除掉的三条：

| 怀疑 | 怎么排除的 |
| --- | --- |
| 档位把它筛掉了 | `--claw4j.tools.profile=coding` 时被挡的 7 个里没有 `Task` |
| 权限被拒 | `Task/{ok,error,denied}` 三个计数全是 0——拒绝也会计数，这里连拒绝都没有 |
| 工具没注册 | `--claw4j.tools.allow=Task` 起实例：`清单 1 个，挡掉 19 个`，挡掉的名单里没有 `Task` → 它在清单里，而且是唯一剩下的那个 |

剩下的现象：模型每次都回"Task 调用返回为空"，而**框架侧零计数、零日志**。也就是说模型那句话
没变成一次真正执行的工具调用。两个可能，需要一次带 `--logging.level.org.springframework.ai=DEBUG`
的复跑（或一个组装级用例）来分：

1. 模型只产出了文本、没产出 `tool_call`（DeepSeek 的 thinking 模式）；
2. 产出了 `tool_call`，但在 Spring AI 的解析/派发那一步被丢掉（工具名或参数 schema 对不上）。

顺带记一笔：`SubagentDelegationTest` 是绿的，说明"派发本身"在单测层面成立——缺的是**对话路径**的验收。

### 7. 知识库：真 bug，且测试挡住了它

默认配置（`rag.enabled=true` + 重排默认开）**起不来**：

```
Parameter 3 of method knowledgeReranker in io.github.claw4j.agent.rag.RagAutoConfiguration
required a bean of type 'com.fasterxml.jackson.databind.ObjectMapper' that could not be found.
```

`RagAutoConfiguration#knowledgeReranker` 注入 `com.fasterxml.jackson.databind.ObjectMapper`（Jackson 2），
而 Spring Boot 4 不再提供这个 bean（默认那套是 Jackson 3 的 `tools.jackson`）。**测试是绿的**，因为
`KnowledgeRetrievalTest:556` 直接 `new ObjectMapper()` 自己造一个——正好绕过了装配。

关掉重排就正常（`RagAutoConfiguration` 该 bean 被条件挡住），检索、融合、出处注入都工作：
问"东西买了能退吗"，回答带 `[1]`（after-sales.md）与 `[2]`（pricing.md）。**重排默认是开的，所以用户
按文档配一个目录就 100% 起不来。**

次要一条：语义切块时一次性送 17 句向量，被 Qwen 拒了——
`batch size is invalid, it should not be larger than 10`，降级成"按转折词切"（WARN）。
不致命，但意味着语义切块在长文档上静默退化。

## 顺带发现：我自己写的那份 README 有一句是错的

`README-adding-capabilities.md` 第一节的验收提示写着"启动日志里那行
`工具档位 messaging：清单 N 个，挡掉 M 个` —— N 应该比之前多 1"。

实测：**这行只在真有工具被挡掉时才打印**（`AgentAutoConfiguration#report` 里
`if (selection.droppedAnything())`）。默认 `full` 档什么都不挡，于是**刚加一个工具时这行根本不出现**。
正确判据是 `claw4j.tool.calls{tool=<你的工具>}` 计数（`docs/capability-tests.md` 就是这条口径）。

## 模型会编：两次实例

- 第一轮 `querySales` 明明被调用了（计数 1），第二轮它却说"那个 99,999 的数字是我编的"。
- `summarize` / `Task` 三次"调用失败、返回为空"里，有几次**框架侧根本没收到调用**。

所以这份报告一律以计数与日志为准。这一点值得写进文档：**判据只能看计数，不能看模型怎么说**。

## 建议（三条，各是一张票的量）

1. **修知识库启动失败**：`knowledgeReranker` 不要注入 Jackson 2 的 `ObjectMapper`，要么改用
   Spring AI 的 JSON 工具，要么自己 `new ObjectMapper()`；同时补一条**装配级**用例
   （`rag.enabled=true` 起容器），否则这个 bug 还能再活一次。
2. **定位子 Agent 的对话路径**：先加 Spring AI 的 DEBUG 日志跑一次，分清"模型没发 tool_call"
   还是"发了没被派发"；结论落到 `docs/capability-tests.md` 第 6 节。
3. **改文档三处**：`工具档位` 那行日志的触发条件（见上）、脚本包只能读包内路径、
   以及"判据只看计数"。

另外两条不影响结论、但值得记账：语义切块批量超限（>10 句必降级）；飞书只验到"连上"，
没验"收一条真消息 → 回推"。
