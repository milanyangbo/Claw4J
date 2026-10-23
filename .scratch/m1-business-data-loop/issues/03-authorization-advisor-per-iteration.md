# 03: 鉴权 Advisor —— 在每一次工具迭代上都校验

**What to build:** 鉴权 Advisor 落到链上，order 排在工具调用循环**内部**，于是模型的每一次工具迭代都会过一遍校验；不通过就把这一轮整个换成一句明确的拒绝（连工具调用请求一起换掉）。工具调用能不能发生由 Principal 决定，不由模型决定（ADR-0007）。

> 落地时改了一处：票面原来写"不通过就短路，不调 `chain.next()`"。判定必须发生在**模型返回之后**——
> 不调 `chain.next()` 就看不到模型这一轮要调什么工具，也就无从判定。不调的是**工具执行**，
> 那一层由"换掉响应里的工具调用"实现（`ToolCallingAdvisor` 因此看不到工具调用）。

**Blocked by:** 01

**Status:** resolved

**Demo:** 换一个角色头问同一句话——工具一次都没被调用，回答是明确的"没权限"，而不是空数据。

- [x] Advisor 同时实现 `CallAdvisor` 与 `StreamAdvisor`，并覆写 `getName()`
- [x] order 排在工具循环内部（**大于** `ToolCallingAdvisor.DEFAULT_ORDER`，即 `HIGHEST_PRECEDENCE + 300`），使每次迭代都生效
- [x] 无权限时工具**一次都没被调用**——用调用计数证明，不是靠"回答里没出现数字"来猜
- [x] 返回的是明确的"没权限"，不是空数据集。**这两者在客户端看起来完全一样**，必须有断言区分
- [x] 多轮迭代场景：模型在第二轮再次尝试调工具时同样被拦（这是 order 位置的意义所在）
- [x] 流式路径同一条断言（agent 层 + HTTP 层各一条）
- [x] 粒度为表级与动作级；不做行级/列级

**注意**：只实现 `CallAdvisor` 的 Advisor 在 `.stream()` 上**既不报错也不生效**（`BaseAdvisorChain` 给流式取的是 `getStreamAdvisors()`）。ArchUnit 已经拦编译期，但这条票要的是行为断言——编译过得去不代表拦得住。匿名 Advisor 的 `getClass().getSimpleName()` 是空串，不覆写 `getName()` 会让观测上下文抛 `advisorName cannot be null or empty`。

**不要做**：登录、注册、会话鉴权体系（ADR-0007 明确不做）。Principal 由通道解析好后交给 Agent，Advisor 只消费它。

## Answer

提交 `e7ee1e1`。97 个测试全绿（`CLAW4J_POSTGRES_IMAGE=docker.m.daocloud.io/pgvector/pgvector:pg16 ./mvnw clean verify`）。

### 一、票面写反了：循环内部是 order **更大**的一侧

票面与 ADR-0007 原文都写"小于 `HIGHEST_PRECEDENCE + 300`"，方向是反的。
`ToolCallingAdvisor`（2.0.2）的类注释写得很清楚：它的 `DEFAULT_ORDER = HIGHEST_PRECEDENCE + 300`
刻意**高于** memory advisor 的 `+200`，好让 memory advisor 落在外面、不参与每次迭代。
链按 order **升序**排，`DefaultAroundAdvisorChain` 每轮递归的是 `copy(this)` 之后的那一段，
也就是 order 更大的那些 Advisor。所以"循环内部" = order **大于** `DEFAULT_ORDER`。

写反不是"慢一点"，是**整条鉴权失效**，而且失效得很隐蔽：
排在循环外面时 `chain.nextCall()` 拿到的是工具循环**跑完之后**的最终响应，
里面已经没有工具调用了——Advisor 连"刚才调过工具"都不知道，于是永远放行。
变异检验里把 order 改成 `-100`，7 条测试红了 6 条（包括"无权限时工具没被调用"）：
工具真的被调了，只是没人拦。ADR-0007 已按实际行为改过来，并留了修订说明。

### 二、流式路径不能用 BaseAdvisor 的默认实现

`BaseAdvisor.adviseStream` 只在 `AdvisorUtils.onFinishReason()` 命中的那一块上调 `after()`。
真实模型把工具调用分块发（名字在头一块、参数在后面几块），**带 finish reason 的收尾块里
通常已经没有工具调用了**。所以流式只能自己逐块看：看到越权当场 `sink.next(拒绝)` + `sink.complete()`
掐断上游。不能攒到最后再判——攒起来的话，`ToolCallingAdvisor` 拿聚合后的响应照样会去执行工具。

这也解释了一个看起来奇怪的现象：流式下被拦时客户端几乎什么都收不到，
因为工具调用块本来就被 `ToolCallingAdvisor` 的 `.filter(!isToolCallResponse(...))` 过滤掉了。

### 三、拒绝必须连工具调用一起换掉

只把正文换成"没有权限"、保留 `AssistantMessage` 里的 `toolCalls`，
`ToolCallingAdvisor` 看到的仍是"要调工具"的响应，工具照跑，客户端拿到"拒绝 + 真数据"。
变异检验（保留 toolCalls）红了 5 条——这个坑真的存在，不是理论。

### 四、权限登记跟着工具走，没登记 = 拒绝

`ToolPermissionRegistry`（domain 接口）+ `ToolPermission(动作, 表清单)`：
- **tools 模块**声明 `querySales → read + sales_order`（只有写工具的人知道它碰哪张表）
- **agent** 侧只认接口，不认识 `querySales` 这个名字
- 查不到一律按拒绝：没人评估过它会碰到什么

动作名用粗粒度动词（`read` / `sendMail`）而不是工具名：把工具名写进角色配置，
等于让"谁能干什么"变成一份工具清单，加工具就要改配置。ADR-0007 的原文
"能不能查 `orders`、能不能发邮件"正好是这两级。

**顺带修正了一处语义**：`Principal.readOnly` 原来的动作集是空的，
意味着"只读身份连查数都不行"——默认角色成了废角色，05 号票一接真实模型就会撞上。
现在它拿到 `read` 动作。通道侧默认角色表同步（`read-only: actions: [read]`），
另加 `guest`（只会 `region` 表）作为"换角色头"的演示用角色。

### 五、Principal 怎么进链

走 Advisor 上下文（`.advisors(spec -> spec.param(PRINCIPAL_CONTEXT_KEY, principal))`），不走 ThreadLocal：
上下文会跟着 `ChatClientRequest` 走完工具循环的每一轮（框架每轮都把 context 原样带下去），
也跟着 Reactor 订阅走；ThreadLocal 在"流式 + 换线程"的组合下会掉，而掉身份的失败姿势是**放行**。

### 六、断言设计与变异检验

7 条 agent 层 + 3 条 HTTP 层。每条都钉在外部可观测事实上（调用计数 / 模型被调了几轮 / 客户端拿到什么），
不去断言 Advisor 内部怎么判。四次变异检验：

| 变异 | 结果 |
| --- | --- |
| order 改成 `DEFAULT_ORDER - 100` | 7 红 6 |
| 不再把 Principal 放进上下文 | 7 红 3（需要放行的那三条） |
| 拒绝时保留 toolCalls | 7 红 5 |
| 摘掉 `authorizationAdvisor` bean | HTTP 层 3 红 2 |

配套改动：`AgentArchitectureTest` 里两条 Advisor 规则的 `allowEmptyShould(true)` 摘掉了——
M0 时主代码没有 Advisor 所以允许空规则，现在有了，留着就等于"有人删光 Advisor 也是绿的"。

### 留给后面的

- 04（脱敏）与 06（记忆）的 Advisor 也要各自定义 order。记忆 Advisor 若排在 `+200`（默认）
  就在循环外面，每轮只记一次；要"每轮都记"就得排在 `+300` 之后，那是另一个取舍，到时候写清楚。
- `querySales` 现在只登记了 `sales_order` 一张表。05 号票真实 SQL 若要 join
  `business.customer` / `business.region`，记得把表补进登记——补漏的代价是拒绝，不是放行。
- 现在的拒绝是"这一轮换成一句话"，模型完全不知道自己被拒了。若要让模型自己换个说法/换工具，
  得把拒绝作为工具结果回给模型（那是另一种设计，不是本票范围）。
