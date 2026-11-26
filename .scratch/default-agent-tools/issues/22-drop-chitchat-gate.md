# 票 22：撤掉检索层的寒暄拦截，no-search 归到模型侧 irrelevance

**Status:** open（代码已落地；装配那条用例缺 `DEEPSEEK_API_KEY` 未现场复核，见「复核」）
**来源：** 用户 2026-11-26 的三条质疑 + 一句裁决。质疑：「不具备通用性」「很长一句话里面包含了
CHITCHAT 这些关键字，那算不算寒暄」「每次过来都要跑一遍正则，真实场景会有性能损耗」「主要感觉
`ChitchatToolIndex` 就是为了跑评测专门增加的」。裁决：「去除 ChitchatToolIndex 吧，感觉真正要提高的
是 irrelevance」。

## 为什么撤

四条，都能指到事实。

1. **词表与题库互为因果。** `gold.yaml` 的 10 道 `no-search` 全是「你好 / 谢谢，明白了 / 哈哈 /
   知道了」，词表就是照着它们写的。1.000 这个数只证明词表覆盖了自己那份题，不证明任何通用性。
2. **覆盖的是"整句寒暄"，不是"没有意义的对话"。** 判据是词表逐段删空，删空才命中——这保证了
   不误杀长句（「谢谢，那个报表怎么导」照常检索），代价是它兜不住真正的废话：「嗯嗯，你说的这个吧，
   我这边也说不太清楚，反正大概就这样」里「说」「清楚」留下了，判不中。用户的直觉在这一点上是对的。
3. **位置错了。** `ToolIndex` 的契约是 query → tools；「这句话该不该检索」是意图判定，不是检索。
   业界两条路都不把它放在检索层：语义路由（Aurelio 的 Semantic Router：route = 一组示例话语的
   embedding，最近 route 低于阈值返回 `None` 即 off-topic，0 次模型调用）与交给模型（Anthropic
   Tool Search Tool 一系，BFCL `irrelevance` 量的正是这件事）。
4. **与 `irrelevance` 重复且更弱。** BFCL `irrelevance` 240 题判的是"给的工具有没有能做的、正确
   动作是一个都不调"，我们 **77.08%**，55 条错全是 `decoder_success`——模型自己解出了合法调用。
   检索层返回零候选并不能阻止这件事，那是模型侧能力。

**性能那条不成立，写在这里防止以后被当理由引用**：词表是 35 条 × 一次 `replaceAll` + 35 次
`String.replace`，100 字的 query 约 3500 次字符比较，微秒级；同一轮里向量翼要做一次
`vectorStore.similaritySearch`（内含一次远程 embedding，30–100ms）。差四个数量级。它是整条链路里
最便宜的环节。（顺带 `String.replaceAll` 每轮重新编译 `Pattern`，是毛病，但不是撤它的理由。）

## 目标形态

| 现在 | 目标 |
| --- | --- |
| `ToolSearch.index()` 最外层 `ChitchatToolIndex` | 最外层是 `RuleToolIndex`（配了规则时）或索引本身（`HybridToolIndex` / `NamespacedToolIndex`） |
| 寒暄、空 query → 零候选 | 原样交给内层索引；"该不该调"由模型与 `irrelevance` 档负责 |
| 检索层评测有 `no-search` 一档（零候选率） | 这一档从检索层评测移除；能力归 BFCL `irrelevance` |
| `ChitchatToolIndex`（121 行，含 35 词词表） | 删除 |

## 决定

| 决定 | 为什么 |
| --- | --- |
| 整个类删掉，**不做语义锚点替代** | 锚点竞争（把"无工具意图"当第 0 号虚拟候选参与打分）仍然是"检索层判意图"，位置同样不对；它还要维护锚点、在融合后过滤虚拟候选。真要做也该放在"要不要披露工具"的决策点 |
| 空 query 的防御一起删 | 它和寒暄属同一类判断（框架 `RegexToolIndex` 空 query 返 `.*`、向量翼返前 k 个）。留一条就等于保留"检索层判意图"的先例 |
| `gold.yaml` 删掉 10 道 `no-search` 题 | 它们不进 qrels，删掉不影响 `R@5`；留着就必须给一个判据，而正确的判据在模型侧 |
| `run_ir.py` 删 `zero_candidate()` 与报告里那一列 | 指标没有对象了，留着会一直是 0.000，变成一条永远不达标的假门 |
| `baseline.json` 去掉 `no_search_rate` | 同上，并重钉题数（60 → 50） |
| 不新增 ADR | 不新增配置项、不改 `ToolIndex` 契约、不动对外行为里的任何能力；撤回的是上一张票自己加的一层。**no ADR needed** |

改动面（9 处）：

- 删 `claw4j-agent/.../toolaccess/ChitchatToolIndex.java`
- `ToolSearch.java`：`index()` 返回值、两处 javadoc（第 97 行、第 125–128 行）
- `ModelAndToolSelectionTest`：`inner()` 的剥壳分支（含 import）、`chineseQueryNeedsNoSpaces` 的第三段
  四条断言、`rulePutsItsToolFirst` 的分层断言
- `ApplicationAssemblyTest`：516–518 行的分层断言（计量 → 寒暄 → 融合/分组 → 计量 → 融合/分组）
- `application.yml` 与根 `README.md` 第 118 行的描述
- `eval/tools/retrieval/`：`gold.yaml`、`run_ir.py`、`baseline.json`、`README.md`、重生成 `reports/`
- 票 21 追加一条："第 2 批之二"里"闲聊拦截独立成 `ChitchatToolIndex`"这一条已于票 22 撤回

## TDD 用例清单

| 判什么行为 | 用例 | 先红的样子 | 结果 |
| --- | --- | --- | --- |
| 配了规则时，最外层是规则层（原来断言的是寒暄层） | `ModelAndToolSelectionTest#rulePutsItsToolFirst`（991 行） | 现有断言 `isInstanceOf(ChitchatToolIndex.class)`，类删掉后编译不过 | ✅ |
| 中文整句照样命中，去掉寒暄断言后这条不被顺手弄坏 | `ModelAndToolSelectionTest#chineseQueryNeedsNoSpaces`（504 行） | 删第三段时把前两段一起删掉，就没人守"中文整句不空手" | ✅ |
| 删除后装配链是 计量 → 融合/分组（原来是 计量 → 寒暄 → 融合/分组） | `ApplicationAssemblyTest#toolSurfaceDefaultsToAutoAndWrapsGrouping`（486 行，516–518 是那三行） | 还 import 不到已删的类；剥壳写死两层，改后必须指向 `HybridToolIndex` 或 `NamespacedToolIndex` | ❌ 未跑：本机 `.env` 的 `DEEPSEEK_API_KEY` 为空，ADR-0011 没有 mock 兜底，上下文起不来 |
| 题库不再有 `no-search`，空 gold 会被拒 | `ToolGoldTest`（9 条，含改写后的"空 gold 抛异常"） | 空 gold 若被静默接受，分母就少一道该扣分的题 | ✅ |
| 检索层评测不再有 `no-search` 一档 | `./eval/tools/retrieval/run.sh --from-report` | `run_ir.py` 仍去找 `no-search.jsonl`，缺文件时报错或静默跳过 | ✅ 退出码 0 |

⏳ **「寒暄不给候选」这条行为本身**：本票不再由任何用例守——它不再是系统的约束，改由 BFCL
`irrelevance` 量。见缺口。

复核：`2026-11-26` — `ModelAndToolSelectionTest` 26/26 绿；`ToolGoldTest` 9/9 绿；
`-pl claw4j-agent,claw4j-eval -am test` 231 条里 230 绿，唯一红的是
`ChatPipelineTest#realModelTurnsNaturalLanguageIntoTheRightToolCall`（缺 `DEEPSEEK_API_KEY`，
与本案无关）；`run.sh --from-report` 退出码 0、`R@5` 0.880 达标。
**`ApplicationAssemblyTest` 那一条没跑**——同样是真模型上下文，同一个原因，留待有 key 时复核。

## 落地记录

| 改了什么 | 说明 |
| --- | --- |
| 删 `ChitchatToolIndex.java` | 121 行，含 35 词词表与"删空才算寒暄"的判据 |
| `ToolSearch.index()` 直接返回规则层或索引本身 | 注释里留了撤掉的三条理由，防止以后有人照"性能"再把它加回来 |
| `ToolGold.goldTools` 改为**不可为空** | 空的唯一含义原本是"不该有候选"，这个语义已不存在；现在空 = 漏写 gold，构造时抛异常 |
| 评测产物同步 | `gold.yaml` 删 10 题（60→50）、`categories.txt` 删 10 行、`no-search.jsonl` 删除、`baseline.json` 去掉 `no_search_rate`、报告重生成 |

评测没有重新导出、只跑 `--from-report`，依据是：**被删的那一层对剩下 50 题原样透传**——它只在
"整句由寒暄词拼成"时返回零候选，而题库里已经没有这类题。所以那 50 题的 `.run` 与重新导出等价。
（有 `QWEN_API_KEY` 的环境再跑一次完整导出也行，预期逐格相同。）

## 缺口

- **`irrelevance` 77.08% 不达标，本票不提高它。** 这是用户点名的下一件事。两条待查线索，各自要
  单独一张票、单独量：
  1. 我们的 `ToolSearch.SYSTEM_SUFFIX` 在**鼓励**检索——「凡是要用工具才能办的事，第一步就是调
     `toolSearchTool`」「给了网址、文件路径、命令就是要用工具」。这句话治的是"该查不查"（票 05
     实测过），但它与"不该查时不查"直接对冲。BFCL 那 240 题是在**没有**这段提示词的条件下跑的，
     所以先要分清：55 条错是模型本身克制力不足，还是我们的提示词在推它。
  2. 检索侧是否还有隐性推力：`mode=auto` 在工具数 ≤ `reveal-below` 时全塞，此时 `irrelevance`
     类的问句面前摆着全部工具，比"只看见一个 toolSearchTool"更容易被硬凑。
- **端到端是否会为一句寒暄多跑一轮检索**：本票不量。要量得跑真实模型的多轮，属另一票。
