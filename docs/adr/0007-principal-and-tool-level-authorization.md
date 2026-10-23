# 授权模型：内置极简 Principal，鉴权做在 Advisor 链上、不做登录系统

---
status: accepted
date: 2026-10-22
---

一次工具调用能不能发生，由 Principal 决定，不由模型决定。Principal 是内置在进程里的极简身份（`user_id` + `role` + 可访问的表/动作集合），M1 阶段由配置化的静态映射给出，HTTP 通道从请求头取用户标识后查表得出。**本项目不做登录、注册、会话鉴权体系**——那不是考点，篇幅留给 Advisor 链本身。

## Considered Options

**先做一套登录系统再谈鉴权**
否决。登录是通用工程，做了也不能证明 Agent 工程能力，反而会把 M1 拖长一倍。

**鉴权写在每个 `@Tool` 方法内部**
否决。散落在方法里的判断无法保证一致，也无法覆盖第三方/脚本层工具；更重要的是它无法在工具调用循环的**每一次迭代**上都生效。

**在 Advisor 链上统一鉴权（采纳）**
工具调用循环在 2.x 里是 `ToolCallingAdvisor`，它按 order 位于链上，并且把"再调一次模型"实现成**递归调用链上排在他后面的 Advisor**。于是把鉴权 Advisor 的 order 排在工具循环**内部**（**大于** `ToolCallingAdvisor.DEFAULT_ORDER`，也就是 `HIGHEST_PRECEDENCE + 300`），就能在模型的每一次工具迭代上都校验一次。判定发生在模型返回之后、工具执行之前：不通过就把这一轮整个换成一句明确的拒绝，连工具调用请求一起换掉——`ToolCallingAdvisor` 因此看不到工具调用，工具一次都不会被执行。

> 修订（M1-03）：这条原本写的是"小于 `HIGHEST_PRECEDENCE + 300`"，方向写反了。链按 order **升序**排，`ToolCallingAdvisor` 每轮递归的是它**之后**的那一段，所以"循环内部"是 order 更大的一侧。写反也能编译、也能跑，只是退化成"整通对话只校验一次"——而且表现得更隐蔽：排在循环外时拿到的是工具循环跑完之后的最终响应，里面已经没有工具调用了，鉴权连"刚才调过工具"都不知道。

## Consequences

- 鉴权 Advisor 必须**同时实现 `CallAdvisor` 与 `StreamAdvisor`**。只实现 `CallAdvisor` 时，`.stream()` 路径上该 Advisor 被静默跳过且不报错——这是 2.x 最经典的坑，因此每个自定义 Advisor 的测试都必须覆盖流式路径。
- 上面那条不再只是口头约定，由三条机制共同兜住：`AgentArchitectureTest` 里的 ArchUnit 规则（Advisor 必须同时可赋值给 `CallAdvisor` 与 `StreamAdvisor`，漏写即编译期失败）；`AdvisorBothPathsTest` 用一个只实现同步接口的 Advisor 把"被静默跳过"的事实钉住；`AdvisorNotSkippedOverHttpTest` 在整条 HTTP→Agent→模型链路上证明挂进容器的 Advisor 在 SSE 上真的生效。根因是 `BaseAdvisorChain` 给流式路径取的是 `getStreamAdvisors()`，只认 `StreamAdvisor`。
- 另外三个已踩过的实现坑：匿名 Advisor 的 `getClass().getSimpleName()` 是空串，不覆写 `getName()` 会让观测上下文抛 `advisorName cannot be null or empty`；`Prompt.augmentUserMessage(String)` 是**替换**用户消息而不是追加，要追加必须走 `Function` 版本；**流式路径上不能用 `BaseAdvisor` 的默认实现**——它只在 `AdvisorUtils.onFinishReason()` 命中的那一块上调 `after()`，而工具调用是分块发下来的（名字在头一块、参数在后面几块），带 finish reason 的收尾块里通常已经没有工具调用了，鉴权会在流式上完全看不见它。所以流式必须逐块看，看到越权当场掐断上游。
- 拒绝必须是**一句话**而不是一段空数据。"没有权限"和"查出来是空的"在客户端看起来一样，测试上要靠调用计数（工具一次都没被调用）加话术断言（以"没有权限"开头）分开这两者。
- 权限的登记跟着工具走：工具所在的模块声明"这个工具需要什么权限"（动作 + 会碰到哪些表），Agent 侧只认 `ToolPermissionRegistry` 这个接口。**没登记的工具一律按拒绝处理**——没人评估过它会碰到什么，放行的风险比拒绝大。
- 鉴权粒度：一层 Java `@Tool` 做到**表级与动作级**（能不能查 `orders`、能不能发邮件）；二层 MCP 工具按工具名授权；三层 Script Package **不授予任何业务权限**，只能访问自己的沙箱目录。
- 同期配一个 PII 脱敏 Advisor，同样双接口实现。行级/列级脱敏**不做**，表级白名单已足够讲清设计。
- Principal 的来源随 Channel 走：`claw4j-channel` 负责从入站消息解析出用户标识，`claw4j-agent` 只接受已经解析好的 Principal，不自己去认人。这条边界保证 domain 与 agent 模块不依赖任何具体通道协议。
