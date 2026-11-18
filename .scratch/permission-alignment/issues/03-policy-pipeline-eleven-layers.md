# 03: 十一条策略管线，每轮重算清单

**What to build:** 把"启动期按部署档位算一次"换成"**每轮按 `(档位 × 各层策略)` 现算**"，
并按它的顺序把缺的层补齐。十一条的顺序与配置键见 ADR §裁决二的表，这里只说怎么落。

新增的六层：按模型档位 / 按模型白黑 / 按 agent × 模型 / 按渠道群 / 按发送者 / 沙箱 + 子 Agent + 继承。
其中子 Agent 那一层还要补 **role** 维度（`leaf` 的固定拒绝清单）。

外加一个**管线之前**的步骤：按模型能力裁剪（本地小模型瘦身、按 provider 摘 `web_search`、
`apply_patch` 的 `allowModels`）。不做这一步，"本地小模型不该看到的工具"仍然会进清单。

**Blocked by:** 02（管线第 1/2 层用的是集合语义的档位）。

**Type:** task

**Status:** resolved

**进度（2026-11-18）**：管线与组装器落地，**十一条里落了六条**（1 画像、3 全局、4 按模型、8 按发送者、
10 子 Agent、11 继承），其余五条有明确理由不做（见下面「落地范围」）。
`ToolPolicyPipelineTest` 10 条 + `ToolPolicyAssemblerTest` 11 条 + `SubagentDelegationTest` 新增 2 条绿，
`./mvnw clean verify` 九模块 SUCCESS。

**裁决来源：** ADR-0030 §裁决二（十一条、四层语义、"每层只减不增"）与 §实现过程·阶段 3。

## 落地范围（2026-11-18 实测，与票面原计划不同）

**先看清一件事**：`ToolCatalog` 早就是活视图（每轮 `getToolCallbacks()` 都问一次来源），所以
"每轮重算"这一步本来就有。这一票真正要做的是"每轮按上下文多筛几层"。

而**上下文在 `ToolCallbackProvider` 展开时拿不到**——那里没有"谁在问、用的哪个模型"。所以
`ChatClientAgentPort#promptOf` 改成每轮显式 `.toolCallbacks(catalog.toolsFor(ctx))`，
`chatClient` 不再挂 `defaultToolCallbacks`（挂了也拿不到上下文）。

落了的六层：

| # | 层 | 落在哪 |
| --- | --- | --- |
| 1 | 部署画像 | `ToolProfile`（票 02），人设可覆盖 |
| 2 | 按模型画像 | `ToolPolicyAssembler.ruleFor` 选中 `byProvider.<key>.profile` |
| 3 | 全局白/黑 | `ToolProfile.select`（票 02） |
| 4 | 按模型白/黑 | `ToolPolicyAssembler` 的 `byProvider:<key>` 层 |
| 8 | 按发送者 | `senderLayer`：`channel:<ch>:<id>` → `id:` → `*`，命中即用 |
| 10 | 子 Agent | `subagentLayer`（内建角色清单 + 配置）+ `SubagentDefinition.selectTools` |
| 11 | 继承 | `ToolPolicyContext.inherited`，排在最后 |

**没做的五层，理由逐条**（都进 ADR 缺口，不造空配置）：

| # | 层 | 为什么不做 |
| --- | --- | --- |
| 5 | 按 agent | 我们没有"一个进程装多个 agent"。人设只覆盖画像，不覆盖 allow/deny |
| 6 | 按 agent × 模型 | 同上；造出来会是一层永远拿不到 agentId 的配置 |
| 7 | 按渠道群 | 没有渠道群概念，`ToolPolicyContext.groupId` 恒为 null |
| 9 | 沙箱工具层 | 沙箱本体是票 06，`sandboxed` 恒为 false |
| — | 前置按模型裁剪 | 没有"本地小模型"概念；`web_search` 的摘除条件在我们这里不存在（搜索工具要么装了要么没装） |

另外 **`apply_patch` 的别名不对称没照抄**：我们没有 `apply_patch`（`Write`/`Edit` 本身就是两个
独立工具名），照抄会得到一条永不生效的规则。两条对应用例随之不做。

## 要动的地方（预计）

| 位置 | 改什么 |
| --- | --- |
| `claw4j-core/**/principal/ToolPolicyPipeline.java`（新） | 纯函数：一层一个 matcher，`before → after` 累积收窄；不依赖 Spring，可单测 |
| `claw4j-agent/**/ToolsProperties.java` | 加 `byProvider.<key>.*`、`toolsBySender.*`、`subagents.tools.*`、`sandbox.tools.*`；`agents.<id>.tools.*` 单独一份 |
| `claw4j-agent/**/AgentAutoConfiguration.java` | `select` 的调用点从启动期挪到每轮组装 prompt 之前 |
| `claw4j-capability/**/tool/ToolCatalog.java` | 已经是活视图（supplier），确认重算不会与它打架：清单是"每轮筛一次"，不是"每轮重建 callback" |
| 群层 | 群标识**从会话解析**（`sessionKey` / `spawnedBy`），调用方传进来的只当候选、对不上就丢 |
| 前置裁剪 | 三个过滤合成一步，放在管线之前 |

四层语义，逐条照抄（都在 ADR 表下方，这里只提醒最容易做错的）：

- **单层内 `deny` 先判**；`allow` 为空则全放，非空则只有命中 `allow` 的才放。
- **`apply_patch` 的别名不对称**：`allow` 含 `write` 时 `apply_patch` 一并放行；`deny: ["write"]`
  **不拦** `apply_patch`。这个不对称是坑，别"修正"成对称。
- **`agents[].tools.toolsBySender` 空 `{}` 不覆盖全局**（编译不出策略就回落）。
- **`agents[].tools.sandbox.tools` / `agents[].tools.profile` 是替换，不是叠加**。

## TDD 用例清单

| 判什么行为 | 用例名 | 先红的样子 | 过没过 |
| --- | --- | --- | --- |
| `deny` 命中即拒，且 `allow` 管不到它 | `ToolPolicyPipelineTest#denyWinsOverAllow` | 只有三层、按顺序叠 allow/deny，没有单层语义 | ✅ |
| `allow` 非空时其余全封 | `ToolPolicyPipelineTest#nonEmptyAllowClosesEverythingElse` | 同上 | ✅ |
| `byProvider` 键支持 `provider` 与 `provider/model` 两种 | `ToolPolicyAssemblerTest#byProviderAcceptsBothKeyForms` | 没有这一层 | ✅ |
| 发送者层：`channel:<ch>:<id>` 先于 `id:` 命中 | `ToolPolicyAssemblerTest#senderChannelKeyBeatsBareId` | 没有这一层 | ✅ |
| 没有 `toolsBySender`（含空表）时不上层，清单不受发送者影响 | `ToolPolicyAssemblerTest#noSenderConfigMeansNoNarrowing` | 容易把 `{}` 当覆盖 → 这条红 | ✅ |
| 子 Agent 固定拒绝：定时任务三件一律不给，`leaf` 再多拒 `Task` | `ToolPolicyAssemblerTest#everySubagentDeniesScheduledTasks` + `#leafDeniesSpawningAgain` | 现在只有白/黑名单，没有 role | ✅ |
| 子 Agent 显式 `allow` 里点名的工具能压过上面那张清单 | `ToolPolicyAssemblerTest#explicitAllowOverridesRoleDeny` | 容易做成"硬拒" → 这条红 | ✅ |
| 继承层：父会话的策略被带上 | `ToolPolicyAssemblerTest#inheritedPolicyAppliesToChild` | 没有这一层 | ✅ |
| 每层真的删了工具才留痕，没删不留 | `ToolPolicyPipelineTest#auditOnlyWhenToolsRemoved` | 容易每层都打一行 → 日志里看不出谁起了作用 | ✅ |
| 层序固定：档位 / provider / allow / deny / sender / 子 Agent / 继承 | `ToolPolicyAssemblerTest#layersFollowTheDocumentedOrder` | 顺序换了之后"谁最后说话"变了，而现象只是清单不同 | ✅ |

**复核：2026-11-18 `./mvnw clean verify` 九模块全绿。** 表里原来有 8 行是"照它的十一层逐层抄"，
其中 7 行已不在本票范围（群层 3 行、沙箱 1 行、apply_patch 别名 2 行、本地模型 1 行），
另 1 行（两份 provider 切换）留待票 09 在真部署上验——**都搬进了下面「缺口」，不再是本票的 TDD**。
子 Agent 那一行也是复核时改的：原文抄了它的工具名（`gateway` / `agents_list` / `sessions_send`），
我们没有那几个工具，实际固定拒绝的是定时任务三件 + `Task`。

## 验收判据

| 判什么 | 判据 |
| --- | --- |
| 每轮重算 | ⏳ **未端到端验**。两段各自验过：`ToolPolicyAssembler` 按上下文产出不同的层、`ToolPolicyPipeline` 按层收窄。连接点 `promptOf` 没有对应用例——要验它得有一份配了 `toolsBySender` 的部署，而 yaml 里 `"channel:feishu:alice"` 这种键在 `@SpringBootTest(properties=…)` 里写不出来（属性源的 `.` 与 `:` 语义冲突）。留给两份能力清单（票 09）在真部署上验 |
| 审计可读 | ✅ `collectToolCallbacks` 每层一条 INFO：`工具策略层 {label}：排除 N（deny [...] / 不在 allow [...]）`，且只在真删了工具时打 |
| 默认不变 | ✅ `ToolProfileMatrixTest` 六条逐档断言仍绿（`toolsBySender` 没配时不上层） |
| 门禁 | `./mvnw clean verify` 全绿 |
| 清单 | 两份清单里"为什么看不见某个工具"的排查段按十一层重写（原来只有档位 / allow / deny 三种解释） |

## 缺口

**原 TDD 表搬过来的 8 行**（都不在本票范围，别再按旧表去找用例）：

| 原判什么 | 去向 |
| --- | --- |
| `apply_patch` 跟随 `write` 放行 / `deny: [write]` 不拦 `apply_patch` | **不做**：我们没有 `apply_patch` 工具（ADR-0030 缺口表"`apply_patch` 别名"一条） |
| 群层三条（候选链回落、不可信 `groupId` 丢弃、群内先 sender 后 tools） | **不做**：渠道不给稳定群标识，ADR 退场条件已触发；`ToolPolicyContext#groupId` 恒为 null |
| 非沙箱会话跳过沙箱层 | **归票 06**：沙箱本体在那一票，`ToolPolicyContext#sandboxed` 现在恒为 false |
| 本地小模型摘掉 `web_search` | **不做**：前置按模型裁剪在 ADR 缺口表里判了不做（清单每轮按 provider 重算，但不按模型能力裁） |
| 两份 provider 策略切换后清单不同 | **归票 09**：`ToolPolicyAssembler` 与 `ToolPolicyPipeline` 各自验过，连接点 `promptOf` 没有对应用例（yaml 键里带 `:`，`@SpringBootTest(properties=…)` 写不出来），留给两份能力清单在真部署上验 |

- **没有 `spawnedBy` 之外的继承来源**：我们只从会话里存的父策略取，不做"跨进程继承"。
- **群层依赖渠道给稳定群标识**：拿不到就按 ADR 退场条件处理（不做，记进 `docs/agents/guardrails.md`）。
- **缓存命中率**：配了 `toolsBySender` 之后 prompt 前缀随人变化，这是 ADR 代价 10，本票不优化。
