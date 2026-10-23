# Claw4J

[![CI](https://github.com/milanyangbo/Claw4J/actions/workflows/ci.yml/badge.svg)](https://github.com/milanyangbo/Claw4J/actions/workflows/ci.yml)

企业 IM 上的业务数据助手。主场景是 **Text-to-SQL 销售域问答**：业务同学在群里问"上周华东区成交额多少"，Agent 自己查库、自己答。

整个项目是 **基于 Spring AI 2.x 从零重建** 的（1.x 版本已冻结进 `legacy/`，见 [legacy/README.md](legacy/README.md)）。重建的全部设计决策都留了 ADR，见 [`docs/adr/`](docs/adr/)。

> **当前进度：M0 骨架完成。** HTTP+SSE 通道端到端打通、模型按 key 有无自动装配、架构边界由机器校验。M1 接工具与鉴权，M2 接飞书长连接与 Skill 引擎。

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
./mvnw verify                        # 一条命令：构建 + 架构检查 + 81 个测试
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
CLAW4J_POSTGRES_IMAGE=你的镜像 ./mvnw -pl claw4j-app spring-boot:run
```

`application-local.yml` 被 `.gitignore` 忽略（它可能放真实 key），仓库里只有 `.example` 模板。

### 两套库：常驻的给你看，一次性的给测试跑

| | 谁在用 | 怎么起 | 数据 |
| --- | --- | --- | --- |
| compose 常驻 | 本地开发、真模型联调 | `docker compose up -d`，或 `local` profile 让 Boot 拉 | 会攒数据，改 schema 靠 Flyway 叠加版本 |
| Testcontainers | `./mvnw verify` 里的数据库测试 | 每个测试类起一个，跑完销毁 | 每次都是空的，只有种子 |

不给测试复用常驻库，是因为常驻库会串数据：一个测试写进去的东西让下一个测试变红或变绿，
排查时看到的是"刚才明明能跑"。一次性实例慢一些，但每次都是确定的。

业务 schema 与种子在 `claw4j-tools/src/main/resources/db/migration/`（`V1` 建表、`V2` 放种子），
由 Flyway 执行。**改字段是加一个 `V3__xxx.sql`，不要回头改已提交的版本** —— 本地常驻库已经记过账了，
改历史版本会让它的校验和不匹配，而删库重建正是这套迁移要替你免掉的事。

种子里的日期是相对的（按"本周一"推算），所以"上周"在任何一天跑都落在同一批订单上：

| 问法 | 答案 |
| --- | --- |
| 上周华东区成交额 | 128,400.00 |
| 上周华南区成交额 | 26,000.00 |
| 本周华东区成交额 | 99,999.00 |

**成交额只算未取消的订单**：上周华东还有一单 50,000 是取消状态，它不该出现在任何答案里。
这三个数就是 05 号票"问一句话拿到数"的判据——两处必须是同一个数，否则会出现
"库里算的是 A、Agent 答的是 B"这种谁都没错的假绿。

数据库测试需要本机有 Postgres 镜像。拉不到 Docker Hub 时用 `CLAW4J_POSTGRES_IMAGE`
指一个本地已有的（跟 compose 用的是同一个变量），否则整类跳过并往 stderr 打一行提示——
别把"跳过"当成"通过"。

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

## 质量门禁

四条检查层层收紧，都不靠口头约定：

| 门禁 | 拦什么 | 什么时候跑 |
| --- | --- | --- |
| `./mvnw verify` | 编译、81 个测试、打包 | CI 每次 push/PR；本地随时 |
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
