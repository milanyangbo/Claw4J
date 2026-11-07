# m0 → m8 全量票面与 OpenClaw 对齐评审（2026-11-07）

## 方法与口径

- 范围：`.scratch/` 下 m0-skeleton 到 m8-durable 共 **72 张票**（含 m2-closeout），11 份 spec。
  不含 `adr-0026` / `adr-0027`（昨天刚做过逐条复核）。
- 判定五档，每档的判据：

  | 判定 | 含义 |
  | --- | --- |
  | **一致** | 我们和 OpenClaw 在同一个概念上同一个口径（两边都有原文证据） |
  | **自研** | OpenClaw 没有这块，属自研能力，不算偏差 |
  | **故意不同** | 不一样，但票面或 ADR 写明了理由 |
  | **不一致** | 不一样且没有理由，或票面说的与今天的代码不符 |
  | **已过期** | 票面描述的东西今天已经不存在（被后续票 / ADR 推翻） |

- 对照物：`/tmp/oc-ref-2`（OpenClaw 源码，1.0G）的 `docs/` 与
  `packages/gateway-protocol/src/schema/`。
- **每条结论都要求 `文件:行` 证据**；找不到证据的写「未找到证据」，不推测。
- 逐票明细在 `.scratch/review-2026-11-07/batch-{a..e}.md`。

## 总表

| 批次 | 里程碑 | 票数 | 一致 | 自研 | 故意不同 | 不一致 | 已过期 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| A | m0-skeleton + m1 | 12 | 1 | 6 | 0 | 2 | 3 |
| B | m2（能力 / 真模型 / 收尾） | 16 | 4 | 4 | 4 | 3 | 1 |
| C | m3 + m4 + m5 | 14 | 6 | 5 | 2 | 0 | 1 |
| D | m6 + m7 | 18 | 4 | 5 | 0 | 2 | 7 |
| E | m8-agent-quality + m8-durable | 12 | 3 | 3 | 3 | 0 | 3 |
| — | **合计** | **72** | **18** | **23** | **9** | **7** | **15** |

**结论一句话**：72 张票里 41 张（一致 + 自研）今天仍然成立；**9 张"故意不同"是设计选择，应当留档而不是修**；
真正要处理的是 7 张不一致 + 15 张已过期——而这 22 张的问题几乎全在**票面与文档的指针**上，只有一处落在产品行为上。

## 一、要动手的（按代价从低到高）

### 0. `ScheduleTools` 告诉模型一个不存在的端点（唯一的功能性问题）

`claw4j-agent/.../schedule/ScheduleTools.java:282` 的工具描述原文：

> `，跑出来的每一个 run 都能用 GET /api/runs/{id} 取回全文`

这句会**进模型的提示词**。端点已在 V24 删掉，模型于是会向用户承诺一个拿不到的东西。
这不是注释问题，是「模型被喂了错的能力清单」——与 `docs/capability-tests.md` 里那条
「模型的话会编，计数不会」是同一类风险，只是这次错的是我们自己的工具描述。

### 1. 已删端点 / 已改名类的死指针（6 处 + 6 处）

`GET /api/runs`（V24 已删）与 `RunResumer`（已删）：

| 位置 | 性质 |
| --- | --- |
| `NotifyPolicy.java:15` | javadoc：「结果查不到由 `GET /api/runs/{id}` 保证」 |
| `ScheduleScanner.java:215` | javadoc：「`GET /api/runs` 那一侧仍然查得到」 |
| `HttpChannelAutoConfiguration.java:94` | javadoc：拿 `/api/runs` 当「端点仍在只是 404」的范例 |
| `FeishuChannelAutoConfiguration.java:116` | javadoc：「那是 `GET /api/runs/{id}` 那一读的事」 |
| `TaskLauncher.java:15` | `{@link RunResumer}` 指向已删的类 |
| `HttpEndpointsTest.java:25` | 类注释仍写着 A05 的端点 |
| `README.md:443` | 升级说明附近仍提到该端点 |

`DomainBoundaryTest`（已改名 `CoreBoundariesTest`）——**7 处**，其中 4 处在 `src/main`：

`domain/port/package-info.java:5`、`domain/package-info.java:4`、`ApprovalVerdict.java:13`、
`ApprovalLedger.java:35`、`ToolProfileTest.java:26`、`docs/adr/0010-*.md`。

> 迁移里的旧引用（`V6` / `V12` / `V16`）**不动**：那是不可变的历史，改它等于改已执行过的迁移。

### 2. 死代码与缺失的回归（2 处）

- `FaultTolerantSessionService#deleteExpiredSessions`（`:82`）**生产路径没有调用方**，只有测试在调。
  而 m3-04 票面与 ADR-0026 都还在引用它——读代码的人会以为「过期就会删」默认生效，
  实际要 `mode: enforce` 才成立。
- m2-capability-07 承诺的 `BudgetAcrossLayersTest` / `PermissionsAcrossLayersTest` **不存在**。
  「三层（脚本 / 工具 / 子 Agent）共用同一份预算与鉴权」今天没有测试守着。

### 3. 两处需要裁决，不是改字

- **m5-02**：票面验收写「这两段 YAML 原样贴进 `application.yml` 就能用」，
  但它引用的 `application-mcp-third-party.yml` 不存在（只有 `application-mcp-stdio.yml`）。
  是补这份文件，还是改票面？
- **m0-03 / ADR-0011**：票面「无 key 也能起并跑通」与今天「无 key 启动失败」正面冲突。
  这是 ADR-0011 的意图变更，票面要加 superseded 指针；同时 README 与 ADR-0011
  对「真模型 / 假模型」的口径不一致，需要定一个。

### 4. 15 张「已过期」票各加一行指针（纯文档，量最大）

这些票的验收项今天已不成立，但状态还是 `resolved` 且勾满：

| 票 | 今天的落点 |
| --- | --- |
| m0-02 | Redis 已按 ADR-0005 删除 |
| m0-03 | 被 ADR-0011 推翻（mock 兜底取消） |
| m1-01 | `ScriptedChatModel` 已删，替身改 `FixedChatModel` |
| m1-07 | `-Preal-model` 隔离已删 |
| m2-real-model-01 | 真模型基线被推翻 |
| m3-04 | TTL 清理语义已改，见上 |
| m5-02 | 见上（待裁决） |
| A01 / A02 / A03 / A04 / A05 | run / step / resume / 预算 / 投递表，全部随 ADR-0027 改了口径 |
| A06 / A08 / P14 | 判分原料改 `audit_event`；计划前缀改会话目标；span 不再带账本行号 |
| P05 / P06 / P07 | 会话额度表已删；投递目标表已删；`audit_trail` 视图已删 |

## 二、9 张「故意不同」——这些是设计选择，不要修

留档的价值大于修改：它们每一条都写了理由，下一个人不必重新推一遍。

| 票 | 差异 | 理由出处 |
| --- | --- | --- |
| m2-02 | MCP 启动即 fail-fast，不后台退避重试 | 启动期硬失败更早暴露配置错 |
| m2-08 | argv 直执，不经 shell | ADR-0002 / 票 08 |
| closeout-04 | 进程内 TTL 去重，不做持久队列 | 单实例部署，队列是多余的一跳 |
| closeout-05 | 连不上即启动失败 | 与 ADR-0011 同一原则 |
| m3-03 | 召回范围收窄到当前会话 | 防跨段泄露（ADR-0026） |
| m5-02（配置） | profile 收成 local 单份 | 避免「配了不用」 |
| P10 | 知识库做向量 + bigram + RRF，OpenClaw 明确 no embeddings | ADR-0020 |
| P13 | Slack 走 HTTP，OpenClaw 默认 Socket Mode | 票面 42-46 行 |
| P15 | 只做跨进程子 Agent，不做进程内多 agent + bindings | 票面 42-44 行 |

## 三、抽样核验

五批共 72 条结论，我抽查了 4 条会引发改动的，全部成立：

1. `TaskLauncher.java:15` 的 `{@link RunResumer}` —— 存在，指向已删类；
2. `NotifyPolicy.java:15` 的 `GET /api/runs/{id}` —— 存在；
3. `deleteExpiredSessions` 生产无调用方 —— 成立（只有 `SessionMemoryTest` 调）；
4. `BudgetAcrossLayersTest` / `PermissionsAcrossLayersTest` —— 全仓无此类。

另外发现子代理低估了一处：`DomainBoundaryTest` 不是 1 处而是 7 处（还包括 `claw4j-core/pom.xml` 的一行注释）。

## 四、没纳入的

有一批把一条**已经明确否决过**的建议又提了一遍。已剔除，不在本报告里重复。

## 附：逐票明细

- `.scratch/review-2026-11-07/batch-a.md`（m0 + m1，12 张）
- `.scratch/review-2026-11-07/batch-b.md`（m2 三份，16 张）
- `.scratch/review-2026-11-07/batch-c.md`（m3 + m4 + m5，14 张）
- `.scratch/review-2026-11-07/batch-d.md`（m6 + m7，18 张）
- `.scratch/review-2026-11-07/batch-e.md`（m8 两份，12 张）

## 五、处理结果（2026-11-07 12:30）

### 已改

| 项 | 落点 |
| --- | --- |
| 失效指针 | `ScheduleTools`（这条会给模型看）、`NotifyPolicy`、`ScheduleScanner`、`HttpChannelAutoConfiguration`、`FeishuChannelAutoConfiguration`、`TaskLauncher`（`{@link RunResumer}`）、`HttpEndpointsTest`、`README` 的能力对照表四段 |
| 死指针 7 处 | `DomainBoundaryTest` → `CoreBoundariesTest`：4 处 `src/main` javadoc、1 处测试、ADR-0010、ADR-0015 |
| 死代码 | 那一条是接口义务、删不掉；另扫出 4 个真·零调用方法，已删——见下 |
| 缺失回归 | 补 1 条，另 1 条判定为"不复建"——见下 |

**关于"死代码"这个判断，我先前说错了。**
`FaultTolerantSessionService#deleteExpiredSessions` 不是死代码，它是外部接口
`org.springframework.ai.session.SessionService` 的**抽象方法**（`javap` 可见：
`public abstract int deleteExpiredSessions(java.time.Instant)`），本类不实现就编译不过。
它只是**没有调用方**。所以删不掉，只能把这件事写进 javadoc——
"没有任何调用方，真正判过期的是 `SessionMaintenance`，别把它当成'配了 TTL 就会删'的证据"。

**同一轮扫出的四个真·零调用方法已删**（全仓 `src/main` + `src/test` 无引用，
不是 `@Override`、不是 `@Bean`、不是配置绑定入口）：

| 位置 | 方法 | 为什么是死的 |
| --- | --- | --- |
| `NotifyPolicy:51` | `announcesChange()` | `state_changes` 这一档没有生产者，没人问"要不要推中间态" |
| `GoalStatus:86` | `mutableWhenComplete()` | "完成之后不能直接 edit"由 `JdbcSessionGoalStore#EDIT` 的 SQL 挡，这个方法没人调 |
| `AuditAction:41` | `isStart()` | "开始只能配 started"由 V19 的 check 约束挡 |
| `McpConnection:99` | `requiredPermission()` | 唯一在用的是 `permissionFor(toolName)` |

**剩下 10 个零调用方法是 Spring 的绑定入口，不能删**：`ModelProperties#setPrimary`、
`ApprovalProperties#setTtl` 等，它们是 `claw4j.model.primary` / `claw4j.approval.ttl`
这类配置键唯一的落点——删了不是"清理"，是配置项静默失效。


**缺失的两条回归**：

- `PermissionsAcrossLayersTest` → 补成 `ChatPipelineTest#aToolWithoutItsRegistryEntryIsRefused`：
  某一层的工具没在权限表里，当场按"没有权限"拒掉、一次都没执行。
  反例已验：把它登记进去，该用例立刻红。
- `BudgetAcrossLayersTest` → **不复建**。它测的是会话级 40/150 的工具调用预算，
  那套随 ADR-0027 删除了；今天对应的是"目标 token 预算 + 工具熔断"，
  后者由 `ChatPipelineTest#totalBudgetStopsAcrossDifferentTools` 覆盖（三个不同工具共用
  一个总数上限）。按旧名字补，只会补出一个测已不存在语义的空壳。

两条都因为硬约束没有新增文件：每模块 10 个测试文件、单文件 20 个用例（`scripts/verify.sh`），
而所有模块正好 10 个文件。

### 两处裁决

- **m5-02**：不新建 `application-mcp-third-party.yml`，也不加随包 profile。
  真实落点是 `claw4j.capability.mcp.servers` 下的两个条目（`README.md` 与
  `docs/capability-tests.md` 里就是它们）。理由：那两个 server 一个要本机 `npx`、
  一个要出网，随包发默认 profile 会让"能不能启动"取决于开发机装没装 Node；
  OpenClaw 同样是在配置里声明 `mcpServers`，没有"随包示例服务器清单"。
- **m0-03**：票面加 superseded 指针，指向 ADR-0011（无 key 即启动失败）。
  原则取自 OpenClaw："Permanent account and credential errors … fail fast instead of
  retrying forever"（`docs/channels/slack/transports.md:71`，原文讲的是通道凭据）。**但"启动即失败"是我们
  自己的选择**——OpenClaw 是多供应商网关，缺 key 照常启动、调用时才报错；我们只服务一个
  部署，起得来但答不了比起不来更难查。
