# 票 06：工具面策略——常驻、阈值、namespace

**Status:** open

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
      mode: auto            # off | always | auto；always = 票 01 起的行为（全藏、按需检索）
      reveal-below: 10      # auto 且工具数（不含检索工具自己）≤ 10 → 全塞，不检索
      pinned: []            # 常驻工具名；空 = 不常驻。代码里不写死任何一个
      namespaces: {}        # namespace → 工具名；空 = 按名字前缀推导
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

- 显式配置优先：`namespaces: {file: [Read, Glob], web: [WebFetch]}`。
- 没配到的工具按**名字前缀**推导：第一个 `_` 之前（`github_issue_list` → `github`）；没有下划线
  则等于工具名本身（`Read` → `Read`）。
- **不预置任何工具名映射**。预置等于把某一批工具写死进产品，正是本票要消掉的东西。
- 类别清单要拼进检索提示词（"可收窄到这些类别：file / web / exec …"）：`categoryFilter` 的
  描述是英文一句带过，模型不会自己想到用它。

## 改哪

1. `claw4j-agent/toolaccess/ToolSearchProperties`：加 `mode` / `revealBelow` / `pinned` / `namespaces`；
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
| pinned 的工具每轮都在清单里 | `ModelAndToolSelectionTest#pinnedToolsAreAlwaysVisible` | 不实现常驻时清单只有检索工具，断言含 pinned 的那个红 |
| 工具数 ≤ 阈值时全塞 | `ModelAndToolSelectionTest#revealsEverythingBelowTheThreshold` | 恒检索时清单只有检索工具，断言"工具全在"红 |
| 工具数 > 阈值时只见检索工具 | `ModelAndToolSelectionTest#hidesLongTailAboveTheThreshold` | 阈值判反时红 |
| `mode=off` 永不检索 | `ModelAndToolSelectionTest#offModeNeverHidesTools` | 仍拼了"只看得见检索工具"的提示时红 |
| namespace 默认按前缀推导 | `ModelAndToolSelectionTest#namespaceDefaultsToNamePrefix` | `github_issue_list` 归不到 `github` 时红 |
| `categoryFilter` 能收窄 | `ModelAndToolSelectionTest#namespaceFilterNarrowsTheSearch` | 现状（传了等于没传）会返回别的 namespace，断言只回 file 类红 |
| 配置里的 namespace 覆盖前缀推导 | `ModelAndToolSelectionTest#explicitNamespaceWins` | 只实现推导、忽略配置时红 |
| pinned 写了不存在的名字只告警 | `ModelAndToolSelectionTest#unknownPinnedNameIsIgnored` | 抛异常时红（同一份配置多部署共用，写错不该炸） |
| 装配：默认配置下看得见全部工具 | `ApplicationAssemblyTest#theDefaultProfileExposesEveryTool`（替换现有"只看得见检索工具"那条） | 装配没换时红 |

## 缺口（本票不做）

- **混合索引**（regex ∪ 向量）→ 票 07。它要独立向量表，是 schema 变更。
- **rerank**：候选只有 5–20 个，融合排序足够；留 `off` 的位置，不实现。
- **token 代价未量化**：全塞比检索多多少，要评测集量（票 07 之后一起做 `eval/tools`）。
- **词面匹配的天花板还在**：namespace 只解决"能收窄"，不解决"中文说法命中不了英文描述"，
  那是票 07 的事。
