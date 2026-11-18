# 权限模型照 OpenClaw 重建

## 这份 spec 是什么

`docs/adr/0030-permission-alignment-with-openclaw.md` 的落地。ADR 的六个阶段各一张票，
另加一张票 07（存在性过滤，ADR 里没有对应的阶段），每张可独立验收、独立回滚；
论证一律在 ADR 里，票面只写"做什么、怎么验"。

范围：`exec` 三段式（`security` × `ask` × `autoReview`）、档位从阶梯改集合、
十一条策略管线与每轮重算、删掉执行前鉴权、审批挪到执行前、容器化之后的
`sandbox.tools` / `exec.host` / `elevated`。不做的部分见 ADR 的「不做」与「缺口」。

## 边界：这一轮只做大模型层的权限（用户 2026-11-18 裁决）

- **做**：模型看得见什么（tool policy）+ 执行前要不要人批（approvals）。
- **不做**：人的细粒度权限、业务数据范围。知识库检索的 ACL 与业务表的数据范围后移到"第二步"，
  那时在工具实现、检索器或独立授权组件里新增。
- **下线**：出站暴露（`claw4j.mcp.server.*`，把本进程工具交给外部 MCP 客户端）整块删掉，
  不是后移。理由：那层"交哪几个"的挑选与入站三层争同一个问题（见 ADR 裁决二末尾）。归**票 08**。
- **删掉（第二步的东西，先清出去）**：
  - 角色目录整条链：`RoleDirectory`、`ConfiguredRoleDirectory`、`X-Claw4j-Role`、**五个通道各自的
    `roles` 配置段**、`Principal.role`、`agent_schedule.owner_role`；
  - MCP 配置里的权限声明：`claw4j.mcp.servers.*.permission` / `.tools` 与 `McpServerProperties.Permission`；
  - `ToolPermission` / `ToolPermissionRegistry` / `Approval`、`Principal` 的 actions/tables、
    `PermissionFingerprint`；
  - 两处照样落点：`HybridDocumentRetriever` 的 doc 判定、`SalesExampleAutoConfiguration` 的表声明。
  归**票 04**。

**为什么现在就删、而不是留着不用**：留着就是两套判定并存。`mcp.servers.demo.permission.tables`
配上去不报错也不生效，排查"为什么没拦住"要花半天；`roles` 更糟——它长得像权限，实际只决定
"能不能进通道"，下一个人会以为改它就能收紧工具。

**删掉角色之后的直接后果**：`Principal` 只剩 `userId`，`toolsBySender` 未配时**同一部署里所有人
拿到同一份清单**。想区分人只能逐人列 `id:`，或者等第二步。五个通道（http / wecom / feishu /
slack / dingtalk）一律从"按角色分组的成员名单"退成一份允许的用户标识白名单（默认空 = 谁都不进）。

## 票与顺序

| 票 | 一件事 | Blocked by |
| --- | --- | --- |
| `01-exec-policy-three-fields` | exec 三段式、五档预设、`allow-always` 落表、复核器接口 | 无 |
| `02-profile-as-explicit-sets` | 档位改显式集合、`alsoAllow`、`group:*` 简写、外部工具桶 | 无 |
| `03-policy-pipeline-eleven-layers` | 十一条管线 + 每轮重算 | 02 |
| `04-delete-pre-exec-authorization` | 删 `ToolPermission` / `ToolPermissionRegistry` / `Approval` 三个类型、四个消费方、整条角色目录 | 03、05、07 |
| `05-approval-before-execution` | 审批落点从 Advisor 挪到工具执行前 | 01 |
| `06-sandbox-host-elevated` | 沙箱层、`exec.host`、`elevated` 四档 | 03 已完成；M9 的 `P17a/b/c` **全部 blocked**（容器化 2026-11-18 回滚）。本票**部分落地**：`exec.host` 的合法性校验已做（`ExecHostTest`），其余三件等容器 |
| `07-tool-filter-include-exclude` | 存在性过滤 `toolFilter` + `toolNames()` 独立 + 拿 example MCP server 联测 | 02 |
| `08-retire-mcp-server-exposure` | 下线出站暴露（`claw4j.mcp.server.*` 整块删） | 无 |
| `09-revise-capability-test-lists` | 修订两份能力验收清单（`README-capability-tests.md` / `docs/capability-tests.md`） | 01–05、08 |

两条不能并行的链：

1. **03 → 04**。清单还没按各层收窄之前删掉执行前鉴权，等于这一段时间里没有任何权限判定——中间态不能上线。
2. **05 → 04**。审批现在是 `AuthorizationAdvisor` 里调的，04 把那个类删掉；审批没先挪到执行前就删，
   中间态是 **exec 完全不审批**，同样是上线级缺口。所以 04 等三张票，它自己是这条链的终点。

07 单独给 04 供一个前置：`toolNames()`。现在五处档位登记借的是 `permissions().keySet()`，
不先拆出来，删 `ToolPermission` 会一起炸。

**08 与上面全部无关**，无依赖、可先做。它只是删一个功能（出站暴露），不涉及清单与审批；
把它塞进 04 会让一个纯删除的动作白等三张票，回滚粒度也不对。

## 三层过滤，别混（02 / 03 / 07 各占一层）

| 层 | 回答什么 | 票 | 被筛掉的还能回来吗 |
| --- | --- | --- | --- |
| 存在性 | 这个工具**有没有** | 07 `toolFilter` | 不能，`allow` 无从作用 |
| 画像 | 这**类**工具允不允许 | 02 `profiles` + 外部工具桶 | 换档或 `alsoAllow` |
| 策略 | 具体哪几个**可见** | 03 十一条管线 | 改 `allow` / `deny` |

**只有这三层**。原本还有一条"出站挑选"（`claw4j.mcp.server.expose`，从清单里挑子集交给外部
MCP 客户端），用户 2026-11-18 裁决**整块下线**——它那层挑选要么等于清单（没新增信息），要么就是
第二份 allow 列表，与 `tools.allow` 争同一个问题。删掉之后工具可见性只有一个消费者，
归**票 08**。

注意与 `claw4j.mcp.servers.*`（**连别人的** MCP server，入站）不是一回事：后者保留，票 07 的
`toolFilter` 就挂在它上面。

## 共同的判据

- **反例先跑红**：每条用例都写成"把实现改回旧行为，只有这一条红"。
- **配置键不改名**：现有键是 `claw4j.tools.exec`，与 OpenClaw 的 `tools.exec` 同名同形，
  各票沿用；不为了"看起来像 ADR"去改键。
- **每票一个行为开关**：01 会改 `ASK` 的语义（见下），其余各票在默认配置下的行为不变。
  改语义的那一票要在 PR 描述里单独写一段，别混在功能里。
- **门禁三条，缺一条不算完**（用户 2026-11-18 要求）：
  1. `./mvnw clean verify` 全绿（含 checkstyle / spotbugs / 结构规则 / 覆盖率下限）；
  2. **`README-capability-tests.md` 里对应的节按原样跑通**——curl + `claw4j.tool.calls` 的端到端清单，
     `verify` 覆盖不到；
  3. **`docs/capability-tests.md` 里对应的行**（含库表断言与负向用例）跑通。
- **第 2、3 条跟着票走，不是最后统一回归**：这两份清单里有一批断言是拿旧权限模型写的，删掉之后
  不会报错、只会静默失效。已核出的断点见**票 09**，那张票负责收尾与全量重跑，但每张票要改的是
  自己那一节。
- **落点标"预计"**：票里的文件路径与测试类都是预计，落地后回填实际位置。

## 已知的两处"现状与 ADR 描述不一致"，各票开工前先核

- `ExecProperties` 的类注释写着"`auto` 需要原生复核器——这里没有，所以不提供那一档"。
  票 01 改成"提供档位 + 复核器接口，默认实现恒回落"，注释要跟着改。
- ADR 曾把"审批人名单"记成缺口，实际 `ApproverRoster`（P03）早已存在且更严
  （空名单=没人能批、提问人不能自批）。缺口只剩"按渠道分组解析"那一半，票 05 不改这块。
