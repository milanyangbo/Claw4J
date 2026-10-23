# 01: 脚本化假模型与 `agent → tools` 依赖边

**What to build:** `claw4j-tools` 里第一次出现 Java 代码（一个桩工具），`claw4j-agent` 第一次依赖它并完成装配——这是 M0 留下的缺口。同时把假模型从"回声"升级成"按脚本吐响应"，于是"模型先要调工具、再据此作答"这两步在 CI 里第一次变得可测。没有这两样，M1 后面每一张票都只能在真模型上手工验。

**Blocked by:** None (can start immediately)

**Status:** resolved

**Demo:** 一条走 `AgentPort` 接缝的端到端测试——假模型先吐一次工具调用请求、再吐最终回答，桩工具真的被调用，最终回答里带着工具返回的值。

- [x] `claw4j-tools` 有一个 `@Tool`（桩即可，返回固定串），该模块只依赖 `claw4j-domain`
- [x] `claw4j-agent` 的 POM 依赖 `claw4j-tools`，工具注册进 `ChatClient`（补上 M0 缺的那条边）
- [x] ArchUnit 规则：`tools` 不得依赖 `agent` 或 `channel`（反向依赖即失败）
- [x] 脚本化假模型：按预设顺序吐响应（第一次带工具调用请求，第二次是最终回答），脚本耗尽时给出明确失败而不是静默返回空
- [x] 端到端测试（AgentPort 接缝）：走完两轮模型调用。**开工前这条必然是红的**——现在没有任何工具会被调用
- [x] 同步与流式两条路径各一条断言
- [x] 不烧 token、不依赖网络（ADR-0006）

**注意**：这里的桩工具是 deliberately 临时的，05 号票会用真实的 Text-to-SQL 工具替换它。

**顺带要做**：`MockChatModel` 流式仍按块吐（每 8 字符一块是 M0 为测出"流式被降级成一次性响应"而设的），别把这条保护弄丢。

## Answer

提交 `ab6539b`（实现）+ `a6a759d`（评审修复）。73 个测试全绿。

### 落地形状

| 位置 | 东西 |
| --- | --- |
| `claw4j-tools` | `SalesTools`（`@Tool querySales(region, period)`，返回占位值）、`ToolsAutoConfiguration`（把自己暴露成 `ToolCallbackProvider` bean）、`ToolsArchitectureTest` |
| `claw4j-agent` | POM 加上 tools 依赖；`AgentAutoConfiguration` 把容器里所有 `ToolCallbackProvider` 挂进 `ChatClient` |
| `claw4j-agent`（测试） | `ScriptedChatModel`（按脚本吐响应、记录收到的 Prompt）、`ScriptedChatModelTest`、`ToolCallingEndToEndTest` |

装配只认 Spring AI 的 `ToolCallbackProvider` 接口，**不点名任何工具类**，所以加工具是加一个 bean。

### 端到端断言（比票面写的更强）

票面写的是"最终回答里含桩工具的返回值"。实现时换成了三条都不依赖桩内容的断言：

1. `taken() == 2` —— 循环走了两轮（第一轮决定调工具，第二轮才作答）
2. 第二轮的提示词里含 `STUB_RESULT` —— 工具的返回值确实回到了模型
3. 最终回答等于第二轮脚本那句 —— 循环走完了

理由：票面那条是"脚本让模型说什么它就说什么"，断言它等于什么都没证明。换成这三条之后，05 号票把桩换成真实 Text-to-SQL 时这三条都不用改。

**变异检验**：把 `defaultToolCallbacks(...)` 那一行删掉，两条端到端测试立刻报错——断言不是恒真的。

### 两个只有真跑才会撞到的坑

1. **`ToolCallingAdvisor` 开头是 `options instanceof ToolCallingChatOptions`，不是就跳过整个工具循环**。真实的 `DeepSeekChatOptions` / `OpenAiChatOptions` 都实现了它所以生产路径正常，但假模型返回普通 `ChatOptions` 会让工具调用**静默不发生**——不报错、不告警，只是模型永远拿不到工具结果。所以 `ScriptedChatModel` 必须返回 `DefaultToolCallingChatOptions`。
   顺带把 `MockChatModel` 也改了：它原来返回普通 `ChatOptions`，于是"接真模型时工具能调、一退到 mock 工具就不调"——这个差异只在本地演示、以及后面几张票在 mock 上手验时才暴露，而那时没人会想到是 options 的类型。

2. **`ToolResponseMessage#getText()` 是空串**，工具结果在 `getResponses()` 的 `responseData()` 里。只拼各条消息的 `getText()` 会把工具结果整个漏掉，"工具没执行"和"执行了"看起来一模一样。

另外：取脚本放进 `Flux.defer`，让脚本耗尽的失败在**订阅时**以 `onError` 传出，而不是在拼装流那一行同步抛出。真实模型不会在 `stream()` 里抛，混在一起会让"流上的失败"被误读成调用方的错。

### 与票面文字的两处偏差（如实记）

- **"该模块只依赖 `claw4j-domain`"**：字面不成立。tools 还需要 `spring-ai-client-chat`（`@Tool` 注解）和 `spring-boot-starter`（`@AutoConfiguration`）。票面的意思应是"不依赖 agent / channel"，这条由 ArchUnit 钉住。
- **`ToolsAutoConfiguration` 是票面没写的**：票面只说"工具注册进 `ChatClient`"。走自动配置是为了让 Agent 不用点名工具类；代价是 tools 多一个 boot 依赖，且 `@ConditionalOnMissingBean(ToolCallbackProvider.class)` 意味着**第二个 provider bean 会让位、工具静默消失**。这个坑已写进该类的 javadoc，留给 05 加第二个工具那天。

### 留给 05 的

桩工具的 `@Tool` 描述里明确写了"当前是占位实现，不要把它当成真实金额"——从现在到 05 之间跑真实模型时，模型会自己知道这数不能信。方法名 `querySales` 与参数 `(region, period)` 保持不变，05 换实现即可。
