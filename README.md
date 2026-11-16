# Claw4J

![License: MIT](https://img.shields.io/badge/License-MIT-green.svg)
![JDK](https://img.shields.io/badge/JDK-21-blue.svg)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.1.2-brightgreen.svg)
![Spring AI](https://img.shields.io/badge/Spring%20AI-2.0.2-brightgreen.svg)

跑在 Spring AI 2.x 上的**企业 Agent 骨架**：把模型、IM 和内部系统接成一条能上生产的链路——
对话收进来、能力接上去、权限卡在模型之前、每一步留下能查的痕迹。

企业里通常已经有模型 API、有 IM、有一堆内部系统，缺的是中间那一层，Claw4J 就是那一层。
它**不是某个业务的问答机器人**：业务能力都是插件，仓库自带的销售工具只是一个示例实现。

三条边界：**不做登录体系**（认证在网关，通道只负责"认出是谁"）；**不自己造插件机制**
（用 Boot 自动配置，一个 jar 就是插件，同进程可信代码）；**核心不含业务**。

## 特性

- **五条通道，同一份逻辑** —— HTTP+SSE、飞书长连接、企业微信、钉钉、Slack；通道只做
  "收进来 → 认出是谁 → 发出去"，去重 / 认人 / 异步 / 回推只有一份
- **五种能力来源，对模型是一回事** —— Java `@Tool`、MCP server（stdio / HTTP）、脚本包、Skill、子 Agent
- **权限在模型之前** —— 角色到"可见表 × 可执行动作"的映射；越权与套提示词的消息不花 token、不进历史
- **知识库检索带出处** —— 混合检索（向量 + 全文）加 `qwen3-rerank` 重排，每段都带「文档 + 第几段」
- **一轮一个 trace** —— 配一个 OTLP 端点就导出整条链路，根 span 是 run，与审计靠 `claw4j.run.id` 对上
- **也能反过来当 MCP server** —— 点名几个工具，Claude Code 这类客户端就能像用别的工具一样调它们

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

`local` profile 会按 `compose.yaml` 拉起 Postgres(pgvector) 与 Redis Stack，退出时回收；
compose 文件在仓库根目录，所以要在根目录启动。

起来就问（`X-Claw4j-User` 必填，`X-Claw4j-Role` 缺省 `read-only`）：

```bash
curl -s -X POST http://localhost:8080/api/chat \
  -H 'Content-Type: application/json' -H 'X-Claw4j-User: ethan' -H 'X-Claw4j-Role: admin' \
  -d '{"text":"这个进程现在堆内存上限是多少？线程数呢？"}'
# {"chatId":"http:ethan","text":"堆内存上限 8192 MB（8 GB），当前已用 73 MB；线程数 42。"}
```

几个容易卡住的点：

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

请求体只有 `{"text":"..."}`；请求头三个：`X-Claw4j-User`（必填）、`X-Claw4j-Role`（给了但没登记是 401）、
`X-Claw4j-Chat-Id`（缺省回落到用户标识）。错误码：`401` 认不出人、`400` 请求体不合法、
`503` 流式线程池已满。

```bash
# 流式：客户端断开即中断背后的模型调用
curl -N -X POST http://localhost:8080/api/chat/stream \
  -H 'Content-Type: application/json' -H 'X-Claw4j-User: ethan' \
  -d '{"text":"上周华东的成交额是多少"}'
# event:chunk / data:上周华东的成交额是 128,400.00…… / event:done
```

### 身份与权限

授权表只有一份（`claw4j.channel.http.roles`，飞书按角色名共用），改它不用改代码：

| 角色 | 可见表 | 可执行动作 |
| --- | --- | --- |
| `read-only`（缺省） | `*` | 只读类动作：`read`、`use_skill`、`delegate_task`、会话与目标、定时任务等 |
| `guest` | `region` | `read`、`use_skill`、`delegate_task` |
| `admin` | `*` | `*`（含 `run_script`） |

逐项默认值在 `application.yml` 的 `claw4j.channel.http.roles`，表里没有的角色一律拒绝。

三件与"记忆"有关的事，多用户部署要提前知道：

- **会话按「人 + 权限 + 通道 + 对话 + 段」落库**。同一个人在网页私聊查到的数字，不会出现在他十分钟后
  @ 机器人的项目群里；降权之后是新的一段，历史里躺着的数字读不到。
- **段是"这段对话的第几世"**：跨逻辑日（默认 4 点）或空闲超时（默认 120 分钟）就换一段，
  记忆与额度从零开始。
- **`claw4j.tools.sessions.visibility` 默认是 `all`**，也就是 `recall_session` 能召回别的会话、
  包括其他用户的。多用户、角色不同的部署必须显式配成 `self`——这是它的默认值，不是 bug。

### 当 MCP server 用

同一个 jar 换个 profile，就从"服务"变成"工具"：

```bash
java -jar claw4j-app/target/claw4j-app-0.1.0-SNAPSHOT.jar \
     --spring.profiles.active=mcp-stdio --claw4j.mcp.server.expose=querySales
```

客户端的 `.mcp.json` 就是一条 `command`（把上面那串原样抄进 `args`）。三点要知道：
交出去的只能是模型能用的那一份（档位与 allow / deny 照常生效，需要审批或动作为写的一律启动失败）；
外部客户端是**另一个身份**，能读哪些表由 `claw4j.mcp.server.tables` 决定，与角色表无关；
它**不落审计**、不扣 run 预算，只留一行日志。

## 扩展

加一个能力或一条通道，都是自动配置或一段配置——**不改 Claw4J 的代码，也不在父 POM 里登记**。
每一步怎么做、代码长什么样，见 **[`README-adding-capabilities.md`](README-adding-capabilities.md)**（八个能直接抄的例子）。

| 你要加的东西 | 写什么 | 例子 |
| --- | --- | --- |
| 一个工具 | `@Tool` 方法 + 三个 bean（工具、权限登记、档位）+ `AutoConfiguration.imports` | [1](README-adding-capabilities.md#1-java-工具4-个文件) |
| 一个 MCP server | 一段 YAML，server 用任何语言写，宿主侧不写代码 | [2](README-adding-capabilities.md#2-mcp-server一段-yaml) |
| 一个脚本包 | 一个目录（`package.properties` + 入口脚本），配 `claw4j.scripts.directory` | [3](README-adding-capabilities.md#3-脚本包一个目录) |
| 一个 Skill | 一个含 `SKILL.md` 的目录，配 `claw4j.skills.directory` | [4](README-adding-capabilities.md#4-skill一份做法) |
| 一个子 Agent | 一份 `*.md`，配 `claw4j.subagents.directory` | [5](README-adding-capabilities.md#5-子-agent一份-md) |
| 一个人设 | 一份 `*.md`，配 `claw4j.agent.directory` 与 `claw4j.agent.default` | [6](README-adding-capabilities.md#6-agent人设这个部署是谁) |
| 一份知识库 | 一个 `*.md` / `*.txt` 目录，配 `claw4j.rag.directory` | [7](README-adding-capabilities.md#7-知识库一个目录) |
| 一条通道 | `ChatChannel` 实现 + 自己的端点，不带任何 IM SDK | [8](README-adding-capabilities.md#8-通道收认人发) |

两种情况会**启动失败并点名**，不是默认放行：工具没登记权限或档位；插件没在
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
| `CLAW4J_RAG_ENABLED` / `CLAW4J_RAG_DIRECTORY` | 否 | 打开知识库；开了就必须配目录，否则启动失败 |
| `CLAW4J_OBSERVABILITY_ENABLED` / `OTLP_ENDPOINT` | 否 | trace 导出，两个都要；指标仍走 actuator |
| `CLAW4J_CACHE_REDIS_URI` | 否 | 语义缓存地址，默认 `redis://127.0.0.1:6379` |

`claw4j.*` 的每个键都有明确的拥有者，`application.yml` 里逐项写了默认值与"动它之前要知道什么"。
最常改的几个：`claw4j.channel.http.roles`、`claw4j.guard.sensitive-words`、`claw4j.cache.*`、
`claw4j.rag.*`，以及三个能力目录 `claw4j.{scripts,skills,subagents}.directory`。

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
| `claw4j-core` | 契约与不变量：`domain`（端口、`Principal`、权限登记表）+ `channel.spi` + `channel.kernel`；编译期只有 slf4j |
| `claw4j-agent` | ChatClient 装配、模型路由、Advisor 链、会话与缓存；不认识任何通道，也不认识任何工具类 |
| `claw4j-channel` | 入站出站适配，按平台分包 |
| `claw4j-capability` | 能力来源的接缝：MCP、脚本包、Skill、子 Agent、生成器，加一个平台无关的 `system` 工具 |
| `claw4j-example-sales` | 示例插件：一个业务工具 + 四张业务表 + 种子数据 |
| `claw4j-example-mcp-server` | 示例插件：`java -jar` 就能跑的 Java MCP server，不依赖 Claw4J 任何模块 |
| `claw4j-app` | 启动与装配，唯一打 fat jar 的模块 |

## 与 OpenClaw 的关系

OpenClaw 是单用户常驻的个人助手（Gateway 控制面 + 浏览器 / 语音 / 手机节点做外设）；Claw4J 是企业里
那一层：多用户身份、权限在模型之前裁决、可嵌在网关后面。两者形态不同，同一个功能落在两边判定可以相反，
所以**不抄它的功能清单**，逐条的取舍见 [ADR-0021](docs/adr/0021-explicitly-not-doing.md)。

## 还没做的

- **MCP 的凭据只有"静态请求头"**：需要浏览器跳转、刷新 token、按用户授权的端点（OAuth 授权码流）接不了。
- **MCP 的出口只有 stdio**：`spring-ai-starter-mcp-server-webmvc` 的 2.0.2 不在本地仓库，离线构建拉不到；
  也没有 WebSocket。stdio 天生只对一个客户端。

## 门禁与验收

一条命令跑完全部用例与覆盖率闸：`./scripts/verify.sh`。它证明的是"装配成立"，证明不了"能力真能用"——
后者要真跑：**加了能力想确认它生效，按 [README-capability-tests.md](README-capability-tests.md) 的八节走**
（每节一句触发话 + 该看到什么 + 不对时看哪里）；要逐条跑全部工具与子 Agent，
清单在 [docs/capability-tests.md](docs/capability-tests.md)。判定看
`claw4j.tool.calls{tool=…,outcome=ok|denied|error}` 这个计数，不看模型措辞。

贡献者要看的：[CONTRIBUTING.md](CONTRIBUTING.md)（怎么跑、迁移怎么加）· [docs/design/](docs/design/)
（能力层、鉴权、会话、缓存的设计）· [docs/adr/](docs/adr/)（为什么这么设计、否决了什么）·
[examples/](examples/README.md)（脚本包、Skill、Subagent、Python MCP 素材）· [GLOSSARY.md](GLOSSARY.md)（术语）。

## License

[MIT](LICENSE) © 2024 OpenClaw4J
