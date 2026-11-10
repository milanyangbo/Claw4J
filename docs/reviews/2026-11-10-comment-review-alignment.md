# 注释重写与对齐核查（2026-11-10）

## 这次做了什么

重写 347 个 Java 文件的注释与 Javadoc，代码一行没动。范围是 8 个模块的 `src/main` 与 `src/test`，不含 `legacy/`。
另有 12 个文件没改：里面没有可改的注释。

标准见 `docs/agents/comments.md`：Google Java Style 7.1 / 7.2 / 7.3 / 4.8.6 的硬格式，加本仓库七条（Javadoc 内禁 Markdown、一段一意、不自称「我们」、引用外部系统必须带得出处、不写变更史……），语言用 humanizer-zh 的判据过一遍。

改注释时顺带核了两件事，分开放：

- 注释与代码不符 → 直接按代码改注释，本文第三节。
- 代码本身与 OpenClaw / Spring AI 不对齐，或设计上可疑 → 只记录，本文第一、二节。**没有改代码。**

## 机器校验

- `git diff` 里的非注释改动行：**0**。
- `test-compile` 通过；`./scripts/verify.sh` 结果见提交说明。
- Javadoc 内残留 Markdown 标记 2 处：一处是脱敏手机号 `138****`（保留），另一处已改。
- **两个子 agent 越界改到了代码，已修**：`AuditProperties` 的 `private boolean enabled = true;` 被删掉，
  `WeightedScoreJoiner` 的 `join(Map)` 签名被复制成两份。原因是注释块紧贴代码，Edit 的 `old_string`
  跨过 `*/` 边界时会把下面的代码一起吃掉。这是并行改注释的真实失败模式，下一次要在批次说明里
  明确禁止跨 `*/` 的批量替换。

## 一、需要人裁决（高）

1. **`/goal` 可能永远读不到目标** — `agent/goal/GoalCommand.java:116`。
   `execute` 传的 sessionKey 带段号，而 `SessionGoalStore.of` 按会话节点键查 `session_entry.session_key`，
   模型工具那条走的是 `ContextKeys.CONVERSATION`。要么改传入 `conversationKey`，要么把 store 的定位键
   与三条 SQL 一起改。注释已按现状写，代码未动。

2. **`SessionMemoryAdvisor` 会压缩，但注释说它从不压** — `agent/session/SessionCompactor.java:14`、
   `SessionMemoryConfiguration.java:137`。反编译 0.10.0 的字节码可见 `after()` 在 compactionTrigger 与
   Strategy 都配了、且本轮没请求工具调用时会调 `SessionService.compact`。本仓库为避免重复，把压缩放在
   进模型之前。注释已改成事实。**要定的是：`SessionCompactor` 还需不需要留。**

3. **配了连不上的 MCP server，装配期不会失败** — `capability/mcp/ConfiguredMcpServersAutoConfiguration.java:43`。
   原注释说装配期即启动失败，实际走 `McpToolProvider` 的 fail-soft：记 DOWN、后台退避重试，只有配置写错才抛。
   注释已按代码改。**若本意是 fail-fast，那要改的是代码。**

## 二、与 OpenClaw 的默认值或判据不一致（中）

| 位置 | 我们 | OpenClaw | 说明 |
| --- | --- | --- | --- |
| `SessionMaintenanceProperties#mode` | 默认 `warn` | 默认 `enforce`（`docs/gateway/config-agents/sessions.md:129`） | 注释已改成「比它保守」。要对齐就要动默认值 |
| `SessionMaintenanceProperties#maxEntries` | 默认 500 | 默认 5000（同文件 `:131`） | 注释已写明差异 |
| `McpToolProvider` 退避 | 30 秒起、封顶 10 分钟 | 15 秒起、封顶 600 秒（`src/agents/mcp-startup-backoff.ts:125`、`:158`） | 只有上限一致。注释已改成「上限一致」 |
| `FeishuLongConnectionChannel:277` 落库失败 | 往上抛，让平台重推 | 关掉 socket 并重建连接（`docs/channels/feishu/setup.md:45`） | 实现差异属实，注释已不再暗示我们照做 |
| `SessionKeys#...` 会话粒度 | 带通道（对应 `per-channel-peer`） | `session.dmScope` 默认 `main`，DM 共用一个主会话（`docs/concepts/session.md:162`） | 注释已改；要对齐要改分段粒度 |
| `Slack/DingtalkChannelAutoConfiguration` | 凭据不齐时仍注册 principalResolver | —— | 原注释称「一个 bean 都不注册」，与实际不符，已按实现改 |

## 三、注释写反了，已按代码改正

- `DeliveryTarget`：写「一对多，一件事可登记多个地址」，实际是单地址。
- `ApprovalGate:10`：写「把这一步落成 awaiting、挂起 run」，接口只有 `redeem` 与 `ask`，没有这个能力。
- `ConversationApprovalGate:14`：首句写「这里什么都做」，实际是正常主路径（ask → 登记 → 推卡片）。
- `AuthorizationAdvisor:395`：写「审计留 90 天」，实际 `AuditLedger.RETENTION` 是 30 天。
- `GenerationAudit:15`：写「一行一条的 JSONL」，实际用制表符拼接，扩展名 `.tsv`。
- `ReviewGate:70`：写「不合格的坏在哪要留在审计里」，代码遇不合格直接返回空，没有这条审计。
- `SessionMaintenance:64`：写「桶级清理把 cron 段排除在外」，SQL 里只有 `last_interaction_at < ?`。
- `ProcessRunner:3`：写「全仓只有 LocalProcessRunner 实现」，实际有三个实现。
- `LocalProcessRunner:13`：写「例外写在 ProcessBoundaryTest」，该测试类不存在。
- `ProcessStdioMcpEndpoint:22`：把 `gh`、`himalaya` 说成 Python 包，两者都是独立 CLI。
- `ExecViolationException:3`：边界写成「目录、超时」，exec 不判目录，超时由 `ExecSpec` clamp。
- `AuditProperties` / `AuditLedger`：出处写 `commit f533469`，改用 `docs/gateway/protocol/ledgers.md:17`。

## 四、出处核不到，已把声称删掉

`never falls back to lastChannel`、`OVERLOADED_REPLY` 逐字相同、`getOrder() { return 0; }` 出自官方示例、
`localhost:4318` 回落、`createKeywordScoreScale` 的配置键已撤、`SubagentDelegation.TOOL_NAME` 取自 Claude Code、
`README` 里的 200+ `ok=false` 英文引语——这些在 `/tmp/oc-ref-2` 或本地 jar 里都查不到，按标准删掉声称，
只留代码本身的行为。**没有一处是编出来的行号。**

## 五、留着没动的

- **字符串字面量里的声称**：`SessionMemoryTest` 的「OpenClaw 默认也是只报告」、`ChatPipelineTest` 的
  「纯命令消息绕过队列和模型」、`KnowledgeRetrievalTest` 的「官方 joiner」、`FeishuApprovalCard` 里
  `lark_md` 的 `**`。字符串不是注释，本批不许改；前两条能查到最近的位置（`docs/concepts/memory-search.md:318`、
  `docs/reference/session-management-compaction/maintenance.md:154`），另一条没有直证。
- **用例名声称覆盖但没覆盖**：`SlackChannelTest:206` 说测了 `X-Slack-Retry-Num`，两次调用都传 `retryNum=null`。
- **死代码**：`ScheduleTools.runIdOf` 没有调用点；`DingtalkSignature.MILLIS_DIGITS` 没有引用。
- **索引键名**：`KnowledgeIndexer` 用 `index_meta_v1`，OpenClaw 是 `memory_index_meta_v1`。形制对齐了，键名没对齐。
- **测试注释与实现脱节**：`SearchSqlTest` 说覆盖语义缓存，文件里已无缓存用例（缓存已改落 Redis）；
  `PersistenceSchemaTest:49` 说「表是 V6 建的」，与该文件实际覆盖的 V3/V5/V6/V7 对不上。
- **ADR 引用漂移**：ADR-0018 仍把 `OutboundDelivery` 写在 `spi` 下（实际在 `domain.port`）；
  `domain/package-info.java` 指向 ADR-0004（边界检查实际在 enforcer 与 `CoreBoundariesTest`）；
  `channel/package-info.java` 与 ADR-0010 的包清单都过期（`channel.spi`、`channel.a2a` 已不存在）；
  `WecomCallbackController` 把重试约定挂在 ADR-0009（选型）上，可能该指 ADR-0022。

## 六、本次不做的

- 不改任何代码、不改 `docs/adr` 与票面。上面每一条要落地，都得单独开票。
- 不把文档里读不懂的表述改成人话——按约定，ADR 与票是给 AI 读的，保持原样。
