Status: ready-for-agent

# M1：业务数据闭环 —— 能在 IM 里问"上周华东区成交额"并拿到数

## Problem Statement

M0 结束时能演示的是"问一句话、拿一句回答"。这个回答要么来自 mock 模型的回声，要么来自模型自己编的——**它不知道公司库里有什么表，也没有任何边界**。企业员工真正要问的是"上周华东区成交额多少"这种数，而数在业务库里。

差距有四处，每一处都单独足以让演示停在"看起来很聪明但干不了活"：

1. **没有业务数据与 schema**，模型无从查起，Text-to-SQL 无从生成。
2. **没有工具**：Agent 拿不到库里的数，只能编。
3. **没有边界**：谁都能问、问出什么都往外说。ADR-0007 已经把 Advisor 链鉴权与 PII 脱敏 Advisor 写成决策，但**两个 Advisor 一个都没落地**——目前链上是空的。
4. **没有记忆**：换一轮就失忆，"那华南呢"接不上"上周华东区成交额"的口径，只能把背景重讲一遍。

面试视角下这是分水岭：M0 证明了"能跑"，M1 才证明"能干业务且不出事"。只有 Advisor 链而没有真实的鉴权与脱敏场景，链只是一个空壳——被追问"你的 Advisor 到底拦住了什么"时答不上来。反之，鉴权与脱敏是《数据安全法》与等保三级那类问题最自然的落点，也是这个项目唯一能把"合规"讲成代码而不是口号的地方。

## Solution

在既有骨架上补四件事，HTTP+SSE 通道与 `AgentPort` 接缝都不动：

- **业务 schema 与种子**：销售域（区域 / 客户 / 订单 / 订单项），种子数据可复现，让"上周华东区成交额"有一个能算出来的确定答案。
- **Text-to-SQL 的 `@Tool`**：落在 `claw4j-tools`（当前是空模块，M1 首次落 Java 代码），只读访问业务库，生成 SQL 后执行并把结果交回模型组织语言。
- **Advisor 链上的鉴权与 PII 脱敏**：两个 Advisor 都双接口实现，鉴权的 order 排在工具调用循环内部以实现每次迭代都校验。
- **会话记忆**：`spring-ai-session` JDBC，同库不同 schema，让多轮追问接得上。

"上周华东区成交额"这条路径的形状：

```
入站（含 Principal）
  → 鉴权 Advisor：这个角色能不能查这张表
  → 模型决定调 querySales 工具
  → Text-to-SQL：生成 SQL → 只读执行 → 拿回行
  → PII 脱敏 Advisor：输出里的手机号/身份证掩码
  → 出站
```

会话记忆让"那华南呢"能继承上一轮的"上周"与"成交额"口径，而不是重新开始一轮。

## User Stories

### 企业员工

1. As a 企业员工，我想要问"上周华东区成交额多少"拿到一个具体的数，so that 我不用找数据分析师排队
2. As a 企业员工，我想要追问"那华南呢"时不用把"上周""成交额"重讲一遍，so that 多轮追问是顺的
3. As a 企业员工，我想要我没有权限的表问出来的结果是明确的"没权限"，so that 我不会把"查不到"误读成"数据是零"
4. As a 企业员工，我想要回答里出现的手机号、身份证号是掩码后的，so that 我在 IM 里问数不会变成泄露同事信息的口子
5. As a 企业员工，我想要换个设备或换个通道进来时上下文仍然连续，so that 我不用把背景重讲一遍
6. As a 企业员工，我想要问业务问题时不用关心背后是 SQL 还是脚本，so that 我只关心拿到的数对不对

### 平台维护者

7. As a 平台维护者，我想要鉴权在工具调用循环的**每一次迭代**上都生效，so that 模型没法靠"第二轮再调一次"绕过第一轮的校验
8. As a 平台维护者，我想要鉴权与脱敏两个 Advisor 在流式路径上同样生效，so that 不会出现"同步拦住了、SSE 漏了"这种只在生产暴露的洞
9. As a 平台维护者，我想要 Agent 对业务库的访问是只读的，so that DDL 与 DML 不可能通过一条提问发生
10. As a 平台维护者，我想要会话存在 Postgres 里而不是内存里，so that 多实例部署不会失忆
11. As a 平台维护者，我想要集成测试跑在干净的数据库实例上，so that 上一个测试留下的数据不会干扰下一个
12. As a 平台维护者，我想要业务工具与 Agent 的装配边界由编译期约束，so that 不会出现工具直接依赖 Agent 内部的反向依赖

### 安全视角

13. As a 关注安全的人，我想要一次工具调用能不能发生由 Principal 决定而不是由模型决定，so that 模型被提示词注入时越不过权限边界
14. As a 关注安全的人，我想要脱敏发生在出站之前且原始值不落日志，so that "脱敏了"不是只在响应里脱敏、日志里仍是明文
15. As a 关注安全的人，我想要 Agent 拿不到业务库凭据，连接信息只在 Spring 配置与环境变量里，so that 提示词被套取时也不会泄露连接串

### 面试演示者

16. As a 面试演示者，我想要在 60 秒内演示"问一个数 → 拿到数"，so that 我能在开场就证明这个项目是干业务的
17. As a 面试演示者，我想要换一个角色头就能演示"同一个人没权限时拿不到"，so that 鉴权是看得见的而不是嘴上说的
18. As a 面试演示者，我想要在 SSE 流式输出里演示脱敏同样生效，so that 我能讲清 Advisor 双接口这个坑我们真的处理了
19. As a 面试演示者，我想要能用真实模型演示，也能在没网没 key 时退到脚本化假模型演示，so that 现场网络出问题时我仍有东西可讲
20. As a 面试演示者，我想要每一条"拦住了什么"都有一条对应的测试指着，so that 被追问时我能直接打开测试而不是复述设计

### CI

21. As a CI，我想要所有单元测试不烧 token、不依赖网络，so that 我的结果不会因为供应商限流而随机变红
22. As a CI，我想要慢的 Testcontainers 测试与快测试分层，so that 提交门禁仍然在几秒内给出结论

## Implementation Decisions

- **依赖方向不变**（ADR-0010）：`channel → domain ← agent`，M1 补上缺失的那条边——`claw4j-agent` 依赖 `claw4j-tools`，`claw4j-tools` 只依赖 `domain`。工具不认识 Agent，Agent 负责装配。这条边此前没有接上，是 M0 留下的已知缺口。
- **业务 schema 用销售域**：区域 / 客户 / 订单 / 订单项，种子数据可复现且量小（够算出确定的答案，不至于让测试变慢）。迁移方式（Flyway 还是 `schema.sql`）由 01 号票定，本 spec 不预设。
- **Text-to-SQL 走 `@Tool`**，只做 SELECT，结果带行数上限。生成失败时走自纠错链路并用固定 schema 跑通（ADR-0008 列为必测项）。只读约束的落法（只读库用户 / 语句校验 / 两者都要）由 03 号票定。
- **鉴权 Advisor 的 order 排在工具循环内部**（小于 `HIGHEST_PRECEDENCE + 300`，ADR-0007），于是模型的每一次工具迭代都会过一遍；不通过就短路，不调 `chain.next()`。粒度是表级与动作级。
- **两个 Advisor 都同时实现 `CallAdvisor` 与 `StreamAdvisor`**，且必须覆写 `getName()`——匿名 Advisor 的 `getClass().getSimpleName()` 是空串，会让观测上下文抛 `advisorName cannot be null or empty`（M0 踩过）。
- **会话用 `spring-ai-session` 的 JDBC starter**（`org.springaicommunity:spring-ai-starter-session-jdbc`，groupId 不是 `org.springframework.ai`，见 ADR-0005 修订记录），与业务数据共用同一 Postgres、靠 schema 分开，`InMemorySessionRepository` 明确禁用。**这一条是硬要求，不回退到"自己写个 Map 存着"**——自己写的版本讲不出多实例与事务，且偏离 ADR-0005。
- **`chatId` 与会话 id 是两件事**：`chatId` 是 M0 定的通道侧会话**寻址键**（`X-Claw4j-Chat-Id`，缺省回落用户标识），不等于 Session 本身。M0 专门把 `sessionKey` 改名就是为了 M1 接 `spring-ai-session` 时不撞名、不混用。接线时不要把 `chatId` 直接当会话主键用。
- **模型侧不新增能力**：沿用已完成的主备路由（`RoutingChatModel`，`claw4j.model.primary` 切主），M1 只在假模型上加脚本化能力。
- **脚本化假模型**：按序吐出预设响应（先一次工具调用请求，再最终回答）。没有它，Text-to-SQL 那两步——模型先要调工具、再据此作答——在 CI 里根本测不出来。

## Testing Decisions

**三个接缝（本轮确认）**，各管一件事，不重复钉同一条行为：

| 接缝 | 测什么 | 不测什么 |
|---|---|---|
| **`AgentPort`（主）** | 端到端外部行为：一条 `InboundMessage` 进 → `OutboundMessage`（或一串块）出。`reply` 与 `stream` 两条路径都要走。覆盖鉴权短路、脱敏、多轮记忆 | 不测 Advisor 的 order 值、不测内部调用了哪个方法 |
| **`@Tool` 方法（次）** | 业务工具本身：给定参数 → 断言返回的行或被拒绝。脱离模型，单独定位 SQL 执行与权限判定 | 不测模型怎么决定调它 |
| **数据源（次，Testcontainers 直连）** | schema、种子、查询正确性："上周华东区成交额"的 SQL 真能算出那个数 | 不测 Agent 是否聪明 |

外加 `ChatModel` 作为**替身**（既有框架接缝，不算新接缝）升级为脚本化假模型。

- **流式路径必测**：鉴权与脱敏都要有一条 SSE 上的断言，对应 ADR-0007 那个"只实现 `CallAdvisor` 会被静默跳过"的坑。已有 `AdvisorBothPathsTest`（反面用例）与 `AdvisorNotSkippedOverHttpTest`（整链证明）两层，M1 的两个 Advisor 各自补上同类断言。
- **单元测试全部 mock 模型调用**，不烧 token、不依赖网络（ADR-0006）。
- **Testcontainers 类测试默认不进提交门禁**：pre-commit hook 仍只跑 `*ArchitectureTest`（约 5 秒），慢测试留在 `mvn verify` 与 CI。
- **每条"拦住了什么"都有一条测试指着**：第 20 条用户故事是这条约定的来源，不是一句口号——没有对应测试的拦截等于没拦。

## Out of Scope

- MCP server 与 Script Package 的**能力**（ADR-0003 二层三层），M2
- Agent Skills 渐进式披露、Subagent 编排、Skill Generator 沙箱，M2
- 飞书长连接通道（含异步 ACK 与事件去重），M2；A2A 端点，M3
- 可观测（token 计量、Micrometer）、评估器进 CI、语义缓存（Redis），M3
- **行级 / 列级脱敏**：ADR-0007 明确不做，表级白名单已足够讲清设计
- 登录、注册、会话鉴权体系（ADR-0007）
- **轮次压缩**（Trigger 与 Strategy 成对）：M1 只做到"记得住上一轮"。压缩是容量问题，等有真实多轮数据再定策略，不在这一轮拍
- **澄清追问**（口径不对时先问一句再算）：不进 M1，等闭环跑通后单独一轮
- 真实模型的限流、配额、压测
- 向量检索与 RAG：pgvector 已在 compose 里但不接，M2 之后
- `legacy/` 下的任何改动

## Further Notes

- **进 M1 时的现状**：M0 五张票全部 resolved；64 个测试绿；`mvn verify` BUILD SUCCESS。模型主备路由已落地，真实 DeepSeek 与通义 key 均已验证过真链路；术语 `sessionKey → chatId` 已统一；流式取消传播、模型调用超时、池满返回 503 都已修完。这些是 M1 的前置，不在 M1 范围里重做。
- **环境**：两个真实 key 都在本机 `.env`（已被 `.gitignore` 忽略），`CLAW4J_MODEL_PRIMARY=qwen|deepseek` 切主。仓库里只有 `.env.example` 模板。
- **由票内决定、本 spec 不预设**（避免在这里发明决策）：① 业务 schema 的迁移方式（Flyway vs `schema.sql`）② 只读约束的落法（只读库用户 / 语句校验 / 两者都要）③ 自纠错的重试次数上限。
- **决策依据**：ADR-0003（能力三层）、0005（一个 Postgres 三用）、0006（模型与 mock）、0007（Principal 与鉴权）、0008（交付节奏与测试策略）、0010（模块分层与依赖方向）。术语以根 `GLOSSARY.md` 为准。
- 本 spec 对应的实现票在同级 `issues/` 目录，01 号票无前置依赖，可立即开工。
