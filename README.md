# Claw4J

[![CI](https://github.com/milanyangbo/Claw4J/actions/workflows/ci.yml/badge.svg)](https://github.com/milanyangbo/Claw4J/actions/workflows/ci.yml)

企业 IM 上的业务数据助手。主场景是 **Text-to-SQL 销售域问答**：业务同学在群里问"上周华东区成交额多少"，Agent 自己查库、自己答。

整个项目是 **基于 Spring AI 2.x 从零重建** 的（1.x 版本已冻结进 `legacy/`，见 [legacy/README.md](legacy/README.md)）。重建的全部设计决策都留了 ADR，见 [`docs/adr/`](docs/adr/)。

> **当前进度：M1 进行中。** M0 骨架已完成：HTTP+SSE 通道端到端打通、模型按 key 有无自动装配、架构边界由机器校验。M1 已完成业务库与种子、工具调用闭环、鉴权与脱敏 Advisor、Text-to-SQL 闭环、会话记忆（落 Postgres）；待办是真实模型联调。M2 接飞书长连接与 Skill 引擎。

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
./mvnw verify                        # 一条命令：构建 + 架构检查 + 171 个测试
./mvnw -pl claw4j-app spring-boot:run
```

没有 Docker、没有 API key 也能跑完 —— 模型会自动降级到 mock（`[mock]` 前缀），数据源是懒连接不会在启动时真连（ADR-0006）。

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
但**"跳过的绿"和"通过的绿"长得一样**，所以 CI 上跑的是
`./mvnw verify -Dclaw4j.dbTests=required`——那边跑不起来就直接红。
（`CLAW4J_POSTGRES_IMAGE` 与 compose 用的是同一个变量，写进根目录 `.env` 也能生效。）

### 要真实模型

见下一节。

## key 怎么配

凭据一律走环境变量，不写进仓库（ADR-0002、ADR-0006）。仓库根目录放一个 `.env`（已被忽略）：

```bash
DEEPSEEK_API_KEY=sk-xxxxxxxx
# QWEN_API_KEY=sk-xxxxxxxx   # 备用模型
```

`application-local.yml` 里有一行 `spring.config.import: optional:file:.env[.properties]` —— Spring Boot 默认**不**读 `.env`（那是 docker compose 的约定），这行把它接进来。也可以直接 `export DEEPSEEK_API_KEY=...`。

`claw4j.model.mock` 是三态的：

| 值 | 行为 |
| --- | --- |
| 留空（默认） | 有 key 就用真实模型，没 key 自动降级 mock |
| `true` | 强制 mock，有 key 也不用（CI 与本机演示用这个） |
| `false` | 强制真实模型，没 key 直接启动失败 —— 用来发现"配置漏了"，而不是让它悄悄降级 |

**测试与 CI 全 mock，从不烧 token** —— CI 上不会因为没有 key 而变红。

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

## 亲手试一下

起应用后：

```bash
# 同步问答
curl -s -X POST http://localhost:8080/api/chat \
  -H 'Content-Type: application/json' \
  -H 'X-Claw4j-User: ethan' -H 'X-Claw4j-Role: admin' \
  -d '{"text":"上周华东区成交额多少"}'
# {"chatId":"http:ethan","text":"[mock] 上周华东区成交额多少"}

# 流式问答（SSE，事件名 chunk / done）
curl -N -X POST http://localhost:8080/api/chat/stream \
  -H 'Content-Type: application/json' -H 'X-Claw4j-User: ethan' \
  -d '{"text":"上周华东区成交额多少"}'

# 匿名请求进不来
curl -s -w ' [%{http_code}]' -X POST http://localhost:8080/api/chat \
  -H 'Content-Type: application/json' -d '{"text":"你好"}'
# {"error":"缺少 X-Claw4j-User 请求头"} [401]
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

重试上限用框架自带的 `ToolCallLimits` 收紧到**每工具 3 次、整轮 6 次**
（默认 40/150 是为分页取数设计的，放在自纠错上就是一次提问烧几百次往返）。
超限的行为是 `THROW`：`ToolCallingAdvisor` 接住后直接变成一句回答给客户端，
不再回头问模型——否则"超限"会被当成工具结果喂回模型，它还会再试一次。

这里有个**静默失效**的坑值得单独记：`spring.ai.tools.limits.*` 配置到不了我们这条链。
`ChatClient` 是我们自己建客户端的，五参重载里传进去的 `ToolCallingManager` 才是生效的那个，
自动配置那个只有在使用 Boot 注入的 `ChatClient.Builder` bean 时才会被用上。
哪天改成注入 bean，这两个数字会静默退回 40/150，所以有单测钉着。

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

四条检查层层收紧，都不靠口头约定：

| 门禁 | 拦什么 | 什么时候跑 |
| --- | --- | --- |
| `./mvnw verify` | 编译、171 个测试、打包 | CI 每次 push/PR；本地随时 |
| ArchUnit 测试 | domain 不依赖框架、agent 与 channel 互不依赖、通道 SPI 平台中立、飞书 SDK 只在 feishu 包、**Advisor 必须双实现 Call + Stream** | `mvn test`，以及提交前 |
| maven-enforcer | JDK/Maven 版本基线；domain 的 POM 里出现 Spring / Spring AI / 厂商 SDK / Reactor 直接失败 | `validate` 阶段，加错依赖当场红 |
| pre-commit hook | 改了 `claw4j-*` 下的 java/xml 就跑一遍架构规则（秒级） | 每次 `git commit` |

启用 hook（一次）：

```bash
git config core.hooksPath .githooks
```

CI 上同样只跑 `./mvnw -B -ntp verify`，另外有一条检查专门拦住"有人把 `legacy` 加回根 POM 的 modules"。

**为什么 Advisor 那条值得单独立规则**：`BaseAdvisorChain` 给流式路径取的是 `getStreamAdvisors()`，只认 `StreamAdvisor`。只写 `CallAdvisor` 的鉴权 Advisor 在 `.stream()` 上**既不报错也不生效**，客户端照常拿到回答——鉴权等于没做，而且从日志上看不出来。这种"能编译、能跑、但悄悄不生效"的缺陷只能靠机器拦（ADR-0007）。

## legacy/ 是什么

1.x 的冻结代码：Spring Boot 3.3.4 / Spring AI 1.0.0-M6，40 个类 / 6793 行 / 零测试，根包名 `com.xteammors.openclaw`。**不参与任何构建，不再修改**，只作为 before/after 对照保留，冻结位置是 `v1.x-legacy` tag。

它里面那些"教科书级反例"（字符串协议的工具调用、把 shell 交给模型、反射 hack 私有字段的向量库）是这次重建的动机，详见 [legacy/README.md](legacy/README.md)。新代码的包名是 `io.github.claw4j`，与它不共用任何类名。

## 文档

- [`docs/adr/`](docs/adr/) —— 10 条架构决策记录，每条都写了 Considered Options 与被否决的原因
- [`GLOSSARY.md`](GLOSSARY.md) —— 术语表

## License

见 [LICENSE](LICENSE)。
