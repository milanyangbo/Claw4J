# 01: 可观测：一次提问到底花了多少 token

**What to build:** 在 `ChatModel` 这一层量出每次调用的 token、次数、耗时，在工具这一层量出
每个工具被调了几次、成没成、有没有被鉴权拒掉，全部进 Micrometer；`/actuator/metrics` 能查到。

**Blocked by:** 无。它与后面五张票的关系是反的——**后面每一张都要用它证明自己做成了**，
所以先落。

**Status:** resolved

**Demo:** 问一句"上周华东成交额多少"，然后在 `/actuator/metrics/claw4j.model.tokens` 里
看到 `kind=prompt` 与 `kind=completion` 两个数，加起来就是这一次提问的真实成本；
把主模型的 base-url 指到没人听的端口再问一次，`route=backup` 的计数从 0 变成 1。

- [x] 每条路由被包一层计量，`route` 标签就是路由名（`deepseek` / `qwen`），
      于是"降级了几次"= `route` 为主那条之外的另一条的调用数，不需要在路由类里再埋计数器
- [x] 同步与流式都记：`claw4j.model.calls{outcome}`、`claw4j.model.duration{stream}`、
      `claw4j.model.tokens{kind}`（`prompt` / `completion`）
- [x] 流式只认最后一帧的 usage：逐帧累加会把总量算成 0（多数供应商只在末帧给）
- [x] 拿不到 usage 时不记 token，**不拿估算值冒充真实值**；`calls` 照记
- [x] 工具调用按 `tool` + `outcome`（`ok` / `error` / `denied`）计数；
      `denied` 由鉴权 Advisor 报，那是唯一知道"被拒了"的地方（工具没被执行，包装层看不见）
- [x] 装 `spring-boot-starter-actuator`；`metrics` 端点开在**独立端口且只绑本机**——
      指标和业务端口混在一起，等于谁能发消息谁就能看指标
- [x] 变异：把 `recordUsage` 里记 token 的两行删掉 → 第 2 条变红；把流式的
      `doOnComplete` 改成 `doOnNext` 里记 → 第 3 条变红（会记成 0 或重复累加）

**落点:** 新包 `io.github.claw4j.agent.observability`（`MeteredChatModel`、`MeteredToolCallback`）。
装配改 `RoutingModelConfiguration`（每条 route 包一层）与 `AgentAutoConfiguration`
（工具包装、给鉴权 Advisor 传 registry）。配置在 `application.yml` 的 `management.*`。

**注意:** 装饰在**每条 route 上**而不是整个 `RoutingChatModel` 外面。包在外面只能知道
"这次请求成功了"，分不出是主答的还是降级后备答的；而"降级次数"这个数没有它就得在
`RoutingChatModel` 里再写一个计数器，多一处状态。

**不要做:** 把 token 落库做成本报表；接 Prometheus 服务端（加 `micrometer-registry-prometheus`
是下一件事，本票只保证 `/actuator/metrics` 与 `MeterRegistry` 可用）；给每个 Advisor 都加计时。

## 实现记录（2026-10-27）

- 新包 `agent.observability`：`MeteredChatModel`（包在每条 route 上）、`MeteredToolCallback`。
- `RoutingModelConfiguration` 每条 route 各包一层；`collectToolCallbacks` 多一个 `MeterRegistry` 参数，
  父与子 Agent 的工具都经这一处包装——子 Agent 调的工具同样是真实成本。
- `AuthorizationAdvisor` 加一个双参构造，`denied` 从那里报。默认 `Metrics.globalRegistry`，
  没装 actuator 的调用方也不会 NPE。
- actuator 独立端口 9090 且 `address: 127.0.0.1`。指标里有"谁被拒了几次"，
  和业务端口混在一起等于"能发消息"就"能看运营数据"。

**变异验证里发现的一处用例无效**：第一版流式用例是"前两块没有 usage、末帧有"，
把 `doOnComplete` 改成 `doOnNext` 逐帧记——**测试照样绿**。因为没 usage 的帧直接跳过，
逐帧和末帧结果一样。真实的错误形态是**每帧都带累计值**（多数供应商就是这样），
逐帧累加会记成 36。改成三帧都给 `usage(12,34)` 后再变异，报
`expected: 12.0 but was: 36.0`，用例这才守住它声称的东西。

**未做**：`micrometer-registry-prometheus`。`/actuator/metrics` 能查到就够了，
接一个 Prometheus 服务端是部署问题，不是代码问题。

**真链路验证（起一次应用）**：`CLAW4J_FEISHU_ENABLED=false`、业务端口 18080、
actuator 9090，问一句"你好，只回答两个字：收到"：

```
POST /api/chat  → {"chatId":"http:u-verify","text":"收到"}
GET  9090/actuator/metrics/claw4j.model.tokens?tag=kind:prompt      → 731
GET  9090/actuator/metrics/claw4j.model.tokens?tag=kind:completion  → 1
GET  9090/actuator/metrics/claw4j.model.calls                       → 1 次，route=deepseek
GET  18080/actuator/metrics                                        → 404
```

最后一行是这一票的另一半：指标确实**不在**业务端口上。
prompt 731 vs completion 1 也说明这两项不能合并——把它们加成一个"总 token"
会让人以为成本随回答长度走，而实际上提示词（系统提示 + 工具定义）才是大头。
