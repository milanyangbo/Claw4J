# 09: 修订两份能力验收清单（`README-capability-tests.md` / `docs/capability-tests.md`）

**What to build:** 把两份能力验收清单里**依赖旧权限模型**的断言改掉，然后按改后的版本全量跑一遍。

这两份是端到端验收，`./mvnw clean verify` 覆盖不到（真实模型、真实通道、真实 `claw4j.tool.calls` 指标）。
它们现在是拿 `ToolPermission` 那套写的，删掉之后**不会报错，只会静默失效**——清单还在、跑还是绿的、
但已经验不到任何东西。这是最坏的一种退化：门禁在，判据没了。

**Blocked by:** 01、02、03、04、05、08（行为变化的来源）。本票是收尾，负责兜底与全量重跑；
**每张票要改的是自己那一节**，等这一票一次改完，中间几张票的"跑通"就是假的。

**Type:** task

**Status:** resolved

**裁决来源：** ADR-0030 §实现过程·每张票完工前都要过三条。用户 2026-11-18 要求。

## 断点清单（逐条核过代码与清单原文）

### `README-capability-tests.md`（697 行，curl + 看指标）

| 位置 | 现在写的 | 为什么不成立 | 改成 |
| --- | --- | --- | --- |
| **全文件**（约 40 处） | 每条 curl 都带 `-H 'X-Claw4j-Role: admin'` | 角色头整条删了（票 04） | **全部删掉这个头**。不要留着当"没用也无害"——它会让读者以为角色还在 |
| 47 / 57–68 | "三个 `-H` 分别是身份、角色、请求体"、"`X-Claw4j-Role` 取 `admin` / `read-only` / `guest`，默认三个角色见 `application.yml`" | 同上 | 改成"两个 `-H`：身份与请求体"；删掉角色那一整段 |
| 68 | "每节、每次复测都换一个用户名，否则模型会记得上一轮失败" | **仍成立**（与角色无关） | 保留 |
| 153 | "权限登记漏了是**每次都被拒**（`outcome:denied` 会涨）" | 没有执行前鉴权了，漏登记不会被拒 | 改成"工具没进清单 → 模型根本调不到；回答里会出现'我没有这个工具'而不是'没有权限'" |
| §3 脚本包（413 起） | 整节默认"要 admin 才能跑" | `run_script` 动作删了 | 按票 02 的档位/外部工具桶重写：脚本包属于哪个档，就在哪个档的清单里 |
| 453–455 | "动作是 `run_script`，默认只有 `admin` 有；换成 `read-only` 再问一次会被拒——`outcome:denied` 涨、`ok` 不涨" | 同上 | 整条删掉（没有"换个人就被拒"这种验法了） |

### `docs/capability-tests.md`（550 行，机器向全量回归）

| 位置 | 现在写的 | 为什么不成立 | 改成 |
| --- | --- | --- | --- |
| 47 | "三个请求头：`X-Claw4j-User`（必填）、`X-Claw4j-Role`（不填走默认 `read-only`）" | 角色头删了 | 改成"两个头：`X-Claw4j-User`（必填）、`Content-Type`" |
| 84 / 87 / 89 | 脚本函数里 `-H "X-Claw4j-Role: ${R:-admin}"`、`R` 环境变量 | 同上 | 删掉 `R` 与那个头 |
| 181–183 | "**权限按到点那一刻重算**……库里只留谁+角色，每次拿当时的角色表换一份权限。角色被删、或者**那张表不给它了**，到点就停用" | `RoleDirectory` 整条链删了（票 04）：不重算身份，也不比对表/动作 | 整段删。改成"到点只用任务行里的 `owner_user_id`；任务要用、且**需要人批**的工具，建的时候就拒" |
| 203 | "要先给角色 `schedule_task` 这个动作（仓库里 `read-only` 已经有了）" | 动作没了 | 删掉整句 |
| 211–218 | "收权怎么验：把创建者的角色从 `claw4j.channel.http.roles` 里删掉 → 等一轮扫描" | 角色表没了，**没有"收权"这个动作可验** | 只保留"把 `next_run_at` 改到过去 → 等一轮扫描"这半（验到点会跑）；后半删 |
| 218 | `ScheduleFlowTest` 十三条里"角色被删就停用、工具权限被收就停用" | 两条都随 `RoleDirectory` / `canUse` 消失 | 用例归票 04 改；**条数与用例名落地后回填，不要在这里猜** |
| 235 | "`claw4j.channel.wecom.roles.<角色>.users` 里填成员 UserID，角色名必须能在 `http.roles` 里找到同名" | 角色分组没了 | 改成"`claw4j.channel.wecom.users` 填允许的成员 UserID 白名单；默认空 = 谁都不进" |
| 286 | "三个调度工具要 `schedule_task` 动作（`read-only` 与 `admin` 有，`guest` 没有）" | 动作没了 | 改成"跟着 `claw4j.schedule.enabled` 进退；档位见票 02 的表" |
| 288 | "`systemStatus` 要 `read_system`，`querySales` 要表权限，`propose_script` 要 `draft_script`——用 `read-only` 测会被拒" | 三种判定全删 | 改成"这三个不再有动作/表要求；数据范围由工具实现兜（`SalesSqlGuard` 只读、`systemStatus` 本身只读）" |
| 289 | 三个目标工具"各要一个动作（`get_goal` / `create_goal` / `update_goal`）" | 同上 | 删掉动作要求，保留"不跟着 `persistence.enabled` 进退" |
| 318 | "`mail_send` 按工具覆盖权限 → `R=read-only` 被拒；`hit mail_send denied` +1" | 按工具覆盖权限的机制没了 | 改成"`mail_send` 在不在清单里由工具桶/档位决定"；`denied` 计数不再适用 |
| 397 | "## 5. Scripts：脚本包（3 个，**全部要 admin**）" | 同 §3 README | 按档位/桶重写标题与正文 |
| 243 | "没有权限：你的企业微信账号还没有在 Claw4J 里登记" | **仍成立**（白名单还在），只是不再提角色 | 保留，话术不用改 |
| 105–145 | 审批段（**谁**能批、自批被拒、`403` 那套） | **不受影响**：`ApproverRoster` 是独立一张表，按 `userId` 比，不挂角色 | 不动，但要在开头注明"这块与角色 / `ToolPermission` 无关" |

### 只有一处不能跟着删（其余都删）

| 保留的东西 | 为什么 | 判据 |
| --- | --- | --- |
| `SalesSqlGuard`（`querySales` 的只读护栏） | 是**工具实现内**的护栏，不是 `ToolPermission` | `docs/capability-tests.md:270`"用 `querySales` 执行 delete → 拒绝执行"这条**继续有效** |

**`ScheduleAuthorization.currentOwner` 这条不保留了**：`RoleDirectory` 随角色目录一起删，
到点不再重算身份。所以"角色被删就停用并通知"这句**要删掉**，不是保留。

## 复核（2026-11-18）

**这份票没有 TDD 用例表**，复核对象是"清单里每一条验法还站不站得住 + 有没有真跑过"。

### 断点清单逐条

| 位置 | 处理 | 状态 |
| --- | --- | --- |
| README 全文件 `X-Claw4j-Role`（28 处，不是 40） | 全部删掉 | ✅ |
| README 47 / 57–68 | "三个 `-H`" → "两个"；角色那一整段删，换成"必须在 `claw4j.channel.http.users` 里" | ✅ |
| README 68（每次换用户名） | 保留 | ✅ 真跑验证：同一个用户问第三遍，模型直接沿用上一轮结论不查库 |
| README 153 | 改成"工具没进清单 → 模型调不到；回答里是'我没有这个工具'而不是'没有权限'" | ✅ |
| README §3 / 453–455 | `run_script` 动作那句删；改成"脚本包属 `coding` 档，降档就在清单里看不见" | ✅ |
| README §8 "表级拒（换成 guest）" | 整条换成"名单外的人拿 `401`、工具计数不动" | ✅ 真跑：`nobody` → 401；顺手改正了原稿里写错的 `403` |
| docs 47 / 84–89 | 两个头；函数里删掉 `R` 与角色头 | ✅ |
| docs 181–183（权限按到点重算） | 整段删，换成"到点只用 `owner_user_id`；唯一还会让任务停的是'工具要人批'" | ✅ |
| docs 203 / 286 / 288 / 289 | 动作/表那一套全删；档位说明补上"默认 `full`" | ✅ |
| docs 211–218（收权怎么验） | 只留 `next_run_at` 改到过去那半；`ScheduleFlowTest` 改成十五条并把"角色被删就停用"换掉 | ✅ |
| docs 235（企微 roles） | 换成 `users` 白名单、默认空 | ✅ |
| docs 318（`mail_send` 按工具覆盖权限） | 换成 `tool-filter` 决定它存不存在 | ✅ |
| docs 397（§5 标题"全部要 admin"） | 换成"属 `coding` 档"，并写明这一节要 `full` 档 | ✅ 真跑撞出来的：`sales`（messaging）下 `Task` 与脚本包都看不见，那不是缺陷 |
| docs §9 负向 | 删掉表级/动作级两条；补回"SQL 只读护栏 / 脚本路径越界 / 审批拒绝"三条仍然有效的 | ✅ 前两条真跑过 |
| docs §10 审计 | `actor_role` 标成死列；`blocked` 那类行没有生产者，改指审批表 | ✅ |
| docs §7 / §1 里"这个进程堆内存多少" | **点名工具**（`用 systemStatus 查…`）：原话模型会反问"哪个进程"，判据恒假 | ✅ 真跑撞出来的 |

### 验收判据

| 判什么 | 结果 |
| --- | --- |
| 单测 | `./mvnw -o clean verify` 全绿：799 条用例，失败 0 / 错误 0 / 跳过 0，八个模块全 SUCCESS（本轮加了 `LiveMcpTiers` 与一条回归用例） |
| 清单 1（README §0–§8） | 跑通 §1/§2(demo)/§3/§4/§5/§7/§8；`github_*`、`mail_*`、`simulateSlowCall`、IM 三条没跑（缺 CLI / 缺凭据 / 要等 30 秒） |
| 清单 2（docs 全表 + §9） | 同上，记录写在 `docs/capability-tests.md` 末尾那张表里 |
| 没有静默失效 | 七个词逐个 grep：`X-Claw4j-Role` / `read-only` / `guest` / `要 admin` / `动作是` 均 0；`被拒` / `denied` 剩下的每一处都是**新语义下的正确用法**或是"这条已作废"的说明 |
| 记录 | 两份清单末尾各有一份"2026-11-18 真跑记录"，逐节写通/未通与原因 |

### 真跑抓到并修掉的一个 bug

`java-http` 启动时 DOWN、十五秒后重连成功，之后**每一轮对话都 500**：
`工具 cronNext 没有声明能力画像`。根因是 `configuredMcpTierRegistry` 在启动时把
`provider.toolNames()` 拍成了快照。改成活视图（`LiveMcpTiers`，两条并行来源共用），
回归用例 `McpToolTest#tierRegistryAnswersForToolsThatAppearedLater`。
这个 bug 单测抓不到、`clean verify` 也抓不到，只有"按清单真跑一遍"能撞出来——正好是本票存在的理由。

## 四类改法（别混）

1. **角色头 → 删**：`-H 'X-Claw4j-Role: …'` 全文件清掉，`R` 环境变量一起删。
   "换个人就被拒"这种验法整条作废。
2. **角色 → 清单**：原来"`read-only` 调不到，被拒"改成"清单里没有它"（前提是票 02 让它属于别的档/桶）。
   判据从 `outcome:denied` 涨不涨，换成**这个工具在不在 `tools/list` 里、`tool:calls{tool=…}` 有没有涨**。
3. **动作 / 表 → 无**：`run_script` / `read_system` / `draft_script` / `schedule_task` / 表权限这些话直接删，
   不要换个名字保留（那正是这次要清掉的东西）。

4. **定时任务**：**只剩一条判据**——任务要用的工具**要不要人批**（要批的不能定时跑）。身份不重算
   （`RoleDirectory` 已删）、表/动作不比对。"收权之后下次到点就停"这套验法整段作废，只留
   "把 `next_run_at` 改到过去 → 它照常跑起来"。

## 验收判据

| 判什么 | 判据 |
| --- | --- |
| 单测 | `./mvnw clean verify` 全绿（含票 04 改过的 `ScheduleFlowTest` 与 `SalesQueryTest`） |
| 清单 1 | `README-capability-tests.md` 从 §0 到 §8 **逐节真跑一遍**（起 local profile、`cache.enabled=false`），每节按它自己写的判据核对：回答内容对 + `claw4j.tool.calls{tool=…}` 涨 |
| 清单 2 | `docs/capability-tests.md` 全表跑一遍，含**负向**那节（§9）——改动之后"不该通的地方"要重新确认：`SalesSqlGuard` 拒绝仍在、`TraversalGuard` 仍在、审批拒绝仍在 |
| 没有静默失效 | 全仓 grep 这两份清单里的 `X-Claw4j-Role` / `read-only` / `guest` / `被拒` / `denied` / `要 admin` / `动作是`，每一处要么改掉，要么标注"与本轮无关"并给出理由 |
| 记录 | 跑的结果写回各节（真跑通的写"已验证 + 日期"，跑不通的开缺口），不留"应该能过" |

## 缺口

- **"角色被删就停用"与"工具权限被收就停用"两条能力都没了**：`RoleDirectory` 与 `canUse` 一起删，
  定时任务到点只用 `owner_user_id`。工具不在当时的清单里就调不到，但**任务本身不会停用**，
  主人也收不到通知。写进 ADR 缺口。
- **同一部署里所有人看到同一份清单**：角色没了，`toolsBySender` 未配时没有"按人区分"的手段。
  两份清单里原来靠"换角色再问一次"验的东西，现在没有对应验法——这一层不补，就别在清单里假装验了。
- **两份清单的"权限"章节名要改**：现在还叫"权限"，实际验的是"清单可见性"。名字不改，下一个人会
  按旧语义读。
- **五条没在本机验过**（不是"应该能过"，是明确没跑）：`github_*` 三个（本机 `gh` 没登录）、
  `mail_*` 两个（没装 `himalaya`，只到"链路通了、缺 CLI"）、`simulateSlowCall`（要等 30 秒超时）、
  IM 四条通道（要真凭据）、§10 的审计 SQL（读库连接串那一步被敏感内容保护拦下，按规矩没重试）。
  这些要在有凭据的机器上补，别在下一轮复测时默认它们通过了。
- **运行期 MCP 掉线仍然会 500**：这一轮修的是"迟到重连之后工具没有画像"，没修
  "服务跑着的时候对端没了，下一句拿到 `ConnectException`"（票 03 已记的那个缺口）。
  清单里那条"起好之后再 kill 掉 server"现在验的就是这个已知缺口，别把它读成新问题。
