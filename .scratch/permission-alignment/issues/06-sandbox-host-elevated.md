# 06: 容器化之后——沙箱工具层、`exec.host`、`elevated` 四档

**What to build:** 三件只有在"命令真的跑在容器里"之后才有意义的东西：

- **`tools.sandbox.tools`**（白/黑名单）作为第 9 层真正生效，并且是 **MCP/插件工具的第二道闸门**：
  沙箱会话里没把 `bundle-mcp`（或 `group:plugins`、或具体 server 的 glob）放进沙箱白名单，
  MCP server 会加载成功、工具却看不见。
- **`tools.exec.host`**（`auto` / `sandbox` / `gateway` / `node`）决定 exec 在哪跑：
  `auto` = 有沙箱就进沙箱，否则宿主。
- **`elevated` 四档**：`on` / `ask`（别名）/ `full` / `off`，加 `enabled` 与按通道分组的 `allowFrom`；
  `full` 跳过审批。

**Blocked by:** 03（第 9 层要挂在管线上）+ **M9 的 P17a / P17b / P17c**（已拆票，见
`.scratch/m9-scale/issues/`）——本票只写"容器化之后"的那三件，不重复 P17 的范围。
`elevated` 那一段另有一条硬顺序：必须等 P17 全部落地之后才动（ADR-0030 裁决六）。

**Type:** task

**Status:** resolved（2026-11-18）：沙箱层 / `exec.host` / `elevated` 三件已落地；`exec_allowlist` 的 agent 维度没有对象，见文末「没做的那一行」。
**二审（2026-11-18，code-review 之后）**：补了 `gateway` 拒与「非沙箱下 elevated 无行为」两条用例、
补了 `SandboxedExecBackend#start` 的句柄记账（沙箱部署里提权起的那条也是宿主进程）、
把 `bundle-mcp` 的 MCP 工具名改成每轮现算（原来是装配期快照，MCP 重连后看不见）；四条无对象的用例搬进缺口。

**裁决来源：** ADR-0030 §裁决六（沙箱三键、MCP 闸门、elevated 四档与启用前提）与
§实现过程·阶段 6。

## 要动的地方（预计）

| 位置 | 改什么 |
| --- | --- |
| 新 `SandboxProperties` | `mode`（off / non-main / all）、`backend`（docker / ssh）、`scope`（session / agent / shared）、`workspaceAccess`（ro / rw / none）、`tools.allow/deny/alsoAllow` |
| `claw4j-capability/**/exec/SandboxedExecBackend.java` | 按 `host` 路由：沙箱内 / 宿主 / node；`auto` 的回退顺序 |
| `claw4j-capability/**/mcp/**` | 沙箱会话下按沙箱白名单过滤 MCP 工具（`bundle-mcp` 闸门） |
| `claw4j-agent/**/session/**` | elevated 是**会话级状态**，不是永久配置；落在哪张表由实现定 |
| `claw4j-channel/**` | `/elevated` 指令解析（`on|off|ask|full`，无参数显示当前档） |
| `claw4j-agent/src/main/resources/db/migration/V12__exec_allowlist_agent.sql`（新） | `exec_allowlist` 加 `agent_id text`；唯一索引从 `(pattern, coalesce(arg_pattern,''))` 换成 `(coalesce(agent_id,''), pattern, coalesce(arg_pattern,''))` |
| `claw4j-capability/**/exec/ExecApprovalPort.java` | `Request` 加 `agentId`（`null` = wildcard 桶）；`alreadyApproved` / `remember` 跟着带 |

**`exec_allowlist` 的 agent 维度归本票**（票 01 刻意没建这一列，理由见 ADR 裁决四"为什么这一阶段没有 agent 维度"）：
它的条目挂在 `agents.<id>.allowlist` 下，并与 `agents["*"].allowlist` 合并（`[...wildcard, ...agent]`），
所以查询要同时看 wildcard 桶与当前 agent 桶。判据：`coding` agent 批过的 `rg`，`messaging` agent 那边
**仍然问一次**；写在 wildcard 桶（`agent_id` 为 `null`）的条目对所有 agent 生效。

**落地后回填（2026-11-18）**：上表三行没做，理由是**没有对象**——`scope` 键与 per-agent `docker`
覆盖随多 agent 一起才成立；`agent_id` 同上（见文末「没做的那一行」）；`/elevated` 的解析落在
`claw4j-agent/**/elevated/`（与 `/new`、`/goal` 同层），不在 `claw4j-channel`。
其余各行按表落地，实际路径见「Answer」。

四条照抄的语义：

- **`elevated` 只在沙箱化时改变行为**：非沙箱部署里 exec 本来就在宿主上跑，这个指令等于无操作。
- **`allowFrom` 是交集不是覆盖**：`agents[].tools.elevated.allowFrom` 要求 sender **同时命中**
  全局与 per-agent 两份名单；`enabled` 也是两道门都要开。
- **`/elevated full` 跳过审批的完整条件是"请求侧与宿主审批文件同时 `full` + `off`"**：
  我们只有一层配置，单层下这个条件自动成立；将来补 host 层时要把条件补回去。
- **`scope=shared` 时 `docker` / `browser` / `prune` / `ssh` 四段的 per-agent 覆盖被丢弃**（只保留全局）。

## TDD 用例清单

| 判什么行为 | 用例名 | 先红的样子 | 过没过 |
| --- | --- | --- | --- |
| 沙箱白名单内的命令在容器里跑通 | `SandboxTest#containerBackendTakesOverAfterThePolicyLayer` + `#execContainerBackendRunsInsideAContainer`（后者要 docker） | 现在只有宿主一条路 | ✅ |
| **未在白名单里放 `bundle-mcp` 时，MCP 工具不可见**（负例） | `ToolPolicyAssemblerTest#mcpInvisibleWithoutBundleMcp` | 接上就能看见 → 这条红 | ✅ |
| 放了 `bundle-mcp` 之后 MCP 工具可见 | `ToolPolicyAssemblerTest#mcpVisibleWithBundleMcp` | 同上的另一半 | ✅ |
| 非沙箱会话不看这三张表 | `ToolPolicyAssemblerTest#sandboxToolsIgnoredOutsideSandbox` | 沙箱层的判据挂错位置就会在普通会话里生效 | ✅ |
| 三张表全空时不出这一层 | `ToolPolicyAssemblerTest#emptySandboxConfigAddsNoLayer` | 空层会让"挡掉 0 个"天天刷日志 | ✅ |
| `host=auto`：没有容器就落宿主 | `ExecHostTest#autoNeedsNoSandbox` | 现在只有宿主一条路 | ✅ |
| `host=auto`：有容器就进容器 | `ExecElevationTest#offRunsInContainer` | 同上 | ✅ |
| `host=gateway` 在沙箱会话里被拒 | `ExecElevationTest#gatewayIsRejectedWhileTheSandboxIsOn` | 老实现 `return host` 就放过去了 → 这条红 | ✅ |
| `host=sandbox` 但沙箱没开：启动失败 | `ExecHostTest#sandboxWithoutSandboxFailsFast` | 静默回落宿主 | ✅ |
| `host=node`：启动失败 | `ExecHostTest#nodeFailsFast` | 同上 | ✅ |
| `/elevated on` 在沙箱外跑但**保留审批** | `ExecElevationTest#onRunsOnHostAndKeepsApprovals` | 没有这个指令 | ✅ |
| `/elevated full` 跳过审批 | `ExecElevationTest#fullSkipsApprovals` | 同上 | ✅ |
| `security=deny` 压过 `full` | `ExecElevationTest#fullDoesNotOverrideDeny` | 容易让 full 变成"什么都放" → 这条红 | ✅ |
| 非沙箱部署里 `/elevated` 无行为 | `ExecElevationTest#elevatedHasNoEffectWithoutAContainer` | 会以为 `host=gateway` 也被拒 | ✅ |
| 名单外的人发 `/elevated full` 直接被拒 | `SessionMemoryTest#elevatedRefusedForUnlistedSender` | 「没配」被当成「都行」 | ✅ |
| 四档解析、`ask` 是 `on` 的别名、裸命令看当前档 | `SessionMemoryTest#elevatedParsesItsFourWords` | 没有这个指令 | ✅ |
| 部署没开时拒，并说清该改哪个键 | `SessionMemoryTest#elevatedRefusedWhenDeploymentIsClosed` | 同上 | ✅ |
| TTL 到点自动回到 `off` | `SessionMemoryTest#elevatedExpiresAfterItsTtl` | 忘了关就变成永久 | ✅ |
| 沙箱部署里提权起的宿主后台 shell 也被收掉 | `SandboxTest#destroyKillsElevatedHostShellsWhenAContainerIsConfigured` | 老写法在 `container != null` 时直接 `return`，句柄一条不记 | ✅ |
| 容器内 `curl` 出网失败（`network: none`） | ⏳ 归 **P17b**：`DockerCliExecBackend.Builder` 还没有 `--network`，等上游 | 没有容器 | ⏳ |
| 全局名单有、per-agent 名单没有 → 视为未授权（交集不是覆盖） | ⏳ 无对象：一个进程一个 agent，没有 per-agent 名单可交 | 容易实现成覆盖 | ⏳ |
| `scope=shared` 时 per-agent 的 `docker` 覆盖被忽略 | ⏳ 无对象：`scope` 键与 per-agent 覆盖都没有 | —— | ⏳ |
| wildcard 桶的条目对所有 agent 生效 | ⏳ 无对象，同「没做的那一行」 | 现在没有 agent 维度 | ⏳ |
| 某个 agent 批过的条目不外溢 | ⏳ 同上；这是加 `agent_id` 的全部理由 | 同上 | ⏳ |

**标记（2026-11-18 二审逐行复核）**：表里没有 `❌`；四条 `⏳` 已搬进「缺口」并写明去向。
二审补了两条用例（`gatewayIsRejectedWhileTheSandboxIsOn` / `elevatedHasNoEffectWithoutAContainer`）——
原表里点名的 `gatewayHostRejectedFromSandbox` 与 `elevatedIsNoopWithoutSandbox` 全仓都不存在，
是「表比代码走得远」的典型，这次补上代码而不是改表。

## 验收判据

| 判什么 | 判据 |
| --- | --- |
| 真隔离 | 起 local 实例 + docker 后端：白名单命令跑通、宿主路径不可见。**`curl` 失败这一条做不了**（P17b 的网络限额还没到位），见缺口 |
| 闸门 | 沙箱会话里不列 `bundle-mcp`，`names()` 里没有 MCP 工具；列上之后出现 |
| elevated | 会话里 `/elevated full` 后一条本该弹审批的命令直接跑；`/elevated on` 后仍弹 |
| 门禁 | `./mvnw clean verify` 全绿 |
| 清单 | 两份清单目前没有沙箱 / elevated 的节；本票新增一节，标注"容器化未落地前跳过" |

## 缺口

下面四行原在 TDD 表里，现在搬到这里——**它们没有对象或没有上游参数，不是"以后补一条用例"**：

- **`--network none` / 限额四项 → P17b**（blocked）：等 `DockerCliExecBackend.Builder` 加上
  `network` / 只读根 / 内存 / pids 参数。回来时改 `SandboxBackends#execContainer` 一处，
  用例写在 `SandboxTest`（`containerHasNoEgress`）。在那之前"真隔离"只到"另一份文件系统"这一步。
- **per-agent 的 `allowFrom` 交集 → 无对象**：`agents[].tools.elevated.allowFrom` 要求
  "sender 同时命中全局与 per-agent 两份名单"，而我们一个进程只有一个 agent（换人设要重启），
  第二份名单永远不存在。多 agent 真存在时再补，那时 `Elevations#allows` 改成两段取交集。
- **`scope=shared` 丢 per-agent 覆盖 → 无对象**：`SandboxProperties` 没有 `scope` 键，
  也没有 per-agent 覆盖可丢。真要做多 agent 时它会跟着 `agent_id` 一起出现。
- **`exec_allowlist` 的 agent 维度 → 无对象**：见下面「没做的那一行」。

另外两条原来就在缺口里，不受影响：

- **`openshell` 后端不做**（它是它的托管沙箱产品，我们有 docker + ssh 两条够用）。
- **`elevated` 的 `allowFrom` 通配 `*` 只告警不拦**：它的 policy 检查链我们不做（ADR「不做」）。

## Answer（2026-11-18 收票）

前提按裁决补齐了：P18 回滚、P17b / P17c 等上游加参数（不自己实现）、**P17a 恢复**
（`3dbf99c`，命令真的跑进容器）。这一票的三件因此都有对象，逐件落地：

### 1. `tools.sandbox.tools`（第 9 层 + MCP 闸门）

- 键：`claw4j.tools.sandbox.tools.{allow,deny,also-allow}`，绑在 `SandboxProperties.Tools`。
- 生效条件：`ToolPolicyContext#sandboxed`，由装配处每轮从 `sandbox.mode` 盖上去
  （部署级——逐会话那档 `non-main` 仍然配了启动失败）。
- 位置：管线第 9 层（第 8 层之后、子 Agent 之前），标签 `sandbox`。
- **`bundle-mcp` 是组名**，不写它沙箱会话里看不见任何 MCP 工具——即使 `allow` 是空的。
  展开靠 `ToolTierRegistry#source()`（`builtin` / `business` / `mcp`），MCP 的活视图登记表报
  `mcp`：这样只点名 MCP 那一支，不会把业务工具（`querySales`）顺手放进来。
- 用例：`ToolPolicyAssemblerTest` 4 条（不写 bundle-mcp 不可见 / 写了可见 / 非沙箱不看这三张表 /
  空配置不出层）。

### 2. `tools.exec.host`

`SandboxedExecBackend#target()` 四档：

| 取值 | 行为 |
| --- | --- |
| `auto` | 有容器进容器，没有落宿主 |
| `sandbox` | 必须进容器；`sandbox.mode=off` 时装配期就起不来（`ExecProperties#requireUsable`） |
| `gateway` | 本机；**沙箱开着时被拒**（那是"从沙箱提到宿主"，属于 `elevated`，不是 `host` 的取值） |
| `node` | 启动期被拒：本仓没有多宿主 |

用例：`ExecHostTest` 5 条（auto / gateway / sandbox 缺沙箱 / sandbox 有沙箱 / node）。

### 3. `elevated` 四档

- 配置：`claw4j.tools.elevated.{enabled,allow-from,ttl}`，**默认关**，名单为空时谁都不许。
- 状态：`Elevations`（内存、按会话键、30 分钟过期；`/new` 换段之后跟着走）。
- 命令：`/elevated on|off|ask|full`，`ask` 是 `on` 的别名，裸命令看当前档，词不认识回用法。
  与 `/new`、`/goal` 同层——消息里认出来就当场办，不进模型、不开 run。
- 效果：`on` 跑到沙箱外但**审批照旧**；`full` 跑沙箱外且**跳过审批**（只把 `ASK` 抬成 `RUN`，
  `DENY` 不动——`security=deny` 不是审批能改的东西）。
- 用例：`ExecElevationTest` 4 条（off 进容器 / on 走宿主且问一次 / full 不问直接跑 /
  full 压不过 `security=deny`）；`SessionMemoryTest` 4 条（解析、部署没开、人不在名单、过期回落）。

### 没做的那一行：`exec_allowlist` 的 agent 维度

`agentId` 取的是**选中的人设名**，而换人设要重启——一个进程只有一个 agent，于是
"wildcard 桶（`agent_id` 为 null）与当前 agent 桶"里第二个桶永远不存在。与 `scope=shared`
的 per-agent 覆盖同一类问题：**没有对象**。等多 agent 真存在时再加那一列，那时它才是加列的理由。

### 验收

| 判什么 | 结果 |
| --- | --- |
| 门禁 | `./scripts/verify.sh` 四步全绿：789 用例，失败/错误/跳过 0 |
| 真隔离 | 本机跑 `-Pdocker` 那几条（`SandboxTest` 的容器用例）需要 docker 与镜像；CI 上没有，按既有分组排除 |
| 清单 | `docs/capability-tests.md` 新增第 9 节（沙箱与提权），标注"要 docker 或要开 elevated 才验得到" |

**仍然等上游的**：P17b 的限额（网络 / 只读根 / 内存 / pids / 非 root）——那是"隔离关不关得住"的
那一半，本票不做也不绕。
