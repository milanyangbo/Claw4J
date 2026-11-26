# 票 18：题库重排——把"一格题太贵"的档补厚，business 换成去字面化的问法

**Status:** resolved（2026-11-26，60 题重排完毕，四条判据全中）

> 依赖票 12（`ir_measures` 口径）、票 13（zh-en 加厚那次）、票 14（MCP 进目录）。
> 用户 2026-11-26 裁决："按 A 来"——先换题，不动工具。

## 由来

38 条题里 36 条进指标，一格 = 1/36 = 0.028。看起来还行，但**分摊到每一档是错的**：

| 档 | 题量 | 一格题的分值 | 现状 |
| --- | --- | --- | --- |
| `business` | 12 | 0.083 | R@5 五档全 1.000，**没有区分度** |
| `zh-en` | 20 | 0.050 | R@5 0.100–0.550，唯一有信息量的一档 |
| `base` | 4 | **0.250** | R@5 0.250–0.750，一格题决定半个分数 |
| `no-search` | 2 | **0.500** | 零候选率 0.500 = 就一题说了算 |

逐题名次（2026-11-26，`reports/*.run` 反查）说明了 `business` 为什么饱和：

```
qid                          regex 名次   向量名次
tools-sales-last-week             2          1
tools-sales-region-rank           3          1
tools-schedule-daily              2          4
tools-schedule-list               1          1
tools-mail-unread                 3          1
tools-mail-send                   4          1
tools-github-issues               2          1
tools-github-pr-ci                5          1
tools-schedule-cancel             2          1
tools-system-status               4          1
tools-recall-previous             5          1
tools-intent-remind               1          1
```

**12 道里关键字那一路最差排到第 5**——刚好卡在 `R@5` 的边界内。所以 `R@5` 全 1.000 不是"题太送分"，
是**这批工具的同域干扰项就那么几个，翻到第 5 也就翻完了**。真正的区分度藏在 `R@1`（0.167 对 0.833）。

`base` 那 4 道更糟，两种配置下有三道直接 miss：

```
tools-glob-md      regex 未返回    向量第 5
tools-grep-refund  regex 未返回    向量未返回
tools-bash-test    regex 未返回    向量第 2
tools-read-head    regex 第 2      向量第 2
```

"索引能不能工作"这条底线，现在是破的——但只有 4 道题，说不清是题的问题还是索引的问题。

## 裁决

无需 ADR：只改评测题库，不改线上行为。

**不动工具、不动 Java 侧**（`ToolGoldTest` 的约束照旧满足）。加工具的事另记缺口——
它会作废基线、要写 `ToolProvider`，而"换题"便宜且可回退，先做后者。

## 改什么

| 档 | 从 | 到 | 怎么加 |
| --- | --- | --- | --- |
| `base` | 4 | 10 | 每个基座工具（Read/Grep/Glob/Bash）各补 1–2 道。**刻意混两种**：一半带中文字面词（现在能命中）、一半纯语义（现在命中不了）——全是后者的话这一档永远是 0，就看不出退步 |
| `business` | 12 | 20 | 新增 8 道**去字面化**：不点工具名、也不出现工具描述里的词（"销售/邮件/issue/PR/定时/提醒"）。判据是"这 8 道在 `regex` 下应当排到第 6 名以外"——排不进去说明出题没到位 |
| `zh-en` | 20 | 20 | 不动。票 13 刚加厚过，一格 0.05 与回归门同量级 |
| `no-search` | 2 | 10 | 只加**彻底无工具语义**的闲聊。凡是含"方案/选择/查/找"这类能撞上工具描述的词都不要——那种题本身有歧义，判"给了候选就是错"不公平 |

新增的 8 道 business 是这一票的核心，列在这里供复核：

| 问法 | gold | 刻意避开的字面 |
| --- | --- | --- |
| 华东区这个季度走得怎么样 | `querySales` | 销售、查询 |
| 把上个月的数按区域拆开看看 | `querySales` | 销售 |
| 一到早上九点就叫我一声 | `schedule_task` | 定时、提醒、任务 |
| 我之前让它定期干的事都在哪儿 | `list_schedules` | 列表、定时任务 |
| 跟老王说一声周五的会挪到下周一 | `mail_send` | 邮件、发送 |
| 有没有人给我留了话 | `mail_list_unread` | 邮件、未读 |
| 那次改动有没有挂 | `github_pr_checks` | PR、CI、检查 |
| 还有什么事挂着没人管 | `github_issue_list` | issue、仓库 |

## 判据

改完重跑，**这四条同时成立**才算这票做成：

1. **`business` 的 `R@5` 不再是五档全 1.000**：至少有一档掉下来，说明这 8 道真的更难。
2. **`hybrid-0.0` 在 `business` 上仍然领先 `regex`**：这 8 道如果连向量也拿不到，说明是题出坏了
   （问了件工具做不到的事），不是索引不行。领先幅度记进票面。
3. **`base` 档出现非零的非满分**：10 道里既有命中也有失败，这一档才有"能不能看退步"的资格。
4. **`no-search` 的零候选率不再由单题决定**：10 道里逐题打印谁给了候选。

`{TOLERANCE}` 保持 0.05。但题量变了 → **旧基线作废**，`baseline.json` 重钉，
README 里的 2026-11-26 那张表要标"旧题库，已作废"。

## TDD 用例清单

Java 侧已有约束（`ToolGoldTest`）不用改，但要确认新增题目都满足：

| 判什么 | 用哪个已有用例 | 会不会红 |
| --- | --- | --- |
| 四类齐全、id 不重复、总数 ≥20 | `goldFileLoads` | 加题不会破 |
| `zh-en` ≥20 且 gold 都是英文描述工具 | `zhEnHasEnoughQuestions` / `zhEnOnlyTargetsEnglishDescriptions` | 这一档不动，不会破 |
| `business` 仍覆盖四个外部工具 | `businessCoversExternalSystems` | 原有四道不动，不会破 |
| 新题的 gold 真在目录里 | `ToolRetrievalExportTest#everyGoldToolExistsInCatalogue` | 加一个不存在的工具名会当场红 |

`base` / `no-search` 的题量下限**现在没有断言**——这正是它们能退化到 2 题的原因。
本票要给 `ToolGoldTest` 补两条：`base` ≥ 10、`no-search` ≥ 10，并写明"为什么是 10"
（一格 0.10，与 `TOLERANCE` 同量级；2 题时一格 0.5，判据比噪声还粗）。

复核（2026-11-26）：

```bash
./mvnw -o -pl claw4j-eval -am test -Dtest=ToolGoldTest            # 10 条全绿（原 8 + 新 2 条题量下限）
./eval/tools/retrieval/run.sh --cross-check                       # 五档导出 + 算分 + 一致性校验
./eval/tools/retrieval/run.sh --from-report --update-baseline     # 重钉基线
```

**四条判据的结果，逐条对：**

1. `business` 的 `R@5` **不再饱和**：0.650 / 0.750 / 0.650 / 0.900 / 0.850（五档分别 regex / lucene /
   hybrid-1.0 / hybrid-0.5 / hybrid-0.0）。`R@1` 也拉开：0.150–0.600。
2. `hybrid-0.0` 在 `business` 上仍领先 `regex`：0.850 对 0.650（+0.20）。
   **但 `hybrid-0.5` 的 0.900 比纯向量还高**——说明"去字面化"之后关键字那一路仍有贡献
   （"华东区""上个月""周五"这些词还在），融合权重 0.5 在这一档更合适。这条记下来，
   它跟"纯向量最好"的旧结论不冲突，只是那一版没有这类题。
3. `base` 档出现非零非满分：`regex` 0.300、`hybrid-0.0` 0.400。**并且暴露了真问题**：
   `把 pom.xml 打开我看看`、`代码里哪些地方还留着 TODO`、`把项目编译一下` 三种配置下都没返回。
4. `no-search` 不再由单题决定：10 道里 5 道给了候选（`thanks` / `ack` / `ok-then` / `weather` / `bye`），
   零候选率 0.500。逐题名单每轮都打印。

**出题是否到位的硬判据**：新增那 8 道 business 题里，**7 道在 `regex` 下完全没有返回**，
只有 `tools-schedule-alarm`（"一到早上九点就叫我一声"）排第 1——"九点"撞上了 `schedule_task`
描述里的时间词。达标。

**纯向量漏的 3 道**（`R@5` 0.850 = 17/20）：`tools-schedule-alarm`、`tools-mail-tell`
（"跟老王说一声周五的会挪到下周一"）、`tools-github-backlog`（"还有什么事挂着没人管"）。
三件事工具都做得了，所以不是题坏——是语义没接上。

**新发现（本票范围外，已记缺口）**：换题库后 Java 自研与 `ir_measures` 在 `R@5` 上对不齐。
自研（60 题）0.333、第三方（50 题）0.360，减掉 5 道 `no-search` 的命中后是 15 对 18，**差 3 题**。
`R@1` 那边能完全对上（4 + 5 = 9），所以不是整体口径问题，是 `R@5` 这 3 题的具体判定不同。

## 缺口（本票不做）

- **加工具能让 `business` 彻底不饱和**，但代价是所有历史分数作废、要写 `ToolProvider`。
  先看这一票换题之后够不够；不够再开票。
- **`tools-grep-refund` 两种配置全 miss**（"哪些文件里提到了 refund"→ `Grep`）：
  这是真实失效，不是坏题，本票**保留**它作为证据。修的是"这一档没有统计意义"，不是"让它变绿"。
- **`Task` / `github_api` 两个工具从没被考过**：`Task` 描述是中文、`github_api` 是通用兜底，
  两者都不适合单独做 gold（一个太抽象、一个什么都能干）。留在缺口里说明是有意为之。
- **两套实现的 `R@5` 差 3 题**（本票新发现）：Java 自研与 `ir_measures` 在 50 道有 gold 的题上
  给出 15 与 18。`R@1` 能完全对上，所以不是分母问题。判回归只认第三方，自研那个数只当"先看一眼"。
  要收掉它得逐题比对两边的排序与判定——是另一张票。
