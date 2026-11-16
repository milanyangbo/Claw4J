# 05: 语义缓存把"我做不到"也缓存了，而且不分用户

**What to build:** 待裁决。这张票先把现象与代价钉住，方案在裁决之后写——它改的是"什么回答值得复用"
这条产品口径，不是一处代码写法。

**Blocked by:** 无。但**与票 02 同源**：票 02 的误判就是被它造成的。

**Type:** investigation

**Status:** resolved（跟框架一致，不加自定义处理）

**Demo:** 一次"派不出去"的回答之后，换一个用户、换一个实例问同类问题，**不会**拿到那句旧回答。

## 现象（今天的实测原文）

1. 上一轮某实例产出过一句答复：「派不出去——`capacity-triage` 已经连续三次空返回（两次 Task 派发 +
   一次检索派发），这次再派结果也一样……」（当时的原因见票 02）。
2. 这个实例已停。新起一个进程，用**另一个用户**（`X-Claw4j-User: probe1`，新会话、无记忆）问同一句话，
   拿到的回答**与上面一字不差**。
3. 该进程的 `claw4j.tool.calls{tool=Task,outcome=ok} = 0`，`/tmp` 日志里没有任何 `Executing tool call`
   ——本轮根本没有模型调用。

三件事同时成立只有一种解释：回答来自 Redis 里的语义缓存（`DefaultSemanticCache` + `SemanticCacheAdvisor`，
阈值 0.93），跨会话、跨用户。

## 代价

- **失败被固化**：一次工具抖动、一次检索没命中、一次模型抽风，写下的"做不到"在当天会被反复复用。
  系统提示词里的日期是唯一的自然失效点（见 `application.yml` 的 cache 段注释），也就是**能活一整天**。
- **不分用户**：`probe1` 与 `ethan` 拿到同一句。缓存键里没有身份，于是 A 用户的答复可以成为 B 用户的答复；
  如果那句话里带着 A 的数据（业务问答里很常见），这就是一次跨用户泄露——**这一条比"失败被固化"更重**，
  必须先确认。
- **让验证失真**：它已经骗过我们一次（票 02 的负结论）。

## 要查清的三件事（按这个顺序）

1. **缓存键到底含什么**（读 `SemanticCacheAdvisor` 2.0.2 的实现，或把 Redis 里的行 dump 出来）：
   是否含用户/会话。若不含，那"跨用户"不是"阈值太松"，而是设计上就没有隔离。
2. **失败回答是怎么进去的**：`SemanticCacheAdvisor` 是 `after()` 无条件 `set`，还是判了 `finishReason`
   /异常？若无条件，那么"拒答、报错、被审批拦下"三种都进了缓存。
3. **同一句话在缓存命中时的行为是否可区分**：`SemanticCacheAdvisor` 命中时有没有可观测信号
   （日志/指标/`ToolContext`）。今天能确定命中，靠的是"回答一字不差 + 零计数"这种间接证据，
   这本身就该改——见下一节最后一条。

## 裁决要回答的问题

- 键要不要带身份（`principal.userId()`）？带了，缓存命中率会掉；不带，跨用户泄露就是设计后果。
- 失败回答入库前要不要判？可选口径：`finishReason != STOP`、回答里出现"没有权限/做不到"这类确定性拒答、
  或者本轮 `claw4j.tool.calls{outcome=denied|error}` 非 0 时不写。
- 命中要不要留痕（一行 DEBUG 或一个计数器 `claw4j.cache.hits`）？不留痕，"这是缓存还是真的答了"
  永远要靠猜——本票的取证全靠间接证据。

## 验收判据

方案定下来之后补。现在能写的只有一条：**复测任何能力之前，`--claw4j.cache.enabled=false`**
（已写进 `docs/capability-tests.md` §6）。

## 裁决（用户 2026-11-16）：框架怎么处理就怎么处理，不额外加我们的逻辑

结论：**这件事不是我们的缺陷，是本仓刻意"跟框架走"的结果，因此不改。**
依据是反编译 `spring-ai-redis-semantic-cache-2.0.2` 得到的实际行为，不是推测：

| 问题 | 框架的做法 | 证据（`SemanticCacheAdvisor` 字节码） |
| --- | --- | --- |
| 键里有没有身份 | **没有**。键 = 用户文本 + `sha256(系统提示词)` | `extractUserText` 取 `prompt.getUserMessage().getText()`；`extractContextHash` 只对 `getSystemMessage().getText()` 做 `computeHash`，整条路径没有 principal / chatId |
| 失败回答进不进库 | **进**。只要 `chatResponse != null` 就 `set`，不看 `finishReason`、不看有没有异常、不看被拒过几次 | `adviseCall` 内 `cache.get(...)` 未命中 → `nextCall(...)` → `if (resp.chatResponse() != null) cache.set(text, chatResponse, hash)`；流式那条同构（`lambda$adviseStream$0`） |
| 命中能不能看出来 | **看不出来**：直接 `ChatClientResponse.builder().chatResponse(命中).context(原 context).build()` 返回，不打日志、不计指标 | 同上，命中分支只有 builder 三条指令 |

所以"换用户拿到同一句"与"失败被固化"都是**框架的既定语义**，不是我们包装出来的一层。
要做隔离或过滤，就是在本仓自己加一条框架没有的规则——与 `docs/agents/alignment.md` 的判据相反
（上一轮 `ApprovalService` 那个多余的守卫就是因为这个被回退的）。

**唯一保留的动作在文档侧，不在代码侧**：`docs/capability-tests.md` §6 已写明"复测任何能力前
`--claw4j.cache.enabled=false`"，否则测出来的是缓存不是能力（票 02 的教训）。

如果将来要改这条口径（比如键里带用户、或拒答不入库），那是一次**产品决定**，要单独开票并在票里
写明"我们与框架的分歧是什么、为什么值得分歧"——不能顺手加。
