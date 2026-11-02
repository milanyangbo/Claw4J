# P02: 挂起 run 等审批（不挂线程）+ 参数指纹

**What to build:** 鉴权通过后，检查是否存在**同参数**的有效批准；没有就把 step 标 awaiting、
run 标 suspended，请求直接返回——"停在这一步"是库里的一个状态，不是挂着的线程。
批准后由**服务端** resume。核心是参数指纹：工具名 + 按 key 递归排序后的规范化参数做哈希。
它与幂等键**形状相似、口径相反**：幂等键含 runId 与序号（区分每一次执行），指纹不含
（跨执行复用一次批准）。混用的事故是双向的。

**Blocked by:** A01（挂起是库里的状态）、A02（服务端 resume）、P01

**Status:** resolved

- [x] REQUIRED 动作无批准 → run 挂起、请求返回审批编号，不占线程等待
- [x] 批准后服务端续跑同一 runId，回答是"下一步"而不是让用户重新解释背景
- [x] grant 一次用完即废 + 默认十分钟过期；过期后卡片就地显示"已过期"
- [x] 拒绝也落一行，理由回灌模型，模型不在原地反复请求同一件事
- [x] **变异验证**：指纹退化成只算工具名，"批准后改参数仍被拦"的用例必须变红；
      幂等键与指纹混用，两个方向的用例都必须变红
- [x] 真模型闭环用例："被卡住 → 批准 → 放行"

## Comments

- 2026-11-01 收票。**"停下来"落在两个地方，缺一条就有一件事说不清**：
  `agent_run.status = suspended` 说这件事停了，`agent_step` 上一行 `approval / awaiting`
  说停在哪个工具上。只落前者，"卡过几次、每次卡在什么工具上"只能靠读摘要文本猜；
  只落后者，没有人知道它不该被一句"继续"唤醒。顺序是**先登记再挂起**：反过来会出现
  "run 已 suspended、审批表里还没有那一行"的窗口，那时点批准点的是一个不存在的编号。
- **一个真 bug，值得记**：第一版 9 条用例里 6 条红，根因是 `closeTurn` 把刚标成 suspended 的
  run 又收尾成 `done`。表现不是报错，而是"批准落下来了、`reopen` 却直接失败且不吭声"——
  因为那条 SQL 只从 `suspended` 转。修法是 `ChatClientAgentPort` 在被门拦下的那一轮**早退**：
  不收尾、不记 `model_turn`。这与"状态机只允许一条边"是同一件事，早退比事后补一条边便宜。
- **一次点头一次执行**：`redeem` 是**一条语句**
  （`update ... where consumed_at is null and expires_at > now() order by expires_at desc limit 1
  for update skip locked returning`）。查与改分成两步的话，两个并发的同参数调用会同时查到同一条，
  于是一次点头放行两次副作用——发两封邮件那种事故，日志里两边都"有批准"。
- **指纹两头都得钉**，这是我做变异验证的两个方向：
  - 退化成 `sha256(工具名)`：`aChangedArgumentDoesNotRideOnAnEarlierGrant`、
    `theApprovalFingerprintIsNotAnIdempotencyKey`、`theFingerprintIgnoresOrderButNotValues` 三条红。
  - 把幂等键（`runId|stepSeq|工具名|规范化参数`）当指纹用：
    `theApprovalFingerprintIsNotAnIdempotencyKey` 红。两次都跑过、都回滚了。
- **规范化手写，不再过 Jackson**：`canonicalArgs` 递归排 key、数字走 `stripTrailingZeros`、
  字符串自己转义。用 Jackson 再序列化一次看着更省事，但它会把 key 顺序又交回去——那正是
  这个类存在的理由。反过来"字段顺序变了就要重批"，人会开始闭眼点，而闭眼点是这道门失效的方式。
- **流式下参数不全就不判**：`Flux.defer` 让每次订阅各带一套 `partialArgs` / `settled`，
  否则第二次订阅继承第一次的结论，全放行。参数解析不出完整 JSON 时 `of` 返回空，
  调用方**等下一块**，不拿半截参数去问账本。
- **续跑用发起人身份快照，不用审批人身份**：后者等于提权（审批人通常权限更大）。
  快照存 `agent_run.principal_snapshot`（`userId / role / tables / actions`），
  缺字段就当成读不出来、**不补默认值**——补出来的 `role=guest` 会让续跑以一个没人授予过的
  身份发生，而它看起来完全正常。
- **拒绝也续跑**：不续的话，用户看到"已拒绝"而模型永远不知道，下一轮它会把同一个请求原样再来。
  理由原样回灌，并明确写"不要再请求同一个操作"。
- **`decide` 的三步顺序不是随意的**：先落结论（续跑一起来，工具那层就会问"有没有批准"，
  它读的正是这次 update）→ 再改 step（recap 是续跑时模型唯一看得到的进度）→ 最后续跑
  （前两步只改库，这一步唯一不可逆）。落不下来时 `explain` 分三态回答：
  没这条 / 已经有结论 / 过期了——合成一句"操作失败"，点按钮的人只能再点一次。
- **话术里删掉了"十分钟内有效"**：`AuthorizationAdvisor` 读不到 `claw4j.approval.ttl`，
  写死的数字一改配置就对不上卡片上剩下的时间，而这种偏差只在"我以为还有五分钟"时才发现。
  有效期由卡片按库里的 `expires_at` 显示，那里是唯一来源。
- **门禁差点红在覆盖率上**：agent 从 64.9 掉到 58.2（`ApprovalService` / `JdbcApprovalLedger` /
  `RunApprovalGate` / `PrincipalSnapshot` 都是新代码，而它们只在 app 模块的真库用例里被跑到）。
  补法受两条上限约束（模块 ≤10 个测试文件、单文件 ≤20 个方法，agent 刚好 10 / 20 满）：
  `InboundSecurityTest` 用了最后一个方法位放**指纹口径**（顺序、`1` 与 `1.00`、嵌套、转义、
  半截参数）；`ChatPipelineTest` 把两条"没有 run 存储"的用例并成一条腾出位置，放**审批门**
  （suspended + awaiting 两处、没有 runId 时只登记不挂）。60.2% 过线——
  `PrincipalSnapshot` 是 `run` 包里的包级类，不给它单独开一个测试文件就只能留在未覆盖里。
- 用例：agent `InboundSecurityTest` +1、`ChatPipelineTest` +1（净 0：并了两条）；
  app `ApprovalFlowTest` 新增 9 条（真库 + 假模型，含变异验证要的两条）、
  `RunSchemaSqlTest` 改 6 条 + 新增 1 条（`redeem` 四个口径、摘要与可配过期）、
  `ApplicationAssemblyTest` +1（**真模型闭环**："被卡住 → 批准 → 放行"）。
  全量 363 条全过、跳过 0；行覆盖率 core 82.0 / channel 67.9 / agent 60.2 / capability 84.8。
- 同步：`MigrationCompositionTest` 1..11、`V11__approval_fingerprint.sql`、`application.yml`
  的 `claw4j.approval.ttl`、`README` 的"已达 / 未达"两格、`docs/capability-tests.md`
  新增"要人批的动作"一节（并如实写明现在用 curl 走不完，理由见下）。
- **没做的（各有归属，不是漏项）**：
  - **入口在 P03**：HTTP 接口与飞书卡片（谁批、批哪一个）。所以 `capability-tests.md` 那一节
    只写"停下来的样子"能自己确认，点按钮之后的行为靠测试钉。
  - **配置面也没有**：MCP 的 `permission` 仍然只给 `action` / `tables`，`Approval` 恒为 `NONE`，
    所以随包发出的工具里**没有一个**要审批。加 `approval: required` 这个键很便宜，
    但"哪个外部动作必须有批"是部署决定，归 P03/P09 的策略名单。
  - **子 Agent 那一层只登记不挂起**：没有 runId 就只写审批行、不 `suspend`（`RunApprovalGate`
    里那条判断已在，但没有真实路径能触发，用例走的是构造出来的 null runId）。
  - **审批卡片本身**：`ApprovalDecider.find` 已经把 `expired`（SQL 里按库时钟算）与
    `expiresAt` 交出来了，渲染是 P03 的事。
