# 票 06：工具面策略——常驻、阈值、namespace

**Status:** resolved

## 由来

票 05 记了症状（人话问文件/网页命中不了，`examples 目录下有哪些 md` 5/5 不检索），用户 2026-11-26
给了三条通用要求，并追加 namespace：

1. 常用工具常驻；
2. `≤10 全塞 / >10 检索`；
3. 关键字 + 向量的混合检索（→ 票 07）；
4. namespace：框架的 `categoryFilter` 有预留，做完要能配置。

**共同要求是"通用"**：不许针对 `Read/Glob/Grep/Write/Edit/Bash/WebFetch/WebSearch` 写特例，
否则换一批工具就失效。所以三条都落成**配置 + 算法**，代码里不出现任何具体工具名。

本票做 1、2、4 的**露出侧**（配置、清单算法、namespace 定义与收窄）。索引怎么打分归票 07。

## 目标形状（一次说清，分两张票落地）

```yaml
claw4j:
  advisor:
    tool-search:
      mode: auto            # off | always | auto；默认 auto（见下"默认值怎么定的"）
      reveal-below: 10      # auto 且工具数（不含检索工具自己）≤ 10 → 全塞，不检索
      pinned: []            # 常驻工具名；空 = 不常驻。代码里不写死任何一个
      categories: {}        # namespace → 工具名；空 = 按名字前缀推导
      max-results: 5        # 已有（票 04 之前就有）
      index: regex          # 本票保持 regex | lucene
      hybrid:               # 票 07
        keyword-weight: 0.5
```

## 裁决

`docs/adr/0021-explicitly-not-doing.md` 修订（表里「**领先 2**：工具可见性」那一行）：披露边界从
"全部工具都藏、行为不随工具数量变化"改为**可配置三档**，并明确「露出多少」与「检索怎么打分」
是两件事——前者是策略，后者是索引实现。

**这是对 ADR-0021 一条取舍的有意反转**：那一条写的是"行为不随工具数量变化"，理由是"不想让
部署规模决定语义"。代价当时估的是"工具少也得多花一轮检索"；实测下来这一轮不是花在边缘场景，
而是花在最常见的一类问法上（问文件、问网页），所以改成按规模滑档。代价换成"两种模式两条路"，
用同一个 advisor 内部分支消化，不额外开一条链。

无需新 ADR：namespace 的形状照 OpenAI 的 namespace、落点用框架已预留的 `categoryFilter`，
是同一件事的参数化。

## 框架的三个事实（决定了必须自研 advisor）

- `ToolSearchToolCallingAdvisor` 的 Builder **没有常驻字段**；`prepareIteration` 把清单硬替换成
  「检索工具 + 从 `MessageType.TOOL` 消息里认出的工具」，`extractToolNameReferences` 只扫 tool 响应，
  从系统提示注入名字也无效。
- `ToolCallingAdvisor` 的 `doInitializeLoop` / `doBeforeCall` / `doBeforeStream` 是 `protected`，
  可以继承——自研落点，不必重写整个工具循环。
- `ToolSearchRequest.categoryFilter` 是**单个 nullable String**，`ToolSearchTool` 已把它作为
  `@ToolParam` 交给模型（英文描述 "Optional filter to narrow search to a specific tool category"），
  但**没有任何 `ToolIndex` 实现读它** → 现在模型传了等于没传。这是 namespace 的落地位置。

## 默认值怎么定的（2026-11-26 对齐）

- **`mode` 默认 `auto` 而不是 `always`**：本仓当前 20–30 个工具，`auto` 判下来仍是检索，行为与今天
  完全一致；而工具少的部署（只挂三五个业务工具）自动变成全塞，不用改配置。`always` 留给"我要它
  一直省 token"的显式选择，`off` 留给"工具就几个、检索纯属浪费"。
- **`reveal-below` 默认 10**：Anthropic 的口径是少于约 10 个工具时全量比检索更快。
- **配置键用 `categories`**（不是 `namespaces`）：框架侧暴露给模型的参数叫 `categoryFilter`，
  两边同一个词，不需要在文档里做一次翻译。语义就是 OpenAI 说的 namespace。

## 清单怎么算（每轮）

```
总额 = 工具总数 - 1（检索工具自己）
mode=off                       → 全塞，不建索引、不拼提示
mode=auto 且 总额 ≤ reveal-below → 全塞
否则                            → {检索工具} ∪ pinned ∪ 已检索到的
```

"全塞"与"检索"走同一个 advisor：`doInitializeLoop` 在 context 里打标记，`doBeforeCall` 按标记决定
是否替换清单。不分成两个 advisor——分成两个就会出现"另一条路平时不执行"。

`pinned` 里的名字按 `ToolCallback#getToolDefinition().name()` 精确匹配；对不上的忽略并 WARN
（同一份配置可能在多个部署间共用，写错一个名字不该让启动失败）。

## namespace 怎么算

- 显式配置优先：`categories: {file: [Read, Glob], web: [WebFetch]}`。
- 没配到的工具按**名字前缀**推导：第一个 `_` 之前（`github_issue_list` → `github`）；没有下划线
  则等于工具名本身（`Read` → `Read`）。
- **不预置任何工具名映射**。预置等于把某一批工具写死进产品，正是本票要消掉的东西。
- 类别清单要拼进检索提示词（"可收窄到这些类别：file / web / exec …"）：`categoryFilter` 的
  描述是英文一句带过，模型不会自己想到用它。

## 改哪

1. `claw4j-agent/toolaccess/ToolSearchProperties`：加 `mode` / `revealBelow` / `pinned` / `categories`；
   缺省值收口在此（`mode` 默认 `always` 保持旧行为，`revealBelow` 默认 10）。
2. 新增 `claw4j-agent/toolaccess/NamespacedToolIndex implements ToolIndex`：包装任意 `ToolIndex`，
   维护 `sessionId → (toolName → namespace)`；`search` 收到 `categoryFilter` 时先放大内层
   `maxResults` 再按类别过滤（放大是因为过滤会打折，不放大就凑不满一页）。
3. 新增 `claw4j-agent/toolaccess/DisclosureToolCallingAdvisor`：继承 `ToolCallingAdvisor`，实现清单
   算法；检索工具用框架的 `ToolSearchTool`（`MethodToolCallbackProvider` 包一层，照
   `ToolSearchToolCallingAdvisor` 构造函数的写法）。
4. `ToolSearch`：suffix 按模式生成（全塞时不拼"只看得见检索工具"那段），类别清单动态拼。
5. `AgentAutoConfiguration`：装配换成新 advisor；`toolIndex` 外面包一层 `NamespacedToolIndex`。
6. `application.yml`：加四个配置项与注释；`application-local.yml` 用 `mode=auto`。

## TDD 用例清单

用例全部并入 `ModelAndToolSelectionTest`（现 12 条）——`claw4j-agent` 已到 14 个测试文件的配额，
不开新文件；方法数 12 → 19，仍在 26 以内。

| 判什么行为 | 用例 | 先红的样子 |
| --- | --- | --- |
| pinned 的工具每轮都在清单里 | ✅ `ModelAndToolSelectionTest#pinnedToolsAreAlwaysVisible` | 不实现常驻时清单只有检索工具，断言含 pinned 的那个红 |
| 工具数 ≤ 阈值时全塞 | ✅ `ModelAndToolSelectionTest#revealsEverythingBelowTheThreshold` | 恒检索时清单只有检索工具，断言"工具全在"红 |
| 工具数 > 阈值时只见检索工具 | ✅ `ModelAndToolSelectionTest#hidesLongTailAboveTheThreshold` | 阈值判反时红 |
| `mode=off` 永不检索 | ✅ `ModelAndToolSelectionTest#offModeNeverHidesTools` | 仍拼了"只看得见检索工具"的提示时红 |
| 类别默认按前缀推导，配置优先 | ✅ `ModelAndToolSelectionTest#categoryDefaultsToNamePrefix` | `github_issue_list` 归不到 `github`、或配置被忽略时红 |
| `categoryFilter` 能收窄 | ✅ `ModelAndToolSelectionTest#categoryFilterNarrowsTheSearch` | 现状（传了等于没传）会返回别的类别，断言只回 file 类红 |
| pinned 写了不存在的名字只告警 | ✅ `ModelAndToolSelectionTest#unknownPinnedNameIsIgnored` | 抛异常时红（同一份配置多部署共用，写错不该炸） |
| 装配：默认 auto、两个集合不预置、索引外面有分组层 | ✅ `ApplicationAssemblyTest#toolSurfaceDefaultsToAutoAndWrapsGrouping` | 装配没换、或 yml 里预置了工具名时红 |

"配置覆盖前缀推导"合进了 `categoryDefaultsToNamePrefix`（同一个方法里两段：推导、覆盖），不单开一行——
单独一行只是同一个入口的第二次输入，与 `retryableClassificationTable` 拿一张表跑多行同理。

**改了三处既有用例**（都是"原断言把旧的恒定行为写死了"）：

- `singleToolIsStillSearchedFirst` → `singleToolIsSearchedFirstWhenModeSaysAlways`：单工具在 `auto` 下就该
  全塞，那条的题目改成"`always` 档不随工具数量改变"，保留"检索回来的工具进清单"这半段（只有它在验）。
- `streamPathAlsoUsesProgressiveDisclosure`：显式传 `always`，否则单工具被全塞，流式那条钩子就没被走到。
- `SubagentDelegationTest#runner`：显式 `always`。脚本写的是"检索 → 调用 → 结论"三步，子链只有一两个
  工具时 `auto` 会全塞、少一步脚本对不上；子链怎么滑档不是那两条的题目。

收票记录：复核：2026-11-26 `./scripts/verify.sh`（失败 0 / 错误 0 / 跳过 0；行覆盖 agent 63.5%、
app 88.4%、core 82.3%，其余 ≥ 78%；文件数与方法数配额未动，agent 14 / 26）。

## 真机验证（2026-11-26，同一句人话：「examples 目录下有哪些 .md 文件？」）

| 配置 | 结果 | 证据 |
| --- | --- | --- |
| 默认（`mode=auto`，20+ 个工具） | 仍答"我这边没有能列目录或读本地文件的工具" | 这正是票 05 的症状，说明**默认档在当前规模下没改变行为**（本票的取舍：先给开关，不偷偷换默认） |
| `mode=off` | 直接列出 130 个 `.md`，按目录分组 | `claw4j.tool.calls?tool=Glob` = 1 |
| `pinned=Glob,Read,Grep,WebFetch` | 目录问题直接通（Glob = 1，不经检索）；业务问题仍走检索 | 问成交额那一轮 `claw4j.model.calls` 增量 = 3（第一次检索、第二次调用、第三次作答）；`querySales` 被调用一次 |

**判"有没有检索"要看 `claw4j.model.calls` 的增量（2 还是 3），不要看 `claw4j.tool.calls?tool=toolSearchTool`**：
检索工具是 advisor 内部注入的，不经过 `collectToolCallbacks` 那层包装，所以它没有计数——那个 0 是"没被计量"，
不是"没调用"。这一条对验收文档同样成立。

## 缺口（本票不做）

- **混合索引**（regex ∪ 向量）→ 票 07。它要独立向量表，是 schema 变更。
- **rerank**：候选只有 5–20 个，融合排序足够；留 `off` 的位置，不实现。
- **token 代价未量化**：全塞比检索多多少，要评测集量（票 07 之后一起做 `eval/tools`）。
- **词面匹配的天花板还在**：类别只解决"能收窄"，不解决"中文说法命中不了英文描述"，
  那是票 07 的事。
- **验收文档没跟着改**：`README-capability-tests.md` 里 B2/C5 还是"先检索、再点名工具"的写法，
  现在默认档在工具少时会全塞。要等票 07 落地后一起按最终形态重写，避免中间态改两遍。
