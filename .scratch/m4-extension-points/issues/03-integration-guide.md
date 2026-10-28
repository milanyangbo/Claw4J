# 03: 集成指南：接一个新 tool / MCP / Skill / 通道分别要写什么

**What to build:** 把"扩展点"从口头约定变成可照抄的文档：
`docs/design/extension-points.md`（写细节：契约、依赖、失效姿势）+
README 的「接自己的能力」一节指向它。覆盖四类：**Java 工具、MCP server（Java/Python）、Skill、通道**。

**Blocked by:** 01、02 —— 两份文档里的示例路径要指向真实存在的模块，
在它们落地之前写出来就是编的。

**Status:** claimed

## 为什么

现在想知道"接一个新能力要写什么"，只能读 `ToolsAutoConfiguration` 的 javadoc——
那里确实写清楚了，但它是**给改框架的人**看的，不是给接插件的人看的。
外部读者拿到的是一个 jar 和一堆"为什么"，缺的是"第一步建什么目录、第二步写哪个文件"。

## 要做

`docs/design/extension-points.md`，四节，每节都要能照抄：

- [ ] **Java 工具**：一个 `@Tool` 类 + `ToolCallbackProvider` bean + `ToolPermissionRegistry` bean
      + `AutoConfiguration.imports`。两次依赖（`claw4j-domain`、`spring-ai-client-chat`）。
      用 01 的 `claw4j-example-sales` 当参照，指出它**不依赖 `claw4j-capability`**。
- [ ] **MCP server**：Java 用 02 的模块当模板；Python 用 `examples/mcp/demo_mcp_server.py`。
      重点写**两边一样的三件事**：`ProcessStdioMcpEndpoint.of(...)` 的 argv 形态、环境变量默认为空、
      工具清单为空即失败。附一条 curl 的实测输出。
- [ ] **Skill**：`claw4j.skills.directory` + 一个含 `SKILL.md` 的目录 + 为什么它不提供动作只编排动作。
- [ ] **通道**：`AgentPort` 契约（`reply` / `stream` 的 finalChunk 语义、中断响应）、
      `ChatChannel` 的三条硬要求（立刻 ACK、eventId 幂等、认不出就拒绝）、
      **如实写限制**：复用现有编排要依赖 `claw4j-channel`，会连带飞书 SDK；中性编排层尚未抽出。

外加一节「让插件静默失效的三种写法」，每条都对应仓库里踩过的真事：

- [ ] `@ConditionalOnMissingBean(ToolCallbackProvider.class)` / `(ToolPermissionRegistry.class)`
      → 第二个来源进来时第一个静默消失，模型开始编答案
- [ ] 忘了登记 `ToolPermissionRegistry` → 工具在，但一律按"没权限"被拒
- [ ] 自动配置类里 `@ConditionalOnBean` 依赖 `@Bean` 方法声明顺序 → 条件永远为假，工具整层消失

- [ ] README 的「接自己的能力」表补两行：MCP server、Skill，并指向新文档
- [ ] `AGENTS.md` 的路由表加一行（什么时候该读这份）
- [ ] `GLOSSARY.md` 若出现"插件/扩展点"的新叫法，先统一定义

## 判据

- 文档里每一个路径、类名、配置键**都在仓库里真实存在**（写完逐条 grep 一遍）。
  这个仓库已经因为"文档承诺与代码不符"返工过多轮，这一条是这份票唯一不能让步的。
- README 里不出现"支持热加载""插件市场"这类没有实现的说法。
- 示例片段必须是**能编译的 Java**：两段代码（工具插件、通道插件）至少各编译一次
  （临时写成文件 `javac` 一遍即可，不必进仓库）。

## 不要做

- 不做插件清单的 HTTP 接口、不做插件版本兼容矩阵（没有多个插件，就没有矩阵）。
- 不重写 `docs/design/capability-layers.md`（它讲的是"为什么三层"，与"怎么接第四类"不冲突）。
