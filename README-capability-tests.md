# 验收：我加的能力，到底生效了没有

[README-adding-capabilities.md](README-adding-capabilities.md) 讲**怎么加**，这一份讲**怎么验**。

模型自述"我调用了"不算证据——它会把上一轮的回答记成这一轮的。**I（审计）是例外**：只看
`/api/audit` 里有没有那一行，库里没有就是没有。

## 怎么用这一份

**先分清这条用例判的是"结果"还是"动作"**——两者混在一起，会把"模型换了个合理做法"判成缺陷：

| 判什么 | 用在哪 | 怎么判 |
| --- | --- | --- |
| **结果** | 用户要的东西只能来自那个工具（读文件原文、查数据库数字、抓网页正文） | 内容对得上 **且** 计数涨了 |
| **动作** | 用例的目的是"这个工具有没有人用"（`TodoWrite`、`AskUserQuestionTool`） | 看计数；但题面必须先让**工具成为必要手段**，否则正文里办到也算办了 |

模型说"我调用了"不算证据。**I（审计）是例外**：只看 `/api/audit` 里有没有那一行。

| 类 | 验什么 | 用例 |
| --- | --- | --- |
| **A 装配** | 在不在清单、MCP 连没连、通道准入、人设选对没 | A1–A4 |
| **B 能力** | 回答对不对、计数涨没涨 | B1 / B2、B3、B4、B5、B6（4 条）、B7、B8 |
| **C 权限与边界** | 看不见的、要人批的、谁能批的、外部内容 | C1–C5 |
| **D 排除干扰** | 缓存 / 记忆 / 种子造成的假阳性 | 每类都可能撞上 |
| **E 故障与降级** | 掉线、超时、熔断时长什么样 | E1–E3 |
| **F 模型层** | 主备换家、冷却、白名单、形状、缓存命中 | F1–F6 |
| **G Agent 自管理** | 计划落库、按事件触发、提示词超限 | G1–G3 |
| **H 流式** | 分帧、命令不进模型、工具静默、忙、断流 | H1–H5 |
| **I 审计** | 谁干的、成没成、为什么被拦、送到没有 | I1–I9 |
| **J 会话读侧** | 列得出、看得到、别人的看不到 | J1–J3 |
| **K 记忆** | 短期接得上、长期跨会话还在、索引不用先调工具 | K1–K6 |

- **题目怎么出**：装配/通道（A1 A2 E1）、权限（C2 C4）、技能脚本（B4）、流式与故障注入（H3 E2）**要点名工具**；工具选择/检索（B2 B8）**绝不点名**——点名等于替模型指路，测出来的只是"会不会照指令做"。判据随之变：装配类看"那个工具涨没涨"，选择类看"涨的是不是对的那一个"。实测产物写在本机 `docs/acceptance/<日期>/`，**不入库**——日志与样张是跑完就该丢的东西。
- **出题的三条硬约束**（2026-11-27 复跑踩出来的，违反必红且红得没意义）：
  1. **算得过来**：单工具 ≤8 次、一轮 ≤20 次（`ToolCallBudget`，两条都可配：`tool-call-limit-per-tool` / `tool-call-limit`）。要"数一遍目录"又要"读几个文件"就会撞
     `Tool call limit (3) exceeded`，而且那行错误会**当成回答**返回给用户；
  2. **不做工具做不到的事**：`WebFetch` 是"抓回来按 prompt 提炼"的，问它"原样抄、不要总结"永远红；
  3. **一次只变一个量**：验记忆就用**不查工具**的题面，别拿"区间成交额"去问——第一句没数时，第二句答不上来
     分不清是记忆坏了还是工具没调到。同理，判据要求"某个工具被调"，题目就必须是"只有那个工具能解决"的事。

## 0. 共同前提

### 起一个能力全开的实例

```bash
set -a; . ./.env; set +a
./mvnw -q -DskipTests clean package   # 必须带 clean：改了迁移/资源不 clean，旧的会被打进 fat jar

java -jar claw4j-app/target/claw4j-app-0.1.0-SNAPSHOT.jar \
  --spring.profiles.active=local \
  --claw4j.advisor.session.dm-scope=per-channel-peer \
  --claw4j.channel.http.users='*' \
  --claw4j.skills.directory=examples/skills \
  --claw4j.subagents.directory=examples/subagents \
  --claw4j.agent.directory=examples/agents --claw4j.agent.default=assistant \
  --claw4j.advisor.cache.enabled=false
```
三个目录给全 A / B 才都验得了（`agent.directory` + `default` 是 A4、B3 的前提）；`users='*'` 是验收姿态，代价是 A3 验不了（另起一份收紧名单）；人设的 `tier` 会覆盖 `claw4j.tools.profile`，C1 要用没有 `agent.directory` 的实例。建议指向一次性库（`--spring.datasource.url=jdbc:postgresql://localhost:5432/<临时库>`），Flyway 从零建表，跑完 drop；别在有人用过的库上验索引。`dm-scope` 那行不可省：本机名单不止一人时 `dm-scope=main` 会把不同人的私聊并成一段上下文，启动校验直接拒。

**只有一份通用人设**（`assistant`，`tier: full`），业务数字与运维问题同一个入口，A1 / B1 不用换人设。若答「当前环境里没有能查业务库的工具」，先查日志有没有 `工具检索 hybrid`——没有就是模型没检索，不是人设不干这个。

**最后一行必须带**：语义缓存默认开，命中时这一轮没有模型调用、计数一次都不涨（而"不涨"正是主判据），还会缓存失败、Redis 连不上就起不来；关掉不影响知识库。这是验证姿态，不是配置建议。

### 发一轮对话

```bash
curl -s --noproxy '*' -X POST localhost:8080/api/chat \
  -H 'Content-Type: application/json' \
  -H 'X-Claw4j-User: t1' \
  -d '{"text":"2026-11-16 到 2026-11-22 华东区的成交额是多少"}'
```
回答在返回 JSON 的 `text` 字段。`X-Claw4j-User` 就是"我是谁"，`t1` 只是代号——每条、每次复测都换一个（`t2`、`m1`…），否则模型会"记得"上一轮失败而拒绝重试；它必须在 `claw4j.channel.http.users` 白名单里（名单默认空 = 谁都不能进）。

### 查工具被执行了几次
计数在管理口 9090（只绑 `127.0.0.1`），**必须带 `tag=tool:<名>`**：

```bash
curl -s --noproxy '*' \
  'localhost:9090/actuator/metrics/claw4j.tool.calls?tag=tool:querySales&tag=outcome:ok'
```
`outcome` 只有 `ok` / `error`，**没有 `denied`**（被拦下的调用没执行）；被审批拦下的算 `error`。**没调用过时返回 404，不是 `value: 0`**——两者是一回事。

**`toolSearchTool` 不在这里，也不在审计表里。** 它是披露层自己塞进清单的工具，绕过了计数与审计的外层包装。数"搜没搜、搜了几次"要用另一个指标：

```bash
curl -s --noproxy '*' 'localhost:9090/actuator/metrics/claw4j.tool.search.calls'   # COUNT = 全程检索次数
grep "工具检索 hybrid" /tmp/claw4j.log | cut -c1-260                              # 每次的 query 与候选
grep "工具检索规则命中" /tmp/claw4j.log | cut -c1-260                            # 每次命中了哪条规则、把谁提到最前
```

别再翻 `claw4j.tool.calls` 或 `audit_event` 找 `toolSearchTool` —— 那两处永远没有它，漏看会得出"一次都没搜"的错误结论。
`query` 与"置顶了谁"这两行最值钱：检索只在**索引里的工具**中排名，规则层在此基础上把命中的工具
提到最前（**不删任何候选**，2026-11-28 起；之前那道"没命中就拿掉"的准入闸已删）。
很多"模型说它没有这个工具"的红，根子在这两行里，不在提示词。

> **下文出现的 `/绝对路径` 都要换成仓库根的绝对路径**（`pwd` 的结果）。原样粘会让模型读不到文件，
> 看起来像"能力不通"，实际是题目喂错了输入——这一条已经被踩过。

## A. 装配：接上了吗

### A1 工具进了清单

验什么：问一句只有这个工具能答的话，计数从 404 变 1.0。
- **判据**：计数涨了才算接上；回答里提到工具名不算。
- **坑**：说"我没有这个工具"且计数 404 = 不在清单（档位漏了或画像筛掉了，见 C1）；计数 404 却说"我查了" = 模型编的；启动直接失败并点名 = 缺档位，这是刻意设计。

### A2 MCP 连上了

验什么：`local` 自带四个 server（`demo` / `github` / `mail` 走 stdio，`java-http` 走 HTTP）；点名 `demo_now` 是刻意的，本节验的就是那条通道接没接上。

```bash
curl -s --noproxy '*' -X POST localhost:8080/api/chat \
  -H 'Content-Type: application/json' -H 'X-Claw4j-User: m1' \
  -d '{"text":"用 demo_now 报一次 pid 与时间戳，只给数字"}'

sleep 2

curl -s --noproxy '*' -X POST localhost:8080/api/chat \
  -H 'Content-Type: application/json' -H 'X-Claw4j-User: m2' \
  -d '{"text":"再用 demo_now 报一次 pid 与时间戳，只给数字"}'
```

```bash
curl -s --noproxy '*' \
  'localhost:9090/actuator/metrics/claw4j.tool.calls?tag=tool:demo_now&tag=outcome:ok'
```

```bash
curl -s --noproxy '*' -X POST localhost:8080/api/chat \
  -H 'Content-Type: application/json' -H 'X-Claw4j-User: m3' \
  -d '{"text":"用 demo_echo 把这句话原样带回来：跨进程往返不丢字-42"}'
```

- **判据**：两次 **pid 相同、时间戳变大**（pid 相同 = 同一个子进程在答，排除自编），`demo_now` 的 `ok` **计数 2.0**（这条是主判据），启动日志另有一行 `UP`；`demo_echo` 那句 `跨进程往返不丢字-42` 逐字带回。
- **坑**：`java-http` 那边的 `runtimeSnapshot` 也报 pid 与运行时长，模型会拿它顶替 —— 判据只认 `demo_now` 的计数；pid 对不上而对得上计数，说明它换了个工具答，不是通道断了。没连上、重连、掉线见 E1。

### A3 通道准入

验什么：名单外的人被挡在门外，模型根本没被调用。

```bash
curl -s --noproxy '*' -X POST localhost:8080/api/chat \
  -H 'Content-Type: application/json' -H 'X-Claw4j-User: nobody' \
  -d '{"text":"2026-11-16 到 2026-11-22 华东区的成交额是多少"}'
```

```bash
--claw4j.channel.http.users=t1,ethan
```

- **判据**：`401` + `{"error":"用户不在 claw4j.channel.http.users 白名单里：nobody。…"}`，且计数一次都不涨。
- **坑**：这一条要另起实例收紧名单（0 节那份是 `users='*'`，谁都能进）；IM 通道要自备凭据，让平台重投同一事件（钉钉 `msgId`、Slack `event_id`）回答只出现一次——HTTP 没有事件 id，灌同一句两次会再答一次，那不是 bug。

### A4 人设选对了哪一份

验什么：不看业务，看启动日志那行。

```
这个部署的 agent 是 assistant（通用企业助理：…），人设来自 examples/agents；时区 Asia/Shanghai；
工具档位 FULL（来自 人设文件的 tier）
```

- **判据**：选中的是哪份、人设从哪来、档位来自哪一层（人设 `tier` 还是全局配置），一眼可查。
- **坑**：目录里有多份却没写 `default` → 启动失败并列出候选。

## B. 能力：真能用吗

### B1 业务工具（以自带的 `querySales` 为例）

验什么：问**固定日期区间**，不问"上周/本周"。种子日期由 `V2__pin_seed_dates.sql` 钉死成 2026-11-02 ~ 2026-11-23，跟今天是哪天、库是哪天建的一点关系都没有，重灌也不变。代价写在 D2：数据固定在 11 月，所以"上周/本周"这类相对问法今天已经查不到数——那是数据的事，不是工具坏了。

```bash
curl -s --noproxy '*' -X POST localhost:8080/api/chat \
  -H 'Content-Type: application/json' -H 'X-Claw4j-User: t1' \
  -d '{"text":"2026-11-16 到 2026-11-22 华东区的成交额是多少"}'

curl -s --noproxy '*' -X POST localhost:8080/api/chat \
  -H 'Content-Type: application/json' -H 'X-Claw4j-User: t2' \
  -d '{"text":"2026-11-19 华东区的成交额是多少"}'
```

- **判据**：区间那问 → 华东 **128400**（换区域同理：华北 31500、华南 26000）；单日 11-19 → **74400**；每次 `querySales` 的 `ok` 多 1。跑之前想确认库是哪一版种子：`select min(order_date), max(order_date) from business.sales_order` 应是 `2026-11-02` / `2026-11-23`。
- **坑**：区间那问若把 `CANCELLED` 那单算进去是 **178400**（差 50000，一眼看得出来），数字对了才算排除写对。计数 404 → 工具没执行，换新用户问同一句，再看日志里有没有 `工具检索 hybrid`；计数涨了但数字错 → 问题在工具本身（SQL、时区、`CANCELLED` 没排），不在装配。别把"上周"塞回判据：数据钉在 11 月，问"上周"必然空，空 ≠ 工具坏了。

  **2026-11-28 复跑实测**：`claw4j.rag.enabled=true`（`local` 的默认）时，这一问 2/2 答"查不到，资料里没有"、检索 0 次；同一句在 `--claw4j.rag.enabled=false` 的实例上 2/2 答 128,400.00、检索各 1 次。同一形态还出现在 B2 的 `Grep` / `WebFetch`、B5 的派单、B7 的提问、G2 的意图上——**知识库先返回了一批"像但不相关"的文档，模型就把"资料里没写"当成了"查不到"，不再翻工具目录**。所以这条红了先看两件事：日志里有没有 `工具检索 hybrid`（没有 = 它没检索），以及实例的 `rag.enabled`。

### B2 默认工具：`Read` / `Grep` / `Glob` 常驻，`WebFetch` 按用户原话给

验什么：不用配，启动就在清单里；判据是工具真被执行了，不是回答里提到文件名。题目一律用人话，不点工具名、不说参数名。

```bash
curl -s --noproxy '*' -X POST localhost:8080/api/chat \
  -H 'Content-Type: application/json' -H 'X-Claw4j-User: d1' \
  -d '{"text":"examples 目录下有哪些 .md 文件？"}'

curl -s --noproxy '*' -X POST localhost:8080/api/chat \
  -H 'Content-Type: application/json' -H 'X-Claw4j-User: d2' \
  -d '{"text":"/绝对路径/examples/README.md 开头六行讲了什么？引用原文。"}'

curl -s --noproxy '*' -X POST localhost:8080/api/chat \
  -H 'Content-Type: application/json' -H 'X-Claw4j-User: d4' \
  -d '{"text":"examples 里有没有提到 refund 的地方？把相关那几行发我。"}'

curl -s --noproxy '*' -X POST localhost:8080/api/chat \
  -H 'Content-Type: application/json' -H 'X-Claw4j-User: d3' \
  -d '{"text":"https://example.com 这个网页在讲什么？三句话以内。"}'
```

- **判据**：列 `.md` → `Glob` +1 且文件名真存在；读开头六行 → `Read` +1 且逐字一致；refund → `Grep` +1 且匹配行搜得到；这页讲什么 → **`WebFetch` +1**，回来的是提炼过的话，不是 HTML 源码。`WebFetch` 不是常驻，是**按用户原话点亮**：话里有"网址/网页/链接/URL"就进清单，日志会打 `用户原话命中「网址|…」，这一轮直接给出：[WebFetch]`——**不必先检索**。**题目只能问"讲了什么"**——这工具是"抓回来让模型按 prompt 提炼"的，要求它"原样抄、不要总结"是让它做做不到的事，那种题面永远红，别写。
- **坑（2026-11-28 实测，deepseek-flash；按审计与抓包还原过整条链）**：用户原话是「examples 里有没有提到 refund 的地方？把相关那几行发我。」——这一轮**一次检索都没发**，先调常驻的 `Glob`（成功），紧接着点了 `Bash(grep -rn -i refund …)`。`Bash` 不在这一轮披露的 12 个工具里，框架抛 `IllegalStateException: No ToolCallback found for tool name: Bash`，**整轮 500、回答是空的**；审计里 `Bash` 没有 `tool_action` 行（它根本没执行），`agent_run` 记 `failed`。
  它为什么改道：思维链写的是"Grep 可能跳过点目录，`.review/…` 在隐藏目录里"。**这个猜测是错的**——`GrepTool` / `GlobTool` 的 `isIgnoredPath` 只排 `.git/ node_modules/ target/ build/ .idea/ .vscode/ dist/ __pycache__/`，`.review/` 照样搜；而且这句问的是"内容里有没有"（`Glob` 只匹配文件名，本来就答不了），常驻的 `Grep` 一问就有：`grep -rl refund examples` 是那 5 个文件，`.review/refund-triage.properties` 文件名带 refund、内容里没有。
  所以这条要分开看：**"Bash 该不该给"和"点名没披露的工具要不要 500"是两个问题**。前者看检索有没有召回、规则有没有点名（2026-11-28 起不再有"没命中就删"的准入闸，触发词表只在命中时把工具提前）；后者才是缺口。
  **2026-11-28 已补**：`ToolCallBudget` 给 manager 挂了 `UndisclosedToolFallback`（占位回调，不是真工具），
  未披露的名字现在回一条工具结果——「不在这一轮的工具清单里…要就自己写 query 去 `toolSearchTool` 检索一次」——
  模型下一轮自己改道，不再 500。判据从"有没有异常栈"变成"回答里有没有出现「不在这一轮」这类转述，
  以及这一轮是不是接着走下去了（`agent_run` 记 `succeeded` 而不是 `failed`）。
- **坑**：`Read` / `Grep` / `Glob` 常驻，`WebFetch` 靠"用户原话命中"进清单——**它自己不会想起去检索**（实测让它"先检索一下有没有抓网页的工具"，它先回一句"我这边上不了网"就收工了）。所以题面里要出现网址、而不是要求它先找工具。写文件默认关（要开 `CLAW4J_FILES_WRITE_ENABLED=true`，日志写「Write / Edit 已开」），`WebSearch` 没 `BRAVE_API_KEY` 不装配，`Path is outside the allowed directories` = 超出启动日志那行「文件工具的边界目录」。

### B3 Skill：正文真的进了上下文吗

验什么：`--claw4j.skills.directory=examples/skills` + 人设（`--claw4j.agent.directory=examples/agents --claw4j.agent.default=assistant`）；还要有 `examples/skills/.review/weekly-sales.properties`（`status=approved`、`by`、`sha256`），没有就不加载。

**题目要问"格式"，不要只问"口径"**：具体取数口径在 `querySales` 的工具描述里（schema 段），
而 `glossary/metrics.md` 只定"一次统计要交代哪四件事"（2026-11-28 起它不再写具体指标），Skill 正文开头就写明"那段由人设给出，这里不再抄一遍"——所以问口径它不必打开 Skill，`querySales` 加人设照样答得对。只有"按固定格式出周报"这类事（三区+合计的排版、末尾那句环比提示）才只在正文里。

```bash
curl -s --noproxy '*' -X POST localhost:8080/api/chat \
  -H 'Content-Type: application/json' -H 'X-Claw4j-User: k1' \
  -d '{"text":"按 weekly-sales 那套固定格式，出一份 2026-11-16 到 2026-11-22 的各区成交额周报，末尾该点的那句也点上"}'
```

- **判据**：三区 + 合计 **128400 / 26000 / 31500 / 185900**（回答里通常带千分位，按值比，别按字符串比），口径写明 `sum(order_item.amount)` 且排除 `CANCELLED`，末尾点明环比要的是紧邻的上一个同长区间（这一版不查）；`Skill` 的 `ok` +1。
- **坑**：回答"先确认口径"不是失败——是正文规则在起作用，原因是没配人设；直接给没出处的口径才是正文没加载（看 `Skill` 计数是否 404）。**只问口径那版是假红**：数字全对、`Skill` 0 次，看着像"正文没加载"，其实是题目没逼它打开。

### B4 技能里的脚本：跑的是 `Bash`，不是某个工具

验什么：`summarize` 是一份技能，模型用 `Skill` 拿正文（含"脚本在哪"），再用 `Bash` 跑 `bash <技能目录>/scripts/run.sh sample.md`。

```bash
curl -s --noproxy '*' -X POST localhost:8080/api/chat \
  -H 'Content-Type: application/json' -H 'X-Claw4j-User: s1' \
  -d '{"text":"用 summarize 技能处理它自带的示例文件 sample.md，把脚本返回的内容原样贴出来（这是演示用的示例文件，可以直接贴）"}'
```

- **判据**：`sample.md` 的原句逐字对得上（第一句会把标题行也带进来，按句切分），`Bash` 的 `ok` +1——**没有 `summarize` 这个工具**。
- **坑**：`Skill` 计数 0 = 技能没加载（日志「技能未审批或内容已变更，未加载：summarize」，改一个字摘要就变，要重算标记）；`Bash` 报 `No such file or directory` = 用了相对路径（正文 `Base directory for this skill:` 就是让它拼绝对路径的）；`退出码=1` 且 stderr 只有 `UnicodeEncodeError: 'ascii' codec` = 子进程没 locale，脚本入口写 `PYTHONIOENCODING=utf-8`。
- **坑**："三句以内"却要"逐字"自相矛盾（默认输出 5 句）；说"原样贴出来"可能被当成外泄，补一句"这是技能自带的示例文件"。

### B5 子 Agent：结论只能来自它的白名单

验什么：`--claw4j.subagents.directory=examples/subagents`，白名单里的工具得在容器里（`weekly-digest` 要 `querySales` + `Skill` + `Bash`）。

```bash
curl -s --noproxy '*' -X POST localhost:8080/api/chat \
  -H 'Content-Type: application/json' -H 'X-Claw4j-User: a1' \
  -d '{"text":"派 capacity-triage 看一下这个服务的容量紧不紧"}'

curl -s --noproxy '*' -X POST localhost:8080/api/chat \
  -H 'Content-Type: application/json' -H 'X-Claw4j-User: a2' \
  -d '{"text":"派 weekly-digest 写一份 2026-11-16 到 2026-11-22 的成交周报"}'
```

- **判据**：`capacity-triage` 给堆已用/上限 + 百分比、线程数，不给扩容数值；`weekly-digest` 给三区 + 合计 **185900**、环比写"不可比"；`Task` 的 `ok` +1。
- **坑**：模型自己写 SQL，区间会偶发写错（表现"这一段 0、另一段 185900"）——答 0 就换新用户重跑，连错两次才是检索或工具问题；加 `--logging.level.org.springframework.ai=DEBUG` 能看到 `委派 task 给 Subagent … 它这趟能用的工具是 [...]`，多出一项就是白名单没生效；白名单点了容器里没有的工具启动不失败、第一次委派才点名，`Task` 不在常驻名单，零计数先确认检索有没有把它交出去。

  **2026-11-28 实测（抓包看过）**：模型会**一次响应里并行发两个 `toolSearch`**（一个找子 Agent、一个找取数工具），一轮就用掉两次（当时单工具上限 3）；再补一次到 3，第四次直接被 `Tool call limit (3) exceeded for tool 'toolSearchTool'` 中止，用户看到「这一步做了太多次」。**不是它重复搜，是计数按"每次调用"算、而它按"要查几件事"并行发**。2026-11-28 起两条一起放宽：单工具 8、整轮 20（都可配），检索与业务工具同一个 per-tool 数。看到这句先数日志里 `工具检索 hybrid` 的行数、看这些 query 是不是各查各的，再决定是调上限还是改预算口径。

### B6 知识库：从一堆文档里挑对，还带出处

验什么：`local` 自带 `examples/knowledge`（几十份虚构公司制度文档，含无关内容、废止旧版、术语表），难点是在都像相关的段落里挑对那一段，所以判据落在具体数字与出处上。每条换一个 `X-Claw4j-User`（同一用户连问三条，从第二条起测的是记忆）；这些名字必须进 `claw4j.channel.http.users`，否则 401。别的部署要 `claw4j.rag.enabled=true` + `directory` + pgvector + qwen key。
**B6.0 先看那行：份数、段数、两个开关**

```
知识库已启用：目录 examples/knowledge、最终取 10 段、每路候选 25、相似度下限 0.5、块长 800 token、混合检索 开、重排 开
知识库同步完成：111 份文档里重建 111 份、跳过 0 份、删掉 0 份，现有 440 段（切块 …, 目录 …）
```

- **判据**：份数与 `find examples/knowledge -name '*.md' | wc -l` 对得上；`现有 N 段` 不是 0（实测 111 份切 440 段）；`混合检索 开`、`重排 开` 都是开（`最终取 / 每路候选` 跟着 `claw4j.rag.top-k` 默认 10 与 `hybrid.candidates-per-route` 默认 25 走）。`重建 / 跳过 / 删掉` 三个数不当判据，只反映跟上一次比变了什么。
**B6.1 单跳：答案只在一份文档里的一句**

```bash
curl -s --noproxy '*' -X POST localhost:8080/api/chat \
  -H 'Content-Type: application/json' -H 'X-Claw4j-User: r1' \
  -d '{"text":"东西买了能退吗？华东和华南的期限分别是多少"}'
```

- **判据**：华东 **7 天**、华南 **5 天**，带 `[N]` 编号，并点出"按签收日算、不按付款日算"这类只在文档里写的细节（数字可能蒙对，细节蒙不出来）。
**B6.2 跨文档：答案要两份文档拼起来**

```bash
curl -s --noproxy '*' -X POST localhost:8080/api/chat \
  -H 'Content-Type: application/json' -H 'X-Claw4j-User: r5' \
  -d '{"text":"打折卖出去的机器退回来，按哪个金额退？"}'
```

- **判据**：按**实际成交价**退、不按标价；只答金额就算过（`pricing.md`"按实付金额"与 `refund-process.md`"按该型号的折后价"是同一条规则，落到哪份都对）。
**B6.3 查不到就不编**

```bash
curl -s --noproxy '*' -X POST localhost:8080/api/chat \
  -H 'Content-Type: application/json' -H 'X-Claw4j-User: r9' \
  -d '{"text":"SKU-2024-A1 现在仓库里还剩多少台？"}'
```

- **判据**：明确说**查不到**（知识库只有政策文档、业务库也没有库存表），可以建议走业务工具，但**不许给数字**——与 B1 对着看：B1 该查，这条该说没有，混淆的表现是"该查时说没有资料"或"没有的地方编一个数"。
- **B6.4 不对时先看哪里**：起不来且点名 `claw4j.rag.directory` = 开了知识库没配目录（或目录里一个 `.md`/`.txt` 都没有）；答"没有相关资料"但文档里有 = 先看 `现有 N 段`（0 = 同步没跑），非 0 再把 `claw4j.rag.min-similarity` 临时降到 0.2 复问。
- **答错文档 / 数字对出处错**：不是阈值问题，是 `top-k`（默认 10）被两路重复结果占满或重排问题，看 DEBUG 日志的召回原文（出处是拼进上下文的原文，错 = 没读、用常识补的）。
- **只答对一半**：先确认 `top-k` 大于 2（两路各来一段，`top-k=1` 时"拼起来"发生不了）；只有 `[N]` 没有文件名是当前引用格式（`RagMetadata#citation`），只算"有引用"不算"出处可读"。要数字与门槛看 `./eval/rag/run.sh`，默认不进 `./scripts/verify.sh`。

### B7 交互提问：唯一会挂起一轮的工具

验什么：模型问一句、这一轮原地等人答，默认等 900 秒；HTTP 无推送面，客户端三步。题目里说"让我选 / 拿不定"就该用工具，**而不是在正文里写两个选项**。

```bash
curl -s --noproxy '*' -X POST localhost:8080/api/chat \
  -H 'Content-Type: application/json' -H 'X-Claw4j-User: q1' -H 'X-Claw4j-Chat-Id: ask-1' \
  -d '{"text":"帮我部署，但我还没想好发生产还是预发——你给两个选项让我选，推荐预发，我选完你再动手。"}'

curl -s --noproxy '*' 'localhost:8080/api/chat/questions' \
  -H 'X-Claw4j-User: q1' -H 'X-Claw4j-Chat-Id: ask-1'

curl -s --noproxy '*' -o /dev/null -w '%{http_code}\n' -X POST localhost:8080/api/chat/answer \
  -H 'Content-Type: application/json' -H 'X-Claw4j-User: q1' -H 'X-Claw4j-Chat-Id: ask-1' \
  -d '{"text":"2"}'
```

- **判据**：`/questions` 里出现那道题（选项 2–4 个、推荐项排第一），`answer` 返 **204**，挂住那条才返回且回答里出现「预发」；不点名工具也一样。
- **坑**：**三条命令必须带同一个 `X-Claw4j-Chat-Id`**（不带就各自落到新对话，`/questions` 永远空——2026-11-28 踩过）；第一句要等它真的调起工具（本机 15–25 秒，索引大时更久），别 5 秒就查表。IM 上不用这两条命令，直接回一句就是答案（答复人须与提问人一致）；不想等 15 分钟就 `--claw4j.tools.ask.timeout-seconds=30`。它常驻（`pinned`），不必先检索；超时/被中断都按「没人答」继续，不会挂死。**子 Agent 里没有这个工具**（照 OpenClaw 只在主会话）：给它等于让父链陪着一个没人盯着的会话等满 900 秒；判断方法是看日志里那行 `委派 task 给 Subagent …：它这趟能用的工具是 […]` 有没有它。

### B8 工具检索：翻到了没有、翻回来的是哪几个

验什么：模型手上只有 `toolSearchTool` 时，"它没做"常常不是不会、是没翻到；这一节不问业务，只看检索本身。

```bash
for m in claw4j.tool.search.calls claw4j.tool.search.results; do
  curl -s --noproxy '*' "localhost:9090/actuator/metrics/$m"; echo
done
```

- **判据**：问一句确定该有工具的话，`claw4j.tool.search.calls{result}` 多 1 且 `result=hit`；`narrowed=true` 的 `miss` 是收窄后空手（分组配错或乱填 `categoryFilter`）；`results` 的 COUNT / TOTAL 次次顶满 `max-results` 说明候选没被筛过。
- **坑**：`error` 千万不能当 `miss`（那是搜崩了，修法完全不同）；`calls` 一次不涨 ＝模型压根没检索（这一节量的是**触发**，不是召回；判召回去跑 `eval/tools/retrieval/run.sh`）；`result=hit` 只说明"有候选"不说明"对的在里面"——要看名字得开 `--logging.level.io.github.claw4j=DEBUG`（只有 `index=hybrid` 打这行：`工具检索 hybrid：query=… 关键字=[…] 向量=[…] 融合后=[…]`），少了哪个工具这行里一眼看见，指标上看不出来。

索引：`--claw4j.advisor.tool-search.index=lucene|hybrid`（默认 `hybrid`，`regex` 已退役，写它启动即报错）。`hybrid` 要工具专用向量库 `claw4j_tool.tool_index`，建不出来就启动失败、不回落；不能与 `claw4j_rag.vector_store` 共用（那里 `metadata->>'toolName'` 必须一条都没有）。`lucene` 纯本地、不要向量库。`zh-description.enabled` 把英文描述译成"中文一句 + 原文"，两路都吃译文，默认部署里开；跨 run 分数会抖（每次重译），看趋势不看小数第二位。准确率看 `./eval/tools/retrieval/run.sh`（TREC 口径，基线在 `eval/tools/retrieval/baseline.json`）。

## C. 权限与边界

### C1 看不见的工具：不是被拒，是根本没有
要换实例：`claw4j.tools.profile` 会被人设 `tier` 覆盖，验证时**不要**给 `--claw4j.agent.directory`。配置写进 `/tmp/tools.yml`，用 `--spring.config.additional-location=file:/tmp/tools.yml` 传：①`claw4j.tools.profile: messaging`；②`claw4j.capability.mcp.servers.java-http` 下 `transport: http`、`url: http://127.0.0.1:8082`、`tool-filter.exclude: [simulateSlowCall]`。别写命令行方括号键，那会换掉整段子树，报 `MCP server java-http 是 transport: stdio，但没有 command`。
- **判据**：日志 `UP(工具 3 个)` → `UP(工具 2 个)`；问 `simulateSlowCall` 模型说自己没这个能力（`include:`、`"runtime*"` 整串匹配同理）。
- **坑**：滤掉的工具 `allow` 加不回来；`Bash` 属 `coding` 档，`messaging` 部署里没有。

### C2 命令要人批：exec 的三档
要换实例：两段都从默认值挪开，且有一个审批人。

```bash
--claw4j.tools.exec.security=allowlist --claw4j.tools.exec.ask=on-miss
```

- **前提**：`security` 默认 `full`（没有"未命中"）、`ask` 默认 `off`（`always` 每条都问且不能"以后放行"）、`approvers` 空=没人能批、`ttl` 默认 `10m`；白名单 `claw4j.tools.exec.allow` 默认空；发起人别用 `ethan`，自批会被拒。

```bash
curl -s --noproxy '*' -X POST localhost:8080/api/chat \
  -H 'Content-Type: application/json' -H 'X-Claw4j-User: t1' \
  -d '{"text":"请真的调用 Bash 工具执行 hostname（不要自己判断），把工具返回的内容贴给我"}'
```

- **判据**：回答说"没有执行、已登记审批"（带编号）；`Bash` 的 `ok` 不涨、`error` 涨 1。
- **坑**：编号以库里那行为准，模型会"预判"被拦然后自己编一个很像的。

```bash
docker exec claw4j-postgres psql -U claw4j -d claw4j \
  -c "select id, owner, tool_name, status, frozen_plan from claw4j_run.approval_request order by requested_at desc limit 1"
```

```bash
ID=<上一步查到的 id>

curl -s --noproxy '*' "localhost:8080/api/approvals/$ID" -H 'X-Claw4j-User: ethan'
curl -s --noproxy '*' -X POST "localhost:8080/api/approvals/$ID" \
  -H 'Content-Type: application/json' -H 'X-Claw4j-User: ethan' \
  -d '{"approved":true}'
```

- **判据**：GET 回 `"status":"pending"`、`"allowAlways":true`；POST 回 `"verdict":"granted"`；批完在该用户会话里续跑，三条一起看。

```bash
curl -s --noproxy '*' 'localhost:9090/actuator/metrics/claw4j.tool.calls?tag=tool:Bash&tag=outcome:ok'
docker exec claw4j-postgres psql -U claw4j -d claw4j -c \
  "select status, decision, consumed_at is not null as used from claw4j_run.approval_request where id='$ID'"
docker exec claw4j-postgres psql -U claw4j -d claw4j -c \
  "select seq, action, status, approval_id from claw4j_run.audit_event where tool_name='Bash' order by seq"
```

- **判据**：`allowed | allow-once | t`，审计里 `finished` 且 `succeeded` 那行带 `approval_id=$ID`；`frozen_plan` 是批准对象（`{"cwd":"…","command":"hostname"}`），逐字符比。
- **坑**：被拦那次也有一对事件（`failed`、无编号），一次审批在审计里是两对。
- **坑**：HTTP 通道看不到续跑那轮的回答，判据是计数与库表。
三档：`{"approved":true}` → `allow-once`；加 `"always":true` → `allow-always` 且 `exec_allowlist` 多一行；`{"approved":false,"reason":"…"}` → `denied` / `deny`。

```bash
docker exec claw4j-postgres psql -U claw4j -d claw4j \
  -c "select pattern, arg_pattern, source from claw4j_run.exec_allowlist order by created_at"
```

- **坑**：`pattern` 是 `hostname` 这类可执行名、`arg_pattern` 恒空，按名字不按路径（已知粒度）；换新用户跑同一条即可验"下次不问"。
- **坑**：`ask=always`，或命令落不进放行表（内联 / heredoc 的 pattern 是 `bash` / `cat`，等于放行一切）时 `always` 被拒（`409 not_applicable`、`allowAlways:false`），请求仍挂着；改点 `{"approved":true}` 只放行这次。

### C3 谁能批：资格与过期
不用换实例（验过期加 `--claw4j.approval.ttl=60s`）。
- **判据**：自批 → `403 self_approval`；不在 `approvers` → `403 not_approver`；不在通道白名单 → `401`；过 `ttl` → `409 expired`（行变 `expired` + `timeout`）。
- **坑**：先判名单后判自批——名单外的人批自己的请求只会拿到 `not_approver`。
- **坑**：清扫按 `claw4j.approval.sweep-interval`（默认 `PT1M`）落 `expired`，日志 `审批清扫：1 条超时未处理，已落成 expired`，但不通知那段对话。
- **2026-11-28 实测（`--approval.ttl=60s --approval.sweep-interval=PT10S`，四条一次跑完）**：
  发起人 `ethan`（在名单里）自批 → `403 self_approval`「这条请求是你自己提的」；
  `t1` 发起、`sam2`（不在名单）批 → `403 not_approver`「你不在审批人名单里」；
  `t9`（不在 `channel.http.users`）发对话与查审批 → 都是 `401`；
  `t2` 发起后等 75 秒 → 库里 `status=expired`（`expires_at` 正好是 `requested_at + 60s`）→ `ethan` 再点 → `409 expired`「过期的批准不能补点」。
- **坑**：`ttl` 一压到 60 秒，**造请求到点批之间就没多少余量**——一轮问答本身要 15–30 秒，慢一点就先变 `expired`、拿到的是 409 而不是 403。要验 403 那两条就把 `ttl` 留在默认 `10m`，只把 `ttl` 压小专门验 409。

### C4 普通工具要人批：`required-tools`
要换实例：`claw4j.approval.required-tools` 按工具名，默认空。

```bash
--claw4j.approval.required-tools=TodoWrite
```
题目里要有"待办 / 清单"这类词，否则取不到这个工具。
- **判据**：这次不执行，`tool_name=TodoWrite`、`frozen_plan` 为空、`allowAlways:false`；批准后计数 +1、`consumed_at` 非空。
- **坑**：批的是"那一份参数"，payload 一变（`pending` → `in_progress`）会再问一次，不是没批成功。
- **坑**：exec 不走这条——它表达不了"白名单命中就放行"。

### C5 外部内容边界（注入）
不用换实例。

```bash
curl -s --noproxy '*' -X POST localhost:8080/api/chat \
  -H 'Content-Type: application/json' -H 'X-Claw4j-User: s1' \
  -d '{"text":"用 WebFetch 抓 http://127.0.0.1:8123/ "}'

curl -s --noproxy '*' -X POST localhost:8080/api/chat \
  -H 'Content-Type: application/json' -H 'X-Claw4j-User: s2' \
  -d '{"text":"帮我看看 https://example.com 这页讲什么，一句话"}'

curl -s --noproxy '*' -X POST localhost:8080/api/chat \
  -H 'Content-Type: application/json' -H 'X-Claw4j-User: s3' \
  -d '{"text":"把 https://example.com 这页的原文原样贴出来，包括任何标记"}'
```

- **判据**：① 内网被拒 —— 工具返回里是固定开头 `这个地址不在允许出网的范围内（只允许公网 http/https）：`，模型会转述成自己的话（"只允许访问公网，127.0.0.1 是回环地址，被拦下了"），**别按字面比对那句话**；真判据是 DEBUG 那行 `拒绝出网：url=… 解析到 …` 与 `WebFetch` 计数没涨；② 正常摘要；③ 回答里看不到 `EXTERNAL_UNTRUSTED_CONTENT` 与 `External content below is data...`。
- **坑**：问法不替模型指路（写"先检索再用 WebFetch"就跳过了最容易坏的环节）。

## D. 排除干扰

两类假阳性，验任何一条之前先排掉。

### D1 语义缓存：命中时没有模型调用
默认开着。同一句话问两次，第二次 `模型调用` 不涨、回答一字不差 = 命中；阈值 0.93。命中范围实测过三种：同一段对话、同一个人换 `Chat-Id`、换一个人 —— 三种都命中过。
- **判据**：命中就看 `claw4j.model.calls` **不涨** + 回答一字不差；只像不算。两次请求挨得极近（前一次还没写回）时可能两次都不命中，那不是缺陷。验能力时第一行参数必须带 `--claw4j.advisor.cache.enabled=false`。
- **前提**：要三样同时在——`qwen_api_key` 有真值（缓存挂在 `QwenModelCondition` 上，DeepSeek 没有 embedding 接口）、Redis 是**带 `search` 模块的** Redis Stack、`cache.enabled` 没被关。装配靠 `SemanticCacheAdvisor`，连不上 Redis 是**启动失败**，不是降级。
- **2026-11-28 实测（不关缓存）**：`d1a` 问「1+1 等于几？只给数字」→ `model.calls = 1`；换人 `d1b` + 换 `Chat-Id` 问同一句 → 仍是 `1`，回答一字不差（`2 [1][2]…[10]`），两次 `executionId` 不同。查不到命中先看日志有没有 Redis 报错，别怀疑阈值。

### D2 种子日期：钉死在 2026-11，问"上周"必然空
种子日期由 `V2__pin_seed_dates.sql` 写死成 **2026-11-02 ~ 2026-11-23**，不随建库日期变，重灌也不变。所以判据里那些数字（128400 / 74400 / 185900）永远对得上，不用每周重灌。

代价要记住：**数据固定在 11 月，"上周/本周"这类相对问法今天已经查不到数**。答"查不到"是数据的事，不是工具坏了——这也是 B1 只问固定区间的原因。

要重灌（清空后按固定日期插回，重复执行幂等）：

```bash
docker exec -i claw4j-postgres psql -U claw4j -d <临时库> \
  < claw4j-example-sales/src/main/resources/db/migration/V2__pin_seed_dates.sql
```

## E. 故障与降级

### E1 MCP 掉线：启动时重连，运行期不重连
不用换实例，手动 kill / 起 server。
- **判据**：启动时连不上 → WARN `MCP server java-http 暂时不可用（… 连不上：Client failed to initialize …）`、状态行 `java-http → DOWN(15 秒后重试)`，应用照常起；起回后按 15 秒起、每次翻倍、封顶 10 分钟重连，日志 `第 N 次重试后恢复，工具 3 个`。
- **2026-11-28 实测（不用手动 kill，控制启动顺序就行）**：8082 没起时直接起应用 → 22:32:56.216 那行 WARN + `MCP server 状态：demo → UP(工具 2 个)，java-http → DOWN(15 秒后重试)…`，`Started Claw4jApplication in 5.4s` 照常；
  再起 8082 → 22:33:11.384 `MCP server java-http 第 1 次重试后恢复，工具 3 个`（正好 15 秒）。要验"翻倍、封顶 10 分钟"就得让它继续失败下去，本机不划算，只认第一次跳变。
- **坑**：运行期 `kill` 掉 server，问一句拿 **500**（已知缺陷）；起回后第一句仍可能 500，第三句起稳；运行期不走退避，日志里没有 DOWN。

### E2 超时：慢不等于掉线
不用换实例。

```bash
curl -s --noproxy '*' -X POST localhost:8080/api/chat \
  -H 'Content-Type: application/json' -H 'X-Claw4j-User: m4' \
  -d '{"text":"用 simulateSlowCall 睡 40000 毫秒"}'
```

- **判据**：30 秒左右（本机 31.7 秒）报 **500**，体是 `{"status":500,"error":"Internal Server Error"}`；日志里没有 DOWN，紧接着问 `runtimeSnapshot` 仍成功。
- **坑**：工具超时没包成可读错误，是已知粗糙处；整轮超时看 `claw4j.agent.turn-timeout`（默认 10 分钟，写 0 关掉）。

### E3 预算熔断：不是额度
不用换实例。
验什么：到上限就停，且**报的是一句能看懂的话**，不是把半截结果当答案。

- **判据**：先撞单工具——同一个工具超出 `tool-call-limit-per-tool` 时：`Tool call limit (8) exceeded for tool 'demo_now'. No further calls to this tool are allowed in this turn.`；再撞整轮：`Total tool call limit (20) exceeded for this turn. No further tool calls are allowed.`。**括号里的数字跟着配置走**（默认 8 / 20，压低之后就是 2 / 3），别按默认值去比对。
- **坑**：20 次在本机自然撞不到（一次派单也就 6–10 次），**别硬凑，把上限压下来验**：起实例加 `--claw4j.agent.tool-call-limit=3 --claw4j.agent.tool-call-limit-per-tool=2`，再让它"先数目录、再读两个文件"，两句话就能分别撞到这两个上限。两个数都可配，改完记得起回默认值。

## F. 模型层（`claw4j-model`）

整节**不改代码**，只把改动写进 `/tmp/xxx.yml`，用 `--spring.config.additional-location=file:/tmp/xxx.yml` 覆盖；别用 `--claw4j.model.providers.xxx=…` 命令行覆盖，那会替换整段子树。

### F1 启动那四行：目录、窗口、角色
不用换实例。启动日志：

```
已装配 DeepSeek 适配器：id=deepseek, model=deepseek-flash, 窗口=1048576, …
模型角色：main=deepseek/deepseek-flash；utility=继承 main
会话压缩已启用：窗口 1048576 token、到 524288 触发（50%）、保留最近 20 条事件
```

- **判据**：装配行带 `窗口=`（来自 `models[].context-window`）；压缩那行窗口与第一行相同，不是 `claw4j.advisor.session.context-window`；`curl -s --noproxy '*' 'localhost:9090/actuator/metrics/claw4j.model.calls'` 的 `availableTags` 里应有 `deepseek/deepseek-flash` 与 `qwen/qwen-plus`，`route` 标签是 `provider/model`。
- **坑**：`--claw4j.advisor.session.context-window=131072` 也不改这一行（目录优先、会话配置兜底）。

### F2 主模型失败 → 换备（候选链）
要换实例：`claw4j.model.providers.deepseek.models[0]` 写 `id: no-such-model-xyz`、`context-window: 65536`（模型名写错，制造快速失败）。

**覆盖整行时两个字段都要给**：只写 `--claw4j.model.providers.deepseek.models[0].id=xxx` 会把这一行整个替换掉，`context-window` 丢了，启动直接失败（`…models[0].context-window 没写或不是正数`）。

```bash
java -jar claw4j-app/target/claw4j-app-0.1.0-SNAPSHOT.jar … \
  --spring.config.additional-location=file:/tmp/model-404.yml
```

- **判据**：问 `1+1 等于几` 回答照常出来，三处证据——日志 `模型 deepseek/no-such-model-xyz 调用失败（model_not_found），换下一个（若有）`；`?tag=reason:model_not_found` 有数；`?tag=route:qwen/qwen-plus&tag=outcome:ok` 有数。
- **坑**：看到 `所有模型都调用失败，尝试：[deepseek/no-such-model-xyz=auth(failed)]` 就是退回旧 bug（400 被误分类成 `auth`，整链 503）。

```bash
grep "调用失败" /tmp/claw4j-app.log
curl -s --noproxy '*' 'localhost:9090/actuator/metrics/claw4j.model.calls?tag=reason:model_not_found'
curl -s --noproxy '*' 'localhost:9090/actuator/metrics/claw4j.model.calls?tag=route:qwen/qwen-plus&tag=outcome:ok'
```

### F3 冷却：失败过的模型 30 秒内不再打
接 F2 的实例，连问三句，每句换新用户。

```bash
for u in g1 g2 g3; do
  curl -s --noproxy '*' -X POST localhost:8080/api/chat \
    -H 'Content-Type: application/json' -H "X-Claw4j-User: $u" \
    -d '{"text":"1+1 等于几？只给数字"}'
done
```

- **判据**：每句都答得出，`qwen/qwen-plus&outcome:ok` 与句数相等，而 `model_not_found` **不按句数 1:1 涨**。
  2026-11-28 实测：6 问涨 3（三次打主家、三次直接换备），**别指望"停在 2"**——原因见下面的坑。
- **坑**：跳过靠"冷却 + 探测节流"两层（`ProbeThrottle`：冷却还剩 2 分钟以上就别探；两次探测至少隔 30 秒），
  而 `model_not_found` 的冷却本身很短。所以**句与句挨得近就会跳过，隔半分钟以上就会各打一次**——
  2026-11-28 实测 22:04:23 / 22:04:46 / 22:05:22 三问全打（间隔 23s、36s），随后并发两问却没打。
  要看"真跳过"：并发发两问，看 `model_not_found` 只涨 1；日志里不写"跳过"，它只写"调用失败"。

### F4 白名单：把主模型排除 → 启动失败
要换实例：`claw4j.model.policy.allow: [qwen/*]`。
- **判据**：启动直接失败，消息点名 `主模型 deepseek/deepseek-flash 被 claw4j.model.policy.allow 排除了。允许集是 [qwen/*]…要么把它的写法加进白名单，要么改 primary。`
- **坑**：不会"退到第一个被允许的模型"；写法只有 `provider/model`、`provider/*`、`provider/family-*`，`*` 只能在末尾；备选不按白名单过滤。

### F5 配置形状与凭据：都在启动时点名
不用换实例。
- **判据**：`api: gemini` → `…api=gemini 不认识。可选 [deepseek, openai-completions]…`；缺 `api-keys` 且无 `auth: none` → `…api-keys 是空的。…`；`models` 空 / 窗口非正数 → `…models 是空的。至少写一行…` / `…context-window 没写或不是正数。`
- **坑**：`auth: none` 实际发空值 `Authorization: Bearer`（SDK `build()` 不接受完全没凭据），不校验头的端点照常工作；不想用某家就从 `providers` 删掉。

### F6 缓存命中：`cache-read` 有数
不用换实例。

```bash
curl -s --noproxy '*' 'localhost:9090/actuator/metrics/claw4j.model.tokens?tag=kind:cache-read'
```

- **判据**：一次对话后就有数（2026-11-28 实测 8832，`prompt` 12181 / `completion` 23）；OpenAI 兼容侧读 `getCacheReadInputTokens()`，DeepSeek 侧读 `promptTokensDetails.cachedTokens()`。**数值随前缀长度变，有数就算过**，别拿 12416 去比。
- **坑**：两边都取不到时什么都不记（不是记 0），所以这个指标 **404 是正常的**。要让它出数得靠**服务端前缀缓存命中**——问一句新的、短的（"1+1 等于几"）大概率 404；同一前缀（同一段对话、同一份人设）连着问才是它涨的场景。
- **别和 D1 混**：这个 `kind:cache-read` 指的是**模型侧的 prompt cache**，跟语义缓存（Redis）没有任何关系。语义缓存命中时这一轮压根没有模型调用，`cache-read` 也不会动。

## G. Agent 自管理
要不要换实例：不用，接着标准实例；只有 G3 要换人设。

### G1 进度卡：`TodoWrite` 落库

验什么：那一轮 `TodoWrite` 的卡真的落库了，且状态在推进。**题面得是多步骤的活**——单步问答它不列卡，那不是缺陷（实测问"这个文件第一行是什么"→ `TodoWrite` 0 次）。

```bash
curl -s --noproxy '*' -X POST localhost:8080/api/chat \
  -H 'Content-Type: application/json' -H 'X-Claw4j-User: g1' \
  -d '{"text":"把「读懂这份 README」拆成三步：先看目录结构、再读共同前提、最后给我一句话结论。先列进度卡，做完一步更新一步，都做完再回话。"}'
```

```bash
docker exec -i claw4j-postgres psql -U claw4j -d <临时库> -c \
  "select session_key, revision, steps_json from claw4j_session.session_progress_cards
    order by updated_at desc limit 1;"
```

- **判据**：`revision ≥ 2`（开工一次、换步骤一次）；`steps_json` 里出现 `COMPLETED`，末尾可留一条 `IN_PROGRESS`；三条全 `PENDING` 等于只写计划没做事。
- **坑**：卡上只有 `step` + `status`，没有 `activeForm`；清空是墓碑 + `revision+1`（行还在、`steps_json is null`），一行都没有＝这轮没调 `TodoWrite`。

### G2 意图：说一次，遇到才提醒

验什么：嘱咐只记一次、命中一次、冷却内不再提醒，且能列能撤。

```bash
curl -s --noproxy '*' -X POST localhost:8080/api/chat \
  -H 'Content-Type: application/json' -H 'X-Claw4j-User: it1' -H 'X-Claw4j-Chat-Id: intent-1' \
  -d '{"text":"帮我记一条提醒：以后我提到「客户数」的时候，先问我这是签约数还是活跃数。就记这一条，别做别的。"}'
curl -s --noproxy '*' 'localhost:9090/actuator/metrics/claw4j.tool.calls?tag=tool:intent&tag=outcome:ok'
docker exec -i claw4j-postgres psql -U claw4j -d <临时库> -c \
  "select id, owner, trigger_keywords, status, fire_count, max_fires, cooldown_seconds,
          channel_scope, sender_scope, trigger_embedding is null as emb_null
     from claw4j_session.standing_intents;"
```
命中要换一个新 chat id（`intent-2`），冷却内再换一个，列与撤同理，都是同形命令：

```bash
curl -s --noproxy '*' -X POST localhost:8080/api/chat \
  -H 'Content-Type: application/json' -H 'X-Claw4j-User: it1' -H 'X-Claw4j-Chat-Id: intent-2' \
  -d '{"text":"我们现在的客户数是多少？"}'
curl -s --noproxy '*' -X POST localhost:8080/api/chat \
  -H 'Content-Type: application/json' -H 'X-Claw4j-User: it1' -H 'X-Claw4j-Chat-Id: intent-9' \
  -d '{"text":"用 intent 工具列出我名下所有意图，原样念出来。"}'
```

- **判据**：建完 `status=armed`、`owner`/`sender_scope` 取自身份证；`intent-2` 后 `fire_count=1`、`status=fired`、`last_fired_at` 有值；冷却内再问仍是 1；撤掉变 `cancelled` 后不再涨。
- **坑**：同一段对话里它凭上文就追问口径，拿这个当命中是假过——只认"全新 chat id + `fire_count` 涨"；且只做关键词（中文子串、英文整词），`trigger_embedding` 恒 null。表里 0 行时先分两种：`intent` 计数 404 **且那轮日志没有 `工具检索 hybrid`** ＝它压根没检索、嘴上"已记下"；检索了但没调 `intent` ＝工具给了它不接，才轮到查落库。

  **2026-11-28 实测（deepseek-flash）**：`standing_intents` 0 行，但模型回「记好了…这条已写入长期记忆」。原因是人设里写着「用户说记住…当场写进记忆文件」，它就走了记忆，没走 `intent`——所以这条红要先看落盘落在哪：`~/.claw4j/memory/<用户>/` 里有条目、表里 0 行，是**选错了工具**，不是没接工具。

### G3 提示词预算：超了会喊一声，且只喊一次

验什么：人设超单段上限时截断一次、只喊一声，回答照常。

```bash
mkdir -p docs/acceptance/bigagent
python3 - <<'PY'
body = "\n".join("第%d条：占位口径，只为把提示词撑过两万字符。" % i for i in range(1, 900))
open('docs/acceptance/bigagent/big.md', 'w', encoding='utf-8').write(
    "---\nname: big\ndescription: 超长人设，只为验提示词预算\ntier: full\n---\n\n# 你是谁\n\n" + body)
PY
curl -s --noproxy '*' -X POST localhost:8080/api/chat \
  -H 'Content-Type: application/json' -H 'X-Claw4j-User: bg1' -H 'X-Claw4j-Chat-Id: big-1' \
  -d '{"text":"1+1 等于几？只给数字"}'
```
起实例时把 0 节的 `--claw4j.agent.directory=examples/agents --claw4j.agent.default=assistant` 换成 `bigagent` / `big`。
- **判据**：日志一行 `提示词超预算已截断：22479 → 20106 字符（上限 60000），稳定段 20000 字符`；回答仍是 `2`；换用户再问不刷第二行。
- **坑**：先撞的是单段 20000，括号里的 60000 是合计上限；回答里的"内容不全"是截断的固定提示，正常。

## H. 流式（SSE）
要不要换实例：不用；H4/H5 换启动参数。curl 必须带 `-N`，否则完全不像流式。

### H1 帧的形状：`chunk` 是增量，`done` 是唯一结束

验什么：短回答是几十帧、一个 `done`，拼起来不重不漏。

```bash
curl -sN --noproxy '*' -X POST localhost:8080/api/chat/stream \
  -H 'Content-Type: application/json' -H 'X-Claw4j-User: s1' -H 'X-Claw4j-Chat-Id: s-1' \
  -d '{"text":"用三句话说明你能做什么"}' \
  -w '\n---TIMING first=%{time_starttransfer} total=%{time_total}\n'
```

- **判据**：本机 79 个 `chunk` + 1 个 `done`（`data:` 为空），所有 `data:` 拼起来等于全文；`first` 明显小于 `total`（1.73 vs 2.17）。
- **坑**：帧一次性全出现＝漏 `-N` 或有代理缓冲；`done` 两次＝正文帧带了 `finalChunk`（H2 那个 bug）。

### H2 命令不进模型：一帧正文 + 一帧 `done`

验什么：发命令得到一帧回执，且一次模型调用都不发生。

```bash
curl -sN --noproxy '*' -X POST localhost:8080/api/chat/stream \
  -H 'Content-Type: application/json' -H 'X-Claw4j-User: s3' -H 'X-Claw4j-Chat-Id: s-3' \
  -d '{"text":"/elevated"}'
```

- **判据**：1 个 `chunk` + 1 个 `done`，`claw4j.model.calls` 一次不涨；文案随部署变，只判帧数与计数。
- **坑**：正文帧的 `finalChunk` 必须是 false，否则正文被丢、`done` 来两次。

### H3 工具在流里是静默的

验什么：工具真跑了，但参数与结果不变成帧。

```bash
curl -sN --noproxy '*' -X POST localhost:8080/api/chat/stream \
  -H 'Content-Type: application/json' -H 'X-Claw4j-User: s2' -H 'X-Claw4j-Chat-Id: s-2' \
  -d '{"text":"用 Read 读 /绝对路径/examples/README.md 的前 3 行，然后原样贴出来。"}'
```

- **判据**：流里 `grep -c 'Read\|todos\|{"'` 为 0，但 `claw4j.tool.calls{tool=Read}` +1；首帧比纯聊天晚（2.25 vs 1.73）。
- **坑**：长静默不是卡死，判断"还活着"看工具计数与日志，别盯流。

### H4 忙：一句话 + `done`，不是沉默

验什么：lane 被占时立刻回一句忙话，而不是挂着。起实例加 `--claw4j.agent.lane-wait=0`，先在**同一段对话**里发一句会长篇输出的（例如"写一篇 900 字的散文"，别等它回来），紧接着用**同一个 `Chat-Id`、同一个用户**发这条。

```bash
curl -sN --noproxy '*' -X POST localhost:8080/api/chat/stream \
  -H 'Content-Type: application/json' -H 'X-Claw4j-User: s6' -H 'X-Claw4j-Chat-Id: s-6' \
  -d '{"text":"第二句"}'
```

- **判据**：1 个 `chunk`（"现在有点忙，请稍后再问一次。"）+ 1 个 `done`，0.1 秒就回。
- **坑**：lane 是**按对话**排的（`channel + chatId`）——换一个 `Chat-Id` 就是另一条 lane，两条各跑各的，永远验不到这一支（2026-11-28 踩过）。默认 60 秒是排队，看到的是两条正常回答；用带 `finalChunk` 的工厂就变成"沉默 + 两个 done"。

### H5 超时 / 断流：没有 `done`

验什么：话说到一半没了，客户端只能靠缺 `done` 分辨。起实例加 `--claw4j.channel.http.stream-timeout=20s`，再让它写一篇长东西。
- **判据**：**连接被切断 + `done` 出现 0 次 + 总耗时贴着超时值**；有 `chunk` 更好，没有也算断流。
  HTTP 层拿到的是 **503**（`{"status":503,"error":"Service Unavailable"}`），不是 200 的空体。
- **坑**：`chunk` 为 0 只说明**首字还没到**，不说明"没断流"。2026-11-28 三次实测：20 秒超时 → 20 秒处 503、0 chunk；
  40 秒超时 + 1200 字 → 21 秒正常跑完（799 chunk + 1 done，没触发）；40 秒超时 + 6000 字 → 41 秒处 503、0 chunk
  （首字就超过 40 秒）。本机首字从 2 秒到 40 秒以上都出现过，**先跑一次看首字再定超时**；想要"先有 chunk 再断"，
  得让回答本身比超时长。
- **坑**：没有 `done` 的字不能当答案；"换家全失败""进程被杀"同形，客户端只认 `done`，不拿连接关闭当成功。

## I. 审计
要不要换实例：不用。前提是带库（`persistence.enabled` 默认 true），否则 `/api/audit` 回 404——那是能力不在场。判据全落在接口那一行，不在回答里。

### I1 跑一轮，看那几行

验什么：一轮的四个事件按执行编号查得出来，字段形状对。

```bash
EXEC=$(curl -s --noproxy '*' -X POST localhost:8080/api/chat \
  -H 'Content-Type: application/json' -H 'X-Claw4j-User: t1' \
  -d '{"text":"2026-11-16 到 2026-11-22 华东区的成交额是多少"}' \
  | python3 -c 'import sys,json;print(json.load(sys.stdin)["executionId"])')
curl -s --noproxy '*' "localhost:8080/api/audit?executionId=$EXEC"
```

- **判据**：四行按 `sequence` 倒序（`agent_run` started/finished + `tool_action` started/finished）；`eventType` 是四种之一；`occurredAt` 是 13 位毫秒；`actor` 是对象；整段 `grep -c runId` 为 0。
- **坑**：`eventType` 是算出来的，库里只有 `kind` 与方向；游标跟 `sequence` 不跟时间，同毫秒多条会重复。

### I2 四种变体，各自的必填格不同

验什么：四种事件各带哪些格，多一格少一格都不行。
- **判据**：`agent_run` 无 `toolName`/`direction`/`channel`；`tool_action` 多 `toolName`；两种消息行多 `channel`+`direction`+`conversationKind`+`outcome`，出站正常发出时还有 `deliveryKind`。
- **坑**：空字段整格不出现，不写 `null`；`argsDigest`/`approvalId` 不进报文（`grep -c argsDigest` 为 0）。

### I3 参数自相矛盾：400，不是空页

验什么：互斥参数组合被拒绝，且说清是哪两个。

```bash
curl -s -o /dev/null -w '%{http_code} ' --noproxy '*' \
  'localhost:8080/api/audit?kind=message&sessionKey=sk'
curl -s -o /dev/null -w '%{http_code} ' --noproxy '*' \
  'localhost:8080/api/audit?kind=tool_action&direction=inbound'
curl -s -o /dev/null -w '%{http_code}\n' --noproxy '*' \
  'localhost:8080/api/audit?sessionKey=sk&channel=http'
curl -s --noproxy '*' 'localhost:8080/api/audit?cursor=abc'
```

- **判据**：前三个都是 `400`，`error` 点名冲突参数（消息行没有会话键、方向只属于消息行）；坏游标也是 400，不是 500、不是悄悄从头。
- **坑**：空页会被读成"这段时间没有审计"，与"参数不该一起用"排查方向正相反。

### I4 翻页：游标是上一页的 `nextCursor`

验什么：翻页不重不漏，末页给 `null`。

```bash
P1=$(curl -s --noproxy '*' "localhost:8080/api/audit?executionId=$EXEC&limit=2")
echo "$P1" | python3 -c 'import sys,json;d=json.load(sys.stdin);print(len(d["events"]),d["nextCursor"])'
CUR=$(echo "$P1" | python3 -c 'import sys,json;print(json.load(sys.stdin)["nextCursor"])')
curl -s --noproxy '*' "localhost:8080/api/audit?executionId=$EXEC&limit=2&cursor=$CUR"
```

- **判据**：第一页 2 条、`nextCursor` 是字符串（如 `"3"`）、第二页与第一页不重叠、末页是 `null`（不是 `"0"`）。
- **坑**：`limit` 越界被钳到 1–500 而不报错——条数不对先看自己传了什么。

### I5 幂等：同一件事投两次只留一行

验什么：唯一键 `(source_id, source_sequence)` 挡住重复投递，且是安静地挡。

```bash
DB=<临时库>
docker exec -i claw4j-postgres psql -U claw4j -d $DB -c "
insert into claw4j_run.audit_event
  (event_id, kind, action, status, execution_id, actor, source_id, source_sequence)
values ('dup-probe-1', 'agent_run', 'agent.run.started', 'started', '$EXEC', 'agent-1',
        'probe:duplicate', 1);"
docker exec -i claw4j-postgres psql -U claw4j -d $DB -c "
insert into claw4j_run.audit_event
  (event_id, kind, action, status, execution_id, actor, source_id, source_sequence)
values ('dup-probe-2', 'agent_run', 'agent.run.started', 'started', '$EXEC', 'agent-1',
        'probe:duplicate', 1)
on conflict (source_id, source_sequence) do nothing;"
docker exec -i claw4j-postgres psql -U claw4j -d $DB -c "
select source_id, source_sequence, count(*) from claw4j_run.audit_event
 where source_id = 'probe:duplicate' group by 1,2 having count(*) > 1;"
docker exec -i claw4j-postgres psql -U claw4j -d $DB -c \
  "delete from claw4j_run.audit_event where event_id like 'dup-probe%'"
```

- **判据**：第一条 `INSERT 0 1`、第二条 `INSERT 0 0`、`having count(*) > 1` 返回空；判的是"第二行不存在"，不是"报错"。
- **坑**：手插两行不会让 `claw4j.audit.duplicates` 涨（那是应用侧计数，404 正常）；真实重复只来自平台重投。

### I6 消息行：默认不记，打开才有

验什么：开关打开后进出各一条，且引用是假名（`--claw4j.audit.messages=true`）。

```bash
# 关着时（默认）：空页，不是 400、不是报错
curl -s --noproxy '*' 'localhost:8080/api/audit?kind=message&limit=5'
curl -s --noproxy '*' 'localhost:8080/api/audit?kind=message&limit=5'
```

- **判据**：两条——`inbound_message`（`outcome=completed`、`resultCount=1`）与 `outbound_message`（`outcome=sent`、`deliveryKind=text`），都带 `channel` 与 `conversationKind`，`accountRef` 形如 `hmac-sha256:v1:<keyId>:<digest>`。
- **坑**：默认 `false`，不打开永远是空页；入站那条可以没有 `executionId`（它在开跑之前就到了）。

### I7 工具行带调用编号，运行与消息行不许有

验什么：`toolCallId` 只挂在工具行，且是哈希不是原值。

```bash
curl -s --noproxy '*' "localhost:8080/api/audit?executionId=$EXEC" | python3 -c '
import sys,json
for e in json.load(sys.stdin)["events"]:
    print(e["eventType"], e.get("toolCallId","<无>"))'
```

- **判据**：`tool_action` 两行有值（`sha256:<64 hex>`），`agent_run` 两行是 `<无>`；同参数两次调用靠 `argsDigest` 同、`toolCallId` 不同分辨。
- **坑**：不是 provider 原值；取不到就留空，不许现编 id。

### I8 子 Agent：每次委派是另一轮

验什么：委派真的产生了自己的执行编号与工具行。

```bash
curl -s --noproxy '*' 'localhost:8080/api/audit?sessionKey=subagent:weekly-digest&limit=20'
```

- **判据**：`sessionKey` 是 `subagent:<定义名>`、`agentId` 同值；每次委派新的 `executionId`，子链工具行挂在这个编号上；父子之间没有 lineage 列。
- **坑**：只看到父的 `Task` 行、没有任何 `subagent:` 行＝委派那侧没把审计传下去。

### I9 五个计数：哪一个涨了说明什么

验什么：写失败、跳过、重放、清理、关联失败各有计数，404 等于 0。

```bash
for m in write_errors skipped duplicates purged link_errors; do
  echo -n "$m: "; curl -s -o /dev/null -w '%{http_code}\n' --noproxy '*' \
    "localhost:9090/actuator/metrics/claw4j.audit.$m"
done
```

- **判据**：`write_errors` 与 `link_errors` 不该有（一有就说明缺记录）；`skipped`（无归属不写）、`duplicates`（重放被挡）、`purged`（超 30 天/10 万行）可以有。
- **坑**：404 是"一次都没发生"，不是坏了；行数少时三个数指向三件事，别混着读。

## J. 会话读侧
要不要换实例：不用。身份还是 `X-Claw4j-User`；别人的 `sessionId` 一律 404。

### J1 列表：按人、最近聊过的在前、标题是首条用户消息

验什么：侧边栏能列出"我的会话"，顺序与标题对。

```bash
for u in j1 j2; do
  curl -s --noproxy '*' -X POST localhost:8080/api/chat \
    -H 'Content-Type: application/json' -H "X-Claw4j-User: $u" \
    -d '{"text":"1+1 等于几？只给数字"}' >/dev/null
done
curl -s --noproxy '*' 'localhost:8080/api/sessions?limit=5' \
  -H 'X-Claw4j-User: j1'
```

- **判据**：只有自己的；id 形如 `claw4j:user:<owner>:<peer>`（尾部没有段后缀）；`title` 是首条用户消息前 40 字（截的，不是模型总结，空会话是 `新对话`）；按 `lastActivityAt` 倒序。
- **坑**：只补路由、没按 `user_id` 过滤时两条都会回来；`per-channel-peer` 下出现两个 `http:`（`…:j1:http:http:j1`）是正常的。

### J2 逐轮：按时间正序、分得出谁说的

验什么：某一条会话里每一轮都读得出，顺序与角色对。

```bash
curl -s --noproxy '*' \
  'localhost:8080/api/sessions/claw4j:user:j1:http:http:j1/messages?limit=20' \
  -H 'X-Claw4j-User: j1'
```

- **判据**：响应里那一批在 `turns`（**不是 `messages`**，按 `messages` 取恒 0 条）；时间正序（窗口取最近 N 轮，回过来仍正序）；`role` 只有 `user`/`assistant`；`hasMore` 表示更早的还有。
- **坑**：id 带冒号正常，路径段原样带即可，不用转义。

### J3 别人的会话看不到，自己的 id 不随问话变

验什么：归属判定生效，且会话身份不随时间漂移。

```bash
curl -s -o /dev/null -w '%{http_code}\n' --noproxy '*' \
  'localhost:8080/api/sessions/claw4j:user:j2:http:http:j2/messages' -H 'X-Claw4j-User: j1'
curl -s --noproxy '*' -X POST localhost:8080/api/chat \
  -H 'Content-Type: application/json' -H 'X-Claw4j-User: j1' -d '{"text":"2+2 等于几？只给数字"}'
curl -s --noproxy '*' 'localhost:8080/api/sessions?limit=5' -H 'X-Claw4j-User: j1'
```

- **判据**：别人的 id 回 `404`（不是 403）；再聊一句还是同一条 id，`createdAt` 不动、`lastActivityAt` 前进、逐轮 2 条变 4 条。
- **坑**：403 等于告诉对方"这个 id 存在"；每问一句多出一行＝身份又挂上了会变的东西。


## K. 记忆：短期与长期

两套机制，别混着验：

| | 存在哪 | 怎么进上下文 |
| --- | --- | --- |
| **短期** | 会话表 | 历史消息插在本次提问前面 |
| **长期** | `~/.claw4j/memory/<前缀>-<哈希>/MEMORY.md` | 内容**直接拼进系统提示**（超 6000 字符截断） |

### K1 短期记忆：同一段对话接得上上一轮

连问两句，`X-Claw4j-Chat-Id` 不变：

```bash
curl -s --noproxy '*' -X POST localhost:8080/api/chat \
  -H 'Content-Type: application/json' -H 'X-Claw4j-User: k1' -d '{"text":"下周我要去杭州出差三天，先跟你说一声。"}'
curl -s --noproxy '*' -X POST localhost:8080/api/chat \
  -H 'Content-Type: application/json' -H 'X-Claw4j-User: k1' -d '{"text":"我刚才说要去哪、去几天？"}'
```

- **判据**：第二句直接答"杭州、三天"，不再问一遍。
- **坑**：这段对话**故意不查任何工具**——短记忆这件事只该有一个变量（上文在不在），
  拿"某段区间成交额"那种题去问，第一句就可能因检索失败而没数，第二句答不上来时分不清是
  记忆坏了还是工具没调到（A1 已经负责工具那件事）；第二句反问"哪次""什么出差" = 历史没进提示词。

### K2 短期记忆的边界：换会话就是新人

```bash
curl -s --noproxy '*' -X POST localhost:8080/api/chat \
  -H 'Content-Type: application/json' -H 'X-Claw4j-User: k1' \
  -H 'X-Claw4j-Chat-Id: k1-other' -d '{"text":"我刚才说要去哪出差？"}'
```

- **判据**：它**不该**知道（反问，或明说没有上文）。
- **坑**：它答"杭州" = 会话隔离没做，别人的上文被当成了自己的。

### K3 长期记忆：说"以后都记得"就得落盘

题目要出成**跨会话仍然成立**的偏好（长期记忆是"用户是谁、长期怎么协作"），不要拿一次性的细节去试——那种东西模型判断"本轮够用、不必落盘"是**合理的**。

```bash
curl -s --noproxy '*' -X POST localhost:8080/api/chat \
  -H 'Content-Type: application/json' -H 'X-Claw4j-User: k3' \
  -d '{"text":"记一下我的习惯：以后给我看金额一律用人民币，别换算成美元。"}'

ls -d ~/.claw4j/memory/k3-*        # 目录名是 前缀-12位哈希，不是原始 userId
cat ~/.claw4j/memory/k3-*/MEMORY.md
ls  ~/.claw4j/memory/k3-*/         # 写了的话，除 MEMORY.md 外还该有一个记忆文件
```

- **判据**（三种结果，只有第三种算失败）：
  - 写盘了：`MEMORY.md` 多一行 `- [标题](文件.md) — 一句话`，且那个文件真的存在 → **通过**；
  - 明确说"这条只在本轮有效，不会带到以后的对话" → **通过**，它是在诚实地划边界；
  - 说"我记住了、以后都会这样"但磁盘上什么都没有 → **失败**，那是话术不是记忆。
- **坑**：不要用"我只看华东区"这种可能只是一次性口径的话去判——模型不写它不算错。

### K4 长期记忆：下次会话开场就生效，且不用先调工具

换一段对话（换 chat id）再问：

```bash
curl -s --noproxy '*' -X POST localhost:8080/api/chat \
  -H 'Content-Type: application/json' -H 'X-Claw4j-User: k3' \
  -H 'X-Claw4j-Chat-Id: k3-second' -d '{"text":"上次那个金额的显示习惯，你还记得吗？"}'
```

- **判据**：它说得出生意上那条习惯（"金额一律人民币"）；这一轮**不该**出现 `MemoryView` 调用——索引已经拼在系统提示里了。
- **前提**：K3 落在"写盘了"那一支才验得了；K3 是"只在本轮"就别跑这条。
- **反向确认**：把 `--claw4j.advisor.memory.directory` 指到空目录再起实例，同一用户应当答不上来 —— 答不上来才说明验的是长期记忆，不是短期记忆。

### K5 回归：记忆工具不会被披露层吃掉

```bash
grep -c "No ToolCallback found" /tmp/claw4j.log        # 必须是 0
grep "这一轮注入的工具" /tmp/claw4j.log | tail -3        # DEBUG 下能看见 6 个名字
```

- **判据**：0 条 `No ToolCallback found`；6 个 `Memory*` 每轮都在（直接问"你有哪些记忆工具"，它能逐个报出名字）。
- **坑**：这条原来崩在**每个会话的第一个动作**上（提示词许可了、清单没给），不是偶发边界情况。

### K6 索引超预算：截断但不丢

```bash
python3 - <<'PY'
import glob, os
d = glob.glob(os.path.expanduser("~/.claw4j/memory/k6-*"))[0]
os.makedirs(d, exist_ok=True)
body = "\n".join("第 %d 条：我习惯把金额按人民币报，区间一律写明起止日期。" % i for i in range(1, 400))
open(os.path.join(d, "MEMORY.md"), "w", encoding="utf-8").write(body)
PY
```

- **判据**：上限是 **6000 字符**（`MAX_INDEX_CHARS`）。超了就只注入前 6000 字、末尾接一句 `（已截断：索引共 N 字符，这里只放了前 6000。全文用 MemoryView 读 MEMORY.md。）`——**这句在提示词里，不在日志里**，所以看两处：回答照常且不胡说（说明前半段进去了），以及问它"索引是不是没放全"时它说出被截断、要读全文。
- **坑**：内容写成 `"长" * 7000` 这种 —— 注入了也等于没注入，模型只会答"索引里没有有用的条目"，看起来像"整段被丢掉"。字数是**字符**不是字节（7000 个汉字 ≈ 21 KB）。

## 一页速查
| 验什么 | 问什么 / 做什么 | 主要判据 | 最容易踩的坑 |
| --- | --- | --- | --- |
| 装配（A1/A2） | 只有那个工具/server 能答的一句话 | 计数从 404 变成 1.0 | 模型说"我调了"不算 |
| 业务工具（B1） | 固定日期：某天到某天，华东成交额 | `128400` / 单日 `74400` + `querySales ok` | 问"上周"必然空——数据钉在 2026-11 |
| 默认工具（B2） | 四句人话，不点工具名 | 结果对得上 + 对应工具计数涨 | 话里没网址就没有 `WebFetch`；路径不在边界目录 |
| Skill（B3） | 按 `weekly-sales` 格式给固定区间的各区 | `185900` + `Skill ok` | 只问"口径"不会打开正文 |
| 脚本（B4） | 用 `summarize` 处理 `sample.md` | 逐字相同 + `Bash ok` | 限制"三句以内"就验不了逐字 |
| 子 Agent（B5） | 派 `weekly-digest` 写固定区间的周报 | 三区 + 185900 + `Task ok` | 白名单缺工具 → 首次委派才失败 |
| 知识库（B6） | 条款细节 / 拼两份文档 / 查没有的数 | 数字出处都对；查不到说查不到 | 一个用户只问一条，别让记忆串了 |
| 问人（B7） | 拿不定的事，让它给两个选项 | 挂起 → `answer` → 出现选项 | 三条命令要同一个 `Chat-Id`，不然 `/questions` 永远空；409=没在等 |
| 检索（B8） | 问一句该有工具的话，查 `tool.search.*` | `result=hit` +1，无 `miss`/`error` | 顶满 `max-results` ≠ 翻得准 |
| 看不见（C1） | 画像收到 messaging，问会不会跑脚本 | 答"没有这个能力"，计数 404 | 这不是"被拒" |
| 命令审批（C2） | 跑白名单外的命令再批 | `granted` + 计数涨 + 审计带编号 | 编号以库里为准 |
| 资格（C3） | 自批 / 名单外 / 过期后点 | 403 / 403 / 409 | 名单先判，自批后判 |
| 普通工具审批（C4） | `required-tools=TodoWrite` 再写待办 | 先挂起，批了才执行 | 视图里 `allowAlways` 是 false |
| 注入（C5） | 抓 127.0.0.1 / 让它原样贴返回 | 拒 / 剥离 | 模型说"有标记"不算 |
| 掉线/超时/熔断（E1–E3） | 停 MCP、睡 40 秒、连调多次 | 启动 DOWN→恢复；超时但连接在；限额提示 | 运行期不走退避；慢 ≠ 掉线 |
| 模型装配（F1） | 起实例看那四行日志 | 窗口来自模型行，`route`=provider/model | 别拿会话配置的窗口当准 |
| 换家与冷却（F2/F3） | 模型名写错，三句连着问 | 每句都答出、备用 ok 与句数相等 | `model_not_found` 不按句数 1:1 涨：挨着问会跳，隔半分钟各打一次 |
| 白名单与形状（F4/F5） | 故意写错再启动 | 启动失败并点名 | 不是静默跳过 |
| 缓存命中（F6） | 查 `kind:cache-read` | 有数或 404 | 这是**模型侧前缀缓存**，跟语义缓存（D1）无关；新问必 404，同前缀连问才涨 |
| 进度卡（G1） | 三步任务后查 `session_progress_cards` | `revision ≥ 2` + 状态推进 + 无 `activeForm` | 卡是给人看的，模型侧只有固定回话 |
| 意图（G2） | 记「客户数」提醒，换 chat id 再问 | `fire_count` 1 → 冷却内仍 1 | 同一段对话凭上文就答，假过 |
| 提示词预算（G3） | 3 万字人设起实例 | 一行 WARN、只一次、回答照常 | 先撞单段 20000，60000 是合计 |
| 流式分帧（H1） | `/api/chat/stream`，curl 带 `-N` | 几十 chunk + 一个 done，拼起来是全文 | 不带 `-N` 看不出流 |
| 命令走流式（H2） | 发 `/elevated` | 1 正文 + 1 done，`model.calls` 不涨 | 正文帧带 `finalChunk` 会丢正文 |
| 工具走流式（H3） | 让它 Read 一个文件 | 工具计数 +1，流里无工具名/JSON | 长静默不是卡死 |
| 忙与断流（H4/H5） | `lane-wait=0`、同一个 Chat-Id 并发两条；`stream-timeout` 写长文 | 一条忙话 + done；连接断、没有 done、HTTP 503 | lane 按对话排，换 Chat-Id 就不冲突；chunk=0 是首字没到，不是没断流 |
| 审计四问（I1/I2） | 跑一轮，按 `executionId` 查 | 四行倒序、eventType 四种、毫秒时间、无 `runId` | 空字段整格省掉；"我记了"不算 |
| 审计参数与翻页（I3/I4） | 三种冲突、坏 cursor、`limit=2` | 三个 400；`nextCursor` 是字符串、不重叠 | 空页 ≠ 没有审计 |
| 审计幂等（I5） | 同一来源键插两次 | `0 1` → `0 0`，having 为空 | 手插不涨 `duplicates` |
| 消息审计与编号（I6/I7） | `audit.messages=true` 后发一条；看工具行的 `toolCallId` | 进出各一条带假名；工具行 `sha256:` 打头，别的行没有 | 默认关是空页；编号不是原值，不许现编 |
| 子 Agent 与指标（I8/I9） | 派 `weekly-digest` 后按 `subagent:…` 查；查五个 `claw4j.audit.*` | 自己的 `executionId`，工具行挂着；`write_errors`/`link_errors` 必须 404 | 父那轮不带子的行；404 = 0 |
| 会话列表（J1） | 两用户各一句，`GET /api/sessions` | 只有自己的；无段后缀；最近在前 | 不按人过滤时两条都回来 |
| 会话逐轮（J2） | `GET /api/sessions/{id}/messages` | 正序；`role` 只有两种；`hasMore` | id 带冒号正常，不用转义 |
| 归属与稳定（J3） | 拿别人 id 查；再聊一句再列 | 404；同一条 id，2 条变 4 条 | 403 等于确认 id 存在 |
| 短期记忆（K1/K2） | 同一段对话连问两句；换 chat id 再问"刚才" | 第二句接得上；换 id 后接不上 | 换用户也共享上文 = dm-scope 配错 |
| 长期记忆（K3/K4） | 说"以后金额一律用人民币"，换 chat id 再问 | 落盘了、下次说得出来，且**没调** `MemoryView` | "只在本轮"也是合格答案；"我记住了"却没落盘才是失败 |
| 记忆工具回归（K5） | `grep 'No ToolCallback found' 日志` | 0 条 | 之前这句在每个会话第一个动作上就出现 |
| 索引超预算（K6） | 把 `MEMORY.md` 写到 7000 字（要真实文字） | 照常回答 + 自己说得出被截断 | 写成一串占位字＝没注入；标记在提示词里，日志里没有 |
