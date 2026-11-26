# 票 23：把 irrelevance 拆成三层量——裸模型、我们的链路、判据归属

**Status:** in progress（第 1 批：L1 复现中；第 2、3 批未开工）
**裁决来源：** 用户 2026-11-26「去除 ChitchatToolIndex 吧，感觉真正要提高的是 irrelevance」。
**no ADR needed**（先量后改；真要改提示词或工具描述，再单独裁）。

## 先纠正我上一轮的判断

我上一轮说「BFCL 那 240 题是在**没有**这段提示词的条件下跑的，所以分不开 55 条错里有多少是模型、
有多少是我们的提示词」——**前半句对，后半句错**。

读产物核实（`result/DeepSeek-V3.2-Exp-FC/non_live/BFCL_v4_irrelevance_result.json`）：

- 240 题**每题只给 1 个函数**（`len(function) == 1`，240/240）。
- `patch/__init__.py` 里没有任何 system prompt 注入（grep `system|suffix|prompt` 无命中）。

所以 77.08% 是**裸模型 + 单工具 + 无检索层 + 无我们的提示词**。这 55 条与 `SYSTEM_SUFFIX` 无关，
"提示词是不是在推它"在 BFCL 这一层根本测不到——它只能回答"模型自身的边界判断有多宽"。

## 那 55 条长什么样（零成本，直接读产物）

185 条输出是文本（老实回答 = 对），55 条解出了调用（= 错），与 score 的
`irrelevance_error:decoder_success: 55` 逐条对齐。55 条分散在 **54 个不同函数**上——
不是某个工具描述写坏了。

错的形态是**语义相邻的过度匹配**，不是胡说：

| 题目 | 给的唯一工具 | 模型填了什么 |
| --- | --- | --- |
| 解二次方程 a=1,b=2,c=3 | `math.sum`（求列表和） | `{"numbers":[4,-12]}` |
| 求线性方程的根 | `find_roots`（只解二次） | `{"a":0,"b":1,"c":1}`——把一次方程降级成二次硬解 |
| 求 `2x` 的导数 | `calculus.compute_definite_integral` | 用差分近似塞进积分器 |
| 洛杉矶与柏林的时差 | `get_co_ordinate`（经纬度） | 调两次拿坐标，自己去算差 |
| 线粒体的数量 | `get_cell_function`（查功能） | `{"cell_part":"mitochondria","detail_level":"detailed"}` |
| 基因序列 "ATCG" | `identify_species`（按序列鉴定物种） | `{"sequence":"ATCG"}` |

判读：模型不是"不克制"，是**边界宽**——"这工具能做一件相邻的事，先调了再说"。只给一个工具时
这种倾向最强：它必须给出答案，手上只有这个。

## 第 1 批：L1 复现（2026-11-26 已跑）

`./eval/tools/bfcl/run.sh`（640 条，1 分 43 秒；先 `--smoke` 验过环境）。

**第一次跑没算数**：BFCL 有缓存，输出是 `All selected test cases have been previously generated ...
No new test cases to generate`，77.08% 只是旧结果重算。要真跑得把逐题结果挪走——旧的两份备份在
`eval/tools/bfcl/stale-20261126/non_live/`（保留，便于对照）。

真跑结果：

| 档 | 旧记录 | 本次 | 差 |
| --- | --- | --- | --- |
| `irrelevance` | 77.08%（185/240） | **76.25%**（183/240） | 2 题 |
| `simple_python` | 95.50% | **95.25%** | 1 题 |

三条结论，都影响后面的做法：

1. **单次跑的噪声是 1–2 题（0.4–0.8pt）**。同一份代码、同一个模型，两次差 2 题。所以"改一句提示词
   让 irrelevance 涨 1 个点"这件事在 BFCL 上**根本测不出来**——要么跑 5 次取中位数，要么换一批
   更敏感的题。这一条直接决定了 L2 必须做，而且 L2 的题库要比 30 条更多（30 条时一格 3.3pt，
   反而比 BFCL 噪声大）。
2. **`simple_python` 的"达标"只有 1 题余量**：95.25 距 95% 门 0.25pt。代码没动它也可能翻红，
   这不是回归，是判据贴噪声。L3 要处理的是这件事。
3. **两次都落在 `TOLERANCE`（0.03）以内**，所以没有重钉基线——`baseline.json` 保持 77.08 / 95.50，
   它是"上次被接受的那版"，不是"最新一次跑的数"。

判定：`irrelevance` 76.25% < 90%，仍不达标；`simple_python` 95.25% ≥ 95%，达标。退出码 1。

## 第 1 批之二：换调用形态（2026-11-26 已跑，L1 的另一半）

`bfcl-eval` 里 DeepSeek 其实注册了三条，都是 `deepseek-chat` / `deepseek-reasoner` 这同一批 API 模型，
差别只在**怎么调**：

| 注册名 | API 模型 | 形态 | `irrelevance` |
| --- | --- | --- | --- |
| `DeepSeek-V3.2-Exp-FC` | `deepseek-chat` | 原生 function calling | **76.25%**（183/240） |
| `DeepSeek-V3.2-Exp` | `deepseek-chat` | prompt（BFCL 的系统提示 + 文本解析） | **88.75% / 89.17%**（213 / 214） |
| `DeepSeek-V3.2-Exp-thinking` | `deepseek-reasoner` | prompt + 推理 | **87.50%**（210/240） |

跑法（`report.py` 本来就吃 `--model`，`run.sh` 写死 FC，实验期直接调 `boot.py`）：

```
set -a; . ./.env; set +a
eval/tools/bfcl/.venv/bin/python eval/tools/bfcl/boot.py generate \
  --model DeepSeek-V3.2-Exp --test-category irrelevance --num-threads 8
eval/tools/bfcl/.venv/bin/python eval/tools/bfcl/boot.py evaluate \
  --model DeepSeek-V3.2-Exp --test-category irrelevance --partial-eval
```

**同一个 API 模型，只是换了调用形态，差 12.5 个点。**这比 FC 的噪声（2 题 = 0.8pt）大一个量级，
不是抖动。

为什么 prompt 形态更好——**能找到一句话，但不能把 12.5 全记在它头上**：

BFCL prompt 形态的系统提示（`bfcl_eval/constants/default_prompts.py` 的 `classic` 档）里有一句

> If none of the functions can be used, point it out. If the given question lacks the parameters
> required by the function, also point it out.

这是**明确许可"这次不调"**，而原生 FC 模式没有对应的话。但两组之间的差别**同时**含两个变量
（调用形态本身 + 这段系统提示），本批没拆开——要拆得再加一组"FC + 只补这一句"，而那正好是
L2 能做的事（见下）。

**这条不通向"生产换成 prompt 形态"**：Spring AI 走原生 tool calling，生产就是 FC。
所以上表里 88.75% 是"模型能力上限"的诊断数，**不是生产口径的数**；生产口径仍以 FC 的 76.25% 为准。

判定：90% 这条线，三条注册项都够不着（最高 89.17%，差 2 题）。**要过 90，只有换模型或改线**，
我们自己的代码动不了这个数。

## 下一步：三条路，互不替代

| 路 | 做什么 | 成本 | 能让 BFCL irrelevance ≥90 吗 |
| --- | --- | --- | --- |
| A 换模型 | BFCL 已注册 `gpt-5.2-2025-12-11-FC`、`claude-sonnet-4-5`、`gemini-3-pro`；要对应 key，240 题几分钟 | 一个新 key | **能**，这是唯一不动评测条件的路 |
| B 改线 | 90% 是我们的发明；240 题里 2 题 = 0.83pt，而 FC 同配置两跑就差 2 题——**门限分辨率细于噪声** | 不用钱，要裁决 | 不能，它是承认够不着 |
| C 生产侧补一句 | 把那句"没有合适的工具就直接说明"写进 `SYSTEM_SUFFIX`（一行，行业形状，见票 24） | 8 分钟 L2 | 不能，但这是唯一同时改善线上的改动 |

C 的判据：L2 A 组乱调率（现 10.0%，其中 4 条 `recall_session`）。它同时是"12.5pt 里有多少来自那句话"
的拆变量实验——所以 C 不只是产品改动，它也是本票缺的那一组对照。

## 三层，必须分开

| 层 | 问的是 | 怎么量 | 现在有没有数 |
| --- | --- | --- | --- |
| **L1 裸模型** | 模型自己在单工具下能到多少 | BFCL，换模型对照 | 有：76.25% / 77.08%（两次跑，差 2 题） |
| **L2 我们的链路** | 检索层 + `SYSTEM_SUFFIX` + 5 个候选，会不会更差 | 端到端：同一批"不该调"的话走 Agent，判最终有没有调工具 | **没有** |
| **L3 判据** | 90% 这条线画在哪 | 从"十次闲聊允许一次乱调"推的；换层不换线 | 有 |

只有 L2 能回答"提示词在不在推"。L1 换模型只能回答"这是不是选型问题"。把两者混在一张表里，
就会得出"改提示词能让 BFCL 涨分"这种错结论——BFCL 里根本没有那段提示词。

## 分批

**第 1 批：探 L1 的天花板（不改代码，但要 key）**

| 选项 | 代价 | 说明 |
| --- | --- | --- |
| 换 `gpt-5.2` / `claude-sonnet-4-5` / `gemini-3-pro` | 要新 key（成本未核算，240 题量级很小） | BFCL 已注册，改 `--model` 即换；结论最硬（跨供应商） |
| dashscope 的 `qwen-plus` / `qwen-max` | 要写约 50 行 handler（继承 `OpenAICompletionsHandler`，base_url 指向 dashscope） | BFCL 只注册了自托管 qwen3 小模型，没有 dashscope 的；我们有现成 `QWEN_API_KEY` 就能跑 |

判据：qwen/GPT 若也在 75–80%，说明这是当前一代 FC 模型的普遍水平，90% 这条线画得比行业高——
**那是 L3 要改，不是代码要改**；若明显更好，是选型问题，也不该动代码去补。

**第 2 批：L2 端到端（2026-11-26 开工，同日撤回——见文末「撤回」）**

要回答的只有一句：**在我们的链路上（检索 + 5 个候选 + `SYSTEM_SUFFIX`），模型会不会比裸模型更爱乱调。**

设计：

| 项 | 定下来的事 | 为什么 |
| --- | --- | --- |
| 题库 | 60 条中文"工具做不了"的问句，形态照 BFCL 那 6 类（相邻概念、只差一个动词、场景同但指标不同、纯知识、纯寒暄、元问题） | 60 条一格 1.7pt，仍比 BFCL 的 1–2 题噪声粗，但成本可接受；30 条一格 3.3pt 直接没意义 |
| 为什么不用 BFCL 原题 | 原题是英文、单工具；我们的链路是中文、5 候选 | 语言与候选数都是变量，混进来就说不清是哪个在起作用。**L2 只做四组内部对照，不与 76.25% 比** |
| 工具集 | 真实目录（Read/Grep/Glob/Bash/querySales/mail_*/github_* 等），用**替身**实现只记调用、不真执行 | 要判的是"调没调"，不是"调对没调对" |
| 判据 | 这一轮**有没有调用任何业务工具**。调 `toolSearchTool` 不算错——提示词就是让它先检索 | 把"检索"和"乱调"混在一起判，会把正确流程也算成错 |
| 组 A | `mode=always` + `SYSTEM_SUFFIX`（20–30 工具时的生产形态） | 基线 |
| 组 B | `mode=always`，**不挂** `SYSTEM_SUFFIX` | 直接量出"提示词在不在推" |
| 组 C | `mode=off`（全部工具摆在眼前） | 工具少时的生产形态；也量"候选多就更容易凑" |
| 组 D | `mode=always` + `SYSTEM_SUFFIX` + `maxResults=1` | 贴近 BFCL 的单候选，看候选数与乱调的关系 |

不改生产代码：B 组在评测里自建 `DisclosureToolCallingAdvisor`（不挂 suffix），A/C/D 走 `ToolSearch.toolLoop`。
**不新增配置项**——为一个实验加开关，开关本身会活在产品里。

产出：`eval/tools/selection/reports/<组>.jsonl`（每题一条：qid、调了哪些工具、整轮有没有抛），
`summary.csv` 四组一行。说明与判据见 `eval/tools/selection/README.md`。

**2026-11-26 第一次全量（60 条，DeepSeek-V3.2-Exp-FC，四组并行约 8 分钟）**

| 组 | 乱调 | 率 | 其中 `recall_session` | 明确乱调（WebFetch/Bash/Glob） |
| --- | --- | --- | --- | --- |
| A 生产形态 | 6/60 | 10.0% | 4 | 2 |
| B 不挂提示词 | 2/60 | 3.3% | 0 | 2 |
| C 全塞 | 7/60 | 11.7% | 2 | **5** |
| D 只给 1 个候选 | 5/60 | 8.3% | 4 | 1 |

三条结论：

1. **我们的链路比裸模型好**：A 10.0% 对 BFCL 的 23.75%（那里是单工具、无提示词）。把工具藏起来
   （A/B/D）比全塞（C）好，C 的错法也最"实"——5 条真去抓网页。
2. **"提示词在推"只对 `recall_session` 成立**。A 比 B 多 4 题，全是用 `recall_session` 查会话历史，
   而 `SYSTEM_SUFFIX` 里那句「例如『帮我找之前聊过的内容』『打开这个网页』」正好把这两个动作教给了
   模型——这是能指认的因果。但**按"真去执行"算，A 与 B 都是 2 条**，提示词没有让模型更多地去抓网页
   或跑命令。上一轮我说"提示词在跟 irrelevance 对冲"，只对了一半。
3. **单候选不比 5 候选好**：D 8.3% 对 A 10.0%，差 1 题，在噪声里。

所以第 3 批真正值得改的不是"加一句别乱调"，而是**换掉提示词里那两个示例**——它们在教模型
"遇到含糊的话就去查历史"。这件事要等票 22 之后单独裁（改的是 `ToolSearch.SYSTEM_SUFFIX`，
影响所有部署）。

判什么行为：

| 判什么行为 | 用例 | 先红的样子 |
| --- | --- | --- |
| 这轮只调检索工具、没调业务工具时，判"没错" | `SelectionIrrelevanceTest#aSearchOnlyTurnIsNotAWrongCall` | 把 `toolSearchTool` 也算进去，正确流程被判成乱调 |
| 明说"做不了"时，判"对" | 同上，用固定模型（不花 key） | — |
| 四组的判定表能出 | `run.sh` 跑完 `report.md` 有四行 | 某组没产出 jsonl，表格少一行而没人发现 |

⏳ 四组之间的**差异是否显著**（60 条、每组差 3 题以内算噪声）留到跑完再写，不预先承诺。

**第 3 批：才轮到改**

候选动作（按预期收益排）：`SYSTEM_SUFFIX` 加一句"没有对应能力就直说，不要拿相邻的工具凑"；
工具描述补"不适用于…"；`mode` 的默认值调整。每一条都要拿第 2 批的基线做 A/B。

## TDD 用例清单

| 判什么行为 | 用例 | 先红的样子 | 结果 |
| --- | --- | --- | --- |
| 检索不算乱调、业务工具被调才算 | `SelectionIrrelevanceTest#aSearchOnlyTurnIsNotAWrongCall` | 把 `toolSearchTool` 或 `AskUserQuestionTool` 算进判据，正确流程被判成乱调 | ✅ |
| 四组各出一份 jsonl | `SelectionIrrelevanceTest#measureGroups` | 某组没产出，`summary.csv` 少一行而没人发现 | ✅ 四组各 60 行 |
| 每题独立计数 | 同上（第一次跑时踩过） | 不清零的话从第一次调用的那题起，后面每题都显示同一批工具名 | ✅ 修后四组各不相同 |

## 缺口

- **模型会编工具名，整轮直接抛**：跑到过
  `No ToolCallback found for tool name: AskUserQuestion`（目录里是 `AskUserQuestionTool`），
  `DefaultToolCallingManager` 抛 `IllegalStateException`。评测里记进 `error` 继续跑，
  但**生产上这就是一次 500**。要不要在工具循环里兜住（改成"把这个名字当成不存在，让模型重来"
  或"直接回一句做不到"），是另一张票的事——它会改变失败语义。
- **`recall_session` 是争议项**：meta / opinion 类题上它去查历史不算完全乱来。统计里单列了，
  没混进"明确乱调"。题库要不要把这类题剔掉，等第 3 批定（剔掉后 60 条里少 12 条，一格变 2.1pt）。
- **单次跑、无重复**：A 与 B 差 4 题（6.7pt）方向明确，但没跑第二遍。要当结论用之前，
  至少重复一次看是否复现。
- **60 条一格 1.67pt**，比 BFCL 的 1–2 题噪声细，但仍不足以判 1–2 题的差异。

## 撤回：L2 与 `eval/tools/selection` 已删（2026-11-26 22:4x）

用户：「我感觉好像没有要求去测试 eval/tools/selection……把解决问题中心放到 irrelevance 达标上面」
「不用钻牛角尖在闲聊这个点上，核心点不应该是大模型乱调用了工具吗」。

判断：这套端到端评测是我自己加的，不在授权范围内。**已删**：`eval/tools/selection/`（题库、
README、产物）、`SelectionIrrelevanceTest`、`.gitignore` 里那一行。上面第 2 批的设计与四组数据
**保留为历史记录，不再作为计划**（数据本身是真的：A 10.0% / B 3.3% / C 11.7% / D 8.3%，
但它已经不是本票的下一步）。

重心收回到一句话：**没有合适的工具时，模型会不会硬凑一个**。这句话有两个可动的地方，互不替代：

| 可动的地方 | 现状 | 谁来改 | 能不能让 irrelevance ≥90 |
| --- | --- | --- | --- |
| 模型 / 调用形态 | FC 76.25%；同模型 prompt 形态 88.75% | 选型（要新 key） | **能**，唯一不动评测条件的路 |
| 我们给的候选 | 向量翼下限 0.2（框架写死）、Lucene 0.25，几乎什么 query 都能拿到 5 个候选 | 我们的代码（见票 25） | 不能（BFCL 不经我们的链路），但直接决定线上"有没有东西可凑" |
