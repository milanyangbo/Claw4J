# P11 · 反向 MCP server：把自己的工具交出去

**Status:** ready-for-agent
**Blocked by:** None（P09 的工具清单已落地，是这张票的前置）

## 背景

ADR-0020 对这件的判据是一句话：**把 claw4j 配成一个 MCP server，Claude Code 能像用别的工具一样调我的业务工具。**

今天这一层只有单向。MCP 的两个装配类都在做"进"：`McpToolAutoConfiguration` 把发现的工具收进清单，
`ConfiguredMcpServersAutoConfiguration` 把配置里写的 server 连上；反方向的出口一个都没有。
而工具清单本身也不是一个可以被第二方消费的东西——它是 `AgentAutoConfiguration:116` 里的一个局部变量，
`chatClient` 在本方法内拼完就交给 `ChatClient`，出了这个方法就没人见过它。

`claw4j-example-mcp-server` 不能被当成这个出口的雏形。它的 pom 第一条注释写的是
「这份 pom 里**没有任何 io.github.claw4j 依赖**，这是刻意的」，工具是写死的 `DemoJavaTools`。
它回答的是"别人怎么用 Java 写一个 MCP server"，与本票要回答的"我们自己的工具怎么出去"是两件事。

## 必须先回答的一个结构问题

**交出去的是哪一份清单？**

- **重算一份**：MCP 侧自己从容器里收工具，按自己的规则决定暴露谁。代价是三处各自漂移的机会——
  P09 的档位筛选、`MeteredToolCallback` 那层包装（计量、预算、审计）、
  `ToolPermissionRegistry` 的鉴权，都在 `collectToolCallbacks` 那一条路径上；
  另起一条路径，等于这三件事各自要再做一遍，且没人会发现少做了一件。
- **同一份 bean**：把 `chatClient` 里那段提成 bean，模型侧与 MCP 侧吃同一份。

选后者。判据因此可以写成一句可断言的话：**外部客户端看到的工具，是模型能用的工具的子集**，
不是另算的一份。子集而不是相等，是因为有些工具对"零上下文的客户端"没有意义（见下面第一条）。

这里有一条实测出来的硬事实，决定了实现形态：`spring-ai-autoconfigure-mcp-server-common:2.0.2` 的
`ToolCallbackConverterAutoConfiguration` 会收集容器里的 `List<ToolCallback>` 与 `ToolCallbackProvider`，
把它们原样转成 MCP 工具。仓库里现在有**四个** `ToolCallbackProvider` bean
（`SystemStatusAutoConfiguration:43`、`SalesExampleAutoConfiguration:83`、`GeneratorAutoConfiguration:90`、
`RunPersistenceConfiguration:293`）。也就是说：只要引入 server 的 starter，那四个来源的工具会
**绕过档位、绕过包装、绕过鉴权**出现在客户端面前，与我们筛过的那一份同台。
所以本票不引 starter，自己装配 `McpSyncServer`（`mcp-core` 已随 `spring-ai-starter-mcp-client`
传递进 capability，agent 依赖 capability，类在编译 classpath 上；是否需要显式声明一条
`spring-ai-mcp` 由实现时按"不靠传递依赖"的偏好定）。

## 三个不替实现决定的点

1. **暴露规则怎么定**：显式名单（`claw4j.mcp.server.expose`，默认空）还是自动推导（按权限动作
   `read` 且不需要审批）。显式名单的好处是"交出去什么"是一次被人做过的决定，默认安全；
   代价是每个要暴露的工具都得写名字，插件工具默认不出现。自动推导的好处是新插件装上就有出口；
   代价是 `toolSearch` 这类元工具的 action 也是 `read`，会被推导进来，而它对一个
   "自己本来就有工具列表"的客户端毫无用处。两条都成立，实现时选定并写进票。
2. **外部客户端是谁**。MCP 路径不经过 Advisor 链，`AuthorizationAdvisor` 那两道门
   （鉴权、审批）根本不在这条路上。所以身份必须由暴露层自己给：`Principal.readOnly(...)` 是现成的，
   但表范围写死 `*` 就意味着外部客户端能读这个部署的所有表——比 IM 里的 `guest` 宽得多。
   要不要给一个 `claw4j.mcp.server.tables`（默认 `*`，可收窄）由实现定，但**必须能答出**
   "这个外部客户端能读哪些表、依据是什么"。
3. **传输与进程形态**。本地仓库里 `spring-ai-starter-mcp-server-webmvc` 只有 1.1.5 / 1.1.6，
   2.0.2 要联网拉，离线构建会直接红（spec 已记这一条）。所以这一版只有 stdio。
   stdio 的代价不是"连着就行"：**stdout 是协议通道**，而 Spring Boot 的横幅、启动日志、
   Tomcat 的日志默认都往 stdout 走——`claw4j-example-mcp-server` 的 `logback.xml` 与类注释
   把这件事记了一遍（第一次跑就是"客户端报连不上、server 侧一片正常"）。
   另一个代价是进程形态：`java -jar claw4j-app.jar` 起的是完整应用（Tomcat、通道、调度、库连接）。
   被当 MCP server 用时要不要把这些一起关掉、靠 profile 还是靠配置键，由实现定，但要让"这个进程是
   在当服务跑还是在给一个客户端当工具"从配置上看得出来。

## 验收判据

- [ ] **清单一致**：`listTools` 返回的名字集合等于配置里 expose 的集合，且其中每一个都在
      P09 筛过之后的清单里。反例必须能红：expose 写了一个被 deny 掉的名字，它不能出现。
- [ ] **档位跟着走**：`claw4j.tools.profile=messaging` 时 `run_script` / `draft_script` 不在 `listTools` 里，
      即使 expose 里写了。这一条把 P09 与 P11 钉在一起，不是"再过滤一次"而是"同一份清单"。
- [ ] **鉴权在暴露层自己这一道**：外部调用用的是暴露层给定的身份，`ToolPermission.grantedTo` 为假时
      返回一条明确的拒绝（`isError=true`），且**工具没有被执行**——用一个探针工具计数断言为 0。
      只有"返回了拒绝文本、工具照跑"是不能接受的。
- [ ] **审批不在这条路上**：`ToolPermission.needsApproval()` 的工具写进 expose 是启动失败并点名，
      不是"调用时返回等你批"。外部客户端没有人能点按钮，也没有 run 可以挂起，
      把它暴露出去等于给一条绕过审批的路径。
- [ ] **来历不明的工具不暴露**：expose 里的名字查不到权限登记（或查不到档位）时启动失败并点名，
      与 P09 的"未分档即失败"同构。理由一样：暴露出去之后没有第二道门。
- [ ] **默认关，且关着时行为不变**：`claw4j.mcp.server.enabled` 缺省 false，
      容器里没有 `McpSyncServer`，既有 527 条用例全过。开启但 expose 为空时启动失败
      （连上了却一件能力都没有，与 `claw4j-example-mcp-server` 那条"工具清单为空等于失败"同构）。
- [ ] **协议层真的通**：用 `claw4j-capability` 测试侧已有的管道 stdio 设施
      （`InProcessStdioMcpServer` / `PipedStdioClientTransport`）起一个真 `McpClient`，
      完成 initialize → `listTools` → `callTool` 一轮。断言落在 MCP 客户端视角，
      不是在服务端调了一下自己的方法。
- [ ] **stdout 干净**：stdio 形态的日志出口指向 stderr、横幅关闭。这一条**不做真进程端到端**
      （要真库与真模型 key，而 MCP 这条路本身不调模型）：以那一份 logback 配置与启动参数为准，
      并在 README 的启动命令里写出来。如实记，不当成"已验证"。
- [ ] `./scripts/verify.sh` 全绿，跳过 0，行覆盖率不低于 60%。
- [ ] **门禁余量**：core / agent / capability / app 都已 10 个测试文件（上限 10），
      **不能新增测试文件**。可用的是 capability 的 `McpToolTest`（11 条，可加到 20）
      与 app 的 `ApplicationAssemblyTest`（14 条，可加到 20）。

## 不做

- **不做 streamable HTTP / SSE 传输**：本地没有 2.0.2 的 webmvc starter，离线构建会红；
  真要 HTTP，鉴权、会话、并发三件事都要重做一遍，那是另一张票。
- 不做 MCP 的 resources / prompts / sampling / elicitation：这一版只交工具。
- 不做多客户端并发与会话隔离：stdio 天生一个客户端。
- **不做外部调用的审计落库**：审计行就是 step，而外部调用没有 run（P07 的口径）。
  只留一行结构化日志（客户端名、工具名、结局、耗时），并在文档里写明**这不是审计**——
  ADR-0023 那三条线不许混。同理，外部调用不扣 run 预算。
- 不做按客户端区分权限（哪个 Claude Code 与哪个 IDE 拿不同的表范围），不做插件分发。

## Comments

- 现状核对（2026-11-03，逐个打开看过，不是凭印象）：
  - 工具清单在 `AgentAutoConfiguration:116` 是局部变量，`chatClient` 之外没有消费者；
    `collectToolCallbacks` 是 `public static`，委派层已经在用，但它要 `ObjectProvider` 与 `ToolProfile`，
    不是"拿起来就能用"的东西。
  - 四个 `ToolCallbackProvider` bean 的位置见上文；`ToolCallbackConverterAutoConfiguration` 的条件是
    `spring.ai.mcp.server.type`（sync / async，`matchIfMissing=true`），收集的是
    `ObjectProvider<List<ToolCallback>>` 与 `ToolCallbackProvider`——单个 `ToolCallback` bean 不在其中。
  - `spring.ai.mcp.server.stdio` 的默认值是 **false**，`spring.ai.mcp.server.enabled` 默认 **true**。
    只引依赖不改配置，得到的是"server 关掉 stdio、又没有别的传输"，不是"什么都没发生"。
  - `McpSchema.Tool.Builder` 有 `inputSchema(McpJsonMapper, String)`，直接吃
    `ToolDefinition#inputSchema()` 那串 JSON Schema，不用自己解析。
  - `McpSyncServerExchange#getClientInfo()` 存在，客户端名能在调用时拿到——日志与身份都用得上。
  - `mcp-core` 的 `mcp-spring-webmvc` 在本地只有 1.1.x（与 spec 记的一致）。
- **门禁余量（硬约束，开工前先看）**：四个模块的测试文件数都到 10 了。
  加用例只能进未满 20 条的文件：capability 的 `McpToolTest`（11）、app 的
  `ApplicationAssemblyTest`（14）；agent 侧若需要，`ObservabilityTest`（16）与
  `ToolCallAuditTest`（13）还有位置，但本票尽量不碰 agent。
- 与 P09 的关系：这张票**不新增一条轴**，只是把已有的那份清单多交一个出口。
  任何"在 MCP 侧再筛一次档位"的实现都算走错——那意味着有两份清单，而两份就会漂。
- 与 P08 的关系：句柄在出口才解析（模型 key、MCP 的 env / headers）。工具**返回的内容**里
  不会有真值，因为工具本身拿不到真值；这一条不需要这张票额外做什么，但实现时不要顺手把
  配置原样回显给客户端。
- 拆票说明：M8 其余五张（P10、P12、P13、P14、P16）尚未拆，P10 仍被中文分词方案卡着，
  见 `.scratch/m8-durable/spec.md` 的 Comments。
