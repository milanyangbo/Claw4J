# 票 01 把 14 份迁移合并成 4 份基线

**Status:** resolved
Blocked by: —
裁决来源：无需 ADR——沿用 ADR-0005 的"Flyway 版本化、只有一个入口"与 `CONTRIBUTING.md`
「迁移与种子」的基线口径（2026-11-09 / 11-10 / 11-19 三轮之后第四次），用户当场指名的这一件事。

## 做什么

`claw4j-agent` 的 `V3`..`V16` 收成四份，一个 schema 一份：

| 文件 | 吸收了什么 |
| --- | --- |
| `V3__session.sql` | 原 V3 + 进度卡（V7）+ 事件条件意图（V8） |
| `V4__rag.sql` | 原 V4，不动 |
| `V5__run.sql` | 原 V5 + 来源键与删 `actor_role`（V9）+ 执行决策事实（V10）+ 终态配对（V11）+ 消息审计列与假名密钥（V12）+ 工具调用编号（V13）+ 归属与会话两把键（V14）+ 消息行必填互斥（V15）+ `run_id → execution_id`（V16） |
| `V6__channel.sql` | 原 V6，不动 |

重写的时候只做两件事：把补丁叠成最终形状；把注释改成现在时（不再有"V14 加了两列"、"历史行先归一"、
"改名之前"这类叙述）。设计一条没动。

## TDD 用例清单

| 判什么 | 用例名 | 先红的样子 | 结果 |
| --- | --- | --- | --- |
| 历史里只有六份基线 | `BuildContractTest#migrationsFromEveryModuleLandInOneHistory` | 旧文件还在时断言 1..6 红在"实际有 1..16" | ✅ |
| 合并后的形状没漏东西：五张表、审计的约束与索引都在 | `PersistenceSchemaTest#migrationLeavesOnlyTheTablesThatSurvived` | 漏一张表或少一条约束即红 | ✅ |
| 执行编号仍必填（约束名没在合并里丢） | `PersistenceSchemaTest#anAuditEventWithoutAnExecutionIdDoesNotLand` | 少了 `ck_audit_event_execution_required` 时红在"插得进去" | ✅ |

## 复核

复核：2026-11-23 `./scripts/verify.sh` 全绿（失败 0 / 错误 0 / 跳过 0）。**形状比对**：整理前的库
（`claw4j_squash`）与整理后的库（`claw4j_squash2`）各查一次 `information_schema` / `pg_constraint` /
`pg_indexes` / `pg_description`，差异只有本轮有意改写的注释；比对中发现并补回两处漏写
（`ck_audit_event_actor_present`、`ck_audit_event_channel_message_only`），补完后四类对象逐项一致。

## 缺口

- **旧库要重建**：本地已有的 `claw4j` 库记着 V3..V16 的账，而 V7..V16 的脚本已经不在了，
  Flyway 会报 applied-but-not-resolved。drop 之后重启一次即可（`CONTRIBUTING.md` 已写）。
- **两份比对用的库留在本地**（`claw4j_squash` 整理前 / `claw4j_squash2` 整理后），要复跑比对时
  直接查它们；清掉要人来点。
