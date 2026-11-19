# 加一个能力：九个照着抄的最小例子

> [README.md](README.md) 讲怎么把它跑起来；这一份讲**怎么往里面加东西**。九节，每节一个能直接抄的 demo。

这一份是**操作手册**：每节给一个最小可跑的 demo，再说怎么打开、怎么确认它生效。

共同前提：能力来源都是**同进程的可信代码**，加它不用改 Claw4J 一行代码，也不用在父 POM 里登记。
只有一条必须记住：**档位漏了 = 起不来**。

| 你要加的东西 | 产出物 | 详细 |
| --- | --- | --- |
| Java 工具 | 两个 bean + 一个注册文件 | [1](#1-java-工具4-个文件) |
| MCP server | 一段 YAML，或一个任何语言写的 server | [2](#2-mcp-server一段-yaml) |
| 技能里的脚本 | 技能目录下的 `scripts/`，由模型用 `Bash` 跑 | [3](#3-技能里的脚本一个目录--skillmd) |
| Skill | 一个含 `SKILL.md` 的目录 | [4](#4-skill一份做法) |
| 子 Agent | 一份 `*.md` | [5](#5-子-agent一份-md) |
| agent（人设） | 一份 `*.md` | [6](#6-agent人设这个部署是谁) |
| 知识库 | 一个文档目录 | [7](#7-知识库一个目录) |
| 通道 | 一个 `ChatChannel` 实现 + 你的端点 | [8](#8-通道收认人发) |
| 工具观测器 | 一个 `ToolCallListener` bean | [9](#9-工具观测器一个-bean) |

---

## 1. Java 工具（4 个文件）

产出：一个普通 Java 对象 + 一段自动配置 + 一行注册。照抄 [`claw4j-example-sales`](claw4j-example-sales) 即可。

```text
jira-plugin/
  src/main/java/com/acme/jira/JiraTools.java                       # 工具本体
  src/main/java/com/acme/jira/JiraToolsAutoConfiguration.java      # 两个 bean
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

**② 自动配置**——工具与档位两样，一个都不能少：

```java
@AutoConfiguration
public class JiraToolsAutoConfiguration {

    @Bean
    public ToolCallbackProvider jiraTools(JiraClient jira) {
        return MethodToolCallbackProvider.builder().toolObjects(new JiraTools(jira)).build();
    }

    // 这个工具属于哪几档，同时登记成"外部工具"（group:plugins 与沙箱只认这一批）。
    // 漏了 = 启动失败并点名；写 sameFor 而不是 external，group:plugins 就关不掉它。
    @Bean
    public ToolTierRegistry jiraToolTiers() {
        return ToolTierRegistry.external(List.of("jiraSearchIssues"),
                ToolTier.MESSAGING, ToolTier.CODING);
    }
}
```

**③ 注册文件**——`AutoConfiguration.imports`，一行一个全限定类名：

```text
com.acme.jira.JiraToolsAutoConfiguration
```

**依赖**：`claw4j-core` + `spring-ai-client-chat`。**打开**：把 jar 加进依赖（或见文末 `loader.path`）。

**怎么确认**：问一句只有这个工具能答的话，然后看计数——

```bash
curl -s "localhost:9090/actuator/metrics/claw4j.tool.calls?tag=tool=jiraSearchIssues&tag=outcome:ok"
```

问之前这个地址返回 404（还没有这个标签），问之后有数、`ok` 为 1。**别去启动日志里找
`工具画像 … 清单 N 个`**：那行只在真有工具被挡掉时才打印（默认画像 `full` 什么都不挡），
刚加一个工具时它根本不会出现。

**两条不要写**：两个 bean 上都不要 `@ConditionalOnMissingBean`（会让别的来源静默消失）；
档位不要写成"任何名字都算"（`name -> Optional.of(...)` 那种）——那会让未声明的校验永远通过，
新加的工具默默出现在不该出现的档里。也不要在自动配置里用 `@ConditionalOnBean` 依赖同类里
`@Bean` 的声明顺序。

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
          tool-filter:
            exclude: [send_email]                   # 可选：这个 server 的哪些工具进清单
```

- **可见性和随包工具同一套**：接进来的工具声明为 `coding` 与 `messaging` 两档，
  `claw4j.tools.profile` 收到 `minimal` 时它们不出现；要按 server 收窄就用 `tool-filter.include` /
  `exclude`（`*` 通配，按整串匹配，滤掉的工具 `allow` 也加不回来）。
- **超时要调大**：`request-timeout`（默认 30s）与 `initialization-timeout`（默认 60s）是两个键。
  `npx` 冷启动实测 36 秒，只调握手那个没用——30 秒的请求超时会先炸。
- **凭据写句柄不写值**：`headers: { Authorization: "Bearer secret:mcp_token" }`，真值在每次请求时才换出来；
  非凭据常量用 `plain:`。裸写明文启动就被拒。
- **环境不是白名单**：显式声明的变量一定在，父进程其余变量也会被继承——要隔离靠部署层的容器策略。

**自己写 server**：语言随便、传输随便，宿主只认协议。两个平级样本：
[`examples/mcp/`](examples/mcp)（Python，stdio，零依赖）与
[`claw4j-example-mcp-server`](claw4j-example-mcp-server)（Java，Streamable HTTP，`java -jar` 就能跑，
先起它再起宿主）。接 HTTP 那个只要三行：

```yaml
java-http:
  transport: http
  url: http://127.0.0.1:8082
```

写 stdio server 时最容易撞的一条：**stdout 是协议通道**，日志一律走 stderr。HTTP 那份没这个约束，
它的日志照常往 stdout 打。

**怎么确认**：启动日志 `MCP server 状态：deepwiki → UP(工具 3 个)`。连不上时是
`broken → DOWN(15 秒后重试)`——括号里是**下次重试的间隔**，原因在同一时刻旁边那行 WARN 里
（`MCP server broken 暂时不可用（…连不上：Client failed to initialize by explicit API call）`）。
之后按 15 秒起、每次翻倍、封顶 10 分钟在后台重连，应用照常起。

---

## 3. 技能里的脚本（一个目录 + `SKILL.md`）

产出：一个目录，`SKILL.md` 讲什么时候用、怎么跑，脚本与数据放在它下面。技能本身不被执行，
脚本由模型用 `Bash` 去跑。

```text
examples/skills/weather-forecast/
  SKILL.md
  scripts/run.sh
  scripts/weather.py
```

```markdown
---
name: weather-forecast
description: 查一个城市未来三天的天气预报。用户问“明天深圳热不热”时用它。
---

## 怎么跑

脚本在技能目录的 `scripts/` 下，拿 `Bash` 照抄这一条（`{baseDir}` 会被替换成那个目录）：

    bash {baseDir}/scripts/run.sh 深圳
```

```sh
#!/bin/sh
# 参数是模型写的一条命令里的一段；到这一层就是一个普通字符串，这里不解析也不拼进别的命令。
# PYTHONIOENCODING 不能省：Bash 的子进程只拿到声明的那几个环境变量，没有 LANG/LC_ALL，
# Python 3.7 以前的 stdout 会落在 ASCII 上，打印中文直接崩（退出码 1）。
set -e
here=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
exec env PYTHONIOENCODING=utf-8 python3 "$here/weather.py" "$@"
```

**打开**：`--claw4j.skills.directory=examples/skills`，和 §4 是同一个目录——脚本没有单独那一层了。

**路径**：模型看到的第一行是 `Base directory for this skill: <技能目录>`，正文里的 `{baseDir}`
在交给模型之前就替换成它（正文里这么写就能直接跑）。所以脚本的路径要写全，
**不要写相对路径**：`Bash` 的工作目录是 `claw4j.tools.exec.workspace`，不是技能目录。

**加载前的审批标记**：技能目录的摘要（所有文件，含脚本）绑在 `.review/<name>.properties` 的 `sha256` 上。
改一个脚本字节，旧标记就作废——正文与脚本同一个账本，不存在"审了正文没审脚本"。

**边界**：脚本本体**不进上下文**，进上下文的只有 `Bash` 的返回；子进程只拿得到声明的那几个环境变量
（默认只有 `PATH`），**拿不到任何凭据**——需要 key 的能力该走 MCP。反过来要记住：**没有 locale
也是这条边界的代价**，脚本里的输出编码、时间格式、大小写规则都别指望环境给。

**失败会说什么**：`Bash` 直接回模型的是一条工具结果（退出码 + stdout + stderr），不再经过一层
"包装成异常"。所以正文里该写清"哪种退出码算环境问题、哪种算输入问题"——那是模型分流处理的依据。

---

## 4. Skill（一份做法）

Skill 不是工具，也不是脚本，是**做法**：它不读库、不调接口，只告诉模型"这件事按什么顺序做、什么算做完"。

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

**打开**：`--claw4j.skills.directory=examples/skills`。模型看到的工具名是 `Skill`。

**渐进式披露**：启动时只把 `name` + `description` 放前面，命中之后正文作为一次工具调用的结果进来。
所以二十份做法只花二十行描述。它**不新增能力**——真正碰表那一步照样受工具清单约束。

**怎么确认**：问一句只有这份做法能答的话（比如"周报的成交额按什么口径算"），
`claw4j.tool.calls?tag=tool=Skill&tag=outcome:ok` +1，且回答里出现 `SKILL.md` 正文的原话——
那句原话进了上下文，才说明正文真的被加载，而不是模型照常识编的。

---

## 5. 子 Agent（一份 md）

产出：一份 `*.md`，front matter 写 `name` 与 `description`（要限工具再加 `tools` / `disallowedTools`），
正文是它自己的系统提示词。

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
- 白名单里写了 `Task` 自己 → **启动失败**（委派不嵌套）。写了容器里**没有的**工具 →
  **首次委派时**才失败并点名，不是启动时：工具集要等 `Task` 这个 bean 建好才能取，装配期取会撞循环依赖。
- 用父的身份，走同一套档位策略——**委派不提权**。

**打开**：`--claw4j.subagents.directory=examples/subagents`。工具名 `Task`，入参是库的
`TaskCall`——`subagent_type` 派谁、`prompt` 让它做什么；要后台跑就加 `run_in_background: true`，
再用 `TaskOutput` 按 `task_id` 取结论。几份能直接抄的形状在
[`examples/subagents/`](examples/subagents)。

**怎么确认**：`claw4j.tool.calls?tag=tool=Task&tag=outcome:ok` +1；把
`--logging.level.org.springframework.ai=DEBUG` 打开还能看到
`委派 task 给 Subagent <名字>：它这趟能用的工具是 […]`。回答里的结论只能来自那几个工具——
多出一项查库结果，就是白名单没生效。

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

**本地开发什么都不用配**：`application-local.yml.example` 里把这些开关都写好了，起
`--spring.profiles.active=local` 就带着它们（知识库要 compose 的 pgvector 与 `.env` 里的 qwen key）：

| 配置 | 本地那份写了什么 |
| --- | --- |
| `claw4j.rag.directory` | `examples/knowledge`，`enabled: true` |
| `claw4j.skills.directory` | `examples/skills` |
| `claw4j.subagents.directory` | `examples/subagents` |
| `claw4j.memory.directory` | `${user.home}/.claw4j/memory`：跨会话的长期记忆，每人一层、明文 |
| `claw4j.session.*` | schema / 私聊与群聊边界 / 上下文窗口 / 换段策略，本地值集中在那一段 |

上面这几段 yaml 是**别的部署**要加的行。用 local profile 时它们已经在文件里，启动命令行就不用再带
`--claw4j.skills.directory=...` 这类参数。

**怎么确认**：问一个只有这份文档能答的问题，回答里应带出处；再问一句闲聊，不该被"资料里没有"劫持。
重排有没有真跑，看 DEBUG 日志那一行 `重排完成：候选 N 条，服务返回 M 条，前三条 […] → […]`——
只有它出现了才说明响应被解析、顺序被应用过；`M` 为 0 或整个不出现，就是重排没生效（失败会另有一行 WARN）。

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

**怎么确认**：给它发一条消息，收到回答；再让通道**重投同一条事件**（IM 通道有事件 id：钉钉取
`msgId`、Slack 取 `event_id`），回答只出现一次。HTTP 通道没有事件 id，验的是"能问答"与
"没身份被拒"这两条，验不了重复：往它灌同一句话两次，会再答一次（可能命中语义缓存）——那不是 bug，
是它本来就没有幂等键。

---

## 9. 工具观测器（一个 bean）

想知道"模型这一轮调了哪些工具、各自花了多久、哪个失败了"，注册一个 `ToolCallListener` 就够了，
不用改任何工具实现。装饰由库的 `ToolCallListeners.wrapAll` 做，我们只决定套在哪一层。

```java
@Bean
ToolCallListener toolAudit() {
    return new ToolCallListener() {
        @Override
        public void afterCall(Object context, String toolName, String toolInput, String result) {
            // 每次真实执行之后：推给 UI、写外部审计、算耗时分布
        }
    };
}
```

四个回调：`beforeCall`（执行前，可返回一个关联上下文）、`afterCall`（正常返回）、`onError`（工具抛了）、
`afterCompletion`（无论成败，但 `beforeCall` 抛时不会来）。没有 bean 时这一层**不存在**——清单里还是
原来那些对象，不多一层包装。

**三条必须知道的**

1. **回调里不许抛。** 库不兜异常：`beforeCall` 抛会阻止工具执行，`afterCall` 抛会丢掉一个**已经成功**
   的结果（副作用已经发生，模型可能重试它）。自己的失败要在回调里吞掉。
2. **它看不见被审批拦下的调用。** 它套在审批闸之内：需要人批的命令在进到这一层之前就停下了，
   所以 listener 只会收到真正执行过的那些。被拦那一次在审计里是 `failed`、且没有 `approval_id`，
   与"执行了但失败"分开看。
3. **它替代不了计量与审计。** 那两样要 `runId` / 身份 / 会话键，而 listener 的回调签名里没有
   `ToolContext`；它们留在 `MeteredToolCallback` 里。listener 是给部署方的观测点，不是内部账本的落点。

**怎么确认**：调一个工具，你的回调被调到了；把回调改成抛异常，工具照样跑完（也就是说你没有把
它当成控制流）。

---

## 最后：插件 jar 怎么进 classpath

| 方式 | 命令 / 做法 | 代价 |
| --- | --- | --- |
| Maven 依赖 | 正常加一条依赖 | 要重新构建发版 |
| `loader.path` | `java -cp app.jar -Dloader.path=/opt/claw4j/plugins org.springframework.boot.loader.launch.PropertiesLauncher` | 启动时扫一次，不是热加载 |

`java -jar app.jar -Dloader.path=…` **不生效且一声不响**：默认 `Main-Class` 是 `JarLauncher`，它不认识这个参数。

再可选一步：在 jar 里放 `META-INF/claw4j-plugin.properties` 声明 `requires-core=>=0.1.0 <0.2.0`，
启动时**在任何 bean 被创建之前**对一次，不匹配就停并指出是哪个 jar。没有跳过校验的开关。
