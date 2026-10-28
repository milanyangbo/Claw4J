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
| Java 工具 | `ToolCallbackProvider` + `ToolPermissionRegistry` | `claw4j-domain`、`spring-ai-client-chat` | [`claw4j-example-sales`](../../claw4j-example-sales) |
| MCP server | 一个说 MCP 协议的可执行程序（任何语言） | 宿主侧 `claw4j-capability`；server 侧什么都不要 | [`claw4j-example-mcp-server`](../../claw4j-example-mcp-server)、[`examples/mcp/`](../../examples/mcp) |
| Skill | 一个含 `SKILL.md` 的目录 | 无（纯文本） | [`examples/skills/weekly-sales`](../../examples/skills/weekly-sales) |
| 通道 | 实现 `ChatChannel` 的 bean + 自己的端点 | `claw4j-domain`（要复用编排再加 `claw4j-channel`） | `claw4j-channel/http`、`claw4j-channel/feishu` |

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

**2. 自动配置**——把它变成容器里的两个 bean：

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
}
```

**3. 注册文件**——`src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`：

```text
com.acme.jira.JiraToolsAutoConfiguration
```

### 谁收这两个 bean

`AgentAutoConfiguration` 收工具用的是 `ObjectProvider<ToolCallbackProvider>`（连同单个
`ToolCallback` 一起），收权限用的是 `ObjectProvider<ToolPermissionRegistry>`；
鉴权与语义缓存两条链路各自按**列表**消费权限表。所以**加一个来源只是加一个 bean**，
没有任何一处需要点名你的工具类。

### 权限是必填项，漏了就是拒绝

工具没登记权限时，鉴权一律按"没权限"处理（ADR-0007）：失败方向是拒绝，不是放行。
登记的是**这个工具会碰到什么**（表 + 动作），不是"谁能用它"——后者在角色的授权表里，
改那个不用发版。

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

MCP 这一层与语言无关：**server 是另一个进程，和宿主之间只有 JSON-RPC**。
Python 能写，Java 也能写，写出来对宿主完全一样。

### 宿主侧：多放一个端点 bean

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

`McpToolAutoConfiguration` 收所有 `McpServerEndpoint`，每条连接在**启动时**建立、
工具清单在启动时取回。于是：

- **清单为空即失败**（ADR-0002）：连接是通的、协议握手也成功，只是它一件能力都没有。
  那种状态必须报错，否则现场是"模型说它没这个能力"，而真正的原因是命令写错了。
- **会话丢失就重新初始化连接**，不对着一条已经不在的会话重试——那会让一次断连变成永久不可用。
- **命令是 argv 数组**，不经任何命令解释器（ADR-0002）。模型的入参走 JSON-RPC 帧，永远不进 argv。

### server 侧：两个能直接抄的样本

**Java**：`claw4j-example-mcp-server`。它只有一个 `main`：

```java
var transport = new StdioServerTransportProvider(McpJsonDefaults.getMapper(), System.in, System.out);
var specs = new SyncMcpToolProvider(List.of(new DemoJavaTools())).getToolSpecifications();
McpServer.sync(transport).serverInfo("claw4j-java-demo", "1.0.0").tools(specs).build();
new CountDownLatch(1).await();   // main 返回 = 进程退出 = 客户端看到"刚起来就没了"
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

## 四、通道

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

- **复用现有编排要依赖整个 `claw4j-channel`**。认人、幂等、ACK、回推、过载答复这套逻辑
  现在长在 `feishu` 包里，第三方通道想复用它，就会把飞书 SDK 一起带进来。
  只依赖 `claw4j-domain` 也能接，代价是那套编排要自己再写一遍。
  抽一层平台无关的编排是待办，不是已完成的承诺。
- **插件 jar 的外部加载（`loader.path`）没验证过**。加 Maven 依赖重新打包是验证过的路径；
  不重新打包就换 classpath 这条路，写在这里是给方向，不是给保证。

---

## 一张表总结

| 我想加… | 改哪里 | 不需要改 |
| --- | --- | --- |
| 一个业务工具 | 新 jar：工具类 + 自动配置 + imports 文件 + 一条依赖 | 框架任何一处 |
| 一个新的 MCP 能力 | 宿主侧多一个 `McpServerEndpoint` bean；server 用任何语言写 | 连接的建立、重连、权限登记 |
| 一套做事流程 | 一个目录 + `SKILL.md` | 代码 |
| 一条新通道 | 新 jar：`ChatChannel` 实现 + 端点 + imports 文件 | agent、已有的通道 |
