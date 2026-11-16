# 验收：我加的能力，到底生效了没有

> [README-adding-capabilities.md](README-adding-capabilities.md) 讲**怎么加**；这一份讲**怎么验**。
> 两份的八节一一对应：加了第几节的东西，就看这里的第几节。
>
> 机器向的全量回归清单在 [docs/capability-tests.md](docs/capability-tests.md)（每个工具、每个子 Agent
> 各一行，含库表断言与负向用例）。**这一份只给"刚加完，想确认它能用"的人**。

验收只有两条判据，缺一条都算没跑通：

1. **回答里的内容对得上**——具体数字、口径、出处，不是"看起来像"；
2. **`claw4j.tool.calls{tool=…}` 涨了**——这个工具真的被执行过。

模型的自述**不能当证据**：它会说"我调用了"，也确实可能没调；它会把上一轮的回答记成这一轮的。
下面每一节都写清"这次该看哪个数、哪行日志"。

---

## 0. 共同前提

```bash
set -a; . ./.env; set +a
./mvnw -q -DskipTests package

java -jar claw4j-app/target/claw4j-app-0.1.0-SNAPSHOT.jar \
  --spring.profiles.active=local \
  --claw4j.scripts.directory=examples/scripts \
  --claw4j.skills.directory=examples/skills \
  --claw4j.subagents.directory=examples/subagents \
  --claw4j.agent.directory=examples/agents --claw4j.agent.default=ops \
  --claw4j.cache.enabled=false
```

三个能力目录给全才是"八节都能验"。`agent.directory` 与 `default` 是 §4 与 §6 的前提
（口径写在人设的 `glossary` 里）；只用 Tool/MCP 时可以不给。

```bash
# t <文本>   发一轮。用户用 U 改，角色用 R 改
t() { curl -s --noproxy '*' -X POST localhost:8080/api/chat -H 'Content-Type: application/json' \
      -H "X-Claw4j-User: ${U:-me}" -H "X-Claw4j-Role: ${R:-admin}" -d "{\"text\":\"$1\"}"; }

# hit <工具名> [ok|denied|error]   这个工具被调用过几次
hit() { curl -s --noproxy '*' "localhost:9090/actuator/metrics/claw4j.tool.calls?tag=tool:$1&tag=outcome:${2:-ok}" \
        | python3 -c 'import json,sys
try: print(int(json.load(sys.stdin)["measurements"][0]["value"]))
except Exception: print(0)'; }
```

**没调用过时 `hit` 返回 0**——那个 URL 此时是 404，不是 0 次。

### 三条卫生规则（不知道就会把"缓存"和"记忆"验成能力）

1. **关语义缓存**（上面命令里已有）：命中缓存时这一轮**根本没有模型调用**，你验的是历史答案。
   实测过同一个问题换用户、换进程，拿到的是一字不差的旧回答。
2. **每次换一个 `U`**：同一个用户连问几轮会累积对话记忆，模型会"记得"上次失败而拒绝重试。
3. **先重灌种子**（只影响 §1/§4/§5 的数字）：种子日期是**迁移那一刻的本周一**算的，库放一周，
   "上周"就漂到别的窗口，数字全变——那不是模型算错。

```bash
# 数字对不上时先跑这两条，再看是不是代码问题
docker exec claw4j-postgres psql -U claw4j -d claw4j \
  -c "delete from business.order_item; delete from business.sales_order; delete from business.customer; delete from business.region;"
docker exec -i claw4j-postgres psql -U claw4j -d claw4j \
  < claw4j-example-sales/src/main/resources/db/migration/V2__business_seed.sql
```

---

## 1. Java 工具

**前提**：插件 jar 在 classpath 里（Maven 依赖，或文末 `loader.path`）。

**触发**（以自带的 `querySales` 为例）：

```bash
U=t1 t '上周华东区的成交额是多少'
```

**期望**：`128400`。然后换两个时间窗各问一次——**三条都对，才说明时间窗写对了**：

| 问句 | 期望 |
| --- | --- |
| 上周华东 | 128400 |
| 本周华东 | 99999 |
| 三周前华东 | 77777 |

**再确认执行过**：`hit querySales` 比刚才多 1。

**数字不对时先看哪里**：

- `hit` 是 0 → 工具**没被执行**。先看模型有没有检索到它：`U=t2 t '你能查销售数据吗'`，
  再看 `hit toolSearchTool` 有没有涨。零计数 + 回答里却说"我查了" = 模型在编。
- `hit` 涨了但数字错 → 问题在工具/口径（SQL、时区、`CANCELLED` 有没有排），不在装配。
- 你新加的工具**连清单都没进**：`@Bean` 的三个件（工具、权限、档位）漏一个就跑不动，
  档位漏了是**启动失败**，权限漏了是**每次都被拒**（`hit <工具> denied` 会涨）。

---

## 2. MCP server

**前提**：`application-local.yml` 里已经写了 `demo` / `github` / `mail` 三个（`local` profile 即连）。
接自己的 server 就照 [README-adding-capabilities.md](README-adding-capabilities.md) 第 2 节加一段 YAML。

**触发**：连问两次同一件事，中间隔两秒——

```bash
U=m1 t '用 demo_now 报一次 pid 与时间戳，只给数字'
sleep 2
U=m2 t '再用 demo_now 报一次 pid 与时间戳，只给数字'
```

**期望**：两次 **pid 相同**、**时间戳变大**。pid 相同 = 同一个外部子进程在答，
这就排除了"模型自己编了一个时间"。

**再确认**：`hit demo_now` 是 2；启动日志有一行
`MCP server 状态：demo → UP(工具 2 个)，github → UP(工具 3 个)，mail → UP(工具 2 个)`。

**跨进程往返不丢字**：`U=m3 t '用 demo_echo 把这句话原样带回来：跨进程往返不丢字-42'`，
回答里那句话要逐字出现。

**不对时先看哪里**：

- 日志 `broken → DOWN(15 秒后重试)`：括号里是**下次重试的时间**，原因在同一时刻旁边那行 WARN
  （`…连不上：Client failed to initialize by explicit API call`）。应用照常起，只是这一轮
  清单里没有它。之后按 15 秒起、每次翻倍、封顶 10 分钟重连。
- `npx` 起的 server 起不来：`request-timeout`（默认 30s）与 `initialization-timeout`（默认 60s）
  **两个都要调大**，`npx` 冷启动实测 36 秒。
- 要带 `Authorization` 的端点：头里只能写句柄（`Bearer secret:mcp_token`），裸写明文启动就被拒。

---

## 3. 脚本包

**前提**：`--claw4j.scripts.directory=examples/scripts`；**要读的文件必须在包目录里**。

**触发**：

```bash
U=s1 t '用 summarize 处理 sample.md，把工具返回的原文原样贴给我，不要总结、不要改写'
```

**期望**：输出里有 `sample.md` 的**原句**，逐字能对上：

```
- 华东区自签收之日起 7 天内可无理由退货，商品需保持完好、配件齐全、不影响二次销售。
- 华南区为 5 天，需提供原包装；
```

**再确认**：`hit summarize` +1。

**不对时先看哪里**：

- 问"三句以内"却要"逐字"，是**自相矛盾**：脚本默认输出 5 句，模型会压缩改写。
  验逐字就别限制句数。
- 参数被拒：`路径参数必须是相对路径，拿到的是 …` / `路径 … 越出了脚本包自己的目录`——
  把文件放进包目录、用包内相对路径。
- `weather-forecast` 要出网：本机 SSL 不通时 `hit weather-forecast error` 会涨，
  说明**脚本跑起来了**、缺的是网络，不是装配。
- 权限：动作是 `run_script`，默认只有 `admin` 有。用 `R=read-only` 问会被拒——
  这时 `hit <包名> denied` 涨、`ok` 不涨。

---

## 4. Skill

**前提**：`--claw4j.skills.directory=examples/skills`，**并且配了人设**
（`--claw4j.agent.directory=examples/agents --claw4j.agent.default=sales`）——
口径不在 Skill 里，在人设引用的 `glossary/metrics.md` 里。

**触发**：

```bash
U=k1 t '按 weekly-sales 的口径，给我上周各区成交额'
```

**期望**：三区 + 合计 **128400 / 26000 / 31500 / 185900**，且口径写的是
`sum(order_item.amount)` + 排除 `CANCELLED`。**再确认**：`hit Skill` +1。

**有两种"没出数"要分清**：

- 回答是"先确认口径"（问你是含税还是排除已取消）——**这不是失败**，是 Skill 正文里那句
  "没看到口径就先问"在起作用，原因是你的部署没配人设。配上再问一次。
- 直接给了个没出处的口径——那说明正文没加载，只是模型按常识答的。看 `hit Skill` 是不是 0。

---

## 5. 子 Agent

**前提**：`--claw4j.subagents.directory=examples/subagents`，**并且白名单里的工具在容器里**
（`weekly-digest` 要 `querySales` + `summarize`，后者要 `--claw4j.scripts.directory`）。

**触发**（两条典型）：

```bash
U=a1 t '派 capacity-triage 看一下这个服务的容量紧不紧'
U=a2 t '派 weekly-digest 写一份上周的周报'
```

**期望**：

| 子 Agent | 该看到什么 | 不该看到什么 |
| --- | --- | --- |
| `capacity-triage` | 堆 已用/上限 + 百分比（如 `105 MB / 8192 MB，1.3%`）、线程数 | 具体扩容数值（它只该给判断） |
| `weekly-digest` | 三区 + 合计 **185900**，环比写"不可比" | 编出来的环比、没有出数的"表现不错" |

**再确认**：`hit Task` +1。想看得更细就把 `--logging.level.org.springframework.ai=DEBUG` 打开，
会有一行 `委派 task 给 Subagent weekly-digest：它这趟能用的工具是 [querySales, summarize]`。

**不对时先看哪里**：

- **白名单点了容器里没有的工具**：启动不会失败，**第一次委派**才失败并点名。上面两条命令里
  少了 `--claw4j.scripts.directory` 时，`weekly-digest` 就是这样挂的。
- **预算**：一轮最多 6 次工具调用（`claw4j.agent.tool-call-limit`，可调），单个工具 3 次（不可调）。
  回答里出现 `Total tool call limit (6) exceeded` 就是撞了这个——它是熔断，不是额度。
  多区/多窗的活要写成**一条 SQL 一次取回**（`weekly-digest` 的定义里就是这么要求的）。
- **零计数**：先确认检索有没有把 `Task` 交出去（模型一开始只看得见 `toolSearchTool`），
  再看调用；别把"模型没派"当成"功能坏了"。

---

## 6. 人设（agent）

**前提**：`--claw4j.agent.directory=examples/agents --claw4j.agent.default=sales`（或 `ops`）。

**怎么验**（这条不用问业务问题，看日志就够）：

```
这个部署的 agent 是 sales（销售助手：…），人设来自 examples/agents；时区 Asia/Shanghai；
工具档位 MESSAGING（来自 人设文件的 tier）
```

三件事一眼可查：**选中的是哪份**、**人设从哪来**、**档位来自哪一层**（人设的 `tier` 还是全局配置）。

**行为上也验一次**：`U=p1 t '你能跑脚本吗？帮我跑个脚本算一下上周成交额'`——`sales` 的档位是
`messaging`，不该有脚本能力，回答应当拒绝而不是硬跑。

**不对时先看哪里**：

- 目录里有多份却没写 `default` → **启动失败**并列出候选：
  `人设目录里有 2 份定义，但没有写 claw4j.agent.default…必须点名：- ops… - sales…`。
- `tier` 只覆盖**档位**，不覆盖 `claw4j.tools.allow/deny`。想"全局 full 但这个人不能跑脚本"，
  写人设的 `tier: messaging`，不要在 allow/deny 上想办法。

---

## 7. 知识库

**前提**：`--spring.profiles.active=local` 已经默认带知识库（`examples/knowledge`，两条文档）。
别的部署要自己配 `claw4j.rag.enabled=true` + `directory`，并且有 pgvector 与 qwen key。

**触发一（能答且带出处）**：

```bash
U=r1 t '东西买了能退吗？华东和华南的期限分别是多少'
```

**期望**：华东 **7 天**、华南 **5 天**，并带 `[1]` 这样的出处；答里还会点出"按签收日算"这类
只在文档里写的细节——那是真的检索到了，不是常识。

**触发二（查不到就不编）**：

```bash
U=r2 t 'SKU-2024 这个商品现在还剩多少库存'
```

**期望**：明确说**查不到**，并说明为什么（没有库存表 / 那不是这个库能答的）。
如果它给了个像模像样的数字，就是这套东西最该被修的地方。

**看重排有没有真跑**：把 `io.github.claw4j` 开到 DEBUG，会有一行

```
重排完成：候选 2 条，服务返回 2 条，前三条 [<id>, <id>] → [<id>, <id>]
```

只有它出现了，才说明重排的响应被解析、顺序被应用过；`服务返回 0 条` 或整行不出现 = 没生效
（失败会另有一行 `重排失败（…），这一轮按候选原顺序继续`，那属于"降级但检索还在"）。

**不对时先看哪里**：

- 启动就失败并点名 `claw4j.rag.directory` → 开了却没配目录。这是刻意的：空知识库比启动失败更糟。
- 答"没有相关资料"但文档里明明有 → 先看 `知识库同步完成：…现有 N 段` 里 N 是不是 0；
  再看相似度下限（`claw4j.rag.min-similarity`，默认 0.5）是不是把结果挡掉了。

---

## 8. 通道

**前提**：HTTP 通道随应用就有；IM 通道要有你自己的凭据（飞书 / 钉钉 / 企微 / Slack 各自的配置）。

**HTTP 通道（本机就能验）**：

```bash
U=c1 t '你好'                    # 能问答
U=c2 R=guest t '上周华东成交额'   # 表级拒：
# 没有权限：角色 guest 不允许访问表 customer、order_item、sales_order（工具 querySales）
```

判据是**回答是那句拒绝、并且 `hit querySales denied` +1、`ok` 不涨**。
别用 `read-only` 验"被拒"——它的 `tables` 就是 `["*"]`，能正常查到数（实测 128400）；
默认三个角色里"看不到业务表"的是 `guest`（它只有 `region`）。

**IM 通道（要自己的凭据）**：发一条消息应收到回答；再让平台**重投同一条事件**
（钉钉 `msgId`、Slack `event_id`），回答**只出现一次**——幂等在 `channel.kernel` 里，
你的实现只要做到"拿到消息立刻 ACK + 用事件 id 去重 + 认不出人就拒绝"这三条。

HTTP 通道**没有事件 id**，所以"重复事件"这条验不了它：往它灌同一句话两次会再答一次
（可能命中语义缓存），那不是 bug，是它本来就没有幂等键。

**不对时先看哪里**：

- 没人能提问但通道连上了：角色表白名单里没有这个人（`claw4j.channel.*.roles`），
  日志里会有带 `sender=` 的 WARN。
- 重复投递答了两次：看事件 id 有没有被传进 `InboundEvent`；`channel.kernel` 只在有 id 时去重。

---

## 一页速查

| 能力 | 一句触发 | 主要判据 | 最容易踩的坑 |
| --- | --- | --- | --- |
| Java 工具 | `t '上周华东成交额'` | 128400 + `hit querySales` | 只看"答了数字"，不看计数 |
| MCP | 连问两次 `demo_now` | pid 相同、时间戳变大 | `npx` 超时两个键都要调 |
| 脚本包 | `t '用 summarize 处理 sample.md，原样贴回'` | 与原文逐字相同 | 限制"三句以内"就验不了逐字 |
| Skill | `t '按 weekly-sales 的口径给上周各区成交额'` | 185900 + `hit Skill` | 没配人设 → 它会先问口径（正常） |
| 子 Agent | `t '派 weekly-digest 写一份上周的周报'` | 三区 + 185900 + `hit Task` | 白名单工具缺失 → **首次委派**才失败 |
| 人设 | 看启动日志 | `agent 是 sales…档位 MESSAGING（来自 人设文件的 tier）` | 多份没写 `default` → 启动失败 |
| 知识库 | `t '东西买了能退吗'` / `t 'SKU-2024 还剩多少'` | 7 天 / 5 天带出处；后者说查不到 | 不看重排那行 DEBUG 就不知道它有没有生效 |
| 通道 | `t '你好'` / `R=guest t '上周成交额'` | 有回答；后者是表级拒且 `denied` +1 | HTTP 没有事件 id，验不了重复 |
