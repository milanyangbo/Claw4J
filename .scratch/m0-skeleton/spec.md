Status: ready-for-agent

# M0：骨架 —— 让仓库进入"旧代码不在构建路径、新代码能跑通一句话"的状态

## Problem Statement

Claw4J 的 1.x 版本是一本反面教材：6793 行代码零测试，Agent 的"工具调用"其实是让模型输出 `CALL_SKILL: xxx` 再在 Java 侧用 `indexOf` 解析；脚本执行把宿主机 shell 交给模型，还带上硬编码 root 和 `pip install --break-system-packages`；构建配置三处自相矛盾（属性写 21、compiler plugin 写 17、`<executable>` 指向本机不存在的路径），`mvn compile` 压根跑不起来。两处明文凭证（Teammors token、IMAP 密码）曾经差点被提交进公开仓库。

这个项目现在的用途是**技术面试**：需要证明 AI Agent 的工程能力，且越接近真实场景越好。而当前状态连"问一句话拿到回答"都做不到，更谈不上面试现场演示。

真正的问题不是"功能少"，而是**没有任何一条能端到端跑通并验证的路径**：没有测试、没有可用的构建、没有对外入口（0 个 `@Controller`，Telegram 通道代码被注释掉）。在这种状态下往上加任何 Agent 能力都是空中楼阁。

## Solution

冻结 1.x 作为 before 对照（已打 tag `v1.x-legacy`，源码移入 `legacy/`，不参与构建），在同仓库用七个 `claw4j-*` 模块从零搭起骨架，版本基线锁在 Spring AI 2.0.2 + Spring Boot 4.1.2 + JDK 21。

M0 结束时可用的东西：一条 `docker compose` 拉起 Postgres(pgvector) 与 Redis；没有模型 key 也能靠 mock 模型跑通全链路；HTTP 通道上问一句话能拿到回答，流式路径同样能逐块收到；CI 绿。

模块划分的核心主张是**用编译期强制边界**：`domain` 模块里出现一行 Spring AI 的 import 就是破界。这条规则比口头约定靠谱，也是面试时能一句话讲清的架构主张。

## User Stories

### 本地开发者

1. As a 本地开发者，我想要克隆仓库后敲一条命令就把 Postgres 和 Redis 拉起来，so that 我不用手工装 pgvector 扩展、不用自己拼连接串
2. As a 本地开发者，我想要在没有模型 API key 的情况下也能启动应用并跑通一次问答，so that 我不会因为没有凭据就被卡在本地开发的第一步
3. As a 本地开发者，我想要 `mvn verify` 在全新环境下能一次通过，so that 我可以相信构建结果而不是靠"我这里能跑"
4. As a 本地开发者，我想要旧代码整体躺在一个目录里且不参与构建，so that IDE 全局搜索和 Maven 反应堆都不会被 6793 行死代码干扰
5. As a 本地开发者，我想要每个模块的依赖方向在构建期就被约束住，so that 我不必靠 code review 发现有人把 Spring 引进了领域层
6. As a 本地开发者，我想要模型名与端点全部走配置，so that 换供应商时我只改配置不改代码

### 面试演示者

7. As a 面试演示者，我想要一个不依赖任何外部账号的通道（HTTP+SSE），so that 现场扫码失败或网络出问题时我仍然有东西可演示
8. As a 面试演示者，我想要问一句话就能拿到回答，so that 我能在 30 秒内证明这个项目是活的
9. As a 面试演示者，我想要流式路径也能逐块输出，so that 我能演示 Advisor 链在流式下同样生效
10. As a 面试演示者，我想要 before/after 在同一个仓库里可对照，so that 我讲"我们如何把通道抽象掉了"时能直接指给面试官看
11. As a 面试演示者，我想要每条架构决策都有 ADR 记录它否决了什么，so that 被追问"为什么这么选"时我有依据而不是现场编
12. As a 面试演示者，我想要 README 写清楚怎么起、key 怎么配、legacy 是什么，so that 面试官自己看仓库也能看懂

### Agent 的使用者（企业员工）

13. As a 企业员工，我想要在 IM 里问"上周华东区成交额多少"，so that 我不用找数据分析师排队
14. As a 企业员工，我想要问业务问题时不用关心背后是 SQL 还是脚本，so that 我只关心拿到的数对不对
15. As a 企业员工，我想要我换个设备或换个通道进来时上下文还是连续的，so that 我不用把背景重讲一遍
16. As a 企业员工，我想要我看不到的数据（例如成本列）不会被 Agent 说出来，so that Agent 不会成为越权的口子
17. As a 企业员工，我想要 Agent 在动手前先问我一句而不是猜，so that 它不会按错误口径算完才来问我

### 平台维护者

18. As a 平台维护者，我想要 Agent 调工具前必须经过 Principal 校验，so that 工具调用能不能发生不由模型决定
19. As a 平台维护者，我想要每个自定义 Advisor 都同时实现同步与流式两个接口，so that 流式路径不会静默跳过脱敏和鉴权
20. As a 平台维护者，我想要敏感配置从脚本包里收回到 Spring 配置并走环境变量，so that 数据库连接串不会出现在被提交的 Markdown 里
21. As a 平台维护者，我想要一条命令就能确认没有任何模块越界引入框架依赖，so that 这条规则不会因为没人检查而腐烂
22. As a 平台维护者，我想要 CI 只构建新模块不构建 legacy，so that 死代码不会拖慢流水线
23. As a 平台维护者，我想要集成测试用干净的数据库实例，so that 上一个测试留下的数据不会干扰下一个

### 安全视角

24. As a 关注安全的人，我想要模型产出的脚本只在隔离沙箱里执行并留审计，so that "AI 自动写技能并执行"不会变成把 shell 交给模型
25. As a 关注安全的人，我想要脚本层不持有任何业务凭据，so that 脚本被注入时也拿不到业务库
26. As a 关注安全的人，我想要仓库里不再出现任何明文凭证，so that 公开仓库不会泄露 token 与密码
27. As a 关注安全的人，我想要外部不可信数据不再用 fastjson 直接反序列化，so that 反序列化入口不是攻击面

### CI

28. As a CI，我想要所有单元测试都不需要网络与真实模型，so that 我的结果不会因为供应商限流而随机变红
29. As a CI，我想要构建在 JDK 21 上跑，so that 我不会因为某个模块残留 17 的配置而在本地和远端表现不一致
30. As a CI，我想要质量门禁能拦住依赖越界，so that 架构约定是机器检查的而不是靠自觉

## Implementation Decisions

- **七模块**：`domain`（不依赖框架）、`agent`（ChatClient 装配、Advisor 链、Session、鉴权）、`channel-api`（SPI）、`channel-http`、`channel-feishu`、`tools`、`app`（启动与配置）。依赖方向单向：domain ← agent / channel / tools ← app。
- **版本基线写死在父 POM 的属性里**：Spring AI 2.0.2、Boot 4.1.2、JDK 21（`maven.compiler.release`）、`spring-ai-agent-utils` 0.13.0、`spring-ai-session-bom` 0.10.0。改这四个版本号等于改架构决策，要在 ADR 留痕。
- **唯一的通道接缝**是 `ChatChannel`：入站消息怎么进、结果怎么回。形状大致如此（来自本轮设计，非线上代码）：

  ```
  ChatChannel {
      String id();
      void start(Consumer<InboundMessage> onMessage);   // 入站
      void send(OutboundMessage message);               // 出站
      void stop();
  }
  ```

  测试一律从这个接缝进、从这个接缝出，中间全部可替换。这是 M0 唯一需要的新接缝，其余能力尽量复用框架既有接缝（`ChatModel` 打桩、Advisor 链）。
- **通道负责认人，Agent 不认人**：`channel` 从入站消息解析出用户标识并交给上层，`agent` 只接受已经解析好的 Principal。这条边界保证 domain 与 agent 不依赖任何具体通道协议。
- **没有 key 时自动降级到 mock 模型**，应用必须能启动并跑通全链路；拿到 key 后只改配置切到真实供应商。DeepSeek 走一等公民适配器，通义走 OpenAI 兼容端点。
- **会话落在 Postgres**（`spring-ai-session` 的 JDBC starter），与业务数据、pgvector 共用同一实例、靠 schema 分开；Redis 只做语义缓存，只保留 Lettuce 一个客户端。
- **鉴权做在 Advisor 链上**，order 排在工具循环内部以便每次迭代都校验；粒度到表级与动作级；行级/列级脱敏不做。
- **不做登录系统**，Principal 用配置化的静态映射给出。
- **脚本三层**（Java `@Tool` / MCP server `@McpTool` / Script Package）在 M0 只落地骨架，具体能力在 M2。
- **凭据一律走环境变量与 Spring 配置**，不进脚本包、不进仓库。

## Testing Decisions

- **接缝选择（已确认）**：主接缝是 `ChatChannel`（入站/出站），次接缝是 `ChatModel`（打桩）。M0 只新增前者这一个接缝，其余复用框架既有扩展点。测试从"一条入站消息"进、到"一条出站消息"出，中间全部可替换 —— 换通道实现时测试不用改。代价是通道接口必须在 Agent 之前定型，这个顺序已经反映在票的依赖边上。
- **什么是好测试**：只测外部行为，不测实现细节。具体说——测"发一句话进去，拿到一句回答出来"，不测"Advisor 内部调用了哪个方法"；测"没有权限时工具没被调用"，不测"鉴权 Advisor 的 order 值是多少"。
- **必须覆盖的模块与场景**：`agent` 的每个自定义 Advisor，同步与流式两条路径都要测（流式被静默跳过是 2.x 最经典的坑）；`channel-http` 的端到端问答与流式；`domain` 的纯单测（不引入测试框架之外的依赖）。
- **网络与凭据**：单元测试全部 mock 模型调用，不烧 token、不依赖网络；集成测试走真实端点时用 profile 隔离，默认不进 CI。
- **数据库**：开发期用 `spring-boot-docker-compose` 常驻，集成测试用 Testcontainers 起干净实例，避免脏数据串扰。
- **本项目没有测试先例**（1.x 零测试），因此不追求"对齐现有风格"，而是直接按上述原则从零建立，并在 M0 就把 CI 跑绿，让后续每个能力点都有可回归的基线。

## Out of Scope

- M1 及之后的内容：Session 记忆、业务库 Text-to-SQL、正式的业务 `@Tool`
- M2 的 Agent Skills 渐进式披露、Subagent 编排、MCP server、Skill Generator 沙箱、飞书长连接通道
- M3 的 A2A 端点、可观测（token 计量、Micrometer）、评估器进 CI、语义缓存
- 企业微信通道（个人账号拿不到管理后台，无法创建自建应用，见 ADR-0009）
- 登录、注册、会话鉴权体系
- 真实模型的联调（等 API key 到位后单独一轮）
- `legacy/` 下的任何改动

## Further Notes

- 决策依据全部落在 `docs/adr/`：0001 基线、0002 脚本执行收口、0003 能力三层、0004 仓库结构与 legacy 处置、0005 一个 Postgres 三用、0006 模型供应商与 mock、0007 Principal 与鉴权、0008 交付节奏、0009 通道选型。术语以根 `GLOSSARY.md` 为准。
- 模型 API key 尚未提供。在此之前所有演示走 mock 模型；key 到位后补一轮真实供应商联调（含 DeepSeek 与通义的路由分流验证）。
- 本 spec 对应的实现票在同级 `issues/` 目录，01 号票无前置依赖，可立即开工。
