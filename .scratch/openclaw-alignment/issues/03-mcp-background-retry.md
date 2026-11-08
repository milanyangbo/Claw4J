# 03: MCP 连不上不再让应用起不来——后台退避重试，工具按轮解析

**What to build:** 把"MCP server 连不上/没导出工具"从**启动失败**改成**后台退避重试**：
应用照常起、其它工具照常可用、恢复之后工具在**下一轮**自动回到清单里；
真正的**配置错误**（点名了不存在的工具、重名、server 名重复）仍然启动失败。

**Blocked by:** `02`（先在小得多的通道上把"可注入端口 + 可控调度器 + 状态"这套形状跑通）。

**Type:** task

**Demo:** 第三方 MCP server 升级重启 40 秒。这段时间里照样能问、`querySales` 与 `systemStatus` 照常工作，
只是那家的工具暂时看不见；40 秒后下一轮它就回来了，日志里有一行"第 2 次重试后恢复，工具 N 个"。
**进程一次都没退。**

## 背景：这条是偏差，不是选择

`docs/tools/mcp.md:61`（OpenClaw 原文）：

> When OpenClaw's built-in MCP client cannot start a server, it **retries with exponential backoff:
> 30 seconds, doubling up to 10 minutes**. … After a startup failure, **turns never wait for retries**:
> recovery runs in the background, and recovered tools become available **at the next turn boundary**.

`docs/tools/mcp.md:44-45`：

> keeps its **last successfully listed tools** and utilities in the session's model-facing catalog
> **while recovery runs in the background**

对照我们的行为：任一 server 连不上 = bean 创建失败 = 应用起不来。在 K8s 上是 CrashLoopBackOff，
**重试由编排层替我们做，代价是整个进程（含 HTTP 通道）一起下线**；而且日志里只有一行"MCP server X 连不上"，
看不出"正在第几次重试"。对端重启 40 秒，换来的是一次全站不可用。

## 现状（代码证据）

三处，缺一不可：

1. **建 bean 就连接、空清单就抛**：`McpToolProvider:41-80` 的构造函数里 `connection.listTools()`
   （`:48-55`），为空 → `IllegalStateException`；连不上更早——`McpConnection#open():167-184` 直接抛。
   它是单例 bean，于是"建的时候"＝"启动的时候"。
2. **工具清单是启动时的一份快照**：`AgentAutoConfiguration#toolCatalog:108-120` 把
   `collectToolCallbacks`（`:267-...`）的结果收成一个 `List<ToolCallback>`，
   `#chatClient:168` 用 `.defaultToolCallbacks(catalog.tools())` 一次定死。`ChatClient` 是单例 bean。
   所以**即使连接自己恢复了，模型也看不见**——这是本票的主要工作量，不是补一个 `catch`。
3. **重连只覆盖"会话丢了"**：`McpConnection#callTool:135-146` 只认
   `McpTransportSessionNotFoundException`，重开一次并重放。这条对（`ADR-0002`），但它管的是
   "server 还活着、session 过期"，管不了"server 当时根本起不来"。

顺带一条**已有的先例**：子 Agent 那条链传的是 `Supplier<List<ToolCallback>>`
（`SubagentRunner:89`），父这条链今天不是。按轮解析在子那边已经有形状可以照。

## 裁决一：把"配置错误"和"连不上"分开

这是本票唯一的分界线，分错了后面全错。判据是**重试一万次会不会变**：

| 情形 | 判 | 为什么 |
| --- | --- | --- |
| `tools` 里点名了 server 没导出的工具（`McpToolProvider:59-66`） | 启动失败 | yml 写错了，重试不会让名字出现 |
| 两个 server 导出同名工具（`:67-75`） | 启动失败 | 注册顺序问题，重试只会让"这次是谁"更随机 |
| server 名 / 命令为空 | 启动失败 | 配置不成立 |
| 传输建不起来、握手超时、`listTools` 请求失败 | 后台重试 | 对端在重启 / 网络抖动 |
| **连上了，但 `listTools()` 返回空清单**（`:48-55`） | **后台重试** | 见下 |

**空清单这一格是这次唯一的判断改动，要说透**：一个 server 刚起来、工具还没注册完，是一个真实存在的窗口
（`npx` 拉包后立刻列工具就很常见）；把它判成永久错误，等于把"对方启动慢"变成"我们永远起不来"。
OpenClaw 的判据是"能不能启动 server"（`mcp.md:61`），空清单说明它起来了、只是暂时没有工具——
按它的口径归可恢复，日志写"连上了但没导出工具，30 秒后重试"。

**这一条与 ADR-0002 的"启动期硬失败"正面冲突，ADR-0002 要加修订记录**（那篇已有先例：
顶部加一段"部分失效"横幅 + 正文不动）。不写这一条，下一个人读 ADR 会照着 fail-fast 改回去。

## 裁决二：慢的不能是轮次

重试跑在后台线程，**任何一轮都不许等它**。三个由它派生的要求：

- 当前不可用的 server **不贡献工具**（这是"轮次不等"的全部含义）。别给它造一个占位的空壳工具——
  模型会去调它，然后收到一个"这个能力暂时不可用"，那是把基础设施故障变成模型的措辞问题。
- 运行中掉线的 server **保留上一次成功列出的工具**（`mcp.md:44-45`）。调用它们时按普通工具失败
  处理（`tool_failed` 那条词表已有），而不是让工具从清单里消失——消失会让模型说"我没有这个能力"，
  而真实情况是"它刚断了一下"。
- 首次就没成功过的 server 没有"上一次清单"，于是它就是**不可见**，只留一行状态。

## 要做

### 1. 一个按 server 的状态表 + 后台重试

`McpConnection` 之上加一层（名字实做时定，比如 `McpServerRegistry` / `McpReconnectSupervisor`），
每个 server 一份：

```text
name, status(UP / DOWN), lastKnownTools(List<Tool>), attempt(int), nextRetryAt(Instant), lastError
```

- 退避：**30 秒起、每次翻倍、上限 10 分钟**（与 `02` 同一个数字，便于两处日志对得上）。
- 每个失败 `WARN`：server 名、`attempt`、原因一句、下次时间。
- 恢复 `INFO`：server 名、第几次重试后恢复、工具个数。
- `close()` 要能停掉 scheduler（`McpToolProvider#close` 已经在容器关闭时被调，挂在那里）。
  与 `02` 同一条：关不掉的线程会让 JVM 不退出。

### 2. 工具清单从快照改成按轮解析

判据只有一条：**注册表新增工具之后，不重启进程，下一轮拿到的清单里就有它。**

怎么接是实做决定（`defaultToolCallbacks` 换成每轮解析 / 把 `ToolCatalog` 换成 `Supplier` /
每轮 `copy().build()`），但要知道三个约束：

- **父子两条链共用这一份清单**（`SubagentAutoConfiguration:117-129` 调的是同一个
  `collectToolCallbacks`）。改一处不能只改父那条，否则子 Agent 会看不到恢复之后的工具。
- **`ToolIndex` 是同一个关切**：按 `ToolSearch` 的注释，工具循环每轮把当轮清单索引进 `ToolIndex`
  （按会话 id 分桶）。清单按轮解析之后索引应当自动跟上——**这一点要用一条用例确认，别只信注释**。
- **不要为了"每轮都连一次"把退避弄丢**：解析清单只读状态表，绝不在这里发起连接或握手。

### 3. 装配处的失败口径

`McpToolProvider` 构造期只保留"配置错误"那几条抛（清单里的 `tools` 点名、重名、空名字）；
连接类失败**不抛**，把 server 记成 `DOWN` 并起重试。启动日志里要有一份清单：
`MCP server 名称 → UP(工具 N 个) / DOWN(30 秒后重试)`。

## 验收判据（反例都要能红）

- [ ] **连不上不再让应用起不来**：假端点第一次握手抛、第二次成功 → 上下文起得来，
      清单里暂时没有它的工具，其他工具在。
- [ ] **恢复之后下一轮可见**：让假端点第二次成功列出工具，再取一次清单（模拟下一轮），
      断言那个工具在里面。反例：只把 `McpConnection` 改成会重连、清单仍是快照 → **这条红**
      （这正是最容易漏的一半，所以它必须断在装配层）。
- [ ] **退避递增**：可控 scheduler，断言 30s / 60s / 120s 三个点。反例：固定间隔 → 红。
- [ ] **掉线保留上次清单**：先成功列出、再让传输失败，断言工具仍在清单里。反例："DOWN 就整份撤掉" → 红。
- [ ] **配置错误仍然启动失败**：点名不导出的工具、重名、空清单三种，各一条；反例：把它们也改成重试 → 红。
- [ ] **空清单归可恢复**：连得上但工具为 0 → 上下文起得来、状态 `DOWN`、日志里有"没导出工具"。反例：判成永久 → 红。
- [ ] **`close()` 之后没有残留任务**：断言 scheduler 被关。反例：漏掉 → 红。
- [ ] ADR-0002 加修订记录；`docs/design/capability-layers.md` 与 `docs/capability-tests.md` 里
      "MCP 连不上就起不来"的表述同步（这两处今天写的就是旧口径）。
- [ ] `./scripts/verify.sh` 全绿、跳过 0、行覆盖率 ≥60%（capability 现 85.6%，加类不会掉到线下，
      但**新增的调度类必须有 Java 层用例**——`03` 这类后台线程最容易出现"零覆盖但不报错"）。

## 门禁余量

`claw4j-capability` 已是 10 个测试文件。`McpToolTest` 与 `McpServersConfigTest` 都已到 20 条 `@Test`（满），
所以本票**必须腾槽**。两个候选：

1. 把 `McpServersConfigTest`（配置解析）并进 `McpToolTest` 的一个分组——同一个关切
   （"这份 yml 说的事，我们读成了什么"），合并是主题合并，不是凑数；
2. 或者把状态机与退避的纯逻辑放到一个已有的 `capability` 文件里（如果实做时发现
   `McpToolTest` 里已有大半 setup 可复用）。

先试 2、不行再 1。**不要**去动别的模块的文件凑数。

## 不要做

- **不要引 Resilience4j / Spring Retry**。这是一段三十行的退避，引一个库进来要连带配置、指标、
  线程模型三件新东西，而我们要的只是"30 秒起、翻倍、封顶 10 分钟"。
- **不要重试"配置错误"**：那条会掩盖 yml 写错，让"我明明配了"变成"过一会儿就好了"。
- **不要把 MCP 的状态并进 `claw4j.tool.calls` 的指标**：工具有没有调过是另一件事，
  混在一起会让"这个部署有没有装 MCP"看不出来。
- **不要顺手把 `McpConnection#callTool` 的一次重放改成多次**：那是另一个判断（重放多次会重复产生副作用），
  与本票无关。
