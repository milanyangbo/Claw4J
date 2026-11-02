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
| **三条通道，同一份逻辑** | HTTP+SSE（给网关/前端）、飞书长连接（给群聊）、企业微信自建应用（给企业内）；通道只做"收进来 → 认出是谁 → 发出去"，去重/认人/异步/回推只有一份 |
| **五种能力来源，对模型是一回事** | Java `@Tool`、MCP server（stdio / HTTP 都行）、受限脚本包、Agent Skill、子 Agent |
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
| `read-only`（缺省） | `*` | `read`、`use_skill`、`delegate_task`、`read_system`、`recall_session`、`update_plan`、`schedule_task`、`list_schedules`、`cancel_schedule` |
| `guest` | `region` | `read`、`use_skill`、`delegate_task` |
| `admin` | `*` | `*` |

动作名与工具登记的动作同口径：`read`（读表）、`use_skill`、`delegate_task`、`read_system`、
`recall_session`、`update_plan`（改自己这一件事的计划）、`schedule_task`（定一个以后按点做的任务）、
`list_schedules`、`cancel_schedule`（只碰自己的任务，按归属键判、不按编号判）、`run_script`（跑脚本包）、
`draft_script`（交脚本草稿）、`send_email`（MCP 按工具覆盖时用）。
表里没有的角色一律拒绝，没有默认角色兜底。**会话记忆按 `userId` + 权限指纹落库**（`SessionKeys`）：换一条通道进来还是同一个人，但**换一份权限就是另一段会话**——降权之后，历史里躺着的数字读不到，而不是还记得（ADR-0007 的边界在会话这层同样成立）。

**定时任务存的是身份（`userId` + 角色名），不是权限快照**：到点那一刻按当前的角色表重算一遍，
所以 `schedule_task` 永远不比这个人现在能做的更多——把这个角色删掉（或把某个工具的动作收走），
任务下一次到点就失败并自动停用，同时通知任务主人。

## 集成

五种能力 + 一条新通道，都是自动配置或一段配置——**不改 Claw4J 的代码，也不在父 POM 里登记**。
能整份照抄的模板与三种"静默失效"的写法在 [`docs/design/extension-points.md`](docs/design/extension-points.md)。

| 你要加的东西 | 写什么 | 依赖 |
| --- | --- | --- |
| 一个工具 | `@Tool` 方法 + 两个 bean + `AutoConfiguration.imports` | `claw4j-core`、`spring-ai-client-chat` |
| 一个 MCP server | 一段 YAML；server 用任何语言写，宿主侧不写代码 | 宿主侧 `claw4j-capability`；**server 侧零依赖** |
| 一个脚本包 | 一个目录（`package.properties` + 入口脚本），配 `claw4j.scripts.directory` | 无 |
| 一个 Skill | 一个含 `SKILL.md` 的目录，配 `claw4j.skills.directory` | 无（纯文本） |
| 一个子 Agent | 一份 `*.md`（front matter + 正文），配 `claw4j.subagents.directory` | 无（纯文本） |
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
}
```

再把类名写进 `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`，一个文件一行。

**硬规矩：这两个 bean 都不要写 `@ConditionalOnMissingBean`。** 那条条件的语义是"容器里已经有了就让位"，
而容器里本来就有别的来源——让位的结果是你这一方的工具**静默消失**：不报错，只是模型手上没它，于是它开始编答案。

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
- 凭据写成占位符：`headers: { Authorization: "Bearer ${MCP_TOKEN}" }`。
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

### 6. 一条通道

```java
@AutoConfiguration
@ConditionalOnProperty(prefix = "acme.channel.dingtalk", name = "enabled", havingValue = "true")
public class DingTalkChannelAutoConfiguration {
    @Bean(initMethod = "start", destroyMethod = "stop")
    public DingTalkChannel dingTalkChannel(AgentPort agentPort) {
        return new DingTalkChannel(agentPort, ...);   // 收消息 → 认出是谁 → 交给 AgentPort → 回推
    }
}
```

通道只要三件事：**拿到消息尽快 ACK**（IM 平台都有超时重推）、**用事件 id 做幂等**、**认不出人就拒绝**。
这三条写进了 [`ChatChannel`](claw4j-core/src/main/java/io/github/claw4j/channel/spi/ChatChannel.java) 的契约；
"ACK 之后怎么办"（去重、认人、异步、分段回推、失败也回一句）在
[`channel.kernel`](claw4j-core/src/main/java/io/github/claw4j/channel/kernel)——它在 `claw4j-core` 里，
所以接 Telegram 不必把飞书 SDK 拖进 classpath。飞书与企业微信都是凭据齐全才装配，缺一个整个通道退让、HTTP 照常。
两家的差别只在"怎么收"与"怎么发"：飞书是 SDK 长连接，企业微信是自己验签解密的回调端点；
一个平台一个包，编排层不认识它们中的任何一个。

## 配置

凭据一律走环境变量（仓库根目录的 `.env` 已被忽略）：

| 变量 | 必填 | 说明 |
| --- | --- | --- |
| `DEEPSEEK_API_KEY` | 是 | 主模型 key，缺失即启动失败 |
| `QWEN_API_KEY` | 否 | 备用模型 key；也是语义缓存的向量凭据，配了才有主备降级与缓存 |
| `CLAW4J_MODEL_PRIMARY` | 否 | `deepseek`（默认）/ `qwen`，决定谁是主模型 |
| `FEISHU_APP_ID` / `FEISHU_APP_SECRET` | 否 | 两个都配才装配长连接通道 |
| `WECOM_CORP_ID` / `WECOM_CORP_SECRET` / `WECOM_AGENT_ID` | 否 | 企业微信自建应用，三个都配才有出站 |
| `WECOM_TOKEN` / `WECOM_ENCODING_AES_KEY` | 否 | 回调的签名与解密；与上面三个**一起**齐了才装配通道 |
| `CLAW4J_POSTGRES_IMAGE` | 否 | 换 Postgres 镜像源，与 compose 共用 |

`claw4j.*` 的每个键都有明确的拥有者，`application.yml` 里逐项写了默认值与"动它之前要知道什么"。
最常改的几个：`claw4j.channel.http.roles`、`claw4j.guard.sensitive-words`、`claw4j.cache.*`、
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
| `claw4j-channel` | 入站出站适配（HTTP+SSE、飞书长连接、企业微信回调），按平台分包 |
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

门禁跑的是假模型与真容器，能证明"装配成立"，证明不了"能力真能用"。后者要一次真跑，
**按工具逐条**给出命令与期望值在 **[`docs/capability-tests.md`](docs/capability-tests.md)**：
本仓库的 21 个工具每个一条（8 个进程内 + 10 个自建 MCP + 3 个脚本包），
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
| **已达** | 多用户 + 权限前移（越权不花 token、不进历史）；三层能力 21 个工具（进程内 8 / 自建 MCP 10 / 脚本包 3）+ 渐进式披露；Skill 生成链路与 5 个子 Agent；会话记忆（按人 + 权限隔离）与语义缓存（pgvector）；HTTP+SSE 与飞书长连接、企业微信回调三条通道；**run 落库（跨请求的"继续"、计划外置到 step）与按 run 累计的三项预算（工具调用 / token / 耗时，撞线即停并把结局写进库里）**；**结果投递与回收（run 绑 principal 不绑通道：地址表一对多、按 `notifyPolicy` 推、`GET /api/runs/{id}` 取回全文）**；**审批（权限的第三个分量：参数指纹让批准只覆盖"这一次具体要干的事"，没有批准就把 run 停在 suspended、请求当场返回审批编号，不占线程；批准/拒绝之后用发起人身份续跑同一件事，一次点头只放行一次执行、默认十分钟过期；入口是独立的人名单 + 飞书卡片一键批/拒 + 同一条服务端逻辑的 HTTP 接口，自己发起的批不动而别人还能批）**；**审计（一次工具调用一行，补在 step 上而不另建执行表：`actor / actor_role / outcome / args_digest / args_summary / approval_id` 六列 + 只读视图 `claw4j_run.audit_trail`，按人、按时间、按结局都答得出；只落脱敏摘要与指纹，不落参数与产出的原文；被拦下的那次也落一行，带工具名与当时的角色；"一条批准只放行一次执行"由部分唯一索引担保；留存 90 天，到点删整件旧事，审计不会活得比它的执行记录更久）**；**定时执行（一张调度表 + 每 30 秒扫一次，`for update skip locked` 认领所以两个实例不会跑两遍；到点只做一件事——开一个 run，鉴权/工具/预算/投递全部复用现成路径；错过的时间点不补跑、连着三次没跑成就停用并推一条带最后错误的通知；任务由对话里的三个工具建/列/取消，建的时候就把"要用的工具主人现在都有"核完，**权限按到点那一刻的角色表重算而不是用创建时的快照**——收权对定时任务是有效的，角色被删就当场停用并通知主人，每次运行各记一笔预算）**；**企业微信通道（自建应用回调：验签 → 解密 → 去重 → 认人 → 异步 → 回推，五秒内回空串、先推一句回执再推答案；出站是一次 `POST`，token 缓存两小时、被判过期重试一次；投递与飞书共用那张地址表，所以在飞书问、企微也能收到同一条 run 的结果）**；单 Postgres + Flyway 可审计；真模型测试与 7 模块 ArchUnit 门禁 |
| **未达（有票）** | 更多通道（P12/P13）、凭据句柄（P08）、知识库检索（P10）、反向 MCP（P11）、OTel 导出（P14）、脚本沙箱隔离（P17）、`doctor` 自检（P18）、WebSocket 传输（P19）——编号见 [ADR-0020](docs/adr/0020-platform-capabilities-ride-on-run.md)。另外**仓库里仍没有随包工具登记成"要审批"**（MCP 的 `permission` 只有 `action` / `tables`，`Approval` 恒为 `NONE`），所以审批链路要靠测试造请求才走得完 |
| **刻意不做** | 浏览器自动化、语音 / Canvas、设备节点、配对码、`heartbeat` 常驻自唤醒、插件市场——理由与重开条件见 [ADR-0021](docs/adr/0021-explicitly-not-doing.md) |

## 还没做的

写在这里是因为它们会影响上面那些承诺的强度，而不是自谦：

- **插件与核心的版本兼容口径没定义**：没有任何版本校验，插件依赖旧版 `claw4j-core` 而核心接口一变，
  就是运行期 `NoSuchMethodError`，而不是启动时一句"这个插件要求核心 ≥ x.y"。现在只能靠"同时升级"。
- **MCP 的凭据只有"静态请求头"**：需要浏览器跳转、刷新 token、按用户授权的端点（OAuth 授权码流）接不了。
- **MCP 只支持 stdio 与 http/sse**：没有 WebSocket，也没有"宿主反向被调用"那一半。

## License

[MIT](LICENSE) © 2024 OpenClaw4J
