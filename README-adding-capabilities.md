# 加一个能力：八个照着抄的最小例子

> [README.md](README.md) 讲怎么把它跑起来；这一份讲**怎么往里面加东西**。八节，每节一个能直接抄的 demo。

这一份是**操作手册**：每节给一个最小可跑的 demo，再说怎么打开、怎么确认它生效。
为什么这么设计、哪些写法会静默失效，在 [docs/design/extension-points.md](docs/design/extension-points.md)（同一件事的契约视角）。

共同前提：能力来源都是**同进程的可信代码**，加它不用改 Claw4J 一行代码，也不用在父 POM 里登记。
只有两条必须记住：**权限漏了 = 一律拒绝**，**档位漏了 = 起不来**。

| 你要加的东西 | 产出物 | 详细 |
| --- | --- | --- |
| Java 工具 | 3 个 bean + 一个注册文件 | [1](#1-java-工具4-个文件) |
| MCP server | 一段 YAML，或一个任何语言写的 server | [2](#2-mcp-server一段-yaml) |
| 脚本包 | 一个目录（清单 + 入口脚本） | [3](#3-脚本包一个目录) |
| Skill | 一个含 `SKILL.md` 的目录 | [4](#4-skill一份做法) |
| 子 Agent | 一份 `*.md` | [5](#5-子-agent一份-md) |
| agent（人设） | 一份 `*.md` | [6](#6-agent人设这个部署是谁) |
| 知识库 | 一个文档目录 | [7](#7-知识库一个目录) |
| 通道 | 一个 `ChatChannel` 实现 + 你的端点 | [8](#8-通道收认人发) |

---

## 1. Java 工具（4 个文件）

产出：一个普通 Java 对象 + 一段自动配置 + 一行注册。照抄 [`claw4j-example-sales`](claw4j-example-sales) 即可。

```text
jira-plugin/
  src/main/java/com/acme/jira/JiraTools.java                       # 工具本体
  src/main/java/com/acme/jira/JiraToolsAutoConfiguration.java      # 三个 bean
  src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
  src/main/resources/META-INF/claw4j-plugin.properties              # 可选：报核心版本区间
```

**① 工具本体**——`@Tool` 标在方法上，不需要继承任何东西：

```java
public class JiraTools {

    @Tool(name = "jiraSearchIssues",
          description = "按 JQL 搜索 Jira 工单，只读。例：project = OPS AND status = Open")
    public String search(@ToolParam(description = "JQL 语句") String jql) {
        // 失败返回原因，不要抛异常：抛出去会被框架包成模型看不懂的话
        return jira.search(jql);
    }
}
```

**② 自动配置**——工具、权限、档位三样，一个都不能少：

```java
@AutoConfiguration
public class JiraToolsAutoConfiguration {

    @Bean
    public ToolCallbackProvider jiraTools(JiraClient jira) {
        return MethodToolCallbackProvider.builder().toolObjects(new JiraTools(jira)).build();
    }

    // 这个工具会碰到什么。漏了 = 鉴权一律拒绝（方向是安全的，但你调不动它）
    @Bean
    public ToolPermissionRegistry jiraPermissions() {
        return ToolPermissionRegistry.of(Map.of(
                "jiraSearchIssues", ToolPermission.read()));
    }

    // 最低需要哪一档才出现。漏了 = 启动失败并点名
    @Bean
    public ToolTierRegistry jiraToolTiers() {
        return ToolTierRegistry.sameFor(List.of("jiraSearchIssues"), ToolTier.MESSAGING);
    }
}
```

**③ 注册文件**——`AutoConfiguration.imports`，一行一个全限定类名：

```text
com.acme.jira.JiraToolsAutoConfiguration
```

**依赖**：`claw4j-core` + `spring-ai-client-chat`。**打开**：把 jar 加进依赖（或见文末 `loader.path`）。

**怎么确认**：启动日志里那行 `工具档位 messaging：清单 N 个，挡掉 M 个（…）` —— `N` 应该比之前多 1；
然后问一句只有这个工具能答的话。

**三条不要写**：三个 bean 上都不要 `@ConditionalOnMissingBean`（会让别的来源静默消失）；
档位不要写成"任何名字都算"；不要在自动配置里用 `@ConditionalOnBean` 依赖同类里 `@Bean` 的声明顺序。

---

## 2. MCP server（一段 YAML）

**接别人写的**：一段配置 = 一条连接，一行代码都不用写。

```yaml
claw4j:
  capability:
    mcp:
      servers:
        deepwiki:                                   # 对方已经跑着，我们只当客户端
          transport: http                           # http（Streamable）/ sse（旧版）/ stdio（默认，起子进程）
          url: https://mcp.deepwiki.com
        everything:
          command: npx
          args: ["-y", "@modelcontextprotocol/server-everything"]
          inherit-env: [PATH, HOME]                 # 子进程要能找得到 npx
          permission:
            action: read                            # 默认只读、不碰任何业务表
          tools:                                    # 可选：按工具覆盖权限
            send_email: { action: send_email }
```

- **超时要调大**：`request-timeout`（默认 30s）与 `initialization-timeout`（默认 60s）是两个键。
  `npx` 冷启动实测 36 秒，只调握手那个没用——30 秒的请求超时会先炸。
- **凭据写句柄不写值**：`headers: { Authorization: "Bearer secret:mcp_token" }`，真值在每次请求时才换出来；
  非凭据常量用 `plain:`。裸写明文启动就被拒。
- **环境不是白名单**：显式声明的变量一定在，父进程其余变量也会被继承——要隔离靠部署层的容器策略。

**自己写 server**：语言随便，协议一样。两个平级样本：[`examples/mcp/`](examples/mcp)（Python，零依赖）、
[`claw4j-example-mcp-server`](claw4j-example-mcp-server)（Java，`java -jar` 就能跑）。
最容易撞的一条：**stdout 是协议通道**，日志一律走 stderr。

**怎么确认**：启动日志 `MCP server 状态：deepwiki → UP(工具 3 个)`；`DOWN` 会说明原因，并在后台按 15 秒起翻倍退避重连。

---

## 3. 脚本包（一个目录）

产出：一个目录 = 一个工具，**工具名就是目录名**。

```text
examples/scripts/weather-forecast/
  package.properties      # description=…  entrypoint=run.sh  timeoutMillis=20000
  run.sh                  # 入口；参数是 argv 数组，不经 shell
```

```properties
# package.properties
description=查一个城市未来几天的天气预报。用法：给一个城市名，例如“深圳”或“Shenzhen”。
entrypoint=run.sh
timeoutMillis=20000
```

```sh
#!/bin/sh
# 参数已经由框架做过校验与引号转义，这里不再解析、也不拼进任何命令
exec python3 "$(dirname "$0")/weather.py" "$@"
```

**打开**：`--claw4j.scripts.directory=examples/scripts`。**权限动作是 `run_script`**，与 `read` 是两个口径：
默认三个角色里只有 `admin` 有它。

**边界**：脚本本体**不进上下文**，进上下文的只有标准输出；子进程只拿得到 `PATH`，**拿不到任何凭据**——
需要 key 的能力在这一层跑不通，该走 MCP。

---

## 4. Skill（一份做法）

Skill 不是动作，是**做法**：它不读库、不调接口，只告诉模型"这件事按什么顺序做、什么算做完"。

```text
examples/skills/weekly-sales/SKILL.md
```

```markdown
---
name: weekly-sales
description: 出一份上周各区成交额周报。用户问“上周卖得怎么样”“各区对比”时用它。
---

## 口径
成交额 = `sum(order_item.amount)`，`status <> 'CANCELLED'`。
**没看到这段口径就先问一句"按哪个口径"，不要自己挑一个。**
```

**打开**：`--claw4j.skills.directory=examples/skills`。工具名 `Skill`，动作 `use_skill`。

**渐进式披露**：启动时只把 `name` + `description` 放前面，命中之后正文作为一次工具调用的结果进来。
所以二十份做法只花二十行描述。它**不新增能力**——真正碰表那一步照样过鉴权。

---

## 5. 子 Agent（一份 md）

产出：一份 `*.md`，front matter 四个键，正文是它自己的系统提示词。

```markdown
---
name: capacity-triage
description: 判断这个进程的容量是不是吃紧了。用户问“要不要扩容”时派它，别在父上下文里自己猜。
tools:
  - systemStatus
---
你被派来只做一件事：把**这一刻**的容量事实读出来，给一个能拿去决策的结论，不修任何东西。
```

- `tools` 是白名单；也可以改成 `disallowedTools`（继承全部再减）。
- 点了容器里没有的工具、或点了 `Task` 自己，**启动失败**。
- 用的是父的身份，过同一套鉴权——**委派不提权**。

**打开**：`--claw4j.subagents.directory=examples/subagents`。工具名 `Task`，动作 `delegate_task`。
三个能直接抄的形状在 [`examples/subagents/`](examples/subagents)。

---

## 6. agent（人设）：这个部署是谁

同一个 jar，两次部署只改配置：一个变销售助手，一个变运维助手。**这一段决定"你是谁"。**

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
    directory: examples/agents
    default: sales
    zone: Asia/Shanghai
```

- `tier` 只覆盖**档位**，不覆盖 allow / deny——销售助手永远不该跑脚本，哪怕部署全局是 `full`。
- `glossary` 是**引用**，几份人设指向同一个文件：口径改一处，所有引用它的 agent 同时变。
- **时间不要写进正文**，框架每轮会拼一句；写死的时间在进程跑一周后还是启动那天。
- 目录里有多份却没写 `default` 是**启动失败**并列出候选。

**怎么确认**：启动日志 `这个部署的 agent 是 …，人设来自 …`。

---

## 7. 知识库（一个目录）

```text
docs-kb/
  metrics.md
  refund-policy.txt
```

```yaml
claw4j:
  rag:
    enabled: true
    directory: docs-kb          # 开了就必须配，空知识库比启动失败更糟
```

启动后同步一次，按文件内容哈希跳过没变的。默认混合检索（向量 + 全文）加 `qwen3-rerank` 重排：
向量与重排都复用 `QWEN_API_KEY`，全文那一路用 PG 的 `pg_trgm`。**每段结果都带「文档 + 第几段」**，不写出处等于让模型自由发挥。

**怎么确认**：问一个只有这份文档能答的问题，回答里应带出处；再问一句闲聊，不该被"资料里没有"劫持。

---

## 8. 通道（收、认人、发）

产出：一个 `ChatChannel` 实现 + 你自己的端点。

```java
@AutoConfiguration
@ConditionalOnProperty(prefix = "acme.channel.telegram", name = "enabled", havingValue = "true")
public class TelegramChannelAutoConfiguration {

    @Bean(initMethod = "start", destroyMethod = "stop")
    public TelegramChannel telegramChannel(AgentPort agentPort) {
        return new TelegramChannel(agentPort, token);
    }
}
```

接口只有三个方法（`id()` / `start()` / `stop()`），**三条硬要求写在契约里**：
拿到消息立刻 ACK、用事件 id 做幂等、认不出人就拒绝。
ACK 之后的去重 / 认人 / 异步 / 分段回推 / 失败也回一句，已经在 `channel.kernel` 里做好了——
它在 `claw4j-core`，所以接 Telegram 不用把飞书 SDK 拖进 classpath。

**怎么确认**：给它发一条消息，收到回答；再故意发一条重复事件，回答只出现一次。

---

## 最后：插件 jar 怎么进 classpath

| 方式 | 命令 / 做法 | 代价 |
| --- | --- | --- |
| Maven 依赖 | 正常加一条依赖 | 要重新构建发版 |
| `loader.path` | `java -cp app.jar -Dloader.path=/opt/claw4j/plugins org.springframework.boot.loader.launch.PropertiesLauncher` | 启动时扫一次，不是热加载 |

`java -jar app.jar -Dloader.path=…` **不生效且一声不响**：默认 `Main-Class` 是 `JarLauncher`，它不认识这个参数。

再可选一步：在 jar 里放 `META-INF/claw4j-plugin.properties` 声明 `requires-core=>=0.1.0 <0.2.0`，
启动时**在任何 bean 被创建之前**对一次，不匹配就停并指出是哪个 jar。没有跳过校验的开关。
