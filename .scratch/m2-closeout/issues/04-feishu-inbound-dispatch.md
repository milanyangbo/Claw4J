# 04: 飞书入站调度：去重 → 立刻 ACK → 异步执行 → 回推

**What to build:** 一个纯 Java 的入站调度层：接一条**已经解析好的**入站事件，负责
"按 `eventId` 去重 → 立刻 ACK → 在 executor 上跑 Agent → 用回推接口发结果"。它不认识飞书 SDK 的
任何类型，所以能进默认构建，用假发送 + 可控 executor 断言"入站不等 Agent"。

这一层是 ADR-0009 里"3 秒内 ACK、异步编排、主动回推"的具体承载；也是四项做深里唯一还没演示过的
那一项在代码上的落点。

**Blocked by:** 01, 02, 03 —— 顺序不是排期偏好：三处缺陷里有两处是"边界比写在纸面上的宽"，
通道进来的是真人之后，后果从"演示时模型多调了一个工具"变成"某个真实用户拿到不该看的数据"。

**Status:** resolved

**Demo:** 用假事件源连发两条同 `eventId` 的事件，Agent 只被调用一次、回推只发出一条；
把 Agent 卡住不返回，ACK 仍然立刻返回。

- [x] 一条事件进来 → ACK 在 Agent 开始之前返回（可控 executor 卡住 Agent，ACK 已返回）
- [x] 同一 `eventId` 第二次进来 → 仍然 ACK，但 Agent 只被调用一次（不 ACK 会触发下一轮重推）
- [x] Agent 抛异常 → 回推一条说清失败的文本，而不是群里什么都没有
- [x] 未登记的用户 → 回推"没有权限"并记 WARN；绝不"认不出就放行"，也不"认不出就静默丢弃"
- [x] Agent 跑得慢也不阻塞事件线程：两条不同 `eventId` 的事件能并行在 executor 上跑
- [x] 超长回答按段落切成多条顺序发送，切分不把代码块或表格拦腰截断
- [x] 这一层不依赖任何 SDK 类型，也不往 `channel.spi` 与 `domain` 加东西（架构测试钉住）
- [x] 变异：把 ACK 改成"处理完再返回" → 第 1 条变红；把去重去掉 → 第 2 条变红

**落点:** `claw4j-channel` 的 `channel.feishu` 包内：两个包内接口（入站事件、回推发送）+ 调度类。
测试新增一个文件（该模块现有 2 个，上限 10），方法数 20 以内。

**注意:** 去重是进程内带 TTL 的缓存，不引 Redis——长连接是集群投递、不支持广播，单实例是既定形态。

**不要做:** 流式增量更新（要额外调消息更新接口并处理限频）；卡片消息与按钮回调；多实例去重；
公网回调端点。

## 落点

- 新增包 `io.github.claw4j.channel.feishu`（+ `package-info` 写清两层怎么分）：
  - `FeishuInboundEvent` / `FeishuReplySender` —— 两个包内契约，只含 JDK 类型；
  - `InboundDispatcher` —— 去重 → ACK → 异步 → 回推，`accept()` 返回即 ACK；
  - `MessageSplitter` —— 按"块"切：围栏代码块、表格、段落；块内超限时按行切，
    代码块每段重放围栏（带语言标记）、表格每段重放表头。
- **落点比票面多一个 `MessageSplitter`**：切分规则有它的独立判据（什么是"拦腰截断"），
  埋在调度类里会让 dispatch 那段被 80 行字符串处理淹没。它没有自己的测试文件，
  由调度层那两条用例（超长代码块 / 超长表格）从入口穿到，符合 testing.md 的"工具类靠用到它的功能测"。
- **SDK 绑定将来放子包 `channel.feishu.sdk`**，这样 ArchUnit 能用"精确包名"
  （`channel.feishu` 不含子包）钉住"调度层不碰 SDK"，而不是靠人看 import。
- 去重 `SeenEventIds`：`ConcurrentHashMap` + `compute` 原子判重（两条重推同时到达只有一条能过），
  每条按自己的时间戳过 TTL（不是"表满了才清"，否则 TTL 在小流量下从不生效），
  另设 1024/8192 两道水位兜住内存。测试用可推的 `Clock` 验 TTL，不睡线程。
- 认人失败与线程池满这两条"没有任务可跑"的分支在事件线程上直接回一句（一次小发送）：
  前者不回用户以为机器人坏了，后者已经 ACK 过、飞书不会重推，不回就永远没人应。
- 测试新增 `InboundDispatchTest` 一个文件、12 条；`ChannelBoundaryTest` 加两条规则
  （调度层不依赖 `com.larksuite..`；`channel.spi` 不反向依赖飞书包）。
  两条会挂死的用例加了 `@Timeout(20)`——回归成同步时应该是"红"，不是"构建卡住"。
- 变异三次全部验过并还原：
  ① `executor.execute(...)` 改成直接调 `handle(...)` → `ackReturnsBeforeTheAgentRuns` 红
  （"accept 里就跑了 Agent"）；② 去重判断短路掉 → `duplicateDeliveryRunsOnceButStillAcks`
  与 `redeliveryAfterTheWindowRunsAgain` 同时红；③ 切代码块时不重放开围栏 →
  `oversizedCodeBlockKeepsFencesBalanced` 红（"每段都要重新开头围栏"）。
- 结果：全仓 176 条用例、失败 0、跳过 0；channel 覆盖率 80.5% → 82.9%；
  该模块测试文件 3 个（上限 10）、单文件最大 15 个方法（上限 20）。
