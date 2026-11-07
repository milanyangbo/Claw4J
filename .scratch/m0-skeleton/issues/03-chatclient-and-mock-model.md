
## 修订（2026-11-07 评审）：这张票的"无 key 也能起"已被推翻

**不要再按它加回 mock 兜底。** 今天的取向是**没有 key 就启动失败**：

- `RealChatModelCondition` 只装"取得到真值"的那一家；一家都取不到时装配方法直接抛，
  fail fast 并说清去哪配（`claw4j.model.*.api-key`）。
- 原则与 OpenClaw 一致的那一句：**凭据类错误是永久的，不重试也不降级**。
  它原文（`docs/channels/slack/transports.md:71`，说的是通道凭据）：
  "Permanent account and credential errors such as invalid auth, revoked tokens,
  or missing scopes fail fast instead of retrying forever"。
- 但"启动就失败"这一条**是我们自己的选择，OpenClaw 没有**：它是多供应商网关，
  缺某家的 key 照常启动、调用时才报 "No API key found for provider"。我们只服务
  一个部署、一个主模型，起得来但答不了比起不来更难查。完整的账在 ADR-0011。
- mock 兜底的代价是"本地跑着 mock 却以为在跑真模型"：假模型既不失败也不需要工具，
  只在真链路上出现的缺陷永远验不出来。完整的账在 ADR-0011。

票面第 3 条（"无 key 时自动生效"）与 Answer 里"应用无 key 启动成功"只描述当时。
