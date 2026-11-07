
## 修订（2026-11-07 评审）：这张票的"无 key 也能起"已被推翻

**不要再按它加回 mock 兜底。** 今天的取向是**没有 key 就启动失败**：

- `RealChatModelCondition` 只装"取得到真值"的那一家；一家都取不到时装配方法直接抛，
  fail fast 并说清去哪配（`claw4j.model.*.api-key`）。
- 与 OpenClaw 同一条原则：**凭据类错误是永久的，不重试也不降级**——它文档里的原话是
  "permanent account and credential errors … fail fast instead of retrying forever"。
- mock 兜底的代价是"本地跑着 mock 却以为在跑真模型"：假模型既不失败也不需要工具，
  只在真链路上出现的缺陷永远验不出来。完整的账在 ADR-0011。

票面第 3 条（"无 key 时自动生效"）与 Answer 里"应用无 key 启动成功"只描述当时。
