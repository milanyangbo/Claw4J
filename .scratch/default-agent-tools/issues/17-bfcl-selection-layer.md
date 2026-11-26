# 票 17：选择层评测——用 BFCL 的开源评测器测"选对没有"

**Status:** resolved（2026-11-26，两档全量跑通，基线已钉）

> 依赖票 12 / 15（检索层与它的报告）、票 14（目录里要有外部系统工具，题目才像真的）。

## 由来

现有评测只测**检索层**：一句话进来，前 5 个候选里有没有那个工具。它答不了下面这些：

- 候选给了 5 个，模型挑了哪个？挑错时是"长得像"的那个吗？
- 参数填对没有（路径少了前缀、仓库名写成 URL）？
- 本来**不该调**工具的时候（"谢谢，明白了"），它会不会硬调一个？

这三件是`选择层`，也是用户线上真正会骂人的那种错。用户裁决："**可以做，但是也要用开源框架去评测**"。

## 裁决

无需 ADR：只加评测通道，不改线上行为。

## 用哪个框架：BFCL，但先做第 0 步

Berkeley Function Calling Leaderboard（`bfcl-eval`，Python）是目前唯一把"选对/参数对/不该调"
三件事都定义清楚的公开评测器：AST 比对参数、`irrelevance` 类别专门测"该不该调用"。

**第 0 步（2026-11-26 已做，结论在下面）**

核实结果，逐条对上面三个问题：

1. **包名对得上**：PyPI 上是 `bfcl-eval`（2026.3.23，要求 Python ≥3.10），仓库在
   `ShishirPatil/gorilla/berkeley-function-call-leaderboard`。子命令是 `bfcl generate` 与
   `bfcl evaluate`，类别要写 `simple_python`（V4 把 `simple` 改名了）、`irrelevance`；
   只跑子集时评估要加 `--partial-eval`。
2. **没有 `--register-handler`**。CLI 只认已经注册的模型名；加新模型是**改它的源码**：
   `bfcl_eval/constants/model_config.py` 里加一条 `ModelConfig`、`supported_models.py` 里加进
   支持列表，handler 若能复用 `OpenAICompletionsHandler` 就不用自己写类。
   另一条路是 `.env` 里的 `REMOTE_OPENAI_BASE_URL` + `REMOTE_OPENAI_API_KEY`（给"已经在跑的
   OpenAI 兼容端点"用的）——**这正是我们的情况**，但它仍然要求模型名先注册，所以还是得打那两个补丁。
3. **成本没量**：得先装起来、注册、跑一次 `--run-ids` 的十几条才知道。**没量就不写数**。

**用户裁决（2026-11-26）：走方案 ①——装 + 本地补丁，报告可以称"用 BFCL 评测"。**
补丁单独放（`eval/tools/bfcl/patch/`），不 fork 整个仓库、不改我们的构建——
理由与 `eval/rag` 引 Ragas 一致：第三方是评测工具，不是我们的依赖。

**实际做下去发现第 0 步的估计有一半是错的**（据实记）：

- **注册项不用加**。BFCL 已经注册了 `DeepSeek-V3.2-Exp` / `-FC` / `-thinking` 三个名字，
  `model_name` 都是 `deepseek-chat`，`DeepSeekAPIHandler` 的 `base_url` 硬编码
  `https://api.deepseek.com`、key 读 `DEEPSEEK_API_KEY` —— 正是我们的情况。
  所以 `model_config.py` 与 `supported_models.py` **一行都不用改**。
- **但补丁还是要打，位置和第 0 步猜的不一样**。`OpenAICompletionsHandler.__init__`
  无条件 `OpenAI(**self._build_client_kwargs())`，而那个方法只认 `OPENAI_API_KEY`；
  `DeepSeekAPIHandler` 是先调父类、再换自己的 client。于是没有 OpenAI key 的机器上，
  构造 DeepSeek handler 在父类那一步就抛 `OpenAIError: Missing credentials`，
  永远走不到后面那行覆写。补丁就补这一处（父类缺 key 且是 DeepSeek 注册项时用
  `DEEPSEEK_API_KEY` 顶上），十几行，见 `patch/__init__.py`。

## 评什么（第一版只做两档）

| 类别 | 测什么 | 为什么先做它 |
| --- | --- | --- |
| `simple_python` | 单工具、参数简单：选对 + 参数对 | 最能反映"工具名与描述写得好不好" |
| `irrelevance` | 不该调用时会不会老实不调 | 对应我们 `no-search` 那一档，但判的是**模型的决策**而不是索引 |

（V4 把 `simple` 改名成 `simple_python` / `simple_java` / `simple_javascript`，
Java 那一档看着更"对口"，但那是给 Java 函数签名的写法，与"工具选择"无关，第一版只做 python。）

`multiple` / `parallel` / `multi-turn` 留缺口：它们要真多轮执行环境，与"选工具"不是同一件事。

## 数据从哪来

BFCL 自带题库，**不掺我们的工具集**——掺进去就不是"与公开数字可比"的那个东西了。
我们自己那 24 个工具的选择层评测是另一件事（缺口里记着）。

## TDD 用例清单

| 判什么行为 | 用例 | 先红的样子 | 实测 |
| --- | --- | --- | --- |
| 模型的端点能被 BFCL 叫起来 | `./eval/tools/bfcl/run.sh --smoke`（6 条，十来秒） | 不打补丁时 `generate` 直接抛 `OpenAIError: Missing credentials` | 打完补丁 6 条跑通（simple 3/3、irrelevance 2/3，1 秒/条） |
| 结果落成一份与检索层同构的报告 | 产出 `reports/bfcl.json` + `reports/report.md`（指标释义、错因释义、怎么算达标都在里面） | 只打印到 stdout 时没有基线可比 | `--from-score` 重建两份产物 |
| 两路数必须一致 | 逐条明细数出来的对错 == 汇总行给的数 | 结果文件被部分覆盖时两路会打架 | 640 条上一致（382+18=400、185+55=240） |
| 不该调用时调了要单独计 | 报告里 `irrelevance` 单列，不与 `simple_python` 平均 | 混成总分时"老实拒调"被工具选对率盖住 | 两档各自判，各自点名 |
| 冒烟不污染正式结果 | `--smoke` 用独立的 `smoke/` 根目录，且 `--no-baseline` | 6 条结果盖掉同名题，下一轮全量分数悄悄变 | 首版正是踩了这个坑（`BFCL_PROJECT_ROOT` 用了相对路径，产物落到套娃目录），已改绝对路径 |
| 缺 key 时退出 2，不是跑出 0 分 | 同 `eval/tools/retrieval/run.sh` 的口径 | 没配 key 时全 0 分，看着像"模型全错" | 手工验过（清空变量后 exit 2） |

## 复核（2026-11-26）

```bash
./eval/tools/bfcl/run.sh --smoke          # 6 条，约 12 秒
./eval/tools/bfcl/run.sh                  # 全量 640 条，8 线程 1 分半
./eval/tools/bfcl/run.sh --from-score     # 不调模型，只重算分与报告
```

实测（`DeepSeek-V3.2-Exp-FC`，`deepseek-chat`，temperature 0.001）：

| 类别 | 准确率 | 判对/题量 | 错的长什么样 |
| --- | --- | --- | --- |
| `simple_python` | 95.50% | 382 / 400 | 18 条，5 类：`value_error:string` 6、`type_error:nested` 4、`value_error:list/tuple` 4、`value_error:others` 3、`simple_function_checker:wrong_count` 1 |
| `irrelevance` | 77.08% | 185 / 240 | 55 条，**全是** `irrelevance_error:decoder_success`——本该一个都不调，却解出了合法调用 |

用量（`result/*.json` 里的计数字段，不是估的）：640 条共 245,556 输入 token + 75,316 输出 token。
换算成钱要看当时的价目表，这里只记 token。

`irrelevance` 那 55 条统一是"硬凑工具"，是这次最值得记的一条：模型不是不会调，是**太爱调**。

## 缺口（本票不做）

- **不评我们自己的工具集**：BFCL 的题库是它自己的工具。要评"我们的 24 个工具里选对没有"，
  得自己造题，那是另一张票（数据来自票 12 的 38 道题，把 gold 当"唯一正确答案"）。
- **不进 CI**：要真模型、要花钱、结果受模型版本影响。与 `-Peval` 同一条规矩：手动跑。
- **不做多轮与并行调用**：见上。
- **不是官方榜单分数**：只跑了两档、用了 `--partial-eval`，其余类别（multiple / parallel /
  live_* / multi_turn / memory / web_search）都没跑。要说"BFCL 上多少分"，得先把它们跑完，
  而 multi_turn 那几档要真执行环境（Docker 里的多轮沙箱），不是加个参数就能开。
- **补丁要跟着上游走**：`patch/__init__.py` 是运行时 monkeypatch。升级 `bfcl-eval` 后先跑
  `--smoke`；能直接跑通说明上游修了，补丁可以删。
- **模型版本会漂**：`deepseek-chat` 指向的权重由服务方决定，基线只能跟"同一天跑的"比。
