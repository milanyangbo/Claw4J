# 验收材料（2026-11-27 这一批：题目改人话之后重跑）

对应仓库根 `README-capability-tests.md`。这一批只做一件事：**把题目改成不点工具名之后，
再跑一遍**，看"模型自己会不会找到、用对"。

## 结论（三句话）

1. **常驻的三个工具（`Read`/`Grep`/`Glob`）不点名也能用**，结果都对得上；
2. **不在常驻的工具（`WebFetch`/`TodoWrite`/`AskUserQuestionTool`）不点名就不用**——
   模型不检索，直接答"我无法访问外部网页"，或者干脆在正文里手写待办清单；
3. **加一句"手边没有就自己找一个"立刻恢复**：`WebFetch` 从 0 次到 1 次、`TodoWrite` 从 0 次到 2 次。
   所以缺的不是能力，是**模型不知道还能去找**。

外加一个阻断：**开长期记忆时，任何一次工具调用都会 500**（`No ToolCallback found for tool name: MemoryView`）。

## 实测

| 节 | 题目 | 结果 | 计数 |
| --- | --- | --- | --- |
| A1 | 上周华东区的成交额是多少 | ✅ 128,400.00 元、口径正确 | `querySales ok` 1 |
| A2 | 用 `demo_now` 报 pid 与时间戳（两次） | ✅ pid 都是 71652，时间戳 1795776040.412 → 1795776071.571 | — |
| A2 | `demo_echo` 原样带回 | ✅ 逐字一致 | — |
| B1 | 同上 A1（业务工具那一档复用） | ✅ | — |
| B2-1 | examples 目录下有哪些 .md 文件？ | ✅ 工具调了、文件名都对；**但它自己汇总的数字错**：说 130，磁盘是 128（skills 多算 1、knowledge 多算 2） | `Glob ok` 1，`search` 0 增量 |
| B2-2 | …README.md 开头六行讲了什么？引用原文。 | ✅ 与文件逐字一致 | `Read ok` 1，`search` 0 增量 |
| B2-3 | examples 里有没有提到 refund 的地方？ | ✅ 给了 5 个文件的行，抽查 `payment-methods.md:27` 确实存在 | `Grep ok` 1，`search` 0 增量 |
| B2-4 | 帮我看看 https://example.com 这页在讲什么 | ❌ 答"我无法访问外部网页"，**一次检索都没发** | `WebFetch` 0，`search` 0 增量 |
| B7 | 部署拿不定，给两个选项让我选 | ❌ 没反问、没检索；它判断"部署超出我的边界"直接拒绝 | `AskUserQuestionTool` 0，`search` 0 增量 |
| B8 | 三件事 + 列个待办清单 | ❌ 正文里手写了 1/2/3 清单，工具一次没调 | `TodoWrite` 0，`search` 0 增量 |

### 三条对照（证明是"不知道可以找"，不是"不会用"）

| 对照 | 加的那一句 | 结果 |
| --- | --- | --- |
| B2-4 复跑 | 「你手边要是没有能抓网页的工具，就**自己去找一下**」 | ✅ 抓到了 example.com；`search` +1、`WebFetch` 1 |
| B8 复跑 | 「手边没有记待办的工具就**自己找一个**」 | ✅ `TodoWrite` 2 次（同时撞上 6 次总预算，回答是限额提示） |

同一句话、同一个模型、同一个工具，只差这一句提示——说明**票 31 删掉的那句
"需要的能力不在这里，就去取"是有功能作用的**，不能只按 BFCL 的分数判断它。

## 阻断：开长期记忆就 500

第一次按文档第 0 节起实例（`application-local.yml` 里 `memory.directory` 默认配着），
第一句 `POST /api/chat` 直接 500：

```
java.lang.IllegalStateException: No ToolCallback found for tool name: MemoryView
```

机制：`PrincipalMemoryAdvisor`（order `PRINCIPAL_MEMORY`）先把 6 个记忆工具注入这一轮；
`DisclosureToolCallingAdvisor`（order `TOOL_LOOP`，在它之后）**用检索清单整体替换**
`toolCallbacks`，只保留 `toolSearchTool` + `pinned` + 检索结果——记忆那 6 个被丢掉。
模型在 prompt 里看得见 `MemoryView`，调它时框架找不到 callback，于是 500。

现场：`main-app-memory-blocked.log`。**这一条要单独开票**，不属于"题目改人话"的范围。

## 这一批没跑的（如实列，不留一半）

为了让这一批能跑下去，`application-local.yml` 里 `memory` 三行被**临时注释掉**了
（跑完要恢复）。因此：

| 没跑 | 原因 |
| --- | --- |
| D2 / D3（跨会话记忆） | 记忆被临时关闭，验不了 |
| C2 / C3 / C4（审批） | 要换参数起实例（`required-tools`、`allowlist`、`ttl`） |
| C1 / A3 / A4（画像、人设、准入） | 要换实例（`users=t1,ethan`、`sales` 人设） |
| E1 / E2 / E3（故障与降级） | 要停 MCP server / `simulateSlowCall` / 撞预算 |
| F2–F8（模型层） | 要换实例（错误模型名、不可达 base-url 等） |
| G3 / H4–H6 | 要换实例（超长人设、`lane-wait=0`、`stream-timeout=2s`） |
| I（审计） | 需要跑完整一轮再看库；本批只跑了 A/B，量不够 |
| J（会话读侧） | 未跑 |

**B3 / B4 / B5 / B6 也没跑**：按本批的发现，`Skill` / `Task` 都不在常驻，不点名大概率也会
"不用它"；要么改题目（加"自己找"），要么先修提示词再跑，否则跑出来是同一个结论。

## 材料

| 路径 | 是什么 |
| --- | --- |
| `main-app.log` | 第三次起的实例（记忆已关）：A1/A2/B1/B2/B7/B8 与三条对照 |
| `main-app-memory-blocked.log` | 第一次起的实例（记忆开着）：500 的现场 |
| `a1.txt` / `a1b.txt` | A1 的两次（记忆开时答"没有工具"、关掉后答 128400） |
| `a2.txt` | A2 三条 |
| `b2-glob.txt` / `b2-read.txt` / `b2-grep.txt` / `b2-webfetch.txt` | B2 四条 |
| `b2-webfetch-hint.txt` / `b8-hint.txt` | 两条对照 |
| `b7.txt` / `b8.txt` | 两条失败现场 |

库：`claw4j_accept_1127`（新建）。实例已停。
