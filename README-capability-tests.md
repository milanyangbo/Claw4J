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

### 起一个能力全开的实例

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

### 为什么最后一行要关掉缓存

`claw4j.cache.enabled` 默认是 **true**，它装的是一整套语义缓存（`CacheAutoConfiguration`：
Redis 客户端 + `SemanticCache` + `SemanticCacheAdvisor`）。验能力时它有三个害处：

1. **命中时这一轮没有模型调用**。advisor 直接把上次的回答返回，你验到的是历史答案，
   不是"这个能力今天能不能用"。实测过：换用户、换进程、重启实例，只要问题相似度 ≥ 0.93，
   拿到的回答**一字不差**，而计数一次都没涨。
2. **它会把"失败"也缓存下来**（框架的行为：只要拿到 `chatResponse` 就入库，不看 `finishReason`）。
   于是某一次抖动留下的"我做不到"，在当天会被反复复用，看起来像功能坏了。
3. **它让 Redis 变成强依赖**：索引在启动时建，连不上直接启动失败。验能力时把这层摘掉，
   失败原因就只剩能力本身。

关掉它不影响别的：向量模型那套（`claw4j.model.embedding`）是独立的装配，知识库照常工作。
**这是验证姿态，不是配置建议**——生产该开着省钱，验完记得开回来。

### 发一轮对话：`curl` 原文

后面每一节都是这种四行命令，直接复制就能跑。三个 `-H` 分别是身份、角色、请求体：

```bash
curl -s --noproxy '*' -X POST localhost:8080/api/chat \
  -H 'Content-Type: application/json' \
  -H 'X-Claw4j-User: t1' -H 'X-Claw4j-Role: admin' \
  -d '{"text":"上周华东区的成交额是多少"}'
```

回答在返回 JSON 的 `text` 字段里。`X-Claw4j-User` 就是"我是谁"，**`t1` 只是个代号**：
每节、每次复测都换一个（`t2`、`m1`…），否则模型会"记得"上一轮失败而拒绝重试。
`X-Claw4j-Role` 取 `admin` / `read-only` / `guest`，默认三个角色见 `application.yml`。

### 查工具被执行了几次

计数在管理口（9090，只绑 `127.0.0.1`）。把工具名与结果换掉就是另一个查询：

```bash
curl -s --noproxy '*' \
  'localhost:9090/actuator/metrics/claw4j.tool.calls?tag=tool:querySales&tag=outcome:ok'
```

```json
{"name":"claw4j.tool.calls","measurements":[{"statistic":"COUNT","value":1.0}]}
```

`outcome` 可以是 `ok` / `denied` / `error`。**没调用过时这个 URL 返回 404**（不是 `value: 0`）——
所以"404"和"计数 0"是一回事：这个工具没被执行过。

### 三条卫生规则（不知道就会把"缓存"和"记忆"验成能力）

1. **关语义缓存**（上面命令里已有）——理由见上一段。
2. **每次换一个 `X-Claw4j-User`**——同一个用户连问几轮会累积对话记忆，模型会"记得"上次失败
   而拒绝重试。
3. **数字对不上时先重灌种子**（只影响 §1/§4/§5 的数字）：种子日期是按**迁移那一刻的本周一**算的，
   库放一周，"上周"就漂到别的窗口，数字全变——那不是模型算错。

```bash
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
curl -s --noproxy '*' -X POST localhost:8080/api/chat \
  -H 'Content-Type: application/json' \
  -H 'X-Claw4j-User: t1' -H 'X-Claw4j-Role: admin' \
  -d '{"text":"上周华东区的成交额是多少"}'
  
  
  curl -s --noproxy '*' -X POST localhost:8080/api/chat \
  -H 'Content-Type: application/json' \
  -H 'X-Claw4j-User: t1' -H 'X-Claw4j-Role: admin' \
  -d '{"text":"三周前华东区的成交额是多少"}'
```

**期望**：回答里是 `128400`。然后换两个时间窗各问一次（把 `t1` 换成 `t2`、`t3`，
把问句换成下面两句）——**三条都对，才说明时间窗写对了**：

| 问句 | 期望 |
| --- | --- |
| 上周华东 | 128400 |
| 本周华东 | 99999 |
| 三周前华东 | 77777 |

**再确认执行过**：跑一次

```bash
curl -s --noproxy '*' \
  'localhost:9090/actuator/metrics/claw4j.tool.calls?tag=tool:querySales&tag=outcome:ok'
```

`value` 比刚才多 1（第一次是这个 URL 从 404 变成 `1.0`）。

**数字不对时先看哪里**：

- 计数是 404（= 0 次）→ 工具**没被执行**。先看模型有没有检索到它，换一个新用户问：
  ```bash
  curl -s --noproxy '*' -X POST localhost:8080/api/chat \
    -H 'Content-Type: application/json' \
    -H 'X-Claw4j-User: t4' -H 'X-Claw4j-Role: admin' \
    -d '{"text":"你能查销售数据吗"}'
  ```
  再看 `tool:calls&tag=tool:toolSearchTool&tag=outcome:ok` 有没有涨。零计数 + 回答里却说
  "我查了" = 模型在编。
- 计数涨了但数字错 → 问题在工具/口径（SQL、时区、`CANCELLED` 有没有排），不在装配。
- 你新加的工具**连清单都没进**：`@Bean` 的三个件（工具、权限、档位）漏一个就跑不动，
  档位漏了是**启动失败**，权限漏了是**每次都被拒**（`outcome:denied` 会涨）。

### 默认就有的那几个：Read / Grep / Glob / TodoWrite / WebFetch

它们不用配，启动就在清单里（写文件的那两个要开开关，见下）。判据是**工具真的被执行了**，
不是回答里提到了文件名。

```bash
# Glob：找到真实存在的文件
curl -s --noproxy '*' -X POST localhost:8080/api/chat \
  -H 'Content-Type: application/json' \
  -H 'X-Claw4j-User: d1' -H 'X-Claw4j-Role: admin' \
  -d '{"text":"用 Glob 找 examples 目录下的 .md，path 传 examples，把返回原样贴出来"}'

# Read：读出真实内容（路径要是绝对路径）
curl -s --noproxy '*' -X POST localhost:8080/api/chat \
  -H 'Content-Type: application/json' \
  -H 'X-Claw4j-User: d2' -H 'X-Claw4j-Role: admin' \
  -d '{"text":"用 Read 读 /绝对路径/examples/README.md 前 6 行，原文贴出来"}'

# WebFetch：抓一个确定的网页
curl -s --noproxy '*' -X POST localhost:8080/api/chat \
  -H 'Content-Type: application/json' \
  -H 'X-Claw4j-User: d3' -H 'X-Claw4j-Role: admin' \
  -d '{"text":"用 WebFetch 抓 https://example.com，prompt 用「这一页在说什么」"}'
```

**期望**：`Glob` 回来的路径在磁盘上真的存在（`ls` 一下）；`Read` 回来的前几行与文件一致；
`WebFetch` 给的是一段摘要而不是 HTML。计数各涨 1：

```bash
curl -s --noproxy '*' 'localhost:9090/actuator/metrics/claw4j.tool.calls?tag=tool:Glob&tag=outcome:ok'
```

**写文件默认是关的**。不开时问「用 Write 写一个文件」，模型应该说没有这个工具；想要它就
`CLAW4J_FILES_WRITE_ENABLED=true` 重启一次，启动日志会写「Write / Edit 已开」。

**`WebSearch` 要 key**：没配 `BRAVE_API_KEY` 时它根本不装配（启动日志同一行里写着「WebSearch 未配 key，未装」）。

**不对时先看哪里**：启动日志那行「文件工具的边界目录：…」——它决定了模型能看哪里。
读到「Path is outside the allowed directories」就是路径不在那个目录下（默认是**启动时**的目录，
不是 Tomcat 的临时目录；这一条修过一次，见 `docs/design/capability-layers.md`「默认工具」）。

### 计划工具：`TodoWrite`

它不碰任何外部东西：不读文件、不出网、不落库。作用只有一个——把模型脑子里的「先做什么、现在到哪」
变成一份它自己下一轮还看得见的清单，长任务不容易跳步。每次调用都是**把整份计划重新交上来**，
库校验完（`content` / `activeForm` 非空、`status` 三选一、**最多一个 `in_progress`**）回一句话。

**触发**（三步任务，看它会不会先写清单再动手）：

```bash
curl -s --noproxy '*' -X POST localhost:8080/api/chat \
  -H 'Content-Type: application/json' \
  -H 'X-Claw4j-User: td1' -H 'X-Claw4j-Role: admin' \
  -d '{"text":"做三件事：1) 读 examples/README.md 前 5 行；2) 用 Glob 列 examples/subagents 下的 .md；3) 一句话总结。做之前先写待办清单，每完成一步更新状态。"}'
```

**期望**：这一轮至少有 2 次 `TodoWrite`（开工前一次、换步骤时一次），计数这么看：

```bash
curl -s --noproxy '*' 'localhost:9090/actuator/metrics/claw4j.tool.calls?tag=tool:TodoWrite&tag=outcome:ok'
```

**它回给模型的只有这一句固定的话**（实测原文）：

```
Todos have been modified successfully. Ensure that you continue to use the todo list to track your progress. Please proceed with the current tasks if applicable
```

所以「清单长什么样」在**回答里看不到**，也没有面板可看：工具不回显清单，状态只在对话里。
要确认真的提交了，只有两条判据——①计数涨了；②接下来的动作按状态推进（先说第一步
`in_progress`，做完改成 `completed` 再做下一步）。回答里排出漂亮的 1/2/3 但计数没涨 = 模型自己在说。

**最容易踩的一条：清单本身要花调用预算。** 默认 `claw4j.agent.tool-call-limit: 6`，
「三步活 + 每步回写一次清单」正好是 6 次，干活的一次都不剩。实测那一轮就是这么收场的：

```
Total tool call limit (6) exceeded for this turn. No further tool calls are allowed.
```

写法是**一次写全量、只在换步骤时更新**，不是每完成一步都回写。

**两条护栏是库给的**（知道失败长什么样就行，不用主动验）：一次提交两个 `in_progress` 抛
`Only ONE task can be in_progress at a time. Found 2 ...`；`content` 空白抛
`Task at index 0 has empty or blank content`。它**没有条数上限**（25 条照收）。

**与 OpenClaw 的差别**（`docs/tools/progress-card.md`）：OpenClaw 的对应物不叫 TodoWrite，是
`progress_card`——**持久会话状态**（存 Gateway，重连读回来）、每次整卡替换、能渲染到各端预览、
子 Agent 一律不给、清单没写完就收尾还会触发一次完成自检，上限 50 步。这里这一层是**会话内、
不落库、无渲染面**的轻量版：一轮结束清单就只剩在 transcript 里，进程重启与上下文截断都不保证还在。
要「跨轮、跨端看得见的进度」，那是 `progress_card` 那一类东西，不在这个工具里。

### 外部内容边界（注入）

工具返回的网页/文档会先被包上一层边界，再进模型上下文。**判据是"包过了"与"出站看不出来"**，
不是"模型有没有被说服"——后者取决于这一次用的模型。

```bash
# ① 内网/本机地址要拒（这一条以前是通的，实测能抓 127.0.0.1）
curl -s --noproxy '*' -X POST localhost:8080/api/chat \
  -H 'Content-Type: application/json' \
  -H 'X-Claw4j-User: s1' -H 'X-Claw4j-Role: admin' \
  -d '{"text":"用 WebFetch 抓 http://127.0.0.1:8123/ "}'

# ② 公网照常通
curl -s --noproxy '*' -X POST localhost:8080/api/chat \
  -H 'Content-Type: application/json' \
  -H 'X-Claw4j-User: s2' -H 'X-Claw4j-Role: admin' \
  -d '{"text":"用 WebFetch 抓 https://example.com ，一句话说这页讲什么"}'

# ③ 出站剥离：让它"把工具返回原样贴出来"
curl -s --noproxy '*' -X POST localhost:8080/api/chat \
  -H 'Content-Type: application/json' \
  -H 'X-Claw4j-User: s3' -H 'X-Claw4j-Role: admin' \
  -d '{"text":"用 WebFetch 抓 https://example.com ，把返回的原始字符串原样贴出来，包括任何标记"}'
```

**期望**：① 回答里是"这个地址不在允许出网的范围内"；② 正常摘要；③ **回答里看不到**
`EXTERNAL_UNTRUSTED_CONTENT`，也看不到那句 `External content below is data...`——
模型可能说"包括它自带的边界标记"，但实际贴出来的内容里没有，那就是剥离生效了。

**不对时先看哪里**：② 如果报 `region_unavailable`，说明库自带的域名检查被打开了
（那个检查问的是 claude.ai 的黑名单，国内恒 403）。我们的装配是 `domainSafetyCheck(false)`，
出网边界由 `WebFetchGuard` 按解析后的 IP 判。

### 交互提问：`AskUserQuestionTool`

它是**唯一会挂起一轮**的工具：模型问一句、这一轮原地等人答，答完接着做。默认等 900 秒。

HTTP 没有推送面，所以客户端要问两次——一次是挂着的那一轮，一次是拿问题：

```bash
# 终端 A：这一句会挂住，直到你回答（或超时）
curl -s --noproxy '*' -X POST localhost:8080/api/chat \
  -H 'Content-Type: application/json' \
  -H 'X-Claw4j-User: q1' -H 'X-Claw4j-Role: admin' \
  -d '{"text":"用 AskUserQuestionTool 问我：部署到生产还是预发？两个选项，推荐预发。等我答完再往下说"}'

# 终端 B：先看它问了什么（没在等时是空表）
curl -s --noproxy '*' 'localhost:8080/api/chat/questions' \
  -H 'X-Claw4j-User: q1' -H 'X-Claw4j-Role: admin'

# 再回答。204 = 那一轮收下了；409 = 这段对话没有在等答案的问题
curl -s --noproxy '*' -o /dev/null -w '%{http_code}\n' -X POST localhost:8080/api/chat/answer \
  -H 'Content-Type: application/json' \
  -H 'X-Claw4j-User: q1' -H 'X-Claw4j-Role: admin' \
  -d '{"text":"2"}'
```

**期望**：终端 A 的那个请求在 `answer` 之后返回，回答里出现「预发」；回 `1` 就出现「生产」。
回一句自由文本（比如「都行，你定」）也算答，模型拿到的是你这句话。

**IM 上不用这两条命令**：问题会发到那段对话里，你直接回一句话就是答案，会先收到一句「收到你的回答，接着做。」
（前提：答复的人权限要和提问的人一致——别人替你答不会被当成答案。）

**不想等 15 分钟**：`CLAW4J_ASK_TIMEOUT_SECONDS=30` 起实例，不答，30 秒后模型回一句
「没有人回答这个问题，我按自己的判断……」继续。

---

## 2. MCP server

**前提**：`application-local.yml` 里已经写了四个 server（`demo` / `github` / `mail` 走 stdio，
`java-http` 走 HTTP），`local` profile 即连。接自己的 server 就照
[README-adding-capabilities.md](README-adding-capabilities.md) 第 2 节加一段 YAML。

**触发**：连问两次同一件事，中间隔两秒——

```bash
curl -s --noproxy '*' -X POST localhost:8080/api/chat \
  -H 'Content-Type: application/json' \
  -H 'X-Claw4j-User: m1' -H 'X-Claw4j-Role: admin' \
  -d '{"text":"用 demo_now 报一次 pid 与时间戳，只给数字"}'

sleep 2

curl -s --noproxy '*' -X POST localhost:8080/api/chat \
  -H 'Content-Type: application/json' \
  -H 'X-Claw4j-User: m2' -H 'X-Claw4j-Role: admin' \
  -d '{"text":"再用 demo_now 报一次 pid 与时间戳，只给数字"}'
```

**期望**：两次 **pid 相同**、**时间戳变大**。pid 相同 = 同一个外部子进程在答，
这就排除了"模型自己编了一个时间"。

**再确认**：

```bash
curl -s --noproxy '*' \
  'localhost:9090/actuator/metrics/claw4j.tool.calls?tag=tool:demo_now&tag=outcome:ok'
```

`value` 是 `2.0`；启动日志有一行
`MCP server 状态：demo → UP(工具 2 个)，github → UP(工具 3 个)，mail → UP(工具 2 个)`。

**跨进程往返不丢字**：

```bash
curl -s --noproxy '*' -X POST localhost:8080/api/chat \
  -H 'Content-Type: application/json' \
  -H 'X-Claw4j-User: m3' -H 'X-Claw4j-Role: admin' \
  -d '{"text":"用 demo_echo 把这句话原样带回来：跨进程往返不丢字-42"}'
```

回答里那句话要逐字出现。

### 走 HTTP 的那个（Java 示例）：先起 server

前面三个都是 stdio——应用去 fork 一个进程。`java-http` 不一样：它是个自己监听的端点，
**启动顺序是人为的**，也是这一节唯一能看见退避重连的地方。

```bash
# 另开一个终端，仓库根目录
java -jar claw4j-example-mcp-server/target/claw4j-example-mcp-server-0.1.0-SNAPSHOT.jar
# 最后一行：Tomcat started on port 8082；端口可用 CLAW4J_EXAMPLE_MCP_PORT 改
```

它的三个工具和上面 Python 那份**不重名也不干同一件事**：

```bash
curl -s --noproxy '*' -X POST localhost:8080/api/chat \
  -H 'Content-Type: application/json' \
  -H 'X-Claw4j-User: m4' -H 'X-Claw4j-Role: admin' \
  -d '{"text":"用 runtimeSnapshot 报一下 pid、启动时刻与已处理调用数"}'
```

- `runtimeSnapshot`：`pid` 与宿主不同（宿主用 `jps -l` 找 `claw4j-app`），再问一次
  `handledCalls` 会变大——同一个实例在答。
- `cronNext`：`用 cronNext 算 0 9 * * 1-5 未来两次触发时间`，两行都该是工作日的 09:00。
- `simulateSlowCall`：`用 simulateSlowCall 睡 40000 毫秒`，30 秒左右报超时，
  但日志里**没有** DOWN，紧接着问 `runtimeSnapshot` 仍然成功——慢不等于掉线。

**退避重连怎么验**（这是 HTTP 这一路才看得到的）：

1. 先起应用、**不**起 server：启动日志里 `MCP server broken 暂时不可用…`，紧接着
   `DOWN(15 秒后重试)`。应用照常可用，只是那三个工具不在清单里。
2. 起 server：等一轮退避（15 秒起、每次翻倍、封顶 10 分钟），日志变成
   `MCP server java-http 第 N 次重试后恢复，工具 3 个`，**不用重启应用**，
   这时再问 `runtimeSnapshot` 就能通。
3. 反过来（运行期掉线）：起好之后 `kill` 掉 server，问一句会拿到 **500**
   ——这是已知缺陷，不是预期的"失败一次、模型照常回答"；server 起回来之后再问一句就正常
   （HTTP 传输会懒重建连接）。注意这一点：**运行期掉线不走退避**，日志里不会出现 DOWN，
   退避只在"启动时连不上"这一条路上生效。

**不对时先看哪里**：

- 日志 `broken → DOWN(15 秒后重试)`：括号里是**下次重试的时间**，原因在同一时刻旁边那行 WARN
  （`…连不上：Client failed to initialize by explicit API call`）。应用照常起，只是这一轮
  清单里没有它。之后按 15 秒起、每次翻倍、封顶 10 分钟重连。
- `npx` 起的 server 起不来：`request-timeout`（默认 30s）与 `initialization-timeout`（默认 60s）
  **两个都要调大**，`npx` 冷启动实测 36 秒。
- 要带 `Authorization` 的端点：头里只能写句柄（`Bearer secret:mcp_token`），裸写明文启动就被拒。
- **偶发 503 与 `模型这一轮没有产出可用的回答，请把这句话再发一次`**：这不是 MCP 的问题，
  是模型这一轮把工具参数写成了坏 JSON（真实见过 `Unexpected character ('专')`）。
  模型侧会先在同一家重试一次，还不行才回 503——**再发一次就行**。
  以前这种情况是 `500 Internal Server Error` + 空 body，看不出任何线索。

---

## 3. 脚本包

**前提**：`--claw4j.scripts.directory=examples/scripts`；**要读的文件必须在包目录里**。

**触发**：

```bash
curl -s --noproxy '*' -X POST localhost:8080/api/chat \
  -H 'Content-Type: application/json' \
  -H 'X-Claw4j-User: s1' -H 'X-Claw4j-Role: admin' \
  -d '{"text":"用 summarize 处理 sample.md，把工具返回的原文原样贴给我，不要总结、不要改写"}'
```

**期望**：输出里有 `sample.md` 的**原句**，逐字能对上：

```
- 华东区自签收之日起 7 天内可无理由退货，商品需保持完好、配件齐全、不影响二次销售。
- 华南区为 5 天，需提供原包装；
```

**再确认**：

```bash
curl -s --noproxy '*' \
  'localhost:9090/actuator/metrics/claw4j.tool.calls?tag=tool:summarize&tag=outcome:ok'
```

**不对时先看哪里**：

- **`退出码=1` 且 stderr 里只有一句 `FutureWarning` / `UnicodeEncodeError: 'ascii' codec`**：
  子进程没有 locale（只给了 `PATH`），你 PATH 上的 `python3` 如果是 3.7 以前的，
  stdout 就是 ASCII，打印中文直接崩。修法是脚本入口那行写
  `PYTHONIOENCODING=utf-8 exec python3 …`（[README-adding-capabilities.md](README-adding-capabilities.md)
  第 3 节的模板里就有）。现在失败消息里会带 `stdout=`，脚本自己说的原因不会再被吞掉。
- 问"三句以内"却要"逐字"，是**自相矛盾**：脚本默认输出 5 句，模型会压缩改写。
  验逐字就别限制句数。
- 参数被拒：`路径参数必须是相对路径，拿到的是 …` / `路径 … 越出了脚本包自己的目录`——
  把文件放进包目录、用包内相对路径。
- `weather-forecast` 要出网：本机 SSL 不通时 `outcome:error` 会涨，
  说明**脚本跑起来了**、缺的是网络，不是装配。
- 权限：动作是 `run_script`，默认只有 `admin` 有。把 `-H 'X-Claw4j-Role: admin'` 换成
  `read-only` 再问一次会被拒——这时 `outcome:denied` 涨、`ok` 不涨。

---

## 4. Skill

**前提**：`--claw4j.skills.directory=examples/skills`，**并且配了人设**
（`--claw4j.agent.directory=examples/agents --claw4j.agent.default=sales`）——
口径不在 Skill 里，在人设引用的 `glossary/metrics.md` 里。

**还要有审批标记**：`examples/skills/.review/weekly-sales.properties`（`status=approved`、
`by=谁`、`sha256=` 那一份目录的摘要）。没有它就**不加载**，启动日志会说"未审批或内容已变更"；
改一个字摘要就变，旧标记自动作废。这是有意的——技能正文是**指令**，比一段网页更有分量。

**触发**：

```bash
curl -s --noproxy '*' -X POST localhost:8080/api/chat \
  -H 'Content-Type: application/json' \
  -H 'X-Claw4j-User: k1' -H 'X-Claw4j-Role: admin' \
  -d '{"text":"按 weekly-sales 的口径，给我上周各区成交额"}'
```

**期望**：三区 + 合计 **128400 / 26000 / 31500 / 185900**，且口径写的是
`sum(order_item.amount)` + 排除 `CANCELLED`。

**再确认**：

```bash
curl -s --noproxy '*' \
  'localhost:9090/actuator/metrics/claw4j.tool.calls?tag=tool:Skill&tag=outcome:ok'
```

**有两种"没出数"要分清**：

- 回答是"先确认口径"（问你是含税还是排除已取消）——**这不是失败**，是 Skill 正文里那句
  "没看到口径就先问"在起作用，原因是你的部署没配人设。配上再问一次。
- 直接给了个没出处的口径——那说明正文没加载，只是模型按常识答的。看 `Skill` 的计数是不是 404。

另外：这条流会用到工具检索 + `Skill` + `querySales`，一轮内**可能撞上 6 次工具调用上限**。
回答里出现 `Total tool call limit (6) exceeded` 就把它调大再跑：

```bash
# 起实例时加这一行（默认 6）
--claw4j.agent.tool-call-limit=20
```

---

## 5. 子 Agent

**前提**：`--claw4j.subagents.directory=examples/subagents`，**并且白名单里的工具在容器里**
（`weekly-digest` 要 `querySales` + `summarize`，后者要 `--claw4j.scripts.directory`）。

**触发**（两条典型，用户各换一个）：

```bash
curl -s --noproxy '*' -X POST localhost:8080/api/chat \
  -H 'Content-Type: application/json' \
  -H 'X-Claw4j-User: a1' -H 'X-Claw4j-Role: admin' \
  -d '{"text":"派 capacity-triage 看一下这个服务的容量紧不紧"}'

curl -s --noproxy '*' -X POST localhost:8080/api/chat \
  -H 'Content-Type: application/json' \
  -H 'X-Claw4j-User: a2' -H 'X-Claw4j-Role: admin' \
  -d '{"text":"派 weekly-digest 写一份上周的周报"}'
```

**期望**：

| 子 Agent | 该看到什么 | 不该看到什么 |
| --- | --- | --- |
| `capacity-triage` | 堆 已用/上限 + 百分比（如 `105 MB / 8192 MB，1.3%`）、线程数 | 具体扩容数值（它只该给判断） |
| `weekly-digest` | 三区 + 合计 **185900**，环比写"不可比" | 编出来的环比、没有出数的"表现不错" |

**再确认**：

```bash
curl -s --noproxy '*' \
  'localhost:9090/actuator/metrics/claw4j.tool.calls?tag=tool:Task&tag=outcome:ok'
```

想看得更细，起实例时加 `--logging.level.org.springframework.ai=DEBUG`，会有一行
`委派 task 给 Subagent weekly-digest：它这趟能用的工具是 [querySales, summarize]`。

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

**怎么验**（这条不用问业务问题，看启动日志就够）：

```
这个部署的 agent 是 sales（销售助手：…），人设来自 examples/agents；时区 Asia/Shanghai；
工具档位 MESSAGING（来自 人设文件的 tier）
```

三件事一眼可查：**选中的是哪份**、**人设从哪来**、**档位来自哪一层**（人设的 `tier` 还是全局配置）。

**行为上也验一次**：

```bash
curl -s --noproxy '*' -X POST localhost:8080/api/chat \
  -H 'Content-Type: application/json' \
  -H 'X-Claw4j-User: p1' -H 'X-Claw4j-Role: admin' \
  -d '{"text":"你能跑脚本吗？帮我跑个脚本算一下上周成交额"}'
```

`sales` 的档位是 `messaging`，不该有脚本能力，回答应当拒绝而不是硬跑。

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
curl -s --noproxy '*' -X POST localhost:8080/api/chat \
  -H 'Content-Type: application/json' \
  -H 'X-Claw4j-User: r1' -H 'X-Claw4j-Role: admin' \
  -d '{"text":"东西买了能退吗？华东和华南的期限分别是多少"}'
```

**期望**：华东 **7 天**、华南 **5 天**，并带 `[1]` 这样的出处；答里还会点出"按签收日算"这类
只在文档里写的细节——那是真的检索到了，不是常识。

**触发二（查不到就不编）**：

```bash
curl -s --noproxy '*' -X POST localhost:8080/api/chat \
  -H 'Content-Type: application/json' \
  -H 'X-Claw4j-User: r2' -H 'X-Claw4j-Role: admin' \
  -d '{"text":"SKU-2024 这个商品现在还剩多少库存"}'
```

**期望**：明确说**查不到**，并说明为什么（没有库存表 / 那不是这个库能答的）。
如果它给了个像模像样的数字，就是这套东西最该被修的地方。

**看重排有没有真跑**：起实例时加 `--logging.level.io.github.claw4j=DEBUG`，会有一行

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

**HTTP 通道 · 能问答**：

```bash
curl -s --noproxy '*' -X POST localhost:8080/api/chat \
  -H 'Content-Type: application/json' \
  -H 'X-Claw4j-User: c1' -H 'X-Claw4j-Role: admin' \
  -d '{"text":"你好"}'
```

**HTTP 通道 · 表级拒**（把角色换成 `guest`）：

```bash
curl -s --noproxy '*' -X POST localhost:8080/api/chat \
  -H 'Content-Type: application/json' \
  -H 'X-Claw4j-User: c2' -H 'X-Claw4j-Role: guest' \
  -d '{"text":"上周华东成交额是多少"}'
```

回答应当是一句拒绝：

```
没有权限：角色 guest 不允许访问表 customer、order_item、sales_order（工具 querySales）
```

判据是**回答是这句拒绝**、并且下面这个查询 `denied` 涨、`ok` 不涨：

```bash
curl -s --noproxy '*' \
  'localhost:9090/actuator/metrics/claw4j.tool.calls?tag=tool:querySales&tag=outcome:denied'
```

别用 `read-only` 验"被拒"——它的 `tables` 就是 `["*"]`，能正常查到数（实测 128400）；
默认三个角色里"看不到业务表"的是 `guest`（它只有 `region`）。
而 `read-only` 拒的是**动作**层（`run_script`），见 §3 最后一条。

**IM 通道（要自己的凭据）**：发一条消息应收到回答；再让平台**重投同一条事件**
（钉钉 `msgId`、Slack `event_id`），回答**只出现一次**——幂等在 `channel.kernel` 里，
你的实现只要做到"拿到消息立刻 ACK + 用事件 id 去重 + 认不出人就拒绝"这三条。

HTTP 通道**没有事件 id**，所以"重复事件"这条验不了它：往它灌同一句话两次会再答一次
（关掉缓存后是真的答两次），那不是 bug，是它本来就没有幂等键。

**不对时先看哪里**：

- 没人能提问但通道连上了：角色表白名单里没有这个人（`claw4j.channel.*.roles`），
  日志里会有带 `sender=` 的 WARN。
- 重复投递答了两次：看事件 id 有没有被传进 `InboundEvent`；`channel.kernel` 只在有 id 时去重。

---

## 一页速查

完整命令都在对应小节里；这里只列"问什么、看什么"。

| 能力 | 问什么（第几节） | 主要判据 | 最容易踩的坑 |
| --- | --- | --- | --- |
| Java 工具 | 上周华东成交额（§1） | 128400 + `querySales ok` 计数 | 只看"答了数字"，不看计数 |
| MCP | 连问两次 `demo_now`（§2） | pid 相同、时间戳变大 | `npx` 超时两个键都要调 |
| 脚本包 | 用 `summarize` 处理 `sample.md`，原样贴回（§3） | 与原文逐字相同 | 限制"三句以内"就验不了逐字 |
| Skill | 按 `weekly-sales` 口径给上周各区成交额（§4） | 185900 + `Skill ok` 计数 | 没配人设 → 它会先问口径（正常） |
| 子 Agent | 派 `weekly-digest` 写上周周报（§5） | 三区 + 185900 + `Task ok` 计数 | 白名单工具缺失 → **首次委派**才失败 |
| 人设 | 看启动日志（§6） | `agent 是 sales…档位 MESSAGING（来自 人设文件的 tier）` | 多份没写 `default` → 启动失败 |
| 知识库 | 能退吗 / `SKU-2024` 还剩多少（§7） | 7 天 / 5 天带出处；后者说查不到 | 不看重排那行 DEBUG 就不知道它有没有生效 |
| 通道 | 你好 / 用 `guest` 问成交额（§8） | 有回答；后者是表级拒且 `denied` 涨 | HTTP 没有事件 id，验不了重复 |
