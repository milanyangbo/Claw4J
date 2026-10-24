# Claw4J

企业 IM 上的业务数据助手。主场景是 **Text-to-SQL 销售域问答**：业务同学在群里问"上周华东区成交额多少"，Agent 自己查库、自己答。

整个项目是 **基于 Spring AI 2.x 从零重建** 的（1.x 版本已冻结进 `legacy/`，见 [legacy/README.md](legacy/README.md)）。重建的全部设计决策都留了 ADR，见 [`docs/adr/`](docs/adr/)。

> **当前进度：M2 已完成。** M0 骨架：HTTP+SSE 通道端到端打通、模型按 key 有无自动装配、架构边界由机器校验。M1：业务库与种子、工具调用闭环、鉴权与脱敏 Advisor、Text-to-SQL 闭环、会话记忆（落 Postgres）、**真实模型联调**。M2：能力三层（MCP 跨进程、Script Package 沙箱执行、Agent Skills 渐进式披露）、Subagent 编排、Skill Generator 五步链路、真实外部 CLI 走 MCP。**CI 暂未接入**，门禁全在本地（见「质量门禁」）。

## 技术栈

| | 版本 | 说明 |
| --- | --- | --- |
| JDK | 21 | Spring Boot 4 的底线 |
| Spring Boot | 4.1.2 | |
| Spring AI | 2.0.2 | Advisor 链是 2.x 的统一抽象，鉴权/脱敏/记忆都挂在上面 |
| Maven | 3.9+ | 仓库自带 wrapper，用 `./mvnw` 不用自己装 |
| Postgres + pgvector | 16 | 业务数据、会话、向量共用一个库（ADR-0005） |
| Redis | 7 | 只做语义缓存 |

这四个版本是架构决策而不是随手选的，改它们要在 ADR 里留痕（ADR-0001）。

## 模块结构

```
      channel ──► domain ◄── agent
                     ▲          ▲
                     │          │
                     └── tools ─┘
                     ▲
                     │
                    app（唯一知道所有模块的地方，负责接线）
```

| 模块 | 职责 |
| --- | --- |
| `claw4j-domain` | 领域模型与端口。**零框架依赖**，是唯一被承诺能脱离框架复用的模块 |
| `claw4j-agent` | ChatClient 装配、模型选择、Advisor 链。不认识任何通道 |
| `claw4j-channel` | 入站出站适配：HTTP+SSE（M0）、飞书长连接（M2）、A2A（M3）。按平台分包，不按平台分模块 |
| `claw4j-tools` | 三层能力里的工具层 |
| `claw4j-app` | 启动与装配，唯一打 fat jar 的模块 |
| `legacy/` | 1.x 冻结代码，**不参与构建** |

两条最关键的方向：`channel → domain ← agent`，两边互不依赖；接线只发生在 `app`。加一个通道不需要碰 agent，换一个 Agent 实现不需要碰 channel（ADR-0010）。

## 本地怎么起

### 最省事：什么都不装

```bash
./mvnw verify                        # 一条命令：构建 + 架构检查 + 391 个测试（连真实 DeepSeek）
./mvnw -pl claw4j-app spring-boot:run
```

要先有 `DEEPSEEK_API_KEY`：没有 key 应用**起不来**（ADR-0011 起不再有 mock 兜底——
留着兜底就会出现"本地跑着回声模型却以为在跑真模型"这种更贵的困惑）。
数据源是懒连接，不会在启动时真连（ADR-0006）。

**没有 Docker 时数据库那一类会整类跳过**，而最硬的几条判据（只读约束、会话记忆继承、启动迁移）
恰好都在里面。它们跳过时构建照样绿，只在终端里打一行 `本次跳过`。要确认自己跑的是完整版：

```bash
./mvnw verify -Dclaw4j.dbTests=required   # 没有 Docker 就直接红，不给假绿
./scripts/assert-no-skipped-tests.sh      # 跑完 verify 再跑：有跳过就红，并报出真实测试数
```

反过来，**有 Docker 但拉不动 Docker Hub** 时，测试会真的去 pull 然后红——这是有意的：
拉不动是环境问题，该红；静默跳过才是要防的事。国内换镜像源：

```bash
CLAW4J_POSTGRES_IMAGE=docker.m.daocloud.io/pgvector/pgvector:pg16 ./mvnw verify
```

### 要数据库：让 Boot 自己拉容器

```bash
cp claw4j-app/src/main/resources/application-local.yml.example \
   claw4j-app/src/main/resources/application-local.yml
./mvnw -pl claw4j-app spring-boot:run -Dspring-boot.run.profiles=local
```

`local` profile 会用 `compose.yaml` 拉起 Postgres(pgvector) 与 Redis，退出时回收。国内拉不到官方镜像时用环境变量换镜像源：

```bash
CLAW4J_POSTGRES_IMAGE=docker.m.daocloud.io/pgvector/pgvector:pg16 \
  ./mvnw -pl claw4j-app spring-boot:run -Dspring-boot.run.profiles=local
```

`application-local.yml` 被 `.gitignore` 忽略（它可能放真实 key），仓库里只有 `.example` 模板。

### 两套库：常驻的给你看，一次性的给测试跑

| | 谁在用 | 怎么起 | 数据 |
| --- | --- | --- | --- |
| compose 常驻 | 本地开发、真模型联调 | `docker compose up -d`，或 `local` profile 让 Boot 拉 | 会攒数据，改 schema 靠 Flyway 叠加版本 |
| Testcontainers | `./mvnw verify` 里的数据库测试 | 每个测试类起一个，跑完销毁 | 每次都是空的，只有种子 |

不给测试复用常驻库，是因为常驻库会串数据：一个测试写进去的东西让下一个测试变红或变绿，
排查时看到的是"刚才明明能跑"。一次性实例慢一些，但每次都是确定的。

业务 schema 与种子在 `claw4j-tools/src/main/resources/db/migration/`（`V1` 建表、`V2` 放种子、`V3` 会话表），
由 Flyway 执行。**改字段是加一个 `V4__xxx.sql`，不要回头改已提交的版本** —— 本地常驻库已经记过账了，
改历史版本会让它的校验和不匹配，而删库重建正是这套迁移要替你免掉的事。

"应用起来就迁到最新版"这件事由测试盯着（`SchemaMigrationOnStartupTest`）。它值得单独立一条，
是因为 Boot 4 把 Flyway 的自动配置拆成了独立 artifact：只有 `flyway-core` 在 classpath 上时，
`spring.flyway.enabled=true` 是一句**没有任何东西在执行**的配置——不报错、不警告、日志里不出现
Flyway 三个字，只是表一直不建，而业务侧报的是"连不上库"，看的人会去查网络（07 号票第一次
真实联调就是这么挂的）。所以要显式引 `spring-boot-flyway`，它跟着迁移脚本一起放在 tools 模块。

种子里的日期是相对的（按"本周一"推算），所以**每次重建的库**里，"上周"永远落在同一批订单上。
注意这个"相对"只在迁移那一刻求值：常驻的本地库建好之后，它里面的"上周"是**建库那天**的上周，
放久了照样会漂。演示时发现数字对不上，重建一次库就好——这也是测试不肯复用常驻库的一个理由。

| 问法 | 答案 |
| --- | --- |
| 上周华东区成交额 | 128,400.00 |
| 上周华南区成交额 | 26,000.00 |
| 本周华东区成交额 | 99,999.00 |

**成交额只算未取消的订单**：上周华东还有一单 50,000 是取消状态，它不该出现在任何答案里。
这三个数就是 05 号票"问一句话拿到数"的判据——两处必须是同一个数，否则会出现
"库里算的是 A、Agent 答的是 B"这种谁都没错的假绿。

数据库测试需要本机有 Postgres 镜像。**本机跑全量验证要显式给镜像变量**——
Docker Hub 在这台机器上连不到，用的是镜像站：

```bash
CLAW4J_POSTGRES_IMAGE=docker.m.daocloud.io/pgvector/pgvector:pg16 ./mvnw verify
```

不设的话这一类测试会整类跳过，并往 stderr 打一行提示。本机宽容是因为不该逼人为了提交去拉镜像，
但**"跳过的绿"和"通过的绿"长得一样**，所以报数时要跑
`./mvnw verify -Dclaw4j.dbTests=required`——跑不起来就直接红，而不是算绿。
（`CLAW4J_POSTGRES_IMAGE` 与 compose 用的是同一个变量，写进根目录 `.env` 也能生效。）

### 要真实模型

配好 key 起应用即可，见下一节。回归基线**本身**就是真模型（ADR-0011），所以没有单独的
"真实模型那一趟"要开：`./mvnw verify` 打的就是真实 DeepSeek。

```bash
set -a && . ./.env && set +a   # 把 key 导进当前 shell（Maven 不读 .env）
./mvnw verify
CLAW4J_MODEL_PRIMARY=qwen ./mvnw verify   # 换另一家当主模型（只对 app 那几条生效，见下）
```

app 模块那几条（含 `RealModelTest`）需要真库：本机由 `spring-boot-docker-compose` 按
`compose.yaml` 自动起，不用手动 `docker compose up -d`。其余模块用 Testcontainers。

**`CLAW4J_MODEL_PRIMARY` 只对 app 那几条生效**：agent 侧那批测试装配的是固定的 DeepSeek
（`RealModelSupport`），刻意不跟着这个开关走——它们要的是"同一家、温度 0"的确定性，
换厂商该由 `RealModelTest`（走完整 `application.yml`）来验，而不是让每条端到端都变成多厂商矩阵。

回归基线是那 391 个测试，它们**全都在连真实 DeepSeek**（ADR-0011）。这是有代价的：
烧 token、变慢、且模型有随机性——所以温度一律设 0，断言只写方向性（拿到数 / 拿不到数），
关键断言给两次重试。代价换来的东西也实在：只在真链路上才暴露的缺陷能被测到，
M1 那两个（降级丢工具、内部重试把降级拖成分钟级）就是这样抓出来的。

`RealModelIT` 曾经隔离在 `-Preal-model` 里（那时回归基线是假模型），ADR-0011 之后
隔离的理由消失，已改名 `RealModelTest` 并进常规回归——留着它就等于留一条
"日常跑不到、想起来才跑"的裂缝。它多覆盖的是**完整装配**这一层（app 的 yml、
真 Flyway 迁移、fat-jar 级接线），与 agent 侧模块级那批断的东西不同。

## key 怎么配

凭据一律走环境变量，不写进仓库（ADR-0002、ADR-0006）。仓库根目录放一个 `.env`（已被忽略）：

```bash
DEEPSEEK_API_KEY=sk-xxxxxxxx
# QWEN_API_KEY=sk-xxxxxxxx   # 备用模型
```

`application-local.yml` 里有一行 `spring.config.import: optional:file:.env[.properties]` —— Spring Boot 默认**不**读 `.env`（那是 docker compose 的约定），这行把它接进来。也可以直接 `export DEEPSEEK_API_KEY=...`。

没有配置任何 key 时**启动失败**，不降级（ADR-0011）。失败消息会点名 `DEEPSEEK_API_KEY` /
`QWEN_API_KEY`，免得拿着空端点去发第一个请求才炸。

测试连的是真实模型，key 从环境里取（本机 `set -a; . ./.env; set +a`；将来接 CI 时要配
`secrets.DEEPSEEK_API_KEY`）。**没有 key 时测试是红的，不是跳过**——被跳过的测试会被
`assert-no-skipped-tests.sh` 放过，那等于没验。

### 两家模型怎么切

`claw4j.model.primary` 决定主模型（`deepseek` / `qwen`），另一家是备。装配出来的
`RoutingChatModel` 才是业务看到的那个模型（`@Primary`），启动日志会写明主备顺序：

```
模型路由：主=deepseek，备=[qwen]
```

降级规则：

| 路径 | 行为 |
| --- | --- |
| 同步 | 主模型调用失败就换备；两家都挂了抛异常，每一次失败的原因都挂在 `getSuppressed()` 上 |
| 流式 | 只在**第一个字吐出去之前**降级 —— 吐过之后再换会把同一条回答发两遍 |

两家都配了 key 才有备可用；只配一家也能跑，只是没有备。主模型那家没配 key 会**直接启动失败**，
不会悄悄拿备用的顶上（ADR-0006）。

### 降级这条路径上踩到的两个坑

它们都是真实联调才暴露的——**在任何一张假模型测试里都不会红**，因为假模型既不会真的失败、
也不会真的需要工具。

**一、换供应商时工具会丢。** 各家的 `ChatOptions` 互不兼容，降级时必须把参数翻译成备模型那一型
（否则强转当场 `ClassCastException`）；而翻译时只搬温度、topP 这类标量是不够的——
工具回调不在标量里。漏掉它的表现不是报错，是**备模型看不到 `querySales`**：
真实链路上的回答变成"我无法直接访问数据库，请确认口径"。没崩、没超时，只是再也查不到数，
主备降级因此只剩"还能说话"而不再是"还能干活"。工具回调与工具上下文现在跟着一起搬，
`RoutingChatModelTest` 里钉着。

**二、模型内部的重试会把降级拖成分钟级。** Spring AI 默认给 `DeepSeekChatModel` 套的重试模板是
10 次、2 秒起、×5 递增、上限 180 秒；上层还有路由在"换一家"，两层相乘的结果是
真实联调时**等了 4 分 40 秒还没轮到备**，用户看到的是一直转圈。现在模型内部只留短重试
（`RetryUtils.SHORT_RETRY_TEMPLATE`：10 次 × 100ms，合计约 1 秒），
超时预算花在换供应商上，而不是在同一家身上等。通义那侧的 SDK 重试同样关掉（`maxRetries(0)`），
理由一样。

## 亲手试一下

下面是**真实模型（DeepSeek 主）+ 真库**跑出来的原样输出。没有 key 时应用起不来
（ADR-0011），所以本地要先把 `DEEPSEEK_API_KEY` 配上——这也是测试跑的那条路径。

```bash
# 同步问答：模型自己写了 SQL、自己查了库
curl -s -X POST http://localhost:8080/api/chat \
  -H 'Content-Type: application/json' \
  -H 'X-Claw4j-User: ethan' -H 'X-Claw4j-Role: admin' \
  -d '{"text":"上周华东区成交额多少"}'
```
```json
{"chatId":"http:ethan","text":"**上周华东区成交额：￥128,400.00**\n\n计算标准：按订单所属区域（region_code = east）统计，`sum(order_item.amount)`，已排除状态为 CANCELLED 的订单；时间窗口为上周一 00:00 到本周一 00:00（左闭右开）。"}
```

```bash
# 换一个角色头问同一句话：拿不到就是拿不到
curl -s -X POST http://localhost:8080/api/chat \
  -H 'Content-Type: application/json' \
  -H 'X-Claw4j-User: guest01' -H 'X-Claw4j-Role: guest' \
  -d '{"text":"上周华东区成交额多少"}'
```
```json
{"chatId":"http:guest01","text":"没有权限：角色 guest 不允许访问表 customer、order_item、sales_order（工具 querySales）。"}
```

```bash
# 第二轮什么都不复述（会话记忆，华北那次是流式）
curl -s -X POST http://localhost:8080/api/chat \
  -H 'Content-Type: application/json' \
  -H 'X-Claw4j-User: ethan' -H 'X-Claw4j-Role: admin' \
  -d '{"text":"那华南呢"}'
```
```json
{"chatId":"http:ethan","text":"我查了一下，华南区上周成交额是 **￥26,000.00**。\n\n需要说明的是：前面那条华东的数字（￥128,400.00）是上一轮对话里给出的……"}
```

```bash
# 流式问答（SSE，事件名 chunk / done）
curl -N -X POST http://localhost:8080/api/chat/stream \
  -H 'Content-Type: application/json' -H 'X-Claw4j-User: ethan' \
  -d '{"text":"上周华北区成交额多少"}'
```
```
event:chunk
data:**

event:chunk
data:上周华北区成交额：￥31,500.00**
...
event:done
data:
```

```bash
# 匿名请求进不来
curl -s -w ' [%{http_code}]' -X POST http://localhost:8080/api/chat \
  -H 'Content-Type: application/json' -d '{"text":"你好"}'
# {"error":"缺少 X-Claw4j-User 请求头"} [401]
```

**换一家当主模型**（通义，走它的 OpenAI 兼容端点）：

```bash
CLAW4J_MODEL_PRIMARY=qwen ./mvnw -pl claw4j-app spring-boot:run
```
```json
{"chatId":"http:qwen01","text":"上周华东区成交额为 128,400.00 元。"}
```

**主备降级**：把主的 base-url 指到没人听的端口，问同一句话，请求落到备（通义）上：

```bash
DEEPSEEK_BASE_URL=http://127.0.0.1:9/v1 ./mvnw -pl claw4j-app spring-boot:run
curl -s -X POST http://localhost:8080/api/chat \
  -H 'Content-Type: application/json' \
  -H 'X-Claw4j-User: ops9' -H 'X-Claw4j-Role: admin' \
  -d '{"text":"上周华东区成交额多少"}'
# {"chatId":"http:ops9","text":"上周华东区成交额为 128,400.00 元。"}   ← 6.4 秒
```
```
WARN  i.g.c.agent.model.RoutingChatModel : 模型 deepseek 调用失败，降级到下一个（若有）
```

请求头：`X-Claw4j-User`（必填）、`X-Claw4j-Role`（缺省 `read-only`）、`X-Claw4j-Chat-Id`（缺省回落到用户标识）。角色 → 可访问的表/动作在 `claw4j.channel.http.roles` 里配置，改权限不用发版。

现实里的前置条件是由网关在边缘完成认证、把结果写成请求头；**本项目不做登录体系**，那不是考点（ADR-0007）。

## 鉴权：由 Principal 决定，不由模型决定

问"上周华东区成交额多少"时，模型会要求调 `querySales`。**它要求了不等于它会发生**：

```
请求头 ──► 通道认人 ──► Principal ──► 鉴权 Advisor ──► 工具
                                          │
                                          └─ 不通过：这一轮整个换成"没有权限"，工具一次都不调用
```

三点是关键：

1. **order 排在工具调用循环内部**（大于 `ToolCallingAdvisor.DEFAULT_ORDER`）。链按 order 升序排，
   而 `ToolCallingAdvisor` 每轮递归的是排在它**后面**的那段，所以"内部"是 order 更大的一侧。
   于是模型的**每一次**工具迭代都过一遍校验：第一轮放过、第二轮越权，照样拦。
   写成更小也能编译能跑，只是悄悄退化成"整通对话只校验一次"。
2. **判定在工具执行之前**。拦下来的那一轮连工具调用请求一起被换掉，
   所以"工具没被调用"是真的没被调用，不是"调用了但返回空"——
   这两者在客户端看起来几乎一样，测试里靠**调用计数**加**话术断言**分开。
3. **权限跟着工具走**：工具所在的模块登记"这个工具要什么动作、会碰到哪些表"
   （`querySales` → 读动作 + 它可能 join 到的四张表），Agent 只认 `ToolPermissionRegistry` 接口。
   没登记的工具一律按拒绝处理——没人评估过它会碰到什么。

粒度是**表级 + 动作级**，行级列级不做。默认三个角色：

| 角色 | 表 | 动作 | 问成交额 |
| --- | --- | --- | --- |
| `read-only`（缺省） | `*` | `read` | 拿到数 |
| `guest` | `region` | `read` | 明确的"没有权限"（读不到 `sales_order`） |
| `admin` | `*` | `*` | 拿到数 |

流式路径同样受保护：工具调用是分块发下来的（名字在头一块、参数在后面几块），
所以流式必须逐块看，看到越权当场掐断上游——攒到最后再判的话，越权那一轮的内容已经吐出去了。
这条由 `AuthorizationOverHttpTest` 在真实 HTTP 上钉住：换一个角色头问同一句话，
一个有答案、一个是明确的拒绝。

### 脱敏是另一件事：查到了能不能原样说出去

鉴权管"能不能查"，脱敏管"查到了能不能原样说出去"（ADR-0007）。脱敏 Advisor 认手机号与身份证号，
手机号留头三尾四、身份证留头六尾四——全打码的话"尾号 5678 那位"这种话就说不出口了。

三点值得留意：

1. **order 在工具循环外面**（小于 `ToolCallingAdvisor.DEFAULT_ORDER`），整通对话只在出站前做一次。
   放进去会把**工具结果**也一起打码，而模型要靠原始值判断"这个号码是哪一单的"。
2. **流式不能逐块各自脱敏**：分块边界会正好落在一个手机号中间（8 字一块时是必然的），
   两块都匹配不上，原始号码整个漏出去，而且漏不漏取决于模型那批吐了多少字。
   做法是扣住"还可能是号码开头"的尾巴，等下一块来了再一起判，收尾时把剩下的全部吐出。
3. **日志里也是脱敏后的**。只在响应里脱敏、日志里仍是明文，等于没脱；
   `ChatClientAgentPort` 敢在 DEBUG 打出站全文，正是因为脱敏已经做完了——
   哪天有人在链路之外打日志，那条断言会立刻出现原始值。

## Text-to-SQL：模型写 SQL，数据库说了算

放行之后才是正题。模型把问句**翻译成一条 SQL** 交给 `querySales`，工具只读执行、把行还回去，
由模型自己组织成回答。工具参数是整条 SQL 而不是"区域 + 时间窗"两个字段——
收成两个字段，模型就退化成填空机，"按产品""按客户""环比"这些问法全都答不了。

固定 schema（表名、列名、区域代码取值、成交额口径、时间窗写法）写在工具描述里，
随每次请求一起发出去。这不是啰嗦：模型不知道 `region_code` 的取值是 `east` 而不是 `华东`，
写出来的 SQL 不报错、只返回 0 行，而**"返回 0 行"是自纠错唯一无法自己发现的错误**。

### 只读约束落在两层，因为一层不够

| | 拦什么 | 谁来保证 |
| --- | --- | --- |
| 语句校验（`SalesSqlGuard`） | 多语句、非查询首关键字、写操作关键字 | 我们写的正则 |
| 只读事务（`JdbcReadOnlySqlExecutor`） | 任何写操作 | 数据库自己 |

只做第一层不够，理由很具体：**PostgreSQL 有可写 CTE**，
`with x as (insert into ... returning *) select * from x` 是一条语句、而且以 `with` 开头，
任何"首关键字是 select/with 就放行"的规则都会放过它。
所以第二层必须存在：查询跑在 `TransactionTemplate(readOnly=true)` 里，
Spring 把它翻译成 `Connection.setReadOnly(true)`，PostgreSQL 驱动再翻译成事务级只读标记，
写操作由数据库拒绝。校验漏了，数据也不会没。

校验本身也踩过两个坑，都留了断言：注释里夹分号（`select 1 -- ; drop table t`）
在字符串层面看是多语句、在数据库层面是一条，所以校验前要先去掉注释与字符串字面量；
而行上限是**包一层子查询**而不是在末尾拼 `limit`——模型自带的 `order by ... limit 10`
后面再拼一个 limit 就是语法错误。包一层还顺手把 DML 挡在了解析阶段
（PG 的子查询里不能是 `insert` / `update` / `delete`）。

### 自纠错与重试上限

写错了怎么办：**工具返回数据库的原话，而不是抛异常**。抛异常会被框架包成模型看不懂的话，
返回原因（含 `column "amout" does not exist` 这种具体信息）模型才有东西可改。
这是自纠错链路能成立的前提。

重试上限由 `ToolCallBudget` 收紧到**每工具 3 次、整轮 6 次**——落在 `ToolCallingManager`
的 `maxCallsPerTool` / `maxTotalToolCalls` 上，不是 yml 里那个 `spring.ai.tools.limits.*`
（默认 40/150 是为分页取数设计的，放在自纠错上就是一次提问烧几百次往返）。
超限的行为是 `THROW`：`ToolCallingAdvisor` 接住后直接变成一句回答给客户端，
不再回头问模型——否则"超限"会被当成工具结果喂回模型，它还会再试一次。

这里有个**静默失效**的坑值得单独记：`spring.ai.tools.limits.*` 配置到不了我们这条链。
`ChatClient` 是我们自己建客户端的，五参重载里传进去的 `ToolCallingManager` 才是生效的那个，
自动配置那个只有在使用 Boot 注入的 `ChatClient.Builder` bean 时才会被用上。
哪天改成注入 bean，这两个数字会静默退回 40/150，所以**两个数字各有一条测试钉着**：
`TextToSqlLoopTest` 钉单工具上限，`ToolCallBudgetTest` 钉总数上限。

总数那条值得一说：单工具上限（3）比总数上限（6）小，而 M1 只注册了一个工具，
所以"整轮 6 次"在单工具场景下**永远撞不到**——它守的是"多工具叠加"这个还没发生的风险。
与其留一个没人能证明它在生效的数字，测试里干脆造了三个假工具把它逼出来
（三个各调 2 次，第 7 次落在还没调满的那个上）。

## 能力三层：对 Agent 呈现为同一件事

能力按"要不要鉴权、有没有外部副作用、依不依赖外部运行时"分三层（ADR-0003），
**三层对 Agent 呈现为一回事——都是"工具名 + 参数 Schema"**，Agent 侧不出现"这是哪一层"的分支：

| 层 | 形态 | 状态 |
| --- | --- | --- |
| 一层 | Java `@Tool`（`SalesTools.querySales`、`SystemStatusTools.systemStatus`） | M1 已落地，M2-07 补齐 `system-status` |
| 二层 | MCP server 的 `@McpTool` | M2-02 已落地（连接生命周期），M2-07 接到真的 OS 进程，见下 |
| 三层 | Script Package（受限执行） | M2-03 已落地，M2-07 迁完三个保留场景，见下 |

三层之外还有两件事挂在同一套工具契约上：**Agent Skills**（M2-04，命中才加载全文）与
**Subagent**（M2-05，派出去跑、只回结论），再加上 **Skill Generator**（M2-06，
模型产出的脚本要走完五步才轮到人）。它们各自在下面有一节。

MCP 这一层的连接生命周期由 `claw4j-tools` 的 `io.github.claw4j.tools.mcp` 管，两条语义是硬要求：

- **连接建立时工具清单为空即失败**（ADR-0002）。连接是**通的**——协议握手成功、server 起来了——
  只是它一件能力都没有。这种状态不报错的话，现场是"模型说它没这个能力"，
  而真正的原因是命令写错或版本对不上。
- **会话丢失（`McpTransportSessionNotFoundException`）重新初始化连接，而不是重试死会话**。
  对一条已经不在的会话重试，会让一次断连变成永久不可用。

这两条是 ADR-0008 的三条必测之一，所以测试里的 MCP server 是 **Java 自实现的进程内 stdio server**
（`InProcessStdioMcpServer`）：不依赖 Docker、不依赖 Python，于是这两条进得了默认构建。
**它跨的是协议边界不是 OS 进程边界**。真实部署换的是 `McpServerEndpoint.openTransport()`
给出来的传输，上面的生命周期代码一行都不用改——M2-07 就是这么接上真的 OS 进程的：

```java
// 命令与参数分开给，交给 MCP SDK 起进程（不经任何命令解释器）
ProcessStdioMcpEndpoint.of("claw4j-github", "python3",
        List.of("examples/mcp/gh_mcp_server.py"), Map.of("PATH", System.getenv("PATH")))
    .endpoint(ToolPermission.read());
```

`examples/mcp/` 下有三个零第三方依赖的 Python server：`gh_mcp_server.py` 包 `gh`、
`himalaya_mcp_server.py` 包 `himalaya`（它导出会发邮件的 `mail_send`，所以接它的端点登记的是
`send_email` 而不是只读）、`demo_mcp_server.py` 不依赖任何 CLI。
子进程的**环境默认为空**，需要 `PATH` 就显式写一行——应用的模型 key 与数据库口令不会漏过去。

这条链路真跑一次的测试打的是 `real-cli` tag，默认不跑（它依赖本机装了 python3 与那些 CLI，
不是每台机器都有，所以不进默认构建）：

```bash
./mvnw -Preal-cli -pl claw4j-tools test -Dtest=RealCliMcpEndToEndTest
```

MCP 层**不授予任何业务表权限**：它登记的权限里一张表都没有，鉴权只看动作。
没登记权限的工具一律按拒绝处理（ADR-0007）。

### Script Package：脚本本体不进上下文，只有输出进

脚本层这一层（`io.github.claw4j.tools.script`）的不是"能跑脚本"，而是**跑在什么里面**。
模型看到的仍然只是"工具名 + 一个参数"，看不到脚本写了什么；它拿到的返回值只有标准输出。

执行后端是社区包的 `ExecBackend` 接缝（`org.springaicommunity:spring-ai-agent-utils`），
**换沙箱等于换一个实现，调用方一行不动**——这是这条边界唯一可测的地方：

```java
new ScriptPackageRunner(packages)                 // 默认：受限子进程
new ScriptPackageRunner(packages, myFactory)      // 换沙箱：换的是这个
```

**沙箱的物理形态是"应用容器内的受限子进程"**，判据只有一条：**能不能在一台干净的机器上跑起来**
（不依赖 Docker 守护进程、不依赖 Python）。跑不起来的沙箱等于没有测试，
没有测试的边界不能写进 README 当承诺。
（`LocalExecBackend` 自带 `workingDirectory` 与 `cleanEnvironment`，不需要 Docker 与 Python。）

它真的挡住的五样，每样都有一条测试指着：

| 约束 | 做法 | 盯它的测试 |
| --- | --- | --- |
| 工作目录 | 进程起在脚本包自己的目录里 | `SandboxedExecBackendTest` 用 `pwd` 验 |
| 参数 | 每个参数都过路径校验：`..`、绝对路径、符号链接一律拒绝 | `ScriptPackageRunnerTest` |
| 环境变量 | 环境从零开始，只给 `PATH`；调用方塞变量会被拒 | `SandboxedExecBackendTest` 用 `env` 验 |
| 超时 | 上限 30 秒，不给超时或超上限就拒绝执行（不夹取） | `SandboxedExecBackendTest` |
| 非 root | 账号是 root 时拒绝执行 | `SandboxPolicyTest` |

它**挡不住**的，同样说清楚：脚本**体内**自己去读一个绝对路径、自己去连一次网。
进程沙箱锁得住入口与参数，锁不住脚本自己的行为；那种越界要靠另一台容器
（ADR-0003 的第 ② 类沙箱，给 Skill Generator 产出的未审核代码用，M2-06）。

**Java 侧不自己拼命令行**（ADR-0002），这条由两台机器盯着，不靠 review 记：

- ArchUnit：`io.github.claw4j..` 主代码不许调用 `Runtime.exec`、不许碰 `ProcessBuilder`
  ——**例外只有一个被点名的类**：`LocalProcessRunner`。加第二个之前先读票 08 里那段取舍；
- 源码扫描：`claw4j-*/src/main/java` 里不许出现手写的 shell 调用字面量。
  后者挡的是"把命令行当一个字符串传下去"——那种写法起进程的不是本项目，
  于是 API 那条规则看不见它，而宿主机的命令行照样交出去了。

脚本层交给进程的是**一个 argv 数组**，不是一条命令行（票 08）：
入口脚本来自包清单（绝对路径），参数一律先过沙箱的路径校验（越界即拒绝），
然后作为数组里的元素原样交给进程。数组形态没有命令解释器这一步，
所以参数里带 `; echo pwned` 或 `$(...)` 也只是"一个字符串"，不是语法——
"挡住注入"不再依赖任何一处转义写得对不对。代价是入口脚本必须自己可执行、
第一行给出解释器（缺了就在静态检查那一关被挡下，见 Skill Generator 那节）。
起进程的全仓只有 `LocalProcessRunner` 一个地方，工作目录与环境变量由沙箱在构造它时给定。

脚本层**不授予任何业务表权限**，而且它要的动作不是 `read` 而是 **`run_script`**：
只读角色能查数是"只读"的本意，能执行脚本就不是了——脚本能写文件、能往外连，
那是比读一张表宽得多的口子。没登记权限的工具一律按拒绝（ADR-0007）。

启用这一层要显式配目录（配了却不存在即启动失败，那是配置错误不是"这一层不存在"）：

```yaml
claw4j:
  scripts:
    directory: skills      # 每个子目录一个包，包里用 package.properties 说明自己
```

### Agent Skills：命中才加载全文，加载完自己编排工具

前两节说的是"能力从哪来"，这一节说的是**模型怎么知道该按什么顺序用它们**。
`SKILL.md` 写的是"什么时候用得上、按什么流程用"，它**不提供动作，只编排动作**
（GLOSSARY 里 Skill 与 Script Package 的分工就在这里：一个是做法，一个是脚本）。

关键设计是**渐进式披露**：

| 时机 | 进上下文的是什么 | 判据 |
| --- | --- | --- |
| 启动 | 每个 Skill 的 `name` + `description`（几行 XML） | 模型靠这两行判断"有没有用得上的做法" |
| 命中 | 只有被选中那一份的正文，作为那次调用的结果回来一次 | 有二十个 Skill 的代价是二十行描述，不是二十份正文 |

这条边界两头都钉了断言——只钉"正文没进来"是不够的，"谁的正文都没进来"
（比如这一层压根没生效）也能全绿，所以同一份转储上还有一条正向断言盯着观测通道本身：

- `AgentSkillsTest`：工具描述里有元数据、**没有任何一份正文**；用名字调用才取回正文，且只取回那一份；
- `AgentSkillsEndToEndTest`：整条链路里两个 Skill 的元数据都在，**没被选中那份的正文不在任何一轮请求里**。

工具类型**没有自己再包一层**：社区包的 `SkillsTool.Builder.build()` 直接返回 `ToolCallback`，
它的工具名、输入 Schema、"用名字取正文"都在那一个类里。再包一层只会多出一个要同步维护的
描述字符串，而"描述里到底有什么"正是这里要盯的东西。

权限登记的是 **`use_skill` 动作，业务表一张都没有**——Skill 自己不读库，它让模型去读，
而那一步照样过鉴权。所以能读的角色（`read-only` / `guest`）也都给了 `use_skill`：
加载一份做法既不读表也不写东西。把它与 `read` 分开列，是为了让"谁能用什么做法"
成为一行配置能回答的问题，而不是"能读就顺便能用"。

启用这一层同样是显式配目录（配了却一个 `SKILL.md` 都没有即启动失败）：

```yaml
claw4j:
  skills:
    directory: skills      # 每个含 SKILL.md 的子目录是一份 Skill
```

**这里没有的**：让模型自己产出一份做法。这份 SKILL.md 仍然是人写的；
模型产出的是**脚本包**，而且要先过下面那条五步链路（Skill Generator）。

### Subagent：派出去跑，只把结论带回来

前两节说的是"能力从哪来、按什么顺序用"，这一节说的是**长任务别把中间过程摊在会话里**。
委派本身是父 Agent 的一个工具（`Task`），执行时新开一个独立上下文窗口（ADR-0012）：

```
父这一轮：用户问 → 模型决定派出去 → Task(结果只有一个字符串：子的结论) → 组织成回答
子那一轮：只拿到 task 这一句 → 自己调工具若干次 → 只把最后那句交回去
```

**做深的是上下文隔离，不是"起了第二个 Agent"**——后者配一个新 `ChatClient` 就有。
隔离盯三样，每样都有断言：

| 隔离 | 父那边看到什么 | 盯它的测试 |
| --- | --- | --- |
| 子的中间轨迹 | 不进父上下文（子的工具调用与结果留在子窗口） | `SubagentRunnerTest` 只回传结论；`SubagentEndToEndTest` 回传给父的只有结论 |
| 子的系统提示词 | 只在子窗口出现一次；父的工具描述里只有名字与描述 | 同上（两条都带正向对照：那样东西在子侧确实出现过） |
| 父的对话历史 | 子看不见，它只拿到委派时那段 `task` | 委派接口只有一个字符串入参 |

委派**不等于把全部能力交出去**：子能碰哪些工具由定义文件里的 `tools` / `disallowedTools`
决定，白名单点名了却没装上的工具按失败处理（不是"少给一个"）。
`Task` 工具的描述里只有每个 Subagent 的名字与一句话它管什么——
与 Skill 同一套渐进式披露，十份流程说明不会常驻在父的提示词里。

两条安全口径值得单独说，因为它们都有测试指着：

- **委派不提权**：子那一侧照样过 `AuthorizationAdvisor`，用的是**父那个身份本身**
  （`PrincipalCapturingAdvisor` 断言是同一个对象）。guest 派出去的 Subagent 去查
  `sales_order` 时被挡在**执行之前**——断言同时盯着"那个工具一次都没被调用"。
- **子也吃 3 / 6 预算**：子链的 `ToolCallingManager` 与父同一个构造入口。
  委派不能变成绕过预算的口子，否则一个坏掉的脚本在子里循环时父这边看起来毫无动静。

`Task` 自己登记的权限是 **`delegate_task` 动作，业务表一张都没有**：
委派这个动作不碰库，碰库的是子要调的那个工具，那一步按它自己的登记表鉴权。
所以 `read-only` 与 `guest` 也都能委派——给不改变它们看得到什么。

启用这一层同样是显式配目录（目录下每个 `*.md` 是一个专职 Agent，配了却读不出来即启动失败）：

```yaml
claw4j:
  subagents:
    directory: agents      # 每个 *.md 一份定义：name / description / tools / disallowedTools + 正文
```

**这里没有的**：嵌套委派（定义里写 `Task` 按定义错误失败）、并行多个 Subagent、
跨进程 Subagent（A2A，M3）。子 Agent 沿用父的模型实例，不再引第二套 key。

### Skill Generator：模型产出的脚本，五步之后才轮到人

这是本项目"开放扩展"这个定位**唯一能自证的部分**：模型可以自己产出一个新的可执行能力，
但全程关在笼子里（ADR-0002 修订后的定案、ADR-0013）：

```
生成 → 静态检查 → 隔离沙箱自测 → 审计留痕 → 人审通过才注册
```

五步的**顺序就是安全边界的顺序**，不是流水线编排的偏好：

| 步 | 做什么 | 放在这个位置的理由 |
| --- | --- | --- |
| 1 生成 | 脚本落盘到暂存区 | —— |
| 2 静态检查 | 纯函数，按规则表查写法 | 在落盘**之前**：危险代码不该先被写下来再被删掉，那样磁盘上出现过它，而没人会记录 |
| 3 沙箱自测 | 临时副本里跑一次，跑完即删 | 在**沙箱**里而不是暂存区里：跑的是未审核代码，它不能改自己待审的那份内容 |
| 4 审计 | 生成 / 自测 / 执行各记一条 | 三步**各自**记：最后补的那条只能写"发生了什么"，写不出"被挡下的是谁提交的" |
| 5 人审 | 人写一个审批标记，签下"谁、什么时候、审的是哪一版" | 唯一没有代码替你做的那一步 |

**静态检查是未审核代码进门前的唯一一道机器关，也是最便宜的一环**：
自测要起进程、要花时间，而这一关既不落盘也不执行就能挡下绝大多数危险写法。
它是纯函数（不读文件、不看时间、不取随机数），于是"挡住了什么"能被一张表钉死——
危险清单逐条一个用例，正常脚本另有一组用例（只测"危险被挡下"的话，全拒的实现也会绿）。

它认的是**写法**（装包、提权、连网、读凭据、删东西、动态执行、缺 shebang），认不出意图；
把危险动作藏在变量里它看不出来。挡的是明着写的那部分——
而那部分在真实事故里占绝大多数（1.x 那次让脚本自行 `pip install --break-system-packages`
就是明着写的）。

**未人审的脚本拿不到被调用的资格**：工具清单由 `ScriptPackageRegistry` 现取，
"待审"在模型那边就是"根本没有这个工具"——不是靠"模型不去调它"维持的，它看不见。
人审比对的还是**脚本摘要**：签字之后脚本被改（同名重新生成一次），旧签字自动作废，
"审过一次"不会变成一张长期通行证。

**上线的是一份快照，不是暂存区里那个目录**：签字生效时把候选目录整份复制到发布目录，
清单与执行都指向那份副本。暂存区是模型能反复重写的地方（同名再提一次就整份覆盖），
把执行目录直接指向它，等于让"人审的是哪一版"这件事交给文件系统回答——
文件系统答不上来，跑的会是最后一版没人签过字的脚本。
这条由两条用例钉着，一正一反：同名重提之后跑出来的**仍然是人审过的那一版**；
新版重新签过字之后**要能真的上线**（快照不是把闸门焊死）。

自测的沙箱（ADR-0003 的第 ② 类）与 Script Package 那个（第 ① 类）是两套代码，
判据是**跑的东西不同**：① 跑人审过的包，② 跑模型刚写出来的脚本。
它给的三样与①同一口径但更紧：临时目录（跑完即删）、只有 `PATH`、5 秒上限。
**网络隔离这一项它给不了**，规格里如实写成 `NOT_ISOLATED` 并写明原因——
纯 Java 起的子进程没有可移植的网络限制手段，而为了限制脚本再去起一条命令
正是 ADR-0002 禁止的那件事。真正的网络隔离由部署层的容器网络策略给；
在干净机器上跑不起来的形态不写进 README 当承诺，所以联网的脚本在**静态检查**那一关就被挡了。

失败时回给模型的是**脚本的原话**（stderr），不做包装——与自纠错同一个道理：
包装过的话里没有可改的信息，`syntax error near unexpected token` 指得出行，
"自测未通过"指不出任何东西。

生成器这个工具自己登记的权限是 **`draft_script` 动作，业务表一张都没有**；
它不等于 `run_script`：提交一份草稿不等于能跑脚本，把两个动作合成一个，
就等于让"能提议的人"顺手拿到了执行权。所以只有 admin 能提。

```yaml
claw4j:
  scripts:
    directory: skills          # 生成器要往这里注册，没配脚本层就没有生成器
  generator:
    staging-directory: .claw4j/staging   # 候选包落在这
    review-directory: .claw4j/reviews    # 人审标记放这（默认在暂存区**旁边**，不在里面）
    audit-file: .claw4j/audit.tsv        # 只追加
```

**这里没有的**：自动生成 Java `@Tool`；自动人审（这一步必须是人）。

### 一次把三层都接上：真实模型的实录

`examples/` 下放的是能真的接进来的能力（脚本包、`SKILL.md`、Subagent 定义、Python MCP server），
不是文档片段。目录说明见 [`examples/README.md`](examples/README.md)，这里是一次接上全部：

```bash
java -jar claw4j-app/target/claw4j-app-0.1.0-SNAPSHOT.jar \
  --claw4j.scripts.directory=examples/scripts \
  --claw4j.skills.directory=examples/skills \
  --claw4j.subagents.directory=examples/subagents \
  --spring.profiles.active=mcp-demo --claw4j.mcp.demo.demo-server=true
```

下面这几段是**那次运行的真实 curl 输出**，不是占位符（M2-07 收尾时录的，DeepSeek + 本地 Postgres）：

```bash
# 一层：Java @Tool 读自己的运行时状态
curl -s -X POST localhost:8080/api/chat -H 'Content-Type: application/json' \
  -H 'X-Claw4j-User: ops9' -H 'X-Claw4j-Role: admin' \
  -d '{"text":"这个进程现在堆内存上限是多少？线程数呢？"}'
# {"chatId":"http:ops9","text":"堆内存上限 8192 MB（8 GB），当前已用 73 MB；线程数 42。"}

# 二层：跨一个真的 OS 进程，Python server 那头回的话
curl -s -X POST localhost:8080/api/chat -H 'Content-Type: application/json' \
  -H 'X-Claw4j-User: ops9' -H 'X-Claw4j-Role: admin' \
  -d '{"text":"用 demo_echo 这个工具，把“跨进程调用成功”这句话原样带回来，只回这句话"}'
# {"chatId":"http:ops9","text":"跨进程调用成功"}

# 三层：脚本包在受限子进程里跑，回来的只有它的标准输出
curl -s -X POST localhost:8080/api/chat -H 'Content-Type: application/json' \
  -H 'X-Claw4j-User: ops9' -H 'X-Claw4j-Role: admin' \
  -d '{"text":"深圳未来三天的天气怎么样"}'
# {"chatId":"http:ops9","text":"深圳未来三天天气：\n\n| 日期 | 最高温 | 最低温 | 降水概率 |\n|---|---|---|---|
#  | 10-24 | 32℃ | 22℃ | 0% |\n| 10-25 | 32℃ | 22℃ | 0% |\n| 10-26 | 30℃ | 23℃ | 19% |\n\n
#  前两天晴好、气温偏高；26 日略降温，有小概率降水，出门可备把伞。"}
```

Skill 与 Subagent 在同一趟里也各被用上一次。周报那条是模型先加载了 `weekly-sales` 这份做法、
再按它写的格式输出（连"环比这一版没做"都是照着正文里那条边界说的）：

```bash
curl -s -X POST localhost:8080/api/chat -H 'Content-Type: application/json' \
  -H 'X-Claw4j-User: ops9' -H 'X-Claw4j-Role: admin' \
  -d '{"text":"按周报格式给我一份上周各区的成交额"}'
# {"chatId":"http:ops9","text":"上周成交额（2026-10-12 ~ 2026-10-19）\n  华东：128,400.00\n
#   华南：26,000.00\n  华北：31,500.00\n  合计：185,900.00\n\n口径说明：成交额 = sum(order_item.amount)，
#   已排除 status = 'CANCELLED' 的订单；时间窗为半开区间 [2026-10-12, 2026-10-19)。\n\n
#   环比这一版没做——要算环比得再查上周之前一周的数据，需要的话我可以补。"}

curl -s -X POST localhost:8080/api/chat -H 'Content-Type: application/json' \
  -H 'X-Claw4j-User: ops9' -H 'X-Claw4j-Role: admin' \
  -d '{"text":"让 sql-reviewer 看一眼这条 SQL 有没有问题：select sum(amount) from business.order_item"}'
# {"chatId":"http:ops9","text":"sql-reviewer 的结论：**有问题，这条 SQL 算出来的不是成交额。**\n\n主要问题三条：
#  \n\n1. **漏了排除已取消订单**（最严重）。`order_item` 上没有 status，状态在 `sales_order` 上……
#  实测全表 sum = 413,676.00，其中 50,000.00 来自已取消订单，真实成交额应为 363,676.00……"}
```

委派那一条值得注意的是**子 Agent 真的自己跑去查了库**（它只有 `querySales` 一个工具，
白名单写死在定义里），而父这一轮的上下文里看不到它查了几次。

鉴权在这趟里同样生效——同一个天气问题换个角色：

```bash
curl -s -X POST localhost:8080/api/chat -H 'Content-Type: application/json' \
  -H 'X-Claw4j-User: guest9' -H 'X-Claw4j-Role: guest' \
  -d '{"text":"深圳未来三天天气怎么样"}'
# {"chatId":"http:guest9","text":"没有权限：角色 guest 不允许执行 run_script 动作（工具 weather-forecast）。"}
```

上面这五条里，MCP 那条依赖本机装了 python3（那三个 Python server 零第三方依赖），
天气那条要能出网。`gh` / `himalaya` / `xurl` 那几个没在这趟里演示，
它们要本机装了对应 CLI——**演示归演示，回归基线不依赖这些**（ADR-0014）。

## 会话记忆：记得住上一轮，而且换条通道也记得住

第一轮问"上周华东区成交额多少"，第二轮问"那华南呢"——第二轮不该要求把"上周""成交额"重讲一遍。
记忆挂在链的最外层（`SessionMemoryAdvisor`，order 全链最小），用
**`org.springaicommunity:spring-ai-starter-session-jdbc`**（groupId 不是 `org.springframework.ai`，
它是 spring-ai-community 的独立项目，ADR-0005 里记了这个坑）落在 Postgres，
会话表在 `claw4j_session` schema，业务表在 `business`，同库不同 schema。

三点值得留意：

1. **会话属于人，不属于通道**。会话 id 只由 `Principal.userId()` 派生，**不掺 `chatId`**：
   `chatId` 是通道侧的寻址键（HTTP 的请求头、飞书的 chat_id），换条通道它就变了。
   把它编进会话主键，"换通道"和"换会话"就成了同一件事——存储明明是共享的，人却要重讲一遍。
   代价是同一个人在两段对话里问两件事，记忆会混在一起；多话题并行是产品问题，等有真实数据再定。
2. **会话有自己的连接池，URL 上带 `currentSchema`**。`spring-ai-session` 的 SQL 里表名不带前缀，
   表落到哪由连接的 `search_path` 决定；而它的自动配置拿的是主 `DataSource`，表会落到 public。
   所以这里自己声明了 `SessionRepository`（官方那份带 `@ConditionalOnMissingBean`，会退让），
   指向会话 schema。会话池**不注册成 bean**——容器里有两个 `DataSource` 时，
   那些 `@ConditionalOnSingleCandidate(DataSource.class)` 的自动配置会整套安静地不装配。
3. **记忆的收尾排在脱敏之后**。链的收尾按 order 降序，order 越小越晚收尾，
   于是写进会话表的是**掩码后**的文本。反过来写，出站是干净的、会话表里躺的是明文——
   一份比出站更持久的泄露，而且没人会去看。

**记不住不等于答不了。** 会话库挂了的时候，记忆降级成"这一轮没有历史"并打一条 WARN，
而不是让整条链 500：鉴权是安全边界，它不能因为"记不住"就连"没权限"这句话也说不出来
（`SessionMemoryDegradationTest` 钉着）。三个 Advisor 的失败姿势不一样——
鉴权失败只能拒绝、脱敏失败只能拒绝、记忆失败只能降级。

## 质量门禁

五条检查层层收紧，都不靠口头约定：

| 门禁 | 拦什么 | 什么时候跑 |
| --- | --- | --- |
| `./mvnw verify` | 编译、391 个测试、打包 | 本地随时 |
| ArchUnit 测试 | domain 不依赖框架、agent 与 channel 互不依赖、通道 SPI 平台中立、飞书 SDK 只在 feishu 包、**Advisor 必须双实现 Call + Stream**、**Java 侧不起进程（`Runtime.exec` / `ProcessBuilder`，例外只有 `LocalProcessRunner` 一个类，ADR-0002）** | `mvn test`，以及提交前 |
| `NoHandBuiltShellCommandTest` | 主代码里没有手写的 shell 调用字面量：把命令行当字符串传下去时，起进程的不是本项目，上面那条 API 规则就看不见它 | `mvn test` |
| maven-enforcer | JDK/Maven 版本基线；domain 的 POM 里出现 Spring / Spring AI / 厂商 SDK / Reactor 直接失败 | `validate` 阶段，加错依赖当场红 |
| `./scripts/assert-no-skipped-tests.sh` | **有测试被跳过就红**：跳过的绿和通过的绿在构建输出里长得一样，而"数据库测试整类没跑"恰好藏在跳过里 | 本地报数前（verify 之后） |
| pre-commit hook | 改了 `claw4j-*` 下的 java/xml 就跑一遍架构规则（秒级） | 每次 `git commit` |
| 本地冒烟 | 起应用带 `examples/{scripts,skills,subagents}` + `mcp-demo`，真模型走一遍三层与越权（见"一次把三层都接上"那节） | 改了装配或能力层之后 |

启用 hook（一次）：

```bash
git config core.hooksPath .githooks
```

**CI 暂未接入。** `.github/workflows/ci.yml` 先留在仓库里当接线图（`verify` + 跳过闸 +
"legacy 不得回到根 POM 的 modules"），但流水线并没有在跑，README 也不声称它是绿的。
在那之前，门禁就靠上面这几条，全在本地：提交前 hook、报数前的 `verify` 与跳过闸、
以及改动能力层之后的本地冒烟。接 CI 时有两件事得先备好：runner 上没有 Docker 守护进程
（数据库那一类靠 `-Dclaw4j.dbTests=required` 直接判红），以及 `secrets.DEEPSEEK_API_KEY`
（ADR-0011：没有 key 是红，不是跳过）。

**为什么 Advisor 那条值得单独立规则**：`BaseAdvisorChain` 给流式路径取的是 `getStreamAdvisors()`，只认 `StreamAdvisor`。只写 `CallAdvisor` 的鉴权 Advisor 在 `.stream()` 上**既不报错也不生效**，客户端照常拿到回答——鉴权等于没做，而且从日志上看不出来。这种"能编译、能跑、但悄悄不生效"的缺陷只能靠机器拦（ADR-0007）。

## legacy/ 是什么

1.x 的冻结代码：Spring Boot 3.3.4 / Spring AI 1.0.0-M6，40 个类 / 6793 行 / 零测试，根包名 `com.xteammors.openclaw`。**不参与任何构建，不再修改**，只作为 before/after 对照保留，冻结位置是 `v1.x-legacy` tag。

它里面那些"教科书级反例"（字符串协议的工具调用、把 shell 交给模型、反射 hack 私有字段的向量库）是这次重建的动机，详见 [legacy/README.md](legacy/README.md)。新代码的包名是 `io.github.claw4j`，与它不共用任何类名。

## 文档

- [`docs/adr/`](docs/adr/) —— 14 条架构决策记录，每条都写了 Considered Options 与被否决的原因
- [`GLOSSARY.md`](GLOSSARY.md) —— 术语表

## License

见 [LICENSE](LICENSE)。
