# 演示：三件事，从头到尾办完

这一份不是功能清单，是**三场戏的台词与判据**。每场五幕，演的是同一个东西：一个具体的麻烦从"有人开口"
到"事情办完、事后查得到"。能力是路上的必需品，不是展品。

三场戏各自主打不同的东西，合起来把仓库里的能力走了一遍：

| 场景 | 一句话 | 主打 |
| --- | --- | --- |
| **A 售后争议** | 客户要退一单 7 万 4 的货，到底能不能退 | 跨天嘱咐、流式、查库、**命令审批**、子代理、定时跟进 |
| **B 周度经营会** | 上周到底卖了多少，本周为什么只有一单 | 技能口径、Text-to-SQL、**口径守卫**、周期性任务 |
| **C 线上故障** | 那个 MCP 是不是挂了 | 现场采集、审批、**提权**、审计对账、上下文追问 |

每幕三段：**台词**（照着敲）→ **看到什么**（真实样张）→ **怎么算过**（数、编号、库里的行）。
判据一律不依赖措辞——模型每次说的都不一样，数字和编号才是证据。

---

## 0. 开演前

### 三个窗口

| 窗口 | 干什么 |
| --- | --- |
| **A** | 发消息（`curl`）。这是"用户" |
| **B** | 批审批、答口径（`curl`）。这是"另一个人"——审批与问答都必须第二个人，这不是缺陷，是设计 |
| **C** | 后台大屏：三条 SQL，让观众看见"意图、计划、审计"这些没有界面的东西 |

### 起实例（一次全开）

```bash
cd /Users/ethanbyang/projects/java/Claw4J
set -a; . ./.env; set +a
./mvnw -q -DskipTests package
java -jar claw4j-app/target/claw4j-app-0.1.0-SNAPSHOT.jar \
  --spring.profiles.active=local \
  --spring.datasource.url=jdbc:postgresql://localhost:5432/claw4j_demo \
  --claw4j.advisor.session.dm-scope=per-channel-peer \
  --claw4j.channel.http.users='*' \
  --claw4j.skills.directory=examples/skills \
  --claw4j.subagents.directory=examples/subagents \
  --claw4j.agent.directory=examples/agents --claw4j.agent.default=ops \
  --claw4j.tools.exec.security=allowlist --claw4j.tools.exec.ask=on-miss \
  --claw4j.tools.elevated.enabled=true --claw4j.tools.elevated.allow-from=d1,d2 \
  --claw4j.advisor.cache.enabled=false
```

四处不是可选的：

- **`exec.ask=on-miss`**：审批那两幕全靠它。默认 `off`，命令直接跑，没有"挂起等人"这回事。
- **`elevated.enabled=true` + `allow-from`**：C3 的提权要用。名单为空等于谁都不许（不是谁都许）。
- **`cache.enabled=false`**：语义缓存会把"看起来像"的问题判成同一个（附录有实测）。
- **一次性库**：三场戏会写会话、审计、跟进任务，跑完别在有人用的库上重演。

准备好之后确认一句：

```bash
curl -s --noproxy '*' localhost:9090/actuator/health/readiness
```

### 后台大屏（C 窗口，一直开着）

```bash
# 嘱咐：谁留的、触发词、触发过几次
docker exec -i claw4j-postgres psql -U claw4j -d claw4j_demo -c \
  "select id, owner, trigger_keywords, status, fire_count, max_fires
     from claw4j_session.standing_intents;"

# 计划：每段会话一张卡，revision 是"改过几次"
docker exec -i claw4j-postgres psql -U claw4j -d claw4j_demo -c \
  "select left(session_key,34) as sk, revision, left(steps_json,60) as steps
     from claw4j_session.session_progress_cards order by updated_at desc limit 3;"

# 审计：一轮的四个事件 + 工具执行有没有带审批编号
docker exec -i claw4j-postgres psql -U claw4j -d claw4j_demo -c \
  "select seq, action, status, tool_name, left(coalesce(approval_id,''),8) as approval
     from claw4j_run.audit_event order by seq desc limit 8;"
```

---

## A. 售后争议：一单 7 万 4 的退货

> 故事：周一你随口留了一句嘱咐。周三客户经理转来客户的要求。你手上没有任何"退款系统"，
> 只有这个助手：它得先问清两件事、查得到订单、算得出政策、还要为一个真实动作向你请示。

### A1（周一）留一句嘱咐，然后什么都不该发生

**台词**（A 窗口）：

```bash
curl -s --noproxy '*' -X POST localhost:8080/api/chat \
  -H 'Content-Type: application/json' -H 'X-Claw4j-User: d1' -H 'X-Claw4j-Chat-Id: a1' \
  -d '{"text":"留一条提醒：以后讨论退货的时候，先问我签收是哪天、货发到哪个城市。就记这一条，别的都不用做。"}'
```

**看到什么**：回执里带一个 8 位编号（本机实测 `4bafeb6e`），并复述了触发词与上限。
C 窗口那一行：`armed / 0 次 / 上限 3`。

**怎么算过**：

- 关心的是 **`status=armed` 且 `fire_count=0`**——它现在什么都不做，等事情发生。
- 触发器被模型拆成了几个词（`退货,退货政策,能退吗,退货窗口,无理由退货`）。这是它自己的判断，
  不必改；要收窄就在建的时候说清。
- 这一句是"按事件触发"，不是定时任务：**没有到点，只有遇到**。

### A2（周三）客户经理的一句话，先把嘱咐兑现

**台词**（A 窗口，**流式**——这一轮回答会长）：

```bash
curl -sN --noproxy '*' -X POST localhost:8080/api/chat/stream \
  -H 'Content-Type: application/json' -H 'X-Claw4j-User: d1' -H 'X-Claw4j-Chat-Id: a2' \
  -d '{"text":"客户要退货：上周四发的那单，旗舰版那个。能退吗？"}'
```

**看到什么**：一长串 `event:chunk`（本机实测 161 帧），最后 `event:done`。回答**没有直接答能不能退**，
而是先要两件事：

> 先确认两件事：**这单的签收日期**是哪天，以及**收货地址在哪个城市**（按收货地算区域……）

**怎么算过**：

| 判据 | 实测 |
| --- | --- |
| 帧数几十以上、`done` 恰好一次 | 161 帧 / 1 次 |
| C 窗口：`status=fired`、`fire_count=1` | 是 |
| 回答里出现"签收日期 / 收货城市"这类追问 | 是，且引了政策出处 |

**换一个 chat id 再说一遍同样的话，`fire_count` 会停在 1**（冷却 24 小时，这一条本身在
`README-capability-tests.md` G2 第③步验，演示里不重复演）。这是"说过一次就够"，
也是"别拿回答的措辞当判据"的原因——人设本身也会提醒口径，只有那个数分得清。

### A3 给事实，让它去查，别凭客户说的算

**台词**（A 窗口）：

```bash
curl -s --noproxy '*' -X POST localhost:8080/api/chat \
  -H 'Content-Type: application/json' -H 'X-Claw4j-User: d1' -H 'X-Claw4j-Chat-Id: a2' \
  -d '{"text":"8 天前签的，货发到杭州。别凭我说的金额：用 querySales 把这单查出来——上周四、华东、旗舰版，给我订单号、状态和金额。"}'
```

**看到什么**：它先按政策给出"不能无理由退"（8 天 > 华东 7 天，除非质量问题走 15 天），
然后**真的去查了库**，并且多报了一件没人问的事：

| 订单号 | 状态 | 下单日 | 金额 |
| --- | --- | --- | --- |
| SO-LAST-02 | SHIPPED | 2026-11-12（周四） | 74400.00 |
| SO-LAST-03 | CANCELLED | 2026-11-11（周三） | 50000.00 |

> 你说的 74400 对应的是 **SO-LAST-02**……另外提醒：SO-LAST-03 是已取消的，不计入成交额口径。

**怎么算过**：`claw4j.tool.calls{tool=querySales,outcome=ok}` 从 404 变成 1；
回答里的订单号、状态、金额与库一致。

**顺手把计划写下来**（这一句要点名工具，见"五个坑"第一条）：

```bash
curl -s -m 90 --noproxy '*' -X POST localhost:8080/api/chat \
  -H 'Content-Type: application/json' -H 'X-Claw4j-User: d1' -H 'X-Claw4j-Chat-Id: a2' \
  -d '{"text":"用 TodoWrite 工具把这四步记下来：查单（已完成）、按政策算（在做）、拟沟通稿、明天跟进。"}'
```

**看到什么**：C 窗口里出现这张卡（本机实测 `revision=1`，三个状态同时在）：

```
[{"step":"查单：确认订单号、状态、金额、签收日、收货城市","status":"COMPLETED"},
 {"step":"按政策算：跑 refund_quote.sh 得出可退金额与依据","status":"IN_PROGRESS"},
 {"step":"拟沟通稿：给客户的退货答复","status":"PENDING"},
 {"step":"明天跟进：回访/推进审批","status":"PENDING"}]
```

**判据**：`tool.calls{tool=TodoWrite}` ≥ 1；卡里是 `COMPLETED / IN_PROGRESS / PENDING` 三种状态，
`activeForm` 不在卡里（那是给模型的措辞）。再提交一次就变 `revision=2`。

> 这一幕还有个副产品：我故意给错参数（"5 天、可退"）让它更新状态，它**拒绝更新**，
> 指出这单是"8 天、超窗、不可退"，两边对不上。它不是照着指令盖章。

### A4 一个真实动作：跑脚本要你点头

这一轮要跑技能自带的脚本（会真的执行命令），所以它**必须**停下来问人。

**台词**（A 窗口）：

```bash
curl -s -m 60 --noproxy '*' -X POST localhost:8080/api/chat \
  -H 'Content-Type: application/json' -H 'X-Claw4j-User: d1' -H 'X-Claw4j-Chat-Id: a6' \
  -d '{"text":"客户又改口：5 天前签收。用 refund-triage 技能按 east、5 天、74400 重算一次。"}'
```

**看到什么**：回答里出现一条审批编号（本机实测 `9dd4511c`），并且**没有**脚本输出。C 窗口：

```bash
docker exec -i claw4j-postgres psql -U claw4j -d claw4j_demo -c \
  "select id, status, frozen_plan->>'command' as cmd from claw4j_run.approval_request
    where status='pending' order by requested_at desc limit 1;"
```

**B 窗口**（这是"另一个人"，`ethan` 在 `approval.approvers` 名单里）：

```bash
ID=<上面那个编号>
curl -s --noproxy '*' "localhost:8080/api/approvals/$ID" -H 'X-Claw4j-User: ethan'
curl -s --noproxy '*' -X POST "localhost:8080/api/approvals/$ID" \
  -H 'Content-Type: application/json' -H 'X-Claw4j-User: ethan' -d '{"approved":true}'
```

**怎么算过**（三条一起看）：

| 判据 | 实测 |
| --- | --- |
| 批准后脚本真的跑了 | `tool.calls{tool=Bash,outcome=ok}` 涨；会话里出现"已按批准的原命令执行完毕（EXIT=0）" |
| 这次批准被用掉了 | `select status, decision, consumed_at is not null from claw4j_run.approval_request where id='…'` → `allowed / allow-once / t` |
| 审计答得出"是哪条批准放行的" | `audit_event` 里 `tool.action.finished` 且 `status=succeeded` 的那行 `approval_id` 等于这个编号 |

**这一条验出过两个真 bug，值得记住形状**（都已修）：

1. 命令回执与"忙"的正文帧原来带着结束标记，用户只看到两个 `done`、一个字没有；
2. 批准之后交给模型的话只有"接着做"，**没有给出被批准的那条命令原文**——模型凭记忆重写，
   这次少了一个 `; echo "EXIT=$?"`，那次干脆去找路径；于是每次都生成一条新的待批，
   **连批三次、一次都没跑成**。现在那句话会把原文附上，并要求原样重发。

### A5 收尾：把结论写成人话，再让它在两分钟后自己回来看

**台词**（A 窗口）：

```bash
curl -s -m 150 --noproxy '*' -X POST localhost:8080/api/chat \
  -H 'Content-Type: application/json' -H 'X-Claw4j-User: d1' -H 'X-Claw4j-Chat-Id: a6' \
  -d '{"text":"两件事，都用工具做：① 用 Task 派给 complaint-writer 子代理，把这条结论写成给客户看的沟通稿（SO-LAST-02，华东，签收后 5 天，在 7 天窗口内，可无理由退，74400 全额，运费客户承担）；② 用 schedule_task 建一条循环任务：每 2 分钟查一次 SO-LAST-02 的状态，cron 写 0 */2 * * * *，tools 只给 querySales。"}'
```

**看到什么**：

- 一段可以直接发出去的沟通稿（现状 / 能做的 / 下一步与时间），不辩解、不承诺结论以外的事；
- 一条定时任务编号（本机实测 `b2d97454`），`cron=0 */2 * * * *`。

**怎么算过**：

| 判据 | 实测 |
| --- | --- |
| 子代理真的被派了 | `tool.calls{tool=Task}` +1；日志里有 `subagent:leaf：排除 9（deny [Task, schedule_task, …, TodoWrite]）`——它拿不到再派一层、也拿不到定时任务 |
| 定时任务到点自己跑 | 日志 `定时任务跑完 id=b2d97454…`；`agent_schedule.next_run_at` 往后推一档 |
| 它跑了一轮、还查了库 | 审计里出现 `session_key=…:cron:b2d97454…` 的四条事件（run 起止 + 工具起止） |

**注意**：cron 要 **6 段**（秒 分 时 日 月 周）。写 5 段的 `*/2 * * * *` 会被工具拒掉——
本机实测模型会自己指出"要 6 段"，这算它对；台词里直接写 6 段更省一轮。

---

## B. 周度经营会：数字、口径、和一句"帮我改改"

> 故事：周一早上要开会。老板要上周各区成交额，接着问"本周怎么就一单"，最后说"以后把取消的也算进去吧"。
> 这一场没有副作用，考的是**数字与口径的纪律**。

### B1 按技能的口径出周报

```bash
curl -s -m 90 --noproxy '*' -X POST localhost:8080/api/chat \
  -H 'Content-Type: application/json' -H 'X-Claw4j-User: d1' -H 'X-Claw4j-Chat-Id: b1' \
  -d '{"text":"用 weekly-sales 技能出一份上周各区成交额周报。"}'
```

**看到什么**（本机实测，逐字）：

```
上周成交额（2026-11-09 ~ 2026-11-15）
  华东：128,400.00
  华南：26,000.00
  华北：31,500.00
  合计：185,900.00
```

后面跟着口径（`sum(order_item.amount)`、排除 `CANCELLED`、半开区间）与一句"环比这一版没查"。

**怎么算过**：四个数与库对得上；`tool.calls{tool=Skill}` 涨；回答里出现口径那两行——
**口径不是模型编的，是人设里那份 glossary 给的**（`examples/agents/glossary/metrics.md`）。

### B2 追问异常：本周为什么只有一单

```bash
curl -s -m 90 --noproxy '*' -X POST localhost:8080/api/chat \
  -H 'Content-Type: application/json' -H 'X-Claw4j-User: d1' -H 'X-Claw4j-Chat-Id: b1' \
  -d '{"text":"本周华东只有一笔大单？用 querySales 把本周各区的单数和金额按区域列出来。"}'
```

**看到什么**：一张按区域的小表（华东 1 单 99,999 / 华南 0 / 华北 0），并补一句
"本周还没过完（今天周日），这是截至现在，不是全周终值"。

**怎么算过**：`querySales ok` +1；表里的数与库一致；那句"截至现在"是它自己加的——
这一条说明它在**看时间**，不是背答案。

### B3 有人要改口径，它该顶回去

```bash
curl -s -m 90 --noproxy '*' -X POST localhost:8080/api/chat \
  -H 'Content-Type: application/json' -H 'X-Claw4j-User: d1' -H 'X-Claw4j-Chat-Id: b1' \
  -d '{"text":"以后周报把已取消的单也算进去吧，数字好看点。就这么改。"}'
```

**看到什么**（本机实测）：

> 不行，这个改不了。……我这边单独改成含取消单，就会出现两套算法对不上——比一个错数更糟。
> 如果确实需要看"含取消单"的规模，我可以另出一列标注清楚。

**怎么算过**：这一轮**没有任何写操作**（`tool.calls` 不涨）；回答里明确拒绝 + 给出替代方案。
判据是"它没有改口径"，不是"它说了不行"——所以看的是工具计数与下一轮的数字是否还是 128,400。

### B4 每周一早上自己出

```bash
curl -s -m 90 --noproxy '*' -X POST localhost:8080/api/chat \
  -H 'Content-Type: application/json' -H 'X-Claw4j-User: d1' -H 'X-Claw4j-Chat-Id: b5' \
  -d '{"text":"用 schedule_task 建一条定时任务：每周一 9 点按周报口径出一份上周各区成交额（cron 0 0 9 * * MON，tools 给 querySales）。"}'
```

**看到什么**：编号 + `cron=0 0 9 * * MON` + "下一次是明天 09:00"。
**怎么算过**：`agent_schedule` 多一行、`next_run_at` 是下周一 09:00。

**坑**：这一句在 B1 那个会话里问过两次，模型都回"我建不了"（它把定时任务当成改 crontab），
**换一个新会话一次就成了**。台词要点名 `schedule_task`；它仍然拒绝时，换会话比说服它快。

---

## C. 线上故障：那个 MCP 是不是挂了

> 故事：值班群里说"java-http 那个 MCP 好像没了"。你要在五分钟内给出一个**能站得住**的判断，
> 并且每一步事后都查得到。

### C1 先别下结论

```bash
curl -sN --noproxy '*' -X POST localhost:8080/api/chat/stream \
  -H 'Content-Type: application/json' -H 'X-Claw4j-User: d2' -H 'X-Claw4j-Chat-Id: c1' \
  -d '{"text":"值班报障：java-http 那个 MCP 好像挂了，工具清单里没有它。先给个判断。"}'
```

**看到什么**：283 帧 + 一个 `done`；回答先说"不能凭工具清单里没有就下结论"，然后去读运行时状态
（线程数、堆、站点清单）。

**怎么算过**：帧数与 `done`；回答里出现**具体数值**（线程数 / 堆），不是"看起来正常"。
`claw4j.tool.calls{tool=systemStatus}` 涨。

### C2 按模板采集现场（这条要人批）

```bash
curl -s -m 60 --noproxy '*' -X POST localhost:8080/api/chat \
  -H 'Content-Type: application/json' -H 'X-Claw4j-User: d2' -H 'X-Claw4j-Chat-Id: c2' \
  -d '{"text":"先用 Skill 工具读 incident-brief 这份技能，再用 Bash 跑它的 scripts/collect.sh，参数给 8080 9090 8082。"}'
```

**看到什么**：审批编号（本机实测 `56cc9bb8`）。B 窗口批准后，**会话里出现脚本文本**：

```
值班简报 2026-11-22 21:06:19 (+0800)
  主机：Darwin arm64，负载 3.11 4.01 3.62
  127.0.0.1:8080  http=404（在听，不是 actuator）
  127.0.0.1:9090  health=UP    {"groups":["liveness","readiness"],"status":"UP"}
  127.0.0.1:8082  http=000（连不上）
  结论：上述 unreachable 的端口在这台机器上没有服务在听： 8082
```

**怎么算过**：`http=000` 就是"没人听"，不是"超时"；`8080` 的 404 是**正常**（actuator 不在这个端口）。
这一段最容易讲错，也是这份技能存在的理由：**现场长什么样，脚本说了算，不靠印象**。

### C3 这一段别再问我：提权

```bash
curl -s -m 30 --noproxy '*' -X POST localhost:8080/api/chat \
  -H 'Content-Type: application/json' -H 'X-Claw4j-User: d2' -H 'X-Claw4j-Chat-Id: c2' \
  -d '{"text":"/elevated full"}'
```

回一句"这一段跳过审批（到 HH:mm 自动失效）"。紧接着再跑一次采集：

```bash
curl -s -m 90 --noproxy '*' -X POST localhost:8080/api/chat \
  -H 'Content-Type: application/json' -H 'X-Claw4j-User: d2' -H 'X-Claw4j-Chat-Id: c2' \
  -d '{"text":"再用 Bash 跑一次 collect.sh，只测 8080 这一个端口。"}'
```

**怎么算过**：这一次**没有新的审批行**（`select count(*) from approval_request where owner='d2'` 不变），
命令照样执行；提权只对这一段有效，且到点自动失效（本机实测 30 分钟）。

### C4 事后对账：每一步都答得出"谁批的、跑没跑"

```bash
docker exec -i claw4j-postgres psql -U claw4j -d claw4j_demo -c \
  "select seq, action, status, tool_name, left(coalesce(approval_id,''),8) as approval
     from claw4j_run.audit_event where tool_name='Bash' order by seq;"
```

**本机实测**：四个 `succeeded`、六个 `failed`。**被拦下的每一次也有一对事件**
（`finished` 是 `failed`、没有编号）；四对成功里有三对带 `approval_id`，最后那一对是 C3 提权之后跑的，
**没有编号**——这正好是"提权"的痕迹。
一次审批在审计里因此是两对——这不矛盾，是"试过、被拦、批了、跑了"四个事实。

### C5 上下文还在：换个问法，结论能对上

```bash
curl -s -m 60 --noproxy '*' -X POST localhost:8080/api/chat \
  -H 'Content-Type: application/json' -H 'X-Claw4j-User: d2' -H 'X-Claw4j-Chat-Id: c2' \
  -d '{"text":"把刚才这次排查的结论用一句话总结，并说明 8082 最后是什么状态。"}'
```

**看到什么**：三项状态一次说清（8080 在听但探活路径不对 / 9090 UP / 8082 没人听），与前面一致。
**怎么算过**：数字与前面两轮相同；这一轮**没有**再跑一次脚本（`tool.calls` 不涨）——它靠的是上下文。

---

## 能力 × 幕

| 能力 | A | B | C |
| --- | --- | --- | --- |
| 事件条件嘱咐（意图） | A1 建 / A2 命中 | | |
| 流式（SSE） | A2 | | C1 |
| 计划（`TodoWrite` + 进度卡） | A3 补的那一句 | | |
| Text-to-SQL（`querySales`） | A3 | B1–B3 | |
| 知识库 + 出处 | A2–A4 | | |
| 技能（`SKILL.md` + 脚本） | A4 `refund-triage` | B1 `weekly-sales` | C2 `incident-brief` |
| 命令审批（挂起 → 批准 → 兑现） | A4 | | C2 |
| 提权（这一段不问） | | | C3 |
| 子代理（白名单隔离） | A5 `complaint-writer` | | |
| 定时任务（到点自己跑） | A5 每 2 分钟 | B4 每周一 9 点 | |
| 审计对账 | A4 | | C4 |
| 上下文 | A3 | B2 | C5 |
| 口径守卫（拒绝改口径） | | B3 | |

留白是有意的：**每场戏只演自己那条线**，观众不会在同一分钟里看两件不相干的事。

---

## 五个坑（都会在演示现场出现）

1. **点名工具**。渐进式披露下，模型只看见一小撮工具；你说"交给子代理"它可能回"我没有这个通道"。
   台词里写清 `用 Task` / `用 schedule_task` / `用 Skill 读 …`，一次就过。
2. **换会话比说服它快**。同一个会话里被它拒过一次，后面它会沿用那个结论（B4 实测：问两遍都说"建不了"，
   新会话一次成）。
3. **cron 是 6 段**。`*/2 * * * *` 会被拒；`0 */2 * * * *` 才是"每两分钟"。
4. **知识库会无条件注入**。问到没有资料的话题时，它会先说一句"这批资料和你的问题无关"。
   选有知识库覆盖的话题（售后、保修、账期），或者把 `claw4j.rag` 的目录收窄。
5. **HTTP 没有推送面**。审批续跑与定时任务的结果落在会话与审计里，**不在你那个 curl 响应里**。
   要"看见"，就看 C 窗口那三条 SQL，或者接飞书。

---

## 故意不演的

| 能力 | 为什么不在戏里 | 单独怎么验 |
| --- | --- | --- |
| 语义缓存 | **开过一次就出过事**：把"留一条退货提醒"和"客户要退货能退吗"判成同一个问题，0 次模型调用直接回了上一轮的答案。同话题不同请求在 0.93 阈值下会撞 | 见 `README-capability-tests.md` D1（关缓存验效果，开缓存验命中） |
| 主备换家 / 冷却 | 运维属性，塞进业务故事里像在演灾难 | 同上 F2–F4 |
| 提示词预算 | 同上：它是"别悄悄撑爆"，不是用户能感知的能力 | 同上 G3 |
| 守卫（敏感词） | 拒答会把戏打断，且演示要看"事情办完" | 同上 C5 附近 |
| 真 `steer`（把新消息插进正在跑的一轮） | 没有这个接缝：工具循环在框架内部递归 | 同上的"不做"清单 |

一句话：**戏里演的必须是"用户能感知的能力"**；可观测、可配置、可降级这些是运维面的事，
放验收文档里验，别放演示里讲。

---

## 收尾：这一场留下了什么

跑完三场，库里有：1 条嘱咐（触发过 1 次）、1 张进度卡、2 条定时任务、6 条审批
（3 条已兑现、1 条批了但没用上、1 条被拒、1 条过期）、一百五十多条审计
（其中 36 条是那条每两分钟的任务自己跑出来的）。
**这就是"企业级"的样子**：不是答得漂亮，是事后查得到。

清场（想重演一遍时）：

```bash
docker exec claw4j-postgres psql -U claw4j -d postgres -c "drop database if exists claw4j_demo;"
docker exec claw4j-postgres psql -U claw4j -d postgres -c "create database claw4j_demo;"
```
