# 验收材料（2026-11-25 这一批：文档改版后 A–J 全量复跑）

对应仓库根 `README-capability-tests.md` 的 **A–J 十节**。这一批的两件事：

1. **文档改版**：B6 从十条压到三条（只留单跳 / 跨文档 / 查不到就不编），新增 **J 会话读侧**
   一节（侧边栏列会话、逐轮、归属与 `/new`）；D2 / G2 / H2 里讲"段"和"`/new` 换段"的旧说法
   改成现在的语义（id 不变、只清历史；`/new` 的回执文案换成库里那一句）。
2. **从头再跑一遍**：干净库 `claw4j_accept_1125`（首次同步 111 份 / 440 段）。抓到两个问题，
   见下面「这一批没通过的两条」。

## 结论

- A–I 九节：**除 B6.2（跨文档）与 D1（语义缓存）外全部通过**。
- J 节（新写的）：J1、J2 通过；**J3 的第二个判据没通过**——`/new` 把会话行删掉了，不是清历史。
- 新读侧端点的形状与文档写的差一处（会话 id 里是两个 `http:`），已按实测改文档。

## 材料清单

| 路径 | 是什么 |
| --- | --- |
| `main-app.log` | 主实例（ops 人设、能力全开、RAG 开）：A1/A2、B1/B2/B4–B8、C5、D2/D3、E2/E3、F8、G1/G2、H1–H3、I、J |
| `sales-users-messages.log` | V1（sales 人设 + `users=t1,ethan` + `audit.messages=true`）：A3、A4、B3、C1①、I6 |
| `c2-approval.log` | V2（exec `allowlist`+`on-miss`、`ttl=60s`、`required-tools=TodoWrite`）：C2、C3、C4 |
| `f2-model404.log` | V3（模型名写错 + `tool-filter.exclude`）：F2、F3、C1②、H4 |
| `g3-big-h5h6.log` | V4（3 万字人设）：G3 |
| `h5-busy-h6-timeout.log` / `h6-timeout.log` | V5/V6（`agent.lane-wait=0` / `http.stream-timeout=2s`）：H5、H6 |
| `d1-cache.log` | V7（`cache.enabled=true`）：D1 |
| `f4-unreachable.log` | V8（`base-url` 指到 9 端口）：F4 |
| `mcp-java-http.log` | E1 用的 MCP server（起 → kill → 再起） |
| `stream-*.txt` | 原始 SSE 帧：basic / new / tool / failover / busy / timeout |
| 库 `claw4j_accept_1125` | 288 条审计、5 条审批、3 张进度卡、1 条意图、72 条会话 / 152 条事件 |

## 实测到的值（复跑会变，只用来对形状）

| 节 | 本次实测 |
| --- | --- |
| A2 | `demo_now` 两次同 pid（56707）、时间戳 1795618264.855 → 1795618271.702；`demo_echo` 逐字带回 |
| A3 / A4 | `nobody` 401 点名；agent `sales`、画像 MESSAGING（来自 tier） |
| B1 | 128400 / 99999 / 77777；`querySales ok` 3 |
| B2 | Glob/Read/Grep/WebFetch 各 1；`Write/Edit 未开`；`WebSearch 未配 key`（Glob 与 WebFetch 要带"先检索"） |
| B3 / B4 | 185900（三区齐）；脚本输出逐字一致；`Skill ok` 1、`Bash ok` 1 |
| B5 | capacity-triage（堆 2.8%、线程 106）；weekly-digest 185900 + 环比"不可比"；`Task ok` 2 |
| B6 | 单跳（7 天 / 5 天、"按签收日"）、查不到就不编（不给数字）✅；**跨文档那条没答对**（见下） |
| B7 / B8 | 挂起 → 204 → "你选了生产"，再答 409；`TodoWrite ok` 2、卡 `revision=2` 三步全 COMPLETED |
| C1 / C2 / C3 / C4 | 画像与 tool-filter 都生效（3→2）；审批 `granted` + `allow-once` + 审计带 `approvalId`；自批 403 / 非审批人 403 / 过期 409；TodoWrite 挂起后 `allowAlways=false`、批了才落卡 |
| C5 | 内网拒（日志 `拒绝出网`）、公网通、出站剥离（回答里没有边界标记） |
| D2 / D3 | 同人记得"北辰科技"，换人不知道；种子 11-02～11-23 与三窗对得上 |
| E1 / E2 / E3 | DOWN→第 6 次重试恢复（工具 3 个）；运行期 kill → 500、起回来第 2 句恢复；超时 31.7 秒 500 且 `runtimeSnapshot` 照常；单工具 3 次 / 总预算 6 次都拦住 |
| F2–F8 | 换家 1 次（`model_not_found`）→ 冷却后停在 2；不可达 97 秒 + `route_unavailable`；白名单 / `api=gemini` 都点名；`utility=独立链`；`cache-read` 有数 |
| G1 / G2 / G3 | 卡 `revision=2` + 三种状态；意图 `armed→fired(1)`、冷却内仍 1；WARN 一行 `22479 → 20106`，第二问不刷 |
| H1–H6 | 81 帧/1 done；`/new` 1 帧正文 + done 且 `model.calls` 不涨；工具静默；换家照常；忙"现在有点忙"；断流 11 帧 0 done、3 秒 |
| I1–I9 | 四行倒序、毫秒、`actor` 对象、无 `runId`；四变体齐；三条冲突 400 + 坏游标 400；两页不重叠；`INSERT 0 1` / `0 0`；消息行带 `conversationKind`/假名；工具行 `sha256:`、运行与消息行没有；五个指标全 404 |
| J1 / J2 | 列表只有自己那条、id 无段后缀、标题是首条消息、最近活动在前；逐轮 user→assistant 时间正序、`hasMore=false` |

## 这一批没通过的两条

### 1. B6.2 跨文档：口语问法召回不到那两份文档

问「打折卖出去的机器退回来，按哪个金额退？」两次（`r5`、`r5b`），两次都答"资料里没有直接
讲退货按哪个金额退的条款"。而 `pricing.md:16` 写着"按**实付金额**退款，不按标价退款"，
`refund-process.md:50` 写着"按该型号的**折后价**退"——答案在库里。

换成文档自己的词问「折扣订单发生退货时按实付金额还是按标价退款？」→ 立刻答对
（"按实付金额退款，不按标价退款…见 [1]"）。

代码里没有接线问题：召回这一轮向量 25 条、全文 25 条、融合 45 条、重排 20 条都跑了，
只是那两块的排名没进 top-10。**这是"问法与文档用词不一致时的召回退化"**，属于检索质量，
该由 `eval/rag` 用一批真实问法去量，不是对着一条问句调阈值。

### 2. J3：`/new` 删掉了会话行，不是清历史

`/new` 之后 `GET /api/sessions` 是空数组、`GET /api/sessions/{id}/messages` 回 404——会话那一行
被删了（`SlashCommands.clear` 直接调 `sessions.delete(sessionId)`）。再发一句会用**同一个 id**
重建（id 是会话键派生的，所以"id 不变"这一半成立），但 `createdAt` 重置、侧边栏在两条消息之间
是空的。

按 ADR-0026 修订与票 01 的收票记录，`/new` 的语义是"清掉这条会话的历史、id 不变、会话还在"。
现在的实现少了"会话还在"这一半。要修就得让 `clear` 删完再按同一个 id 建一次（框架没有
"只删事件"的接口），或者接受"删了重建"并把文档与 ACK 文案改成这个语义——两条路都该先有票。

## 这一批没验到的

- **D1 语义缓存**：`cache.enabled=true` 起实例，同一个纯模型问题问两次（换用户），`model.calls`
  仍然 +1；缓存索引 `semantic-cache-index` 存在、Redis 里有 850 条 `semantic-cache:*`，但两次
  都没命中。装配是匹配的（DEBUG 条件日志里 `CacheAutoConfiguration matched`）。这一条按"没通过"
  记，没往下追——它验的是干扰项，不影响能力判定。
- **F6 另外两种形状**（`models` 为空、`context-window` 非正数）：这一批只跑了 `api: gemini`。
- **I4 的 `limit=1000 → 500`**：库里几百行，够不着上限，钳位由单测覆盖。
- **I 的 30 天 / 10 万行清理**：要等时钟。

## 跑的过程中踩到的两件事（不是产品问题）

- **`--claw4j.channel.http.users=*` 在 zsh 里会被当通配符展开**：写成 `users=*` 会把当前目录的
  文件名拼进去，启动直接报"准入名单里有多个人"。要写成 `"users=*"` 或 `users='*'`。
- **`pkill` 之后隔 3 秒就起新实例**：旧 JVM 还没退出，新实例绑不上 8081，请求仍被**旧实例**
  （带着上一次的开关，比如 `lane-wait=0`）应答，看到的是上一档的行为。等端口真空出来再起，
  症状是"明明改了参数，结果还是旧的"。
