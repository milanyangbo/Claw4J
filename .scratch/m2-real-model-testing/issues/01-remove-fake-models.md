# 01: 删掉两个假模型类，需要模型的测试全部连真实 DeepSeek

**What to build:** 今天 37 条需要走模型的测试全靠两个假模型驱动——`MockChatModel`（main 里的
生产兜底）与 `ScriptedChatModel`（test 里的编排器）。假模型拦不住只在真链路上才出现的缺陷
（M1-07 一次抓到两个：跨厂商降级丢工具、内部重试把降级拖成分钟级），
而且把断言的落点从"模型做对了什么"挪到了"脚本念了什么"：`SalesQueryEndToEndTest`
断言的那个 `128400.00` 是脚本常量，不是模型查出来的。这张票删掉两个假模型类，
需要模型的测试连真实 DeepSeek；依赖"精确编排模型行为"的那几条降级为不经过模型的组件级测试。
决策与代价见 **ADR-0011**。

**Blocked by:** None (can start immediately)

**Status:** closed

**Demo:** 拔掉网线和 API key 之外，随便跑一次 `./mvnw verify`——
销售额问答、工具调用、会话记忆这三类测试打的都是真实 DeepSeek（启动日志写明用的是哪家），
断言落在"拿到库里那个数 / 没权限时拿不到数"上；把 `DEEPSEEK_API_KEY` 从环境里去掉再跑，
这些测试**红**并写明"需要 DEEPSEEK_API_KEY"，而不是安静地跳过。

- [x] 删除 `MockChatModel`（agent/main）与 `ScriptedChatModel`（agent/test）及其自身测试；
      连带删掉 `claw4j.model.mock` 三态与 `-Preal-model` profile
- [x] 新增真模型测试支撑：真实 `ModelConfiguration` 装配 + 温度 `0.0` + 有限重试（最多 2 次，打日志）
- [x] 端到端那批（销售额问答、工具调用、会话记忆、装配可见清单）改真模型 + 真库，断言方向性
- [x] 组件级那批（调用预算、鉴权计数、脱敏）改为不经过模型：真实 `ToolCallingManager` /
      真实 Advisor / 计数执行器，工具调用指令由测试构造
- [x] `RealModelIT` 并入常规测试（去掉 `IT` 后缀与 profile 隔离），断言风格沿用其方向性写法
- [x] CI 接 `secrets.DEEPSEEK_API_KEY`；没有 key 时测试 **failure** 而不是 skip
- [x] `scripts/assert-no-skipped-tests.sh` 仍报 `skipped=0`，总数基线同步更新
- [x] README / GLOSSARY 里"全部 mock 模型调用"的说法同步改掉

**注意:**

- **无 key 必须红，不能跳过也不能整类排除。** 跳过与被排除在外的测试会被跳过闸放过，
  那正是 M1 那个 P0 的形状（26 条从未执行却被当成绿）。要让"没 key"表现为
  **failure + 一句写明原因的报错**。
- **组件级那批仍然是真组件**：真 `ToolCallingManager`（不要自己数次数）、
  真 `AuthorizationAdvisor`（不要复制一份过滤逻辑）。丢掉的只是"模型自己发起调用"这一层。
- **有限重试会掩盖问题**：只在"这一轮没调工具"时重试，且必须打日志；
  重试 2 次仍不调工具就红，不要无限退让。
- 真库 + 真模型同时跑会变慢。会话记忆那 8 条共用一个容器，不要每条起一个。

**不要做:**

- 不要引入录制回放（cassette）——那是 ADR-0011 记的回退方案，不是这次的范围。
- 不要为了让断言好写就把温度往上调或放宽成"只要有回答就算过"。
- 不要把通义也接进默认档位（多烧一份 token），`CLAW4J_MODEL_PRIMARY` 能切就行。
- 不要顺手改 M2 能力层的票面或代码。

## 变异检验（去掉实现就要红）

| 变异 | 期望 |
| --- | --- |
| 把某条端到端断言换成永真（例如只断言"回答非空"） | 对应测试失去判据，需换成能证伪的对照断言 |
| 组件级预算测试里去掉 `maxTotalToolCalls` | 第 7 次调用不再被拦，红 |
| 鉴权 Advisor 不再过滤无权工具 | 计数断言红（工具真的跑了） |
| 脱敏 Advisor 的 order 挪到工具循环内 | 存进会话的是原文，红 |

## Answer

**179 个测试全绿、skipped 0**（`set -a; . ./.env; set +a; ./mvnw clean verify -Dclaw4j.dbTests=required`，
再跑 `./scripts/assert-no-skipped-tests.sh`）。比原来的 183 少 8 条、比第一版收尾时的 175 多 4 条：
删掉的是假模型自身的测试与几条在真模型下不再成立、又无法用别的方式表达的断言；
多出来的 4 条是 `RealModelIT` 并进常规回归带来的。

### 一、删了什么

| 类 | 去向 |
| --- | --- |
| `MockChatModel`（main，生产兜底） | 删除。连带 `MockModelConfiguration`、`claw4j.model.mock` 三态、`-Preal-model` profile |
| `ScriptedChatModel`（test，编排） | 删除，连带它自己的测试 |
| `AuthorizationOverHttpTest.TwoRoundModel` | 删除（最后一个藏在 app 里的回声模型） |

没 key 现在是**启动失败**（`RealChatModelCondition` 一家都没有时让装配方法 fail fast），
不再是"降级到回声模型"。顺带把 `RoutingModelConfiguration` 上 `before = MockModelConfiguration`
那个排序约束一起拆了。

`RealModelIT` → `RealModelTest`：去掉 IT 后缀与 `claw4j-app/pom.xml` 里的 `real-model` profile，
4 条用例进常规回归。**ADR-0011 明确否决过"真模型只跑 IT、profile 隔离"**——回归基线本身就是
真模型之后，留着那个隔离等于留一条"日常跑不到、想起来才跑"的裂缝。
它多覆盖的是**完整装配**这一层（app 的 yml、真 Flyway 迁移、fat-jar 级接线），
与 agent 侧模块级那批断的东西不同：app 这层断了（迁移没跑、某个自动配置在完整装配下不生效），
agent 那些测试一条都不会红。库的来源没变（本机由 `spring-boot-docker-compose` 按 `compose.yaml` 起）。

### 二、能端到端的改成真模型，改不了的降级为组件级

**端到端（真模型 + 真库）**：销售额问答（同步/流式/换问题数字跟着变）、工具调用闭环、
会话记忆 7 条、装配可见清单 3 条、ChatClient 装配、AgentPort 包装、Advisor 双路径、HTTP 端到端 4 条、
HTTP 鉴权 3 条、降级 2 条。

**组件级（不经模型，但组件全是真的）**：调用预算、鉴权计数、脱敏。
真模型不会为了帮我们撞上限而连调 7 次工具，也不会精确地只调一次、更不会吐出指定号码
好让分块切在号码上——所以"模型这一轮要调什么"改由测试直接构造
（`ChainStubs` / `AssistantMessage` 带 `toolCalls`），执行的是真的
`ToolCallingManager`、真的 Advisor、真的计数工具。

- `RealModelSupport`：真 DeepSeek（温度 0、短重试）、**没 key 就抛 AssertionError（failure，不是 skip）**、
  有限重试（最多 2 次，每次都打日志，免得"一直没调工具"被重试掩盖）
- `RecordingChatModel`：转发给真模型、只记录收到的 `Prompt` 的**探针**。
  判据落在"模型这一轮看得见哪些工具"时只能从请求侧读，而这件事从回答上看不出来
- `ChainStubs`：Advisor 链上"模型那一环"的桩（它是桩不是替身模型）

### 三、真模型当场抓到的缺陷（这一趟最值钱的输出）

**用户消息里的手机号以明文进了会话表。** 出站脱敏在 `after`，会话记忆在 `before` 最早那一步
就把用户消息写库了，改不到它。假模型时代没有任何用例往用户消息里放号码，这条边界一直是黑的。
修法：`ChatClientAgentPort` 入站先过 `PiiRedactor`。代价记账：模型从此看不到用户写的原始号码，
当前没有"按手机号查"的工具，哪天有了给那个工具开白名单。

**三条 HTTP 用例曾互相污染**：共用一个 user 就共用一个会话，真模型把上一轮的"没权限"带进了下一轮。
改成每条用例换一个用户。

### 四、断言风格被迫改掉的几处（都是假模型时代留下的回声依赖）

- HTTP 端到端不再断言"回答里回着问题原话"、不再断言含 `[mock]`
- Advisor 留痕从 before 挪到 after（真模型不会复述用户消息）
- 金额一律按 `128,400.00 / 128400.00 / 128,400 / 128400` 四种写法匹配——
  真模型会自己加千分位，只匹配一种写法会在措辞上假红
- 拒绝话术不再断言原话：拒绝文本回到模型后会被它自己组织一遍

### 五、变异检验

| 变异 | 结果 |
| --- | --- |
| 去掉入站脱敏（`PiiRedactor.mask`） | 会话脱敏那条红 |
| `ToolCallBudget` 去掉 `maxTotalToolCalls` | 预算 2 条红（第 7 次调用真的发生了） |
| `AuthorizationAdvisor.denialFor` 一律放行 | 鉴权 5 条红（计数抓到"工具真的跑了"） |
| 把端到端断言换成永真（"回答非空"） | **真做过一次，不是设想**：见下面第六条 |
| 脱敏 `ORDER` 挪到工具循环内（改成 0） | 会话那 7 条**仍绿**；只有 `PiiRedactionAdvisorTest.order排在工具循环外面` 红。与票面预期不同，原因见下 |

**"脱敏 order"这条变异为什么不红了——这是入站脱敏带来的防线前移，不是约束失效。**
票面写这条时，会话里存的是原文还是掩码，唯一决定因素是"脱敏比记忆先收尾"（order 约束）；
加了入站脱敏之后，用户消息在交给链之前就已经是掩码，于是 order 从**唯一防线**降级为
**第二道防线**：动它，会话那 7 条不再红，但仍有一条专门钉着 order 的单测红。
两道防线都在，坏法不同——去掉入站脱敏会红（主防线），挪 order 也会红（副防线，由单测盯着）。
这个变化要记账，否则将来有人看到"挪 order 不红"会误以为那条约束已经没人管了。

### 六、收尾：我自己写的永真断言，当场掩盖了一个真问题

票面第一条变异检验是"把端到端断言换成永真（只断言回答非空）→ 需换成能证伪的对照断言"。
第一轮收尾时我**真的这么写了**：`HttpChannelEndToEndTest` 三条断言全改成了
`assertFalse(body.isBlank())`，理由是"真模型不会复述原话"。补做这条变异检验时（就是把断言
从非空改回"回答里有没有那个数"）立刻红 3 条，而红的原因是真问题，不是措辞：

- **这个测试一直在用没权限的默认账号**。它只发 `X-Claw4j-User`、不带角色头，
  落到 `defaultRole = read-only`；而它问的是成交额。假模型时代这条测试是回声，
  从来没真查过库，所以"有没有权限"这件事一直没人问过。
- **它还在用固定用户名**，而 app 这一侧用的是**常驻库**、会话落库——上一次跑留下的
  "没权限"对话会被真模型读进去，于是它顺着历史继续答"没权限"。

改成"每条用例换一个用户 + 显式带 `X-Claw4j-Role: admin`"之后拿到数，绿。

值得单独记一笔的是**因果顺序**：先有永真断言（"非空"），才有"这个测试其实一直没查到数"
这件事能被藏住。换真模型不会自动改善判据——断言放宽得比模型更狠的话，
真模型带来的那点真实性会被原样吃掉。这条与 ADR-0011 里"断言只写方向性"不冲突：
方向性是"不钉死措辞"，不是"不钉结果"。

### 七、留给后面

- CI 需要配 `secrets.DEEPSEEK_API_KEY`（workflow 已接上）；没配就是红，不是跳过
- 温度 0 只是压低随机、不是消除：`ModelSelectionTest` 有一条钉着这个默认值
- flaky 与成本一旦失控，第一顺位是改成录制回放（cassette），不是退回 mock
