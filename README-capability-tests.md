# 验收：我加的能力，到底生效了没有

[README-adding-capabilities.md](README-adding-capabilities.md) 讲**怎么加**；这一份讲**怎么验**。

验收只有两条判据，缺一条都算没跑通：

1. **回答里的内容对得上**——具体数字、口径、出处，不是"看起来像"；
2. **`claw4j.tool.calls{tool=…}` 涨了**——这个工具真的被执行过。

模型的自述**不能当证据**：它会说"我调用了"，也确实可能没调；它会把上一轮的回答记成这一轮的。
下面每一条都写清"这次该看哪个数、哪行日志"。

**I（审计）那一节是例外**：它验的不是"回答对不对"，而是 `/api/audit` 返回的那一行——模型说"我已经
记录"完全不算，库里没有就是没有。

## 怎么用这一份

按**验什么**分九类，不按"加了什么"分。加完东西想找对应的一条，看最后一列：

| 类 | 验什么 | 加了什么之后看 |
| --- | --- | --- |
| **A 装配** | 接没接上：在不在清单、连没连上、认不认得出人 | 工具 A1、MCP A2、通道 A3、人设 A4 |
| **B 能力** | 真能用吗：回答对不对、计数涨没涨 | 工具 B1 / B2、Skill B3、脚本 B4、子 Agent B5、知识库 B6（十条）、问人 B7、计划 B8 |
| **C 权限与边界** | 看不见的、要人批的、谁能批的、外部内容 | 工具 C1、命令 C2 / C3、其它工具 C4、WebFetch C5 |
| **D 排除干扰** | 缓存 / 记忆 / 种子造成的假阳性 | 每一类都可能撞上 |
| **E 故障与降级** | 掉线、超时、熔断时长什么样 | MCP E1、工具 E2 / E3 |
| **F 模型层** | 主备换家、冷却、白名单、角色、缓存命中（`claw4j-model`） | 模型 F1–F8 |
| **G Agent 自管理** | 计划落库、嘱咐按事件触发、提示词超限会喊（票 01–03） | 进度卡 G1、意图 G2、预算 G3 |
| **H 流式** | 分帧、命令不进模型、工具静默、换家、忙、断流 | SSE H1–H6 |
| **I 审计** | 出事后答得出四问：谁干的、成没成、为什么被拦、东西送到没有 | 跑一轮查 I1、四种变体 I2、冲突 I3、分页 I4、幂等 I5、消息行 I6、调用编号 I7、子 Agent I8、写失败 I9 |

工具观测器（一个 `ToolCallListener` bean）的验法在
[README-adding-capabilities.md](README-adding-capabilities.md) 第 9 节末尾。

实测留下的东西（日志、每轮原始 SSE 帧、覆盖配置、G3 的超长人设）在
[docs/acceptance/](docs/acceptance/README.md) —— 按日期分批（现有 2026-11-22 一批）。
复跑前先看那份索引，里面有起实例命令、查库命令和上一轮记下的数（用来对形状，不是判据）。

---

## 0. 共同前提

### 起一个能力全开的实例

```bash
set -a; . ./.env; set +a
./mvnw -q -DskipTests package

java -jar claw4j-app/target/claw4j-app-0.1.0-SNAPSHOT.jar \
  --spring.profiles.active=local \
  --claw4j.advisor.session.dm-scope=per-channel-peer \
  --claw4j.channel.http.users='*' \
  --claw4j.skills.directory=examples/skills \
  --claw4j.subagents.directory=examples/subagents \
  --claw4j.agent.directory=examples/agents --claw4j.agent.default=ops \
  --claw4j.advisor.cache.enabled=false
```

三个目录给全，A/B 两类才都能验。`agent.directory` 与 `default` 是 B3 与 A4 的前提（口径写在人设的
`glossary` 里）；只验工具与 MCP 时可以不给。

`http.users='*'` 是**验收姿态**：这一份要换着几十个用户问（`t1`、`m1`、`r5`…），逐个填名单填不完。
代价是 A3（通道准入）在这一份上验不了——它要的就是"名单里有、名单外没有"，另起一份收紧名单即可（见 A3）。
另外注意人设的 `tier` 会覆盖 `claw4j.tools.profile`，C1 要用另一份没有 `agent.directory` 的实例。

**建议指向一个一次性库**：验收会建索引、写会话，用过的库再跑一遍，"跳过 N 份"这类数字就跟上次
混在一起。做法是另给一个库名（`--spring.datasource.url=jdbc:postgresql://localhost:5432/<临时库>`），
Flyway 从零建表，跑完 `drop database` 收掉。别在有人用过的库上验索引行为，也别把临时库名写进配置。

`dm-scope` 那一行不是可选的：本机的通道准入名单里不止一个人，而 `dm-scope=main` 会把不同人的
私聊并成一段上下文，启动时的交叉校验会直接拒绝（ADR-0032）。改名单或改 scope，二选一，
不改就起不来。

**最后一行必须带上**：语义缓存默认是开的，命中时这一轮没有模型调用——你验到的是上次的回答，
计数一次都不涨，而"计数不涨"恰好是这一页最主要的判据。它的另外两个害处：失败也会被缓存
（"我做不到"当天会被反复复用）；Redis 连不上直接启动失败（验能力时不该被这一层挡住）。
关掉只影响缓存，向量模型那一套是独立的装配，知识库照常工作。**这是验证姿态，不是配置建议。**

### 发一轮对话

```bash
curl -s --noproxy '*' -X POST localhost:8080/api/chat \
  -H 'Content-Type: application/json' \
  -H 'X-Claw4j-User: t1' \
  -d '{"text":"上周华东区的成交额是多少"}'
```

回答在返回 JSON 的 `text` 字段里。`X-Claw4j-User` 就是"我是谁"，**`t1` 只是个代号**：
每条、每次复测都换一个（`t2`、`m1`…），否则模型会"记得"上一轮失败而拒绝重试。
**它必须出现在 `claw4j.channel.http.users` 白名单里**：那份名单默认空，空的意思是谁都不能进
（0 节那份实例写的是 `'*'`，所以换什么代号都能进）。

### 查工具被执行了几次

计数在管理口（9090，只绑 `127.0.0.1`）：

```bash
curl -s --noproxy '*' \
  'localhost:9090/actuator/metrics/claw4j.tool.calls?tag=tool:querySales&tag=outcome:ok'
```

```json
{"name":"claw4j.tool.calls","measurements":[{"statistic":"COUNT","value":1.0}]}
```

- `outcome` 只有 `ok` / `error`。**没有 `denied`**：被拦下的调用根本没执行，走不到计数这一层。
  被审批拦下的那一次算 `error`——工具被调用了，命令没跑。
- **没调用过时这个 URL 返回 404**（不是 `value: 0`）。"404"和"计数 0"是一回事。

---

## A. 装配：接上了吗

### A1 工具进了清单

问一句只有这个工具能答的话，然后看计数从 404 变成 1.0。计数涨了才算接上；回答里提到工具名不算。

**接不上的三种样子，分开看：**

| 现象 | 含义 |
| --- | --- |
| 回答"我没有这个工具"，计数 404 | 工具**不在清单里**：档位漏了（启动会失败）或画像把它筛掉了（C1） |
| 计数 404，但回答里说"我查了" | 模型没调工具，是编的 |
| 启动直接失败并点名 | 工具没登记档位——这是刻意的，缺档位就不许上线 |

### A2 MCP 连上了

前提：`application-local.yml` 里写了四个 server（`demo` / `github` / `mail` 走 stdio，`java-http` 走 HTTP），
`local` profile 即连。接自己的 server 照 adding 第 2 节加一段 YAML。

```bash
curl -s --noproxy '*' -X POST localhost:8080/api/chat \
  -H 'Content-Type: application/json' -H 'X-Claw4j-User: m1' \
  -d '{"text":"用 demo_now 报一次 pid 与时间戳，只给数字"}'

sleep 2

curl -s --noproxy '*' -X POST localhost:8080/api/chat \
  -H 'Content-Type: application/json' -H 'X-Claw4j-User: m2' \
  -d '{"text":"再用 demo_now 报一次 pid 与时间戳，只给数字"}'
```

**期望**：两次 **pid 相同**、**时间戳变大**。pid 相同 = 同一个外部子进程在答，
排除了"模型自己编了一个时间"。

```bash
# 计数应为 2.0；启动日志另有一行 UP(工具 N 个)
curl -s --noproxy '*' \
  'localhost:9090/actuator/metrics/claw4j.tool.calls?tag=tool:demo_now&tag=outcome:ok'
```

**跨进程往返不丢字**：

```bash
curl -s --noproxy '*' -X POST localhost:8080/api/chat \
  -H 'Content-Type: application/json' -H 'X-Claw4j-User: m3' \
  -d '{"text":"用 demo_echo 把这句话原样带回来：跨进程往返不丢字-42"}'
```

那句话要逐字出现。

没连上、重连、掉线，见 E1。

### A3 通道准入

HTTP 通道随应用就有。**名单外的人被挡在门外**（把用户名换成一个不在 `users` 里的）：

```bash
curl -s --noproxy '*' -X POST localhost:8080/api/chat \
  -H 'Content-Type: application/json' -H 'X-Claw4j-User: nobody' \
  -d '{"text":"上周华东成交额是多少"}'
```

**期望**：`401` 加一句点名的话，且 `claw4j.tool.calls` 一次都不涨（模型没被调用）。

**这一条要另起一份实例**：0 节那份是 `users='*'`（为了换着几十个代号问），谁都能进。
收紧成两个人再问 `nobody`：

```bash
--claw4j.channel.http.users=t1,ethan
```

```
{"error":"用户不在 claw4j.channel.http.users 白名单里：nobody。…"}
```

IM 通道要自己的凭据：发一条消息应收到回答；再让平台**重投同一条事件**（钉钉 `msgId`、Slack `event_id`），
回答**只出现一次**。HTTP 通道没有事件 id，验不了重复——往它灌同一句话两次会再答一次，
那不是 bug，是它本来就没有幂等键。

### A4 人设选对了哪一份

不用问业务问题，看启动日志：

```
这个部署的 agent 是 sales（销售助手：…），人设来自 examples/agents；时区 Asia/Shanghai；
工具档位 MESSAGING（来自 人设文件的 tier）
```

三件事一眼可查：**选中的是哪份**、**人设从哪来**、**档位来自哪一层**（人设的 `tier` 还是全局配置）。

目录里有多份却没写 `default` → **启动失败**并列出候选。

---

## B. 能力：真能用吗

### B1 业务工具（以自带的 `querySales` 为例）

```bash
curl -s --noproxy '*' -X POST localhost:8080/api/chat \
  -H 'Content-Type: application/json' -H 'X-Claw4j-User: t1' \
  -d '{"text":"上周华东区的成交额是多少"}'
```

换两个时间窗各问一次（用户换 `t2`、`t3`）——**三条都对，才说明时间窗写对了**：

| 问句 | 期望 |
| --- | --- |
| 上周华东 | 128400 |
| 本周华东 | 99999 |
| 三周前华东 | 77777 |

再看 `querySales` 的 `ok` 计数：比刚才多 1。

**不对时先看哪里**：

- 计数 404 → 工具没被执行。换一个新用户问"你能查销售数据吗"，再看 `toolSearchTool` 的计数有没有涨。
  零计数 + 回答里说"我查了" = 模型在编。
- 计数涨了但数字错 → 问题在工具本身（SQL、时区、`CANCELLED` 有没有排），不在装配。

### B2 默认工具：Read / Grep / Glob / WebFetch / WebSearch

不用配，启动就在清单里（写文件的那两个要开开关，见下）。判据是**工具真的被执行了**，
不是回答里提到了文件名。

```bash
# Glob：找到真实存在的文件
curl -s --noproxy '*' -X POST localhost:8080/api/chat \
  -H 'Content-Type: application/json' -H 'X-Claw4j-User: d1' \
  -d '{"text":"用 Glob 找 examples 目录下的 .md，path 传 examples，把返回原样贴出来"}'

# Read：读出真实内容（路径要是绝对路径）
curl -s --noproxy '*' -X POST localhost:8080/api/chat \
  -H 'Content-Type: application/json' -H 'X-Claw4j-User: d2' \
  -d '{"text":"用 Read 读 /绝对路径/examples/README.md 前 6 行，原文贴出来"}'

# Grep：搜文件内容
curl -s --noproxy '*' -X POST localhost:8080/api/chat \
  -H 'Content-Type: application/json' -H 'X-Claw4j-User: d4' \
  -d '{"text":"用 Grep 在 examples 目录里搜 refund，把匹配的行贴出来"}'

# WebFetch：抓一个确定的网页
curl -s --noproxy '*' -X POST localhost:8080/api/chat \
  -H 'Content-Type: application/json' -H 'X-Claw4j-User: d3' \
  -d '{"text":"用 WebFetch 抓 https://example.com，prompt 用「这一页在说什么」"}'
```

**期望**：`Glob` 回来的路径在磁盘上真的存在（`ls` 一下）；`Read` 回来的前几行与文件一致；
`Grep` 的匹配行在文件里搜得到；`WebFetch` 给的是摘要而不是 HTML。计数各涨 1。

**`WebFetch` 有个前提**：模型第一轮只看得见检索工具（`toolSearchTool`），其余工具要它自己检索出来。
所以直接问"用 WebFetch 抓…"时，它偶尔会回**"我没有这个工具"**——那是它没检索，不是工具没装。
判据用这一句（先检索，再抓）：

```bash
curl -s --noproxy '*' -X POST localhost:8080/api/chat \
  -H 'Content-Type: application/json' -H 'X-Claw4j-User: d3' \
  -d '{"text":"先用工具检索找到 WebFetch，再用 WebFetch 抓 https://example.com，prompt 用「这一页在说什么」"}'
```

回"没有这个工具"时让它检索一次：检索结果里出现 `WebFetch` 就说明它在清单里（F 那一节也用这个办法看清单）。

- **写文件默认是关的**：不开时问「用 Write 写一个文件」，模型应该说没有这个工具；
  要开就 `CLAW4J_FILES_WRITE_ENABLED=true` 重启，启动日志会写「Write / Edit 已开」。
- **`WebSearch` 要 key**：没配 `BRAVE_API_KEY` 时根本不装配（启动日志写着「WebSearch 未配 key，未装」）。
- **读不到文件**：启动日志那行「文件工具的边界目录：…」决定了模型能看哪里。读到
  「Path is outside the allowed directories」就是路径不在那个目录下（默认是**启动时**的目录）。

### B3 Skill：正文真的进了上下文吗

前提：`--claw4j.skills.directory=examples/skills`，**并且配了人设**
（`--claw4j.agent.directory=examples/agents --claw4j.agent.default=sales`）——口径在人设引用的
`glossary/metrics.md` 里，不在 Skill 里。

**还要有加载标记**：`examples/skills/.review/weekly-sales.properties`（`status=approved`、`by=谁`、
`sha256=` 那一份目录的摘要）。没有它就**不加载**，启动日志会说"未审批或内容已变更"。

```bash
curl -s --noproxy '*' -X POST localhost:8080/api/chat \
  -H 'Content-Type: application/json' -H 'X-Claw4j-User: k1' \
  -d '{"text":"按 weekly-sales 的口径，给我上周各区成交额"}'
```

**期望**：三区 + 合计 **128400 / 26000 / 31500 / 185900**，且口径写的是
`sum(order_item.amount)` + 排除 `CANCELLED`；`Skill` 的 `ok` 计数涨 1。

**两种"没出数"要分清**：

- 回答是"先确认口径"——**这不是失败**，是正文里那句"没看到口径就先问"在起作用，原因是没配人设。
- 直接给了一个没出处的口径——正文没加载，模型按常识答的。看 `Skill` 计数是不是 404。

### B4 技能里的脚本：跑的是 `Bash`，不是某个工具

脚本不是工具：`summarize` 是一份技能，模型调 `Skill` 拿到正文（含"脚本在哪"），
再用 `Bash` 跑 `bash <技能目录>/scripts/run.sh sample.md`。

```bash
curl -s --noproxy '*' -X POST localhost:8080/api/chat \
  -H 'Content-Type: application/json' -H 'X-Claw4j-User: s1' \
  -d '{"text":"用 summarize 技能处理它自带的示例文件 sample.md，把脚本返回的内容原样贴出来（这是演示用的示例文件，可以直接贴）"}'
```

**期望**：输出里有 `sample.md` 的**原句**，逐字能对上：

```
摘要（5 句，摘自 5 句原文）：
  - # 售后政策摘要样本

华东区自签收之日起 7 天内可无理由退货，商品需保持完好、配件齐全、不影响二次销售。
  - 华南区为 5 天，需提供原包装；
```

第一句会把标题行一起带进来——脚本按句切分，标题也是一个"句子"。判据是**原文逐字对得上**，
不是排版对不对得上上面这段。

**"原样贴出来"这句话会招来拒绝**：有的模型把它理解成"导出内部文档原文"，直接回一句"这属于外泄"。
说清"这是技能自带的示例文件"就过；换成"按它自己的口径总结"也能过，但那样就验不了逐字。

判据是 `Bash` 的 `ok` 计数涨 1——**没有 `summarize` 这个工具**。

**不对时先看哪里**：

- **`Skill` 计数是 0**：技能没加载。看启动日志"技能未审批或内容已变更，未加载：summarize"——
  改一个字摘要就变，旧标记作废，重新算一份。
- **`Bash` 报 `No such file or directory`**：模型把脚本路径写成了相对路径。正文里那句
  `Base directory for this skill:` 就是让它拼绝对路径的。
- **`退出码=1`，stderr 只有 `UnicodeEncodeError: 'ascii' codec`**：子进程没有 locale（只给了 `PATH`），
  旧的 `python3` 会把中文打到 ASCII 上直接崩。脚本入口写 `PYTHONIOENCODING=utf-8`。
- **问"三句以内"却要"逐字"** 是自相矛盾：脚本默认输出 5 句，模型会压缩改写。

### B5 子 Agent：结论只能来自它的白名单

前提：`--claw4j.subagents.directory=examples/subagents`，且白名单里的工具在容器里
（`weekly-digest` 要 `querySales` + `Skill` + `Bash`）。

```bash
curl -s --noproxy '*' -X POST localhost:8080/api/chat \
  -H 'Content-Type: application/json' -H 'X-Claw4j-User: a1' \
  -d '{"text":"派 capacity-triage 看一下这个服务的容量紧不紧"}'

curl -s --noproxy '*' -X POST localhost:8080/api/chat \
  -H 'Content-Type: application/json' -H 'X-Claw4j-User: a2' \
  -d '{"text":"派 weekly-digest 写一份上周的周报"}'
```

| 子 Agent | 该看到什么 | 不该看到什么 |
| --- | --- | --- |
| `capacity-triage` | 堆 已用/上限 + 百分比、线程数 | 具体扩容数值（它只该给判断） |
| `weekly-digest` | 三区 + 合计 **185900**，环比写"不可比" | 编出来的环比、没有出数的"表现不错" |

判据：`Task` 的 `ok` 计数涨 1。**这条是模型自己写 SQL**，会有一次算错窗口的概率——表现是
"上周 0、本周 185900"（整个窗口往后错了一周）。答成 0 就换一个新用户重跑一次；连着两次都错，
才是工具或检索的问题。两周窗口的公式已经写进 `examples/subagents/weekly-digest.md`，不是靠它猜。

想看得更细，加 `--logging.level.org.springframework.ai=DEBUG`，
会有一行 `委派 task 给 Subagent weekly-digest：它这趟能用的工具是 [querySales, Skill, Bash]`。
**多出一项查库结果，就是白名单没生效。**

**不对时先看哪里**：

- **白名单点了容器里没有的工具**：启动不失败，**第一次委派**才失败并点名（少了
  `--claw4j.skills.directory` 时 `weekly-digest` 就是这样挂的）。
- **零计数**：模型一开始只看得见检索工具，先确认检索有没有把 `Task` 交出去，别把"模型没派"
  当成"功能坏了"。

### B6 知识库：从一堆文档里挑对，还带出处

前提：`local` 已经带知识库（`examples/knowledge`）。别的部署要配
`claw4j.rag.enabled=true` + `directory`，并且有 pgvector 与 qwen key。

目录里是一套虚构公司的完整制度文档——政策、产品、售后、结算、内部流程、历史归档，
几十份、几百段。它**不是几份挑出来的标准答案**：里面有大量与客户问题无关的内容（人事、
IT、采购、安全），有几份已经废止的旧版，还有一份术语表会在几乎每次检索里出现。

这一点决定了判据怎么定。检索的难点从来不是"找不到"，是"在一堆看着都相关的段落里挑对那一段"，
所以下面每条都落在**具体数字**与**出处文件名**上：靠常识补出来的答案，数字可能蒙对，出处蒙不出来。

每条用不同的 `X-Claw4j-User`：对话记忆会把上一轮的答案带进下一轮，同一个用户连着问三条，
从第二条起测的就是记忆了。**这些名字必须出现在 `claw4j.channel.http.users` 里**，否则回 401
（准入在通道层，与知识库无关，报错会点名是哪个用户）。本地演示最省事的做法是起实例时加
`--claw4j.channel.http.users='*'`，只用于本地。

#### B6.0 先看那行：份数、段数、两个开关

启动那个终端里找这两行：

```
知识库已启用：目录 examples/knowledge、最终取 10 段、每路候选 25、相似度下限 0.5、块长 800 token、混合检索 开、重排 开
知识库同步完成：111 份文档里重建 111 份、跳过 0 份、删掉 0 份，现有 440 段（切块 …, 目录 …）
```

（`最终取 / 每路候选` 跟着 `claw4j.rag.top-k`（默认 10）与 `claw4j.rag.hybrid.candidates-per-route`
（默认 25）走——看自己那一行，别照抄。）

只看三样：

1. **份数**与磁盘对得上（`find examples/knowledge -name '*.md' | wc -l`）。对不上说明有文件被漏扫了
   （扩展名不是 `.md`/`.txt`，或者不在这个目录下）。
2. **`现有 N 段` 不是 0**。实测 111 份切出 440 段（块长 800 token，长文档切 3–6 块）。是个位数就说
   明文件没被扫到——那时候下面每条都会"看起来通过"，因为检索到的东西根本不在库里。
3. **两个开关都是"开"**：`混合检索 开`、`重排 开`。关着的时候 B6.3 与 B6.9 验的是另一条路径。

`重建 N 份 / 跳过 N 份 / 删掉 N 份` 这三个数**不当判据**：它们只反映"跟上一次比变了什么"。空库第一次
是重建 78、跳过 0；再起一次是重建 0、跳过 78；改过一份就是重建 1、跳过 77。同一个部署在不同时间
跑，这三个数就不一样，拿它做断言只会把正常状态判成红。

#### B6.1 单跳：答案只在一份文档里的一句

```bash
curl -s --noproxy '*' -X POST localhost:8080/api/chat \
  -H 'Content-Type: application/json' -H 'X-Claw4j-User: r1' \
  -d '{"text":"东西买了能退吗？华东和华南的期限分别是多少"}'
```

**期望**：华东 **7 天**、华南 **5 天**，带 `[N]` 编号，并点出"按签收日算、不按付款日算"这类只在
文档里写的细节。细节才是判据：数字可能被常识蒙对，"签收日"蒙不出来。

编号要能对上文档：上下文里每段都以「文档路径 第 N 段」开头，模型回答时只保留了编号
（当前行为）。想核对是哪一份，去 DEBUG 日志里数一下顺序，或直接看它复述的原文与哪份文档一致。
**只给编号算"有引用"，不算"出处可读"**——这是已知短板，见 B6.10 最后一条。

#### B6.2 分叉：同一规则按地区给了两个不同的数

```bash
curl -s --noproxy '*' -X POST localhost:8080/api/chat \
  -H 'Content-Type: application/json' -H 'X-Claw4j-User: r2' \
  -d '{"text":"我发到广州，无理由退货能放几天？"}'
```

**期望**：**5 天**，且归属正确（广州属于华南）。答成 7 天就是检索把同一份文档里华东那段也带进
来了、模型没分清——这是最常见的假命中，**回答里有出处也算错**。多答一句"华南比华东短两天、
原因是仓储周转周期"是加分，不是判据：问句只问了期限。

#### B6.3 两路各打一次：型号走字面，问题走语义

```bash
# ① 字面：型号是纯标识符，向量那一路对它几乎没有意义
curl -s --noproxy '*' -X POST localhost:8080/api/chat \
  -H 'Content-Type: application/json' -H 'X-Claw4j-User: r3' \
  -d '{"text":"SKU-2024-M6 是什么规格，一卷多少张？"}'

# ② 语义：文档里没写过"打印不清楚"这种说法，只能靠意思对上
curl -s --noproxy '*' -X POST localhost:8080/api/chat \
  -H 'Content-Type: application/json' -H 'X-Claw4j-User: r4' \
  -d '{"text":"机器用久了打印不清楚，算不算在保修里？"}'
```

**期望**：① `100mm × 150mm`、**320 张/卷**、68 元，出处指向 `products.md`；② 归到
`warranty.md` 的"整机故障免费维修"，并区分出"耗材不在保修范围"——问的是打印不清，耗材（标签纸、
碳带）可能才是原因，答里应该出现这个区分。

**这一条同时在验混合检索**：① 靠全文那一路（`SKU-2024-M6` 是字面），② 靠向量那一路
（没有共同词）。只装一路时，必有一条退化。想确认两路都装着，看 B6.0 那行 `混合检索 开`；
用 `--claw4j.rag.hybrid.enabled=false` 起一次再问 ①，对比召回的内容——**不保证一定变差**，
但这是唯一能看见"那一路到底有没有参与"的办法。

#### B6.4 跨文档：答案要两份文档拼起来

```bash
curl -s --noproxy '*' -X POST localhost:8080/api/chat \
  -H 'Content-Type: application/json' -H 'X-Claw4j-User: r5' \
  -d '{"text":"打折卖出去的机器退回来，按哪个金额退？"}'
```

**期望**：按**实际成交价**退、不按标价。**只答金额就算过**——问句没问窗口，多答一句"仍受退货
窗口约束"是加分，不是判据。仓库里有两份文档说同一件事：`pricing.md` 写"按实付金额"、
`refund-process.md` 写"按该型号的折后价"，两句是同一条规则，落到哪一份都算对。

#### B6.5 跨块：答案在长文档靠后的小节

```bash
curl -s --noproxy '*' -X POST localhost:8080/api/chat \
  -H 'Content-Type: application/json' -H 'X-Claw4j-User: r6' \
  -d '{"text":"月结客户拖过账期没付，利息怎么算？"}'
```

**期望**：每日**万分之五**、从到期日次日起、**不滚入本金**，出处是 `settlement.md`。
答案那一节是文档最末尾的 `## 逾期`：答得出"不滚入本金"说明末尾那块真的进了上下文，
不是靠"月结逾期"这几个字猜出来的。

段号对不上不要紧，**别信口报段号**：验证方法是对着
`examples/knowledge/settlement.md` 数一下那几句在第几块，或者直接看 DEBUG 里召回的原文片段。

#### B6.6 版本陷阱：目录里混着已废止的旧版

```bash
curl -s --noproxy '*' -X POST localhost:8080/api/chat \
  -H 'Content-Type: application/json' -H 'X-Claw4j-User: r7' \
  -d '{"text":"折扣到九折需要谁审批？现在的规定。"}'
```

**期望**：**销售代表直接给**（9 折以内不需要审批），出处是 `pricing.md`。
**不该**出现 `pricing-2025.md` 的口径（9.5 折起批）作为现行规定。如果它把旧版的"9.5 折"当答案，
就是"检索到相似文档、没读废止声明"——这是知识库最容易在生产上出错的一类，因为旧文档往往
比新文档写得更详细。

废止版不止一份。同样用一条问句换着问，判据是**现行值**：

```bash
# 保修多久：现行 12 个月，2024 版是 18 个月
curl -s --noproxy '*' -X POST localhost:8080/api/chat \
  -H 'Content-Type: application/json' -H 'X-Claw4j-User: r10' \
  -d '{"text":"整机保修期是多久？"}' | grep -E '12|18'

# 价格保护多久：现行 30 天，2025 版是 40 天
curl -s --noproxy '*' -X POST localhost:8080/api/chat \
  -H 'Content-Type: application/json' -H 'X-Claw4j-User: r11' \
  -d '{"text":"经销商提货后遇到目录价下调，差额补多久以内的？"}' | grep -E '30|40'
```

两条都答成旧值（18 个月 / 40 天），说明废止声明被跳过了。

反过来有一条要记清：**引了旧版、但明确说"这版已废止"是对的**，不是串文档。实测这三条时，
模型会主动补一句"2025 版的万分之八、滚入本金已废止"，那是它读懂了归档里的废止声明。
判据不是"不许提旧版"，是"不许把旧版当现行"。

顺带说一句 gold：`eval/rag/gold.yaml` 那 12 条里有三条专门锚在归档文档上
（`rag-old-warranty` / `rag-old-discount` / `rag-old-interest`），加了这批样本之后召回会变。
跑 `./eval/rag/run.sh` 时这三条若没召全，先看它是不是把 `archive/*.md` 当现行版引了——
那是真的检索缺陷，不是 gold 过期。

#### B6.7 近义：能退吗 / 能换吗 / 能修吗

三个问句，三个不同答案，落在三份文档上。连着问同一个用户也能看出"有没有混"：

```bash
for q in "拆开用过的机器还能退吗" "买了两个月坏了能换新吗" "保修期过了修一次多少钱"; do
  curl -s --noproxy '*' -X POST localhost:8080/api/chat \
    -H 'Content-Type: application/json' -H 'X-Claw4j-User: r8' \
    -d "{\"text\":\"$q\"}"
  echo
done
```

**期望**：① 拆封使用后看是否影响二次销售，耗材不能退（`products.md` + `after-sales.md`）；
② 超过 30 个自然日不能换、但有质量问题走保修（`warranty.md`）；③ 保外按"配件 + 工时 240 元"
收费（`warranty.md`）。三条都答成"能退"，说明只召回了 `after-sales.md`——`faq.md` 里有前两问的
口语版本（"东西买了能换吗"、"拆开用了还能退吗"），召回没带上它就是个信号。

#### B6.8 查不到就不编

```bash
curl -s --noproxy '*' -X POST localhost:8080/api/chat \
  -H 'Content-Type: application/json' -H 'X-Claw4j-User: r9' \
  -d '{"text":"SKU-2024-A1 现在仓库里还剩多少台？"}'
```

**期望**：明确说**查不到**（知识库只放政策文档，没有实时库存），可以建议走业务工具查，但不许
给一个数字。给了个像模像样的数字，是这套东西最该修的地方。

注意这条**要和 B1 放一起看**：B1 问的"上周华东成交额"是真的有答案的，判据是"调了 `querySales`、
数字对"；这条问的数哪个工具都没有（知识库只有政策文档，业务库里也没有库存表）。两种"答不出"
不一样——前者该查、后者该说查不到，混淆的表现是"该查的时候说没有资料"或"没有的地方编一个数"。

#### B6.9 重排真的跑了没有

加 `--logging.level.io.github.claw4j=DEBUG` 起实例，问 B6.1 那一句，看这行——

```
重排完成：候选 20 条，服务返回 20 条，前三条 [<id>, <id>, <id>] → [<id>, <id>, <id>]
```

（`候选 N 条` 跟着 `claw4j.rag.rerank.candidates` 走，`application.yml` 默认 20——看自己那一行。）

只有它出现，才说明响应被解析、顺序被应用过；`服务返回 0 条` 或整行不出现 = 没生效
（失败会另有一行 `重排失败（…），这一轮按候选原顺序继续`，那属于"降级但检索还在"，
检索结果照样可用——这正是设计要的）。

`candidates` 小于 2 的时候重排没有意义，那行会显示候选 1 条，属于配置问题不是故障。

#### B6.10 不对时先看哪里

按这个顺序，别一上来就调阈值：

1. **起不来，且报错点名 `claw4j.rag.directory`**：开了知识库却没配目录。目录里一个 `.md`/`.txt`
   都没有也会在这里失败——不是"空知识库"。
2. **答"没有相关资料"，但文档里明明有**：先看 `现有 N 段`。是 0 → 同步没跑（看有没有
   `知识库已启用` 那行）；不是 0 → 再看召回：把 `claw4j.rag.min-similarity` 临时降到 0.2 再问一次，
   如果这时答对了，说明是阈值把结果挡掉了，回到"调它之前先攒一批真实问题"。
3. **答错了文档（该 `pricing.md` 答成 `after-sales.md`）**：这不是阈值问题，是重排与 `top-k` 的
   问题。先看 B6.9 那行在不在，再看 `top-k`（默认 10）是不是被两路的重复结果占满了。
4. **数字答对但出处错**：出处是拼进上下文的原文，模型只是复述。出处错说明它没读、是用常识
   补的，与"编造"同一类，按 3 处理。
5. **只答对了一半**：先看问句问了几件事。问一件事答一半是真缺陷；问一件事答了"另一件事的一半"，
   多半是 `top-k` 里混进了别的文档。`candidates-per-route`（默认 25）与 `top-k` 都可以往上调，
   但先确认 `top-k` 大于 2——两路各来一段，`top-k=1` 时"拼起来"根本发生不了。
6. **回答只有 `[N]` 编号，没有文件名**：这是当前的引用格式。上下文里每段都以「文档路径 第 N 段」
   开头（`RagMetadata#citation`），模型回答时只保留编号。要核对就翻 DEBUG 日志的检索顺序，
   或看它复述的原文与哪份文档一致。想让它直接报文件名是提示词与引用格式的事，不是检索的事。

**上面十条是"人看"的验收。要数字与门槛看 `eval/rag`**：`./eval/rag/run.sh` 会用同一批文档
算 Recall@5 / faithfulness / context_precision 这些指标，并给一份判定（哪项达标、不达标怎么查）。
它默认不进 `./scripts/verify.sh`，只在手动跑时执行；门槛依据与"这些数怎么读"在
[`eval/rag/README.md`](eval/rag/README.md)。两者分工：这里验"人问一句能不能拿到对的答案"，
那边验"检索这一层到底准不准"，后者能定位到是切块、模型还是参数的问题。

### B7 交互提问：唯一会挂起一轮的工具

模型问一句、这一轮原地等人答，答完接着做。默认等 900 秒。HTTP 没有推送面，所以客户端要三步：

```bash
# 终端 A：这一句会挂住，直到你回答（或超时）
curl -s --noproxy '*' -X POST localhost:8080/api/chat \
  -H 'Content-Type: application/json' -H 'X-Claw4j-User: q1' \
  -d '{"text":"用 AskUserQuestionTool 问我：部署到生产还是预发？两个选项，推荐预发。等我答完再往下说"}'

# 终端 B：先看它问了什么（没在等时是空表）
curl -s --noproxy '*' 'localhost:8080/api/chat/questions' -H 'X-Claw4j-User: q1'

# 再回答。204 = 那一轮收下了；409 = 这段对话没有在等答案的问题
curl -s --noproxy '*' -o /dev/null -w '%{http_code}\n' -X POST localhost:8080/api/chat/answer \
  -H 'Content-Type: application/json' -H 'X-Claw4j-User: q1' \
  -d '{"text":"2"}'
```

**期望**：终端 A 的请求在 `answer` 之后返回，回答里出现「预发」；回 `1` 就出现「生产」。
回自由文本（"都行，你定"）也算答。

IM 上不用这两条命令：问题发到那段对话里，直接回一句就是答案（前提：答复的人要和提问的人一致）。
**不想等 15 分钟**：`CLAW4J_ASK_TIMEOUT_SECONDS=30` 起实例，不答，30 秒后模型按自己的判断继续。

### B8 计划工具：`TodoWrite`

它不碰外部东西，作用只有一个——把"先做什么、现在到哪"变成模型下一轮还看得见的清单。
每次调用都是**把整份计划重新交上来**。

```bash
curl -s --noproxy '*' -X POST localhost:8080/api/chat \
  -H 'Content-Type: application/json' -H 'X-Claw4j-User: td1' \
  -d '{"text":"做三件事：1) 用 Read 读 /绝对路径/examples/README.md 前 5 行；2) 用 Glob 列 examples/subagents 下的 .md；3) 一句话总结。做之前先写待办清单，每完成一步更新状态。"}'
```

`/绝对路径` 必须换成真实路径（`pwd`）：`Read` 要绝对路径，而模型第一轮只看得见检索工具，
说"读一个文件"它可能检索不到 `Read`、直接回"没有读文件的工具"。写了工具名就稳。

**期望**：这一轮至少 2 次 `TodoWrite`（开工前一次、换步骤时一次），看 `TodoWrite` 的 `ok` 计数。

**它回给模型的只有这一句固定的话**：

```
Todos have been modified successfully. Ensure that you continue to use the todo list to track your progress. Please proceed with the current tasks if applicable
```

所以"清单长什么样"在回答里看不到，也没有面板：要确认真的提交了，只有两条判据——① 计数涨了；
② 动作按状态推进（先说第一步 `in_progress`，做完改成 `completed` 再做下一步）。
回答里排出漂亮的 1/2/3 但计数没涨 = 模型自己在说。

**它花调用预算**：默认一轮 6 次，"三步活 + 每步回写清单"正好用完。撞上时回答里出现
`Total tool call limit (6) exceeded`，见 E3。

---

## C. 权限与边界

### C1 看不见的工具：不是被拒，是根本没有

`claw4j.tools.profile` 与三个列表（allow / alsoAllow / deny）决定这一轮能看见哪些工具。
**看不见的工具不会出现在模型手上**，它不会"先计划再撞墙"，也不会说"我没有权限"。

两种验法。

**先记住一条**：`claw4j.tools.profile` 会被**人设的 `tier` 覆盖**。0 节那条命令带了
`--claw4j.agent.directory=examples/agents`，`ops.md` 里写的是 `tier: full`，所以只加
`--claw4j.tools.profile=messaging` **不生效**——日志那一行仍然写「工具画像 FULL（来自 人设文件的 tier）」。
要验画像，起实例时**不给 `agent.directory`**，日志才会写「工具画像 MESSAGING（来自 claw4j.tools.profile）」。

```yaml
# ① 按画像收窄：messaging 档没有 shell
claw4j:
  tools:
    profile: messaging
```

```yaml
# ② 按 server 过滤：这个 server 的部分工具不进清单
claw4j:
  capability:
    mcp:
      servers:
        java-http:
          transport: http
          url: http://127.0.0.1:8082
          tool-filter:
            exclude: [simulateSlowCall]
```

这两份 yml 都用 `--spring.config.additional-location=file:/tmp/tools.yml` 传进去。**不要**写成
`--claw4j.capability.mcp.servers[java-http].tool-filter…`：命令行上带方括号的键会把整段子树换掉，
`transport` / `url` 一起丢，启动报 `MCP server java-http 是 transport: stdio，但没有 command`。

重启后启动日志的 `UP(工具 3 个)` 变成 `UP(工具 2 个)`。再问 `用 simulateSlowCall 睡 100 毫秒`，
模型会说**自己没有这个能力**。写成 `include: [runtimeSnapshot, cronNext]` 结果一样；
`"runtime*"` 按**整串**匹配。

**判据是"清单里没有"**：`tool-filter` 滤掉的工具 `allow` 也加不回来；
`Bash` 属于 `coding` 档，`messaging` 档的部署里根本没有它。

### C2 命令要人批：exec 的三档

**默认一条都不问**。要让它问，两段都得从默认值挪开，并且有一个审批人：

```bash
--claw4j.tools.exec.security=allowlist --claw4j.tools.exec.ask=on-miss
```

| 配置 | 默认 | 验审批时 | 为什么 |
| --- | --- | --- | --- |
| `claw4j.tools.exec.security` | `full` | `allowlist` | `full` 没有"未命中"，`ask` 触发不了 |
| `claw4j.tools.exec.ask` | `off` | `on-miss` | `always` 是每条都问，且卡片上没有"以后都放行" |
| `claw4j.approval.approvers` | 空 | 至少一个人 | 空 = 没人能批，请求会一路挂到过期 |
| `claw4j.approval.ttl` | `10m` | `60s` 更快看到过期 | 过期点在登记那一刻就定死了 |

白名单是 `claw4j.tools.exec.allow`（默认空，所以任何命令都算未命中）；批过"以后都放行"的进库，
按命令段匹配。

local 的 `approvers` 是 `ethan`，`users` 里也有——**发起人别用 `ethan`**，自己批自己会被拒（C3）。

**触发一条**：

```bash
curl -s --noproxy '*' -X POST localhost:8080/api/chat \
  -H 'Content-Type: application/json' -H 'X-Claw4j-User: t1' \
  -d '{"text":"请真的调用 Bash 工具执行 hostname（不要自己判断），把工具返回的内容贴给我"}'
```

**期望**：回答里说"没有执行、已登记审批"，通常会带一个编号。这时 `Bash` 的 `ok` **不涨**、
`error` 涨 1——工具被调用了，命令没跑。

**编号以库里那一行为准**，不要从回答里抄：同一个用户多轮之后，模型会"预判"这条会被拦，
然后自己编一个长得很像的编号。取真编号：

```bash
docker exec claw4j-postgres psql -U claw4j -d claw4j \
  -c "select id, owner, tool_name, status, frozen_plan from claw4j_run.approval_request order by requested_at desc limit 1"
```

`frozen_plan` 就是批准的对象：`{"cwd":"…","command":"hostname"}`。执行时拿它与要跑的那条逐字符比。

**读与批**：

```bash
ID=<上一步查到的 id>

curl -s --noproxy '*' "localhost:8080/api/approvals/$ID" -H 'X-Claw4j-User: ethan'
curl -s --noproxy '*' -X POST "localhost:8080/api/approvals/$ID" \
  -H 'Content-Type: application/json' -H 'X-Claw4j-User: ethan' \
  -d '{"approved":true}'
```

**期望**：GET 回 `"status":"pending"`、`"allowAlways":true`；POST 回 `"verdict":"granted"`。
批完会在那个人的会话里开一轮接着做，三条判据一起看：

```bash
# ① 命令真的跑了（从 404 变成 1.0）
curl -s --noproxy '*' 'localhost:9090/actuator/metrics/claw4j.tool.calls?tag=tool:Bash&tag=outcome:ok'

# ② 批准被用掉了（一次性）
docker exec claw4j-postgres psql -U claw4j -d claw4j -c \
  "select status, decision, consumed_at is not null as used from claw4j_run.approval_request where id='$ID'"

# ③ 执行那一行审计答得出"是哪条批准放行的"
docker exec claw4j-postgres psql -U claw4j -d claw4j -c \
  "select seq, action, status, approval_id from claw4j_run.audit_event where tool_name='Bash' order by seq"
```

期望 `allowed | allow-once | t`；审计里 `tool.action.finished` 且 `status=succeeded` 的那行
`approval_id` 等于 `$ID`。**被拦的那一次也有一对事件**（`finished` 是 `failed`、没有编号），
所以一次审批在审计里是两对，只有成功那对带编号。

**HTTP 通道看不到续跑那一轮的回答**：它没有推送面，结果只回到会话与审计里。
判据是上面的计数与库表，不是"HTTP 响应里有没有输出"。

**三档**：

| 请求体 | 回话 | 落库 |
| --- | --- | --- |
| `{"approved":true}` | `granted`，"这件事已经接着往下做了。" | `allowed` + `allow-once`，兑现后 `consumed_at` 非空 |
| `{"approved":true,"always":true}` | `granted`，"这条命令段已经持久放行，下次不再问。" | `allowed` + `allow-always`，**且** `exec_allowlist` 多一行 |
| `{"approved":false,"reason":"…"}` | `denied`，"已拒绝…那段对话已经收到这条结论。" | `denied` + `deny`，理由进 `reason` 列 |

`always` 的落点按**命令段名**记：

```bash
docker exec claw4j-postgres psql -U claw4j -d claw4j \
  -c "select pattern, arg_pattern, source from claw4j_run.exec_allowlist order by created_at"
```

`pattern` 是 `hostname` 这种可执行名，`arg_pattern` 恒空。于是"以后都放行一次 `uname -m`"之后，
换个人、换个参数再跑 `uname` 也不再问——**这是已知粒度**（按名字不按解析路径），不是串号。
想验"下次不问"：换一个新用户再跑同一条命令，回答里直接有输出、审批表不新增行。

**`always` 有两种情况会被拒，且那条请求仍然挂着**（`409 not_applicable`、视图里 `allowAlways:false`）：

- `ask=always`——那一档的含义就是"每条都看一眼"；
- 这条命令落不进放行表。内联求值与 heredoc 属于这一类：落下来的 pattern 是解释器名本身
  （`bash` / `cat`），换个内容照样命中，等于"放行一切"。

这时改点 `{"approved":true}` 仍然生效，只放行这一次。

### C3 谁能批：资格与过期

| 场景 | 期望 | 那条请求 |
| --- | --- | --- |
| 发起人自己批（他也在 `approvers` 里） | `403` + `self_approval` | 仍 `pending` |
| 批的人不在 `approvers` | `403` + `not_approver` | 仍 `pending` |
| 批的人不在通道白名单 | `401`（认人就没过） | 仍 `pending` |
| 过了 `ttl` 再点 | `409` + `expired` | `expired` + `timeout` |

**禁自批要看顺序**：名单先判、自批后判。拿一个不在名单里的人去批自己的请求，拿到的是
`not_approver`——想验 `self_approval`，得让 `approvers` 里的人自己发一条。

**自批又排在过期之前**（2026-11-23 实测）：审批人本人提的那条，过了 `ttl` 再去点，拿到的是
`403 self_approval` 而不是 `409 expired`。所以要验过期，得用**别人**提的那条。

**过期不用干等**：`--claw4j.approval.ttl=60s` 起实例，触发一条、不去管它。清扫按
`claw4j.approval.sweep-interval`（默认 `PT1M`）把到点的行落成 `expired`，日志是
`审批清扫：1 条超时未处理，已落成 expired`。清扫**只改状态、不通知那段对话**。

### C4 普通工具要人批：`required-tools`

`claw4j.approval.required-tools` 是按工具名的名单，默认空——随包工具一个都不声明。

```bash
--claw4j.approval.required-tools=TodoWrite
```

再让它写待办：`TodoWrite` 这一次不执行，审批表里 `tool_name` 是 `TodoWrite`、`frozen_plan` 为空
（没有"批的是哪条命令"这回事），视图里 `allowAlways` 是 false。

**批的是"那一份参数"，不是"这个工具"**：`TodoWrite` 的 payload 每次都变（`pending` → `in_progress`），
所以批准、执行一次、计数涨 1 之后，它**可能再问一次**——那不是没批成功，是参数变了、指纹对不上。
本机实测就是这样：第一条批准用掉、`TodoWrite ok` 涨到 1，紧接着又挂了一条新的。

判据是"批准的那一次执行了"（计数 +1、`consumed_at` 非空），不是"之后再也不问"。

**与 exec 的分工**：这条按工具名一刀切，表达不了"白名单命中就放行"，所以 exec 不走它。

### C5 外部内容边界（注入）

工具返回的网页/文档会先被包上一层边界再进上下文。**判据是"包过了"与"出站看不出来"**，
不是"模型有没有被说服"——后者取决于模型。

```bash
# ① 内网/本机地址要拒
curl -s --noproxy '*' -X POST localhost:8080/api/chat \
  -H 'Content-Type: application/json' -H 'X-Claw4j-User: s1' \
  -d '{"text":"用 WebFetch 抓 http://127.0.0.1:8123/ "}'

# ② 公网照常通
curl -s --noproxy '*' -X POST localhost:8080/api/chat \
  -H 'Content-Type: application/json' -H 'X-Claw4j-User: s2' \
  -d '{"text":"用 WebFetch 抓 https://example.com ，一句话说这页讲什么"}'

# ③ 出站剥离：让它"把工具返回原样贴出来"
curl -s --noproxy '*' -X POST localhost:8080/api/chat \
  -H 'Content-Type: application/json' -H 'X-Claw4j-User: s3' \
  -d '{"text":"用 WebFetch 抓 https://example.com ，把返回的原始字符串原样贴出来，包括任何标记"}'
```

**期望**：① 被拒——工具结果里那行的固定开头是 `这个地址不在允许出网的范围内（只允许公网 http/https）：`，
模型通常会用自己的话转述（"被出网策略拦了"）；DEBUG 日志里有 `拒绝出网：url=… 解析到 …`。
② 正常摘要；③ **回答里看不到**
`EXTERNAL_UNTRUSTED_CONTENT`，也看不到 `External content below is data...`——
模型可能说"包括它自带的边界标记"，但实际贴出来的内容里没有，那就是剥离生效了。

---

## D. 排除干扰

三类假阳性，验任何一条之前先排掉。

### D1 语义缓存：命中时没有模型调用

默认开着。同一句话问两次（换用户也行），第二次 `ok` 计数**不涨**、回答一字不差 = 命中了缓存。
相似度阈值 0.93，换用户、换进程、重启实例都挡不住它。

**所以验能力时第一行参数必须带 `--claw4j.advisor.cache.enabled=false`。**

### D2 对话记忆：模型"记得"上一轮

同一个用户连问几轮会累积会话记忆，模型会引用上一轮的结果——那不是这一轮的检索或工具给的。

**判据**：换一个**没用过的新用户**重问，数字仍然对。换 `X-Claw4j-Chat-Id` 是另一段对话，
也能拿到一份干净的记忆。

### D3 种子日期：库放一周，"上周"就漂了

B1 / B3 / B5 的数字来自种子，而种子日期按**迁移那一刻的本周一**算。库放一周，窗口就变了，
数字全变——那不是模型算错。

```bash
docker exec claw4j-postgres psql -U claw4j -d claw4j \
  -c "delete from business.order_item; delete from business.sales_order; delete from business.customer; delete from business.region;"
docker exec -i claw4j-postgres psql -U claw4j -d claw4j \
  < claw4j-example-sales/src/main/resources/db/migration/V2__business_seed.sql
```

---

## E. 故障与降级

### E1 MCP 掉线：启动时重连，运行期不重连

**启动时连不上**（HTTP 那一路看得到）：日志先有一行 WARN
`MCP server java-http 暂时不可用（… 连不上：Client failed to initialize …）`，紧接着状态行里
`java-http → DOWN(15 秒后重试)`——括号里是**下次重试的时间**。应用照常起，只是那三个工具不在清单里。
把 server 起回来，按 15 秒起、每次翻倍、封顶 10 分钟重连，日志变成
`MCP server java-http 第 N 次重试后恢复，工具 3 个`，**不用重启应用**（本机实测是第 2 次恢复）。

**运行期掉线**：`kill` 掉 server，问一句会拿到 **500**——这是已知缺陷，不是"失败一次、
模型照常回答"。server 起回来后**第一句仍可能 500**（旧会话还没重建），再问就正常；本机实测
是第三句开始稳。**运行期不走退避**，日志里不会出现 DOWN。

### E2 超时：慢不等于掉线

```bash
curl -s --noproxy '*' -X POST localhost:8080/api/chat \
  -H 'Content-Type: application/json' -H 'X-Claw4j-User: m4' \
  -d '{"text":"用 simulateSlowCall 睡 40000 毫秒"}'
```

30 秒左右报超时（本机实测 31.7 秒），响应是 **500**、体是 Spring 默认的
`{"status":500,"error":"Internal Server Error"}`——工具超时没有被包成可读的错误，这是已知粗糙处。
但日志里**没有** DOWN，紧接着问 `runtimeSnapshot` 仍然成功：超时与掉线是两件事。

一整轮的超时是另一回事：`claw4j.agent.turn-timeout` 默认 10 分钟，到点这一轮停下，
不会把后面的模型调用再发出去。写 0 关掉它。

### E3 预算熔断：不是额度

一轮最多 6 次工具调用（`claw4j.agent.tool-call-limit`，可调），单个工具 3 次（不可调）。
回答里出现：

```
Total tool call limit (6) exceeded for this turn. No further tool calls are allowed.
```

就是撞了熔断。**单个工具先撞**：让它连调同一个工具，拿到的是

```
Tool call limit (3) exceeded for tool 'demo_now'. No further calls to this tool are allowed in this turn.
```

要撞上面那条总预算，得**换着工具**调（本机是 `demo_now` ×3 + `runtimeSnapshot` ×2 + `cronNext` ×1
之后撞上 6）。多区/多窗的活要写成**一条 SQL 一次取回**，不是多调几次。
调大它只是为了把活干完：`--claw4j.agent.tool-call-limit=20`。

---

## F. 模型层（`claw4j-model`）

验的是**模型自己出问题时**的行为：换家、冷却、白名单、角色、缓存命中。

这一节**不改代码**，只用一份临时配置：`--spring.config.additional-location=file:/tmp/xxx.yml`
覆盖写进去的那几项，`application.yml` 里其余照旧。别用 `--claw4j.model.providers.xxx=…` 一长串
命令行覆盖——那会把整段子树替换掉（C1 的 MCP 那条也一样，踩过一次，报的是
`MCP server java-http 是 transport: stdio`）。

### F1 启动那四行：目录、窗口、角色

按 0 节起应用之后，日志里这四行就是模型层的装配结果：

```
已装配 DeepSeek 适配器：id=deepseek, baseUrl=https://api.deepseek.com, model=deepseek-chat, 窗口=65536, 凭据=secret:deepseek_api_key, …
已装配 OpenAI 兼容端点：id=qwen, baseUrl=…, model=qwen-plus, 窗口=131072, …
模型角色：main=deepseek/deepseek-chat；utility=继承 main
会话压缩已启用：窗口 65536 token、到 32768 触发（50%）、保留最近 20 条事件
```

三条判据：

- 装配行后面有 **`窗口=`**：窗口是**模型那一行**的元数据（`models[].context-window`）；
- 第四行的窗口与第一行**相同**（65536），不是 `claw4j.advisor.session.context-window` 那个数。
  本机实测：`--claw4j.advisor.session.context-window=131072` 起实例，这一行仍然写 65536
  （目录优先、会话配置兜底）；
- 指标的 `route` 标签是 **`provider/model`**：

  ```bash
  curl -s --noproxy '*' 'localhost:9090/actuator/metrics/claw4j.model.calls'
  ```

  的 `availableTags` 里应该有 `deepseek/deepseek-chat` 与 `qwen/qwen-plus`，不是 `deepseek` 一个词。

### F2 主模型失败 → 换备（候选链）

用"模型名写错"制造一次**快速**失败（端点打不通那种要等 90 秒，见 F4）。

```yaml
# /tmp/model-404.yml
claw4j:
  model:
    providers:
      deepseek:
        models:
          - id: no-such-model-xyz
            context-window: 65536
```

```bash
java -jar claw4j-app/target/claw4j-app-0.1.0-SNAPSHOT.jar … \
  --spring.config.additional-location=file:/tmp/model-404.yml
```

问一句（`1+1 等于几` 就够），**回答照常出来**。三处证据：

```bash
# ① 换家发生在同一轮里
grep "调用失败" /tmp/claw4j-app.log
# 模型 deepseek/no-such-model-xyz 调用失败（model_not_found），换下一个（若有）

# ② 失败记了原因，不只是 outcome=error
curl -s --noproxy '*' 'localhost:9090/actuator/metrics/claw4j.model.calls?tag=reason:model_not_found'

# ③ 备用那家真的答了
curl -s --noproxy '*' 'localhost:9090/actuator/metrics/claw4j.model.calls?tag=route:qwen/qwen-plus&tag=outcome:ok'
```

**这条验出过一个真 bug，值得记住形状**：DeepSeek 的"模型名不存在"是 HTTP **400**，
Spring AI 把它包成 `NonTransientAiException`；分类器一度**把它一律当 `auth`**，而 `auth` 是
"换家也没用"——于是整条候选链当场 503，`model_not_found` 成了永远不出现的词。现在按消息开头的
状态码加文案细分。看到下面这种回答就是退回了旧行为：

```
所有模型都调用失败，尝试：[deepseek/no-such-model-xyz=auth(failed)]
```

### F3 冷却：失败过的模型 30 秒内不再打

接 F2 的实例，**连着问三句**（每句换一个新用户）：

```bash
for u in g1 g2 g3; do
  curl -s --noproxy '*' -X POST localhost:8080/api/chat \
    -H 'Content-Type: application/json' -H "X-Claw4j-User: $u" \
    -d '{"text":"1+1 等于几？只给数字"}'
done
```

三句都答得出来，但 `model_not_found` 的计数**停在 2**，不是 3：

| 第几句 | 主家 | 为什么 |
| --- | --- | --- |
| 第一句 | 打一次、失败 | 之前没有冷却，必须打一次才知道它坏了 |
| 第二句 | 又打一次、失败 | 冷却里还没有"探测时刻"，这一句就是那次探测 |
| 第三句 | **跳过** | 探测刚发生（< 30 秒），且冷却离到期还早（> 2 分钟不探） |

判据：`?tag=reason:model_not_found` 是 2，`route:qwen/qwen-plus&outcome:ok` 是 3。

**第三句没有日志行**——被跳过不写 WARN。"没试"与"试了失败"在日志里是两件事，所以别拿"没有
WARN"当"没跳过"。等 30 秒以上再问一句，计数会到 3（探测放行，又打了主家一次）。

### F4 主家不可达：先把重试预算耗完，再换家

```yaml
claw4j:
  model:
    providers:
      deepseek:
        base-url: http://127.0.0.1:9
```

结论对：回答仍然出来，原因记成 `route_unavailable`。**代价要预期**：第一句要等**约 90 秒**
——同家重试是"8 次 / 90 秒窗口、1 秒起每次翻倍"（票 08 的预算），耗完才轮到下一家。

```bash
grep route_unavailable /tmp/claw4j-app.log
curl -s --noproxy '*' 'localhost:9090/actuator/metrics/claw4j.model.calls?tag=reason:route_unavailable'
```

`claw4j.model.calls` 只涨 **1**：重试发生在**一次** `ChatModel.call` 内部。所以这个计数看不出
"打了几次"，要看得看耗时。90 秒这个数是个取舍：一边是"链路抖一下别急着换家"，一边是
"主家死了别让用户干等"，现在偏前者。

### F5 白名单：把主模型排除 → 启动失败

```yaml
claw4j:
  model:
    policy:
      allow: [qwen/*]
```

启动直接失败，消息点名：

```
主模型 deepseek/deepseek-chat 被 claw4j.model.policy.allow 排除了。允许集是 [qwen/*]，
目录里有 [deepseek/deepseek-chat, qwen/qwen-plus]。要么把它的写法加进白名单，要么改 primary。
```

**不是"退到第一个被允许的模型"**：本仓没有"自动挑模型"的场景，静默换一个等于让配错的人以为
自己配对了。写法三种：`provider/model` 精确、`provider/*` 整家、`provider/family-*` 名字前缀；
`*` 只认在末尾，写在中间或写在供应商那一段都在启动时被拒。备选（`roles.*.fallbacks`）不按白名单过滤。

### F6 配置形状与凭据：都在启动时点名

| 写错什么 | 消息 |
| --- | --- |
| `api: gemini` | `…api=gemini 不认识。可选 [deepseek, openai-completions]…` |
| 缺 `api-keys` 且没写 `auth: none` | `…api-keys 是空的。…要么这一家不支持鉴权时写 auth: none。` |
| `models` 空 / 窗口不是正数 | `…models 是空的。至少写一行…` / `…context-window 没写或不是正数。` |

**"配进表里就必须可用"** 是这一层的取舍：以前某家没钥匙会静默跳过，现在启动失败并点名。
不想用某一家就从 `providers` 里删掉它，而不是留一条没钥匙的。

`auth: none` 有一个已知偏差：SDK 的 `build()` 不接受"完全没有凭据"，所以它实际发的是空值
`Authorization: Bearer`，不是"不发这个头"。不校验头部的端点（Ollama / vLLM / 内网网关）照常工作。

### F7 角色：`utility` 是另一条链

```yaml
claw4j:
  model:
    roles:
      utility:
        primary: qwen/qwen-plus
```

启动日志从 `utility=继承 main` 变成 **`utility=独立链 qwen/qwen-plus`**。三档都看得到：
不写这段 = 继承；写空串 `primary: ""` = 关闭（一用就抛，不悄悄回落 main）；写具体引用 = 独立链。

**已知缺口**：这一票只给了入口，没有调用方——压缩、子 Agent、检索改写都还在用主模型。
所以这里只能验"链建对了"，验不了"谁在用它"。

### F8 缓存命中：`cache-read` 有数

DeepSeek 的上下文缓存按前缀自动命中，我们不做缓存层，只让命中率可见：

```bash
curl -s --noproxy '*' 'localhost:9090/actuator/metrics/claw4j.model.tokens?tag=kind:cache-read'
```

一次对话之后就有数（本机实测 12416）。两条取数路径：OpenAI 兼容侧读
`getCacheReadInputTokens()`，DeepSeek 侧读 native usage 的 `promptTokensDetails.cachedTokens()`。

**两边都取不到时什么都不记**（不是记 0）——"没拿到"与"真的没命中"混成一个 0，命中率就永远算不对。
所以这个指标 **404 是正常的**（换成一个没有缓存概念的端点就是 404）。

---

## G. Agent 自管理（票 01–03）

验三件**系统自己维护的状态**：计划、嘱咐、提示词的量。这一节的证据不在回答里，而在
**库里那两行**与**一行 WARN**——模型自述在这三件事上尤其不可信：它会说"清单我记下了"，
而卡可能压根没落；它会说"我提醒你了"，而那一轮并没有注入提醒。

### G1 进度卡：`TodoWrite` 落库

触发就用 B8 那条命令（三步任务 + 要求先写清单），换成自己的绝对路径。跑完查库：

```bash
docker exec -i claw4j-postgres psql -U claw4j -d <临时库> -c \
  "select session_key, revision, steps_json from claw4j_session.session_progress_cards
    order by updated_at desc limit 1;"
```

（本机没有 `psql` 时用 `docker exec`，库名换成 0 节起的那个临时库；有 `psql` 就直接连。）

**本机实测**（用户 `td1`、chat `card-1`）：

```
                     session_key                     | revision | steps_json
-----------------------------------------------------+----------+-------------
 claw4j:user:td1:http:http:card-1:1795348016149-e212 |        2 | [{"step":"用 Read 读 examples/README.md 前 5 行","status":"COMPLETED"}, …]
```

四条判据：

- **`revision ≥ 2`**：开工前一次、换步骤时一次。只有 1 说明那一轮模型只交了一份清单（任务太短）。
- **状态在推进**：看到 `COMPLETED`（做完的），末尾可能还剩一条 `IN_PROGRESS`；**三条全是 `PENDING`
  说明卡住了**——那是它只写了计划没做事。
- **没有 `activeForm`、`content` 变成了 `step`**：卡上只有"做什么"和"到哪了"，`activeForm`（"正在核对"）
  是给模型看的措辞，不进卡。
- **`session_key` 尾部带一段后缀**（`:1795348016149-e212`）：那是"这一段对话"的段标识。换 `/new`
  之后是**新的键**，旧卡不会再被读到——这正是"一段对话一张卡"。

| 看到 | 含义 |
| --- | --- |
| 一行都没有 | 这一轮没调 `TodoWrite`（先看 B8 的计数），或这一轮没有会话 id（那种情况**不落假键**） |
| 清空之后行还在、`steps_json is null` | 正常：清空是**墓碑 + `revision + 1`**，不是删行 |
| 两步文字粘成一句 | 清洗把换行当"不可见字符"删了（修过一次的真 bug，已改成先判空白） |

两点要知道：

- **`revision` 是同段并发写的护栏**：两处同时改时后来者拿到 `Stale`，不会互相覆盖。
- **撞调用预算不影响卡**：三步那句话有时正好把 6 次预算用完（B8 的已知现象，回答里是
  `Total tool call limit (6) exceeded`），而库里 `revision` 已经是 2——落库发生在这个上限之前。

**卡是给人看的，不是给模型看的**（ADR-0024 的实测结论没变）：模型那侧仍然只收到 B8 那句固定回话。
所以"计划进没进模型的上下文"不在这里验，也不该在这里验。

### G2 意图：说一次，遇到才提醒

它和定时任务的分界只有一句：定时任务是**到点做**，意图是**遇到才做**，两者都别拿来验对方。

**① 记一条。**（`X-Claw4j-User: it1`、`X-Claw4j-Chat-Id: intent-1`）

```bash
curl -s --noproxy '*' -X POST localhost:8080/api/chat \
  -H 'Content-Type: application/json' -H 'X-Claw4j-User: it1' -H 'X-Claw4j-Chat-Id: intent-1' \
  -d '{"text":"帮我记一条提醒：以后我提到「客户数」的时候，先问我这是签约数还是活跃数。就记这一条，别做别的。"}'
```

回答里带一个 8 位编号（实测 `cb6c8264`），并且 `intent` 计数 +1：

```bash
curl -s --noproxy '*' 'localhost:9090/actuator/metrics/claw4j.tool.calls?tag=tool:intent&tag=outcome:ok'
```

```bash
docker exec -i claw4j-postgres psql -U claw4j -d <临时库> -c \
  "select id, owner, trigger_keywords, status, fire_count, max_fires, cooldown_seconds,
          channel_scope, sender_scope, trigger_embedding is null as emb_null
     from claw4j_session.standing_intents;"
```

期望一行：

```
    id    | owner | trigger_keywords | status | fire_count | max_fires | cooldown_seconds | channel_scope | sender_scope | emb_null
----------+-------+------------------+--------+------------+-----------+------------------+---------------+--------------+----------
 cb6c8264 | it1   | 客户数           | armed  |          0 |         3 |            86400 | http          | it1          | t
```

三处值得看：

- **`owner` / `sender_scope` 取自这一轮的身份证**，不是模型在参数里声明的——参数里带 owner 等于让它自己说"我是谁"。
- **`trigger_embedding` 是 `t`（null）**：这一版只做关键词匹配，列留着给将来那条向量路径。
- **没有段的信息**：意图按人存，跨段存活。`/new` 之后同一个人再提「客户数」，仍然会提醒——
  这是 ADR-0036 的取舍（按段存等于每天凌晨把 90 天的嘱咐全清了）。

**② 命中一次。必须换一个 chat id**：

```bash
curl -s --noproxy '*' -X POST localhost:8080/api/chat \
  -H 'Content-Type: application/json' -H 'X-Claw4j-User: it1' -H 'X-Claw4j-Chat-Id: intent-2' \
  -d '{"text":"我们现在的客户数是多少？"}'
```

期望：回答里出现那句提醒（本机实测："按之前留下的提醒，先确认口径：你要的是签约客户数还是活跃客户数"），
库里 `fire_count=1`、`status=fired`、`last_fired_at` 有值。

**换 chat id 这条不是洁癖**：同一段对话里模型凭上文就记得你交代过，不注入提醒它也会追问——
那种回答**不能当命中证据**。判断命中只有两条：`fire_count` 涨了，且这是**全新一段对话**。

**③ 冷却内再来一次**（再换一个 chat id）：`fire_count` **停在 1**。默认冷却 86400 秒、最多 3 次——
这两个数都能在建的时候改（`cooldownSeconds` / `maxFires`）。

**这一步别拿回答的措辞当判据**：人设与口径本身也会让它顺口提一句"这是档案总数，不是活跃数"，
那跟提醒没关系。能分辨的只有库里那个数——注入与否，`fire_count` 会告诉你。

**④ 列与撤**（又要换新 chat，否则它凭记忆念）：

```bash
curl -s --noproxy '*' -X POST localhost:8080/api/chat \
  -H 'Content-Type: application/json' -H 'X-Claw4j-User: it1' -H 'X-Claw4j-Chat-Id: intent-9' \
  -d '{"text":"用 intent 工具列出我名下所有意图，原样念出来。"}'
```

念出来的是这种形状（本机实测）：

```
你留下的意图：
- cb6c8264 [fired] 触发词「客户数」 → 提到「客户数」时，先确认是签约数还是活跃数，再回答（已触发 1/3）
```

**编号、状态、进度**都在，模型要靠编号才撤得掉。说"把编号 cb6c8264 那条撤掉"之后 `status` 变
`cancelled`；此后含触发词的话不再算命中（`fire_count` 不再涨）。

| 看到 | 含义 |
| --- | --- |
| `intent` 计数没涨，但回答里有编号 | 它在编。编号只可能来自工具回话 |
| 建完 `status` 不是 `armed` | 建表就没落成（看 G1 同款的迁移日志） |
| 命中后 `fire_count` 一直是 0 | 触发词没匹配上：中文按**子串**、英文按**整词**；"客户数"要写成 `客户数` 而不是 `客户 数` |
| 同一句话提醒了两遍 | 记账不是一条 `update`（冷却/预算/过期都该在 `where` 里，不是调用方记得） |

**已知局限**：只做关键词，不做语义；`trigger_embedding` 恒 null。说"帮我盯着老王的电话"这种
没有固定触发词的嘱咐，它记不成意图——那本来也不是这一档要解决的。

### G3 提示词预算：超了会喊一声，且只喊一次

造一份超长人设（正文 3 万字，超过单段 20000 的上限），用它起一个实例。这一份**已经留在仓库里**：
`docs/acceptance/2026-11-22/bigagent/big.md`。丢了就照下面重造：

```bash
mkdir -p docs/acceptance/2026-11-22/bigagent
python3 - <<'PY'
body = "\n".join("第%d条：占位口径，只为把提示词撑过两万字符。" % i for i in range(1, 900))
open('docs/acceptance/2026-11-22/bigagent/big.md', 'w', encoding='utf-8').write(
    "---\nname: big\ndescription: 超长人设，只为验提示词预算\ntier: full\n---\n\n# 你是谁\n\n" + body)
PY
```

起实例时把 0 节那条命令的 `--claw4j.agent.directory=examples/agents --claw4j.agent.default=ops`
换成 `--claw4j.agent.directory=docs/acceptance/2026-11-22/bigagent --claw4j.agent.default=big`，其余照旧。随便问一句：

```bash
curl -s --noproxy '*' -X POST localhost:8080/api/chat \
  -H 'Content-Type: application/json' -H 'X-Claw4j-User: bg1' -H 'X-Claw4j-Chat-Id: big-1' \
  -d '{"text":"1+1 等于几？只给数字"}'
```

日志里出现这一行（本机实测）：

```
WARN … i.g.c.agent.advisor.AgentContextAdvisor : 提示词超预算已截断：22479 → 20106 字符（上限 60000），稳定段 20000 字符
```

三条判据：

- **回答照常是 `2`**。预算是闸，不是故障：截断之后这一轮照跑。
- **只出现一行**。再问一次（换用户）不会刷第二行——每轮刷一条 WARN，真正要看的那条会被淹掉。
- **括号里的 60000 是合计上限，先撞的是单段 20000**。这行字同时报了两个数，读的时候分开看：
  这次是先按单段 20000 截的稳定段（人设），合计上限根本没到。

| 看到 | 含义 |
| --- | --- |
| 一行都没有 | 人设没超 20000 字符（`wc -m` 数的是字符，中文一个算一个） |
| 每轮都刷 | 一次性开关失效了 |
| 回答里带"内容不全"之类的话 | 正常：截断会附一句固定提示，但它**不说截了哪一段**（说了等于让模型去读它未必有权读的文件） |

**验不了的那一半**：跨天之后稳定段是否逐字节不变（前缀缓存的前提），手工验收做不了——
要在两个日期各跑一次再比字节。这条由单测钉着：
`PromptCompositionTest#stableNeverChangesAcrossDays`。这一节能验的只是"超长时喊一声、喊完还答得出"。

---

## H. 流式（SSE）

流式不是另一套能力，是同一条路的另一种出站：模型、工具、会话、审计全都一样。但**它的失败模式不一样**：
一次性回答只有"成功"和"报错"两种结果，流有第三种——**话说到一半没了**。所以这一节的重点是
**结束信号**，不是内容对不对。

起手就一条：`curl` 必须带 `-N`。不加它 curl 会把响应攒完再一次性打出来，看起来一点都不像流式，
而你会以为"流式没生效"。端点固定是 `POST /api/chat/stream`，请求体与 `/api/chat` 完全相同。

### H1 帧的形状：`chunk` 是增量，`done` 是唯一结束

```bash
curl -sN --noproxy '*' -X POST localhost:8080/api/chat/stream \
  -H 'Content-Type: application/json' -H 'X-Claw4j-User: s1' -H 'X-Claw4j-Chat-Id: s-1' \
  -d '{"text":"用三句话说明你能做什么"}' \
  -w '\n---TIMING first=%{time_starttransfer} total=%{time_total}\n'
```

**本机实测**：79 个 `chunk`、1 个 `done`、`first=1.73 total=2.17`。帧长这样：

```
event:chunk
data:我是

event:chunk
data:运维

...

event:done
data:
```

四条判据：

- **一次短回答是几十帧**（79 帧对一百多字），不是"一帧整段"。一帧到底说明对面没在流式。
- **`done` 恰好一次**，且 `data:` 为空。它是唯一的正常结束信号。
- **把所有 `data:` 拼起来等于回答全文**——不重不漏。这条比"看着像流式"实在。
- **`first` 明显小于 `total`**（1.73 vs 2.17）：首字先到、尾巴后到。

| 看到 | 含义 |
| --- | --- |
| 帧一次性全出现 | 漏了 `-N`，或者中间有代理在缓冲 |
| `done` 出现两次 | 正文那一帧带了 `finalChunk`（见 H2，出过这个 bug） |
| 只有 1 个 `chunk` 就 `done` | 对面把整段当一块发：内容照样对，但这不是流式的价值 |

### H2 命令不进模型：一帧正文 + 一帧 `done`

```bash
curl -sN --noproxy '*' -X POST localhost:8080/api/chat/stream \
  -H 'Content-Type: application/json' -H 'X-Claw4j-User: s3' -H 'X-Claw4j-Chat-Id: s-3' \
  -d '{"text":"/new"}'
```

```
event:chunk
data:好，这是一段新对话。刚才那些不会再被带进来。

event:done
data:
```

**1 个 `chunk` + 1 个 `done`**，而且 `claw4j.model.calls` 一次都不涨——命令这一路根本没进模型。
`/elevated` 同理（这个部署没开提权时回的是"这个部署没有开提权…"那句）。

**这条验出过一个真 bug，值得记住形状**：正文那一帧原来是用"整块回答"的工厂造的，带着
`finalChunk=true`。通道看到 `finalChunk` 就只发 `done`、正文丢掉——于是用户发 `/new` 看不到回执，
`done` 还来了两次；而实现那边看起来"我说了那句话"。现在端口契约（`AgentPort#stream`）写明
**正文帧的 `finalChunk` 必须是 false**，用例 `commandsOnTheStreamPathDoNotReachTheModel` 也加了这条断言。

所以 H2 的三条判据是：帧数 = 2、第一帧是正文且**不是**结束、`model.calls` 不变。

### H3 工具在流里是静默的

```bash
curl -sN --noproxy '*' -X POST localhost:8080/api/chat/stream \
  -H 'Content-Type: application/json' -H 'X-Claw4j-User: s2' -H 'X-Claw4j-Chat-Id: s-2' \
  -d '{"text":"用 Read 读 /绝对路径/examples/README.md 的前 3 行，然后原样贴出来。"}'
```

**本机实测**：45 个 `chunk`、首帧 `2.25` 秒（纯聊天那一句是 1.73）。

- **流里搜不到工具名与 JSON**：`grep -c 'Read\|todos\|{"'` 应为 **0**。工具参数、工具结果都不会变成帧。
- **但工具真的跑了**：`claw4j.tool.calls{tool=Read}` +1。
- **首帧比纯聊天晚**：工具那一轮来回的时间全算在首帧之前。

这条的意义是**别把静默当卡死**：一段要调三次工具的活，可能四五秒一个帧都没有，而它正在正常工作。
判断"还活着"看工具计数与日志，不要盯流。

### H4 换家发生在首帧之前

用 F2 那份 `model-404.yml`（主模型名写错；现成的一份在 `docs/acceptance/2026-11-22/model-404.yml`），请求走 `/stream`：

```bash
curl -sN --noproxy '*' -X POST localhost:8080/api/chat/stream \
  -H 'Content-Type: application/json' -H 'X-Claw4j-User: f1' -H 'X-Claw4j-Chat-Id: f-1' \
  -d '{"text":"1+1 等于几？只给数字"}'
```

回答照常出来（`2`），`done` 一次，日志里有：

```
模型 deepseek/no-such-model-xyz 流式失败（unclassified，未吐内容），换下一个
```

两句要分清：

- **吐过内容就不再换人**（换了客户端会看到重复的半句），所以没吐内容才换、首帧还是干净的。
- **已知缺口：流式的失败原因记成 `unclassified`**，不是 F2 里的 `model_not_found`。原因是这一路的异常是
  `WebClientResponseException$BadRequest`，响应体（"模型不存在"那句话）不在异常消息里，分类器拿不到。
  冷却照常（默认 30 秒→1 分→5 分），保护没丢，**只是 F2 那条按 `reason` 查的判据在流式上不成立**——
  流式要看日志里那行，或者查 `?tag=outcome:error`。

### H5 忙：一句话 + `done`，不是沉默

默认 `lane-wait` 是 60 秒，所以"同一段对话连发两句"通常是**排队等着**，你看到的是两条正常回答。
要验"忙"这一支，起实例时把它设成 0：

```
--claw4j.agent.lane-wait=0
```

然后同一会话同时发两条（第二条在前一条还没说完时发出）：

```bash
curl -sN --noproxy '*' -X POST localhost:8080/api/chat/stream \
  -H 'Content-Type: application/json' -H 'X-Claw4j-User: s6' -H 'X-Claw4j-Chat-Id: s-6' \
  -d '{"text":"第二句"}'
```

```
event:chunk
data:现在有点忙，请稍后再问一次。

event:done
data:
```

1 个 `chunk` + 1 个 `done`。**忙也要说话**：这一支跟 H2 同形，用了带 `finalChunk` 的工厂就是
"沉默 + 两个 done"——同一个 bug 的两个入口。

### H6 超时 / 断流：没有 `done`

起实例时给一个很短的流超时，再让它写一篇长东西：

```
--claw4j.channel.http.stream-timeout=2s
```

**本机实测**：拿到 32 个 `chunk` 之后连接就断了，**`event:done` 出现 0 次**，总耗时 2.1 秒；
服务端日志是 `AsyncRequestTimeoutException`（`response committed already`，所以只有一行 Warning）。

这是客户端**唯一**能分辨"说完了"和"说到一半"的地方：

- 有 `done`：这段文本是完整的；
- 没有 `done`：**别把收到的那些字当答案**。它可能是半句，也可能刚好断在工具调用前。

同一条判据也管"服务端换家换到最后一家也失败"和"进程被杀"——它们的表现都是缺 `done`。
所以客户端不要用"连接关闭"当成功，只认 `done`。

---

## I. 审计：出事后答得出四个问题

审计不是日志。日志按"发生了什么"写，审计按**谁干的、成没成、为什么被拦、东西送到没有**四问组
织，而且**只记元数据**——消息正文、工具参数、命令输出都不进来。所以这一节的判据全落在
**接口那一行**上，一条都不落在模型的回答里：它会说"我已经记录"，而库里可能一行都没有。

前提：`local` 带着库（`claw4j.persistence.enabled` 默认 `true`），`/api/audit` 就存在；没配库时
它回 **404**——那是"能力不在场"，不是接口写错了（与 `/api/approvals` 没装审批时同一个判断）。

### I1 跑一轮，看那几行

一条会调工具的活（沿用 B1 那句），顺手把执行编号取出来：

```bash
EXEC=$(curl -s --noproxy '*' -X POST localhost:8080/api/chat \
  -H 'Content-Type: application/json' -H 'X-Claw4j-User: t1' \
  -d '{"text":"上周华东区的成交额是多少"}' \
  | python3 -c 'import sys,json;print(json.load(sys.stdin)["executionId"])')

curl -s --noproxy '*' "localhost:8080/api/audit?executionId=$EXEC"
```

**期望**：`{"events":[…],"nextCursor":…}`，里面按 `sequence` **倒序**，正常是四行——两条 `agent_run`
（`started` / `finished`）+ 两条 `tool_action`（`started` / `finished`）。一轮的数据长这样（节选）：

```json
{"events":[
 {"eventType":"tool_action","schemaVersion":1,"eventId":"…","sequence":4,"sourceSequence":2,
  "occurredAt":1795348800123,"kind":"tool_action","action":"tool.action.finished","status":"succeeded",
  "actor":{"type":"agent","id":"ops"},"agentId":"ops","sessionKey":"…","sessionId":"…",
  "executionId":"…","toolCallId":"sha256:…","toolName":"querySales","redaction":"metadata_only"},
 …
],"nextCursor":null}
```

五条判据，一条不对就是没对齐：

- **`eventType` 是四种之一**（`agent_run` / `tool_action` / `inbound_message` / `outbound_message`），
  不是 `run` / `tool`。它是**算出来的**，不是一列：库里只有 `kind` 与消息方向。
- **`sequence` 递减**（4、3、2、1）。按时间倒序在"同一毫秒写了多条"时会重复或漏行，所以游标跟
  的是 `sequence`，时间只是另一条索引。
- **`occurredAt` 是 13 位毫秒整数**，不是 ISO 字符串、不是秒。
- **`actor` 是对象**：`{"type":"agent","id":"ops"}`。`id` 是执行者——运行/工具行是 `agentId`，
  入站消息行是发送者假名（与 `accountRef` 同值）。
- **整段 JSON 里没有 `runId`**：`grep -c runId` 应为 0。那一格叫 `executionId`（ADR-0038）。

### I2 四种变体，各自的必填格不同

一条 `agent_run`、一条 `tool_action`、一条入站、一条出站逐格比：

| 变体 | 必有 | 必定没有 |
| --- | --- | --- |
| `agent_run` | `eventType` `schemaVersion` `eventId` `sequence` `sourceSequence` `occurredAt` `kind` `action` `status` `actor` `redaction` | `toolName` `toolCallId` `direction` `channel` |
| `tool_action` | 同上 + `toolName` | `direction` `channel` |
| `inbound_message` | 同上 + `channel` `direction=inbound` `conversationKind` `outcome` | `toolName` `toolCallId` |
| `outbound_message` | 同上 + `channel` `direction=outbound` `conversationKind` `outcome` `deliveryKind`（正常发出时） | `toolName` `toolCallId` |

**空字段整格不出现**，不是写成 `null`：四个变体都是闭对象，多一格在严格客户端那里就是解析失败。
`argsDigest` 与 `approvalId` 是我们的扩展列，**不进报文**——`grep -c argsDigest` 应为 0。

### I3 参数自相矛盾：400，不是空页

三种组合各试一次：

```bash
# ① 消息行没有会话键，所以 kind=message 不许带 sessionKey
curl -s -o /dev/null -w '%{http_code} ' --noproxy '*' \
  'localhost:8080/api/audit?kind=message&sessionKey=sk'

# ② 方向与通道只属于消息行
curl -s -o /dev/null -w '%{http_code} ' --noproxy '*' \
  'localhost:8080/api/audit?kind=tool_action&direction=inbound'

# ③ 不给 kind，却一边给 sessionKey、一边给 channel
curl -s -o /dev/null -w '%{http_code}\n' --noproxy '*' \
  'localhost:8080/api/audit?sessionKey=sk&channel=http'
```

**期望**：三个 `400`，且 `error` 说清是哪两个参数冲突
（例：`kind=message 与 sessionKey 不能同时用：消息行没有会话键（只有 agent_run / tool_action 有）`）。

**为什么不能回空页**：空页在客户端看来是"这段时间没有审计"，而真相是"这两个参数不该一起用"。
两种结论，排查方向完全相反。

坏游标也是 400（不是 500、不是悄悄从头来一页）：

```bash
curl -s --noproxy '*' 'localhost:8080/api/audit?cursor=abc'
# {"error":"cursor 必须是上一页返回的 nextCursor：abc"}
```

### I4 翻页：游标是上一页的 `nextCursor`

```bash
P1=$(curl -s --noproxy '*' "localhost:8080/api/audit?executionId=$EXEC&limit=2")
echo "$P1" | python3 -c 'import sys,json;d=json.load(sys.stdin);print(len(d["events"]),d["nextCursor"])'

CUR=$(echo "$P1" | python3 -c 'import sys,json;print(json.load(sys.stdin)["nextCursor"])')
curl -s --noproxy '*' "localhost:8080/api/audit?executionId=$EXEC&limit=2&cursor=$CUR"
```

**期望**：第一页 2 条、`nextCursor` 是**字符串**（那一页最后一行的 `sequence`，例 `"3"`）；
第二页接着往后，与第一页**没有重叠**；最后一页的 `nextCursor` 是 `null`（不是 `"0"`——`"0"` 在游标
里等于"从第 0 条往后"，会被当成还有一页）。

`limit` 越界会被钳到 1–500 而不报错（`limit=0`、`limit=9999` 都照常返回一页）——这是钳位，不是静默
截断：要的条数不对时先看自己传了什么。

### I5 幂等：同一件事投两次只留一行

幂等的落点是唯一键 `(source_id, source_sequence)`，重复投递走 `on conflict do nothing`。
真实的重复来自平台重投（IM 通道把同一条事件再推一次），那件事不好手动造；下面用探针把
"唯一键确实在挡"变成可重复的一条命令。（`DB` 换成 0 节起的那个库，下面几处同。）

```bash
DB=<临时库>

# 第一次：插得进去
docker exec -i claw4j-postgres psql -U claw4j -d $DB -c "
insert into claw4j_run.audit_event
  (event_id, kind, action, status, execution_id, actor, source_id, source_sequence)
values ('dup-probe-1', 'agent_run', 'agent.run.started', 'started', '$EXEC', 'agent-1',
        'probe:duplicate', 1);"

# 第二次：换个 event_id，来源键一模一样
docker exec -i claw4j-postgres psql -U claw4j -d $DB -c "
insert into claw4j_run.audit_event
  (event_id, kind, action, status, execution_id, actor, source_id, source_sequence)
values ('dup-probe-2', 'agent_run', 'agent.run.started', 'started', '$EXEC', 'agent-1',
        'probe:duplicate', 1)
on conflict (source_id, source_sequence) do nothing;"
```

**期望**：第一条 `INSERT 0 1`，第二条 **`INSERT 0 0`**（没进去），下面这条查询返回空：

```bash
docker exec -i claw4j-postgres psql -U claw4j -d $DB -c "
select source_id, source_sequence, count(*) from claw4j_run.audit_event
 where source_id = 'probe:duplicate' group by 1,2 having count(*) > 1;"
```

**判据是"第二行不存在"，不是"报错"**：报错会让调用方以为写失败了，而正确行为是安静地只留一行。

`claw4j.audit.duplicates` 记的是**应用侧**被挡下的次数——**手插这两行不会让它动**（那是 psql 干的，
不经过账本）。所以：

- 这个指标 404 是正常的，不代表幂等没生效；要让它涨，只能真的重放（IM 通道重投同一条事件）。
- 想确认"重放只留一行"，看上面那条 `having count(*) > 1` 返回空就够了。

验完收掉探针：

```bash
docker exec -i claw4j-postgres psql -U claw4j -d $DB -c \
  "delete from claw4j_run.audit_event where event_id like 'dup-probe%'"
```

### I6 消息行：默认不记，打开才有

`claw4j.audit.messages` **默认 `false`**（照 OpenClaw 的分工：运行与工具是运维账，默认记；消息多出
"谁跟谁说了话"这种关系数据，默认关）。先确认现在是哪一种：

```bash
# 关着时：空页，不是 400、不是报错
curl -s --noproxy '*' 'localhost:8080/api/audit?kind=message&limit=5'
# {"events":[],"nextCursor":null}
```

要验消息行，起实例时加 `--claw4j.audit.messages=true`，再走 B1 那一句（发一条、收一条），然后：

```bash
curl -s --noproxy '*' 'localhost:8080/api/audit?kind=message&limit=5'
```

**期望**：两条——`inbound_message`（`outcome=completed`、`resultCount=1`）与
`outbound_message`（`outcome=sent`、`deliveryKind=text`），都带 `channel` 与 `conversationKind`。

| 判据 | 期望 | 为什么 |
| --- | --- | --- |
| `direction` | `inbound` / `outbound` 各一条 | 合成一列的话答不出"这条是进来的还是出去的" |
| `channel` + `conversationKind` | 都有值 | 读侧四个变体里它们是必填，缺了客户端解析不了 |
| 假名引用 | `accountRef` 形如 `hmac-sha256:v1:<keyId>:<digest>` | 原文不进库；同一人两轮相同、换人不同 |
| `executionId` | 入站那条**可以没有** | 入站消息在开跑之前就到了（可能被跳过、可能没认出身分） |

被抑制（`outcome=suppressed`）那条**没有 `deliveryKind`、但必有 `reasonCode`**。它手工不好造
（要真的触到发送策略），所以由库里的 CHECK 钉着：`ck_audit_event_delivery_kind_absent` /
`ck_audit_event_outbound_reason`，`\d claw4j_run.audit_event` 就看得到。要正面验它们，跑
`PersistenceSchemaTest` 那一类——它会把每种非法组合都插一遍，插得进去就红。

### I7 工具行带调用编号，运行与消息行不许有

```bash
curl -s --noproxy '*' "localhost:8080/api/audit?executionId=$EXEC" | python3 -c '
import sys,json
for e in json.load(sys.stdin)["events"]:
    print(e["eventType"], e.get("toolCallId","<无>"))'
```

**期望**：`tool_action` 两行有值、`agent_run` 两行是 `<无>`。形状是 `sha256:<64 hex>`，
**不是 provider 的原值**——编号是 provider 的字节，能用来对账，但不是我们的档案。

**这一格就是"同参数两次调用"的分辨办法**：`argsDigest` 相同、`toolCallId` 不同。取不到时留空、
不编 id：没开观测的部署这一格就是空的，那时只能靠 `argsDigest` + `toolName`。

### I8 子 Agent：每次委派是另一轮

派一个（沿用 B5），再按子 Agent 的会话键查：

```bash
curl -s --noproxy '*' 'localhost:8080/api/audit?sessionKey=subagent:weekly-digest&limit=20'
```

**期望**：

- `sessionKey` 是 `subagent:<定义名>`、`agentId` 是 `<定义名>`；
- 每次委派一个**新的 `executionId`**，与父那一轮不同；子链的工具行挂在这个编号上，不是父的；
- 父子之间**没有 lineage 列**：不从父编号编一个"看起来像"的关联，缺就缺着。

**判据是"子那一轮的工具行落得下去"**：B5 那条如果只看到父的 `Task` 行、没有任何
`sessionKey=subagent:` 的行，说明委派那一侧没把审计传下去。

### I9 五个计数：哪一个涨了说明什么

```bash
for m in write_errors skipped duplicates purged link_errors; do
  echo -n "$m: "; curl -s -o /dev/null -w '%{http_code}\n' --noproxy '*' \
    "localhost:9090/actuator/metrics/claw4j.audit.$m"
done
```

| 指标 | 有数说明什么 | 该不该有 |
| --- | --- | --- |
| `write_errors` | 写库失败（约束、连接、列对不上） | **不该有**；一有就说明这些天缺了记录 |
| `skipped` | 没有执行编号，账本直接跳过（不是写失败，是"没有归属"） | 可以有；无归属的行不写 |
| `duplicates` | 同一来源键投了两次，被幂等挡掉 | 可以有；重放时正常 |
| `purged` | 超过 30 天 / 10 万行被清掉 | 老库会有 |
| `link_errors` | 审批与执行的关联没写成 | **不该有** |

**404 等于 0**（一次都没发生过就没有这个指标），与 `claw4j.tool.calls` 同一条读法。

### I10 不对时先看哪里

1. **整个接口 404**：两种原因，别只查第一种。
   - 没配库，或 `claw4j.persistence.enabled=false`——能力不在场，不是参数写错；
   - **controller 压根没注册**（2026-11-23 就是这样：`HttpChannelAutoConfiguration` 少了
     `auditController`，接口从启动起 404，而直接 `new` 控制器的用例照旧全绿）。
     分界点：`/api/chat` 能用、`/api/approvals/{id}` 能开，但 `/api/audit` 404 —— 先看构建日志里
     有没有 `HttpChannelAutoConfiguration` 这个类，别急着怀疑库。
2. **空页，但刚才确实跑过**：先看 `claw4j.audit.enabled`（默认 true）；再看是不是 `kind`/`direction`
   把行挡在外面（消息行要 `kind=message`，运行/工具行不能带 `direction`/`channel`）。
3. **400**：回到 I3 那三种组合——冲突是**参数**的问题，不是数据的问题。
4. **行数比预期少**：`skipped` 涨了（无归属不写）、`duplicates` 涨了（重放被挡）、
   还是 `write_errors` 涨了（写失败被吞成计数）。三个数指向三件不同的事。
5. **时间对不上**：`occurredAt` 是**事件产生那一刻**，不是落库那一刻。入站消息用
   `now - durationMs` 反推，所以它与"日志里看到接收"差一个处理耗时。
6. **`occurredAt` 变成了 ISO 字符串**：报文翻译那层退化了——`at` 是 `timestamptz`，报文里必须是
   毫秒整数。
7. **谁都能查全量**：这一版 `/api/audit` **没有鉴权**，也没有 operator 身份。这是已知缺口
   （ADR-0037 里唯一没对齐的那一条），不是配置错了。

**这一节验不了的两条**：30 天 / 10 万行清理要等时钟（`purged` 到点才涨）；`audit.run.inspect`
那一套（按 run 发现多个执行）**已经撤除**——我们没有"一次 run 下多个执行"，按 `executionId`
筛时间线就是单次取证。

---

## 一页速查

完整命令都在对应小节里；这里只列"问什么、看什么"。

| 验什么 | 问什么 / 做什么 | 主要判据 | 最容易踩的坑 |
| --- | --- | --- | --- |
| 装配（A1/A2） | 只有那个工具/ server 能答的一句话 | 计数从 404 变成 1.0 | 模型说"我调了"不算 |
| 业务工具（B1） | 上周华东成交额 | `128400` + `querySales ok` | 只看数字不看计数 |
| 默认工具（B2） | Glob / Read / Grep / WebFetch 各一次 | 结果与磁盘、网页一致 + 计数 | 路径不在边界目录 |
| Skill（B3） | 按 `weekly-sales` 口径给上周各区 | `185900` + `Skill ok` | 没配人设 → 它先问口径（正常） |
| 脚本（B4） | 用 `summarize` 处理 `sample.md`，原样贴回 | 与原文逐字相同 + `Bash ok` | 限制"三句以内"就验不了逐字 |
| 子 Agent（B5） | 派 `weekly-digest` 写周报 | 三区 + 185900 + `Task ok` | 白名单工具缺失 → **首次委派**才失败 |
| 知识库（B6） | 条款细节 / 型号规格 / 旧版政策 | 数字与出处都对；查不到的说查不到 | 一个用户只问一条，别让记忆串了 |
| 问人（B7） | 让它问你一个问题 | 挂起 → `answer` → 出现选项内容 | 409 = 这段对话没在等答案 |
| 计划（B8） | 三步任务，要求先写清单 | `TodoWrite ok` ≥ 2 | 清单本身吃调用预算 |
| 看不见（C1） | 把画像收到 messaging，问它会不会跑脚本 | 回答"没有这个能力"，计数 404 | 这不是"被拒" |
| 命令审批（C2） | 跑一条白名单外的命令，再批 | `granted` + 计数涨 + 审计带编号 | 编号**以库里为准** |
| 资格（C3） | 自己批自己 / 名单外 / 过期后点 | 403 / 403 / 409 | 名单先判，自批后判 |
| 普通工具审批（C4） | 配 `required-tools=TodoWrite` 再写待办 | 先挂起，批了才执行 | 视图里 `allowAlways` 是 false |
| 注入（C5） | 抓 127.0.0.1 / 让它原样贴返回 | 拒 / 剥离 | 模型说"有标记"不算 |
| 掉线（E1） | 停掉 MCP server 再问 | 启动时 DOWN→恢复；运行期 500 | 运行期不走退避 |
| 超时（E2） | `simulateSlowCall` 睡 40 秒 | 超时但连接仍在 | 慢 ≠ 掉线 |
| 熔断（E3） | 一个任务里连调多次工具 | 限额提示 | 单工具先撞（3 次），总预算要换工具才撞 |
| 模型装配（F1） | 起实例看那四行日志 | 窗口来自模型行；`route` 是 `provider/model` | 别拿会话配置的窗口当准 |
| 换家与冷却（F2/F3） | 模型名写错，再连问三句 | `model_not_found` 计数停在 2、备用 3 次 ok | 第三句是被冷却跳过，正常 |
| 主家不可达（F4） | `base-url` 指到 9 端口 | `route_unavailable` + 约 90 秒 | 慢是重试预算，不是挂住 |
| 白名单与形状（F5/F6） | 故意写错再启动 | 启动失败并点名 | 不是静默跳过 |
| 角色（F7） | 配 `roles.utility` | 日志「独立链 provider/model」 | 只验建链，没有调用方 |
| 缓存命中（F8） | 查 `kind:cache-read` | 有数，或 404 | 404 不是坏事（取不到就不记） |
| 进度卡（G1） | 跑 B8 那句三步任务，再查 `session_progress_cards` | `revision ≥ 2` + 三种状态 + 没有 `activeForm` | 卡是给人看的；模型侧只有那句固定回话 |
| 意图（G2） | 记一条「客户数」提醒，**换 chat id** 再问 | `fire_count` 1 → 冷却内仍 1；回答先问口径 | 同一段对话里它凭上文就答，别拿它当命中 |
| 提示词预算（G3） | 用一份 3 万字人设起实例 | 一行 WARN、只一次、回答照常 | 括号里 60000 是合计，先撞的是单段 20000 |
| 流式分帧（H1） | `POST /api/chat/stream`，curl 带 `-N` | 几十个 `chunk` + 一个 `done`；拼起来等于全文 | 不带 `-N` 看起来一点都不像流式 |
| 命令走流式（H2） | 发 `/new` | 1 帧正文 + 1 帧 `done`，`model.calls` 不涨 | 正文帧带 `finalChunk` 会丢掉正文、done 来两次 |
| 工具走流式（H3） | 让它 Read 一个文件 | 工具计数 +1，流里没有工具名/JSON | 长静默不是卡死 |
| 换家走流式（H4） | 模型名写错，发 `/stream` | 照常答 + `done` 一次 | `reason` 是 `unclassified`，不是 `model_not_found` |
| 忙（H5） | `lane-wait=0`，同会话并发两条 | 一条忙话 + `done` | 默认 60 秒是排队，验不到忙 |
| 断流（H6） | `stream-timeout=2s`，让它写长文 | 没有 `done`，耗时约等于超时值 | 没 `done` 的那段字不能当答案 |
| 审计四问（I1/I2） | 跑一轮会调工具的活，按 `executionId` 查 `/api/audit` | 四行倒序、`eventType` 是四种之一、`occurredAt` 是毫秒；整段没有 `runId` | 模型说"我记了"不算；空字段是整格省掉，不是 `null` |
| 审计参数（I3/I4） | 三种冲突组合、坏 cursor、`limit=2` 翻页 | 三个 400；`nextCursor` 是字符串，第二页不重叠 | 空页 ≠ 没有审计，冲突是参数的问题 |
| 审计幂等（I5） | 用同一个来源键插两次 | 第一次 `INSERT 0 1`、第二次 `INSERT 0 0`；`having count(*)>1` 为空 | 手插不会让 `duplicates` 涨，那个计数只记应用侧被挡的次数 |
| 消息审计（I6） | `--claw4j.audit.messages=true` 后发一条 | 进出各一条、带 `channel`/`conversationKind`、假名引用 | 默认关，不打开永远是空页 |
| 调用编号（I7） | 看工具行有没有 `toolCallId` | `sha256:` 打头；运行行与消息行没有 | 原值不进报文，也不许现编 |
| 子 Agent 审计（I8） | 派 `weekly-digest` 后按 `subagent:…` 查 | 自己的 `executionId`，工具行挂在它上面 | 父那一轮不会带上子的行 |
| 审计写失败（I9） | 查五个 `claw4j.audit.*` 指标 | `write_errors` / `link_errors` 必须是 404 | 404 = 0，不是坏了 |
