# 06: 文档与限制记账对齐

**What to build:** 把这一轮发现的三处事实性漂移修掉，并把 05 留下的测试缺口记进账本：
读 ADR 的人不该读到"装配线还没接"，也不该被一个已经不存在的类名指向空处。

**Blocked by:** 05（guardrails 那一行要等 SDK 绑定层落地后才能写准）

**Status:** ready-for-agent

- [ ] ADR-0010 末尾"`claw4j-capability` 尚未被 `claw4j-agent` 依赖（装配线还没接）"删掉——早已接上
- [ ] ADR-0010 里引用的架构测试类名与现存类一致（`ChannelArchitectureTest` / `ToolsArchitectureTest`
      这类旧名按现状改正）
- [ ] ADR-0009 补一条实现记录：3 秒 ACK、按 `eventId` 去重、主动回推各落在哪个类，附实测证据
- [ ] `guardrails.md` 的偏差表加一行：飞书 SDK 绑定层没有单测，覆盖靠 `feishu-live`，
      并写明补偿是什么、记在哪张票（照"两类沙箱都没有网络隔离"那几行的写法）
- [ ] 若 README 里有通道清单，同步加一行；**不新增解释性段落**（README 是给外部用户的）

**落点:** `docs/adr/0010`、`docs/adr/0009`、`docs/agents/guardrails.md`。

**注意:** 这一票只改"说法与现状不符"的地方，不顺手重写段落，也不把设计论证搬来搬去。

**不要做:** 给 ADR 追加新的决策；把 M3 的规划写进 ADR。
