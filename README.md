# Claw4J

![License: MIT](https://img.shields.io/badge/License-MIT-green.svg)
![JDK](https://img.shields.io/badge/JDK-21-blue.svg)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.1.2-brightgreen.svg)
![Spring AI](https://img.shields.io/badge/Spring%20AI-2.0.2-brightgreen.svg)

跑在 Spring AI 2.x 上的**企业 Agent 骨架**：对话从哪儿进来、能动手做什么、能不能做、做完留下什么——
这四件事各有自己的接缝，换掉任何一件都不用动核心。

## 这是什么

企业里通常已经有模型 API、有 IM、有一堆内部系统，缺的是中间那一层：把对话接进来、把能力接上去、
把权限卡在模型的嘴前面。Claw4J 就是那一层。

它**不是某一个业务的问答机器人**。业务能力是插件——仓库自带的销售工具只是一个**示例实现**
（今天还长在 `claw4j-capability` 里，见「还没做的」），不是产品定义。

三条边界先说清楚：

- **不做登录体系**：认证在网关或平台侧完成，通道只负责"认出是谁"，结果以 `Principal` 交给 Agent。
- **不自己造插件机制**：用 Spring Boot 的自动配置——一个 jar + 一个 `AutoConfiguration.imports` 文件就是插件。
  插件与核心同进程，**它是可信代码**；沙箱只针对模型自己写出来的脚本，不针对插件。
- **核心不含业务**：工具、MCP server、脚本包、Skill、子 Agent 都是能力来源，谁写的都一样对待。

和 [OpenClaw](https://openclawcn.com/) 是同一类东西：**渠道入口 + 工具执行 + 治理边界的收敛层**。
差别在落点——它面向个人设备与本地执行，Claw4J 面向企业内网、多身份与 Java 技术栈。

## 特性

**接入**

- **两条通道，同一份逻辑** —— HTTP+SSE（给网关/前端）与飞书长连接（给群聊）；通道只做"收进来 → 认出是谁 → 发出去"
- **流式与同步同源** —— Advisor 必须同时实现 Call 和 Stream，只写一个会在流式路径上静默不生效
- **通道可外置** —— 契约是 domain 的 `AgentPort`，第三方 jar 自带端点即可接进来（代价见下）
- **会话记忆** —— 落 Postgres，换条通道进来还是同一个人

**能力**

- **四种能力来源，对模型是一回事** —— Java `@Tool`、跨进程 MCP server、受限脚本包、Agent Skill（命中才加载全文）
- **渐进式工具披露** —— 工具多的时候不全塞提示词，模型先用一句话检索该用哪个
- **子 Agent 委派** —— 派出去的子任务是独立上下文与独立预算，只把结论带回来，且不提权
- **开放但不放任** —— 模型可以自己产出脚本，但要过"生成 → 静态检查 → 沙箱自测 → 审计 → 人审"五步

**治理**

- **身份决定权限** —— 角色到"可见表 × 可执行动作"的映射在配置里，改权限不发版
- **入站拦截** —— 套取系统提示词、诱导越权这类消息在模型之前就被挡下：不花 token、不进历史
- **两层只读约束** —— SQL 静态校验拦一层，数据库只读事务兜一层
- **出站脱敏与语义缓存** —— 手机号/身份证在回答里打码；同一个问题同一份数据不重复查库
- **架构边界由测试守护** —— 谁能依赖谁写在 ArchUnit 规则里，加错依赖当场红

## 演示

三条都跑在同一个进程里，不依赖业务表。

**流式**（客户端断开即中断背后的模型调用）：

```bash
curl -N -X POST http://localhost:8080/api/chat/stream \
  -H 'Content-Type: application/json' -H 'X-Claw4j-User: ethan' \
  -d '{"text":"这个进程现在堆内存上限是多少？线程数呢？"}'
```
```text
event:chunk
data:堆内存上限 8192 MB（8 GB），当前已用 73 MB；线程数 42。
event:done
```

**跨进程 MCP**（一个真的 Python server，走协议边界）：

```bash
java -jar claw4j-app/target/claw4j-app-0.1.0-SNAPSHOT.jar --spring.profiles.active=mcp-demo
curl -s -X POST localhost:8080/api/chat -H 'Content-Type: application/json' \
  -H 'X-Claw4j-User: ops9' -H 'X-Claw4j-Role: admin' \
  -d '{"text":"用 demo_echo 这个工具，把“跨进程调用成功”这句话原样带回来，只回这句话"}'
# {"chatId":"http:ops9","text":"跨进程调用成功"}
```

**脚本包**（脚本本体不进上下文，出来的只有标准输出）：

```bash
curl -s -X POST localhost:8080/api/chat -H 'Content-Type: application/json' \
  -H 'X-Claw4j-User: ops9' -H 'X-Claw4j-Role: admin' \
  -d '{"text":"深圳未来三天的天气怎么样"}'
```

飞书那条要凭据，装法与实录见 [`examples/README.md`](examples/README.md) 与
[`docs/design/capability-layers.md`](docs/design/capability-layers.md)。

## 快速开始

前置：**JDK 21**、**Docker**（数据库）、一个 `DEEPSEEK_API_KEY`。

```bash
git clone git@github.com:milanyangbo/Claw4J.git && cd Claw4J

echo 'DEEPSEEK_API_KEY=sk-xxxxxxxx' > .env          # 也可以直接 export

cp claw4j-app/src/main/resources/application-local.yml.example \
   claw4j-app/src/main/resources/application-local.yml
# application-local.yml 被 .gitignore 忽略（它可能放真实 key），仓库里只有模板

./mvnw -pl claw4j-app spring-boot:run -Dspring-boot.run.profiles=local
# local profile 会按 compose.yaml 拉起 Postgres(pgvector)，退出时回收；
# 它会找仓库根目录的 compose.yaml，所以要在根目录启动
```

起来就问：

```bash
curl -s -X POST http://localhost:8080/api/chat \
  -H 'Content-Type: application/json' -H 'X-Claw4j-User: ethan' -H 'X-Claw4j-Role: admin' \
  -d '{"text":"这个进程现在堆内存上限是多少？线程数呢？"}'
```

国内拉不到官方镜像时用 `CLAW4J_POSTGRES_IMAGE=docker.m.daocloud.io/pgvector/pgvector:pg16` 换源。

> 没有 key 应用**起不来**，不降级：留着 mock 兜底就会出现"本地跑着回声模型却以为在跑真模型"这种更贵的困惑。

## 接自己的能力

四种扩展点，都是 Spring Boot 的自动配置——**不需要改 Claw4J 的代码，也不需要父 POM 里登记**。
能整份照抄的模板、每一步的失效姿势，在 [`docs/design/extension-points.md`](docs/design/extension-points.md)；
这里只给结论。

| 扩展点 | 你要提供 | 依赖 | 参照实现 |
| --- | --- | --- | --- |
| 工具 | `ToolCallbackProvider` + `ToolPermissionRegistry` | `claw4j-domain`、`spring-ai-client-chat` | [`claw4j-example-sales`](claw4j-example-sales) |
| MCP server | 一个说 MCP 协议的可执行程序（Java、Python 或任何语言） | 宿主侧 `claw4j-capability`；**server 侧什么都不要** | [`claw4j-example-mcp-server`](claw4j-example-mcp-server)、[`examples/mcp/`](examples/mcp) |
| Skill | 一个含 `SKILL.md` 的目录 | 无（纯文本） | [`examples/skills/weekly-sales`](examples/skills/weekly-sales) |
| 通道 | 实现 `ChatChannel` 的 bean + 自己的端点 | `claw4j-domain`；要复用现有编排再加 `claw4j-channel` | `claw4j-channel/http`、`claw4j-channel/feishu` |

### 三个文件接一个工具

一个 `@Tool` 类、一个自动配置（两个 bean）、一个 `AutoConfiguration.imports`。全部代码就是这段：

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

```text
src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
→ com.acme.jira.JiraToolsAutoConfiguration
```

**一条硬规矩**：这两个 bean 都**不要**写 `@ConditionalOnMissingBean`。那条条件的语义是"容器里已经有了就让位"，
而容器里本来就会有别的来源——让位的结果是**你这一方的工具静默消失**，不报错，只是模型手上没它，
于是它开始编答案。这个坑在 [`docs/design/pitfalls.md`](docs/design/pitfalls.md) 里记了两次。

示例销售插件就是这么接的：它只依赖 `claw4j-domain` 与 Spring AI，**不依赖 `claw4j-capability`**，
`claw4j-app` 里那一行依赖就是全部接线动作——删掉它，框架照常构建、照常启动。

### 接一个 MCP server 只要一个 bean

server 那一侧与语言无关：起一个进程、说 JSON-RPC、交出工具清单。仓库里有一个 Java 版
（[`claw4j-example-mcp-server`](claw4j-example-mcp-server)，`java -jar` 直接跑）与三个 Python 版
（[`examples/mcp/`](examples/mcp)），对宿主完全一样：

```java
@Bean
McpServerEndpoint javaDemoMcp() {
    return ProcessStdioMcpEndpoint
            .of("java-demo", javaBinary(), List.of("-jar", "…/claw4j-example-mcp-server-0.1.0-SNAPSHOT.jar"))
            .endpoint(ToolPermission.read());
}
```

连接在启动时建立、清单在启动时取回，于是**清单为空即失败**；会话丢了就重新初始化，
不对一条已经不在的会话重试。子进程的环境默认是空的——应用的模型 key 与数据库口令不会漏过去。

写 server 时最容易撞的一条：**stdout 是协议通道**。SDK 自己的日志默认也走 stdout，
一帧 JSON 前面插进一行日志，客户端报的是 `Client failed to initialize`，而 server 侧看起来一切正常。
那个 Java 样本里把日志出口改到了 stderr，原因写在它的 `logback.xml` 里。

### 接一条通道

```java
@AutoConfiguration
@ConditionalOnProperty(prefix = "acme.channel.wecom", name = "enabled", havingValue = "true")
public class WecomChannelAutoConfiguration {

    @Bean(initMethod = "start", destroyMethod = "stop")
    @ConditionalOnMissingBean
    public WecomChannel wecomChannel(AgentPort agentPort) {
        return new WecomChannel(agentPort, ...);   // 收消息 → 认出是谁 → 交给 AgentPort → 回推
    }
}
```

通道只要三件事：**拿到消息尽快 ACK**（IM 平台都有超时重推）、**用事件 id 做幂等**、**认不出人就拒绝**。
这三条写进了 [`ChatChannel`](claw4j-channel/src/main/java/io/github/claw4j/channel/spi/ChatChannel.java) 的契约里。

插件 jar 怎么进 classpath：最直接的是加一条 Maven 依赖重新打包；不想重新打包就用
PropertiesLauncher 的 `loader.path` 指到一个插件目录。**后者还没在仓库里验证过**，写在这里是让人知道方向，
不是承诺。

## 配置

凭据一律走环境变量。仓库根目录放一个 `.env`（已被忽略），或直接 `export`：

| 变量 | 必填 | 说明 |
| --- | --- | --- |
| `DEEPSEEK_API_KEY` | 是 | 主模型 key，缺失即启动失败 |
| `QWEN_API_KEY` | 否 | 备用模型 key，配了才有主备降级 |
| `CLAW4J_MODEL_PRIMARY` | 否 | `deepseek`（默认）/ `qwen`，决定谁是主模型 |
| `CLAW4J_POSTGRES_IMAGE` | 否 | 换 Postgres 镜像源，与 compose 共用同一个变量 |
| `FEISHU_APP_ID` / `FEISHU_APP_SECRET` | 否 | 飞书自建应用凭据。两个都配才装配长连接通道，缺一个整个通道退让、HTTP 照常 |

角色到权限的映射在 `claw4j.channel.http.roles` 里配置；飞书那边只登记"谁是谁"
（`claw4j.channel.feishu.roles.*.users`），授权共用这一份，角色名必须与它同名。
模型主备顺序启动时打在日志里。其余可选的覆盖项（数据库地址、缓存开关与阈值、两个通道的开关）
见仓库根的 `.env.example`。

## 用法

| 接口 | 方法 | 说明 |
| --- | --- | --- |
| `/api/chat` | POST | 同步问答，返回 JSON |
| `/api/chat/stream` | POST | 流式问答，SSE，事件名 `chunk` / `done` |

请求体：`{"text":"..."}`。请求头：

| 头 | 必填 | 缺省 |
| --- | --- | --- |
| `X-Claw4j-User` | 是（缺省返回 401） | — |
| `X-Claw4j-Role` | 否 | `read-only` |
| `X-Claw4j-Chat-Id` | 否 | 回落到用户标识 |

## 架构

```
     channel ──► domain ◄── agent
                    ▲          ▲
                    │          │
                    └─ capability ─┘
                    ▲
                    │
                   app（唯一知道所有模块的地方，负责接线）
```

| 模块 | 职责 |
| --- | --- |
| `claw4j-domain` | 领域模型与端口。**零框架依赖**，唯一被承诺能脱离框架复用的模块 |
| `claw4j-agent` | ChatClient 装配、模型路由、Advisor 链。不认识任何通道，也不认识任何工具类 |
| `claw4j-channel` | 入站出站适配（HTTP+SSE、飞书长连接）。按平台分包，不按平台分模块 |
| `claw4j-capability` | 能力来源的接缝：MCP、脚本包、Skill、子 Agent、生成器，加一个平台无关的 `system` 工具 |
| `claw4j-example-sales` | **示例插件**：一个业务工具 + 四张业务表 + 种子数据。不依赖 `claw4j-capability` |
| `claw4j-example-mcp-server` | **示例插件**：一个 `java -jar` 就能跑的 Java MCP server，不依赖 Claw4J 任何模块 |
| `claw4j-app` | 启动与装配，唯一打 fat jar 的模块 |

两条最关键的方向：`channel → domain ← agent`，两边互不依赖；接线只发生在 `app`。
加一个通道不需要碰 agent，换一个 Agent 实现不需要碰 channel。

两个 `example` 模块只依赖 `domain` 与 Spring AI（MCP server 连这个都不依赖），
`claw4j-app` 对它们的依赖是**唯一**的接线点。这不是分层洁癖：
"删掉那一行、框架照常构建启动"正是插件契约能自证的唯一方式。

## 还没做的

写在这里是因为它们会影响上面那些承诺的强度，而不是自谦：

- **通道的中性编排层没抽出来**：认人、幂等、ACK、回推这套逻辑现在长在 `feishu` 包里，
  第三方通道要复用它就得把飞书 SDK 一起拉进来。
- **插件 jar 的外部加载（`loader.path`）没验证过**，也没定义插件与核心的版本兼容口径。
- **框架自己的账本迁移还留在 `claw4j-capability`**：会话表（`V3`）与语义缓存表（`V4`/`V5`）
  与能力来源无关，更该挨着用它们的模块（`claw4j-agent`）。业务表已经跟着
  `claw4j-example-sales` 搬走了，剩这三份是纯账本，不构成"框架带业务"的问题，所以这轮没动。

## 目录结构

```text
claw4j-{domain,agent,channel,capability,app}/      核心五个模块
claw4j-example-{sales,mcp-server}/                 示例插件：业务工具、Java 版 MCP server
docs/adr/            架构决策记录：为什么这样，否决了什么
docs/design/         设计说明：能力来源与扩展点、鉴权、接入拦截、会话记忆、语义缓存
docs/agents/         给 AI 助理的仓库约定（含测试准则与评审流程）
examples/            可直接运行的能力示例：scripts / skills / subagents / mcp
scripts/             本地门禁脚本
legacy/              1.x 冻结代码，不参与构建
CONTRIBUTING.md      怎么跑验证、门禁有哪些
GLOSSARY.md          术语表
```

## 示例

`examples/` 下四份都是能直接挂上跑的：[`examples/README.md`](examples/README.md)

- `scripts/` —— 脚本包：脚本跑在受限子进程里，进入上下文的只有它的标准输出
- `skills/` —— 技能包：渐进式披露，命中才加载全文
- `subagents/` —— 子 Agent 定义
- `mcp/` —— 三个零第三方依赖的 Python MCP server，走真协议

`claw4j-example-mcp-server` 是同一件事的 Java 版：`java -jar target/*.jar` 起一个 stdio server，
导出 `echo` / `serverTime` / `processId` 三个工具。它与那三个 Python server 平级——
宿主不关心对端是什么语言，只关心协议。

仓库自带的销售工具是**示例插件**：一个 `querySales` 工具 + 四张业务表 + 一份种子数据，
用来演示"身份决定权限"这条治理链路。业务同学在群里问一句"上周华东区成交额多少"就是它：

```bash
curl -s -X POST localhost:8080/api/chat -H 'Content-Type: application/json' \
  -H 'X-Claw4j-User: ethan' -H 'X-Claw4j-Role: admin' \
  -d '{"text":"上周华东区成交额多少"}'
# {"chatId":"http:ethan","text":"**上周华东区成交额：￥128,400.00**｜计算标准：按订单所属区域统计
#   sum(order_item.amount)，已排除状态为 CANCELLED 的订单；时间窗口为上周一 00:00 到本周一 00:00。"}

# 换个角色问同一句话
curl -s -X POST localhost:8080/api/chat -H 'Content-Type: application/json' \
  -H 'X-Claw4j-User: guest01' -H 'X-Claw4j-Role: guest' \
  -d '{"text":"上周华东区成交额多少"}'
# {"chatId":"http:guest01","text":"没有权限：角色 guest 不允许访问表 customer、order_item、
#   sales_order（工具 querySales）。"}
```

## 文档

- [`docs/adr/`](docs/adr/) —— 架构决策记录，每条都写了 Considered Options 与被否决的原因
- [`docs/design/`](docs/design/) —— 设计说明：能力来源与扩展接缝、鉴权、接入拦截、会话记忆、语义缓存
- [`docs/agents/review.md`](docs/agents/review.md) —— 评审按什么顺序看、什么不该报
- [`CONTRIBUTING.md`](CONTRIBUTING.md) —— 本地怎么跑验证、门禁有哪些、迁移怎么加
- [`GLOSSARY.md`](GLOSSARY.md) —— 术语表

## License

[MIT](LICENSE) © 2024 OpenClaw4J
