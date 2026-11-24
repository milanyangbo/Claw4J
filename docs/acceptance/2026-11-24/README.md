# 验收材料（2026-11-24 这一批：A–I 全量复跑，修复后）

对应文档：仓库根 `README-capability-tests.md` 的 **A–I 九节全部**。上一批（`2026-11-23`）I 节因
`/api/audit` 没注册跑不了；这一批是修完票 18 / 票 19 之后**从头再跑一遍**，用干净库
（`claw4j_accept_1124`，首次同步 111 份 / 440 段）。

**结论**：A–I 全部通过。跑的过程中又抓到一个 bug（子链工具写两遍审计），已修（票 19）。

## 材料清单

| 路径 | 是什么 |
| --- | --- |
| `main-app.log` | 主实例（能力全开、RAG 开）：A1/A2/A4、B1–B8、C5、D1–D3、E1–E3、F1/F8、H1–H3、I 节 |
| `a3-users.log` | A3：`users=t1,ethan` |
| `c1-messaging.log` / `c1-toolfilter.log` | C1 两条路径（画像 / server 过滤） |
| `c2-approval.log` | C2/C3/C4 + I6（`messages=true`、`ttl=60s`） |
| `f2-model404.log` / `f4-unreachable.log` / `f5-allow.log` / `f6-api.log` / `f6-models.log` / `f7-utility.log` | F2–F7 |
| `g3-big.log` | G3（超长人设） |
| `h5-busy.log` / `h6-timeout.log` | H5（`lane-wait=0`）/ H6（`stream-timeout=2s`） |
| `dup-probe*.log` | 定位票 19 的三轮探针日志（`DUP-PROBE` / `TOOL-PROBE`，探针代码已删） |
| `dup-fixed*.log` | 票 19 修完的复验：连派两次子 Agent，`duplicates` 全程 0 |
| `stream-*.txt` | 原始 SSE 帧：basic / new / failover / busy-a / busy-b / timeout |
| `mcp-java-http.log` | E1 用的 MCP server |
| 库 `claw4j_accept_1124` | 358 条审计、5 条审批、1 条意图、2 张进度卡 |

## 实测到的值（复跑会变，只用来对形状）

| 节 | 本次实测 |
| --- | --- |
| A1 / A2 | 清单含 `querySales`/`Task`/MCP 工具；`demo_now` 两次同 pid（83688）、时间戳递增 |
| A3 / A4 | `nobody` 401 点名；agent `ops`、画像 FULL（来自 tier） |
| B1 | 128400 / 99999 / 77777；`querySales ok` 3 |
| B2 | Glob/Read/Grep/WebFetch 各 1；`Write/Edit 未开`；`WebSearch 未配 key` |
| B3 / B4 | 128400 / 26000 / 31500 / 185900；脚本输出逐字一致；`Skill ok` 1、`Bash ok` 1 |
| B5 | capacity-triage（堆 2.7%、线程 103、磁盘未取到）；weekly-digest 185900 + 主动纠正子 Agent 的环比；`Task ok` 2 |
| B6 | 十条全对（7/5 天、320 张 68 元、按实际成交价、万分之五、9 折直接给且注明旧版废止、12 个月、30 天、三近义各答各的、库存说查不到）；29 次重排 0 降级 |
| B7 / B8 / G1 | 挂起→204→"你选了生产"，异人 409；`TodoWrite ok` 2 且状态按 IN_PROGRESS→COMPLETED 推进（撞 6 次预算）；卡 `revision=2` 三步状态齐 |
| G2 | `a2402858 / it2 / 客户数 / armed→fired(1)→冷却内仍 1→cancelled`，embedding null |
| C1–C5 | 画像 MESSAGING 与 tool-filter（3→2）都生效；审批三判据齐、`always` 落 `uname` 后换参数不问（2→2）；自批 403 / 非审批人 403 / 名单外 401 / 过期 409；TodoWrite 挂起后批准执行；内网被判拒、出站剥离 |
| D1–D3 | 同句两次 `querySales` 8→10；同人记得公司名、换人不知道；种子三窗与库对得上 |
| E1–E3 | DOWN(15s)→第 5 次重试恢复（15/30/60/120/240）→ 工具 3 个；超时 33 秒 500 且快工具仍成功；单工具 3 次、总预算 6 次都拦住 |
| F1–F8 | 换家 1 次→冷却后停在 2；不可达 62 秒、`route_unavailable`、`model.calls` 总 2；三种写错都点名；`utility=独立链`；cache-read 有数 |
| G3 | WARN 一行 `22479 → 20106`，第二问不刷 |
| H1–H6 | 87 帧/1 done；`/new` 不动 model.calls；工具静默（流里 0 命中）；流式换家；忙；断流 217 帧 0 done、2.33 秒 |
| I1–I9 | 四行倒序、毫秒、actor 对象、无 `runId`；四变体逐格符合；三条冲突 400 + 坏游标 400；两页不重叠、`limit=0` 钳 1；`INSERT 0 1`/`0 0`；工具行 71 位 `sha256:`、运行行全空；计数全 0 |

## 这一批抓到的 bug

**票 19：子链的工具被包两层，同一次调用写两遍审计**。表现为 `claw4j.audit.duplicates`
每委派一次涨 2。探针拿到同一 `toolCallId` 进两次，第二次栈是
`Metered → Sanitizing → Metered`。根因是 `collectToolCallbacks` 无条件再包一层，而子链接到的
provider 交出来的是 `Sanitizing(Metered(…))`。修法：收集幂等（穿透装饰器判断），
`UntrustedToolCallback` 加 `unwrap`。修完连派两次子 Agent，`duplicates` 全程 0。

## 没验到的

- **I4 的 `limit=1000 → 500`**：库里只有 358 行，够不着上限。钳位在 `Filter` 的 compact
  构造器里，单测覆盖，端到端没验。
- **I 的 30 天 / 10 万行清理**：要等时钟。

## 起实例

与上一批同一个脚本（`2026-11-23/run-instance.sh`），只是库换成 `claw4j_accept_1124`。
除主实例外全部 `RAG=false`。

## 收掉

```bash
docker exec claw4j-postgres psql -U claw4j -d postgres -c "drop database if exists claw4j_accept_1124;"
```

库先留着（358 条审计是修完票 18 之后的第一手现场）。
