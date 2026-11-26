# 工具选择评测（票 17）

回答一个问题：**候选工具给了之后，模型挑对没有、参数填对没有、不该调的时候老实没有。**

上一层（`eval/tools/retrieval/`）只管"前 5 个候选里有没有那个工具"。这一层用
[Berkeley Function Calling Leaderboard](https://github.com/ShishirPatil/gorilla/tree/main/berkeley-function-call-leaderboard)
（`bfcl-eval`）的题库与判据，测模型拿到候选之后的决策。

## 与检索层的分工

| | 谁算 | 花不花钱 | 回答的问题 |
| --- | --- | --- | --- |
| 检索层 `eval/tools/retrieval/run.sh` | `ir_measures` | 不花（只算 embedding） | 前 k 个候选里有没有对的那个 |
| 选择层（本目录） | BFCL 的 AST 判据 | 花（真模型，全量约 1 元） | 给了候选之后选对没有、该不该选 |

两层都过了，才谈得上"端到端能选对工具"。

## 跑

```bash
./eval/tools/bfcl/run.sh                     # 全量两档（400 + 240 条）
./eval/tools/bfcl/run.sh --smoke             # 6 条冒烟，十来秒
./eval/tools/bfcl/run.sh --from-score        # 不调模型，只重算分与报告
./eval/tools/bfcl/run.sh --update-baseline   # 确认改好了，把分数钉成新基线
```

- 唯一要的环境变量：`DEEPSEEK_API_KEY`。缺了退出 2（不是跑出 0 分）。
- 退出码：`0` 达标（绝对门与回归门都过）；`1` 有不达标或回归项；`2` 环境没搭起来
  （缺 key / 缺依赖）。**现在跑是 `1`**——`irrelevance` 没到 90%，见下面「门槛」。
- **不进 CI**：要真模型、要花钱、结果受模型版本影响。与 `-Peval` 同一条规矩，手动跑。

## 测哪两档

| 类别 | 题量 | 测什么 |
| --- | --- | --- |
| `simple_python` | 400 | 单工具、参数简单：选对 + 参数填对（AST 比对） |
| `irrelevance` | 240 | 工具里没有能做的，正确答案是一个都不调 |

`multiple` / `parallel` / `multi-turn` 留缺口：它们要真多轮执行环境，与"选工具"不是同一件事。

**题库是 BFCL 自带的，不掺我们那 24 个工具。** 掺进去就没有公开数字可比了。
"评我们自己的工具集"是另一张票（缺口里记着）。

## 产物

| 文件 | 谁写 | 内容 |
| --- | --- | --- |
| `result/<model>/non_live/*_result.json` | `bfcl generate` | 模型原始输出（JSONL），不入库 |
| `score/<model>/non_live/*_score.json` | `bfcl evaluate` | 首行汇总 + 逐条错的明细，不入库 |
| `score/data_non_live.csv` | `bfcl evaluate` | 排行榜口径那张表，不入库 |
| `reports/report.md` | `report.py` | 人读的报告：指标怎么算、错了的长什么样、怎么算达标，不入库 |
| `reports/bfcl.json` | `report.py` | 机器读的分数，不入库 |
| `baseline.json` | `report.py --update-baseline` | 上次被接受的版本，**入库** |

`report.py` **不重新判对错**——对错是 BFCL 的 AST 比对说了算。它只做两路互校
（逐条明细数出来的对错必须等于汇总行）、判达标与回归、写报告。

## 门槛：两根，管两件不同的事

| 门 | 回答什么 | 现在的线 |
| --- | --- | --- |
| **绝对门** | 够不够好 | `simple_python ≥ 95%`、`irrelevance ≥ 90%` |
| **回归门** | 有没有变差 | 不低于 `baseline.json` 减 `TOLERANCE`（0.03） |

两者独立：过了绝对门但比基线跌超容差 → 回归；本来就低于合格线 → 不达标。「上次也这样」换不来
达标，`--update-baseline` 也换不来（它只跳过回归那一半，绝对门照判）。

合格线的出处：`simple_python` 95% = 20 次错 1 次；`irrelevance` 90% = 十次闲聊允许一次乱调。
都是从用户可感知的错误频率推的，**不是 BFCL 官方标准**——官方榜给分数与排名，不给及格线
（`bfcl_eval` 全包没有 threshold 概念，核实过）。

**现在 `irrelevance` 是 77.08%，未达标。** 这一档的错法高度集中：55 条全是一种
（`irrelevance_error:decoder_success`，本来不该调却解出了合法调用）。它和检索层的
`no-search` 零候选率 0.50 是同一个病在两个环节的表现。

## 已知环境坑（都已在脚本里绕过，但换机器可能重现）

1. **必须是 Python 3.10–3.12**。`bfcl-eval` 把 numpy 锁在 1.26.4，那个版本没有 3.13 的
   wheel，装的时候会现场编译 C 代码然后失败。`run.sh` 会自己找 3.12。
2. **`tempfile` 探测会失败**。本机如果装了会拦截"随机命名新建路径"的钩子，
   `tempfile.gettempdir()` 会报 `No usable temporary directory found`，症状是每行输出前
   刷一句 `Error in sitecustomize`。`boot.py` 直接给 `tempfile.tempdir` 赋值绕过探测。
3. **`pip` 也受同一条影响**。所以 `run.sh` 装依赖时用 `runpy` 引导 pip，并且**不看 pip 的
   退出码**（它清理临时目录会被拦，带着 WARNING 非 0 退出，但包其实装好了）——
   判据改成"能不能 `import bfcl_eval`"。
4. **`soundfile` 是上游漏声明的依赖**：`qwen-agent` import 它，但 `bfcl-eval` 的
   `install_requires` 里没有。缺了会在 `from bfcl_eval.__main__ import cli` 就炸。
   已写进 `requirements.txt`。

## 那个补丁是干什么的

`patch/__init__.py`。上游的 `OpenAICompletionsHandler.__init__` 无条件建 OpenAI client，
只认 `OPENAI_API_KEY`；而 `DeepSeekAPIHandler` 是先调父类、再换自己的 client。
于是没有 OpenAI key 的机器上，构造 DeepSeek handler 会在父类那一步就抛
`OpenAIError: Missing credentials`，永远走不到后面那行覆写。

补丁只补这一处：父类缺 `api_key` 且确实是 DeepSeek 注册项时，用 `DEEPSEEK_API_KEY` 顶上。
**升级 `bfcl-eval` 后要先看一眼还需不需要**——判据是 `--smoke` 能不能直接跑通；
不需要了就把 `patch/` 删掉。`boot.py` 在目标函数消失时会打印提示，不会静默通过。

## 现在这一轮的数字

2026-11-26，`DeepSeek-V3.2-Exp-FC`（`deepseek-chat`，FC 通道）：

| 类别 | 准确率 | 判对/题量 |
| --- | --- | --- |
| `simple_python` | 95.50% | 382 / 400 |
| `irrelevance` | 77.08% | 185 / 240 |

`irrelevance` 那 55 条错**全是同一种**：`irrelevance_error:decoder_success` ——
本该一个都不调，却解出了一个合法的函数调用。这是"模型爱硬凑工具"的直接证据。
