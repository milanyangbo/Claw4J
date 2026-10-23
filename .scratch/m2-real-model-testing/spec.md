Status: ready-for-agent

# 测试改连真实模型：删掉两个假模型类

（这不是产品能力，是**测试基座与策略**的改造。决策记录在 **ADR-0011**，本份只描述怎么落地。）

## Problem Statement

M1 结束时，需要走模型的测试有 37 条，全部靠两个假模型驱动：`MockChatModel`（main 里的生产兜底）
与 `ScriptedChatModel`（test 里的编排器）。它们能让链路跑通，但拦不住三类问题：

1. **只在真链路上才出现的缺陷。** M1-07 用真模型跑一遍连抓两个 bug（跨厂商降级丢工具、
   模型内部重试把降级拖成分钟级），ADR-0006 还记过一次"通义不调工具、直接反问口径"。
   共同点是**假模型既不会真的失败，也不会真的需要工具**。
2. **断言的落点被挪走了。** `SalesQueryEndToEndTest` 断言第二轮提示词里带着 `128400.00`，
   而那个数是**脚本常量**——它证明的是"链路能把一串字符传下去"，
   不是"模型会按工具描述去调 `querySales`、会把数据库报错读懂再重写 SQL"。
3. **"你怎么测 LLM 应用"这个面试追问答不好。** 全 mock 只能证明管道通，证明不了会干活。

## Solution

所有需要模型的测试连**真实 DeepSeek**；两个假模型类删除。

- **能端到端的（约 23 条）改成真模型 + 真库**：销售额问答、工具调用、会话记忆、装配可见清单。
  断言只写方向性（拿到数 / 拿不到数 / 不含明文手机号），关键断言给有限重试，温度设 `0.0`。
- **依赖"精确编排模型行为"的（约 14 条）降级为组件级**：调用预算、鉴权计数、脱敏。
  真实 `ToolCallingManager` + 真实预算配置 + 真实 Advisor，
  工具调用指令由测试直接构造（`AssistantMessage` 带 `toolCalls`）而不再由模型生成。
  丢掉的是"模型会自己发起这些调用"这一层，留下的是"组件在这条链路上真的这么工作"。
- **没有 key 是 failure 不是 skip**：被跳过与被排除在外的测试会被
  `scripts/assert-no-skipped-tests.sh` 放过——那正是 M1 那个 P0 的形状。

## Implementation Decisions

- 真模型在测试里**由真实配置装配**（`ModelConfiguration` + `DEEPSEEK_API_KEY`），
  不再由测试自己塞一个 `ChatModel` 实现。这样"厂商适配、路由、降级"这几条真链路也被测到。
- 温度 `0.0`：把随机性压到最低，这是压 flaky 最便宜的一招，先于重试。
- 有限重试最多 2 次，且只在"模型这一轮没调工具"时才重试；重试要打日志，
  否则"它其实一直没调工具"会被重试掩盖。
- `claw4j.model.mock` 三态与 `-Preal-model` profile 一并取消
  （ADR-0006 那一节作废、ADR-0008 的隔离条目作废）；`RealModelIT` 并入常规测试。
- 测试类名回归 `*Test`：不再需要靠 `IT` 后缀躲开 surefire。

## Testing Decisions

- 方向性断言，不钉整句（真模型会加解释性文字）。
- 每条真模型测试都要有一条能证伪的对照：例如"换个问题数字就跟着变"、
  "没权限时拿不到数而不是拿到空结果"。
- 组件级那批必须仍走**真实组件**：真 `ToolCallingManager`（不是自己数次数）、
  真 `AuthorizationAdvisor`（不是复制一份过滤逻辑）。变异检验照旧要求去掉实现就红。
- `skipped=0` 仍然是硬门禁；总数基线随本票更新。

## Out of Scope

- 录制回放（cassette）：ADR-0011 记的回退方案，flaky 或成本失控时才做。
- 通义（备模型）也进测试：`CLAW4J_MODEL_PRIMARY` 一改就能用另一家跑同一批断言，
  但不作为默认档位，不重复烧一份 token。
- 评估器（`RelevancyEvaluator` / `FactCheckingEvaluator`）进 CI：ADR-0008 那条仍是 M3 的事。

## Issues

- `issues/01-remove-fake-models.md`
