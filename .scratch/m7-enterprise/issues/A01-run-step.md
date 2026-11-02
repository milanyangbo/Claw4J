# A01: run / step 成为系统里的第一等身份

**What to build:** 一次"事"在系统里有了自己的身份。用户提问时入口创建一个 run；这件事做完，
回答后库里的行能答出"这件事做了几步、每步是什么"。进程杀掉再起来，答案还在——
今天这两个问题的答案是"答不出"与"每次都是新的"。

**Blocked by:** None (can start immediately)

**Status:** resolved

- [x] 一次提问对应一个 run，含发起人身份与状态；工具循环**内部**不写任何行（ADR-0016 硬边界）
      ——**P07 起口径微调**：一次工具调用另落一行审计（`kind = tool_calls`），
      ADR-0023 写明了"只松动这一格"，其余内部事件（检索、候选项、自纠错）仍然不落
- [x] step 只落在 run 边界上，kind 至少有模型轮 / 工具调用汇总 / 计划
      （实际四类：`model_turn` / `tool_calls` / `approval` / `plan`）
- [x] 杀进程重启后，能从库里答出每一个 run 做到了第几步
- [x] run 级指标同缝回读：新增 `claw4j.run.*`，与 `claw4j.tool.calls` 同一判定纪律
- [x] 表结构与唯一键用 SQL 用例钉住（含变异验证，先例：语义缓存那批）
- [x] 门禁本地 `./mvnw clean verify` 通过

## Comments

- 2026-10-30 收票。落点：
  - `V6__run_schema.sql`（claw4j-agent）：schema `claw4j_run`，`agent_run` + `agent_step`，
    形状按 ADR-0016，另加 `step.kind`（模型轮 / 工具调用汇总 / 计划）与两个 check 约束
    （状态取值）和唯一键 `(run_id, seq)`。
  - `agent/run/`：Run / Step / StepInput 三个 record + 三个枚举 + `RunStore` 接口 +
    `JdbcRunStore` + `RunPersistenceConfiguration`（`claw4j.run.enabled` 可关，默认开）。
  - `OutboundMessage` 加 `runId`（ADR-0016 预告的破坏性变更），`AskResponse` 跟着透出。
  - `ChatClientAgentPort` 进门开 run、答完记一步并收尾，失败收尾成 `failed` 且**不吞异常**。
  - 用主连接池、SQL 全限定名，不另开第二个 DataSource（避免 `@ConditionalOnSingleCandidate` 失效）。
- 两条口径：① 工具名暂不落 step——`ToolCallLog` 是 ThreadLocal，跨请求会串味，
  等 A04 定下清理时机再填；② 落库失败降级为"这一轮没有 run"（同会话记忆），
  次数记在 `claw4j.run.errors`，别让降级变静默。
- 用例：主链路 5 条并进 `ChatPipelineTest`（agent 模块测试文件已满 10 个，不能新开文件）；
  SQL 缝新建 `RunSchemaSqlTest`（app，4 条）。全量 310 条绿、跳过 0。
- **2026-11-02 复核补勾**（此前只有上面这段收票记录，验收框一直空着）。六条逐条落到证据：
  ① `agent_run` 的 `owner / principal_digest / status` 三列 + `ChatClientAgentPort.openRun`；
  ② `StepKind` 四个取值而不是三个；③ `writtenRunSurvivesAFreshStore` + `RunStore.stepsOf`；
  ④ `JdbcRunStore.RUN_METRIC` 常量 + `count("created" / "steps" / "finished" / "suspended" /
  "resumed" / "reopened" / "charged" / "errors" / "plan_*")` 拼出 `claw4j.run.*`；
  ⑤ `freshTables`、`migrationCreatesRunTablesInTheirOwnSchema`、
  `sequenceNumbersAreAssignedByTheStore`（撞号那条断言点名 `uq_agent_step_run_seq`，不是只看"没抛"）；
  ⑥ `./scripts/verify.sh` 465 条绿、跳过 0。第 1 条按 P07 的口径补了限定，理由见票面。
