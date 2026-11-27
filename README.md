# Claw4J

![License: MIT](https://img.shields.io/badge/License-MIT-green.svg)
![JDK](https://img.shields.io/badge/JDK-21-blue.svg)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.1.2-brightgreen.svg)
![Spring AI](https://img.shields.io/badge/Spring%20AI-2.0.2-brightgreen.svg)

跑在 Spring AI 2.x 上的**企业 Agent 骨架**：把模型、IM 和内部系统接成一条能上生产的链路——
对话收进来、能力接上去、工具清单卡在模型之前、每一步留下能查的痕迹。

企业里通常已经有模型 API、有 IM、有一堆内部系统，缺的是中间那一层，Claw4J 就是那一层。
它**不是某个业务的问答机器人**：业务能力都是插件，仓库自带的销售工具只是一个示例实现。

三条边界：**不做登录体系**（认证在网关，通道只负责"认出是谁"）；**不自己造插件机制**
（用 Boot 自动配置，一个 jar 就是插件，同进程可信代码）；**核心不含业务**。

## 特性

- **五条通道，同一份逻辑** —— HTTP+SSE、飞书长连接、企业微信、钉钉、Slack；通道只做
  "收进来 → 认出是谁 → 发出去"，去重 / 认人 / 异步 / 回推只有一份
- **四种能力来源，对模型是一回事** —— Java `@Tool`、MCP server（stdio / HTTP）、Skill（可带脚本）、子 Agent
- **工具清单在模型之前** —— 按部署画像与 allow / deny 筛出这一轮能看见的工具，看不见的它无从计划；
  需要人点头的命令在执行前停下，批了才跑，跑完留一条带审批编号的审计
- **知识库检索带出处** —— 混合检索（向量 + 全文）加 `qwen3-rerank` 重排，每段都带「文档 + 第几段」
- **一轮一个 trace** —— 配一个 OTLP 端点就导出整条链路，几个头属性与审计对得上

## 快速开始

前置：**JDK 21**、**Docker**（数据库）、**python3**（示例 MCP server）、一个 `DEEPSEEK_API_KEY`。

```bash
git clone git@github.com:milanyangbo/Claw4J.git && cd Claw4J
echo 'DEEPSEEK_API_KEY=sk-xxxxxxxx' > .env

# 本地配置模板：可能放真实 key 与真实的人，所以只在本地存在
cp claw4j-app/src/main/resources/application-local.yml.example \
   claw4j-app/src/main/resources/application-local.yml

./mvnw -q -DskipTests package
java -jar claw4j-app/target/claw4j-app-0.1.0-SNAPSHOT.jar --spring.profiles.active=local
```

**起之前先打开那份 `application-local.yml`，把你的用户名填进 `claw4j.channel.http.users`。**
模板里是空列表，而空的意思是**谁都不能进**——不填这一步，第一次请求就是 401。

`local` profile 会按 `compose.yaml` 拉起 Postgres(pgvector) 与 Redis Stack，退出时回收；
compose 文件在仓库根目录，所以要在根目录启动。

起来就问（`X-Claw4j-User` 必填，且必须在上面那份名单里；`X-Claw4j-Chat-Id` 可选，缺省回落到用户名）：

```bash
curl -s -X POST http://localhost:8080/api/chat \
  -H 'Content-Type: application/json' -H 'X-Claw4j-User: ethan' \
  -d '{"text":"这个进程现在堆内存上限是多少？线程数呢？"}'
# {"chatId":"http:ethan","text":"堆内存：已用 … / 上限 …；处理器 … 核；线程数 …；已运行 … 秒"}
```

几个容易卡住的点：

- **401 不是 key 配错了**：是这个人不在 `claw4j.channel.http.users` 里，或者根本没带 `X-Claw4j-User`。
- 国内拉不到官方镜像：在 `.env` 里设 `CLAW4J_POSTGRES_IMAGE` / `CLAW4J_REDIS_IMAGE` 换镜像站。
- Redis 必须是 **Stack**（带 RediSearch），普通 `redis` 建不出向量索引，语义缓存启动即失败。
- 没有 key 应用**起不来**，不降级——留 mock 兜底只会得到"跑着回声模型却以为在跑真模型"。
- 健康检查在另一个端口：`curl -s localhost:9090/actuator/health`。

## 使用

### HTTP 接口

| 接口 | 方法 | 说明 |
| --- | --- | --- |
| `/api/chat` | POST | 同步问答，返回 `{"chatId":"...","text":"..."}` |
| `/api/chat/stream` | POST | 流式问答，SSE，事件名 `chunk` / `done` |
| `/api/approvals/{id}` | GET | 读一条待批请求现在的样子 |
| `/api/approvals/{id}` | POST | 批或拒：`{"approved":true}` / `{"approved":true,"always":true}` / `{"approved":false,"reason":"…"}` |

请求体只有 `{"text":"..."}`；请求头两个：`X-Claw4j-User`（必填）、`X-Claw4j-Chat-Id`（可选）。
错误码：`401` 认不出人、`400` 请求体不合法、`503` 流式线程池已满；审批那两个接口另有
`403` 没资格批、`404` 编号不存在、`409` 这个按钮已经不作数（过期、已被批过、不接受长期放行）。

```bash
# 流式：客户端断开即中断背后的模型调用
curl -N -X POST http://localhost:8080/api/chat/stream \
  -H 'Content-Type: application/json' -H 'X-Claw4j-User: ethan' \
  -d '{"text":"2026-11-16 到 2026-11-22 华东的成交额是多少"}'
# event:chunk / data:该区间华东成交额是 128,400.00…… / event:done
```

### 身份与权限

四层，从上到下。**没有角色请求头**——进来之后能做什么，全部由配置决定。

**① 谁能进来。** 认证在网关，这里只认 `X-Claw4j-User`，并要求它在通道白名单里
（`claw4j.channel.http.users`，每个 IM 通道各有一份）。空 = 谁都不能进，`["*"]` = 不设准入。

**② 他能看见哪些工具。** `claw4j.tools.profile` 选画像：

| 画像 | 包含什么 |
| --- | --- |
| `minimal` | 地基：检索、问人、回忆会话、记计划、读自己的状态 |
| `messaging` | 地基 + 外部工具；**碰不到机器**，没有 shell |
| `coding` | 全部内置能力 + 外部工具 |
| `full`（默认） | 不筛 |

再叠三个列表：`allow`（**只能在画像内收窄**，写了画像里没有的工具不生效）、
`alsoAllow`（**显式放宽**，能突破画像，比如 messaging 但放行 `Bash`）、`deny`（最后生效，压过前两者）。
还可以按模型（`byProvider`）与发送者（`toolsBySender`）继续往下减。
**看不见的工具不在清单里**，模型不会"先计划再撞墙"，也不会出现"我没有权限"这种话术。

模型手上默认是两类：六个**常驻基座**（`Read` / `Grep` / `Glob` / `Bash` / `WebFetch` / `TodoWrite`，
`claw4j.advisor.tool-search.pinned`）加一个 `toolSearchTool`；其余工具要搜一次才拿得到。搜得准不准由
`claw4j.advisor.tool-search.index` 决定：`hybrid`（默认，关键字 ∪ 向量按名次融合）、`lucene`（全文打分，
中文按字切）。两档都读中文译文；`hybrid` 要一份**工具专用**的向量库（`claw4j_tool.tool_index`，
由 `EmbeddingModel` 建）——它不注册成 bean，也不与知识库共用表，共用会让工具描述跟着知识库的召回
进业务问答。纯向量不再是单独一档：`hybrid` + `keyword-weight: 0` 就是它。档位只影响
"搜到什么"，**不放宽任何一层可见性**——索引里只有本来就在清单里的工具。
相似度管不了的那类确定性别交给它猜：`claw4j.advisor.tool-search.rules` 可以写
「命中这句正则就把这几个工具置顶」，默认空。检索层不判「这一轮该不该检索」——
寒暄、空查询都照常拿到候选，克制的责任在模型（对应 BFCL 的 `irrelevance` 档）。

**③ 哪些要人批。** 两类，互相独立：

- **命令**：`claw4j.tools.exec.security` × `ask`。默认 `full` + `off`，一条都不问；
  `security: allowlist` + `ask: on-miss` 只在白名单未命中时问；`ask: always` 每条都问。
  白名单是 `claw4j.tools.exec.allow`（整条命令前缀匹配）；批过"以后都放行"的进库，按命令段匹配。
- **其它工具**：`claw4j.approval.required-tools` 按工具名，默认空（随包工具一个都不声明）。

**④ 谁能批。** `claw4j.approval.approvers`：空 = 没人能批，请求会一路挂到过期；
**自己提的不能自己批**；有效期 `claw4j.approval.ttl` 默认 10 分钟，过期不补点。入口有两个——通道里的卡片，
或 `POST /api/approvals/{id}`，两者走同一套资格判定。批了之后会在**发起人那段对话**里接着做。
"以后都放行"只对那一段命令生效，而且内联求值（`bash -c "…"`）与 heredoc 不接受持久放行。

**多用户部署要改的两处：**

- `claw4j.tools.sessions.visibility` 默认是 `all`，也就是 `recall_session` 能翻到别人的会话；
  改成 `self`。这是它的默认值，不是 bug。
- 会话按「人 + 通道 + 对话 + 段」落库：同一个人换通道、换对话就是另一段记忆。
  段**默认不会自己结束**（`claw4j.advisor.session.reset.mode: none`，靠上下文压缩管长度）；要按天或空闲切段，
  配成 `daily-and-idle`（默认 4 点换天、空闲 120 分钟），切了之后记忆与额度从零开始。

## 扩展

加一个能力或一条通道，都是自动配置或一段配置——**不改 Claw4J 的代码，也不在父 POM 里登记**。
每一步怎么做、代码长什么样，见 **[`README-adding-capabilities.md`](README-adding-capabilities.md)**（九个能直接抄的例子）。

想先看它**串起来是什么样**（三场戏、每场五幕，从客户开口到事后审计）：
**[`README-demonstration.md`](README-demonstration.md)**。加完东西要逐条验：
**[`README-capability-tests.md`](README-capability-tests.md)**。

| 你要加的东西 | 写什么 | 例子 |
| --- | --- | --- |
| 一个工具 | `@Tool` 方法 + 自动配置 + `AutoConfiguration.imports` | [1](README-adding-capabilities.md#1-java-工具4-个文件) |
| 一个 MCP server | 一段 YAML，server 用任何语言写，宿主侧不写代码 | [2](README-adding-capabilities.md#2-mcp-server一段-yaml) |
| 技能里的脚本 | 技能目录下的 `scripts/`，`SKILL.md` 写清怎么跑，模型用 `Bash` 跑 | [3](README-adding-capabilities.md#3-技能里的脚本一个目录--skillmd) |
| 一个 Skill | 一个含 `SKILL.md` 的目录，配 `claw4j.skills.directory` | [4](README-adding-capabilities.md#4-skill一份做法) |
| 一个子 Agent | 一份 `*.md`，配 `claw4j.subagents.directory` | [5](README-adding-capabilities.md#5-子-agent一份-md) |
| 一个人设 | 一份 `*.md`，配 `claw4j.agent.directory` 与 `claw4j.agent.default` | [6](README-adding-capabilities.md#6-agent人设这个部署是谁) |
| 一份知识库 | 一个 `*.md` / `*.txt` 目录，配 `claw4j.rag.directory` | [7](README-adding-capabilities.md#7-知识库一个目录) |
| 一条通道 | `ChatChannel` 实现 + 自己的端点，不带任何 IM SDK | [8](README-adding-capabilities.md#8-通道收认人发) |
| 工具观测器 | 一个 `ToolCallListener` bean，工具被调时同步收到事件 | [9](README-adding-capabilities.md#9-工具观测器一个-bean) |

两种情况会**启动失败并点名**，不是默认放行：工具没声明档位；插件没在
`META-INF/claw4j-plugin.properties` 里报它要的核心版本。

## 配置

凭据一律走环境变量（仓库根目录的 `.env` 已被忽略）。

| 变量 | 必填 | 说明 |
| --- | --- | --- |
| `DEEPSEEK_API_KEY` | 是 | 主模型 key，缺失即启动失败 |
| `QWEN_API_KEY` | 否 | 备用模型；也是向量凭据（语义缓存与知识库共用） |
| `FEISHU_APP_ID` / `FEISHU_APP_SECRET` | 否 | 两个都配才装配飞书长连接 |
| `WECOM_*`（五个） | 否 | 企业微信自建应用，五个齐了才装配 |
| `DINGTALK_CLIENT_ID` / `DINGTALK_CLIENT_SECRET` / `DINGTALK_ROBOT_CODE` | 否 | 钉钉机器人；client-secret 同时是验签密钥 |
| `SLACK_SIGNING_SECRET` / `SLACK_BOT_TOKEN` | 否 | 前者只验签、后者只发消息，混了就是"回调全被拒"或"收得到发不出" |
| `CLAW4J_TOOLS_PROFILE` | 否 | 工具画像，默认 `full`；多用户部署通常收到 `messaging` |
| `CLAW4J_SESSION_VISIBILITY` | 否 | `recall_session` 能翻到哪些会话，默认 `all`；多用户部署改成 `self` |
| `CLAW4J_RAG_ENABLED` / `CLAW4J_RAG_DIRECTORY` | 否 | 打开知识库；开了就必须配目录，否则启动失败 |
| `CLAW4J_OBSERVABILITY_ENABLED` / `OTLP_ENDPOINT` | 否 | trace 导出，两个都要；指标仍走 actuator |
| `CLAW4J_CACHE_REDIS_URI` | 否 | 语义缓存地址，默认 `redis://127.0.0.1:6379` |

`claw4j.*` 的每个键都有明确的拥有者，`application.yml` 里逐项写了默认值与"动它之前要知道什么"。
最常改的几个：`claw4j.tools.profile`、`claw4j.tools.exec.*`、`claw4j.approval.*`、
`claw4j.channel.*.users`、`claw4j.guard.sensitive-words-file`（词表在
`examples/guard/sensitive-words.txt`，一行一个词）、`claw4j.advisor.cache.*`、`claw4j.rag.*`，
以及四个目录 `claw4j.{skills,subagents}.directory`、`claw4j.agent.directory`、`claw4j.rag.directory`。

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
| `claw4j-core` | 契约与不变量：`domain`（端口、`Principal`、工具画像与档位）+ `channel.spi` + `channel.kernel`；编译期只有 slf4j |
| `claw4j-agent` | ChatClient 装配、模型路由、Advisor 链、会话与缓存、审批；不认识任何通道，也不认识任何工具类 |
| `claw4j-channel` | 入站出站适配，按平台分包 |
| `claw4j-capability` | 能力来源的接缝：MCP、Skill（含技能里的脚本）、子 Agent、命令执行与沙箱，加上默认就有的文件 / 搜索 / 计划 / 状态工具 |
| `claw4j-example-sales` | 示例插件：一个业务工具 + 四张业务表 + 种子数据 |
| `claw4j-example-mcp-server` | 示例插件：`java -jar` 就能跑的 Java MCP server（Streamable HTTP，`/mcp`），不依赖 Claw4J 任何模块 |
| `claw4j-app` | 启动与装配，唯一打 fat jar 的模块 |

## License

[MIT](LICENSE) © 2024 OpenClaw4J
