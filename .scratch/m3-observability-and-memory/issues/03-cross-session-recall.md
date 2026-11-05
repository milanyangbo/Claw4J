# 03: 跨会话召回：问"上次那批单子怎么定的"能想起来

**What to build:** 把 `spring-ai-session` 的 `CrossSessionRecallTools` 接上，让模型能检索
**同一个用户在其他会话里说过的话**，并把这个工具登记进鉴权表。

**Blocked by:** 01 —— 召回是"往提示词里再塞一段历史"，成本要看得见，否则没法判断它值不值。

**Status:** resolved

> **2026-11-05 修订：召回范围从"这个人的所有会话"改成"当前这一段会话"。**
> 会话键那时已经按"通道 + chatId"分段（ADR-0025），而召回还按 `owner` 查，
> 于是私聊里的内容能被取到群聊里。见文末「隔离修复」。

**Demo:** 会话 A 里问"华东区的口径是什么"，得到答案；换一个会话 B 问"上次华东区的口径怎么定的"，
回答里带上会话 A 的内容。

- [x] `userId` **按当前请求构造**，不是单例 bean。`CrossSessionRecallTools.builder(sessionService, userId)`
      把 userId 绑在实例上，共享一个 bean 意味着**所有人召回的都是同一个人的历史**——
      这是这张票唯一真正的安全边界
- [x] 工具名 `recall_session`，动作 `recall_session`，`tables` 为空（它读的是会话，不是业务表）。
      只给 read-only 与 admin，guest 不给
- [x] 召回结果截断后再进提示词：`pageSize` 有上限，且**整段结果**也要按字符裁一次——
      召回十条长对话能一口气把上下文吃满
- [x] 断言两件事：另一个会话的内容能被这轮看到；**换一个 userId 就看不到**
- [x] 变异：把 userId 从"当前 principal"改成构造时写死的常量 → 第二条断言变红

**落点:** `claw4j-agent` 的 `session` 包（`CrossSessionRecallTools` 的包装与权限登记）。
工具回调自己实现（`FunctionToolCallback`），不依赖它有没有 `@Tool` 注解——
注解在第三方 jar 里，有没有都得试一次才知道，自己写 schema 反而确定。

**注意:** 这是**关键词检索，不是向量语义**。spec 里核实过 jar：`CrossSessionRecallTools`
按词匹配 `SessionEvent`。所以它召回不了"换个说法问同一件事"，那要 pgvector（票 05 的载体），
不在本票。文档里不要写成"语义召回"。

**不要做:** 向量召回；跨用户召回（管理员查别人的会话）；把召回结果写回当前会话。

## 实现记录（2026-10-27）

- 新类 `agent.session.RecallSessionTool`：手写 `ToolDefinition` + `InputSchema`，工具名
  `recall_session`，参数只暴露 `query` / `match_mode` / `since` / `page`（框架方法上的
  `innerThought` 不给模型——它是模型的独白，只会变成日志噪音）。
  框架的 `@Tool` 注解确实在（`cross_session_search`），但这里要改名、要去掉一个参数、
  要在结果上做两层截断，自己写 schema 比反射改写省事。
- 装配在 `SessionMemoryConfiguration`：`RecallSessionTool` 单例 + 一个
  `ToolPermissionRegistry`（动作 `recall_session`，`tables` 空）。因为该配置上有
  `@ConditionalOnSingleCandidate(DataSource.class)`，"没有会话存储就没有这个工具"是自动成立的。
- **`userId` 在 `call` 里按当前 `Principal` 现造**：`CrossSessionRecallTools` 实例不缓存。
  单参 `call(String)` 没有 `ToolContext` → 按"没身份"拒绝，不退化成查某个写死的人。
- 截断两层：`pageSize=5`、整段 2000 字（超了补一句"怎么缩小范围"）。
  没命中时把框架的 `No results found.` 翻成中文并附上"换个查法"——原样交出去，
  模型下一句会用英文接着答。
- `application.yml`（http 与 feishu 两份）的 `read-only` 都加了 `recall_session`；
  `guest` 不加，`admin` 的 `*` 自然含。
- 修正 M3-06 留下的一处失效：`ToolSearch.SYSTEM_SUFFIX` 原来写"用一句话描述你需要的能力"，
  而 `RegexToolIndex` 不认中文分词——整句是一个 token，匹配不到任何工具。
  实测（同一份索引）：`回忆 历史 会话` 命中，`回忆以前说过的话` 落空。
  改成"用 2~4 个空格分隔的关键词"，并补一条用例用真索引钉住两种行为。
  细节记进 `docs/design/pitfalls.md` 第十一条。

**变异验证**（两条都做了）：

1. 把 `recallToolsFor` 里的 `who.userId()` 换成写死的 `"u-1"` →
   只有 `anotherUserSeesNothing` 变红，其余 6 条全绿。正好说明"换个人看不到"是唯一
   能抓住这个缺陷的断言，其余用例对这个失效完全无感。
2. 去掉 `truncate(...)` → `truncatesWhatItBringsBack` 变红（actual 是 1.7 万字）。

**真链路验证**（起一次应用，端口 18080，飞书关）：

```
POST /api/chat  u-recall     "记一下：华东区成交口径按签约主体算，不按开票主体。只回一个字：好"
  → ai_session_event 写入 2 条（USER / ASSISTANT）
UPDATE ... SET archived=true  -- 等价于压缩归档，这一轮上下文里就没有它了
POST /api/chat  u-recall     "我上次说过的华东区成交口径是什么？用你自己的历史记录回忆一下"
  → "找到了。你在 2026-10-27 说过：华东区这批单子的成交口径按签约主体算，不按开票主体。"
POST /api/chat  u-intruder   "帮我回忆一下：华东区成交口径按什么算？"
  → "我查了历史记录，只找到你这条提问本身"（没有那段话）
GET 9090/actuator/metrics/claw4j.tool.calls  → tool=recall_session, outcome=ok: 3 次
```

`u-recall` 那两轮之间打了 2 次工具调用：第一次关键词没命中，模型自己换了一组——描述里
"换一组更贴近原文的词"那句话起了作用。

**未做 / 记账**：

- 语义召回（换个说法问同一件事）。那要 embedding 加向量表，属于票 05。
- 跨用户召回（管理员查别人的会话）。没做，也不打算做。
- **召回结果里会包含当前会话本轮刚发的那条提问**：入站消息在 Advisor 的 `before` 就落库，
  所以"按 userId 查事件"必然查得到它。实测 `u-intruder` 那句"只找到你这条提问本身"就是它。
  没有正确性问题，只是白占一点上下文。要修得有"排除当前 sessionId"的能力，
  而当前一个用户只有一段会话，排掉就等于全排掉——等会话维度加上去再说。

## 隔离修复（2026-11-05）

会话键已经按"人 + 权限 + 通道 + chatId"分段（ADR-0025），但这个工具还在按 `owner` 查
（`findEventsByUserId`，`WHERE user_id = ?`）。结果是：自动拼上下文那条路被分段挡住了，
**工具这道后门还开着**——网页私聊里查到的 128,400，在项目群里让模型"回忆一下"就能取出来。

改动只有一处，但换掉了整个查询：

```text
原先  findEventsByUserId(SessionKeys.ownerOf(who), filter)   ← 这个人的所有会话
现在  getEvents(sessionId, filter)                            ← 只有当前这一段
```

- 为什么不给框架那个工具加个过滤：`EventFilter` 里**没有会话字段**
  （字段只有 from/to/messageTypes/keywords/matchMode/pattern/page/pageSize/excludeArchived/synthetic），
  `CrossSessionRecallTools` 也没有暴露 sessionId 入参。按会话收窄只能自己查。
- 会话 id 由 `ChatClientAgentPort` 写进工具上下文的 `ChatMemory.CONVERSATION_ID`，
  与框架自己的 `SessionEventTools` 是同一把键。**模型手上没有参数能改它**——
  "查别人的聊天记录"这条路不存在，而不是"存在但被拒绝"。
- 身份仍核一次：`sessionId` 必须以 `claw4j:user:<当前身份的 owner>:` 开头，核不上就拒。
  它不参与查询（范围由会话定），防的是宿主传错或将来有人把别人的 id 递进来。
- 输出从框架的 JSON 改成文本行（`- 时间 说话人：正文`）：会话恒等于当前这一段，
  每条都重复的 `sessionId` 与四个键名只是白花 token。
- 顺带解决文末那条记账的一半：仍然会命中本轮刚发的那条提问（它本来就在这一段里），
  但它不再是"按人查"撞出来的，而是这一段里真实存在的一句。

**反例（三条都真跑，跑完复原）**：

1. 把 `getEvents(sessionId, …)` 换回 `findEventsByUserId(owner, …)` →
   `recallsOnlyThisConversation` 变红，实际输出里出现"华东上周成交额 128,400.00，先别外传"。
   只有这一条红，其余 19 条全绿——正好说明别的用例对"范围"这件事完全无感。
2. 去掉归属校验（`belongsTo`）→ `anotherUserSeesNothing` 与 `downgradedUserLosesItsOwnHistory` 变红。
3. 过滤器加 `.excludeArchived(true)` → `recallsOnlyThisConversation` 里那条"归档过的那句也在"变红。

两条与"范围"相关的断言（本段能找到 / 别处找不到 / 归档的也算）合并进了同一个用例：
`claw4j-agent` 每个测试文件的方法数上限是 20，拆成三条就超了——而拆文件同样不行，
那个模块的文件位也是满的。合并的代价是失败时看断言名才能分出是哪一类，
所以每条断言都带了 `as(...)`。

第一次做反例时踩了个坑：假仓库的 `findEventsByUserId` 只从**登记过的会话**里找事件
（真库靠 `ai_session.user_id`），而用例当时只 append 没 `create`，于是反例红的原因是
"假仓库里什么都没有"，不是泄露。补了 `open(...)` 让每条用例都登记会话之后，
反例才红在该红的地方。**假红比不红更糟**：它会让人以为防线有人守着。

