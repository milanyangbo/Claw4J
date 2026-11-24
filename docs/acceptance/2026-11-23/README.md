# 验收材料（2026-11-23 这一批：A–I 全量复跑）

对应文档：仓库根 `README-capability-tests.md` 的 **A–I 九节全部**。这一批是"从头到尾跑一遍"，
不是只补某几节——所以除了文档要求的几个专项实例，其余都在同一个主实例上跑完。

**结论先放这里**：A / B / C / D / E / F / G / H 全部通过，判据与文档写的一致。

**I（审计）当天 HTTP 侧全部 404**——`AuditController` 没有被注册成 bean，`/api/audit` 从启动起就不存在；
库侧（I5/I7/I8/I9）当天就过了。**2026-11-24 已修**（票 18：补注册 + 修空 `where` 的拼接），
修完把 I 节十条全部补跑，逐条结果见文末"当天的阻断与修复"。

## 材料清单

| 路径 | 是什么 |
| --- | --- |
| `run-instance.sh` | 起实例的统一入口（基础参数在里面，差异用环境变量与追加参数）。下面所有日志都是它产出的 |
| `main-app.log` | 主实例：A1/A2/A4、B1–B8、C5、D1、E1–E3、F1、F8、H1–H3、I5/I7/I8/I9 |
| `main-debug.log` | 同上带 `--debug` 重起一次，为的是拿到条件评估报告（定位 `/api/audit` 404 用） |
| `a3-users.log` | A3：`users=t1,ethan` |
| `c1-messaging.log` | C1 其一：不给 `agent.directory`、`tools.profile=messaging` |
| `c1-toolfilter.log` + `c1-toolfilter.yml` | C1 其二：`java-http` 的 `tool-filter.exclude=simulateSlowCall` |
| `c2-approval.log` | C2/C3/C4：`exec.security=allowlist`、`exec.ask=on-miss`、`required-tools=TodoWrite`、`approval.ttl=60s` |
| `f2-model404.log` + `model-404.yml` | F2/F3：主模型名写错 |
| `f4-unreachable.log` + `f4-unreachable.yml` | F4：`base-url` 指到 9 端口 |
| `f5-allow.log` / `f6-api.log` / `f6-models.log` + 同名 `.yml` | F5/F6：三种启动失败 |
| `f7-utility.log` + `f7-utility.yml` | F7：`roles.utility` |
| `g3-big.log` | G3：超长人设（用 2026-11-22 批的 `bigagent/big.md`） |
| `h5-busy.log` / `h6-timeout.log` | H5（`lane-wait=0`）/ H6（`stream-timeout=2s`） |
| `stream-*.txt` | 原始 SSE 帧：basic / new / tool / failover / busy-a / busy-b / timeout |
| `mcp-java-http.log` | E1 用的 `claw4j-example-mcp-server`，含被 kill 与重启两次的完整日志 |
| `i-section.log` | 2026-11-24 修复后补跑 I1–I4 的那一轮（`RAG=false`） |
| `i6-messages.log` | I6 后半段：`--claw4j.audit.messages=true` |
| 库 `claw4j_accept_all` | 全程共用的一个库（复跑前 303 条审计、3 张进度卡、1 条意图、6 条审批；补跑后 308 条） |

## 复跑

```bash
cd /Users/ethanbyang/projects/java/Claw4J
bash docs/acceptance/2026-11-23/run-instance.sh /tmp/main.log            # 主实例
USERS="t1,ethan" RAG=false bash docs/acceptance/2026-11-23/run-instance.sh /tmp/a3.log
```

脚本读的环境变量：`DB`（默认 `claw4j_accept_all`）、`USERS`、`AGENTDIR` / `AGENTDEF`（置空则不给）、
`RAG`、`DMSCOPE`；其余差异一律作为追加参数传（`--claw4j.…`）。

两处与文档 0 节不同，都是为了让"跑十几遍"成立：

- 加了 `--spring.docker.compose.enabled=false`：local 的 compose 生命周期是 `start-and-stop`，
  应用一退出就把 Postgres 收掉，后面的实例全得等它重起。
- 除主实例外全部 `RAG=false`：主实例已经完整同步过一次（111 份 / 440 段），其余节不依赖检索，
  关掉之后启动 4 秒。主实例仍开着 RAG，B6.0 那两行就是从它取的。

## 实测到的值（复跑会变，只用来对形状）

| 节 | 判据 | 本次实测 |
| --- | --- | --- |
| B6.0 | 份数 / 段数 / 两开关 | 111 份 / 440 段 / 混合检索 开 / 重排 开；重建 111、跳过 0 |
| A4 | 选中的人设与档位来源 | `agent 是 ops…人设来自 examples/agents；工具画像 FULL（来自 人设文件的 tier）` |
| A2 | 两次 `demo_now` pid 相同、时间戳变大 | `68313 1795450853.375` → `68313 1795450858.627`；`demo_echo` 逐字 |
| B1 | 三窗 | 128400 / 99999 / 77777；`querySales ok` 3 |
| B2 | 四个默认工具 | Glob/Read/Grep/WebFetch 各 1；`Write / Edit 未开`；`WebSearch 未配 key，未装` |
| B3 | 三区 + 合计 + 口径 | 128400 / 26000 / 31500 / 185900；`Skill ok` 1 |
| B4 | 逐字 | 与 `run.sh` 直跑输出逐字相同（含标题被当第一句）；`Bash ok` 1 |
| B5 | 两个子 Agent | capacity-triage：堆 1.6%、线程 103；weekly-digest：185900、"没有做环比"；`Task ok` 2 |
| B6.1–6.8 | 数字与出处 | 全部命中（7/5 天、广州 5 天、M6 320 张 68 元、按折后价、万分之五不滚本金、9 折直接给且注明旧版已废止、12 个月、30 天、三条近义各答各的、库存说查不到） |
| B6.9 | 重排行 | 25 次，`候选 20 条，服务返回 20 条`，顺序有变化；失败/降级 0 |
| B7 | 挂起 → 回答 | `questions` 返回两选项（预发推荐/生产）；`answer` 204；回答"你选了生产"；另段 `answer` 409 |
| B8 / G1 | 计数 + 落库 | `TodoWrite ok` 3；卡 `revision=3`，三条全 COMPLETED，无 `activeForm` |
| G2 | 意图四步 | `1177f084 / it1 / 客户数 / armed / 0 / 3 / 86400 / http / it1 / emb null` → 命中后 fired、`fire_count=1` → 冷却内仍 1 → 撤销后 cancelled |
| C5 | 出网与剥离 | 127.0.0.1 被拒（日志 `拒绝出网：url=… host=127.0.0.1 解析到 127.0.0.1`）；example.com 正常；回答里没有边界标记 |
| D1 | 缓存确实关着 | 同一句问两次，`querySales ok` 9 → 11 |
| E1 | 启动 DOWN / 重连 / 运行期 | 启动 `java-http → DOWN(15 秒后重试)`；第 6 次重试后恢复（退避 15/30/60/120/240/480）；kill 后调用 500；重启后第一句 500、第二句恢复（pid 61692） |
| E2 | 超时 | 31–33 秒、500、Spring 默认体；紧接着 `runtimeSnapshot` 成功；日志无新 DOWN |
| E3 | 熔断 | 单工具：`Tool call limit (3) exceeded for tool 'demo_now'`；总预算：`Total tool call limit (6) exceeded for this turn` |
| F1 | 四行 + route | 窗口 65536 / 131072；压缩窗口与装配行相同（65536）；`route=deepseek/deepseek-chat` |
| F2 / F3 | 换家与冷却 | `model_not_found` 1 → 三句后停在 2；`qwen/qwen-plus ok` 1 → 4 |
| F4 | 不可达 | 91.19 秒、回答 2、`route_unavailable`；`model.calls` 总 2（主 1 失败 + 备 1 成功） |
| F5 / F6 | 启动失败点名 | `主模型 … 被 claw4j.model.policy.allow 排除了…`、`api=gemini 不认识。可选 [deepseek, openai-completions]`、`models 是空的…` |
| F7 | 角色 | `utility=独立链 qwen/qwen-plus` |
| F8 | 缓存命中 | `cache-read` 535936 |
| G3 | 预算 WARN | `提示词超预算已截断：22479 → 20106 字符（上限 60000），稳定段 20000 字符`；回答 2；第二问后仍 1 行 |
| H1 | 分帧 | 77 chunk + 1 done，first 1.25 / total 2.00 |
| H2 | 命令 | 1 chunk + 1 done；`model.calls` 113 → 113 |
| H3 | 工具静默 | 38 chunk + 1 done，first 3.04；流里工具名/JSON 命中 0；`Read ok` 2 → 3 |
| H4 | 流式换家 | 1 chunk（`2`）+ 1 done；日志 `流式失败（unclassified，未吐内容），换下一个` |
| H5 | 忙 | 1 chunk（"现在有点忙，请稍后再问一次。"）+ 1 done |
| H6 | 断流 | 97 chunk、**0 done**、2.14 秒；`AsyncRequestTimeoutException` + `response committed already` |
| A3 | 准入 | `nobody` → 401 并点名；`t1` → 200 |
| C1 | 画像 / 过滤 | `工具画像 MESSAGING（来自 claw4j.tools.profile）`，回答"没有 Bash"；`java-http → UP(工具 2 个)`（3→2），检索不到 `simulateSlowCall` |
| C2 | 审批三判据 | `Bash ok` 1；`allowed / allow-once / consumed_at 非空`；审计里成功那对带 `approval_id`、被拦那对 `failed` 且无编号；`always` 落 `exec_allowlist(uname)`，换参数再跑直接执行、审批表 6 → 6 |
| C3 | 资格与过期 | 自批 403 `self_approval`；非审批人 403 `not_approver`；名单外 401；清扫 `2 条超时未处理，已落成 expired`；过期后点 409 `expired` |
| C4 | 普通工具审批 | `TodoWrite` 挂起、`frozen_plan` 空、`allowAlways:false`；批后 `TodoWrite ok` 1、`allow-once` 已消费 |
| I5 | 幂等 | `INSERT 0 1` / `INSERT 0 0`；`having count(*)>1` 返回空 |
| I7 | 调用编号 | `tool_action` 102/102 有值、长度 71、`sha256:` 打头；`agent_run` 112/112 为空 |
| I8 | 子 Agent | `subagent:capacity-triage` / `subagent:weekly-digest` 各 4 行、各 1 个 executionId；工具行挂子编号；无 `parent*` 列 |
| I9 | 五个计数 | 全 404（= 0） |

## 当天的阻断与修复：`/api/audit` 从来没被注册

**现象**（2026-11-23 实测）：`/api/audit`、`/api/audit?executionId=…` 一律 404，`/api/approvals`（根路径）、
`/api/schedules` 也 404——但那两个是**没有对应映射**（审批只有 `/{id}`），不是缺陷。

**排除掉的**：

- 库与开关没问题：`claw4j.persistence.enabled=true`，条件报告里
  `AgentPersistenceConfiguration` 与 `AgentPersistenceConfiguration#auditQuery` 全是 **matched**；
  进度卡、意图、审批、审计都在正常落库（303 条审计）。
- `@AutoConfiguration(afterName=…)` 里那个类名是对的：Boot 4.1.2 的
  `spring-boot-jdbc-4.1.2.jar` 里确实是 `org/springframework/boot/jdbc/autoconfigure/DataSourceAutoConfiguration.class`。

**根因**：`HttpChannelAutoConfiguration` 只注册了 `httpChatController` 与 `approvalController`，
**没有 `auditController`**。`AuditController` 类本身是全的，但没有任何代码把它变成 bean；
`@SpringBootApplication` 只扫 `io.github.claw4j.app`，扫不到 `io.github.claw4j.channel.http`。

```java
// claw4j-channel/src/main/java/io/github/claw4j/channel/http/HttpChannelAutoConfiguration.java
@Bean public HttpChatController httpChatController(…) { … }
@Bean public ApprovalController approvalController(…) { … }
// ← 这里少了 auditController
```

**为什么单测没红**：`HttpEndpointsTest` 是 `new AuditController(…)` 直接构造的，绕过了容器。
所以"controller 写对了"与"controller 被注册了"是两件事，用例只钉了前一件。

**影响**：`README-capability-tests.md` 的 I 节前提（"`local` 带着库，`/api/audit` 就存在"）
在当前代码上不成立。**I1 / I2 / I3 / I4 / I6 五条全部跑不了**，`I10` 的第 1 条排查指引
（"整个接口 404 = 没配库"）在这个案例上也是误导——404 也可能是 controller 没注册。

**修法（2026-11-24 已实施，票 18）**：在 `HttpChannelAutoConfiguration` 里照 `approvalController`
的形状加一个 `auditController` bean；并把用例放进 `ApplicationAssemblyTest`——那里起真端口、
走真 HTTP，钉的正是容器这一层（`HttpEndpointsTest` 是 `new` 的，加断言也钉不住注册）。

补注册之后**又露出来一层**：不带任何参数打开这一页（I1 就是）返回 500。
根因是 `JdbcAuditQuery` 用 `new StringJoiner(" and ", " where ", "")`，一个条件都没有时
`toString()` 仍是 `emptyValue`，拼出 `… audit_event where  order by seq desc`。改成最后再决定
要不要加 `where`。两处都修完，`./scripts/verify.sh` 全绿。

### 修复后的 I 节复跑（2026-11-24）

| 条 | 结果 |
| --- | --- |
| I1 | 一轮 4 行、`sequence` 倒序（314→311）、`occurredAt` 13 位毫秒、`actor` 是 `{type,id}`、整页无 `runId` / `argsDigest` / `approvalId` |
| I2 | 四变体逐格符合（`agent_run` 无工具三格；`tool_action` 有 `toolName`/`toolCallId`/`redaction`；`inbound` 有 `accountRef` 与 `resultCount=1`、无 `deliveryKind`；`outbound` 有 `deliveryKind=text`、无 `resultCount`；消息行没有 `sessionKey`） |
| I3 | 三条冲突全 400，文案与文档一致；坏游标 400 |
| I4 | `limit=2` 两页不重叠、游标严格更小；`limit=0` 钳成 1 条。`limit=1000` 钳到 500 **没验到**：库里只有 308 行 |
| I5 | `INSERT 0 1` / `INSERT 0 0`；`having count(*)>1` 空 |
| I6 | 默认关时空页、库里 0 行；打开后进出各一条，字段同 I2 |
| I7 | 工具行 102/102 有 `sha256:` 71 位；运行行 112/112 空 |
| I8 | 两个子 Agent 各 4 行、各 1 个 executionId；无 `parent*` 列 |
| I9 | 五个计数全 404（= 0） |
| I10 | 404 现在只剩"没装查询"一种解释（controller 已注册） |

证据：`i-section.log`（I1–I4）、`i6-messages.log`（I6）。

## 收掉

```bash
docker exec claw4j-postgres psql -U claw4j -d postgres -c "drop database if exists claw4j_accept_all;"
```

库**先留着**：`claw4j_accept_all` 里有 303 条审计与 6 条审批，正是 I 节修好后要复跑的现场。
要收就说一声。
