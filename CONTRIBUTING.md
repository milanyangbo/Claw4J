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
./scripts/verify-local.sh
```

它串了三道闸：测试文件配额 → `clean verify`（含 JaCoCo 行覆盖率红线）→ 跳过数必须为 0。

为什么要包一层而不是直接敲 `./mvnw verify`：跑全量其实需要两句咒语——

1. **Maven 不读 `.env`**，key 得先 export（缺 key 是红不是跳过，省了这一步就是
   "没连真模型却以为连了"）。
2. 非交互 shell 里 `JAVA_HOME` 常常是空的，`./mvnw` 直接失败。

这两句只活在操作过这台机器的人的脑子里，新会话、子代理、换机器都会重新踩一遍。

手动分步时：

```bash
set -a && . ./.env && set +a     # 把 key 导进当前 shell
export JAVA_HOME=~/soft/jdk-21.jdk/Contents/Home
./mvnw clean verify              # 必须 clean：旧 surefire 报告会让后面的计数算上上一代
./scripts/assert-no-skipped-tests.sh
```

## 凭据

一律走环境变量，不写进仓库（ADR-0002、ADR-0006）。仓库根目录放一个 `.env`（已被忽略）：

```bash
DEEPSEEK_API_KEY=sk-xxxxxxxx
# QWEN_API_KEY=sk-xxxxxxxx   # 备用模型
CLAW4J_POSTGRES_IMAGE=docker.m.daocloud.io/pgvector/pgvector:pg16   # 国内拉不到官方镜像时
```

`application-local.yml` 里那行 `spring.config.import: optional:file:.env[.properties]` 是把
`.env` 接进 Boot 的原因——Spring Boot 默认**不**读 `.env`，那是 docker compose 的约定。

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

业务 schema 与种子在 `claw4j-capability/src/main/resources/db/migration/`
（`V1` 建表、`V2` 放种子、`V3` 会话表），由 Flyway 执行。

**改字段是加一个 `V4__xxx.sql`，不要回头改已提交的版本**——本地常驻库已经记过账了，
改历史版本会让校验和不匹配，而删库重建正是这套迁移要替你免掉的事。

**注意**：Boot 4 把 Flyway 的自动配置拆成了独立 artifact，只放 `flyway-core` 时配置会静默不生效，
见 [踩过的坑](docs/design/pitfalls.md) 第三条。

种子里的日期是相对的（按"本周一"推算），所以每次重建的库里"上周"永远落在同一批订单上。
这个"相对"只在迁移那一刻求值：常驻库建好之后，它里面的"上周"是**建库那天**的上周，放久了照样会漂。
演示时发现数字对不上，重建一次库就好。

**成交额只算未取消的订单**——演示数据里上周华东有一单 50,000 是取消状态，它不该出现在任何答案里。

## 质量门禁

几条闸层层收紧，都不靠口头约定：

| 门禁 | 拦什么 | 什么时候跑 |
| --- | --- | --- |
| `./mvnw verify` | 编译、测试、行覆盖率 ≥60%、打包 | 本地随时 |
| ArchUnit 测试 | domain 不依赖框架、agent 与 channel 互不依赖、通道 SPI 平台中立、飞书 SDK 只在 feishu 包、**Advisor 必须双实现 Call + Stream**、**Java 侧不起进程**（例外只有 `LocalProcessRunner` 一个类，ADR-0002） | `mvn test`，以及提交前 |
| `NoHandBuiltShellCommandTest` | 主代码里没有手写的 shell 调用字面量：把命令行当字符串传下去时，起进程的不是本项目，上面那条 API 规则就看不见它 | `mvn test` |
| maven-enforcer | JDK/Maven 版本基线；domain 的 POM 里出现 Spring / Spring AI / 厂商 SDK / Reactor 直接失败 | `validate` 阶段 |
| `assert-test-quota.sh` | 每模块测试文件 ≤10、每文件测试方法 ≤20 | `verify-local.sh` 第一步 |
| `assert-no-skipped-tests.sh` | **有测试被跳过就红** | `verify` 之后 |
| pre-commit hook | 改了 `claw4j-*` 下的 java/xml 就跑一遍架构规则（秒级） | 每次 `git commit` |
| 本地冒烟 | 起应用带 `examples/{scripts,skills,subagents}` + `mcp-demo`，真模型走一遍三层与越权 | 改了装配或能力层之后 |

启用 hook（一次）：

```bash
git config core.hooksPath .githooks
```

**为什么 Advisor 那条值得单独立规则**：`BaseAdvisorChain` 给流式路径取的是 `getStreamAdvisors()`，
只认 `StreamAdvisor`。只写 `CallAdvisor` 的鉴权 Advisor 在 `.stream()` 上**既不报错也不生效**，
客户端照常拿到回答——鉴权等于没做，而且从日志上看不出来（ADR-0007）。

### CI 暂未接入

`.github/workflows/ci.yml` 先留在仓库里当接线图（`verify` + 跳过闸 +
"legacy 不得回到根 POM 的 modules"），但流水线并没有在跑。在那之前门禁全在本地。
接 CI 时有两件事得先备好：runner 上的 Docker 守护进程（数据库那一类没有 Docker 就直接红，
不许判成跳过），以及 `secrets.DEEPSEEK_API_KEY`（ADR-0011：没有 key 是红，不是跳过）。

## 写测试之前

先看 [`docs/agents/testing.md`](docs/agents/testing.md)——那份是本仓库的测试准则，
核心是"以功能为单位，不以类为单位"。几句话的版本：

- 一个功能一份测试，从真实入口进、断言末端结果；每模块 ≤10 个文件，每文件 ≤20 个方法。
- 上限不是目标，行覆盖率 60% 是**下限**也不是目标——破了去补那条链路，不是补 getter 的断言。
- 不许 `@Disabled`、不许 tag 排除、不许靠 skip 过关：缺 key 就把那条删掉，留灰的等于没验。

## 文档放在哪

| 要找的东西 | 在哪 |
| --- | --- |
| 项目简介与用法 | [`README.md`](README.md) |
| 架构决策记录（为什么这样，否决了什么） | [`docs/adr/`](docs/adr/) |
| 设计说明（搬到这儿是为了不让 README 变长） | [`docs/design/`](docs/design/) |
| 踩过的坑（代码里看不出来，只会再犯一次） | [`docs/design/pitfalls.md`](docs/design/pitfalls.md) |
| 术语表 | [`GLOSSARY.md`](GLOSSARY.md) |
| 给 AI 助理的仓库约定 | [`AGENTS.md`](AGENTS.md) |

`legacy/` 是 1.x 的冻结代码（Spring Boot 3.3.4 / Spring AI 1.0.0-M6，根包名 `com.xteammors.openclaw`），
不参与构建、不再修改，只作为 before/after 对照，冻结位置是 `v1.x-legacy` tag。
详见 [`legacy/README.md`](legacy/README.md)。
