# Claw4J

![License: MIT](https://img.shields.io/badge/License-MIT-green.svg)
![JDK](https://img.shields.io/badge/JDK-21-blue.svg)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.1.2-brightgreen.svg)
![Spring AI](https://img.shields.io/badge/Spring%20AI-2.0.2-brightgreen.svg)

跑在 Spring AI 2.x 上的**企业 Agent 骨架**：对话从哪儿进来、能动手做什么、能不能做、做完留下什么——
这四件事各有自己的接缝，换掉任何一件都不用动核心。

企业里通常已经有模型 API、有 IM、有一堆内部系统，缺的是中间那一层：把对话接进来、把能力接上去、
把权限卡在模型的嘴前面。Claw4J 就是那一层。它**不是某一个业务的问答机器人**——业务能力是插件，
仓库自带的销售工具只是一个示例实现（[`claw4j-example-sales`](claw4j-example-sales)，独立 jar，核心不认识它）。

三条边界：**不做登录体系**（认证在网关侧，通道只负责"认出是谁"）；**不自己造插件机制**
（用 Boot 自动配置，一个 jar + 一个 `AutoConfiguration.imports` 就是插件，插件是同进程的可信代码）；
**核心不含业务**（工具、MCP server、脚本包、Skill、子 Agent 都是能力来源，谁写的都一样对待）。

## 特性

| 要点 | 说明 |
| --- | --- |
| **五条通道，同一份逻辑** | HTTP+SSE（给网关/前端）、飞书长连接（给群聊）、企业微信自建应用（给企业内）、钉钉机器人（给企业内，**明文 JSON + header 验签**）、Slack（给团队，**整包验签 + 答案回线程**）；通道只做"收进来 → 认出是谁 → 发出去"，去重/认人/异步/回推只有一份 |
| **五种能力来源，对模型是一回事** | Java `@Tool`、MCP server（stdio / HTTP 都行）、受限脚本包、Agent Skill、子 Agent |
| **知识库检索带出处** | 一份文档目录 + 两路检索（向量 + 中文关键词），按 RRF 排名融合，每段都带「文档 + 第几段」；文档块**不进**缓存表 |
| **一轮一个 trace** | 配一个 OTLP 端点就把整条链路导出去：根 span 是 **run**（不是请求——请求在进线程池那一刻就断了），工具与模型轮挂在它下面，与审计靠 `claw4j.run.id` 对上；**默认不导**，也不新增任何计数器 |
| **也能反过来当 MCP server** | `claw4j.mcp.server.expose` 点名几个工具，Claude Code 这类客户端就能像用别的工具一样调它们；交出去的只能是模型能用的那一份里的 |
| **插件要报它要的核心版本** | 插件 jar 里一份 `META-INF/claw4j-plugin.properties` 写 `requires-core=>=0.1.0 <0.2.0`；启动时对一次——**在任何 bean 被创建之前**，不匹配就停，并指出是哪个 jar 的哪句话。不是等到某次调用 `NoSuchMethodError`。没有跳过校验的开关；这道闸装在 `claw4j-app` 的启动路径上 |
| **流式与同步同源** | Advisor 必须同时实现 Call 和 Stream，只写一个会在流式路径上静默不生效 |
| **权限卡在模型之前** | 角色到"可见表 × 可执行动作"的映射在配置里；越权与套取提示词的消息不花 token、不进历史 |
| **委派不提权** | 子 Agent 独立上下文与预算，只把结论带回来，用的是父的身份、照样过鉴权 |
| **边界由测试守护** | 谁能依赖谁写在 ArchUnit 规则里，加错依赖当场红 |

## 快速开始

前置：**JDK 21**、**Docker**（数据库）、**python3**（三个示例 MCP server）、一个 `DEEPSEEK_API_KEY`。

```bash
git clone git@github.com:milanyangbo/Claw4J.git && cd Claw4J
echo 'DEEPSEEK_API_KEY=sk-xxxxxxxx' > .env           # 也可以直接 export

cp claw4j-app/src/main/resources/application-local.yml.example \
   claw4j-app/src/main/resources/application-local.yml
# 这份被 .gitignore 忽略（可能放真实 key 与真实的人），仓库里只有模板；
# 接哪些 MCP server 也写在这里（一段配置 = 一条连接）

./mvnw -q -DskipTests package
java -jar claw4j-app/target/claw4j-app-0.1.0-SNAPSHOT.jar --spring.profiles.active=local
# local 会按 compose.yaml 拉起 Postgres(pgvector)，退出时回收；compose.yaml 在仓库根目录，所以要在根目录启动
```

起来就问（`X-Claw4j-User` 必填，`X-Claw4j-Role` 缺省是 `read-only`）：

```bash
curl -s -X POST http://localhost:8080/api/chat \
  -H 'Content-Type: application/json' -H 'X-Claw4j-User: ethan' -H 'X-Claw4j-Role: admin' \
  -d '{"text":"这个进程现在堆内存上限是多少？线程数呢？"}'
# {"chatId":"http:ethan","text":"堆内存上限 8192 MB（8 GB），当前已用 73 MB；线程数 42。"}
```

- 国内拉不到官方镜像：`CLAW4J_POSTGRES_IMAGE=docker.m.daocloud.io/pgvector/pgvector:pg16`。
- 没有 key 应用**起不来**，不降级：留 mock 兜底只会得到"跑着回声模型却以为在跑真模型"。
- 健康检查在另一个端口：`curl -s localhost:9090/actuator/health`。

## 使用

### 接口

| 接口 | 方法 | 说明 |
| --- | --- | --- |
| `/api/chat` | POST | 同步问答，返回 `{"chatId":"...","text":"..."}` |
| `/api/chat/stream` | POST | 流式问答，SSE，事件名 `chunk` / `done` |

请求体只有 `{"text":"..."}`；请求头三个：`X-Claw4j-User`（必填）、`X-Claw4j-Role`（缺省 `read-only`，
给了但没登记是 401）、`X-Claw4j-Chat-Id`（缺省回落到用户标识）。

```bash
# 流式：客户端断开即中断背后的模型调用
curl -N -X POST http://localhost:8080/api/chat/stream \
  -H 'Content-Type: application/json' -H 'X-Claw4j-User: ethan' \
  -d '{"text":"上周华东的成交额是多少"}'
# event:chunk / data:上周华东的成交额是 128,400.00…… / event:done
```

错误码：`401` 认不出人、`400` 请求体不合法、`503` 流式线程池已满。敏感词命中不回错误码，
而是整轮拒答并返回一句固定答复——不花 token，也不进会话历史。

### 身份与权限

授权表只有一份，飞书也按角色名用它（`claw4j.channel.http.roles`）：

| 角色 | 可见表 | 可执行动作 |
| --- | --- | --- |
| `read-only`（缺省） | `*` | `read`、`use_skill`、`delegate_task`、`read_system`、`recall_session`、`get_goal`、`create_goal`、`update_goal`、`schedule_task`、`list_schedules`、`cancel_schedule` |
| `guest` | `region` | `read`、`use_skill`、`delegate_task` |
| `admin` | `*` | `*` |

动作名与工具登记的动作同口径：`read`（读表）、`use_skill`、`delegate_task`、`read_system`、
`recall_session`、`get_goal` / `create_goal` / `update_goal`（这一段对话的目标：读、建、标达成或阻塞）、
`schedule_task`（定一个以后按点做的任务）、
`list_schedules`、`cancel_schedule`（只碰自己的任务，按归属键判、不按编号判）、`run_script`（跑脚本包）、
`draft_script`（交脚本草稿）、`send_email`（MCP 按工具覆盖时用）。
表里没有的角色一律拒绝，没有默认角色兜底。**会话记忆按「人 + 权限 + 通道 + 对话 + 段」落库**（`SessionKeys`）：同一个人在网页私聊里查到的数字，不会出现在他十分钟后 @ 机器人的项目群里（听众不一样，权限指纹管不到这一层）；**换一份权限也是另一段会话**——降权之后，历史里躺着的数字读不到，而不是还记得（ADR-0007 的边界在会话这层同样成立）。**段是"这段对话的第几世"**：跨了逻辑日（默认 4 点，按 `claw4j.agent.zone`）或空闲超过 `idle-minutes`（默认 120）就换一个，换段 = 换对话，记忆与额度都从零开始（ADR-0026）。`recall_session` 跟着同一个粒度：`claw4j.tools.sessions.visibility=self` 时它只回查当前这一段对话，看不到别的会话。**默认值是 `all`**（照 OpenClaw 的 `tools.sessions.visibility`，四档 `self` / `tree` / `agent` / `all`），也就是它**能**召回别的会话、包括其他用户的——这是它的默认值，不是 bug，安全位就在这个配置项上。**多用户、角色不同的部署必须显式配 `self`**：`read-only` 是被允许"问数据"的身份，不是被允许"看别人的对话"的身份。启动日志里会打出生效范围，写错的词启动失败（不回落成 `all`）。**同一段对话同时只跑一件事**：连发两句时第二句排队等第一句说完（最多 `claw4j.agent.lane-wait`，默认 60 秒），等不到就回一句"现在有点忙"——不排队的话，第二句拼提示词时第一句的答案还没落库，长得就像"模型偶尔会忘事"。

**定时任务存的是身份（`userId` + 角色名），不是权限快照**：到点那一刻按当前的角色表重算一遍，
所以 `schedule_task` 永远不比这个人现在能做的更多——把这个角色删掉（或把某个工具的动作收走），
任务下一次到点就失败并自动停用，同时通知任务主人。

### 当 MCP server 用

同一个 jar 换一个 profile，就从"服务"变成"工具"——Claude Code / Codex 这类客户端可以把这里的
业务工具当成自己的工具调：

```bash
java -jar claw4j-app/target/claw4j-app-0.1.0-SNAPSHOT.jar \
     --spring.profiles.active=mcp-stdio \
     --claw4j.mcp.server.expose=querySales
```

客户端的 `.mcp.json` 就是一条 `command`（把上面那串原样抄进 `args`）。

三件要记住的事：

- **交出去的只能是模型能用的那一份里的**。档位（`claw4j.tools.profile`）与 allow / deny 在上面照常生效；
  写着需要审批的工具、或者动作不是 `read` 的工具，启动就失败并点名——外部客户端没有人能点按钮，
  也没有 run 可以挂起。
- **客户端是另一个身份**。它的动作只有 `read`，能读哪些表由 `claw4j.mcp.server.tables` 决定，
  与 `channel.http.roles` 那张授权表无关（客户端没有 IM 身份，拿它去查角色表只能靠猜）。
  收窄之后，工具要读的表对不上就在调用时被拒，且工具一次都不会执行。
- **不落审计**。审计是 run 级的（P07），外部调用没有 run——只留一行日志，不假装它是别的东西。
  同理，它也不扣 run 预算。

`mcp-stdio` 那份 profile 只做一件事：把 stdout 让给协议。日志改走 stderr、横幅关掉、容器不起、
四个通道与调度关掉——一帧 JSON 中间插进一行启动日志，客户端报的是"连不上"。

## 集成

七种能力 + 一条新通道，都是自动配置或一段配置——**不改 Claw4J 的代码，也不在父 POM 里登记**。
能整份照抄的模板与三种"静默失效"的写法在 [`docs/design/extension-points.md`](docs/design/extension-points.md)。

| 你要加的东西 | 写什么 | 依赖 |
| --- | --- | --- |
| 一个工具 | `@Tool` 方法 + 三个 bean + `AutoConfiguration.imports` | `claw4j-core`、`spring-ai-client-chat` |
| 一个 MCP server | 一段 YAML；server 用任何语言写，宿主侧不写代码 | 宿主侧 `claw4j-capability`；**server 侧零依赖** |
| **把自己暴露成 MCP server** | 配 `claw4j.mcp.server.expose` 点名要交出去的工具，用 `application-mcp-stdio.yml` 那份 profile 起 | 无（`mcp-core` 已随客户端那一层进来） |
| 一个脚本包 | 一个目录（`package.properties` + 入口脚本），配 `claw4j.scripts.directory` | 无 |
| 一个 Skill | 一个含 `SKILL.md` 的目录，配 `claw4j.skills.directory` | 无（纯文本） |
| 一个子 Agent | 一份 `*.md`（front matter + 正文），配 `claw4j.subagents.directory` | 无（纯文本） |
| **一个 agent（人设）** | 一份 `*.md`（front matter + 正文）+ 可选口径，配 `claw4j.agent.directory` 与 `claw4j.agent.default` | 无（纯文本） |
| **一份知识库** | 一个目录（`*.md` / `*.txt`）+ 配 `claw4j.rag.directory` | 无（切词、切块、融合都在 `claw4j-core` 的 `text` 包） |
| 一条通道 | `ChatChannel` 实现 + 自己的端点 | `claw4j-core`，**不带任何 IM SDK** |

### 1. 一个工具：三个文件

```java
// 你的 jar：com.acme.jira
@AutoConfiguration
public class JiraToolsAutoConfiguration {

    /** 模型看到的就是这个 provider 里的工具。每多一个来源就多一个 bean，谁都不让位。 */
    @Bean
    public ToolCallbackProvider jiraTools(JiraClient jira) {
        return MethodToolCallbackProvider.builder().toolObjects(new JiraTools(jira)).build();
    }

    /** 这些工具会碰到什么——结构事实。没登记的工具一律按"没权限"处理。 */
    @Bean
    public ToolPermissionRegistry jiraPermissions() {
        return ToolPermissionRegistry.of(Map.of(
                "jiraSearchIssues", ToolPermission.read(),
                "jiraCreateIssue", ToolPermission.of("create_issue")));
    }

    /** 最低需要哪一档才出现。漏了是**启动失败并点名**，不是"默认全开"。 */
    @Bean
    public ToolTierRegistry jiraToolTiers() {
        return ToolTierRegistry.sameFor(
                List.of("jiraSearchIssues", "jiraCreateIssue"), ToolTier.MESSAGING);
    }
}
```

再把类名写进 `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`，一个文件一行。

**硬规矩一：这三个 bean 都不要写 `@ConditionalOnMissingBean`。** 那条条件的语义是"容器里已经有了就让位"，
而容器里本来就有别的来源——让位的结果是你这一方的工具**静默消失**：不报错，只是模型手上没它，于是它开始编答案。

**硬规矩二：档位必须登记，而且它不是"可选的美化"。** 装配期会问清单里每一个工具属于哪一档，
查不到就启动失败并点名；`claw4j.tools.profile` 切到 `messaging` 之后，声明为 `coding` 的工具
根本不在清单里（连检索都搜不到），而不是等调了才被拒。

插件 jar 进 classpath 有两条路：加一条 Maven 依赖（要重新构建发版），或者
`java -cp app.jar -Dloader.path=/opt/claw4j/plugins org.springframework.boot.loader.launch.PropertiesLauncher`
（启动时扫一次，不是热加载）。`java -jar app.jar -Dloader.path=...` **不生效且一声不响**——
默认 `Main-Class` 是 `JarLauncher`，它不认识这个参数。

### 2. 一个 MCP server

**接别人写的：写配置，不写代码。** 一段配置 = 一条连接，`transport` 三选一（`stdio` 默认 / `http` / `sse`）：

```yaml
claw4j:
  capability:
    mcp:
      servers:
        deepwiki:                      # Streamable HTTP：连别人已经跑着的端点，不起进程
          transport: http
          url: https://mcp.deepwiki.com
        everything:                    # stdio：起一个子进程，用它的 stdin/stdout 说协议
          command: npx
          args: ["-y", "@modelcontextprotocol/server-everything"]
          inherit-env: [PATH, HOME]
          permission:
            action: read               # 默认只读、不碰任何业务表
```

- **权限可以按工具覆盖**：`tools: { send_email: { action: send_email } }`。第三方 server 常把
  "查一下"与"改一下"放在同一个进程里导出，整条一个权限只有两种结局——写动作被只读身份调用，或只读身份连读都不行。
  `tools` 里点名了 server 不导出的工具名按**启动失败**处理：拼错一个名字而静默兜底，等于让人以为加严了、其实没有。
- **子进程的环境默认是空的**（只有 `PATH`）：应用的模型 key 与数据库口令不会漏过去。
- **两个超时分开调**：`request-timeout`（单次调用，默认 30s）与 `initialization-timeout`（握手，默认 60s）。
  接 `npx` / `uvx` 这类启动器时**两个都要调大**：`npx -y @modelcontextprotocol/server-everything` 本机实测
  冷启动 **36 秒**，只调 `initialization-timeout` 没用——默认 30 秒的 `request-timeout` 会先超时。
- **凭据写句柄，不写值**：`headers: { Authorization: "Bearer secret:mcp_token" }`。
  `secret:<名字>` 里是环境变量名的小写写法，真值在每次请求（或起子进程）那一刻才换出来；
  非凭据常量用 `plain:` 前缀。裸写明文会在启动时被拒并点名到那一行。
- 配错在**启动时**失败并点名 server（stdio 没 `command`、http 没 `url`、`url` 不是 http(s)）；
  **连上了但清单为空也是失败**——"通了但一件能力都没有"不该靠调用方去发现。

**自己写 server：语言随便，协议一样。** 两个平级的样本：[`examples/mcp/`](examples/mcp) 是 Python（零第三方依赖，
导出 `demo_echo` / `demo_now`），[`claw4j-example-mcp-server`](claw4j-example-mcp-server) 是 Java（`java -jar` 就能跑，
导出 `echo` / `serverTime` / `processId`）。对宿主来说两者完全一样。最容易撞的一条：**stdout 是协议通道**——
SDK 的日志默认也走 stdout，一帧 JSON 前面插进一行日志，客户端报 `Client failed to initialize`，而 server 侧看起来正常。

值需要**算出来**的时候（路径要拼、token 从 vault 取、传输要包一层）再写一个 `McpServerEndpoint` bean，
写法在 [`docs/design/extension-points.md`](docs/design/extension-points.md)。

### 3. 一个脚本包

一个目录就是一个工具，工具名 = 目录名：

```text
examples/scripts/weather-forecast/
  package.properties     # description=…  entrypoint=run.sh  timeoutMillis=20000
  run.sh                 # 入口；参数是 argv 数组，不经 shell
```

配 `--claw4j.scripts.directory=examples/scripts`。**权限动作是 `run_script`**，与 `read` 是两个口径：
只读角色能查数，但"能执行脚本"宽得多（脚本可以写文件、可以往外连），默认三个角色里只有 `admin` 有这个动作。
脚本本体**不进上下文**，进入上下文的只有标准输出；子进程只拿得到 `PATH`，**拿不到任何凭据**——
需要 key 的能力在这一层跑不通，该走 MCP。

### 4. 一个 Skill

一个含 `SKILL.md` 的目录，front matter 只有 `name` 与 `description`：

```markdown
---
name: weekly-sales
description: 出一份上周各区成交额周报。用户问“上周卖得怎么样”时用它。
---
## 口径
- 成交额 = `sum(order_item.amount)`，`status <> 'CANCELLED'`……
```

配 `--claw4j.skills.directory=examples/skills`。**渐进式披露**：平时模型只看到名字与一行描述，
命中之后正文才作为一次工具调用的结果进来。它**不提新工具**，只决定"现有工具怎么用"——
真正碰表那一步仍按表级与动作级各自鉴权。工具名 `Skill`，权限动作 `use_skill`。

### 5. 一个子 Agent

一份 `*.md`，front matter 四个键：`name` / `description` / `tools` / `disallowedTools`，正文是它自己的系统提示词。

```markdown
---
name: capacity-triage
description: 判断这个进程的容量是不是吃紧了。用户问“要不要扩容”时派它。
tools:
  - systemStatus
---
```

配 `--claw4j.subagents.directory=examples/subagents`。工具名 `Task`，权限动作 `delegate_task`。
三种形状照抄比从零写快：白名单单工具（`capacity-triage`）、白名单跨层（`weekly-digest` 点了
`querySales` + 脚本包 `summarize`）、继承全部再减（`incident-triage` 用 `disallowedTools` 减掉两个）。
白名单点了容器里没有的工具、或点了 `Task` 自己，都是**启动失败**。

### 6. 一个 agent（人设）

**这一段决定"这个部署是谁"。** 同一个 jar，两次部署只改配置，一个变销售助手、一个变运维助手。

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

配 `--claw4j.agent.directory=examples/agents --claw4j.agent.default=sales`。

- `tier` 覆盖全局 `claw4j.tools.profile`，**只覆盖档位、不覆盖 allow / deny**：档位是"这个助手允许多宽"，
  排除项是"这套部署额外不要什么"。销售助手永远不该跑脚本，哪怕部署的全局档位是 `full`。
- `glossary` 是**引用**，几份人设可以指向同一个文件。口径改一处，所有引用它的 agent 同时变——
  同一段话抄两遍的后果是可预见的：改一处必漏一处，而漏掉的那处会让两个助手对同一个数给出两种说法。
- **时间不用写进正文**：框架每轮拼一句"现在是几点、哪个时区"。写在正文里的时间是死的，
  而进程跑一周之后它还是启动那天。时区取 `claw4j.agent.zone`（默认 `Asia/Shanghai`），
  不取容器默认——那里通常是 UTC，晚上八点之后"今天"就已经差一天。

不配这个目录也能跑，用的是内置的最小可用人设。目录里有多份却没写 `default` 是**启动失败**，
并列出候选：一个进程只有一个 agent，猜错的表现是"这个部署怎么说话像销售"，没有任何报错。

### 7. 一条通道

```java
@AutoConfiguration
@ConditionalOnProperty(prefix = "acme.channel.telegram", name = "enabled", havingValue = "true")
public class TelegramChannelAutoConfiguration {
    @Bean(initMethod = "start", destroyMethod = "stop")
    public TelegramChannel telegramChannel(AgentPort agentPort) {
        return new TelegramChannel(agentPort, ...);   // 收消息 → 认出是谁 → 交给 AgentPort → 回推
    }
}
```

通道只要三件事：**拿到消息尽快 ACK**（IM 平台都有超时重推）、**用事件 id 做幂等**、**认不出人就拒绝**。
这三条写进了 [`ChatChannel`](claw4j-core/src/main/java/io/github/claw4j/channel/spi/ChatChannel.java) 的契约；
"ACK 之后怎么办"（去重、认人、异步、分段回推、失败也回一句）在
[`channel.kernel`](claw4j-core/src/main/java/io/github/claw4j/channel/kernel)——它在 `claw4j-core` 里，
所以接 Telegram 不必把飞书 SDK 拖进 classpath。飞书、企业微信、钉钉、Slack 都是凭据齐全才装配，
缺一个整个通道退让、HTTP 照常。
四家的差别只在"怎么收"与"怎么发"：飞书是 SDK 长连接，企业微信是自己验签解密的加密 XML 回调，
钉钉是自己验签的明文 JSON 回调，Slack 是 Events API 的明文 JSON 回调；一个平台一个包，
编排层不认识它们中的任何一个。

**钉钉这一条要说清一句话**：报文是明文的，所以 header 里那个签名是<b>唯一的门</b>。
验签与"时间戳相差不超过 1 小时"两条缺一不可——只验签名不验时间戳，等于让一对截获的
`(timestamp, sign)` 永远可用。这一条由 `DingtalkChannelTest` 用两条反例钉着
（签名错的报文合法、签名对但时间戳在两小时前）。

**Slack 有三句话要说**。① 它的签名覆盖**整个原始报文**（`v0:timestamp:body`），不只是时间戳，
所以控制器收的是 `String`——反序列化再序列化一遍就永远验不过，而症状看起来像密钥配错。
② **3 秒收不到 2xx 的代价不只是重推**：60 分钟内失败率超过 95%，整个事件订阅会被平台临时禁用，
所以这个端点里只做验签、解析、交给 `InboundDispatcher` 三件很轻的事。
③ **Slack 会把我们自己发出去的消息再推回来**（带 `bot_id`），被编辑的消息也会（`subtype`）。
不先挡这两样，一次提问会变成一问一答的无限循环——它不报错，只在账单上体现，
这是前四个通道都没有过的问题。另外答案默认回**线程**（`thread_ts` 是原消息的 ts），
不然回执加答案会把频道刷满；这一整套由 `SlackChannelTest` 钉着。

**调用量是有限的**：钉钉对机器人有调用量上限（超了会返回 20001 一类
"受调用量影响服务暂停"的错误码，要升级套餐或买增购包）。一个企业里人人来问的部署，
这件事要提前知道，而不是等第一次被限流。会话按对话隔离，但调用次数按企业算。

## 配置

凭据一律走环境变量（仓库根目录的 `.env` 已被忽略）：

| 变量 | 必填 | 说明 |
| --- | --- | --- |
| `DEEPSEEK_API_KEY` | 是 | 主模型 key，缺失即启动失败 |
| `QWEN_API_KEY` | 否 | 备用模型 key；也是向量凭据（语义缓存与知识库共用），配了才有主备降级与缓存 |
| `CLAW4J_MODEL_PRIMARY` | 否 | `deepseek`（默认）/ `qwen`，决定谁是主模型 |
| `FEISHU_APP_ID` / `FEISHU_APP_SECRET` | 否 | 两个都配才装配长连接通道 |
| `WECOM_CORP_ID` / `WECOM_CORP_SECRET` / `WECOM_AGENT_ID` | 否 | 企业微信自建应用，三个都配才有出站 |
| `WECOM_TOKEN` / `WECOM_ENCODING_AES_KEY` | 否 | 回调的签名与解密；与上面三个**一起**齐了才装配通道 |
| `DINGTALK_CLIENT_ID` / `DINGTALK_CLIENT_SECRET` / `DINGTALK_ROBOT_CODE` | 否 | 钉钉机器人，三个都配才装配。**client-secret 同时是验签的密钥**，配错既发不出也收不进 |
| `SLACK_SIGNING_SECRET` / `SLACK_BOT_TOKEN` | 否 | Slack，两个都配才装配。**两把各管一头**：signing-secret 只验签，bot-token（`xoxb-` 开头、长期有效）只发消息。混了的表现是"回调全被拒"或"收得到发不出" |
| `CLAW4J_RAG_ENABLED` | 否 | 打开知识库。**开了就必须配目录**，否则启动失败（空知识库比失败更糟） |
| `CLAW4J_RAG_DIRECTORY` | 否 | 文档目录，启动后同步一次，按文件内容哈希跳过没变的 |
| `CLAW4J_OBSERVABILITY_ENABLED` / `OTLP_ENDPOINT` | 否 | trace 导出，**两个都要**：只开开关不配端点时一条都不导，启动日志会点名缺哪一项（不回落 `localhost:4318`，否则表现成"连不上"而不是"没配"）。指标仍走 actuator，trace 只走 OTLP |
| `CLAW4J_POSTGRES_IMAGE` | 否 | 换 Postgres 镜像源，与 compose 共用 |

`claw4j.*` 的每个键都有明确的拥有者，`application.yml` 里逐项写了默认值与"动它之前要知道什么"。
最常改的几个：`claw4j.channel.http.roles`、`claw4j.guard.sensitive-words`、`claw4j.cache.*`、`claw4j.rag.*`、
以及三个能力目录 `claw4j.{scripts,skills,subagents}.directory`。

## 模块

```
   channel ──► core ◄── agent        两条边互不依赖：换通道不用碰 agent，反之亦然
                 ▲
                 │
            capability                能力层只认 core 的契约
                 ▲
                 │
                app                  唯一知道所有模块的地方：接线只发生在这一层
```

| 模块 | 职责 |
| --- | --- |
| `claw4j-core` | 契约与不变量：`domain`（端口、`Principal`、权限登记表）+ `channel.spi` + `channel.kernel`。**编译期只有 slf4j**，接插件与接通道都只认这一个坐标（[ADR-0015](docs/adr/0015-one-core-artifact.md)） |
| `claw4j-agent` | ChatClient 装配、模型路由、Advisor 链、会话与缓存。不认识任何通道，也不认识任何工具类 |
| `claw4j-channel` | 入站出站适配（HTTP+SSE、飞书长连接、企业微信回调、钉钉回调），按平台分包 |
| `claw4j-capability` | 能力来源的接缝：MCP、脚本包、Skill、子 Agent、生成器，加一个平台无关的 `system` 工具 |
| `claw4j-example-sales` | **示例插件**：一个业务工具 + 四张业务表 + 种子数据，不依赖 `claw4j-capability` |
| `claw4j-example-mcp-server` | **示例插件**：`java -jar` 就能跑的 Java MCP server，不依赖 Claw4J 任何模块 |
| `claw4j-app` | 启动与装配，唯一打 fat jar 的模块 |

最关键的方向是 `channel → core ← agent`：两边互不依赖，接线只发生在 `app`。两个 example 模块对
`claw4j-app` 的依赖是**唯一**的接线点——"删掉那一行、框架照常构建启动"正是插件契约能自证的唯一方式。

## 测试

```bash
./scripts/verify.sh     # 载入 .env → clean verify（全部用例 + 覆盖率下限）→ 用例数、跳过数、文件数核对
```

门禁跑的是替身与真容器（真模型只留一条，验的是"一句话能不能翻成一次对的工具调用"），
能证明"装配成立"，证明不了"能力真能用"。后者要一次真跑，
**按工具逐条**给出命令与期望值在 **[`docs/capability-tests.md`](docs/capability-tests.md)**：
本仓库的 23 个工具每个一条（10 个进程内 + 10 个自建 MCP + 3 个脚本包），
外加两个第三方 MCP server 的"先取清单再点名"、5 个子 Agent、流式与非流式的成对调法、
Java 与非 Java 实现的成对接口，以及越权与拦截的负向用例。

每条用例的判定不看模型措辞，看 `claw4j.tool.calls{tool=…,outcome=ok|denied|error}` 这个计数——
工具是渐进式披露的，问模型"你有哪些工具"只能拿到检索工具，列不全，所以要一条一条点名调。

## 文档

| 要找什么 | 在哪 |
| --- | --- |
| 怎么跑、门禁有哪些、迁移怎么加 | [CONTRIBUTING.md](CONTRIBUTING.md) |
| 某个能力怎么验、期望值是什么 | [docs/capability-tests.md](docs/capability-tests.md) |
| 扩展点的完整模板、三种静默失效的写法 | [docs/design/extension-points.md](docs/design/extension-points.md) |
| 为什么这么设计、否决了什么 | [docs/adr/](docs/adr/) |
| 能力层、鉴权、会话、缓存的设计说明 | [docs/design/](docs/design/) |
| 踩过的坑（代码里看不出来，只会再犯一次） | [docs/design/pitfalls.md](docs/design/pitfalls.md) |
| 示例素材（脚本包、Skill、Subagent、Python MCP） | [examples/README.md](examples/README.md) |
| 术语 | [GLOSSARY.md](GLOSSARY.md) |

## 与 OpenClaw 的关系

OpenClaw 是单用户常驻的个人助手（Gateway 控制面 + 浏览器 / 语音 / 手机节点做外设）；
Claw4J 是企业里那一层：多用户身份、权限在模型之前裁决、可嵌在网关后面。两者形态不同，
同一个功能落在两边判定可以相反，所以**不抄它的功能清单**，逐条的取舍见
[ADR-0021](docs/adr/0021-explicitly-not-doing.md)。截至现在的对照：

| | 内容 |
| --- | --- |
| **已达** | 多用户 + 权限前移（越权不花 token、不进历史）；三层能力 21 个工具（进程内 8 / 自建 MCP 10 / 脚本包 3）+ 渐进式披露；Skill 生成链路与 5 个子 Agent；会话记忆（按人 + 权限 + 对话隔离）与语义缓存（pgvector）；HTTP+SSE 与飞书长连接、企业微信回调三条通道；**一轮一个本地生成的 `runId`（不落表、不能续跑，连续性靠会话）与挂在目标上的 token 预算（撞线进 `budget_limited`，`/goal resume` 开新窗口），外加工具调用熔断（单工具 3 次、整轮默认 6 次，只止损、不改持久状态）**；**结果投递（回到它开始的那段对话：地址在入站消息上、没有登记表，按 `notifyPolicy` 三档决定推不推）**；**审批（权限的第三个分量：参数指纹让批准只覆盖"这一次具体要干的事"，没有批准就回一句"要先审批"并把审批编号交回用户、这一轮当场结束、不占线程；批准或拒绝之后都用发起人身份快照在同一段对话里开新一轮，一次点头只放行一次执行、默认十分钟过期；入口是独立的人名单 + 飞书卡片一键批/拒 + 同一条服务端逻辑的 HTTP 接口，自己发起的批不动而别人还能批）**；**审计（一行 = 一次动作的开始或结束，不按 run 组织：`agent.run.started / agent.run.finished / tool.action.started / tool.action.finished` 四类、闭合状态词表、30 天 / 10 万行上限，按人、会话、run、结局四维过滤；**只记元数据**——不落提示词、消息体、工具参数、工具结果与原始错误，工具调用只留单向指纹；被拒的那次只有一条 `blocked`、没有「开始」，因为它根本没开始；等审批不写审计，那件事的真相在 `approval_request` 上；清理在启动时、每小时、每 500 次写入各一次）**；**定时执行（一张调度表 + 每 30 秒扫一次，`for update skip locked` 认领所以两个实例不会跑两遍；到点只做一件事——开一轮，鉴权/工具/预算/投递全部复用现成路径；错过的时间点不补跑、连着三次没跑成就停用并推一条带最后错误的通知；任务由对话里的三个工具建/列/取消，建的时候就把"要用的工具主人现在都有"核完，**权限按到点那一刻的角色表重算而不是用创建时的快照**——收权对定时任务是有效的，角色被删就当场停用并通知主人，每次运行的消耗计入这段对话那个目标的预算）**；**企业微信通道（自建应用回调：验签 → 解密 → 去重 → 认人 → 异步 → 回推，五秒内回空串、先推一句回执再推答案；出站是一次 `POST`，token 缓存两小时、被判过期重试一次；投递地址来自入站消息，结果只回到它开始的那段对话、不回退到"最近用过的通道"）**；单 Postgres + Flyway 可审计；真模型测试与 7 模块 ArchUnit 门禁 |
| **未达（有票）** | 更多通道（P12/P13）、凭据句柄（P08）、知识库检索（P10）、反向 MCP（P11）、OTel 导出（P14）、脚本沙箱隔离（P17）、`doctor` 自检（P18）、WebSocket 传输（P19）——编号见 [ADR-0020](docs/adr/0020-platform-capabilities-ride-on-run.md)。另外**仓库里仍没有随包工具登记成"要审批"**（MCP 的 `permission` 只有 `action` / `tables`，`Approval` 恒为 `NONE`），所以审批链路要靠测试造请求才走得完 |
| **刻意不做** | 浏览器自动化、语音 / Canvas、设备节点、配对码、`heartbeat` 常驻自唤醒、插件市场——理由与重开条件见 [ADR-0021](docs/adr/0021-explicitly-not-doing.md) |

## 还没做的

写在这里是因为它们会影响上面那些承诺的强度，而不是自谦：

- **MCP 的凭据只有"静态请求头"**：需要浏览器跳转、刷新 token、按用户授权的端点（OAuth 授权码流）接不了。
- **MCP 的出口只有 stdio**：`spring-ai-starter-mcp-server-webmvc` 的 2.0.2 不在本地仓库里，
  离线构建拉不到，所以反向 HTTP / SSE 那一半没做；也没有 WebSocket。stdio 天生只对一个客户端。
  另外外部调用**没有 run**，因此不落审计、不扣 run 预算，只留一行日志。

## License

[MIT](LICENSE) © 2024 OpenClaw4J
