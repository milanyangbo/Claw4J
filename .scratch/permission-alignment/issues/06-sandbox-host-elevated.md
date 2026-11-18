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

**Status:** ready-for-agent

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

四条照抄的语义：

- **`elevated` 只在沙箱化时改变行为**：非沙箱部署里 exec 本来就在宿主上跑，这个指令等于无操作。
- **`allowFrom` 是交集不是覆盖**：`agents[].tools.elevated.allowFrom` 要求 sender **同时命中**
  全局与 per-agent 两份名单；`enabled` 也是两道门都要开。
- **`/elevated full` 跳过审批的完整条件是"请求侧与宿主审批文件同时 `full` + `off`"**：
  我们只有一层配置，单层下这个条件自动成立；将来补 host 层时要把条件补回去。
- **`scope=shared` 时 `docker` / `browser` / `prune` / `ssh` 四段的 per-agent 覆盖被丢弃**（只保留全局）。

## TDD 用例清单

| 判什么行为 | 用例名 | 先红的样子 |
| --- | --- | --- |
| 容器内 `curl` 出网失败（`network: none`） | `SandboxExecTest#containerHasNoEgress` | 现在没有容器 |
| 沙箱白名单内的命令在容器里跑通 | `#allowlistedCommandRunsInsideSandbox` | 同上 |
| **未在白名单里放 `bundle-mcp` 时，MCP 工具不可见**（负例） | `#mcpInvisibleWithoutBundleMcpInSandboxAllow` | 现在接上就能看见 → 这条红 |
| 放了 `bundle-mcp` 之后 MCP 工具可见 | `#mcpVisibleWithBundleMcp` | 同上的另一半 |
| `host=auto`：有沙箱进沙箱，无沙箱落宿主 | `#autoPrefersSandboxThenHost` | 现在只有宿主一条路 |
| `host=gateway` 在沙箱会话里被拒（只有 `auto` 允许从沙箱提到宿主） | `#gatewayHostRejectedFromSandbox` | —— |
| `/elevated on` 在沙箱外跑但**保留审批** | `#elevatedOnKeepsApprovals` | 没有这个指令 |
| `/elevated full` 在 `enabled` + 名单内时跳过审批 | `#elevatedFullSkipsApprovals` | —— |
| 名单外的人发 `/elevated full` 直接被拒 | `#elevatedFullRejectedForUnlistedSender` | —— |
| 全局名单有、per-agent 名单没有 → 视为未授权（交集不是覆盖） | `#elevatedAllowFromIsIntersection` | 容易实现成覆盖 → 这条红 |
| 非沙箱部署里 `/elevated` 无行为 | `#elevatedIsNoopWithoutSandbox` | —— |
| `scope=shared` 时 per-agent 的 `docker` 覆盖被忽略 | `#sharedScopeDropsPerAgentDocker` | —— |
| wildcard 桶的条目对所有 agent 生效 | `ExecAllowlistTest#wildcardEntryAppliesToEveryAgent` | 现在没有 agent 维度 |
| 某个 agent 批过的条目不外溢 | `#agentEntryDoesNotLeakToOtherAgent` | 同上；这条是加 `agent_id` 的全部理由 |

**标记：整张表未复核（票还没开始）。** 收票时逐行改成 `✅` / `❌` / `⏳`，规则见 `AGENTS.md` 第 2 条。

## 验收判据

| 判什么 | 判据 |
| --- | --- |
| 真隔离 | 起 local 实例 + docker 后端：`curl` 失败、宿主路径不可见、白名单命令跑通 |
| 闸门 | 沙箱会话里不列 `bundle-mcp`，`names()` 里没有 MCP 工具；列上之后出现 |
| elevated | 会话里 `/elevated full` 后一条本该弹审批的命令直接跑；`/elevated on` 后仍弹 |
| 门禁 | `./mvnw clean verify` 全绿 |
| 清单 | 两份清单目前没有沙箱 / elevated 的节；本票新增一节，标注"容器化未落地前跳过" |

## 缺口

- **`openshell` 后端不做**（它是它的托管沙箱产品，我们有 docker + ssh 两条够用）。
- **`host=node` 只是占位**：我们没有多宿主，值可以配进来但只会落到本机。
- **`elevated` 的 `allowFrom` 通配 `*` 只告警不拦**：它的 policy 检查链我们不做（ADR「不做」）。
