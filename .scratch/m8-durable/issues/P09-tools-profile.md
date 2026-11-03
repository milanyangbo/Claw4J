# P09: 能力轴——tools profile 决定清单里有什么

**What to build:** 在数据轴之外补一条**能力轴**：`minimal / messaging / coding / full` 四档
profile，外加按工具的 allow / deny 分组。它决定的是"这个部署**有哪些**工具"，
而不是"这个人能不能调"——切到 `messaging` 档之后 `run_script` 根本不在清单里，
检索不到、模型不会去计划它，而不是等它调了才发现被拒。

这条轴必须与数据轴正交（ADR-0020 的裁决）：profile 是部署决定，改一次要重启；
"谁能碰哪张表"是人事决定，按身份走。合并成一个维度会让"换档位"变成一次权限重排。

**Blocked by:** None (can start immediately)——与 P08 互不依赖

**Status:** resolved

- [x] 四档语义在配置里可表达、可读出；缺省是 `full`＝今天的全部工具，**零行为变化**
      （现有用例一条都不用改断言）
      —— `ToolsProperties`（`claw4j.tools`）缺省 `FULL` + 两张空表；`ToolProfile.unrestricted()` 是同一件事。
      证据：`ToolProfileTest#unrestrictedKeepsEverything`；`application.yml` 里
      `tools.profile: ${CLAW4J_TOOLS_PROFILE:full}`；全量 478 → 496 条，没有一条旧用例改断言
- [x] `messaging` 档下 `run_script` / `draft_script` / `propose_script` 与脚本包工具
      不在清单里，检索也检索不到；`querySales` / `systemStatus` / `recall_session` 仍在
      —— 注意 `draft_script` 是**动作名**，对应的工具名是 `propose_script`（`SkillGeneratorToolCallback`），
      两者都归 `CODING`；脚本包工具（`summarize` / `xurl` 这类）同样归 `CODING`。
      证据：`ToolProfileTest#messagingDropsCodingToolsOnly`、
      `ApplicationAssemblyTest#messagingProfileFiltersTheManifestNotTheExecution`
- [x] 判据落在**清单**上：断言清单组装方法（`AgentAutoConfiguration#collectToolCallbacks`）
      的返回值或索引检索结果，不是"调了被拒"——"调了才发现被拒"会让模型先计划再撞墙
      —— 两条用例都直接断言 `collectToolCallbacks` 的返回值（名字列表）。
      索引侧不必单独验：`defaultToolCallbacks(tools)` 传的就是这份清单，工具循环从它建索引
- [x] 元工具不因过滤消失：`toolSearchTool` 在任何"还有工具"的档里都在，
      否则一个工具都搜不到（无工具档要显式定义，不是顺手过滤成空）
      —— `AgentAutoConfiguration#toolSearchTierRegistry` 声明为 `MINIMAL`。
      两道保障：`ToolProfileTest#minimalKeepsMetaTool`（minimal 档它还在）+
      `#emptyResultFailsWithBreakdown`（筛完一个不剩是启动失败，不是静默空清单）
- [x] 未分档的工具**启动失败并点名工具名**；同一工具同时命中 allow 与 deny 也启动失败并点名。
      不能让新加的工具悄悄落进 `full`——那正是"默认比声明更宽"的老问题换个位置重演
      —— `ToolProfile#select` 查不到档位即抛，报错带工具名 + 档位 + "去登记 ToolTierRegistry"；
      冲突在 `ToolProfile` 构造期就抛（不等 `select`，否则裁剪过的部署永远走不到那句校验）。
      证据：`ToolProfileTest#undeclaredTierFailsAndNamesTheTool` / `#denyAppliesAfterAllowAndConflictFails`、
      `ApplicationAssemblyTest#toolWithoutTierFailsAtAssembly`。
      **这条校验在本票里真的抓到过一个漏网工具**：`ApplicationAssemblyTest` 的探针 `sendNotice`
      第一次跑就红了，点名到它——见 Comments
- [x] 两轴正交：`messaging` 档下 `read-only` 身份仍读不到 `sales_order`；
      `full` 档下 `guest` 身份仍调不动 `send_email`（两条轴各拦各的，谁都不替代谁）
      —— `ToolProfileTest#profileDoesNotReplacePermission`：档位放行 `querySales` 之后，
      没有表权限的身份过 `ToolPermission#grantedTo` 仍然 false；`full` 档不会让 `guest` 拿到发邮件动作。
      档位只决定"清单里有没有"，`AuthorizationAdvisor` 那一层一个字没改
- [x] 子 Agent 走同一个出口：父被挡掉的工具，子 Agent 点名也拿不到
      （`SubagentAutoConfiguration` 复用 `collectToolCallbacks`，这条要真测）
      —— 把子侧工具集抽成 `SubagentAutoConfiguration#subagentToolCallbacks`（public static），
      于是"父子一致"可以直接问：`SubagentDelegationTest#subagentToolsRespectTheDeploymentProfile`
      断言 messaging 档下子侧清单只剩 `querySales`
- [x] 生效时机是**装配期**：启动日志能答出"这个部署是什么档、挡掉了几个工具、哪几个"
      —— `AgentAutoConfiguration#report`，实测输出：
      `工具档位 MESSAGING：清单 2 个，挡掉 1 个（档位 [run_script] / allow [] / deny []）`。
      默认档下不刷这行（挡掉 0 个时不打），`allow` / `deny` 里写了清单没有的名字另打一条 WARN
- [x] `./scripts/verify.sh` 全绿，跳过 0，且 agent 行覆盖率不低于 60%（现状 62.0%，见 Comments）
      —— **496 条用例全过、跳过 0**；行覆盖 core 88.8% / capability 84.9% / agent 62.0%；
      测试文件数 core 8、其余 10（上限 10），单文件最大方法数 20（上限 20）

## Comments

- 2026-11-03 拆票。依据 ADR-0020 的 P09 段原文：「现在的权限是纯数据轴（表 × 动作），
  表达不了"这个部署只做消息、不许跑 shell"——不给 `run_script` 会把"读消息"一起卡住。
  补一条能力轴：`minimal / messaging / coding / full` 四档 profile + 按工具的 allow / deny 分组。」
  原文没有给判据，判据按 spec 的 Testing Decisions 那条写：**"清单里没有"，不是"调了被拒"**。
- **现状核过，票面的"现在是什么"照着代码写**：
  - 权限只有数据轴：`ToolPermission(action, tables, approval)` + `Principal#canUse`，
    由 `AuthorizationAdvisor` 在"模型返回之后、工具执行之前"判，未登记权限按拒绝处理（ADR-0007）；
  - 工具清单**只有一个组装点**：`AgentAutoConfiguration#collectToolCallbacks`
    （父 Agent 与子 Agent 共用，注释写着"收工具这件事只该有一处实现"），
    `MeteredToolCallback` 也在同一层包；
  - **已经有一个先例**：调度三件套跟着 `claw4j.schedule.enabled` 一起进退，
    `docs/capability-tests.md:282` 原话「关掉调度时它们从工具清单里消失，不是被拒」。
    本票是把这一条从一个开关推广成档位，不是发明新机制；
  - 现在**没有**任何 `tools.profile` 的实现或配置项；`ToolSearchProperties` 里只有 `maxResults` 一个数；
  - 清单规模 23 个（`docs/capability-tests.md` 编号 1–23），含元工具、MCP 发现的、脚本包、子 Agent 委派。
- **一个必须交代的取舍（MCP）**：MCP 的工具名是**运行时**从远端发现的（`McpToolProvider`），
  没法预先分档；而且 MCP server 的连接发生在 provider bean **构造期**
  （`ConfiguredMcpServersAutoConfiguration` 注释：「连接与工具清单都在建这个 bean 时完成」），
  过滤却发生在 `collectToolCallbacks` 这一层——**两层不同**。于是"被档位排除的 server"
  仍然会被连上、仍然会拉一次工具清单。要么接受这个代价（连了但不暴露），
  要么把判断前移到 server 装配处（那里就能不连）。票不替实现选，但**必须在 Comments 里写明选了哪个、代价是什么**。
- **留给实现的四个决定**（票不替它选）：
  1. 分档数据放哪：每个工具自己声明（像 `ToolPermissionRegistry` 那样跟着工具走），
     还是集中一张表。倾向前者——理由与权限登记表相同，"它要什么"只有工具自己知道；
     但集中一张表更容易做"未分档就失败"的穷举校验。**无论哪种，穷举校验必须有**；
  2. 过滤落在 `collectToolCallbacks` 内部还是调用它之前（它是 `static`，子 Agent 也在用）；
  3. MCP 工具怎么归：按 server 声明档、还是只在 `full` 出现；
  4. 配置键名与档位的精确集合（`claw4j.tools.profile` + `allow` / `deny` 的写法）。
- **不做**：
  - per-agent 的 profile 覆盖——那是 P15（本票只有"这个部署是什么档"）；
  - 运行期热切换档位：改档要重启。做得成不做得成先不论，**热切换会让"清单"与"已经在跑的 run"
    出现两种真相**，而 run 恢复时读的是哪一份没人说得清；
  - 多级会签 / 转交 / 超时升级（ADR-0021 已否决）；
  - 把数据轴合进 profile（ADR 明令正交，理由见 What to build 第二段）。
- **覆盖率提醒**：agent 现在 62.0%，离 60% 下限只剩 2 个点。本票主要改 `claw4j-agent` 的装配，
  **新代码要自带用例**，别指望存量摊薄——上一张票（P08）就把这个数从更高处压下来了。
- 拆票说明：M8 其余七张（P10–P16）尚未拆，见 `.scratch/m8-durable/spec.md` 的 Comments。
- 2026-11-03 收票：496 条用例全过、跳过 0、agent 覆盖率 62.0% 没掉。
- **四个"留给实现"的点，这次是这么定的**：
  1. **分档数据跟着工具走**：新增 `ToolTierRegistry`（core，与 `ToolPermissionRegistry` 同构），
     12 处提供工具的类各加一个 `@Bean`。工具名尽量复用同一份清单——
     脚本包与 MCP 用 `sameFor(provider.permissions().keySet(), …)`，不另抄一份名字。
     **刻意没做 catch-all**（"任何名字都算"）：那会让未声明校验永远通过，
     而这套机制的价值全在那条校验上，所以专门有一条用例盯它
     （`ToolProfileTest#sameForDoesNotActAsCatchAll`）。
  2. **过滤落在 `collectToolCallbacks` 内部，先筛再包**：被挡掉的工具不该连
     `MeteredToolCallback` 都造出来。签名从 5 参变 7 参（`ToolProfile` + `List<ToolTierRegistry>`），
     **两个都必填、没有"不过滤"的重载**——留一个默认值，就等于给"漏传参数"留一条静默全开的暗路，
     而这条路径正是父子两个 Agent 共用的那一条。
  3. **MCP 整批 `FULL`**：外部清单运行时才知道，部署者也没机会逐条审过。
     实际选的是"连了但不暴露"，**没有**把判断前移到 server 装配处。代价如实写在这里：
     一个 `messaging` 部署里，MCP 工具一个都不出现，**包括只读的那些**。
     要让某个 server 进更低的档，是它自己的配置该回答的问题（按 server 声明），本票不做。
  4. **配置键**：`claw4j.tools.profile` / `allow` / `deny`，缺省 `full` + 两张空表。
- **一处判据之外的语义决定：`allow` 只能收窄，不能越过档位。**
  票里没定这条。选"只能收窄"的理由：能放宽的话，`allow` 就成了绕过 `profile` 的口子，
  而 `profile` 正是这个部署的安全默认——一个"只做消息"的部署写一行 `allow: [run_script]`
  就又有了跑代码的能力，而那一行在 Code Review 里看起来只是"列了两个工具"。
  想"messaging 档但要一个脚本"只能调高档位再 `deny` 别的，代价是写得更啰嗦、意图更清楚。
- **如实交代一处宽松**：`allow` / `deny` 里写了清单里不存在的名字**不会失败**，只打一条 WARN。
  原因：部署裁剪掉某个模块时，针对它的 `deny` 条目本来就该落空，失败会误伤正常的裁剪。
  但拼错也长这样，而拼错的那一条永远不生效——所以 WARN 里把名字列出来。
  "未分档就失败"这条硬约束只针对**工具**，不针对配置项，两者的失败代价不一样。
- **一个证据比断言更有意思**：这套校验在本票里第一次跑装配测试时就抓到了一个漏网的工具——
  `ApplicationAssemblyTest.ApprovalProbe` 的探针 `sendNotice`（测试自己挂进容器的 `ToolCallback`）。
  报错点名到它、并指出"去登记 ToolTierRegistry"，于是那条测试补了一个档位 bean。
  这不是设计之外的意外：任何往容器里放工具的地方（包括测试探针、第三方插件）都在同一条规则下。
- 票面第 2 条原文把 `draft_script` 与 `propose_script` 并列成两个工具，实际是
  一个动作名、一个工具名（`SkillGeneratorToolCallback` 的动作是 `draft_script`）。
  验收框里已注明，档位上都归 `CODING`，不影响判据。
- 覆盖率没掉的原因：新代码主要在 `claw4j-core`（三个类 + 14 条用例，core 从 87.3% 升到 88.8%），
  `claw4j-agent` 只加了装配与一个公共方法，它的用例落在 `SubagentDelegationTest`（19 条）
  与 `ApplicationAssemblyTest`（10 条）里——两个都在方法数上限之内。
