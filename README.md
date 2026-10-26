# Claw4J

![License: MIT](https://img.shields.io/badge/License-MIT-green.svg)
![JDK](https://img.shields.io/badge/JDK-21-blue.svg)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.1.2-brightgreen.svg)
![Spring AI](https://img.shields.io/badge/Spring%20AI-2.0.2-brightgreen.svg)

企业 IM 上的**业务数据助手**。业务同学在群里问一句"上周华东区成交额多少"，Agent 自己写 SQL、自己查库、自己回答。

## 这是什么

它是一套跑在 Spring AI 2.x 上的 Agent 骨架，落点很具体：**让一句自然语言问句变成一条能在真实业务库上跑出来的 SQL**，并且回答里的数字和库里算出来的是同一个。

这件事难的不是"用模型写 SQL"，而是**那条 SQL 凭什么能跑**：能不能问他要的那张表、跑的时候会不会
改动数据、查到了能不能原样说出去。这三件事由**调用者的身份**决定，不写在提示词里——
模型说得再好听，也拿不到它没权限的数据。

它不是另一套聊天机器人脚手架，也不做登录体系：认证由网关在边缘完成，结果写成请求头传进来。

## 特性

- **Text-to-SQL** —— 模型写 SQL，工具只读执行，结果由模型组织成回答
- **身份决定权限** —— 角色到"可见表 × 可执行动作"的映射在配置里，改权限不发版
- **两层只读约束** —— SQL 静态校验拦一层，数据库只读事务兜一层，漏一层数据也不会没
- **能力三层** —— Java 工具、跨进程 MCP 工具、脚本包，对 Agent 呈现为同一件事
- **脚本沙箱** —— 脚本本体不进上下文，出来的只有它的标准输出，且不越出包目录
- **子 Agent 委派** —— 派出去的子任务是独立会话和独立预算，只把结论带回来，且不提权
- **会话记忆** —— 落 Postgres，换条通道进来还是同一个人
- **同步与流式同源** —— Advisor 必须同时实现 Call 和 Stream 两个接口，只写一个会在流式路径上静默不生效
- **架构边界由测试守护** —— 谁能依赖谁写在 ArchUnit 规则里，加错依赖当场红

## 演示

```text
$ curl -s -X POST localhost:8080/api/chat \
    -H 'Content-Type: application/json' -H 'X-Claw4j-User: ethan' -H 'X-Claw4j-Role: admin' \
    -d '{"text":"上周华东区成交额多少"}'
{"chatId":"http:ethan","text":"**上周华东区成交额：￥128,400.00**｜计算标准：按订单所属区域统计 sum(order_item.amount)，已排除状态为 CANCELLED 的订单；时间窗口为上周一 00:00 到本周一 00:00。"}

$ # 换个角色头问同一句话
$ curl -s -X POST localhost:8080/api/chat \
    -H 'Content-Type: application/json' -H 'X-Claw4j-User: guest01' -H 'X-Claw4j-Role: guest' \
    -d '{"text":"上周华东区成交额多少"}'
{"chatId":"http:guest01","text":"没有权限：角色 guest 不允许访问表 customer、order_item、sales_order（工具 querySales）。"}

$ # 第二轮什么都不复述
$ curl -s -X POST localhost:8080/api/chat \
    -H 'Content-Type: application/json' -H 'X-Claw4j-User: ethan' -H 'X-Claw4j-Role: admin' \
    -d '{"text":"那华南呢"}'
{"chatId":"http:ethan","text":"我查了一下，华南区上周成交额是 **￥26,000.00**。"}
```

## 快速开始

前置：**JDK 21**、**Docker**（数据库）、一个 `DEEPSEEK_API_KEY`。

```bash
git clone git@github.com:milanyangbo/Claw4J.git && cd Claw4J

echo 'DEEPSEEK_API_KEY=sk-xxxxxxxx' > .env          # 也可以直接 export

cp claw4j-app/src/main/resources/application-local.yml.example \
   claw4j-app/src/main/resources/application-local.yml
# application-local.yml 被 .gitignore 忽略（它可能放真实 key），仓库里只有模板

./mvnw -pl claw4j-app spring-boot:run -Dspring-boot.run.profiles=local
# local profile 会按 compose.yaml 拉起 Postgres(pgvector)，退出时回收；
# 它会找仓库根目录的 compose.yaml，所以要在根目录启动
```

起来就问：

```bash
curl -s -X POST http://localhost:8080/api/chat \
  -H 'Content-Type: application/json' -H 'X-Claw4j-User: ethan' -H 'X-Claw4j-Role: admin' \
  -d '{"text":"上周华东区成交额多少"}'
```

国内拉不到官方镜像时用 `CLAW4J_POSTGRES_IMAGE=docker.m.daocloud.io/pgvector/pgvector:pg16` 换源。

> 没有 key 应用**起不来**，不降级：留着 mock 兜底就会出现"本地跑着回声模型却以为在跑真模型"这种更贵的困惑。

## 配置

凭据一律走环境变量。仓库根目录放一个 `.env`（已被忽略），或直接 `export`：

| 变量 | 必填 | 说明 |
| --- | --- | --- |
| `DEEPSEEK_API_KEY` | 是 | 主模型 key，缺失即启动失败 |
| `QWEN_API_KEY` | 否 | 备用模型 key，配了才有主备降级 |
| `CLAW4J_MODEL_PRIMARY` | 否 | `deepseek`（默认）/ `qwen`，决定谁是主模型 |
| `CLAW4J_POSTGRES_IMAGE` | 否 | 换 Postgres 镜像源，与 compose 共用同一个变量 |
| `FEISHU_APP_ID` / `FEISHU_APP_SECRET` | 否 | 飞书自建应用凭据。两个都配才装配长连接通道，缺一个整个通道退让、HTTP 照常；打包成 fat jar 时要带 `-Pchannel-feishu`（默认包里没有 `oapi-sdk`） |

角色到权限的映射在 `claw4j.channel.http.roles` 里配置；模型主备顺序启动时打在日志里。

## 用法

| 接口 | 方法 | 说明 |
| --- | --- | --- |
| `/api/chat` | POST | 同步问答，返回 JSON |
| `/api/chat/stream` | POST | 流式问答，SSE，事件名 `chunk` / `done` |

请求体：`{"text":"..."}`。请求头：

| 头 | 必填 | 缺省 |
| --- | --- | --- |
| `X-Claw4j-User` | 是（缺省返回 401） | — |
| `X-Claw4j-Role` | 否 | `read-only` |
| `X-Claw4j-Chat-Id` | 否 | 回落到用户标识 |

流式：

```bash
curl -N -X POST http://localhost:8080/api/chat/stream \
  -H 'Content-Type: application/json' -H 'X-Claw4j-User: ethan' \
  -d '{"text":"上周华北区成交额多少"}'
```
```text
event:chunk
data:上周华北区成交额：￥31,500.00**
...
event:done
data:
```

## 架构

```
     channel ──► domain ◄── agent
                    ▲          ▲
                    │          │
                    └─ capability ─┘
                    ▲
                    │
                   app（唯一知道所有模块的地方，负责接线）
```

| 模块 | 职责 |
| --- | --- |
| `claw4j-domain` | 领域模型与端口。**零框架依赖**，唯一被承诺能脱离框架复用的模块 |
| `claw4j-agent` | ChatClient 装配、模型路由、Advisor 链。不认识任何通道 |
| `claw4j-channel` | 入站出站适配（HTTP+SSE、飞书长连接）。按平台分包，不按平台分模块 |
| `claw4j-capability` | 工具、脚本包、技能包、MCP 接入——模型能动手的那部分 |
| `claw4j-app` | 启动与装配，唯一打 fat jar 的模块 |

两条最关键的方向：`channel → domain ← agent`，两边互不依赖；接线只发生在 `app`。
加一个通道不需要碰 agent，换一个 Agent 实现不需要碰 channel。

## 目录结构

```text
claw4j-{domain,agent,channel,capability,app}/   五个 Maven 模块
docs/adr/            架构决策记录：为什么这样，否决了什么
docs/design/         设计说明：能力三层、鉴权、Text-to-SQL、会话记忆
docs/agents/         给 AI 助理的仓库约定
examples/            可直接运行的能力示例：scripts / skills / subagents / mcp
scripts/             本地门禁脚本
legacy/              1.x 冻结代码，不参与构建
CONTRIBUTING.md      怎么跑验证、门禁有哪些
GLOSSARY.md          术语表
```

## 示例

`examples/` 下四份都是能直接挂上跑的：[`examples/README.md`](examples/README.md)

- `scripts/` —— 脚本包：脚本跑在受限子进程里，进入上下文的只有它的标准输出
- `skills/` —— 技能包：渐进式披露，命中才加载全文
- `subagents/` —— 子 Agent 定义
- `mcp/` —— 一个真的跨进程 Python server，走 MCP 协议

## 文档

- [`docs/adr/`](docs/adr/) —— 架构决策记录，每条都写了 Considered Options 与被否决的原因
- [`CONTRIBUTING.md`](CONTRIBUTING.md) —— 本地怎么跑验证、门禁有哪些、迁移怎么加
- [`GLOSSARY.md`](GLOSSARY.md) —— 术语表

## License

[MIT](LICENSE) © 2024 OpenClaw4J
