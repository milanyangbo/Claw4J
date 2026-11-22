# 验收：我加的能力，到底生效了没有

[README-adding-capabilities.md](README-adding-capabilities.md) 讲**怎么加**；这一份讲**怎么验**。

验收只有两条判据，缺一条都算没跑通：

1. **回答里的内容对得上**——具体数字、口径、出处，不是"看起来像"；
2. **`claw4j.tool.calls{tool=…}` 涨了**——这个工具真的被执行过。

模型的自述**不能当证据**：它会说"我调用了"，也确实可能没调；它会把上一轮的回答记成这一轮的。
下面每一条都写清"这次该看哪个数、哪行日志"。

## 怎么用这一份

按**验什么**分六类，不按"加了什么"分。加完东西想找对应的一条，看最后一列：

| 类 | 验什么 | 加了什么之后看 |
| --- | --- | --- |
| **A 装配** | 接没接上：在不在清单、连没连上、认不认得出人 | 工具 A1、MCP A2、通道 A3、人设 A4 |
| **B 能力** | 真能用吗：回答对不对、计数涨没涨 | 工具 B1 / B2、Skill B3、脚本 B4、子 Agent B5、知识库 B6（十条）、问人 B7、计划 B8 |
| **C 权限与边界** | 看不见的、要人批的、谁能批的、外部内容 | 工具 C1、命令 C2 / C3、其它工具 C4、WebFetch C5 |
| **D 排除干扰** | 缓存 / 记忆 / 种子造成的假阳性 | 每一类都可能撞上 |
| **E 故障与降级** | 掉线、超时、熔断时长什么样 | MCP E1、工具 E2 / E3 |
| **F 模型层** | 主备换家、冷却、白名单、角色、缓存命中（`claw4j-model`） | 模型 F1–F8 |

工具观测器（一个 `ToolCallListener` bean）的验法在
[README-adding-capabilities.md](README-adding-capabilities.md) 第 9 节末尾。

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
