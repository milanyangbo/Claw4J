# 票 12：工具检索评测改走第三方指标——Java 只导出，`ir_measures` 算分

**Status:** resolved

> 依赖票 09（金标与自研指标）与 `eval/rag`（同构的先例）。

## 由来

用户 2026-11-26 指出仓库里 `eval/rag/run_ragas.py` **就是用 Ragas 算分的**
（`ragas==0.4.4`，L1 用 `IDBasedContextRecall/Precision`），而票 09 之前的说法是"工具侧自研
Recall@k 就够"。那条说法的前提（"RAG 侧也没引第三方"）是错的，本节留作记录：**这个仓库的
既有方针是"Java 只导出、Python 侧用第三方算指标"**，工具侧没有理由另立一套。

票 09 的自研指标（`ToolRetrievalEval`）不是白做的：它跑得快、不依赖 Python，`ToolGoldTest`
还在门禁里。但**对外口径与指标实现**该与 RAG 侧一致，交给第三方。

`tool-retrieval-benchmark`（ToolRet，ACL 2025）**不进来**：它是 Python 数据集 + 评"IR 模型"的
脚本，入口是 `eval_retrieval(model_name=…)`，自己加载 embedding、对 43k 工具语料检索，
**不接受外部 run 文件**。只能借它的 nDCG@10 口径与题面构造法。

## 裁决

无需 ADR：评测设施，不改任何线上行为，也不进 `./mvnw` 的默认构建与 `verify.sh`。

## 形态：照 `eval/rag` 抄

> 下面这棵树是建票时的样子。2026-11-26 起整棵挪到 `eval/tools/retrieval/`，
> 与票 17 的 `eval/tools/bfcl/` 平级；文件名没变。

```
eval/tools/
  run.sh                  # 唯一入口：Java 导出 → venv → 算分 → 报告
  run_ir.py               # 读 run/qrels，调 ir_measures，出 score.json + report.md
  requirements.txt        # 锁 ir-measures 的版本（与 ragas==0.4.4 同一个理由）
  baseline.json           # 上次被接受的分数（入库）；reports/ 不入库
  gold.yaml               # 票 09 的 20 题，不动
  reports/                # qrels.txt / <config>.run / no-search.jsonl / 产物
```

命令：

```bash
./eval/tools/retrieval/run.sh                      # 全部配置，出对照表
./eval/tools/retrieval/run.sh --configs hybrid-0.0 # 只跑一档
./eval/tools/retrieval/run.sh --from-report        # 不重跑导出，只按当前门槛重算
```

与 RAG 侧的三处不同，都是"更简单"：

| | `eval/rag` | 本票 |
| --- | --- | --- |
| 环境变量 | `DEEPSEEK_API_KEY` + `QWEN_API_KEY`，缺了退出 2 | **只要 `QWEN_API_KEY`**（导出时 hybrid 三档要真 embedding）；不要 judge |
| 成本 | L2 一次约 1 元 | 零（embedding 那点调用算在导出里） |
| 名次类指标 | Ragas 的 `IDBased*` 是集合型，给不了名次，MRR 只能自算 | `ir_measures` 原生给 `RR` / `nDCG@10` |

## Java 侧只做一件事：导出

新增 `ToolRetrievalExportTest`（`@Tag("eval")`，与 `RetrievalExportTest` 同形）：
起容器、按 5 档建索引、跑 20 题，落三个文件：

1. `reports/qrels.txt`：`<qid> 0 <tool> 1`。**只写有 gold 工具的题**（18 条）。
2. `reports/<config>.run`：`<qid> Q0 <tool> <rank> <score> <runid>`，rank 从 1 起，
   顺序**就是 `ToolSearchResponse` 给的顺序**，不重排、不按名字排。
3. `reports/no-search.jsonl`：`{"qid": "...", "returned": 3}`。`no-search` 那两题走这里——
   TREC 表达不了"期望零候选"（qrels 里没有相关文档的 qid 会被跳过或记 0），它必须单独判。

qid 用 `gold.yaml` 的 `id`（`tools-glob-md` 这种），docno 用工具名。两边都是稳定标识符，
不需要映射表。

## Python 侧：算分 + 三层门槛

`run_ir.py` 调 `ir_measures` 的 CLI 或 Python API，产出：

- 每档 `R@5` / `RR` / `nDCG@10`（`R@1`/`R@3` 保留，与票 09 的表能对上）；
- `no-search` 两题按**零候选率**单独报，明细（哪几题、给了谁）每轮打印；
- 判定分**两层**（不是 RAG 侧的三层）：**回归门**（每档 `R@5` 不低于基线 − `TOLERANCE=0.05`）
  + **零候选率的回归门**（基线记当前值，掉下去才算回归）。没有"分档（差/良/好）"：
  RAG 侧有那一层是因为 judge 会漂；这边全是确定性指标，分档只会把当前噪声固化成标准。
- 退出码 0/1/2：0 全过、1 有回归、2 环境没搭起来或 `baseline.json` 缺失。

**`baseline.json` 缺失必须是 2，不是 0**：与 `retrieval-score.json` 不同，这一份是"标准"，
没有标准时"全过"是个假结论。

## 一致性自检（这条是本票的重点）

`run_ir.py --cross-check`：用票 09 的自研公式重算一遍 `R@5` / `MRR`，与 `ir_measures` 的结果
逐档比，差 > 1e-9 就打印两边数字并退出 1。

理由：现在有两套指标实现，**它们对不上时，第一个问题是"信谁"**。这条自检把"对不上"变成
当场可见的错误，而不是两个报告各说一套、过两周才发现。

## TDD 用例清单

`claw4j-eval` 现在 4 个测试文件 / 最大 11 个方法（上限 14 / 26），够放一个新文件。

| 判什么行为 | 用例 | 先红的样子 |
| --- | --- | --- |
| run 的 rank 就是检索返回的顺序 | ✅ `ToolRetrievalExportTest#runKeepsSearchOrder` | 把导出改成按名字排序 → 期望 `[Read…]`、实际 `[Bash, Glob, Grep, Read, TodoWrite]`，实测红 |
| qrels 只含有 gold 的题，`no-search` 不进 | ⏳ 已失去对象（2026-11-27 复核）：`no-search` 档随票 22 删掉，`ToolGold` 现在不允许空 `goldTools`，这道题不可能再出现。守它的用例 `qrelsSkipsZeroCandidateQuestions` 一并删除 | — |
| 零候选题单独落一份，带实际候选数 | ⏳ 同上：`no-search.jsonl` 与 `zeroCandidateQuestionsGoToTheirOwnFile` 都在票 22 删除。该项能力改由 BFCL `irrelevance` 判（票 23） | — |
| 缺 `baseline.json` 时退出 2，不是 0 | ⏳ 手动验过（移走 `baseline.json` → 退出码 2），**未脚本化** | 把缺失当"无基线、全过"时退出 0 |

第四条没做成脚本：要自动化它得再引一个 shell 测试入口，而这条判据一年也不会改一次。
手动验法写在这里，改 `run_ir.py` 的基线分支时照着重跑一遍。

## 缺口（本票不做）

- **ToolRet 数据集**：只能借题面与 nDCG 口径，不能拿它跑我们的索引（它不吃 run）。
- **选择层**：模型拿到候选后选不选对、参数对不对（BFCL v4，`pip install bfcl` +
  `bfcl evaluate`）。那是另一张票，要真模型与执行环境。
- **20 题太小**：这一票换的是"指标由谁算"，不是"题够不够"。加厚 `zh-en` 是另一件事。
- **统计显著性**：题量到几十条以上再谈，现在算 p 值没有意义。
- **向量档的排序抖动**（2026-11-26 查明）：不是 embedding 的问题（同一句三次逐位相同），
  是近分并列 + 框架 `VectorToolIndex` 的 `ORDER BY` 没有 tie-breaker。只影响 `R@1`/`R@3`/`RR`/`nDCG`
  的第 2 位小数，`R@5` 稳定，所以判定只盯 `R@5`。修法是自己实现向量检索并加 `, tool_name`，
  为这点抖动不值得；真要修是另一张票。

## 收票时要改的文档

- `eval/tools/retrieval/README.md`：跑法、产物、三层门槛、与 `eval/rag` 的对照。
- `README-capability-tests.md` B9：把"判据用 `eval/tools/retrieval` 那张表"改成"`./eval/tools/retrieval/run.sh`"。
- 票 09 的「缺口」里"指标实现自研"一句，指向本票。

## 落地时与票面不同的四处（据实改）

1. **环境变量要一把**，不是零把：`QWEN_API_KEY`——`hybrid` 那三档导出时要真 embedding。
   票面写"一个都不要"是漏了导出那一步。
2. **零候选不是硬判据，是回归门**。实测 `regex` 对「谢谢」给了 5 个候选
   （`tools-chitchat-thanks`），零候选率是 0.5 不是 1.0。把 1.0 当门槛，这张票一落地就是红的，
   而它红的原因是"检索器本来就有这个毛病"（票 09 记过），不是本票该修的。改成"基线记当前值、
   掉下去才算回归"，明细每轮照打。
3. **两套指标的分母不同**：第三方只算 qrels 里 18 题（有 gold 的），自研算 20 题
   （`no-search` 按"零候选 = 命中"计）。所以 `regex` 的 `R@1` 是 0.111（第三方）/ 0.150（自研），
   差的就是那两题。**不是算错**，`--cross-check` 在同分母下逐档一致（< 1e-09）。
4. **向量档不是逐位可复现**：同一份代码连跑三次，`hybrid-0.0` 的 `R@1` 是 0.333 / 0.389 / 0.389、
   `R@3` 是 0.556 / 0.500 / 0.444（漂两格），而 **`R@5` 三次都是 0.667**。所以回归门只盯 `R@5`
   ——这不是随手选的，是这一次实测定下来的；`R@1`/`R@3` 当观测项，别照着它改参数。

## 收票记录（2026-11-26）

- `./eval/tools/retrieval/run.sh` 全绿，退出 0；`--cross-check` 5 档一致。
- 基线（18 题进指标）：
  `hybrid-0.0` R@5 0.667 / nDCG@10 0.512；`regex` 0.500 / 0.325；零候选率 0.500（1/2）。
- 门禁不受影响：导出用例带 `@Tag("eval")`，`verify.sh` 的默认组排除它；
  产物目录已进 `.gitignore`（与 `eval/rag/reports` 同一条规矩），`baseline.json` 入库。
