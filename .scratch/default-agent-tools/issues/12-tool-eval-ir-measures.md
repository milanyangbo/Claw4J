# 票 12：工具检索评测改走第三方指标——Java 只导出，`ir_measures` 算分

**Status:** open

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
./eval/tools/run.sh                      # 全部配置，出对照表
./eval/tools/run.sh --configs hybrid-0.0 # 只跑一档
./eval/tools/run.sh --from-report        # 不重跑导出，只按当前门槛重算
```

与 RAG 侧的三处不同，都是"更简单"：

| | `eval/rag` | 本票 |
| --- | --- | --- |
| 环境变量 | `DEEPSEEK_API_KEY` + `QWEN_API_KEY`，缺了退出 2 | **一个都不要**：指标是纯集合运算 |
| 成本 | L2 一次约 1 元 | 零 |
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
- `no-search` 两题：候选数必须为 0，否则记失败；
- 判定分三层，照 `run_ragas.py`：**绝对门**（`R@5` 不低于基线 − `TOLERANCE`）、
  **相对门**（只跟 `baseline.json` 比）、**分档只显示不判定**；
- 退出码 0/1/2：0 全过、1 有不达标/回归、2 环境没搭起来或 `baseline.json` 缺失。

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
| run 的 rank 就是检索返回的顺序 | `ToolRetrievalExportTest#runKeepsSearchOrder` | 按工具名排序、或按分数重排时，第二行的 docno 与 `search` 的第二名不一致，断言红 |
| qrels 只含有 gold 的题，`no-search` 不进 | `ToolRetrievalExportTest#qrelsSkipsZeroCandidateQuestions` | 把 20 题全写进 qrels 时，行数对不上（应为 18 题 × 各 gold 数），断言红 |
| 零候选题单独落一份，带实际候选数 | `ToolRetrievalExportTest#zeroCandidateQuestionsGoToTheirOwnFile` | 只写 qrels 不写这份时，`no-search` 那两题在 Python 侧无据可判，断言红 |
| 缺 `baseline.json` 时退出 2，不是 0 | `eval/tools/run.sh`（脚本级，**不进 Java 门禁**） | 把缺失当"无基线、全过"时，退出码为 0，断言红 |

第四条是脚本行为，写进票面是为了记住它必须被验；验法是一条 shell 断言（`./eval/tools/run.sh;
test $? -eq 2`），跟 `check-ticket-cases.sh` 一样属于 `scripts/` 侧，不塞进 JUnit。

## 缺口（本票不做）

- **ToolRet 数据集**：只能借题面与 nDCG 口径，不能拿它跑我们的索引（它不吃 run）。
- **选择层**：模型拿到候选后选不选对、参数对不对（BFCL v4，`pip install bfcl` +
  `bfcl evaluate`）。那是另一张票，要真模型与执行环境。
- **20 题太小**：这一票换的是"指标由谁算"，不是"题够不够"。加厚 `zh-en` 是另一件事。
- **统计显著性**：题量到几十条以上再谈，现在算 p 值没有意义。

## 收票时要改的文档

- `eval/tools/README.md`：跑法、产物、三层门槛、与 `eval/rag` 的对照。
- `README-capability-tests.md` B9：把"判据用 `eval/tools` 那张表"改成"`./eval/tools/run.sh`"。
- 票 09 的「缺口」里"指标实现自研"一句，指向本票。
