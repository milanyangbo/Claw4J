# A06 · 评测集：把"跑通"换成"跑得好"

**Status:** ready-for-agent
**Blocked by:** 无（依赖的 A01 已落地）。**但 A07–A10 全部依赖本票**（ADR-0019 原话：
"A06 是 M8 唯一的硬闸门。它之前的所有『改好了』都是自述"）。

## 为什么

仓库里现在有 646 条用例，全部真模型、全部真库、跳过 0。它们证明的是**装配成立**：
工具挂上了、鉴权拦住了、迁移跑完了、流式块发出去了。没有一条能回答另一个问题——
**这版比上版好还是差**。

具体到今天会卡在哪儿：改一句工具描述、换一版模型、调一次路由顺序，
**没有任何数字会动**。判断"改好了"只能靠人读 diff、跑一遍对话、看回答像不像。
ADR-0019 后四张票的判据全是效果类的：

- A07「中文整句 query 能命中它该命中的工具」——命中率，现在没法测
- A08「跑到最后模型仍答得出未完项与上次失败原因」——现在只能肉眼问一遍
- A09「故意让工具因为参数可修的原因失败 → 模型自救成功一次」——现在只能肉眼看
- A10「一次委派的总消耗不超过明示上限」——这条能测（预算是数字），但它挂在 A06 后面

所以这张票不是"补一个好东西"，是**后四张票的验收方式本身**。没有它，那四张只能靠自评。

## 判分的原料已经有了（A01 的收益，先说清不用新建什么）

ADR-0019 给 A06 的依赖写的是 A01，理由是"按 run 收工具轨迹"。核过了，确实齐：

| 判分要看 | 从哪儿读 | 现状 |
| --- | --- | --- |
| 答案是否命中要点 | `Run#result()` —— 上一轮回答的**全文**（A05 写在 run 上，不是 step 的截断摘要） | 已有 |
| 工具轨迹是否对 | `RunStore#stepsOf(runId)` 的 `Step#toolNames` / `StepKind` / `StepStatus` | 已有 |
| 有没有越权 | `AuthorizationAdvisor.DENIAL_PREFIX`（`"没有权限"`，公开常量）出现在 `Run#result()` 里；同时 `stepsOf` 里那条被拒调用的审计行（`StepStatus#FAILED` + `resultSummary` 带同一句） | 已有 |
| **怎么拿到 runId** | `AgentPort#reply(...)` 返回的 `OutboundMessage#runId()` | 已有（`ChatClientAgentPort:200` 真的填了它） |

最后一行是这张票能不能做的关键：如果 `OutboundMessage` 不带 runId，评测就只能从
HTTP 层猜、或者去库里按时间去捞最新一行——两种都会错。它在 A01 时被加进去了，
这张票是它的第一个真实消费者。

**不新建表、不加列、不改 `agent_step` 的形状**。判分是只读投影。

## 做什么

### 一、用例集：`eval/cases/*.yaml`

50~100 条，按能力分文件（一个文件一类问题，别按工具分——工具会变，问题不会）：

```
eval/cases/
    sales.yaml         取数：口径、半开区间、汇总、跨表
    knowledge.yaml     知识库检索：命中、出处、答不上来时说什么
    tools.yaml         工具选择：这句话该调哪个工具、不该调哪个
    authz.yaml         越权：表级、动作级、身份缺失
    memory.yaml        多轮与压缩之后的记忆
```

一条用例的形状：

```yaml
- id: sales-weekly-east    # 全局唯一，报错与报告都靠它定位，改了等于换一条用例
  question: 上周华东区的成交额是多少
  principal: analyst       # 指向下面「身份」一节的四档之一，不内联可访问表
  mustMention: ["128400"]  # 必须出现的**数字或标识符**
  mustNotMention: ["31500", "26000"]   # 不许出现的：别的区的数、明细、手机号
  expectTools: ["querySales"]          # 该调的工具，按集合比，不比顺序
  note: 半开区间 [周一起, 下周一止)，跨表 sales_order × region
```

**这个 `128400` 是核过的，别照着猜。** 它在
`claw4j-example-sales/src/main/resources/db/migration/V2__business_seed.sql:42`
的注释里写着"上周华东合计 128400"；而"**上上周**华东"按 m6 的冒烟记录是 `0`（那一周没有行）。
周口径写错一个字，这条用例从写下那天就是红的——而它会以"模型答错了"的样子出现。
最后一节第 3 条就是为这件事准备的。

**`mustMention` 只放数字与标识符，不放自然语言短句。** 这是这张票最重要的一条判分口径：
"命中要点"如果是"回答里要有『华东区上周卖得不错』"这种话，模型换个说法就假红。
而 `128400` 无法同义替换——它要么查对了要么没查到。措辞类判断不放进确定性判分，
留给人工看报告时读。

`mustNotMention` 同样只放数字与标识符，它守的是最贵的那类错：**答了不该答的**
（别的区的数、别的身份能看的明细、明文手机号）。

### 二、身份：四档，不内联

`eval/cases/principals.yaml` 定义，用例只写名字引用：

```yaml
analyst:   { role: analyst,   tables: ["sales_order","region","customer"], actions: ["read"] }
guest:     { role: guest,     tables: [],                                  actions: [] }
read-only: { role: read-only, tables: ["*"],                               actions: ["read"] }
admin:     { role: admin,     tables: ["*"],                               actions: ["*"] }
```

**为什么不内联到每条用例**：越权用例的价值在于"同一个问题换个身份，结论必须相反"。
身份散在 100 条用例里之后，`guest` 到底能看什么表要读 100 处，而任何一处写错
都会让那条用例变成"验证了一个不存在的身份"。集中一份，改一次全对。

### 三、判分：三件事，各自独立算

`EvalScorer` 是纯函数（不碰 IO、不碰模型），输入「用例 + 实际结果」，输出判定。
三件事分开算而不是合成一个分数，因为它们的失败含义完全不同：

| 判的什么 | 怎么判 | 红了说明什么 |
| --- | --- | --- |
| **答案命中** | `mustMention` 全在 `Run#result()` 里，`mustNotMention` 一个都不在 | 答错了、答漏了、或者编了 |
| **轨迹对** | `stepsOf(runId)` 里 `TOOL_CALLS` 行的 `toolNames`（逗号分隔，split 后取并集）⊇ `expectTools` | 工具没调、调错、或者压根没挂上 |
| **越权守住** | 用例标了 `expectRefusal: true` 时：`Run#result()` 含 `AuthorizationAdvisor.DENIAL_PREFIX`，**且**结果里没有 `mustNotMention` 的任何一项 | 该拒的没拒，或者拒了但仍漏了数 |

**越权那条不能用 `Run#status()` 判，这是写票时差点做错的一处。** 被拒之后模型不是
"失败了"，而是**成功回答了一句"没有权限"**——run 的终态是 `DONE`。
按 status 判，所有越权用例会从第一天起就假红，而假红比漏判更容易被"顺手改绿"。

可靠的两个锚点都在代码里，不在措辞里：

- `AuthorizationAdvisor#DENIAL_PREFIX` 是 `public static final String`，注释写着
  "测试靠它区分『明确的拒绝』与『查出来是空的』"——它就是为这件事留的。
- 拒绝时 `replacedWith(response, denial.get().text())` **直接替换了模型响应**，
  所以最终回答就是那句拒绝文本，模型没有机会改写成别的措辞。这一点让判定是确定的。
- 同时落一条审计 step（`audit.rejected(...)`，P07），所以 `stepsOf` 里能看到
  是哪次调用被拒、拒绝的是什么。

第三条最容易做错的地方在于：只断言"被拒了"是不够的——拒绝话术可以出现，
而数已经先漏出去了。所以它必须**同时**验"拒了"与"没漏"。

`expectTools` 用**集合包含**而不是相等：模型多调一个工具不算错（可能是它先查了表结构），
少调才错。但多调到"查了不该查的表"要能红——那由越权那条守，不由轨迹守。

### 四、落点：新模块 `claw4j-eval`，判分在主代码、执行器在测试

```
claw4j-eval/src/main/java/io/github/claw4j/eval/
    EvalCase.java         record(id, question, principal, mustMention, mustNotMention,
                                 expectTools, expectRefusal, note)
    EvalVerdict.java      record(caseId, answerHit, traceHit, authzHeld, failures)
    EvalScorer.java       纯函数：judge(case, result, steps) → EvalVerdict
claw4j-eval/src/test/java/io/github/claw4j/eval/
    EvalScorerTest.java   判分口径单测（进回归，不带 tag）
    EvalCaseSetTest.java  真读 eval/cases/*.yaml 做静态校验（进回归，不带 tag）
    EvalRunTest.java      跑真模型、写报告（@Tag("eval")，默认排除）
eval/cases/*.yaml         用例集（上面第一节）
eval/reports/             报告落盘（.gitignore，只留最后一次的摘要进 README？见第六节）
```

**为什么判分在主代码、执行器在测试**：判分是这张票唯一的"我们的逻辑"，
它坏掉的表现是"报告全绿但其实是假的"——必须进回归、必须有覆盖率。
执行器只是胶水（读用例 → 调 `AgentPort` → 交给 `EvalScorer` → 写 JSON），
它天然要真模型，进不了回归；放在 `src/test` 里，它就不会在 jacoco 上留覆盖率债。

**为什么不放进 `claw4j-app`**：app 的测试文件满 10 个，而且 app 的职责是"启动与编排"，
量具不是它的活。**也不放进 `claw4j-core`**：core 同样满 10 个文件，
而且判分要读 `Step`（在 `agent` 模块），core 不许依赖 agent。

### 五、怎么跑：一条独立命令，不进 `verify.sh`

```
./scripts/eval.sh                    # 全跑
./scripts/eval.sh sales knowledge    # 只跑某几类
./scripts/eval.sh --case sales-weekly-east
```

脚本做三件事：`source .env`（没 key 直接红，不是跳过——ADR-0011 的落地规则）→
`./mvnw -pl claw4j-eval -am -Peval test`（新 profile，把 `eval` 组放出来）→
打印报告摘要。

**为什么不进 `verify.sh`**：`verify.sh` 现在是 646 条、几分钟。加上 100 条真模型调用
（每条 1~6 次往返）是另一个量级，而且它要读真实业务数据、要联网。
ADR-0019 自己写的是「**与 301 条装配用例分开跑、分开看**」。

**但"分开跑"不等于"没人管"**。这是这张票最容易做漏的一处：`verify.sh` 的基调是
"跳过 0、不许 assume"，而一个不进回归的评测集，退化成**没人跑的死文件**只是时间问题
（ADR-0011 记的那个 P0：26 条数据库测试从未执行却被当成绿，就是这个形状）。

所以补一条防线：**评测集本身进回归**。`EvalCaseSetTest` 不调模型，只读文件，断言：

- 每条用例的字段齐全（缺 `id` / `question` / `expectTools` 直接红）
- `id` 全局唯一且是 kebab-case（报告里靠它定位，重名会让两条用例的判定互相覆盖）
- `principal` 在 `principals.yaml` 里存在（写错名字的用例等于验了一个不存在的身份）
- `expectTools` 的每个工具名**在当前工具清单里真实存在**（工具改名后这条会红，
  而不是等评测跑完发现"全都没命中"）
- `mustMention` 里至少有一项看起来是数字或标识符（防止有人写回自然语言短句）

这一条把「用例写坏了」与「评测没跑」分开了：**前者在回归里红，后者是这张票承认的局限**。

### 六、报告：能比较，才叫报告

`eval/reports/<yyyyMMdd-HHmmss>-<short-sha>.json`，一次运行一份。字段：

```json
{
  "ranAt": "2026-11-05T10:12:33+08:00",
  "commit": "486c333",
  "dirty": false,
  "model": { "primary": "deepseek-chat", "temperature": 0.0 },
  "caseSetDigest": "sha256:…",
  "totals": { "cases": 62, "answerHit": 58, "traceHit": 60, "authzHeld": 62 },
  "cases": [ { "id": "sales-weekly-east", "answerHit": true, "traceHit": true,
               "authzHeld": true, "failures": [], "runId": "…", "millis": 4210 } ]
}
```

- **`caseSetDigest`**：用例集内容的哈希。没有它，两份报告分数不同时说不清是
  "代码变了"还是"用例变了"——而那正是这张票要回答的问题。
- **`dirty`**：工作区有未提交改动时记 true。分数可以比，但要能看出比的是什么。
- **`runId`**：留着，报告里的每一条都能回库查那一轮的 step。判分有争议时唯一的裁决入口。
- **`temperature: 0.0`**：ADR-0011 定的，不写进报告的话，两次运行分数不同就说不清
  是模型抖还是代码改了。

控制台只打三行（总数、三类各中多少、不中的那几条 id），细节进 JSON。
**不做一个 HTML 报告页**：这张票要的是"改完能看见分数动"，不是给人看的仪表盘。

### 七、怎么证明它是量具（判据里最硬的一条）

ADR-0019 的判据原话：**"改一句工具描述，报告里的分数能看到变化"**。所以要先埋好**三处**能被改坏的东西。
三处都是核过代码里真实存在的那一句，不是照着想象写的：

1. **`querySales` 描述里删掉 `east=华东` 那行**（`SalesTools.SCHEMA` 的 `business.region` 注释）
   → 问"华东"的用例应当掉分。这条特别有说服力，因为 `SalesTools` 的类注释自己就写着：
   "写 `where region_code = '华东'` 不会报错、只会返回 0 行，而『返回 0 行』是自纠错唯一
   无法自己发现的错误"——描述里那一行**就是**让模型不写中文区域名的唯一原因。
2. **`searchDocs` 的 `@ToolParam` 从"要检索的问题或关键词，直接写用户的原话"改成"写关键词"**
   → 知识库类的中文整句用例应当掉分（现在描述明确鼓励写原话，改成关键词等于把 A07 要修的病
   写进描述里）。
3. **`sales.yaml` 里 `mustMention` 的 `128400` 改成 `31500`**（改的是用例，不是代码）
   → 那条应当掉分，且 `caseSetDigest` 跟着变。这一处证明"报告能把『用例错了』与『代码错了』
   分开"——前两处 `caseSetDigest` 不变、`commit` 变，这一处反过来。

三处各改一次、各跑一次、各记录一次分数。**这是验收动作，不是可选的自检**：
做不到这一条，说明判分没接在真正影响结果的地方上。

### 八、文档改哪两处（不多改）

- **`CONTRIBUTING.md`**：在"怎么验证"旁边加一条与 `verify.sh` **并列**的路径：什么时候该跑
  `./scripts/eval.sh`、跑一次多久、为什么它不在 `verify.sh` 里。不写这一段的话，
  下一个人不会知道有这个东西——而进不了日常动作的量具，与不存在没有区别。
- **`docs/agents/testing.md`**：第二节的"三类对象"表里已经有「模型可用性」这一类
  （"一句话进 → 一轮工具调用 → 一整段出"）。补一句指向评测集：**这一类要成批测，
  且判分锚在数字与标识符上**。它讲的是"什么该写测试"，评测是这一类的规模化做法，
  不补的话那张表会让人以为"模型可用性"就是写几条真模型用例。

**`README.md` 不改。** 它是给接框架的人看的，评测是这个仓库自己的量具；
写进去只会让"我要不要跑它"变成一个对外的承诺。

## 不做什么

- **不做 LLM-as-judge**（再叫一次模型来判前一次答得对不对）。判分必须是确定性的，
  否则两份报告分数的差里有一半是裁判模型的抖动，"这版比上版好"就永远说不清。
  它的正确用法是"人工看报告时帮你归类失败原因"，那是人的活，不是闸的活。
- **不做录制回放 / cassette**。ADR-0011 已否决，理由（会随 prompt 与工具描述静默过期）
  在这里更严重：评测集的存在就是为了发现"改了描述之后效果变差"。
- **不进 `verify.sh`**，理由见第五节。
- **不追求绝对分数**（"62 条里 58 条命中 = 93.6 分"这种话不写进 README）。
  它只在**同一份用例集的两个 commit 之间**有意义；跨版本、跨模型比分数是无意义的，
  而一旦有绝对值，就会有人拿它去比。
- **不覆盖通道层**（飞书/钉钉/Slack 的入站解析、签名、回推）。那些是协议与绑定，
  不是"答得好不好"，`docs/agents/testing.md` 判据二划得很清。
- **不新增任何配置项**。评测要配的东西（模型、端点）已经在 `claw4j.model.*` 里，
  再开一套 `claw4j.eval.*` 会让"跑评测时用的是哪个模型"变成第二个真相。

## 代价与已知局限（写在这里，不让它以后变成"意外"）

- **一次全跑十几分钟 + 几分钱**。50~100 条 × 1~6 次往返。这不是"贵"，是"贵到不能每次
  提交都跑"，所以它不进回归——代价是**它可能长期没人跑**。`EvalCaseSetTest` 只守住
  "用例集没烂"，守不住"评测没跑"。如实记账。
- **判分是字符串包含，不是语义**。模型把 `128400` 说成"十二万八千四"就假红。
  缓解手段是 `mustMention` 只放数字与标识符，但这条缓解不彻底：模型确实会换个写法。
  报告里 `failures` 会带上实际答案片段，假红一眼能看出来，所以它是"要看一眼的报告"，
  不是"红了就改代码的闸"。
- **用例集会漂**。模型升级之后同一批用例的通过率可能整体变化，那时要重新校准
  `mustMention`（有些数字会变得答不出来）。**校准的时候要记一笔"为什么改"**，
  否则三次校准之后没人说得清分数下降是代码退步还是用例放宽了。
- **`expectTools` 按工具名比**。工具改名（比如 `querySales` → `query_sales`）会让
  整批轨迹判定红。这是有意的：改名本来就该让评测红一次，让人去看"描述是不是也跟着变了"。

## 门禁余量

实测（`find <module>/src/test -name '*.java' | wc -l`，脚本按 `@Test|@ArchTest` **子串**数方法）：

| 模块 | 现在 | 本票之后 | 说明 |
| --- | --- | --- | --- |
| `claw4j-core` | 10 个（顶格） | 10 个 | **不动**。判分不放进 core，理由见第四节 |
| `claw4j-app` | 10 个（顶格） | 10 个 | **不动**。app 的职责是启动与编排，量具不是它的活 |
| `claw4j-eval` | 新模块 | 3 个文件 | `EvalScorerTest`(≤14) + `EvalCaseSetTest`(≤8) + `EvalRunTest`(1，带 `@Tag("eval")`) |

`claw4j-eval` 要加进根 `pom.xml` 的 `<modules>` 与 `scripts/verify.sh` 的 `MODULES`
（后者不加的话，这个模块的文件数与覆盖率不会被统计——一条新模块最容易漏的一步）。

覆盖率：`claw4j-eval` 的主代码只有三个类（两个 record + 一个纯函数），
`EvalScorerTest` 与 `EvalCaseSetTest` 都进回归，60% 线够用。
`EvalRunTest` 被排除，但它不产生主代码覆盖，只影响它自己那一行。

## 验收判据

- [ ] **一条用例能从提问跑到判定**：真模型、真库，`./scripts/eval.sh --case <id>` 跑通，
      报告里那条 `answerHit` / `traceHit` / `authzHeld` 三个值都有。
      反例：只写 `runId` 不写判定 → 红。
- [ ] **答案命中按 `mustMention` 判**：故意把 `mustMention` 里的 `128400` 改成 `999999`
      → 那条 `answerHit` 变 false 且 `failures` 里带上"缺 999999"。反例：改成"回答非空即通过" → 红。
- [ ] **`mustNotMention` 真的拦得住**：某条用例加了 `mustNotMention: ["31500"]`，
      而问题是"华东"（模型若答成华北的数）→ 判定 false。反例：只判 `mustMention` 不判
      `mustNotMention` → 红（"答了不该答的"比"答漏了"贵）。
- [ ] **轨迹按集合包含判**：`expectTools: ["querySales"]` 时，模型多调了一个 `searchDocs`
      → 仍算命中；少调 `querySales` → 不命中。反例：用 `equals` 判集合 → 多调就红，
      而多调不是错。
- [ ] **越权那条要同时验两件事**：`expectRefusal: true` 的用例，只有在
      「`Run#result()` 含 `AuthorizationAdvisor.DENIAL_PREFIX`」**且**「结果里没有
      `mustNotMention` 任一项」时才通过。
      反例一：只看"有没有拒绝文本" → 红（拒了但数已经漏出去了，那句话照样在）。
      反例二：改用 `Run#status() != DONE` 判 → 红（被拒之后 run 是 `DONE`，所有越权用例假红，
      而假红的下场是被顺手改绿）。
- [ ] **被拒的调用在审计里看得见**：`expectRefusal` 的用例，`stepsOf(runId)` 里
      至少一条 `StepStatus#FAILED` 且 `resultSummary` 含拒绝文本。
      反例：只读最终回答不看 step → 红（"拒了哪一次调用"说不清，报告只够说"它拒了"）。
- [ ] **身份集中一份**：`principals.yaml` 里 `guest` 的可访问表是空，所有越权用例引用它；
      改 `guest` 的表清单，所有引用它的用例一致受影响。反例：身份内联在 100 条用例里 → 红。
- [ ] **用例集自洽进回归**：`EvalCaseSetTest` 在 `./scripts/verify.sh` 里跑，不调模型。
      造一条缺 `id` 的用例 → 回归红；造一个引用不存在身份的用例 → 回归红；
      `expectTools` 写一个不存在的工具名 → 回归红。
      反例：把这条校验写成"只在评测时检查" → 红（那时已经烧了钱）。
- [ ] **工具改名会让回归红**：把 `querySales` 改成 `query_sales`（只改工具名不改用例）
      → `EvalCaseSetTest` 红并点名是哪条用例引用了它。反例：用例里写的是工具的
      `description` 前缀 → 红（那会随描述变化而误红）。
- [ ] **报告能比较**：两次运行（改一行代码前后）的 JSON 里，`commit` 不同、
      `caseSetDigest` 相同（用例没动）；只改用例不改代码时，`commit` 相同、
      `caseSetDigest` 不同。反例：报告里没有 `caseSetDigest` → 红（分数变了说不清是谁变的）。
- [ ] **判据里的那三处"改坏"各跑一次并记录**：删 `querySales` 的"按周汇总"、
      把 `searchDocs` 的"直接写用户原话"改成"写关键词"、把某条 `mustMention` 改成错的数字
      —— 三处各让对应的用例掉分，三份报告（或报告片段）贴在 Comments 里。
      反例：只跑一次全绿的基线就交差 → 红（那说明不了它是不是量具）。
- [ ] **温度是 0.0 且记进报告**：报告里有 `model.temperature`。
      反例：报告里没有模型信息 → 红（两次分数不同时无法归因）。
- [ ] **没有 key 是失败，不是跳过**：`./scripts/eval.sh` 在无 key 时退出码非 0 并点名缺什么。
      反例：`@EnabledIfEnvironmentVariable` → 红（ADR-0011 的落地规则）。
- [ ] **`claw4j-eval` 进了 verify 的统计**：`verify.sh` 的输出里出现 `claw4j-eval` 那一行，
      文件数与覆盖率都有数（不是 `-`）。反例：加进 `<modules>` 但没加进 `MODULES` → 红。
- [ ] `./scripts/verify.sh` 全绿，跳过 0，行覆盖率不低于 60%，既有用例一条不改地通过。

## 落地时先确认的三件事

1. **`OutboundMessage#runId()` 在真模型路径上真的不为 null**。代码里 `ChatClientAgentPort:200`
   填了它，但那是"读了代码"，不是"跑过"。开工第一件事是跑一条用例、打印 runId，
   为 null 就先修装配（那说明 run 存储没接上，评测读不到轨迹）。
2. **`eval` 这个 group 名与现有的 `real-cli` / `real-mcp` 不冲突**，且 `-Peval` 的写法
   与 `-Preal-cli` 一致（都靠"把 excludedGroups 换成不存在的组名"来放开）。
   照抄现有 profile，不发明第二套机制。
3. **业务数据在评测容器里是同一份**。`mustMention` 里的 `128400` 来自 `V*__seed.sql`，
   评测起的库必须与回归同一个迁移 + 同一份种子，否则用例从写下那天就是红的。
   核对方式：`EvalCaseSetTest` 顺手断言"答案里要求的数字在种子 SQL 里出现过"——
   这条很便宜，能挡住"用例按一个不存在的数写的"。
