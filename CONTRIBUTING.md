# 贡献指南

写给要改代码的人（人和 AI 都算）。README 回答"这是什么、怎么用"，这一份回答
"改之前要准备什么、改之后怎么证明没改坏"。

## 前置

| 依赖 | 版本 | 说明 |
| --- | --- | --- |
| JDK | 21 | Spring Boot 4 的底线。非交互 shell 不 source `~/.zshrc`，所以 `JAVA_HOME` 常常是空的 |
| Maven | 3.9+ | 仓库自带 wrapper，一律用 `./mvnw`，不装全局 Maven |
| Docker | — | 数据库测试用 Testcontainers 起一次性 Postgres（pgvector） |
| `DEEPSEEK_API_KEY` | — | 见下一节。没有它测试和启动都是**红**，不是跳过 |

这四个版本是架构决策而不是随手选的，改它们要在 ADR 里留痕（ADR-0001）。

## 一条命令

```bash
./scripts/verify.sh
```

它先载入 `.env`，再跑 `./mvnw -o clean verify`（编译、全部测试、JaCoCo 行覆盖率 60% 红线），
最后核对三个数：用例全过、**跳过数必须为 0**、每模块文件数与每文件方法数不超上限。

为什么要包一层而不是直接敲 `./mvnw verify`：跑全量其实需要两句咒语——

1. **Maven 不读 `.env`**，key 得先 export（缺 key 是红不是跳过，省了这一步就是
   "没连真模型却以为连了"）。
2. 非交互 shell 里 `JAVA_HOME` 常常是空的，`./mvnw` 直接失败。

这两句只活在操作过这台机器的人的脑子里，新会话、子代理、换机器都会重新踩一遍。

手动分步时：

```bash
set -a && . ./.env && set +a     # 把 key 导进当前 shell
export JAVA_HOME=~/soft/jdk-21.jdk/Contents/Home
./mvnw -o clean verify           # 必须 clean：旧报告会让计数算上上一代
```

**只跑一个模块时先 `install` 一次。** `./mvnw -o -pl claw4j-agent test` 读的是本地仓库里的
兄弟模块 jar，那些 jar 是上次 `install` 时的——所以单模块测试不加 `-am` 就不可信，
加了 `-am` 又每次都重编 core / capability（占掉一半墙钟时间）。
先跑一次 `./mvnw -o -q -DskipTests install`，之后单模块测试就可以不带 `-am`。
`./scripts/verify.sh` 走的是 `clean verify`，不受这条影响。

## 凭据

真值一律走环境变量，不写进仓库（ADR-0002、ADR-0006）。仓库根目录放一个 `.env`（已被忽略）：

```bash
DEEPSEEK_API_KEY=sk-xxxxxxxx
# QWEN_API_KEY=sk-xxxxxxxx   # 备用模型
CLAW4J_POSTGRES_IMAGE=docker.m.daocloud.io/pgvector/pgvector:pg16   # 国内拉不到官方镜像时
```

**配置里写的是句柄，不是 key**（P08）：`claw4j.model.deepseek.api-key: secret:deepseek_api_key`
——句柄名是环境变量名的小写写法，真值在发请求那一刻才被换出来，于是配置、日志、审计、
模型上下文里只有名字。写了句柄却没有对应变量，启动就失败，报错只说名字。
`env` / `headers` 里也一样；非凭据常量用 `plain:` 前缀。

`application-local.yml` 里那行 `spring.config.import: optional:file:./.env[.properties],optional:file:../.env[.properties]`
是把 `.env` 接进 Boot 的原因——Spring Boot 默认**不**读 `.env`，那是 docker compose 的约定。
两个路径是为了命令行（工作目录是仓库根目录）与 IDEA（工作目录是 `claw4j-app` 模块）都能找到。

IDEA 里跑：Run Configuration 的 **Active profiles** 填 `local`，工作目录设成仓库根目录
（`compose.yaml` 在那）；不起 Docker 就只在 **Environment variables** 里填 key。

**两家模型怎么切**：`claw4j.model.primary` 决定主模型（`deepseek` / `qwen`），另一家是备，
装配出来的 `RoutingChatModel` 才是业务看到的那个（`@Primary`）。降级规则见
[踩过的坑](docs/design/pitfalls.md) 前两条——换供应商时工具会丢、模型内部重试会把降级拖成分钟级。

## 数据库

| | 谁在用 | 怎么起 | 数据 |
| --- | --- | --- | --- |
| compose 常驻 | 本地开发、真模型联调 | `docker compose up -d`，或 `local` profile 让 Boot 拉 | 会攒数据，改 schema 靠 Flyway 叠加版本 |
| Testcontainers | `./mvnw verify` 里的数据库测试 | 每个测试类起一个，跑完销毁 | 每次都是空的，只有种子 |

不给测试复用常驻库，是因为常驻库会串数据：一个测试写进去的东西让下一个测试变红或变绿，
排查时看到的是"刚才明明能跑"。

本地起带库的应用：

```bash
cp claw4j-app/src/main/resources/application-local.yml.example \
   claw4j-app/src/main/resources/application-local.yml
./mvnw -pl claw4j-app spring-boot:run -Dspring-boot.run.profiles=local
```

`application-local.yml` 被 `.gitignore` 忽略（它可能放真实 key），仓库里只有 `.example` 模板。

### 迁移与种子

迁移脚本按**归属**分散在各模块自己的 `src/main/resources/db/migration/` 下：

| 版本 | 在哪 | 建什么 | 谁在用 |
| --- | --- | --- | --- |
| `V1` `V2` | `claw4j-example-sales` | 业务表与种子（`business`） | 示例插件自己的工具 |
| `V3` | `claw4j-agent` | 会话三张表（`claw4j_session`）：`ai_session`、`ai_session_event`、`session_entry` | `agent.session` / `agent.goal` |
| `V5` | `claw4j-agent` | 知识库的文件账 `source` 与索引身份 `index_meta`（`claw4j_rag`；块与向量在框架建的 `vector_store` 里）+ `vector` / `pg_trgm` 扩展 | `agent.rag` |
| `V6` | `claw4j-agent` | 审批 / 定时任务 / 审计（`claw4j_run`） | `agent.approval` / `agent.schedule` / `agent.audit` |
| `V7` | `claw4j-agent` | 入站队列（`claw4j_channel`） | `channel.kernel` / `agent.inbound` |

**没有 `V4`，不是漏了。** 它原来是语义缓存（`claw4j_cache`），2026-11-09 缓存改落 Redis Stack
之后整张表连同 schema 一起删了。**编号不复用**——与 ADR 编号同一个口径，空出来的号不挪给别的表。
同理，`V8` / `V9` / `V10` 也已经并回上表，不再单独存在。

**这六份是基线，不是历史。** 两轮合并：

- 2026-11-09：把 `claw4j-agent` 侧 25 个迁移（V3..V27）按 schema 合成五份。那一轮的大半内容在
  反复改同一批表，而 `agent_run` / `agent_step` / `agent_run_delivery` 三张表与 `audit_trail` 视图
  最后整体删掉了。
- 2026-11-10：把随后新增的三份（`V8` 会话并表、`V9` 删缓存、`V10` 索引身份）并回各自的基线。
  于是目录里只剩四份 DDL，加示例插件两份。

合并只删历史、不改设计——11-09 那轮前后各 `pg_dump -s` 比过一次，结构差异 0。
代价：**已经跑过旧迁移的库要重建**（`V4`、`V8`..`V10` 在本地已经不存在，Flyway 校验会直接失败），
开发库 drop 之后重启一次即可。生产没有部署过这套基线之前的历史。

判据只有一条：**谁读这张表，DDL 就放在谁的模块里**。这三张框架表原先都在 `claw4j-capability`，
而那个模块装的是 mcp / script / skill / generator，一条 JDBC 都不碰——于是它被迫依赖整套
Flyway 与 Postgres 驱动（M6-04 搬走了）。

Flyway 扫遍 classpath 上所有 `db/migration` 再**按版本号排序**，所以文件在哪个 jar 里不影响
执行顺序，"只有一个入口"指的是应用启动时那一次迁移。代价是**版本号必须全局唯一**：
新增一个迁移就是挑当前最大号 +1，跟在哪个模块无关。

**不要回头改已提交的版本**——本地常驻库已经记过账了，改历史版本会让校验和不匹配，
而删库重建正是这套迁移要替你免掉的事。唯一一次例外是上面那次基线合并：那是**有意的重来**，
不是修历史——它把"改过很多次的形状"落成一份干净的 DDL，代价一次付清。

**注意**：Boot 4 把 Flyway 的自动配置拆成了独立 artifact，只放 `flyway-core` 时配置会静默不生效，
见 [踩过的坑](docs/design/pitfalls.md) 第三条。三条依赖（`flyway-core` /
`flyway-database-postgresql` / `spring-boot-flyway`）跟着迁移放在 `claw4j-agent`，
应用经依赖传递拿到。

种子里的日期是相对的（按"本周一"推算），所以每次重建的库里"上周"永远落在同一批订单上。
这个"相对"只在迁移那一刻求值：常驻库建好之后，它里面的"上周"是**建库那天**的上周，放久了照样会漂。
演示时发现数字对不上，重建一次库就好。

**成交额只算未取消的订单**——演示数据里上周华东有一单 50,000 是取消状态，它不该出现在任何答案里。

## 冒烟

门禁跑的是假模型与真容器，它能证明"装配成立"，证明不了"各层能力真能用"。后者要一次真跑：

```bash
set -a; . ./.env; set +a
java -jar claw4j-app/target/claw4j-app-0.1.0-SNAPSHOT.jar \
  --spring.profiles.active=local \
  --claw4j.scripts.directory=examples/scripts \
  --claw4j.skills.directory=examples/skills \
  --claw4j.subagents.directory=examples/subagents
```

**每个工具**问什么、该看到什么、判定的口径（哪些是确定数、哪些只能断言形状）、配错长什么样：
**[`docs/capability-tests.md`](docs/capability-tests.md)**。那份把仓库里的每一项能力一个一条列全了，
改动装配或能力层之后照它走一遍——它是"能力清单"，不是"设计说明"，加一个工具就该在里面多一条。
最省事的验法是照那份文档的表格逐行走一遍，再回读每个工具的计数。

三条最容易踩的写在这里，因为不看那份文档的人也会踩：

- **脚本层必须 `X-Claw4j-Role: admin`**：`run_script` 不在 `read-only` / `guest` 的动作表里，
  用默认角色问会拿到"没有权限"，容易误判成脚本层坏了。
- **判断"是不是被拒了"看日志与计数，不要看模型转述**。同一个会话里被拒过一次之后，模型可能照着那个句式
  编一条拒绝出来——2026-10-28 那次冒烟里它就编了个不存在的动作名（`load_skill`）。
- **不要问模型"你有哪些工具"来核对清单**：工具是渐进式披露的，它只看得到检索工具。
  清单以那份文档的表格为准，实际有没有被调起来看 `claw4j.tool.calls{tool=…}`。

## 质量门禁

几条闸层层收紧，都不靠口头约定：

| 门禁 | 拦什么 | 什么时候跑 |
| --- | --- | --- |
| `./mvnw verify` | 编译、测试、行覆盖率 ≥60%、打包 | 本地随时 |
| ArchUnit 测试 | domain 连 slf4j 都不依赖、agent 与 channel 互不依赖、通道 SPI 平台中立、`core` 的 channel 侧只依赖 JDK / slf4j / domain、飞书 SDK 只在 feishu 包、**Advisor 必须双实现 Call + Stream**、**Java 侧不起进程**（例外只有 `LocalProcessRunner` 一个类，ADR-0002） | `mvn test` |
| `ProcessBoundaryTest` | 主代码里没有手写的 shell 调用字面量：把命令行当字符串传下去时，起进程的不是本项目，上面那条 API 规则就看不见它 | `mvn test` |
| maven-enforcer | JDK/Maven 版本基线；`claw4j-core` 的 POM 里出现 Spring / Spring AI / 厂商 SDK / Reactor 直接失败 | `validate` 阶段 |
| `./scripts/verify.sh` | 一条命令跑完上面几步：载入 `.env` → `clean verify`（含覆盖率红线）→ 用例数与**跳过数必须为 0** → 文件数/方法数上限（≤10 / ≤20） | 提交前 |
| 本地冒烟 | 起应用带 `examples/{scripts,skills,subagents}`（三个示例 MCP server 已经写在 `application-local.yml` 里），真模型走一遍各层与越权。**命令、每层问什么、该看到什么，见 [`docs/capability-tests.md`](docs/capability-tests.md)** | 改了装配或能力层之后 |
| 要外部世界的两组（`real-cli` / `real-mcp`） | 真的 OS 子进程（Python、`gh`、`himalaya`）与真的公网 MCP 端点。**默认排除**，`-Preal-cli` 才跑；它们依赖本机装了什么与能不能出网，不属于回归基线 | 改 MCP 传输、改脚本层之后 |
| `./scripts/eval.sh` | **评测集**：五十来条真模型调用，判分锚在数字与标识符上（`sales` / `knowledge` / `tools` / `authz` / `memory` 五类）。回答"这版比上版好还是差"，而不是"接上了没有"。**默认不在 `verify.sh` 里**：一次十几分钟、几分钱、要联网。用例集写坏了与判分口径坏了仍然进回归（`EvalCaseSetTest` / `EvalScorerTest`） | 改了工具描述、换了模型、调了路由顺序之后，以及想说"这版更好"之前 |

**为什么评测单独一条路而不是并进 `verify.sh`**：它回答的不是同一个问题。`verify.sh` 守
"装配成立、跳过 0、覆盖够"，那是可以每次提交都跑的；评测守"答得好不好"，答案会随模型版本漂、
每条要 1~6 次往返。把两者绑在一起的结果只有一个——慢到没人提交前跑，
于是闸形同虚设。代价是它可能长期没人跑，所以用例集自洽那部分留在回归里垫底。

**评测红了先看报告，别先改代码**：每份报告里带着那条用例的 `answer` 原文与 `runId`。
判分是字符串包含，假红与"模型抖了一次"都真实存在（温度 0.0 也不保证确定），
所以红了要做的是读那句回答，判断这是判据写窄了、模型退步了，还是它这次恰好换了说法。

**为什么 Advisor 那条值得单独立规则**：`BaseAdvisorChain` 给流式路径取的是 `getStreamAdvisors()`，
只认 `StreamAdvisor`。只写 `CallAdvisor` 的鉴权 Advisor 在 `.stream()` 上**既不报错也不生效**，
客户端照常拿到回答——鉴权等于没做，而且从日志上看不出来（ADR-0007）。

门禁全在本地跑：仓库没有接 CI，也没有 pre-commit hook。`./scripts/verify.sh` 是提交前的唯一入口，
它红了就是不能提交。评测走 `./scripts/eval.sh`，那是**并列**的另一条路（不进 `verify.sh`，
理由见上面那行）。接 CI 时有两件事得先备好：runner 上的 Docker 守护进程（数据库那一类没有
Docker 就直接红，不许判成跳过），以及 `secrets.DEEPSEEK_API_KEY`（ADR-0011：没有 key 是红，不是跳过）。

## 写测试之前

先看 [`docs/agents/testing.md`](docs/agents/testing.md)——那份是本仓库的测试准则，
核心是"以功能为单位，不以类为单位"。几句话的版本：

- 一个功能一份测试，从真实入口进、断言末端结果；每模块 ≤10 个文件，每文件 ≤20 个方法。
- 上限不是目标，行覆盖率 60% 是**下限**也不是目标——破了去补那条链路，不是补 getter 的断言。
- 不许 `@Disabled`、不许靠 skip 过关：缺 key 就把那条删掉，留灰的等于没验。
- `@Tag` 只有一种用法是允许的——把"该单独跑的那一类"标出来由 `verify.sh` 排除，现在只有
  `eval` 这一组（见上表）。拿它去排除装配类用例等于给闸开一个口子：报表里不会出现，
  也就没人看得出它没跑。这也是为什么排除要写在父 POM 的 `excludedGroups` 里，
  而不是让某个人在测试上加个标签自己心里有数。

## 文档放在哪

| 要找的东西 | 在哪 |
| --- | --- |
| 项目简介、怎么用、怎么集成 | [`README.md`](README.md) |
| 某个能力怎么验、期望值是什么 | [`docs/capability-tests.md`](docs/capability-tests.md) |
| 架构决策记录（为什么这样，否决了什么） | [`docs/adr/`](docs/adr/) |
| 设计说明（搬到这儿是为了不让 README 变长） | [`docs/design/`](docs/design/) |
| 踩过的坑（代码里看不出来，只会再犯一次） | [`docs/design/pitfalls.md`](docs/design/pitfalls.md) |
| 术语表 | [`GLOSSARY.md`](GLOSSARY.md) |
| 给 AI 助理的仓库约定 | [`AGENTS.md`](AGENTS.md) |

`legacy/` 是 1.x 的冻结代码（Spring Boot 3.3.4 / Spring AI 1.0.0-M6，根包名 `com.xteammors.openclaw`），
不参与构建、不再修改，只作为 before/after 对照，冻结位置是 `v1.x-legacy` tag。
详见 [`legacy/README.md`](legacy/README.md)。
