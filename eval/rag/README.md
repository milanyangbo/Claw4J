# RAG 评测

一条命令量清楚：**该找到的资料有没有找到，回答有没有照着资料说。**

```bash
./eval/rag/run.sh
```

跑完在 `eval/rag/reports/report.md` 看结论。56 条实测约 34 分钟，4–5 元（L2 占大头）。

## 它量两件事

| 层 | 在回答什么 | 指标 | 要模型 | 成本 |
| --- | --- | --- | --- | --- |
| **L1 检索** | 该被找到的段，进没进上下文、排第几 | Recall@k / Precision@k | 不要 | 0 |
| **L2 生成** | 给了这些上下文，回答有没有超出它、有没有漏读 | faithfulness / context_recall / context_precision | 要 judge | 约 4–5 元 |

分开的理由：端到端只给一个"答错了"，分不清是**没检索到**、**检索到了没用上**、还是**模型自己编的**。
L1 全绿而 L2 低，问题在提示词或模型；L1 就低，后面两项不用看。

## 怎么跑

| 命令 | 用途 | 耗时与花费 |
| --- | --- | --- |
| `./eval/rag/run.sh` | L1 + L2，完整一轮 | 约 34 分钟，4–5 元 |
| `./eval/rag/run.sh --skip-judge` | 只跑 L1。调切块、top-k、重排时用这个 | 几分钟，无 judge 费用 |
| `./eval/rag/run.sh --cutoffs 3,5,8,15` | 指定算哪几个 k | 同上 |
| `./eval/rag/run.sh --update-baseline` | 确认这次改好了，把新基线钉下来 | 同你跑的那一轮 |
| `./eval/rag/run.sh --from-score eval/rag/reports/score.json` | 不重新检索，只按当前门槛重算判定与报告 | 秒级，0 |

`run.sh` 做的四步：加载 `.env` → 查 `DEEPSEEK_API_KEY` / `QWEN_API_KEY` → Java 导出每条问题的检索结果 → Python 算分。
`--from-score` 会跳过第三步——那步要起容器、真检索、真回答，跳过才谈得上"零成本"。

退出码：全部通过 `0`，有不达标 / 回归 / 存疑 `1`。可以直接挂到别的地方判。

## 结果在哪、怎么看

三个去处：

- 屏幕上的判定表（跑完就打）
- `eval/rag/reports/report.md` —— 人读，带诊断
- `eval/rag/reports/score.json` —— 机器读

判定表长这样：

| 指标 | 当前 | 判定口径 | 档位 | 结论 |
| --- | --- | --- | --- | --- |
| Recall@5 | 0.920 | ≥0.90 | — | 达标 |
| 召回完整性@5 | 51/56 | 诊断项 | — | — |
| faithfulness | 0.899 | 基线 0.899 ±0.05 | 好 | 稳定 |
| context_recall | 0.915 | 基线 0.915 ±0.05 | 好 | 稳定 |
| context_precision | 0.875 | 基线 0.875 ±0.05 | 好 | 稳定 |
| judge 失败 | 0 | =0 | — | 达标 |

**结论有七种**：

- **达标** —— 过了绝对门槛（只给确定性指标）。
- **不达标** —— 没过。
- **稳定** —— 与基线差在 0.05 内。这个区间里改没改都一样，别当优化成果。
- **提升** —— 比基线涨超 0.05。确认是改好了，用 `--update-baseline` 把它记下来。
- **回归** —— 比基线跌超 0.05。这是真退步，去查。
- **存疑** —— judge 有失败，分数只覆盖了一部分。**这时别看均值，先看 `judge 失败` 那行**。
- **基准** —— 第一次跑，或者题集换过、没有可比的基线，这次只把分数记下来。

**两类判定不能混着看**：`Recall@k` 与 `judge 失败` 同样的输入永远同样的分，所以配绝对门槛；
L2 三项要 judge，分数会随模型版本和温度漂，所以只跟自己的基线比。

## 指标怎么读

| 指标 | 在回答什么 | 低了说明什么 |
| --- | --- | --- |
| Recall@k | 该被找到的段，有多少进了前 k 段 | 检索漏了。这一项低，后面所有指标都在测一个没料可用的模型。k 超过产品 `top-k`（5）时，量的是候选池里的名次，不是模型真正看到的那几段 |
| 召回完整性@k | 有几条题是一段不漏地召回的 | 看长尾。均值会把「某条只召到一半」抹平，所以它是诊断项，不是门槛 |
| Precision@k | 前 k 段里 gold 占多少 | 上下文夹带噪声。56 题里 54 题只有 1 段 gold，分母是 k、分子最多 1 → k=5 的上限只有 **0.207**，实测 0.189 已占 91%。它基本就是 `R@k / k`，只在多段 gold 的题上才有独立信息，所以只看趋势 |
| faithfulness | 回答里每条主张能否被上下文支持 | 在编。要么写进了常识，要么上下文被截断丢了一段 |
| context_recall | 参考答案的要点被上下文覆盖了多少 | 检索漏段（回到 Recall 看），或 reference 写得比文档还宽 |
| context_precision | 上下文里相关段的比例与顺序 | 夹带噪声，或 gold 被排到了后面 |
| judge 失败 | 有多少条因为 judge 调用失败算不出来 | 不是分数，是体检。有失败时剩下的均值不可信 |

**差 / 良 / 好怎么分**：

| 指标 | 差 | 良 | 好 |
| --- | --- | --- | --- |
| faithfulness | <0.70 | 0.70–0.85 | ≥0.85 |
| context_recall | <0.70 | 0.70–0.80 | ≥0.80 |
| context_precision | <0.60 | 0.60–0.75 | ≥0.75 |

- **好**：这层不用动，去改别处。
- **良**：能用，但该改。
- **差**：这层不合格，先修它——单项差的时候，别的指标再高也是假象。

这三档是外部经验值，**不参与判定**，只帮人读数字。Ragas 官方文档只给「0 到 1，越高越好」，
不给合格线；各家写的好线也不一致（faithfulness 从 0.85 到 0.95 都有人写），这里取的是多数派下限。

## 分数不好时，按这个顺序查

1. **先看 `judge 失败` 是不是 0**。不是 0 就停下，剩下的均值不可信。
2. **看 Recall@k 随 k 变大有没有变化**。不变 → 那段压根不在候选里 → 查切块（是否切坏、劈词）、
   查两路检索（向量那路的 embedding、全文那路的分词）。
3. **看「没召全」那几条缺的段排第几**。排在第 k 位之外 = top-k 太小或重排没顶上来；
   压根不在列表 = 检索没打中。
4. **看 context_precision**。低 → 上下文塞了无关段 → 降 top-k、提 `min-similarity`、让重排把 gold 顶前面。
   别一上来就调相似度阈值，它会连带砍掉召回。
5. **最后看 faithfulness**。低 → 模型在编 → 先确认上下文没被截断（长文档 + 多段容易超窗），
   再改提示词要求"只依据给定资料"。

三条组合起来最有用：

- `context_recall` 高 + `context_precision` 低 = 该给的一段没漏，但夹带很多无关段 → 动 top-k 与重排。
- `faithfulness` 高 + `context_recall` 低 = 照着资料说，但资料本身不全 → 问题在检索，不在模型。
- `Recall@k` 随 k 变大不变 = 调大 k 没用，得查切块与检索那一路。

上面第 2、3 步要落到"库里到底有什么"时，看下一节。

## 看库里的数据

查到最后总要落到一句：**库里到底存了什么、这句检索到底取到了哪几段。**

| 表 | 每行是什么 |
| --- | --- |
| `claw4j_rag.vector_store` | 一个块。`content` 正文、`metadata`（json：`path` / `index` / `source` / `content_hash`）、`embedding`（`vector(1024)`） |
| `claw4j_rag.source` | 一份文件。`content_hash`（增量索引靠它判断改没改）、`mtime` / `size` / `indexed_at` |
| `claw4j_rag.index_meta` | 索引的元信息 |

**两路检索查的是同一张表**：向量那路用 `embedding` 列（`PgVectorStore` 生成 `order by embedding <=> ?`），
全文那路用 `content` 列（`ilike`）。一张表是融合的前提——同一个块两路各命中一次时，
行 id 相同，才能合成一条、按 0.7 / 0.3 加权；分成两张表就会出现两条重复段落，各带一路的分。
`source` 与 `index_meta` 不参与检索，只记账。

**评测跑的那一次看不到库**：它跑在 Testcontainers 里，进程一退容器就没了。想看那一次的候选，
读 `eval/rag/reports/run.jsonl`——每条题都带着检索到的段 id 与正文，以及 gold 落在哪几段。
要连库查，得让应用自己起一次：`mvn -pl claw4j-app spring-boot:run -Dspring-boot.run.profiles=local`
会拉起 compose 里的 pgvector 并索引 `examples/knowledge`。

本机没装 `psql`，用容器里那个：

```bash
docker exec -it claw4j-postgres psql -U claw4j -d claw4j
```

库不在本机就直接 `psql "$CLAW4J_DB_URL"`（默认 `postgresql://localhost:5432/claw4j`，用户与密码都是 `claw4j`）。

库里是**上一次应用启动时索引的那份语料**。很久没起过应用、或者刚改过 `examples/knowledge`，
先看第 ⑤ 条的 `indexed_at`——比文档的修改时间早，说明查的是旧内容。

进去之后 `\dt claw4j_rag.*` 看有哪几张表，`\d claw4j_rag.vector_store` 看列与索引。

### 六条常用查询

**① 现在库里有多少块，每份文档切了几段**

```sql
select metadata->>'path' as doc, count(*) as chunks, sum(length(content)) as chars
from claw4j_rag.vector_store
group by 1
order by chunks desc, chars desc
limit 20;
```

一行都没有 = 没索引（应用没起过，或者 `claw4j.rag.enabled` 没开）。

**② 看某份文档切成了什么，块尾有没有把词劈开**

```sql
select (metadata->>'index')::int as chunk, length(content) as chars,
       left(content, 40) as head, right(content, 40) as tail
from claw4j_rag.vector_store
where metadata->>'path' = 'settlement.md'
order by chunk;
```

切块问题只能这样看：块尾断在半句话上、或者"万分之五"被切成"每日万"+"分之五"，
`head` / `tail` 一眼能看出来。

**③ 全文那一路：SQL 与命中次数**

这一路没有一条写死的 SQL，它按"词"的个数现拼。词是从问题里抽的：**整句 + 最多 4 个纯 ASCII 锚点**
（字母或数字开头、长度 ≥3，如 `SKU-2024`、`A-07`、`v1.2`）。问「SKU-2024 还剩多少」时，文档里
没有这七个连续的字，整句一条都命不中，靠锚点那一路才捞得回来。

等价、可直接跑的写法——**换问题只改 `values` 那行**：

```sql
with t(term) as (values ('退货'), ('退款'))
select v.metadata->>'path' as doc,
       (v.metadata->>'index')::int as chunk,
       sum((length(v.content) - length(replace(lower(v.content), lower(t.term), '')))
             / length(t.term)) as hits
from claw4j_rag.vector_store v
join t on v.content ilike '%' || t.term || '%'
where v.metadata->>'source' = 'docs'
group by 1, 2
order by hits desc, chunk
limit 10;
```

`hits` 是每个词「出现次数 ÷ 词长」再求和。除词长是为了让一次命中都算 1 分——不除的话，
长词命中一次就等于短词命中三次，那是拿词长当相关度。它同时是排序依据和交给融合的分数
（`0.7 × 向量 + 0.3 × 全文`，见 `WeightedScoreJoiner`）。

代码里那条是同一个式子，只是词写成绑定参数（`KeywordDocumentRetriever#sqlFor`）：

```sql
select id, content, metadata->>'path' as path, metadata->>'source' as source,
       metadata->>'index' as ordinal, metadata->>'content_hash' as hash,
       ((length(content) - length(replace(lower(content), lower(?), ''))) / length(?)
      + (length(content) - length(replace(lower(content), lower(?), ''))) / length(?)) as hits
from claw4j_rag.vector_store
where metadata->>'source' = ? and (content ilike ? escape '\' or content ilike ? escape '\')
order by hits desc, (metadata->>'index')::int
limit ?;
```

绑定顺序就是 `?` 出现的顺序：`词1, 词1, 词2, 词2, source, %词1%, %词2%, limit`。

这里的 `limit` 是**每路候选数**（`claw4j.rag.hybrid.candidates-per-route`，默认 15），不是最终的
`top-k`（默认 5）：两路各召回 15 条 → 融合 → 重排拿前 10 条换顺序 → 截到 5 段进提示词。
所以"关键词没命中"要分清是哪一层没中：SQL 一条都没有，还是 SQL 有但排在 15 名之后。

两个坑：

- **`escape '\'` 不能省**。`ilike` 的 `%` 与 `_` 是通配符：问"折扣率 5%"时不转义会变成
  `ilike '%5%%'`，匹配所有含 5 的块，而且不报错。代码里由 `escapeLike()` 处理，手写 SQL 得自己转，
  或者改用上面那条 `values` 版（它不拼 `%`，但词里带 `%` 时同样会出事）。包在 shell 双引号里跑
  （`psql -c "…"`）时写成 `escape '\\'`——反斜杠会被 shell 先吃一层。
- **中文不切分**。这一路只负责编号、型号、代码这类向量认不出的字面量；中文问句靠向量那一路。
  所以中文在 `hits` 上是 0 并不说明检索坏了，那是分工。

**④ 向量那一路：谁离这个块最近**

psql 里没法把一句中文变成向量（那要调 embedding 接口），所以查不了"这句话会召回什么"。
能验的是反过来的：拿一条已有块当探针，看它周围的邻居对不对。

```sql
with probe as (
  select embedding from claw4j_rag.vector_store
  where metadata->>'path' = 'after-sales.md' and (metadata->>'index')::int = 1
)
select v.metadata->>'path' as doc, (v.metadata->>'index')::int as chunk,
       round((1 - (v.embedding <=> p.embedding))::numeric, 4) as cosine
from claw4j_rag.vector_store v, probe p
order by v.embedding <=> p.embedding
limit 10;
```

`<=>` 是余弦**距离**，所以 `1 - 距离` 才是余弦相似度（探针自己那行必然是 1.0000）。
邻居里塞满同一份文档的其它段、却找不到相关的另一份，说明切块太碎或语料里确实没有。

**⑤ 索引是不是最新的**

```sql
select path, size, indexed_at from claw4j_rag.source order by indexed_at desc limit 10;
```

`indexed_at` 早于你改文档的时间 = 库里是旧内容，检索当然按旧的答。改完文档要重建索引就重启应用。

**⑥ 一次检索到底取到了什么**

不必连库，导出文件里就有：

```bash
python3 - <<'EOF'
import json
row = [json.loads(l) for l in open('eval/rag/reports/run.jsonl')][0]
print(row['id'], '|', row['question'])
for i, (cid, text) in enumerate(zip(row['retrieved_context_ids'], row['retrieved_contexts']), 1):
    print(f'  {i}. {cid}  {text[:60]}')
print('gold:', row['reference_context_ids'])
EOF
```

`retrieved_context_ids` 按名次排（重排之后、截断之前），`gold` 是答案真正所在的那几段——
两者一对比，"没召全"到底是排太后还是压根没进候选，立刻分得清。

两个限制：`metadata` 是 `json` 而不是 `jsonb`，`->>` 每次现解析，几百段无所谓，别拿它做大规模分析；
`content` 上的 trigram 索引（`ix_rag_vector_store_content_trgm`）只服务 `ilike`，按 `path` 过滤是全表筛。

## 常见报错

| 报错 | 含义 | 怎么办 |
| --- | --- | --- |
| `402 Insufficient Balance` | judge 账户没钱了 | 充值。最容易跑到一半断，那轮会变成「存疑」 |
| `401` | key 失效 | 换 key，或设 `EVAL_JUDGE_API_KEY` |
| `429` | 限流 | 把 `--concurrency` 降到 1 |
| `IncompleteOutputException` | 输出被 `max_tokens` 截断 | 调大 `run_ragas.py` 里 `make_judge` 的 `max_tokens` |
| `缺环境变量：…` | 没配 `.env` | 补 `DEEPSEEK_API_KEY` 与 `QWEN_API_KEY`。缺 key 是失败不是跳过（退出码 2） |

## 基线

`eval/rag/baseline.json` 记的是**上一次被接受的版本**（入库），相对判定就是拿它比的。

只有确认这次改好了才更新：

```bash
./eval/rag/run.sh --update-baseline
```

两条规矩：

- L2 任一项够不到「好」档时拒绝写入。基线定低了，之后怎么跑都是「稳定」，这道地板防的就是它。
- **换 judge 必须重建基线**。不同 judge 的分数不能横向比。

## 现在什么水平

下面是最近一次实跑的快照，随时会过时——**以 `reports/report.md` 为准**。

108 份语料 / 56 条题 / judge `deepseek-flash`：

| 指标 | 值 | 档位 |
| --- | --- | --- |
| Recall@3 / @5 / @8 | 0.866 / 0.920 / 0.920 | — |
| 召回完整性@5 | 51/56 | — |
| faithfulness | 0.899 | 好 |
| context_recall | 0.915 | 好 |
| context_precision | 0.875 | 好 |

三项 L2 比 12 条那轮（0.963 / 1.000 / 0.805）低，不是退步：**题换了**。新加的 44 条里有
无答案、口语、否定例外、多跳、会议纪要与政策冲突——这些本来就答得更难。跨题集比均值没有意义，
所以判定表里这三行标「基准」，不跟旧基线谈回归。

读这组数要知道两件事：

- **56 条题，一条约 1.8 个百分点**。能看出 4 个百分点级别的变化，再细就得靠显著性检验，现在没有。
- **judge 分数有抖动**，同一份数据连跑几次差 0.02–0.03 是常态。看变化要看 L1 那些确定性指标。

没召全的 5 条集中在四类：切块把词劈开（`rag-overdue-interest`，"万分之五"被块边界劈成
"每日万"+"分之五"）、无答案（`rag-noanswer-contact-name`）、口语与书面语的差距
（`rag-colloquial-late`、`rag-colloquial-white-line`）、会议纪要与政策的冲突
（`rag-meeting-return-10days`）。

## 相关文件

| 文件 | 作用 |
| --- | --- |
| `run.sh` | 唯一入口 |
| `gold.yaml` | 56 条题：问题 + 参考答案 + 答案所在原句（`gold` = 检验里的「金标准」，指不随实现变化的参照答案）。**改文档原文会让它失效**（会当场报错，不会静默给假分数） |
| `baseline.json` | 上次被接受的版本，入库 |
| `run_ragas.py` | 算分 + 判定 + 出报告 |
| `check_gold.py` | 改完 gold 先在本地自检：每条 span 能不能落到文档里。不用起容器，秒级 |
| `reports/` | 产物，不入库 |

门槛为什么这么定、踩过哪些坑、哪些还没做 → [`docs/agents/rag-eval.md`](../../docs/agents/rag-eval.md)
（给要改这套评测的人看，日常使用不用读）。

要加题：照 `gold.yaml` 的格式加一条——`question` 是问法，`reference` 是期望答案，
`gold_span.span` 抄文档里那句原话（不用带 `**` 强调，比对时会去掉）。
