# 票 32：长记忆改成内容注入、披露层保住注入的工具、检索结果带签名

**Status:** in_progress（2026-11-27 19:58 开票；20:20 四处代码已落地，验收两半：记忆全绿、"先去找一遍"未达标）
**前置：** 票 29（准入闸）、票 31（重写 suffix，ADR-0041）

## 背景

2026-11-27 那一批验收（`docs/acceptance/2026-11-27/`）跑出四件事，彼此独立但落在同一条链上。

### 一、开长期记忆，第一个动作就 500

```
java.lang.IllegalStateException: No ToolCallback found for tool name: MemoryView
```

机制（三处都对得上，不是推断）：

1. `AutoMemoryToolsAdvisor.before()` 往系统提示词尾部拼了一段 10492 字节的记忆说明书，
   里面有一张表写着「`MemoryView` … Use `MEMORY.md` as the first call in any session」，
   同时把 6 个记忆工具加入 `toolCallbacks`；
2. `DisclosureToolCallingAdvisor.prepare()` 每轮**整表重建**工具清单，只装
   `toolSearchTool` + `pinned` + 历史里检索到过的名字——记忆那 6 个三样都不占，每轮都被抹掉；
3. `DefaultToolCallingManager:289-296` 在清单里按名字找，找不到**抛异常**（不是回一条工具错误），
   异常冒到 HTTP 层变成 500。

即：提示词许了、清单没给、失败不放回模型。位置最坏——提示词要求它**每次会话第一个动作**就调，
所以是开场即崩，用户一个字都拿不到。

### 二、长记忆的读入口不该是"要调工具才能看见"

`MEMORY.md` 是整理过的长期索引，每次会话都要看。现在的实现让它必须靠 `MemoryView` 读一次，
而 OpenClaw 的做法相反（`docs.openclaw.ai/zh-CN/concepts/memory`，已查原文）：

- `MEMORY.md` —— **会话开始时直接注入**；超预算时磁盘留全文、注入副本**截断**；
- `memory/YYYY-MM-DD.md` —— 每日日志，**不注入**，靠 `memory_search` / `memory_get` 检索；
- `memory_get` 在文件不存在时返回 `{ text: "", path }` 而**不抛** `ENOENT`。

省下的那一次工具往返是实打实的：每个会话第一次调用都要先读一次索引，才轮到正事。

### 三、披露层吃掉"别人注入的工具"这件事没有边界

记忆只是第一个撞上的。任何在 `TOOL_LOOP` 之前注入工具的 advisor 都会被同样处理，
而 `prepare()` 分不出"仓库里的备选货"和"上游刚递到手上的接口"——
`initialize()` 拿到的 `options` 里两者已经混在一起了。

### 四、检索结果只有名字，模型得再等一轮才知道要传什么

`ToolSearchTool.toolSearchTool()` 最后一行 `map(tr -> tr.toolName())`，描述被主动丢掉。
OpenClaw 的 `tools.search` 每个命中项带一个**有界的 TypeScript 风格 input 签名**
（`{ id: string; mode?: "drip" | "flood" }`），签名够用时模型可以跳过 `describe`。

注意收益边界：**带签名省不掉那一轮展开**（清单在模型开口前就定了），它省的是"盲搜第二遍"
和"参数写错后的第三次调用"。

## 要改成什么

| # | 改什么 | 落在哪 |
| --- | --- | --- |
| 1 | `MEMORY.md` 内容直接拼进系统提示（超 6000 字符截断并留标记） | `PrincipalMemoryAdvisor` + 新的提示词资源 |
| 2 | 注入的工具登记进上下文，披露层每轮并回清单 | `ContextKeys` 加键；`PrincipalMemoryAdvisor` 写；`DisclosureToolCallingAdvisor` 读 |
| 3 | `toolSearchTool` 返回 `name + 一句话描述 + 参数签名` | 新的工具类替换框架的 `ToolSearchTool` |
| 4 | `namesIn()` 兼容新旧两种形状，并在"响应非空但解出 0 个名字"时告警 | `DisclosureToolCallingAdvisor` |
| 5 | `SYSTEM_SUFFIX` 第三句改成"先去找一遍；确实没有，就直说做不到" | `ToolSearch` |
| 6 | `README-capability-tests.md` 补长短记忆用例 | 文档 |
| 7 | 恢复 `application-local.yml` 的 `memory` 三行 | 配置 |

### 关于 1：为什么用"现有框架能力"

不改库的读写工具，只用它给的扩展点：

- `AutoMemoryToolsAdvisor.Builder.memorySystemPrompt(Resource)` 换成我们自己的提示词
  （去掉"先用 `MemoryView`"，改成"记忆内容在下面，写用这几个工具"）；
- `PrincipalMemoryAdvisor.before()` 转发之后，把 `MEMORY.md` 的内容追加到系统提示尾部。

这样"读"不再依赖工具，"写"仍走库的 6 个工具，一行库代码都不用重写。

### 关于 2：为什么要登记，而不是"检查上游给了什么"

`initialize()` 时 `options` 里已经是「全部候选 + 注入的 6 个」混在一起，**分不出谁注入的**。
所以只能由注入方自己报名：`PrincipalMemoryAdvisor` 对比转发前后的工具名集合，差集就是它注入的，
写进 `ContextKeys.INJECTED_TOOLS`。不硬编码工具名，库升级换名也不会静默失效。

## 验收

| # | 判据 | 怎么量 |
| --- | --- | --- |
| 1 | 开记忆后第一句话不再 500，且回答正确 | A1 复跑，`POST /api/chat` 返回 200 |
| 2 | 系统提示里出现 `MEMORY.md` 的内容 | 日志/单测断言注入块存在 |
| 3 | 每轮可见工具里都有 6 个 `Memory*` | 单测断言 `prepare()` 后清单包含登记项 |
| 4 | 检索结果含描述与签名，模型不必再猜参数 | 单测断言返回结构；实测看参数错误重试次数 |
| 5 | suffix 五句仍在，"不要拿相近的工具硬做"仍在 | `ModelAndToolSelectionTest` |
| 6 | BFCL `irrelevance` 不低于 84.17%（票 31 的最低一轮） | `./eval/tools/bfcl/run.sh --with-prompt` |
| 7 | B7 / B8 三条由红转绿 | `README-capability-tests.md` B 节 |

第 6 条只查"没变坏"：`irrelevance` 奖励"别多调工具"，而第五句在鼓励"再找一次"，
方向相反。真掉分就在这里暴露，而不是等产品里发现。

## TDD 用例清单

| 判什么行为 | 用例 | 先红的样子 |
| --- | --- | --- |
| 注入的工具不会被披露层吃掉 | `DisclosureKeepsInjectedToolsTest` | 今天必红：`prepare()` 之后清单里没有 `MemoryView` |
| 登记是差集，不是硬编码 | 同上（换一个假名字注入，仍被保住） | 硬编码实现会红 |
| `MEMORY.md` 内容进了系统提示 | `PrincipalMemoryInjectionTest` | 现在提示里只有库的说明书，没有内容 |
| 检索结果带描述和签名 | `ToolSearchProjectionTest` | 现在返回 `["name", ...]` |
| `namesIn` 兼容对象形状且解得出名字 | 同上（把新形状喂给 `prepare()` 的历史） | 现在解析出 0 个名字且不告警 |
| 响应非空但解不出名字要告警 | 同上 | 现在静默 |
| suffix 第三句含"先去找一遍"、仍含"不要拿相近的工具硬做" | `ModelAndToolSelectionTest` | 改之前不含前半句 |

## 缺口（先记下，不在本票做）

- **提前检索**（进循环前用用户 query 先搜一次，把 top5 直接放进清单）能省掉一整轮，
  但抢走了模型"什么时候搜、搜什么"的决定权，本票不做。
- **代理调用**（OpenClaw 的 `tool_call(id, args)`，一次就能调通）改动跨审批、度量、评测口径，
  单独开票。
- **检索质量本身**：`hybrid-0.5` R@5 = 0.841，纯向量 0.852 更高，融合挤掉冠军 10 题。
  这是比"带签名"更大的杠杆，单独一张票。

## 复核（2026-11-27 20:20）

### 落了什么

| # | 文件 | 改了什么 |
| --- | --- | --- |
| 1 | `PrincipalMemoryAdvisor` + `resources/prompt/MEMORY_SYSTEM_PROMPT.md` | 换掉库那份"先用 MemoryView"的说明书；转发之后把 `MEMORY.md` 内容追加到系统提示尾部（超 6000 字符截断并注明全文在哪） |
| 2 | `ContextKeys.INJECTED_TOOLS` + `PrincipalMemoryAdvisor` + `DisclosureToolCallingAdvisor#addInjected` | 注入方对比转发前后的工具名差集并登记；披露层每轮并回清单，不参与筛选、不占检索名额 |
| 3 | `DescriptiveToolSearchTool`（新增，替掉框架的 `ToolSearchTool`） | 命中项回 `name + 一句话描述 + 参数签名`；描述取第一行并截到 120 字符，签名从 `inputSchema` 压成 `(path: string, range?: string)` |
| 4 | `DisclosureToolCallingAdvisor#namesIn` | 兼容 `["Read"]` 与 `[{"name":"Read",…}]` 两种形状；"响应非空却解出 0 个名字"时告警 |
| 5 | `ToolSearch.SYSTEM_SUFFIX` 第三句 | 改成"先去找一遍；确实没有，就直说做不到，不要拿相近的工具硬做" |
| 6 | `README-capability-tests.md` | 新增 K 节（K1–K6：短期 ×2、长期 ×2、回归、截断），速查表补 4 行 |
| 7 | `application-local.yml` | `memory` 三行恢复 |

顺带修掉两个真坑：

- **两版 Jackson**：`JsonHelper` 走的是 Jackson 3（`tools.jackson`），`com.fasterxml` 的 `JsonNode`
  在它那边反序列化直接报 "Cannot construct instance of JsonNode"。所以解析一律用
  `Map` / `List<Object>` 这种具体形状，不碰树类型。
- **记忆提示词的占位符不能自己写**：库的变量名是 `MEMORIES_ROOT_DIERC TORY`（中间有个空格），
  ST 模板解析不了，日志里每轮一条 `'TORY' came as a complete surprise to me`。我们的提示词
  干脆不提根目录——工具描述里已经说了路径是相对的。

### 单测

`./mvnw -o -pl claw4j-agent -am test`：agent 全绿（新增 `InjectedToolsAndSearchTest` 4 条、
`MemoryIndexInjectionTest` 4 条，`ModelAndToolSelectionTest` 26 条不动）；
`./mvnw -o -pl claw4j-app -am -Dtest=ApplicationAssemblyTest test`：25/25。

### 实测（`claw4j_accept_1132`，20:09–20:15，记忆开着）

| 验什么 | 结果 |
| --- | --- |
| A1「上周华东区的成交额是多少」 | ✅ 128,400.00 元，口径对 |
| `No ToolCallback found` | ✅ **0 条**（改之前第一句就撞） |
| 6 个记忆工具登记 | ✅ 日志：`这一轮注入的工具 [MemoryCreate, MemoryView, MemoryInsert, MemoryDelete, MemoryRename, MemoryStrReplace]` |
| 模型看不看得见（换用户直接问） | ✅ 逐字回出那 6 个名字 |
| K3 写记忆 | ✅ `MEMORY.md` 一行索引 + `user_scope_east_china_weekly.md` 落盘 |
| K4 换会话用上偏好 | ✅ 换 chat id 后按"华东区、周一到周日"作答，且**没调** `MemoryView` |
| 检索结果的新形状 | ✅ 检索命中后模型真调到了 `querySales`（说明 `namesIn` 解析对象形状没问题） |

### 没达标的

| 现象 | 判断 |
| --- | --- |
| K4 那句"上周的成交额是多少"，模型答"我这边也没有能查成交额的工具"，**一次检索都没发** | 第四句改了之后**仍然会发生**。这次它先读了知识库、发现没有数据就收手 |
| B7「给两个选项」不再拒绝，但**没调** `AskUserQuestionTool`、也没检索 | 从"拒绝"变成"正文里给选项"，符合 B8 的老毛病 |

所以第 5 项（B7/B8 转绿）**没达成**。原票验收第 7 条不通过。

### 没跑的

- **BFCL 五档没重跑**（要真钱真时间，一轮 640 题约 4 分钟 ×5）。`SYSTEM_SUFFIX` 第三句
  往"鼓励多搜一次"的方向走，`irrelevance` 是反向的，**必须补跑才能定稿**。这是本票最大的未验项。
- C/D/E/F/G/H/I/J 各节照旧没动（要换实例参数）。

### 缺口与下一步

- **"先去找一遍"只写进提示词不够。** 两次现场都发生在"知识库先给了一堆不相关文档"之后——
  模型看完文档就认定"没有"。要么把这句话落到**决策点**上（检索工具的 description 里写
  "答之前先确认有没有能查这个数据的工具"），要么走 ADR-0042 说的那条路：先反问用户。
- **`simple_python` 还没量**：带签名会不会让模型在简单题上多解释一句，未知。

## 复跑（2026-11-27 20:34，文档重写后，`claw4j_accept_r2`）

标准实例（local profile + 三目录 + cache 关），只跑不需要换实例的那一批：

| 用例 | 结果 |
| --- | --- |
| A1 上周华东成交额 | ✅ 128,400.00 |
| B2-1 列 examples 下的 .md | ✅ 文件名对 |
| B2-2 引 examples/README.md 开头六行 | ✅ 逐字一致 |
| B2-3 examples 里的 refund | ✅ 给了带行号的几处 |
| B2-4 看看 example.com 这页 | ❌ "我无法打开网页，也没有抓取网页内容的工具"，**一次检索都没发** |
| K1 同一段对话接上一轮 | ❌ 第一句就答"资料里没有…查不到"，没查业务库 |
| K2 换会话问"刚才那个数" | ✅ 反问/不编数 |
| K3 说"记住：我只看华东区" | ❌ 回"记下了"，磁盘上只有 `.dream-state.json`，没有 `MEMORY.md` |
| K4 换会话用上偏好 | ❌ 没有偏好可用，且仍然没检索 |
| K5 `No ToolCallback found` | ✅ 0 条 |
| B7 给两个选项 | ⚠️ 正文给了 A/B 两个选项，但没调 `AskUserQuestionTool`、没检索 |
| B8 三件事 + 待办清单 | ❌ "我做不到。我手上没有…查询工具" |

结论没变，而且更清楚了：**"先去找一遍"那句不足以让模型去检索。**

三次失败现场有一个共同前置：**RAG 先注入了一批不相关的知识库文档**（回答里带 `[3]` 那种引用），
模型读完就认定"资料里没有 / 我没有这个工具"，然后收手。提示词写在 system 里，压不住
"我已经看到了证据"这种判断。

K3 失败同一类：模型把"记下了"当成完成，没有真的落盘。上一轮（`claw4j_accept_1132`）里
显式要求"先建文件、再在 MEMORY.md 加一行"时它是会写的 —— 说明工具和路径都没问题，
**缺的是"什么时候必须写"的判定**。

下一步（建议单开一票）：

1. 把"先用工具确认有没有能查这个的能力"从 system 挪到**决策点**上：`toolSearchTool` 的
   description 里写死"回答业务数字之前先搜一次"；
2. K3 这类"说记住了但没写"要在**回答后校验**：本轮声明写记忆却没有 `Memory*` 调用时补一次
   提示，或直接由服务端落盘；
3. RAG 与工具检索的先后顺序值得单独看：现在知识库会把"没有数据"变成模型的既有结论。

## 撤回的三条建议（2026-11-27 21:20，用户裁决）

上面「下一步（建议单开一票）」那三条，用户否了，理由成立，**作废**：

1. **把判断点写进 `toolSearchTool` 的 description** —— 那个描述只该讲"我是干什么的"。
   把"回答前先搜一次"塞进去，是让检索工具自己教模型什么时候用它，既重复又短视；
   票 31 刚论证过"取用是工具描述的职责、策略是 system 的职责"。**不做。**
2. **回答后校验有没有真落盘** —— 用户那句"我只看华东区"，模型判断"本轮够用、不必跨会话"
   是**合理判断**，不是缺陷。强制每句"记住"都写盘，等于把长期记忆降级成记事本。**不做。**
3. **改 RAG 与工具检索的先后** —— 诊断本身就错了：问"血常规结果"却返回《检验科工作规范》，
   那是**检索没答对**，不是顺序错。拿一个检索失败当流程缺陷的证据，站不住。**不做。**

改的是判据，不是代码：K3 现在允许"只在本轮有效"这个诚实答案，只有"说以后都记得、却没落盘"
才算失败；K4 的题目也从"上周成交额"改成问"上次那个显示习惯你还记得吗"，避免用业务问题混淆两件事。

### 剩下的分歧点记在这里，不改

B2-4（抓网页）与 B8（三件事 + 待办）在这个模型上仍然是红的：模型看完知识库文档就认定
"我没有这个工具"，不发检索。**这是模型行为，不是流程缺陷**，系统流程与行业做法一致。
在拿到"换一个模型同样题目会不会也这样"的对照数据之前，不为它改提示词、也不改链路顺序。

## 复核二（2026-11-27 21:20–21:35，改题面后重跑，`claw4j_accept_r2` / 日志 `/tmp/claw4j-r3.log`）

按"先看题目本身对不对"的路线，先审题再跑。**三处题面确实是坏的，改完即绿**：

| 用例 | 原来的毛病 | 改成 | 结果 |
| --- | --- | --- | --- |
| B2-4 | 题面要"原样抄、不要总结"，判据却要"返回摘要"——`WebFetch` 是抓回来**让模型按 prompt 提炼**的，这句话让它做做不到的事，永远红 | "这个网页在讲什么？三句话以内" | 仍红（模型不检索），但题面不再自相矛盾 |
| B8 | ①三步里两步就能做完，列计划没理由；②题面写"下一轮我会问你第三步做到哪了"，用例里根本没有下一轮，等于宣称验了却没验；③判据写"至少 2 次"然后又自我降级成"观察项" | 单文件 + 一句对比；判据只留"`TodoWrite` ≥2"，不再自我降级 | 结果半绿（Read 1、回答对），`TodoWrite` 仍 0 |
| K1 / K3 | K1 用"上周成交额"问短记忆——第一句自己就可能没数，第二句答不上来时分不清是记忆坏了还是工具没调到；K3 用"我只看华东区"，那更像一次性口径，模型不落盘是合理判断 | K1 换成不查任何工具的题（出差去哪、几天）；K3 换成"以后金额一律用人民币" | **K1 ✅ 杭州三天 / K2 ✅ 换会话就不知道 / K3 ✅ `MEMORY.md` + `user_currency_preference.md` 真落盘 / K4 ✅ 换 chat id 直接答出偏好且 0 次工具调用** |

K5（`No ToolCallback found`）全程 0 条，记忆那条主链路是稳的。

### 两个新发现，都带日志证据，且都不是题目的错

**① 数检索不能看 `claw4j.tool.calls` / `audit_event`。** `toolSearchTool` 是披露层自己塞进清单的，绕过了计数与审计的包装：
审计里从头到尾只有 `Read / Glob / Grep / querySales` 四行。真正的数在 `claw4j.tool.search.calls`（本轮 COUNT=4，与日志
4 条 `工具检索 hybrid` 对得上）。**上一轮"一次检索都没发"的结论是靠审计表得出的，方法本身有问题**，这一轮已改用日志与
该指标重判（结论恰好一致，但不该靠运气）。

**② 准入闸拿"模型改写的检索词"去比触发词，语义搜到了也照样拿掉。** 探针（用户 `pr1`：明确要求"先找找看有没有能给我出
选择题的工具"）两次检索的原始日志：

```
工具准入闸：query=出选择题 让用户点选 选项 交互式提问      拿掉=[AskUserQuestionTool] 最终=[Task, intent, book_search, querySales]
工具准入闸：query=ask user question multiple choice options select 拿掉=[AskUserQuestionTool, TodoWrite, Bash] 最终=[Skill, MemoryView, Task, demo_echo, querySales, color_scheme, Glob]
```

第二条 query 是模型自己写对了的（`ask user question multiple choice options select`），关键字那一路也真的召回了
`AskUserQuestionTool`，**闸凭"检索词里没有 `让我选 / 哪 个好` 这几个中文触发词"把它拿掉了**，模型于是如实回答
"我找了两轮，手上没有这类工具"。B7 同一个机制：用户说"让我选"，模型搜的是 `deployment environment release pipeline`，
`AskUserQuestionTool` 压根进不了候选，它转去用 `Bash` 翻文件，六次预算烧完，最后把
`Total tool call limit (6) exceeded` **原文当成回答**返回。

`GatedToolIndex.search()` 第 3 行就是 `String query = toolSearchRequest.query();` —— 闸的输入是模型的改写，不是用户原话。
用户原话在 `prepare()` 里就有（`request.prompt().getInstructions()`），取得到。

### 建议（未动代码，等裁决）

1. **闸的判据改取用户原话**（而不是模型改写的检索词）。理由：闸的语义是"用户没提这类事就别给这个工具"，那是用户的话，
   不是模型的话；改成用户原话后，`ask user question…` 这种正确检索不会再被自己的改写惩罚。
2. **撞预算不要把异常字符串当回答**：`Tool call limit (N) exceeded` 现在是 `text` 字段原文返回（B7、td1、td1b 三处现场），
   用户看到的是一句英文报错。应当换成"这轮做不完，做到哪一步了"的收尾。
3. B8 的 `TodoWrite ≥2`：在 ①修好之前它验的是闸不是工具；①修好后要重判一次。**现在不删这条判据，也不改代码绕它。**

> 后续：本票 `withIndex` 用 `augmentSystemMessage(String)` 拼索引，而它是**整段替换**——
> 开了记忆的每一轮人设都被覆盖。已在票 33 修掉（见那一票的复核表）。
