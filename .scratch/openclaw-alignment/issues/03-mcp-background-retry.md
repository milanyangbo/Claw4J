# 03: MCP 连不上不再让应用起不来——后台退避重试，工具按轮解析

**What to build:** 把"MCP server 连不上/没导出工具"从**启动失败**改成**后台退避重试**：
应用照常起、其它工具照常可用、恢复之后工具在**下一轮**自动回到清单里；
真正的**配置错误**（点名了不存在的工具、重名、server 名重复）仍然启动失败。

**Blocked by:** `02`（先在小得多的通道上把"可注入端口 + 可控调度器 + 状态"这套形状跑通）。

**Type:** task

**Status:** resolved（2026-11-08）

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

- [x] **连不上不再让应用起不来**：`McpServersConfigTest#unreachableServerKeepsTheApplicationUp`
      （配一个不存在的命令 → 上下文 `hasNotFailed`、`McpToolProvider` 在、状态 DOWN、`lastError` 指名 server）、
      `McpServerRetryTest#unreachableServerDoesNotStopTheApplication`（坏端点 + 好端点并存 →
      好端点 4 个工具照常在、启动清单里有 `broken → DOWN(30 秒后重试)` 与 `calc → UP(工具 4 个)`）。
- [x] **恢复之后下一轮可见**，断在两个层次：
      `McpServerRetryTest#recoveredServerShowsItsToolsOnTheNextLook`（第一次连不上 → 重试成功 →
      `toolNames()` 立刻有那 4 个，日志"第 1 次重试后恢复，工具 4 个"）；
      `ChatPipelineTest#aToolThatAppearsLaterIsVisibleAtTheNextTurn`（装配层：同一个
      `ChatClient` 第一轮请求里 0 个工具，来源多出一个之后**第二轮请求**里就有它）。
- [x] **退避递增**：`McpServerRetryTest#backoffDoublesAndCaps` 用只记录不真等的 scheduler，
      断言 `30 / 60 / 120 / 240 / 480 / 600 / 600` 秒（封顶那两点也在，上限不真的封住是这条最容易漏的一半）。
- [x] **掉线保留上次清单**：`McpServerRetryTest#lostServerKeepsItsLastKnownTools`（先成功列出 4 个 →
      会话丢失且重开失败 → 状态 DOWN，但 4 个工具仍在清单里，日志写"保留上次成功列出的 4 个工具"）。
- [x] **配置错误仍然启动失败**：点名不导出的工具 `McpToolTest#unknownToolNameInOverridesFailsWhenTheProviderIsBuilt`、
      跨 server 重名 `#duplicateToolNamesAcrossServersFail`、命令为空
      `McpServersConfigTest#stdioWithoutCommandFailsOnTheSpot` / URL 空 `#httpWithoutUrlFailsOnTheSpot`。
      运行期才发现的配置错误另有一条：`McpServerRetryTest#misconfigurationFoundAtRecoveryStopsRetrying`。
- [x] **空清单归可恢复**：`McpToolTest#emptyToolListIsRecoverableRatherThanFatal`（工具为空、状态 DOWN、
      `lastError` 含"没导出"）。
- [x] **`close()` 之后没有残留任务**：`McpServerRetryTest#closeStopsTheRetryLoop`
      （`isShutdown` 为真、关掉之后排着的任务不再排出新的、可重复调用）。
- [x] ADR-0002 加修订记录（"`listTools()` 为空即 fail-fast"收窄成"配置错误才当场失败"）；
      `docs/design/capability-layers.md`、`docs/capability-tests.md` 同步。
      **多改了一处**：`docs/design/extension-points.md` 第二节也写着"清单为空即失败"，
      而 `capability-layers.md` 把读者指向那里（"失败姿势在 extension-points.md 的第二节"）——
      只改两处会留下一个主动误导的指针。
- [x] `./scripts/verify.sh` 全绿、跳过 0、行覆盖率 ≥60%（数字见 Comments）。

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

## Comments

- 2026-11-08 实现。新增三个类（`mcp` 下 `McpServerStatus` / `McpServerSnapshot`，
  以及 `McpToolProvider` 的重写），改 `McpToolAutoConfiguration`（两张登记表改成活视图）、
  `McpConnection`（重开也失败时上报）、`ToolCatalog`（从 record 改成活视图 + `ToolCallbackProvider`）、
  `AgentAutoConfiguration`（`.defaultToolCallbacks(catalog)` + 日志按结论去重）。
- **`ToolCatalog` 从快照改成活视图是这一票的主要工作量**，改法只有一处：让它实现
  `ToolCallbackProvider`，`chatClient` 那边把 `defaultToolCallbacks(catalog.tools())` 换成
  `defaultToolCallbacks(catalog)`。框架在**每次请求**时展开 provider
  （`DefaultChatClientUtils.toChatClientRequest` 里那个循环就写在请求构造里），
  于是"这一轮模型看得见哪些工具"每轮重新算一次。查过 `mergeToolCallbacks`：运行时那份**赢**，
  provider 展开出来的就是它，所以不需要动 `ChatClientAgentPort`（它仍然只拼请求，不碰工具）。
- **两张登记表也必须跟着活**，否则恢复回来的工具用不了：
  `ToolPermissionRegistry` 拍快照 → 新工具是"未登记"（拒绝，方向安全但现象是能力回来了调不动）；
  `ToolTierRegistry` 拍快照 → `ToolProfile#select` 查不到档位直接抛，**一个外部 server 的重启会把整轮对话打掉**。
  两处都改成指向 `McpToolProvider` 的方法引用。
- **掉线检测多写了一段**（票面没要求，但不写这条验收就是空的）：`McpConnection` 在
  "会话丢失 → 重开 → 重开也失败"时回调一次，provider 收到才记 DOWN 并开始退避。
  没有它，"UP → DOWN"这个迁移在代码里根本走不到（provider 不轮询活着的连接），
  "保留上次清单"那条用例也就成了永远绿的空断言。回调在连接锁**外**调用，避免与 provider 的锁交叉。
- **三处判断，都写进代码注释**：
  1. 运行期才发现的配置错误不能"启动失败"（那时进程已经在跑），改成 `MISCONFIGURED` + ERROR + 不再重试；
  2. 空清单归可恢复（server 刚起来、工具还没注册完是真实窗口）；
  3. 退避成功后清零——下一次再断仍然等 30 秒，而不是直接等到 10 分钟。
- **四处反例真跑了**（改完即还原，全是"只让对应那条红"）：
  1. `ToolCatalog.getToolCallbacks()` 改回缓存快照 → `ChatPipelineTest#aToolThatAppearsLaterIsVisibleAtTheNextTurn` 红；
  2. `backoff` 固定返回 30 秒 → `McpServerRetryTest#backoffDoublesAndCaps` 红；
  3. `unavailable` 里清空 `tools` / `callbacks` / `permissions` → `#lostServerKeepsItsLastKnownTools` 红；
  4. 空清单改成抛 `McpConfigException` → `McpToolTest#emptyToolListIsRecoverableRatherThanFatal` 报错。
- **一处测试上的妥协，写在这里不藏**：`ChatPipelineTest` 那条用的是"生产那条接法"
  （`defaultToolCallbacks(catalog)`），但它自己拼的 `ChatClient`——真正的接线那一行在
  `AgentAutoConfiguration#chatClient` 的 bean 体里，没有用例直接覆盖它（app 那个全上下文里
  没有能中途长出工具的来源）。所以它守的是机制，不是那一行；反例 1 证明了"机制退回快照会红"。
- **门禁余量怎么腾的**：`InProcessStdioMcpServer`（0 条用例的辅助类，只有 `McpToolTest` 用）
  并进 `McpToolTest` 当嵌套类，空出的文件位给 `McpServerRetryTest`——与票 01 把
  `PipedStdioClientTransport` 并进它是同一个做法。
- 没做：把自己暴露成 MCP server 那一侧（`McpExposure`）仍是装配期采样一次；
  恢复回来的工具不会出现在交出去的 server 里，要重启才生效。它不在本票范围（那是"当 server"，
  不是"接 server"），但**是同一类快照**，将来有人问"为什么外部客户端看不到刚恢复的工具"时从这里查。
