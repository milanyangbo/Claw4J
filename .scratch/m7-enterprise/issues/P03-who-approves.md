# P03: 谁来批——审批人名单与飞书卡片

**What to build:** 审批有了明确的"谁批"。审批人是独立名单，与业务角色分开配置；
提问人不能批准自己的请求——自行批准等于没有审批。审批入口是 HTTP 接口 +
飞书交互卡片（走现有长连接，不需要公网回调地址）：卡片上能看到工具名、参数摘要、谁提的，
就地一键批准或拒绝。

**Blocked by:** P02

**Status:** resolved

- [x] 飞书卡片一键批/拒，卡片带工具名、参数摘要、发起人
- [x] 提问人批准自己的请求被拒（越权用例验证）
- [x] 审批人名单独立配置，与业务角色互不影响
- [x] HTTP 审批接口可独立于飞书使用（同一条服务端逻辑）
- [x] 无人值守场景用例：审批超时自动过期，卡片显示"已过期"

## Comments

- 2026-11-02 收票。**资格判定只在服务端一处**（`ApprovalService.decide`）：两个入口都只做
  "认出谁在点"——HTTP 是请求头，飞书是卡片事件的 operator——然后调同一个 `decide`。
  写在入口上的话，第二个入口必然漏，而漏掉的表现是"同一个按钮换个通道就能自己批自己"。
  请求体里因此**没有"谁"**：让调用方自报身份，自批那一条就只是一句口头约定。
- **自批被拒不等于那一行作废**：结论不落库，`approval_request` 还是 `pending`，名单里换一个人
  照样能批。用例两头都钉（不落结论 + 换个人能过）——只钉一头的话，把名单配空也能绿。
  档位也分开了：`SELF_APPROVAL` 与 `NOT_APPROVER` 是两个答案，合成一个的话，名单里的人真遇上
  自己那条时第一反应是回去翻"我的名字是不是拼错了"。
- **名单是独立的人名单**（`ApproverRoster`），与 `claw4j.channel.http.roles` 是两张表：后者回答
  "能读哪些表"，前者回答"愿意为别人的操作负责"。挂到角色上的话，任何一次提权（给某人加 `admin`）
  都会静默扩大审批面。默认空 = **没人能批**，方向与 `ApprovalGate#denyAll()` 一致；代价是忘配置时
  所有请求都会过期，所以装配时日志里有一条 WARN 说明这件事。
- **卡片推给"这件事登记过的会话"，不是名单上每个人**：那需要一张"人 → 会话"地址表，仓库里没有，
  而 `agent_run_delivery` 已经记着"这件事在哪些会话被问过"。所以按 `sender.channel()` 匹配着推，
  多个通道都问过就都推；不在名单里的人点了会被服务端拒——多看到一张卡片不会造成放行。
- **端口只有 `announce`，没有 `update`**：就地改卡（`CallBackCard(type=raw)`）是飞书自己的机制，
  企微、HTTP 各有各的做法。把它写进端口，等于让"能不能改一张已发出的卡片"变成核心契约的一部分。
  所以 `ApprovalCards` 只管"把待批请求说出去"，点完之后长什么样由通道自己决定。
- **推失败不升级成错误**：登记与挂起已成、编号已经在对话里交回去了，缺的只是按钮，
  所以 `RunApprovalGate` 对 `announce` 的任何异常只 `log.warn`。反过来让推失败 500，
  用户会以为"这件事没进去"，其实库里那一行已经在那儿了。
- **HTTP 状态码就是结论**：`200` 已落定 / `403` 没资格 / `404` 没有这个编号 / `409` 这个按钮
  已不作数。全回 `200` 会逼调用方去解析中文文案。没装配审批时**一律 404**——那是能力不在场，
  不是这个通道坏了，与 `GET /api/runs/{id}` 同一个口径。
- **`@ConditionalOnMissingBean(name = "feishuApprovalCards")` 而不是按类型**：按类型的话，
  第一个 `ApprovalCards` bean 之后所有通道的都注册不上——多通道并存时"后加的那个静默不生效"。
  同理 `ApprovalDecider` 走 `ObjectProvider`：没有它时控制台照常起，只是点不动。
- **`settles(verdict)` 是必须的**：`NOT_FOUND` / `NOT_APPROVER` / `SELF_APPROVAL` 都不落定，
  这时读回现状再渲染一遍，会让"你没资格"变成一张看起来刷新生效了的卡片。
- **三个红都出在我自己的用例上**：① 断言卡片 JSON 里含字面 `"pending"`——卡片里根本没有这个词
  （只有标题"需要有人批准"），改成断言标题；② 替身 `find()` 恒返回 pending，于是"批过了的卡片
  还带按钮"，替身改成按 `verdict` 返回；③ 自批那条断言"卡片不变"而实现总是读回现状，
  实现上补 `settles` 才对。**另有两条低级的**：core 不依赖 AssertJ，`ApproverRosterTest` 一开始
  写 `assertThat` 编不过；`./mvnw -pl X` 不带 `-am` 在 offline 下解析不到兄弟模块的 SNAPSHOT。
- 用例：core 新增 `ApproverRosterTest`（4 条：不在名单 / 空名单 / 自批与名单外分开 / 空白项与 trim）；
  channel 新增 `ApprovalControllerTest`（7 条）与 `FeishuApprovalTest`（9 条）；
  app `ApprovalFlowTest` +2 → 11 条，`ApplicationAssemblyTest` 加 `approvers` 动态属性后 6 条仍绿
  （真模型闭环要真点一次按钮，名单本来就是部署的事）。全量 **385 条全过、跳过 0**；
  行覆盖率 core 84.7 / channel 67.6 / capability 84.8 / agent 60.5 / sales 93.8 / mcp 75.0；
  文件数 core 6 / channel 9 / capability 10 / agent 10，单文件最大 17 / 15 / 18 / 20，都在上限内。
- 同步：`application.yml` 的 `claw4j.approval.approvers`（连同"两张表"的四行注释）、
  `application-local.yml` 里填了两个人、`docs/capability-tests.md` 的审批一节补了
  `GET/POST /api/approvals/{id}` 与状态码口径、`README` 的"已达 / 未达"两格（P03 移入已达）。
  **没有新迁移**：卡片要的三样（工具名、参数摘要、发起人）V10 里都有，
  `expired` 一直由 SQL 按库时钟算，`MigrationCompositionTest` 仍是 1..11。
- **没做的（各有归属，不是漏项）**：
  - **仍没有随包工具登记成"要审批"**：MCP 的 `permission` 只有 `action` / `tables`，
    `Approval` 恒为 `NONE`。"哪个外部动作必须有批"是部署的策略名单，归 P09。
    所以 `capability-tests.md` 那一段仍写明"用 curl 造不出待批请求"，
    入库之后的两条（读、批）才能自己跑。
  - **不能撤回自己已发的请求**：过期是唯一的收场。撤回要新增一个状态与一条边，
    而现在连"谁发起"都已经在行上，缺的只是一个动词——没有票就不顺手加。
  - **卡片不带"还剩多久"的倒计时**：`expires_at` 已经渲染成绝对时间，飞书卡片 1.0 没有本地时钟，
    加倒计时只能靠服务端定时改卡，那是一条定时任务（P04）。
  - **子 Agent 那一层仍然只登记不挂起**（P02 留下的那条）：`RunApprovalGate` 里那条判断还在，
    但没有真实路径能触发。
