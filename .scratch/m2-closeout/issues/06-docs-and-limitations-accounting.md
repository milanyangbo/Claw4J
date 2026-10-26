# 06: 文档与限制记账对齐

**What to build:** 把这一轮发现的三处事实性漂移修掉，并把 05 留下的测试缺口记进账本：
读 ADR 的人不该读到"装配线还没接"，也不该被一个已经不存在的类名指向空处。

**Blocked by:** 05（guardrails 那一行要等 SDK 绑定层落地后才能写准）

**Status:** resolved

- [x] ADR-0010 末尾"`claw4j-capability` 尚未被 `claw4j-agent` 依赖（装配线还没接）"删掉——早已接上
- [x] ADR-0010 里引用的架构测试类名与现存类一致（`ChannelArchitectureTest` / `ToolsArchitectureTest`
      这类旧名按现状改正）
- [x] ADR-0009 补一条实现记录：3 秒 ACK、按 `eventId` 去重、主动回推各落在哪个类，附实测证据
- [x] `guardrails.md` 的偏差表加一行：飞书 SDK 绑定层没有单测，覆盖靠 `feishu-live`，
      并写明补偿是什么、记在哪张票（照"两类沙箱都没有网络隔离"那几行的写法）
- [x] 若 README 里有通道清单，同步加一行；**不新增解释性段落**（README 是给外部用户的）

**落点:** `docs/adr/0010`、`docs/adr/0009`、`docs/agents/guardrails.md`。

**注意:** 这一票只改"说法与现状不符"的地方，不顺手重写段落，也不把设计论证搬来搬去。

**不要做:** 给 ADR 追加新的决策；把 M3 的规划写进 ADR。

## 进展（2026-10-26）

**ADR-0010**（三处）：

1. 末条"`claw4j-capability` 尚未被 `claw4j-agent` 依赖（装配线还没接）"整条删掉。
2. 架构测试那条按现状重写：四个类名 → `DomainBoundaryTest` / `AgentBoundaryTest` /
   `ChannelBoundaryTest` / `ToolsBoundaryTest`，并补上 `claw4j-app` 的 `ProcessBoundaryTest`
   （ADR-0002 那条红线要扫全仓，只能落在唯一看见所有模块的地方）。
   同一句里"提交前 hook 也跑"是假的——仓库没有 CI 也没有 pre-commit hook，入口只有
   `./scripts/verify.sh`（guardrails 第 8 行原话），改为指向那份。
3. 依赖图里 `└── tools ─┘` 的模块名早改成 `claw4j-capability`，图上还写着 `tools`，一并改回。

**ADR-0009**：末尾加"实现记录（2026-10-26）"，一张表把六条约束（ACK / 异步编排 / `eventId` 去重 /
主动回推 / 超长分段 / 认人）对到类与方法，每条挂 `InboundDispatchTest` / `FeishuChannelWiringTest`
里的具体用例名；表后写三条实现取舍（去重留在进程内不引 Redis、两条"没有任务可跑"的分支必须当场回一句、
回调里绝不抛异常）。

**guardrails**：偏差表新增一行——`channel.feishu.sdk` 六个类不写单测，channel 行覆盖率
82.9% → 61.0%；补偿只有装配层 6 条，**真链路用例还没有**，补法打 `feishu-live` tag。

**README**：通道清单那一行原本就有（模块表里 `claw4j-channel` 写着"HTTP+SSE、飞书长连接"），
真正缺的是配置表——新增 `FEISHU_APP_ID` / `FEISHU_APP_SECRET` 一行，写清"两个都配才装配、缺一个退让"
与"打 fat jar 要带 `-Pchannel-feishu`"。没有新增解释性段落。

### 顺带修掉的同类漂移（一句话能说清、不涉及判断的那种）

- 五处 `tools 模块` 旧名注释：`claw4j-agent/pom.xml` ×2、`claw4j-capability/pom.xml`、
  `capability/builtin/sales/ReadOnlySqlExecutor.java`、`claw4j-app/.../application.yml`。
  模块在 ADR-0010 就改名了，注释没跟着改——与上面第 3 条是同一类错，只是散在代码里。

### 发现但**不在本票落点**（已开 07）

- `docs/` 下 25 个测试类名已经不存在（现存测试类 22 个）：`TextToSqlLoopTest`、`AuthorizationOverHttpTest`、
  `SubagentRunnerTest`、`SandboxPolicyTest`……见 `07-docs-stale-test-class-names.md`。
  这一条要按"那段行为现在由谁承载"逐个定位，属于调查而不是改字，塞进本票会把"只改说法"变成"重写文档"。
- ADR-0010「什么时候该拆模块」里"全仓库 Java 不到 400 行"实测已是 **10,140 行**（main）。
  数字一改，它支撑的那句"当前不满足任何一条"就要重新判断要不要拆 `claw4j-capability`——
  那是决策，本票明文禁止，放进 07。

### 验证

`./scripts/verify.sh` 全绿：**183 条用例、失败 0、错误 0、跳过 0**；
覆盖率 domain 100.0% / channel 61.0% / capability 84.7% / agent 87.3%。
