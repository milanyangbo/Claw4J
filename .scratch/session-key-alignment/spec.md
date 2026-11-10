# 会话键与重置策略照 OpenClaw 重做

## 这份 spec 是什么

`docs/adr/0029-session-key-and-reset-alignment.md` 的落地。两件事一起做，因为它们改的是同一个函数
（`SessionKeys`）和同一个判定（`SessionEntries#segment`），分开做会让键的形状改两遍。

范围：私聊按 `dmScope`（默认 `main`）分段、群聊按 `groupScope`（默认 `per-group`）分段、
重置默认 `none`、`/new` 不受策略限制。不做的部分见 ADR 的「不做」。

## 票与顺序

只有一张票：`issues/01-session-keys-follow-peer-kind.md`。它必须一次做完——中间状态下
（键已经是 main、但 `/new` 还受策略挡）会让人对着一句话反复试。

## 共同的判据

- **反例先跑红**：每条都写成"把实现改回旧行为，只有这一条红"。
- **门禁余量**：`claw4j-agent` 已是 10 个测试文件、`SessionMemoryTest` 与 `ChatPipelineTest` 都是 20 条
  `@Test`（满）。本票**不新开文件**，键形状的用例改在 `SessionMemoryTest` 已有的那条方法里，
  `/new` 那一格用 `ToolGovernanceTest`（19 条）的空位。
- 键变了，库里历史变孤儿：**不写迁移**，按 ADR 的代价 1 走，旧行只读留存。
