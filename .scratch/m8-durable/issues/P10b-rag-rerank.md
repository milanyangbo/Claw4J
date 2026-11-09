# P10b · 知识库重排：通义 `qwen3-rerank` 落在官方 `DocumentPostProcessor` 槽位

**Status:** done（2026-11-09 实现；本票是从 P10 文末两节拆出来的，见 Comments 第一条）
**Blocked by:** P10a（重排的输入是融合后的候选，装配点也在 P10a 建的那个 advisor 上）
**裁决来源:** ADR-0020 第三次修订（P10 一节）+ `docs/design/knowledge-retrieval.md` 第六节。

## 一句话

检索出来 10 条候选，顺序决定用户先看到哪一段。**专用 reranker 只改顺序、不改集合**，
并且它挂掉时结果必须原样还在——这比"排序对不对"重要得多。

## 一、对齐到端点，不对齐到用法（2026-11-09 更正过一处）

三件事都查过：

- **OpenClaw 有 rerank**（原先写"0 命中"是错的，见本节末的更正记录）：`extensions/rerank/` 是
  随包插件、默认启用；`docs/providers/rerank.md` 把任何 `/rerank` 端点接成 **Decision model**；
  `docs/plugins/knowledge/memory-provider.md:175` 推荐 `gte-reranker-modernbert-base` 当
  **召回闸门**的判定模型（问的是"这一页能不能帮上忙？"）。
- **用途不同**：它是一对一**判定**（值不值得进上下文），我们是列表**重排**（10 条候选排序 +
  截到 topK）。端点形状一样（`/rerank` + `relevance_score` 0~1），用法不同。
- **Spring AI 2.0.2 没有 rerank 类型**：BOM 里没有模块，逐个 jar 扫 `rerank` 是 0 命中，
  **连 `RerankModel` 这个接口都不存在**。所以"实现框架的 rerank 接口"这条路不成立。

结论：落点是框架留的 `DocumentPostProcessor` 槽位（官方接口、官方装配点），调用层是自己发的
HTTP。**这是本项目唯一一处"官方没有、直接调 HTTP"的能力**，按 `docs/agents/alignment.md` 记账；
退场条件是框架出 rerank 模块（或某个 provider 模块带 `RerankModel`），届时只换这个 HTTP 客户端。

> **更正记录（2026-11-09 晚）**：这里原来写"全仓搜 `rerank` 0 命中，所以不是对齐"。
> 成因是拿 Bash 里的 toybox grep 加 `\|` 分支搜的——它不支持 BRE 的 `\|`，**恒返回 0 且不报错**
> （`docs/design/pitfalls.md` 第十八条）。用 ripgrep 复查后改成上面那三行。
> 代码不用动（结论"直连 HTTP"不变），但"这是新增不是对齐"这个说法要撤掉。

## 二、端点是 `/compatible-api`，不是 `/compatible-mode`

```
POST {base-url}/reranks
Authorization: Bearer <同一个 QWEN_API_KEY>
{"model":"qwen3-rerank","query":"退货政策","documents":[...],"top_n":10,"return_documents":false}
```

实测（2026-11-09，真实请求）：

| 候选 | `relevance_score` |
| --- | --- |
| 华东区自签收之日起 7 天内可无理由退货 | **0.657** |
| 生鲜商品不支持无理由退货 | 0.588 |
| A-07 号仓的作业时间 | 0.281 |

三段都与"退货"沾边，它把**真正讲政策的那段**排到前面，分差够截断。`qwen3-rerank` 单请求
支持 500 条文档、query 最多 4000 token，我们最多送 10 条。

前缀与 chat/embedding 的 `/compatible-mode/v1` **不是同一个**，所以 `rerank.base-url` 单独一个键，
不从 qwen 那个派生（拼字符串迟早拼错）；`rerankUrl()` 只在一处拼。

## 三、失败姿势：没有任何一条路径会抛

| 情况 | 结果 |
| --- | --- |
| HTTP 4xx / 5xx | 候选原样返回，WARN 一行（只记状态码，不记 body——错误响应可能回显我们刚发过去的整段文档） |
| 超时（默认 2s） | 同上 |
| 响应缺 `results` | 同上 |
| 下标越界 / 重复 | 丢掉那一项，其余按返回顺序，没被点到的按原顺序补在后面，补到 `topK` |
| 取不到 key | 跳过重排（DEBUG 一行），调用时才取 key，真值不进字段（P08 同一条约束） |
| `claw4j.rag.rerank.enabled=false` | 装配层不装这个 bean（类里不再有第二个开关） |
| 候选 < 2 条 / query 为空 | 不调端点，原样返回 |

超时或中断之后**不能把中断标志留给下一次调用**：`catch (InterruptedException)` 里
`Thread.currentThread().interrupt()` 后返回原顺序。

## 四、配置

```yaml
claw4j:
  rag:
    rerank:
      enabled: true
      candidates: 10     # 送进重排的条数；实际取 max(top-k, 它)
      base-url: https://dashscope.aliyuncs.com/compatible-api/v1
      model: qwen3-rerank
      timeout: 2s
      # key 不在这里：复用 claw4j.model.qwen.api-key，不引第二把钥匙
```

`rerankCandidates()` 取 `max(topK, candidates)`：少于最终要返回的条数，"重排之后取 topK"
会取到候选之外——这种错只在结果只剩两三条时才看得出来。

## 五、TDD 用例清单

规矩：**先写用例、看它红、再写实现**。全部在 `KnowledgeRetrievalTest`（替身起一个
`com.sun.net.httpserver.HttpServer` 当端点，不起真服务、不烧 token）。

| # | 功能点（判行为） | 用例 | 先红的样子 | 反例 |
| --- | --- | --- | --- | --- |
| 1 | 按返回的下标重排，并截到 `topK` | `#rerankReordersAndTruncates` | 忽略 `results`、照原顺序返回 → 顺序不变 | 待验 |
| 2 | 下标越界 / 重复：丢掉那一项，其余补在后面，**条数不变** | `#invalidIndexesAreDroppedNotFatal` | 越界直接 `get(9)` → `IndexOutOfBoundsException`，整轮失败 | 待验 |
| 3 | 端点 500：候选原样返回，不抛 | `#endpointFailureReturnsCandidatesAsIs` | 非 200 时抛出去 → 一次接口抖动就是一次对话失败 | 待验 |
| 4 | 端点超时：原样返回，且不把中断标志留给当前线程 | `#timeoutReturnsCandidatesAsIs` | 超时后抛 / 不 restart 中断 → 后续调用被 InterruptedException 打断 | **实测** |
| 5 | 关掉 `rerank.enabled` 时不装这个 bean | 待补（见第六节） | 开关只写在配置里、bean 照装 → "配了关但还在调" | 未守 |
| 6 | 没有 qwen 凭据时不装（而不是每轮打一次 401） | 待补（见第六节） | 调用时才取 key → 配错凭据的部署每轮 401 | 未守 |

## 六、这一票没守住的两条（缺口）

| 判据 | 现状 | 该长在哪 |
| --- | --- | --- |
| `rerank.enabled=false` → 容器里没有 `RerankDocumentPostProcessor` | `advisorIsRegisteredAfterMemory` 虽然设了这个属性，但只断言 order，没断言 bean 缺席 | `KnowledgeRetrievalTest`，`ApplicationContextRunner` 里加 `doesNotHaveBean` |
| 没有 qwen 凭据 → 不装 | 装配层靠 `@ConditionalOnProperty` + 构造时取 key，但没有用例 | 同上 |

两条都是"装配条件"，一条用例能同时盖住；写在这一节而不是勾在验收里，理由与 P10a 第六节相同：
勾了就是"文档承诺比代码走得远"。

## 七、实现记录

| 文件 | 变化 |
| --- | --- |
| `RerankDocumentPostProcessor` | 调通义 `qwen3-rerank`，任何失败原样返回；JDK `HttpClient`（`claw4j-agent` 的 classpath 上没有 `spring-web`，为一个调用引整个 web 模块不值得） |
| `RagAutoConfiguration#knowledgeReranker` | 两个条件都是"要么完整可用、要么不装"：`enabled=false` 不装、没凭据不装 |
| `RagProperties.Rerank` | `enabled / candidates / base-url / model / timeout`，`rerankUrl()` 只在一处拼 |
| `application.yml` | 新增 `claw4j.rag.rerank.*` 与注释 |

门禁（2026-11-09 实测）：**661 条、失败 0、跳过 0**；agent 69.7% / app 80.8%；
测试文件 10 个/模块、单文件最多 20 条（本票没新开测试文件，4 条用例塞进 `KnowledgeRetrievalTest`）。

## 八、验收

- [x] 重排改变顺序并截断（`rerankReordersAndTruncates`）
- [x] 非法下标不丢结果（`invalidIndexesAreDroppedNotFatal`）
- [x] 500 / 超时都原样返回，且不留中断标志（两条）
- [x] 只记状态码不记 body（错误响应会回显我们发过去的文档）
- [x] `rerankUrl()` 与 `rerankCandidates()` 各只一处
- [x] `./scripts/verify.sh` 全绿、跳过 0、覆盖率 ≥60%
- [ ] 第六节那两条装配条件——**未做**，见那一节

## Comments

- 2026-11-09 拆票。用户裁决"要拆票，票里面要包含 TDD 测试的功能点"，于是把它从 P10 拆出来
  单独一张。**为什么它值得单独一张**：`claw4j.rag.rerank.enabled=false` 就等于"没有重排"，
  可以单独关、单独回退；而它是全项目唯一一处直连供应商 HTTP 的能力，退场条件必须写在自己的
  票上，否则将来框架出 rerank 模块时没人知道该删哪一段。
- 上一版设计用的是 chat 模型做 listwise 排序，后来换成专用 reranker（2026-11-09 用户裁决）。
  原因写在设计文档第六节：chat 模型自评有位置偏见与"读起来像答案"的偏好，而专用 reranker
  是对着相关性训练出来的。
- 这票同样是**实现后补票**（代码先落地、票后拆），第五节"反例"列里标 **实测** 的才是真的
  改坏过看它红；其余按断言应当红，动到那段代码时验一遍。
- 编号用 `P10b` 而不是继续往下排：**P17–P19 在 ADR-0020 的 M9 里已经分给脚本沙箱 / `doctor` /
  WebSocket**，编号跨批次唯一、不复用。它是能力 P10 的一次变更，挂回 P10 才对得上口径。
