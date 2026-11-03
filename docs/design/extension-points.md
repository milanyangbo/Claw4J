# 扩展点：接一个能力或一条通道，要写哪几个文件

> 设计说明：为什么这么做、否决了什么。要看结论去 [README](../../README.md)，要看决策去 [ADR](../adr/)。
> 讲"为什么要分三层"的是 [capability-layers.md](capability-layers.md)；这一份讲"我现在要接一个新的，按哪几步来"。

核心只有一句：**插件就是 Spring Boot 的自动配置**。一个 jar、一个
`META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` 文件，
里面写一行全限定类名，它就在启动时被装配。不需要父 POM 登记，不需要改 Claw4J 一行代码，
也不需要发明一个 `Claw4jPlugin` 接口——那层 SPI Spring 已经给了。

四种扩展点，各自的契约与参照实现：

| 扩展点 | 你要提供 | 依赖 | 参照实现 |
| --- | --- | --- | --- |
| Java 工具 | `ToolCallbackProvider` + `ToolPermissionRegistry` + `ToolTierRegistry` | `claw4j-core`、`spring-ai-client-chat` | [`claw4j-example-sales`](../../claw4j-example-sales) |
| MCP server | 一段配置（或一个 `McpServerEndpoint` bean）；server 本身可以是任何语言 | 宿主侧 `claw4j-capability`；server 侧什么都不要 | [`application-local.yml.example`](../../claw4j-app/src/main/resources/application-local.yml.example)、[`claw4j-example-mcp-server`](../../claw4j-example-mcp-server)、[`examples/mcp/`](../../examples/mcp) |
| 脚本包 | 一个目录（`package.properties` + 入口脚本） | 无（子进程 + 沙箱） | [`examples/scripts/`](../../examples/scripts) |
| Skill | 一个含 `SKILL.md` 的目录 | 无（纯文本） | [`examples/skills/weekly-sales`](../../examples/skills/weekly-sales) |
| Subagent | 一份 `*.md`（front matter + 正文） | 无（纯文本） | [`examples/subagents/capacity-triage.md`](../../examples/subagents/capacity-triage.md) |
| 通道 | 实现 `ChatChannel` 的 bean + 自己的端点 | `claw4j-core`（**不带任何 IM SDK**） | `claw4j-channel/http`、`claw4j-channel/feishu` |


---

## 一、Java 工具

### 三个文件

**1. 工具类**——一个普通对象，方法上 `@Tool`。不需要继承、不需要注册：

```java
public class JiraTools {
    @Tool(name = "jiraSearchIssues", description = "按 JQL 搜索 Jira 工单，只读")
    public String search(@ToolParam(description = "JQL 语句") String jql) { ... }
}
```

**2. 自动配置**——把它变成容器里的三个 bean：

```java
@AutoConfiguration
public class JiraToolsAutoConfiguration {

    @Bean
    public ToolCallbackProvider jiraTools(JiraClient jira) {
        return MethodToolCallbackProvider.builder().toolObjects(new JiraTools(jira)).build();
    }

    @Bean
    public ToolPermissionRegistry jiraPermissions() {
        return ToolPermissionRegistry.of(Map.of(
                "jiraSearchIssues", ToolPermission.read(),
                "jiraCreateIssue", ToolPermission.of("create_issue")));
    }

    // 档位：这些工具最低需要哪一档才出现（P09）。读工单不碰代码，所以是 messaging。
    @Bean
    public ToolTierRegistry jiraToolTiers() {
        return ToolTierRegistry.sameFor(
                List.of("jiraSearchIssues", "jiraCreateIssue"), ToolTier.MESSAGING);
    }
}
```

**3. 注册文件**——`src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`：

```text
com.acme.jira.JiraToolsAutoConfiguration
```

### 谁收这三个 bean

`AgentAutoConfiguration` 收工具用的是 `ObjectProvider<ToolCallbackProvider>`（连同单个
`ToolCallback` 一起），收权限用的是 `ObjectProvider<ToolPermissionRegistry>`，
收档位用的是 `ObjectProvider<ToolTierRegistry>`；鉴权与语义缓存两条链路各自按**列表**
消费权限表，清单组装处按**列表**消费档位表。所以**加一个来源只是加几个 bean**，
没有任何一处需要点名你的工具类。

### 权限是必填项，漏了就是拒绝

工具没登记权限时，鉴权一律按"没权限"处理（ADR-0007）：失败方向是拒绝，不是放行。
登记的是**这个工具会碰到什么**（表 + 动作），不是"谁能用它"——后者在角色的授权表里，
改那个不用发版。

**粒度到工具，不到参数。** 一个 Java 工具的权限写在它自己的 `@Bean` 里（一个工具一份声明）；
MCP 那边默认按 server 一份，`servers.<名字>.tools.<工具名>` 可以逐个覆盖。需要这一层的原因是
第三方 server 常把只读查询与写动作装在同一个进程里（`list_inbox` 与 `send_email`）：
只按 server 声明时，这类 server 只有"写动作也被只读身份调用"与"整条不可用"两个结局。
覆盖只影响被点名的工具；点名了 server 没导出的工具名，启动就失败。

### 档位也是必填项，漏了是**起不来**

这一条与上一条方向相反：权限漏了是"拒绝"（系统照跑，只是那个工具调不动），
档位漏了是**启动失败并点名是哪个工具**（P09）。

方向不同是有意的。权限漏了有一个安全的兜底（拒绝），而档位漏了没有：
按"出现"兜底等于让新工具默认拥有最宽的能力，按"不出现"兜底则会让一个刚加的工具
在所有部署里静默消失、没人知道少了什么。两种都不是能接受的默认，所以只剩"说出来"。

档位是累进的：`minimal < messaging < coding < full`。工具声明它**最低**需要哪一档，
部署用 `claw4j.tools.profile` 选一档，声明档不高于所选档的工具才进清单。
分档的口径只有一条：**这个工具会不会碰到代码、出网、碰别人的数据**——
只跟自己和对话有关的是 `minimal`，业务只读与消息类是 `messaging`，
跑脚本是 `coding`，外部 MCP 是 `full`。

两个整批归档的场合，因为名字要到运行时才知道：脚本包跟 `coding`，MCP 跟 `full`。
它们用 `ToolTierRegistry.sameFor(自己导出的名字, 档)`，**不要写成"任何名字都算"**——
那种实现会让"没登记档位就失败"这条校验永远通过。

部署侧还有两个收窄手段（`claw4j.tools.allow` / `deny`），它们**不能越过档位**：
`allow` 是白名单，只能把档位里的东西再筛少；想"messaging 档但要一个脚本"，
只能调高 `profile` 再 `deny` 别的。

### 加一个开关

示例插件给自己的键是 `claw4j.example.sales.enabled`（默认 true）。插件应该给得出
"怎么关掉我"的答案，否则它不是插件、是内置功能：

```java
@AutoConfiguration
@ConditionalOnProperty(prefix = "com.acme.jira", name = "enabled", havingValue = "true", matchIfMissing = true)
public class JiraToolsAutoConfiguration { ... }
```

### 已经踩过的三种失效

这三条不是风格问题，是**不报错**的失效，值得在写插件时逐条对照：

| 写法 | 会发生什么 | 为什么 |
| --- | --- | --- |
| `@ConditionalOnMissingBean(ToolCallbackProvider.class)` | 第二个来源进来时，第一个来源的工具**静默消失** | 那条条件的语义是"容器里已经有了就让位"，而容器里本来就该有多个来源。后果是模型手上没这个工具，然后开始编答案 |
| `@ConditionalOnMissingBean(ToolPermissionRegistry.class)` | 只有一张表被注册，另一方的工具**从此一律被拒** | 同上，只是症状换成了"某个工具忽然谁都用不了" |
| 自动配置里 `@ConditionalOnBean` 依赖同类 `@Bean` 方法的声明顺序 | 条件**永远为假**，整层工具不注册 | 同一个自动配置类内的条件按方法声明顺序求值；顺序反了就永远读不到那个 bean |

`claw4j-example-sales` 里这三个坑各有一句注释——那份代码是可以整份照抄的模板。

---

## 二、MCP server

MCP 这一层与语言无关：**server 是另一个进程（或另一个端点），和宿主之间只有 JSON-RPC**。
Python 能写，Java 也能写，写出来对宿主完全一样。

### 宿主侧：一段配置就够

接一个**别人写的** server 不需要写 Java——`claw4j.capability.mcp.servers` 下写一段：

```yaml
claw4j:
  capability:
    mcp:
      servers:
        deepwiki:                      # 走 HTTP：连别人已经跑着的端点
          transport: http
          url: https://mcp.deepwiki.com
          # endpoint 不填走 SDK 默认（http → /mcp，sse → /sse）
          # headers:
          #   Authorization: "Bearer secret:mcp_token"   # 头里写句柄，不写值（见下）
        everything:                    # 走 stdio：起一个子进程
          command: npx
          args: ["-y", "@modelcontextprotocol/server-everything"]
          inherit-env: [PATH, HOME]    # 没点名的变量子进程一个都看不到
          # env:                         # 要给它一份专用凭据时写这里
          #   MCP_TOKEN: secret:mcp_token
          #   HOME: plain:/tmp/isolated   # 非凭据常量用 plain: 显式标出来
          permission:
            action: read               # 默认就是 read；能写东西的 server 要显式写
```

**凭据是句柄，不是值**（P08）：`env` 与 `headers` 的值要么是 `secret:<名字>`
（`<名字>` 是环境变量名的小写写法，`secret:mcp_token` 对应 `MCP_TOKEN`），要么是
`plain:<文本>`（非凭据常量）。真值只在出口换出来——起子进程那一刻、或者每次写 http 头那一刻——
所以配置、日志、审计、模型上下文里从头到尾只有名字。裸写明文会在启动时被拒，报错点名到
`servers.<名字>.env.<键>` / `.headers.<键>`；写了句柄却没有对应环境变量，同样是启动失败。

键的名字与默认值在 `McpServerProperties` 里；`McpEndpoints` 负责把它翻成端点，那里也是
**配错就当场失败**的地方：stdio 没 `command`、http 没 `url`、`url` 不是 http(s)、
`inherit-env` 里有空项、凭据位裸写明文——每条失败消息都带 server 名，因为配置化之后一次会配好几个。

装配条件看的是**配置**（`ConfiguredMcpServersAutoConfiguration.ServersConfigured` 读 Environment），
不是容器里有没有某个 bean。写成 `@ConditionalOnBean` 会踩上面第三种失效：条件按声明顺序求值，
顺序一错整层静默消失。

### 什么时候仍然写 bean

值要**算出来**的时候：路径要拼、token 要从 vault 取、传输要包一层重试或代理。
配置写不出表达式，bean 可以——比如把一个 `java -jar` 的 server 接进来（`java` 的绝对路径按 `java.home` 算，
不依赖 `PATH`）：

```java
@Bean
McpServerEndpoint javaDemoMcp() {
    return ProcessStdioMcpEndpoint
            .of("java-demo",
                    Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    List.of("-jar", "claw4j-example-mcp-server/target/claw4j-example-mcp-server-0.1.0-SNAPSHOT.jar"),
                    Map.of())          // 环境默认为空：应用的模型 key 与数据库口令不会漏给子进程
            .endpoint(ToolPermission.read());   // 不带表 = 没有业务表权限
}
```

两条路都在 `McpToolAutoConfiguration` / `ConfiguredMcpServersAutoConfiguration` 里收，
各自出**一份** `McpToolProvider`：多份是设计上允许的，Agent 侧按 `ObjectProvider` 收全部。
每条连接在**启动时**建立、工具清单在启动时取回。于是：


- **清单为空即失败**（ADR-0002）：连接是通的、协议握手也成功，只是它一件能力都没有。
  那种状态必须报错，否则现场是"模型说它没这个能力"，而真正的原因是命令写错了。
- **会话丢失就重新初始化连接**，不对着一条已经不在的会话重试——那会让一次断连变成永久不可用。
- **命令是 argv 数组**，不经任何命令解释器（ADR-0002）。模型的入参走 JSON-RPC 帧，永远不进 argv。

### server 侧：两个能直接抄的样本

**Java**：`claw4j-example-mcp-server`。它只有一个 `main`：

```java
var transport = new StdioServerTransportProvider(McpJsonDefaults.getMapper(), System.in, System.out);
var specs = new SyncMcpToolProvider(List.of(new DemoJavaTools())).getToolSpecifications();
var server = McpServer.sync(transport).serverInfo("claw4j-java-demo", "1.0.0").tools(specs).build();
Runtime.getRuntime().addShutdownHook(new Thread(server::closeGracefully));
// 不用挂 CountDownLatch 阻塞 main：传输线程不是守护线程，main 返回后进程照样活着。
// 构建过程中实测过一次——加了 latch 与不加没有区别，于是删掉了它。
```

它踩过一次坑，值得所有人提前知道：**stdout 是协议通道**。SDK 自己的 INFO 日志默认也走 stdout，
于是第一帧 JSON 前面插进一行日志，客户端报 `Client failed to initialize`，
server 侧看起来一切正常。修法是把日志出口改到 stderr（模块里的 `logback.xml`）。

**Python**：`examples/mcp/demo_mcp_server.py`，零第三方依赖，包的是几个 shell 命令。
用 `python3` 起它，端点写法与上面那份 Java 的一模一样——只换 `command` 与 `args`。

---

## 三、Skill

Skill 不是动作，是**做法**：它不读库、不调接口，只告诉模型"这件事按什么顺序做、
什么算做完"。所以它与"能力"是两条正交的轴（GLOSSARY 里 Skill 与 Script Package 的分工）。

要接一份 Skill：在 `claw4j.skills.directory` 指向的目录下建一个子目录，放一个 `SKILL.md`：

```text
skills/
  weekly-sales/
    SKILL.md        # front matter: name / description；正文: 什么时候用、按什么流程、边界在哪
```

渐进式披露是这一层的全部价值：

| 时机 | 进上下文的是什么 |
| --- | --- |
| 启动 | 每个 Skill 的 `name` + `description`（几行 XML） |
| 命中 | 只有被选中那一份的正文，作为那次调用的结果回来一次 |

于是二十份做法的代价是二十行描述，不是二十份正文。它登记的动作是 `use_skill`、
**业务表一张都没有**——Skill 自己不读库，它让模型去读，而那一步照样过鉴权。

---

## 四、agent（人设）

这一节不是"再加一个能力"，而是**决定这个部署是谁**。同一个 jar，两次部署只改配置，
一个变销售助手、一个变运维助手——在此之前系统提示词是 Java 里的一段常量，
所以"换一个人格"等于改代码重新发版。

形状与子 Agent 同源：一份 markdown，front matter 是配置、正文是系统提示词。

```text
agents/
  sales.md              # 一个人设
  ops.md                # 另一个
  glossary/
    metrics.md          # 口径，被几份人设共同引用
```

```markdown
---
name: sales
description: 销售助手：成交额、区域对比、订单明细。只读，不跑脚本
tier: messaging
glossary:
  - glossary/metrics.md
---

你是销售侧的助手。别人问你卖得怎么样，你查库回答，不评价好坏，也不跑脚本。
```

```yaml
claw4j:
  agent:
    directory: agents
    default: sales
    zone: Asia/Shanghai
```

### `tier` 只覆盖档位

`tier` 覆盖全局 `claw4j.tools.profile`，但**不覆盖 `allow` / `deny`**。
前者是"这个助手允许多宽"（销售助手永远不该跑脚本，哪怕部署的全局档位是 `full`），
后者是"这套部署额外不要什么"，属于部署级的事实。让排除项跟着人设走的话，
"同一个助手换个部署就少了几个工具"，而那种问题查起来非常费劲。

P09 的"未分档就启动失败"在这里照旧生效：多了一层人设不等于放宽校验。

### 口径是引用，不是抄写

`glossary` 里写的是路径。**同一段口径抄进两份人设的后果是可预见的**：
改一处必漏一处，而漏掉的那处会让两个助手对同一个数字给出两种说法，还都自称"按口径算的"。

改 `glossary/metrics.md` 一行，所有引用它的 agent 下一轮就都变了——因为每轮拼提示词时才读。

### 时间是框架拼的，不要写进正文

正文里写"今天是 2026 年 11 月 3 日"是没有意义的：进程跑一周，那句还是启动那天。
框架每轮在提示词末尾拼一句"此刻是 ……（星期几，时区）"，时区取 `claw4j.agent.zone`。

**为什么时区要显式配**：容器里 `ZoneId.systemDefault()` 通常是 UTC。差八小时意味着
每天晚上八点之后，模型看到的"今天"已经是明天，而它会照常回答，没有任何异常。

### 三条失败姿势

| 情况 | 结果 |
| --- | --- |
| 目录配了但不存在 / 是空的 | 启动失败 |
| 目录里多份、没写 `claw4j.agent.default` | 启动失败，列出候选 |
| 点名的人设不存在 | 启动失败，列出候选 |

一条都不含糊：一个进程只有一个 agent，选错的表现是"这个部署怎么说话像销售"，
没有任何报错，也没有任何一行日志会主动提醒。

不配 `directory` 也能跑，用内置的最小可用人设，启动日志会说清这次用的是内置的还是配的。

---

## 五、通道

通道只做三件事：收进来 → 认出是谁 → 发出去。中间"怎么回答"不属于通道，
它调的是 `AgentPort`——那是 domain 里的一个接口，实现由 agent 模块提供，接线由 app 完成（ADR-0010）。

### 契约

```java
public interface AgentPort {
    OutboundMessage reply(InboundMessage message);
    void stream(InboundMessage message, Consumer<OutboundMessage> onChunk);   // 最后一次回调带 finalChunk
}
```

`finalChunk` 那一块是**唯一**的正常结束信号，实现必须保证它发生一次且仅一次——
哪怕模型一块都没吐出来。通道据此收尾，不需要另设超时去猜"是不是说完了"。

### 三条硬要求

写在 `ChatChannel` 的类注释里，因为它们全都不报错、只表现为"用户觉得机器人坏了"：

1. **拿到消息尽快 ACK**，不要在收消息的线程里同步等 Agent。飞书要求 3 秒内处理完事件，
   否则重推；而一次带工具的回答可能十几秒。正确形状是「立刻 ACK → 异步编排 → 主动回推」。
2. **用事件 id 做幂等**（`InboundMessage#eventId`）。重推不能让同一句话被处理两次。
3. **认不出人就拒绝**，不要把匿名请求放进来，也不要回落到某个默认角色。

### 这三条已经有人替你实现了

`claw4j-core` 的 `channel.kernel.InboundDispatcher` 做的就是这四件事：去重 → 认人 →
异步 → 分段回推，以及"失败也要回一句"。它只认两个接口，你的通道只要把平台事件压成五列即可：

```java
// 1. 平台事件 → 五个字段（这里用 Telegram 的形状）
record TelegramUpdate(String updateId, long chatId, long fromId, String text, Instant receivedAt)
        implements InboundEvent {
    public String eventId()        { return updateId; }        // 重试时不变的那个
    public String conversationId() { return String.valueOf(chatId); }
    public String senderId()       { return String.valueOf(fromId); }
}

// 2. 发一段文本（分段由编排层做，实现只管发）
ReplySender sender = (conversationId, text) -> telegram.sendMessage(conversationId, text);

// 3. 接起来。accept 返回就是 ACK，它不等模型
new InboundDispatcher(agentPort, executor, resolver, sender, "Telegram").accept(update);
```

为什么它能跨通道，而不是"飞书专用"：三个平台的回调在这五列上逐个对得上——
飞书 `event_id` / `chat_id` / `open_id`、Telegram `update_id` / `chat.id` / `from.id`、
Slack `event_id` / `channel` / `user`。差别只在参数（窗口 3 秒还是 7 秒、单条上限 4096 还是 40000），
不在结构。飞书自己走的就是这条路：`FeishuInboundEvent` 只是把这个接口的 `conversationId()`
映射到 `chat_id`，编排代码一行不分平台。

模块边界是可执行的：`claw4j-core/pom.xml` 的编译期依赖只有 `slf4j-api`（`domain` 在 ADR-0015
之后就在同一个 jar 里，不再是一个坐标），`CoreBoundaryTest` 用白名单守着
（只许依赖 JDK、日志、domain、自家代码）；POM 里想加 Spring 有 enforcer 在 `validate` 阶段拦。

### 装配

```java
@AutoConfiguration
@ConditionalOnProperty(prefix = "com.acme.channel.wecom", name = "enabled", havingValue = "true")
public class WecomChannelAutoConfiguration {

    @Bean(initMethod = "start", destroyMethod = "stop")
    @ConditionalOnMissingBean
    public WecomChannel wecomChannel(AgentPort agentPort) { ... }
}
```

再加一行 `AutoConfiguration.imports`，与工具插件一样。认证不归通道做：
它由平台或网关完成，通道只负责把结果翻译成 `Principal`。

### 今天的限制（如实写）

- **版本兼容口径还没定义**。插件靠自动配置被发现，但没有任何版本校验：插件编译时依赖的是旧版
  `claw4j-core`，核心接口一变就是运行期 `NoSuchMethodError`，而不是启动时一句
  "这个插件要求核心 ≥ x.y"。现在只能靠"插件与核心同时升级"这个约定。
- **只支持 `stdio` 与 `http`/`sse` 两种 MCP 传输**。社区里还有别的（比如基于消息队列的），
  接进来要改 `McpEndpoints` 的枚举——那是一个明确的改动点，不是"配一下就行"。

## 六、语言不是扩展点的维度

"用 Python 写一个 tool"这句话在这个仓库里没有直接对应物，因为 Spring AI 让模型**动手**的
官方通道只有两条：Java 方法（`@Tool` / `MethodToolCallbackProvider`，本项目再加一张
`ToolPermissionRegistry`）与 MCP（`SyncMcpToolCallbackProvider`，来自 `spring-ai-mcp`）。
非 Java 的代码要变成模型手上的能力，只有三种结局：

| 你手上的东西 | 变成什么 | 语言无关来自哪儿 | 官方还是本项目 |
| --- | --- | --- | --- |
| 会说 MCP 的程序 | 一个 MCP 端点（生成 `ToolCallback`） | **协议**：JSON-RPC 帧，与实现语言无关 | 官方（MCP SDK + `spring-ai-mcp`） |
| 命令行程序 | 一个脚本包工具 | **进程边界**：起得来、读得懂 stdout | 本项目接缝（`capability/script`） |
| 一份做法 | 一个 Skill | **文件格式**：`SKILL.md` | 本项目接缝（`capability/skill`） |
| 一个专职的人 | 一个 Subagent | **文件格式**：markdown + front matter | 本项目接缝（`agent/subagent`） |

四条路的取舍很实际：

- **要复用已有的 Python/Go 服务，就走 MCP。** 它有官方 SDK、有协议兜底、有会话与重连语义，
  而且对宿主是**唯一**一种"跨语言且跨进程"的接法。
- **只是一条命令，就走脚本包。** 代价是子进程只拿得到 `PATH`（`SandboxPolicy`），
  需要凭据的能力在这一层跑不通——那不是缺陷，是这一层故意的边界。
- **只是"怎么做"，就不要写成工具。** Skill 不提新能力，它只决定现有工具怎么用；
  把它写成工具等于让模型多一个需要检索的动作，收益为零。
- **要隔离上下文与预算，才用 Subagent。** 它的 `tools` 是白名单，是这一层唯一的安全边界。

反过来说：**不要为了"支持某语言"而加一层插件协议**。跨语言这件事 MCP 已经解决了，
再加一层只会得到"两个都不完整的协议"。

---

## 七、插件 jar 怎么进 classpath

### 两种装法

| 做法 | 动什么 | 代价 |
| --- | --- | --- |
| 加 Maven 依赖 | 宿主的 `pom.xml` 一行 | 重新构建、重新发版 |
| 外部目录 + `loader.path` | 什么都不改，只改启动命令 | 启动时扫一次，**不是热加载** |

```bash
java -cp claw4j-app-0.1.0-SNAPSHOT.jar \
     -Dloader.path=/opt/claw4j/plugins \
     org.springframework.boot.loader.launch.PropertiesLauncher
```

### 实测记录（三组对照，缺一不可）

插件 jar 的内容就是"工具类 + 自动配置 + `imports` 文件"，工具名取 `pluginPing`
（与仓库里任何工具都不同，于是"它出现了"是可判定的）。

| 起法 | 插件 bean | 说明 |
| --- | --- | --- |
| `-Dloader.path=/tmp/plugins` + `PropertiesLauncher` | **创建了** | 插件生效 |
| `PropertiesLauncher`，不给 `loader.path` | 没创建 | 证明插件确实来自那个目录，不是 classpath 里本来就有 |
| `java -jar app.jar -Dloader.path=...` | 没创建，**且不报错** | 默认 `Main-Class` 是 `JarLauncher`，它不认识 `loader.path` |

端到端也验过一次：`POST /api/chat` 让模型调 `pluginPing`，返回
`plugin 收到：外部插件端到端通了`——工具真到了模型手上，不是只建了个 bean。

两个坑：

- **`-D` 必须写在 `-cp` 前面**，且主类要显式写成 `PropertiesLauncher`。
  写成 `java -jar` 时启动一切正常、插件静默不加载，这是最难查的一种。
- **插件 jar 里必须有 `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`**。
  少这个文件，那个 jar 只是躺在 classpath 上，一个 bean 都不会注册。

### 网关类产品是怎么做到"不改源码加一个负载均衡算法"的

那类需求通常不是靠 `loader.path`，而是靠 **SPI + 外部 ClassLoader**：

1. 核心定义一个稳定接口（`LoadBalancer`）与一个按名字取实现的注册表；
2. 插件 jar 提供实现，并在 `META-INF/services/<接口全限定名>`（JDK `ServiceLoader`）、
   `META-INF/dubbo/`（Dubbo）、`META-INF/shenyu/`（ShenYu）里登记；
3. 启动时用 `URLClassLoader` 扫一个目录，把实现加载进来；
4. 配置里写 `load-balancer: my-round-robin`，按名字选中一个。

关键点是第 4 步：SPI 解决的是**「同类实现里选一个」**。

Claw4J 的扩展点不是这个形状，所以没有走 SPI：

| | 网关的扩展点 | Claw4J 的扩展点 |
| --- | --- | --- |
| 语义 | 同一接口的多个实现，按名字选一个 | 多个来源并存，全部交给模型 |
| 需要什么 | 稳定接口 + 注册表 + 名字 | Spring bean + 条件装配 |
| 用什么加载 | `ServiceLoader` + `URLClassLoader` | Boot 的自动配置发现 |

"两个工具插件都提供 `querySales`"在 Claw4J 里是**配置错误**（重名即冲突，见
`McpToolProvider` 的检查），不是"按名字选一个"——因为模型认的是工具名，
悄悄留一个等于让这次调到谁取决于注册顺序。

如果哪天真要"按名字选一个"（比如多种检索策略），那才需要 SPI 那一套；
现在还不需要，`loader.path` 已经够用，而且免费拿到 Spring 的条件装配与配置绑定。

---

## 一张表总结

| 我想加… | 改哪里 | 不需要改 |
| --- | --- | --- |
| 一个业务工具 | 新 jar：工具类 + 自动配置 + imports 文件 + 一条依赖 | 框架任何一处 |
| 一个新的 MCP 能力 | 一段 `claw4j.capability.mcp.servers.<名字>` 配置；要算值时才写 bean | 连接的建立、重连、权限登记 |
| 一个命令行能力 | 一个目录（`package.properties` + 入口脚本）+ `claw4j.scripts.directory` | 代码 |
| 一套做事流程 | 一个目录 + `SKILL.md` + `claw4j.skills.directory` | 代码 |
| 一个专职的子 Agent | 一份 `*.md` + `claw4j.subagents.directory` | 代码 |
| 一条新通道 | 新 jar：`ChatChannel` 实现 + 端点 + imports 文件 | agent、已有的通道 |

