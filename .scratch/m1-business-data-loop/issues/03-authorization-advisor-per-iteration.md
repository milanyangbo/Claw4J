# 03: 鉴权 Advisor —— 在每一次工具迭代上都校验

**What to build:** 鉴权 Advisor 落到链上，order 排在工具调用循环**内部**，于是模型的每一次工具迭代都会过一遍校验；不通过就短路，不调 `chain.next()`。工具调用能不能发生由 Principal 决定，不由模型决定（ADR-0007）。

**Blocked by:** 01

**Status:** ready-for-agent

**Demo:** 换一个角色头问同一句话——工具一次都没被调用，回答是明确的"没权限"，而不是空数据。

- [ ] Advisor 同时实现 `CallAdvisor` 与 `StreamAdvisor`，并覆写 `getName()`
- [ ] order 排在工具循环内部（小于 `HIGHEST_PRECEDENCE + 300`），使每次迭代都生效
- [ ] 无权限时工具**一次都没被调用**——用调用计数证明，不是靠"回答里没出现数字"来猜
- [ ] 返回的是明确的"没权限"，不是空数据集。**这两者在客户端看起来完全一样**，必须有断言区分
- [ ] 多轮迭代场景：模型在第二轮再次尝试调工具时同样被拦（这是 order 位置的意义所在）
- [ ] 流式路径同一条断言（SSE 输出里同样没有工具结果，且是"没权限"）
- [ ] 粒度为表级与动作级；不做行级/列级

**注意**：只实现 `CallAdvisor` 的 Advisor 在 `.stream()` 上**既不报错也不生效**（`BaseAdvisorChain` 给流式取的是 `getStreamAdvisors()`）。ArchUnit 已经拦编译期，但这条票要的是行为断言——编译过得去不代表拦得住。匿名 Advisor 的 `getClass().getSimpleName()` 是空串，不覆写 `getName()` 会让观测上下文抛 `advisorName cannot be null or empty`。

**不要做**：登录、注册、会话鉴权体系（ADR-0007 明确不做）。Principal 由通道解析好后交给 Agent，Advisor 只消费它。
