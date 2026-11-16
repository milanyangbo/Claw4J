# 07: README-adding-capabilities.md 逐条实测——两处说法比代码走得远

**What to build:** 把八节里每条可核查的说法对着代码与真实例走一遍，改掉不成立的。

**Blocked by:** 无。

**Type:** docs

**Status:** resolved

**Demo:** 照 §5 的说明做——白名单里写一个容器里没有的工具——**启动会成功**，而这与原文
"启动失败"相反；改完之后文档写的是"首次委派时失败"。

## 逐条结论（8 节 + 文末）

| # | 查的是什么 | 结论 | 证据 |
| --- | --- | --- | --- |
| 1 | 三个 bean 的 API 签名、`MethodToolCallbackProvider`、依赖坐标 | **对** | `SalesExampleAutoConfiguration:83` 与 `claw4j-example-sales/pom.xml` 的 `claw4j-core` + `spring-ai-client-chat` 逐字一致 |
| 1 | 指标判据：问之前 404、问之后有数 | **对** | `curl …tool:summarize…` 未调用时 HTTP **404**，调用后 `ok=1` |
| 1 | "档位漏了 = 起不来 / 权限漏了 = 一律拒绝" | **对** | `ToolProfile:93` 未声明档位抛 `IllegalStateException`；未登记权限由 `AuthorizationAdvisor` 拒 |
| 2 | `transport: http/sse/stdio`、超时默认值、退避参数 | **对** | `Transport` 枚举 STDIO/HTTP/SSE；`requestTimeout=30s`、`initializationTimeout=60s`；`INITIAL_BACKOFF=15s`、封顶 10 分钟 |
| 2 | `headers` 只收句柄，裸写明文启动被拒 | **对** | 起实例传 `headers.Authorization=Bearer abc123` → 启动失败并点名 `…servers.plain.headers.Authorization 只能是句柄` |
| 2 | 日志 `… → UP(工具 3 个)`；`DOWN` 说明原因并退避 | **对（措辞要更准）** | 真实例：`deepwiki → UP(工具 3 个)`；坏端点：`broken → DOWN(15 秒后重试)`，**原因在同一时刻的 WARN 里**，不在括号内 |
| 3 | 清单三个键、目录名即工具名、动作 `run_script`、只有 admin 有它 | **对** | `SCRIPT_ACTION="run_script"`；`application.yml:141-152` 里 read-only 与 guest 的动作表都没有它 |
| 3 | "参数已经由框架做过校验与引号转义" | **不准** | 参数是以 **argv 数组**送达（`ProcessRequest`），不经 shell，**没有转义这回事**；做的是包内相对路径校验与改写（`tokenOf`） |
| 3 | 子进程只拿得到 `PATH` | **对** | `LocalProcessRunner` 起进程前 `environment().clear()` 再填 `PATH` |
| 4 | 工具名 `Skill`、动作 `use_skill` | **对** | `AgentSkills:40/49` |
| 4 | 真实例能加载正文 | **对** | `claw4j.tool.calls{tool=Skill,outcome=ok}=1` |
| 5 | front matter 四个键、`tools` / `disallowedTools`、`Task` 不能进白名单 | **对** | `SubagentDefinition:46-48`、`:75` 抛错 |
| 5 | **"点了容器里没有的工具…启动失败"** | **错** | 实测：白名单写 `no_such_tool` → **应用照常启动**。`SubagentDefinition` 的构造器只校验空值、两表冲突、白名单含 `Task`；缺工具由 `selectTools` 在**首次委派**时抛，`SubagentRunner:53` 的注释写明原因（装配期取工具集会撞循环依赖） |
| 6 | 日志行、tier 只覆盖档位、多份没写 default 启动失败并列出候选 | **对** | 真实例：`这个部署的 agent 是 sales…工具档位 MESSAGING（来自 人设文件的 tier）`；去掉 default → `人设目录里有 2 份定义，但没有写 claw4j.agent.default…必须点名：` 并列出 ops/sales |
| 7 | 本地默认开、同步与重排日志 | **对** | 只带 `--spring.profiles.active=local`：`知识库已启用…重排 开`、`同步完成…现有 2 段`、`重排完成：候选 2 条，服务返回 2 条…` |
| 8 | `ChatChannel` 只有 `id/start/stop`、kernel 在 `claw4j-core` | **对** | 接口 41 行三个方法；`claw4j-core/.../channel/kernel/` 下有 `InboundDispatcher`（去重）、`MessageSplitter`（分段）、`ReplySender` |
| 8 | "给它发一条消息…再发一条重复事件" | **要限定** | HTTP 通道没有事件 id（只有 IM 通道有，如钉钉取 `msgId`），所以"重复事件"这条只对 IM 通道成立 |
| 文末 | `-Dloader.path` 用 `PropertiesLauncher`、`java -jar` 下不生效 | **对** | 清单里 `Main-Class: JarLauncher`；按文档那行 `java -cp … PropertiesLauncher -Dloader.path=…` 起实例成功（`Started Claw4jApplication in 5.154s`，server 9092、管理口 9090） |
| 文末 | `requires-core=>=0.1.0 <0.2.0`、没有跳过开关 | **对** | `claw4j-example-sales/META-INF/claw4j-plugin.properties` 逐字相同；`PluginContract` 有"没声明就不通过"的用例 |

## 改了什么

1. §2 的 `DOWN` 说明：`DOWN(重试间隔)` 里是**重试时间**，原因在同一行的 WARN。
2. §3 的 `run.sh` 注释：删掉"引号转义"（argv 数组不需要转义），改成"已过沙箱校验、不经 shell"。
3. §5 拆开那句：**白名单含 `Task` → 启动失败**；**点了容器里没有的工具 → 首次委派时失败**，并写明为什么不是启动时。
4. §3/§4/§5 补上"怎么确认"（其余五节都有，这三节缺，与文首"每节四段"的说法不一致）。
5. §8 的复验方式限定成"IM 通道"。

## 没改、但记一笔（都不是文档问题）

- **工具调用上限 6 会把内部错误当成回答**：一轮里工具调用超过 6 次时，用户拿到的是
  `Total tool call limit (6) exceeded for this turn. No further tool calls are allowed.`（HTTP 200），
  不是一句人话。§4 的复验（要一份周报）就可能撞上。
- **模型把工具参数 JSON 写坏时，HTTP 500**：`Conversion from JSON to java.util.Map failed`
  （`StreamReadException: Unexpected character '专'`）直接抛到接口层，用户看到 500 而不是一句解释。
