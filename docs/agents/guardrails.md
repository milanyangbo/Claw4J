# Guardrails

**这台仓库哪些事是强制的、哪些是有意不强制的。** 评审时判断"这是缺陷还是已知状态"，
看这一份就够了。决策与判据在 `docs/adr/`。

闸的清单不列在这里：本地那几条在 `CONTRIBUTING.md`「质量门禁」，在这里抄一份只会过期。

**没有 CI，也没有 pre-commit hook**：门禁全在本地，入口只有一个——
`./scripts/verify.sh`（载入 `.env` → `clean verify` 含覆盖率红线 → 跳过数必须为 0 → 文件/方法数上限）。
接 CI 时要补两样：runner 上没有 Docker 守护进程（数据库那一类直接判红），以及 `secrets.DEEPSEEK_API_KEY`。

## 改一条红线，就新开一张票

禁令类 ADR（0002 命令行、0007 鉴权、0010 分层、0011 真模型）各有一条机器检查盯着。
改代码去迁就一条红线、和改一条红线放代码过，在 diff 里长得一样，代价完全不同。
后者单独开票，票里写清"这条红线现在拦住的是对的东西，为什么"。

已发生的实例（2026-10-24，票 08）：脚本层要把命令行改成结构化 argv，就得自己起进程，
也就必须收窄 `ProcessBuilder` 那条禁令。票里写清了"这条红线原先拦住的不是错的东西"——
它拦住了"自己起进程"，却把"参数被命令解释器当成语法"这扇门留着，
唯一挡它的是一处手写引号。收窄的方向是"只有 `LocalProcessRunner` 这一个被点名的类
可以碰"，不是把规则删掉：删了之后"Java 侧不起进程"就没有任何机器证据。

## 已知偏差

判据：**有人能在不看代码的情况下知道这个偏差是有意的**。
记账过的偏差，评审时该查的是"补偿手段够不够"，不是"这是不是 bug"。
写法照 `IsolationSpec.Network.NOT_ISOLATED`：枚举值自己带着"没有隔离"的字样，
还有一条断言盯着它不许撒谎——防的是把常量改名成 `networkIsolated` 让规格看起来漂亮。

| 偏差 | 实际给的 | 补偿 | 记在哪 |
| --- | --- | --- | --- |
| 两类沙箱都没有网络隔离与资源上限 | 第 ① 类：应用容器内的受限子进程，锁得住入口、参数、目录、环境、时长；第 ② 类：临时目录副本 + 只有 PATH + 5 秒，如实写 `NOT_ISOLATED` | 真正的网络隔离由部署层的容器网络策略给；联网脚本在静态检查那关被挡（`NETWORK_CLIENT` / `PY_NETWORK`） | 票 06、ADR-0002、ADR-0013 |
| "非 root"取 `user.name` 而非 uid | uid 0 而 `user.name` 非 root 时会放行；macOS 与常见 runner 上恒非 root，等于从不触发 | 真正的保证是容器镜像的 `USER`；这一条只挡应用被跑成 root | 票 03 |
| 入口脚本必须有可执行位与 shebang | 不再经命令解释器执行（票 08），这两件事从"能跑就行"变成硬要求 | 静态检查在落盘之前挡（`NO_SHEBANG`）；漏过去的执行侧给一句指名"第一行"的话 | 票 08、ADR-0002 修订 |
| `exec` 的 `ask` 比它严：白名单命中也问人 | 它的 `ask` 是"allowlist 命中直接跑、未命中才问人"；我们的审批静态标在 `ToolPermission` 上，没法按参数内容动态决定 | 白名单先兜一层（未命中直接拒、不进卡片），人只决定"这一条现在要不要跑"——让审批人去读一条任意 shell 等于把人当 WAF。重开条件：`Approval` 支持按参数判定 | 票 01 偏离 1 |
| `exec` 默认不批（`mode=full`） | 照它的 baseline（未配置时 `full` / `off`）；它的推荐值 `auto` 需要原生复核器，我们没有 | 闸在授权表：`exec_command` 只给 `admin`，`read-only` / `guest` 调不到 `Bash` / `BashOutput` / `KillShell`；`application.yml` 注释写明怎么收紧 | 票 01 |
| MCP 的"跨进程"在默认构建里是进程内 stdio | 进程内管道版（surefire 下拿子进程 classpath 太脆） | 真子进程的 server 打 `real-cli` tag，`-Preal-cli` 才跑 | 票 02、ADR-0014 |
| 飞书 SDK 的**翻译与发送**没有单测（建连那层现在有了） | `channel.feishu.sdk` 里"SDK 类型 ↔ 本项目类型"的翻译、发送、条件装配仍不写单测——那要真凭据。**建连这一层进得来**（票 02 抽了 `FeishuConnection` / `FeishuConnectionFactory` 端口）：失败分类、退避序列、状态机都在默认构建里测得到，不联网 | 装配那几条钉着（凭据缺失/半配/SDK 不在 classpath/`enabled=false`/认人只看登记表/重复登记启动失败/凭据齐了但一个人没登记要在启动日志里说一声）；重连五条钉着（可恢复不失败、永久仍失败、30-60-120 退避、成功即停、`stop` 取消并关连接）。**真链路仍靠人工验一次**：2026-10-27 带真凭据起应用，日志出现"长连接已建立"，群里 @ 机器人收到真实回复（那条回复本身就是 `im().message().create` 发出去的），入站事件与回推都通了；同日再 @ 一次问"上周华东区成交额多少"，四段日志齐全，回答里数字（99,999.00，已对库核过）与三条口径都在，`claw4j.tool.calls{tool=querySales,outcome=ok}=1`。之后没有任何自动化守着它——SDK 升级或飞书改协议时会静默失效，只能靠再手验一次 | 票 05、票 06、票 02 |
| 测试里有两个假 `ChatModel` | `FixedChatModel`、`SubagentModelStub`（ADR-0011 说不对 `ChatModel` 打桩） | 只在要验的东西不在模型身上时用 | M2-real 票 01 |
| 会话记忆会把旧答案带进新问题，模型可能不查库 | 记忆 TTL 30 天。同一份数据、7 次提问的实测：只有 1 次真查了库，其余复述了四天前的回答（128,400 而非 99,999） | 缓存 TTL 1 小时 + 上下文键含时间词/数字，把错误限制在"同权限、同一小时、同一时间窗"；`claw4j.tool.calls{tool=querySales}` 能看出这一轮到底查没查库，排查先看指标不看回答 | `docs/design/session-memory.md`、`semantic-cache.md` |
| 入站拦截挡不住改写 | 关键词子串匹配，同义词、全角字符、词间插空格都能绕过（框架文档自己写明不处理 homoglyph / 全角 / 零宽） | 它只挡**随口一问**，不是安全边界：越权的真实边界是鉴权（表级 × 动作级），外泄的边界是出站脱敏。词表空着时整个 Advisor 不装配，`claw4j.guard.sensitive-words` 是唯一开关 | `docs/design/inbound-guard.md` |
| 通知策略三档里只有两档可达：`state_changes` 的中间态没有生产者 | 需要审批时推出去的是**审批卡片**（`ApprovalCards`），不是 `ConversationDelivery` 的通知；`done_only` 与 `silent` 正常。选 `state_changes` 的人今天拿不到额外那一句 | `claw4j.run.delivery{skipped,policy=…}` 能看出这一轮被策略跳过；审批人那边看卡片到没到。两者是两条路，别把"卡片到了"当成"通知策略生效了" | spec 的 Comments、`NotifyPolicy#STATE_CHANGES`（枚举值在，但没有一处产出中间态） |
| 额度只在非流式那一轮记全 | 流式回答拿不到汇总 usage，那一轮按 0 记——目标上的 `tokens_used` 因此会少算流式那部分（与非流式同一句"不拿估算值冒充"的口径） | 额度这件事本来就是护栏不是账单（OpenClaw 也这么说）；要查"这次到底花了多少"看模型层的 `claw4j.model.tokens` 指标，它两条路都有 | 票 04、ADR-0027 决定五 |
| 没有"按 id 取回一次结果"的接口 | 只有 `POST /api/chat` 与流式两个入口（ADR-0027）。要回答"那一轮说了什么"，读通道里的回答与 `audit_event` 的两条事件（`run_id` 能对上） | 同步请求本来就当场返回；定时任务与审批之后的新一轮，结果推到它自己那段对话；`runId` 与 `claw4j.run.id` 同值，串日志与翻审计都够。**真要按 id 取历史，那是一个新能力（会话历史端点），不是把 run 表加回来** | 票 05、ADR-0027 |
| "一条批准只对应一次执行"不再有唯一索引 | 这条保证由审批表的状态迁移给：`pending → allowed/denied/expired` 只可能发生一次，并发下第二个请求拿不到 `pending` 那一行 | 审批行的 `status` 是真相；审计里 `approval_id` 只出现在一条 `tool.action.finished` 上（同一次执行的两条事件共享它） | 票 05、V24 |
| 审批过期之后不重画卡片 | 过期在判定时生效：`decide` 拒绝过期请求，卡片渲染时也不带按钮——但**没有任何任务回头去改那张已经发出去的卡** | 点一下就拿到"这条审批已经过期了，让提请求的人重新发起"，不会变成"点了没反应"；`approval_request` 上的 `status`（清扫会把它落成 `expired`）与 `expires_at` 是真相，卡片只是它的一个快照 | spec 的 Comments、`ApprovalService#decide` |
| `recall_session` 默认能跨用户召回（`visibility=all`） | 照它的默认值（`tools.sessions.visibility` 原文 Default: `all`）。四档都在，但 `tree` 暂等同 `self`（子 Agent 会话不落库）、`agent` 暂等同 `all`（只有一个 agent 命名空间），于是今天真正新增的只有"跨用户"这一档 | 启动日志里打出生效范围；`application.yml` 与 README 写明**多用户、角色不同的部署必须配 `self`**。它自带的检查（多 agent 且解析成 `all` 时报 finding）在这里永远不触发，所以改用这两处兜底。`read-only` 是"能问数据"的身份，不是"能看别人对话"的身份——要撤销就配 `self`，或把 `recall_session` 从角色表里去掉 | 票 05 |
| `visibility` 写错时启动失败（它静默回落成 `all`） | 它的 `resolveSessionToolsVisibility`（`src/plugin-sdk/session-visibility.ts:132-138`）对非法值 `return "all"`；我们抛 `IllegalArgumentException` 并点名键名与四个合法值 | 方向是更严不是更松：写错变成最宽授权是 fail-open，而"写错了"与"写的是 all"在日志上一模一样。代价要认：手滑一个拼写会让服务起不来，而不是悄悄开到最宽——`session.maintenance.mode` 当初拼错回落 `warn`（2026-11-10 也改成了启动失败：落回 `warn` 是一条都不删、落回 `enforce` 是拼错反而删数据，两边都不比启动失败更清楚） | 票 05、`SessionVisibility#of` |

"会话记忆会把旧答案带进新问题"那行本身就是例子：先例记在第一次决定的那张票里，
第二处照着做却没补记，评审时就显得像违规。记账要跟着**用的地方**走。
