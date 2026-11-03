# P11 · 反向 MCP server：把自己的工具交出去

**Status:** done（2026-11-03 实现，`f3dba26`）
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

- [x] **清单一致**：`listTools` 返回的名字就是 `claw4j.mcp.server.expose` 点名的那些，一个不多一个不少；
      名字写错或已被档位挡掉的不出现。证据：`McpToolTest#exposedToolsReachTheClient`（只有 `peek`）、
      `#unknownNameIsSkipped`（点 `ghost` 只交 `peek`）
- [x] **档位跟着走**：MCP 侧没有第二道档位判断——它消费的就是 P09 筛完的那份 `ToolCatalog`。
      证据：`ApplicationAssemblyTest#messagingManifestCannotExposeCodingTools`（messaging 清单里没有
      `run_script`，expose 写了也交不出去）、`#theToolManifestIsOneBeanForBothExits`（清单是一个 bean，
      两个出口吃同一份）。**没有另造一条"切到 messaging 再看 listTools"的端到端**：MCP 侧没有任何
      档位代码可测，那样做只是把 P09 的用例再抄一遍
- [x] **鉴权在暴露层自己这一道**：`#narrowTablesRefuseTheCallAndTheToolNeverRuns`——客户端只能读
      `region`、工具要读 `sales_order`，结果是 `isError` 且探针计数为 0（拒绝落在执行之前）
- [x] **审批不在这条路上**：`#approvalToolIsRefusedAtAssembly`，启动期失败并点名
- [x] **来历不明的工具不暴露**：`#unregisteredToolIsRefusedAtAssembly`（没登记权限）、
      `#nonReadToolIsRefusedAtAssembly`（动作不是 read）。两条都是启动期失败，不是调用时拒绝
- [x] **默认关，且关着时行为不变**：`ApplicationAssemblyTest#mcpServerIsOffUnlessConfigured`
      （容器里没有 `McpSyncServer`）；开着但 expose 为空 → `#emptyExposureFailsAtAssembly`
- [x] **协议层真的通**：`#exposedToolsReachTheClient` / `#clientCallGoesThroughTheExposedTool` 起的是
      真 `McpSyncServer` + 真 `McpSyncClient`，走 `PipedStdioClientTransport` 的管道与真 JSON-RPC 帧，
      完成 initialize → listTools → callTool；`#toolFailureBecomesAnErrorResult` 钉住工具报错的形状
- [x] **stdout 干净（只做到配置层面）**：`application-mcp-stdio.yml`（横幅关、`logging.config` 指向
      `logback-mcp-stdio.xml`、容器不起、三个通道与调度关）+ `logback-mcp-stdio.xml`（根出口 stderr）；
      README 的启动命令就是这两份的用法。**没有起过一次真 jar 去连客户端**，见 Comments 的如实交代
- [x] `./scripts/verify.sh` 全绿：**539 条用例、失败 0、跳过 0**，core 88.8% / capability 85.6% /
      agent 61.8%，均 ≥60%
- [x] **门禁余量**：没有新增测试文件（四个模块仍是 10 个）；用例进 `McpToolTest`（11 → 20）与
      `ApplicationAssemblyTest`（14 → 17）

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
- 2026-11-03 实现完成。三个留给实现的点选了这些：
  - **暴露规则用显式名单**（`claw4j.mcp.server.expose`，默认空）。按动作自动推导的话，
    `toolSearch` 这类元工具的 action 也是 `read`，会被推导进来——而它对一个"自己本来就有工具列表"
    的客户端毫无用处。显式的代价是插件工具默认不出现，但那本来就是"交出去什么要有人做决定"。
  - **身份与 `Principal.readOnly` 同形**：`mcp:<客户端自报名>`、角色 `mcp-client`、动作只有 `read`，
    表范围可配 `claw4j.mcp.server.tables`（默认 `*`）。它不查 IM 的角色表：客户端没有 open_id / UserID。
  - **进程形态用 profile**（`mcp-stdio`），不逐个写配置键：横幅关、日志走 stderr、
    `web-application-type: none`、三个通道与调度关。这样"当工具用"与"当服务用"的差异集中在一个文件里。
- 落点：暴露层在 `claw4j-capability`（`mcp/expose` 下 `McpExposure` 纯函数 +
  `McpServerExposureAutoConfiguration` 装配），清单 bean 在 `claw4j-agent`；
  `ToolCatalog` 这个记录放在 capability 的 `tool` 包，因为 agent 依赖 capability、
  而 core 不能拿 Spring AI 的类型。
- **"不引 starter"落到了代码**：`McpServerExposureAutoConfiguration` 自己
  `McpServer.sync(transport).tools(specs).build()`，工具来源只有 `ToolCatalog` 一处。
  核对过 2.1.0 的 SDK：`Tool.Builder#inputSchema(McpJsonMapper, String)` 直接吃工具定义里那串 schema；
  `CallToolResult` 在 2.1.0 上只有 builder（`addTextContent` + `isError`），0.14 那种
  `(String, Boolean)` 构造器已经没有了——按 0.14 的签名写会编不过（这一点是实测的，不是查文档）。
- 两处如实交代：
  1. **没有起过真 jar 去连客户端**。判据 8 只做到配置层面（那两份文件 + README 的命令）。
     真进程要真库与真模型 key，而 MCP 这条路本身不调模型——这个验证留给第一次真用，不在这里假装做过。
  2. **`mcp-stdio` 这个 profile 没有进自动化**。`@SpringBootTest` 加载它会去读测试进程的 stdin；
     `web-application-type: none` 也没有任何用例跑过。它的第一次真实验证就是用户第一次起它。
- 写用例时纠正过一个自己的误判：**检索工具不在 `ToolCatalog` 里**。P09 用例里那个
  `"toolSearchTool"` 是桩；真实实现里它是 `ToolSearch.toolLoop` 内部注入的那一个，
  不是容器里的 `ToolCallback` bean。这一点写进了 `theToolManifestIsOneBeanForBothExits` 的注释——
  下次有人想"把检索工具也交出去"，会先看到这句话。
- 与 P09 的关系：这张票没有新增一条轴，只在既有清单上多开一个出口。
  `messagingManifestCannotExposeCodingTools` 就是"没有第二份清单"的判据。
