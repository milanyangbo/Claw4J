Status: ready-for-agent

# M2：能力层三层打通 + Agent Skills 渐进式披露与 Subagent 编排

（本份只做能力层与 Skills。**飞书长连接通道另立一份 spec**，见 Out of Scope。）

## Problem Statement

M1 结束时能演示的是"问一句话、拿到业务库里的数"，但 Agent 手里的能力只有一种：写在 `claw4j-tools` 里的 Java `@Tool`。差距有三处，每一处都单独让项目停在"一个会查库的 Spring Boot 应用"：

1. **能力只有一层。** 依赖外部 CLI 或跨语言的能力（`gh`、邮件客户端）用 Java 重写不划算，而现在除了重写没有别的路。ADR-0003 定的三层——Java `@Tool` / MCP server / Script Package——只有第一层落地了。
2. **没有 Skill，也没有 Subagent。** 模型拿到的是一堆扁平工具名，不知道"先做什么再做什么"；长任务全塞在同一个上下文窗口里，中间过程与结论混在一起。ADR-0008 把 Agent Skills 与 Subagent 编排列为四项做深之二，目前两项都是空的。
3. **"写个脚本就能扩展"这条定位是空的。** 1.x 靠 `sh -c` + `ProcessBuilder` 实现，把宿主机 shell 交给了模型；ADR-0002 禁止之后，脚本层直接消失了，Script Package 一个都没有。而 Skill Generator（模型自己产出新脚本包）是本项目**核心能力**，不是附属功能——它是"开放扩展"这个定位唯一能自证的部分。

面试视角下 M2 是第二个分水岭：M1 证明"能干业务且不出事"，M2 才证明"能扩展"。被追问"你的 Agent 怎么新增一种能力""脚本是你自己 exec 的吗""模型自己写的脚本你敢跑吗"时，三层分流、MCP 收口、五步链路这三件事就是答案，而且它们都能讲出取舍。

## Solution

在既有骨架上补三块，M1 的 `AgentPort` 接缝、`channel → domain ← agent` 依赖方向、Advisor 链都不动：

- **三层能力打通**：Java `@Tool`（已有）之外，MCP server 走跨进程工具协议（`@McpTool`），Script Package 走社区包执行（受限工作目录、超时、非 root）。三层对 Agent 呈现为一回事——工具名 + 参数 Schema 是唯一契约。
- **Agent Skills 渐进式披露**：`SKILL.md` 启动时只加载元数据（`name` + `description`），命中才加载全文；脚本本体不进上下文，只有输出进。
- **Subagent 编排**：Orchestrator 委派专职 Subagent 在独立上下文窗口里跑，只把结论回传父 Agent，中间轨迹不回传。
- **Skill Generator 五步链路**：生成 → 静态检查 → 隔离沙箱自测 → 审计留痕 → 人审通过才注册为 Script Package。

一次"用某个 Skill 完成一件事"的路径形状：

```
入站（含 Principal）
  → 鉴权 Advisor：这个角色能不能用这个工具
  → 模型选中一个 Skill（此时才加载它的全文）
  → 按 Skill 描述的顺序调工具：Java @Tool / MCP @McpTool / Script Package 对模型长得一样
  → 需要长任务时委派 Subagent（独立上下文，只回传结论）
  → 脱敏 Advisor → 出站
```

## User Stories

### 企业员工（用的人）

1. As a 企业员工，我想要问"这个仓库最近三个 PR 什么状态"能拿到结果，so that 不用离开 IM 去开浏览器
2. As a 企业员工，我想要脚本类能力（天气、摘要、抓取）用起来跟查库一样自然，so that 我只关心拿到的东西对不对
3. As a 企业员工，我想要不用关心背后是 Java 工具、MCP server 还是脚本，so that 我不需要学这套分层
4. As a 企业员工，我想要长任务由 Subagent 跑、我只等结论，so that 我的会话不被一堆中间步骤刷屏
5. As a 企业员工，我想要没权限的能力给我一句明确的"没权限"，so that 我不会把"不能做"误读成"做完了"
6. As a 企业员工，我想要脚本层的能力不会因为我换了个问法就绕过权限，so that 权限体验是统一的
7. As a 企业员工，我想要一次提问不会因为某个脚本死循环而无限烧下去，so that 我能拿到一句答复而不是等到超时

### 平台维护者

8. As a 平台维护者，我想要三层能力对 Agent 呈现为同一件事，so that 编排逻辑不用按层级分支
9. As a 平台维护者，我想要加一个 MCP server 不改 Agent 装配代码，so that 接新能力只是加配置
10. As a 平台维护者，我想要**加第二个工具来源不会让第一个静默消失**，so that "工具少了一个"不能以"能编译能跑"的状态长期存在
11. As a 平台维护者，我想要 MCP 连接建立时工具清单为空就 fail-fast，so that 配置错误在启动时暴露而不是等到用户问到了才暴露
12. As a 平台维护者，我想要 MCP 会话丢失时重新初始化连接而不是重试死会话，so that 一次断连不会让后续所有调用都失败
13. As a 平台维护者，我想要脚本执行有两个位置且互不混淆（应用容器内 vs 独立隔离沙箱），so that "哪个环境能碰到什么"有唯一答案
14. As a 平台维护者，我想要 Java 侧不出现 `Runtime.exec` / `ProcessBuilder` / `sh -c`，并且这条由机器查，so that 我不会靠 review 记住它
15. As a 平台维护者，我想要脚本只能访问自己的沙箱目录，so that 一个脚本读不到应用配置与别的脚本的产物
16. As a 平台维护者，我想要脚本执行环境里拿不到业务库凭据与 API key，so that 提示词被套取也换不到凭据
17. As a 平台维护者，我想要 MCP 与脚本层不授予任何业务表权限，so that 分层收紧是一条可验证的事实而不是一句承诺
18. As a 平台维护者，我想要调用预算覆盖三层而不是只管 Java 工具，so that 新增一种能力不会顺带把预算打开
19. As a 平台维护者，我想要沙箱边界有测试指着，so that 跑不起来的沙箱不会被当成"已经收口了"
20. As a 平台维护者，我想要启动时只加载 Skill 的元数据，so that 二十个长流程说明不会把每次提问的上下文挤满
21. As a 平台维护者，我想要 Subagent 的中间轨迹不进父上下文，so that 上下文隔离是真的隔离
22. As a 平台维护者，我想要 Subagent 能用的工具由它的定义白名单决定，so that 委派不等于把全部能力交出去

### 安全视角

23. As a 关注安全的人，我想要模型生成的脚本只在网络受限、无凭据的隔离环境里跑，so that "敢跑"不等于"在应用进程里跑"
24. As a 关注安全的人，我想要静态检查挡住危险 import 与越权文件操作，且它是进门前的第一道机器关，so that 明显有害的东西到不了沙箱那一步
25. As a 关注安全的人，我想要没经过人审的脚本不能注册成 Script Package，so that 自动产出的能力不会自动获得被调用的资格
26. As a 关注安全的人，我想要脚本的生成、自测、执行全程留审计，so that 事后能回放"谁在什么时候跑了什么、结果如何"
27. As a 关注安全的人，我想要模型被提示词注入去调 MCP 或脚本时越不过权限边界，so that 能不能调用由 Principal 决定而不是由模型决定
28. As a 关注安全的人，我想要沙箱不继承应用容器的环境变量，so that "换个执行位置"不会变成"换了个能拿到凭据的位置"

### 写脚本 / 写技能的人

29. As a 写脚本的人，我想要写一个 `SKILL.md` 加一个脚本就能扩展能力，so that 我不必写 Java 也不必等发版
30. As a 写脚本的人，我想要 Skill 只在被命中时加载全文，so that 我的长流程说明不占别人的上下文
31. As a 写脚本的人，我想要脚本本体不进上下文、只有输出进，so that 两百行 Python 不会变成两百行 token
32. As a 写脚本的人，我想要自测失败时看到脚本的原始 stderr，so that 我有东西可改（与自纠错同理：包装过的话没有可改的信息）

### 面试演示者

33. As a 面试演示者，我想要 60 秒内演示"模型自己选中一个 Skill 并按它编排工具"，so that 我能证明 Skill 是编排而不只是提示词
34. As a 面试演示者，我想要演示 Subagent 委派后父 Agent 只拿到结论，so that 上下文隔离是看得见的
35. As a 面试演示者，我想要演示 MCP 跨进程：Java 侧只见工具协议、不见脚本，so that ADR-0002 那条"不再直接拼命令行"能被指着讲
36. As a 面试演示者，我想要演示 skill-generator 五步链路且每一步都有产物可见，so that "核心能力"不是一句自我介绍
37. As a 面试演示者，我想要每一条边界（静态检查挡住了什么、沙箱挡住了什么）都有一条测试指着，so that 被追问时我打开测试而不是复述设计
38. As a 面试演示者，我想要整套演示不依赖外网也不依赖 Docker，so that 现场网络出问题时我仍有东西可讲

### CI

39. As a CI，我想要所有单元测试不烧 token、不依赖网络，so that 我的结果不会因为供应商限流而随机变红
40. As a CI，我想要 MCP 的 fail-fast 与会话重连**进 CI**（ADR-0008 三条必测之一），so that 连接层那两个故障不会只在生产第一次出现
41. As a CI，我想要慢的（真实沙箱、真实 MCP 进程）与快测试分层，so that 提交门禁仍然在几秒内给出结论

## Implementation Decisions

- **依赖方向不变**（ADR-0010）：MCP client 与 Skill/脚本执行落在 `claw4j-tools`（它已经是被 agent 依赖的能力层），`claw4j-agent` 只做装配，`claw4j-domain` 继续零框架。ADR-0010 已写明"M2 引入 Skill 引擎与 MCP client 后若 `claw4j-agent` 明显膨胀，再按判据评估拆 `claw4j-skills`，不预先拆"——**本轮不预先拆**，模块数仍是五个。
- **工具装配的接缝必须改**：今天 `claw4j-tools` 只导出一个 `ToolCallbackProvider` bean，条件按"整个 provider 缺失"判；再加第二个工具来源时，第二个 provider 会让位，工具静默消失（这条坑已写在该自动配置的类注释上）。M2 有三层来源，必须改成**收集容器内所有 `ToolCallback` / `ToolCallbackProvider`**。"少了一个工具"要能被一条断言直接看见，而不是靠人发现模型变笨了。
- **三层对 Agent 呈现为一回事**（ADR-0003）：都注册成工具回调，名字与参数 Schema 是唯一契约。Agent 侧不出现"这是 MCP 工具还是脚本工具"的分支。
- **鉴权只覆盖第一层**（ADR-0003 / ADR-0007）：MCP 与脚本层不授予任何业务权限，未登记权限的工具一律按拒绝处理。M2 新增的每个工具都要由提供它的模块登记 `ToolPermission`——**脚本/MCP 工具也要登记**，登记内容是"无业务表 + 只能访问自己的沙箱目录"，留空不等于"无需权限"，而是拒绝。
- **MCP 用 `spring-ai-starter-mcp-client`**（版本由 `spring-ai-bom` 管理，2.0.2 的三个 starter——client / server / server-webmvc——坐标已核对存在于 Maven Central）。连接建立时 `listTools()` 为空即 fail-fast；会话丢失（`McpTransportSessionNotFoundException`）重新初始化而非重试死会话。这两条是 ADR-0008 三条必测之一，**不在票内重新讨论要不要做**。
- **脚本层走社区包 `org.springaicommunity:spring-ai-agent-utils`**（0.13.0，根 POM 已在 dependencyManagement 声明、尚未被任何模块引入；传递引 `-common`）。它提供：`SkillsTool`（`build()` 返回 `ToolCallback`，可直接进工具集）、`Skills.loadDirectory(...)`（`Skill` 记录带 `name` / `frontMatter` / `content` / `basePath`）、`ShellTools`、`TaskTool`（Subagent 编排，子 Agent 定义来自 markdown frontmatter：`name` / `description` / `model` / `tools` / `disallowedTools` / `skills` / `permissionMode`）与 `ExecBackend`（`LocalExecBackend` 是其一个实现，另有 `ExecSpec` / `ExecResult` / `ExecHandle`）。**类名与签名已核对（javap 过 0.13.0 的 jar），但"用它哪一部分、自己补什么"由票内定**——照抄文档而不核坐标在这个项目上已经出过一次事（M1-06 的 groupId）。
- **两个执行位置必须分清**（ADR-0003）：① Script Package 在**应用容器**内执行：限工作目录、超时、非 root，且拿不到任何业务凭据；② Skill Generator 产出的脚本在**独立隔离沙箱**内自测：网络受限、资源有上限、不继承环境变量、全程留审计。两者共用同一个 `ExecBackend` 接口——这是这条边界唯一可测的地方：换沙箱等于换实现，调用方不动。
- **沙箱的物理形态由票内定，判据是"能不能在 CI 里跑起来"**：跑不起来的沙箱等于没有测试，没有测试的边界不能写进 README 当承诺。第一版可以先落在受限本地执行 + 静态检查 + 审计，但 `ExecBackend` 的实现必须是可替换的，不允许把"本地执行"写死在调用方。
- **Skill Generator 五步链路固定**（ADR-0002）：生成 → 静态检查 → 沙箱内自测 → 审计留痕 → 人审通过才注册。其中**静态检查是纯函数、优先单测**：它是未审核代码进门前的唯一一道机器关，也是最便宜的一环，值得用表驱动用例把危险清单钉死。
- **Java 侧不出现 `Runtime.exec` / `ProcessBuilder` / `sh -c`**（ADR-0002）：这条加进既有 ArchUnit 规则体系，靠机器查而不是靠 review 记。
- **Skill 渐进式披露**：启动时只加载 `SKILL.md` 的元数据，命中才加载全文；脚本本体不进上下文，只有输出进（GLOSSARY 对 Script Package 的定义）。
- **Subagent 只回传结论**：父 Agent 拿不到子 Agent 的工具调用轨迹；子 Agent 可用工具由定义里的 `tools` / `disallowedTools` 决定。
- **调用预算覆盖三层**：每工具 3 次、整轮 6 次（GLOSSARY）。预算挂在 `ToolCallingManager` 上，新增工具来源不得重置或绕过它；M1 已有断言钉着"换了 builder 会静默退回 40/150"，M2 新增来源后那条断言必须仍然绿。
- **模型侧不新增能力**：沿用主备路由与脚本化假模型。真实调用与真实沙箱自测都不进 CI（ADR-0006 / ADR-0008）。

## Testing Decisions

**四个接缝**，各管一件事，不重复钉同一条行为。理想是只新增一个，实际做不到：跨进程连接与沙箱是两条**物理边界**，替身测不出它们的真实时序与真实约束。

| 接缝 | 测什么 | 不测什么 |
|---|---|---|
| **`AgentPort`（既有，主）** | 端到端外部行为：一条 `InboundMessage` 进 → `OutboundMessage`（或一串块）出。`reply` 与 `stream` 两条路径都要走。覆盖 Skill 命中、Subagent 只回结论、三层工具被调用 | 不测工具内部怎么实现、不测 Advisor 的 order 值 |
| **工具回调收集点（新，最高点）** | 装配层：容器里有哪些工具回调、三层是否都进来了、少一个能不能被测出来。绝大多数测试在这里用假回调替身，**不需要真 MCP server、真脚本、真沙箱** | 不测 MCP 协议、不测沙箱约束 |
| **MCP 传输层（次，跨界必须真测）** | 连接语义：`listTools()` 为空 fail-fast、会话丢失重新初始化。**用进程内 stdio MCP server（Java 自实现）真起一个**，不测业务逻辑 | 不测业务工具对不对 |
| **`ExecBackend`（次，沙箱边界）** | 替身 backend 断言"传进去的 `ExecSpec` 被约束住了"：工作目录在沙箱内、超时有值、无凭据环境变量、审计被写 | 不真起容器（除非票内决定沙箱形态后另加一条慢测试） |

- **好测试 = 只测外部行为**：给什么进、得到什么出；不断言内部调用了哪个方法、不断言配置的值（M1 唯一破例的是调用预算那条——它失效的姿势是静默退回默认值，不钉就没人知道）。
- **既有先例（照它们的样子写）**：`AgentPortTest`、`ScriptedChatModel`（脚本化假模型，按序吐响应）、`FakeSqlExecutor`（替身让真实实现让位）、`SalesQueryEndToEndTest`、`ToolCallBudgetTest`、`AuthorizationOverHttpTest`（计数差值而非比文本）、`*ArchitectureTest`（边界机器校验）。
- **MCP 那两条必须进 CI**，所以测试用的 server 用 Java 进程内 stdio 实现，不依赖 Docker、不依赖 Python——否则 ADR-0008 的必测项会变成"只在有 Docker 的机器上测过"。
- **Skill 的渐进式披露不是接缝**，是加载策略，直接单测：未命中的 Skill 全文不进上下文（断言拼给模型的提示里没有它的 `content`）。
- **静态检查用表驱动**：危险清单逐条一个用例，命中必须拒、正常脚本必须放行，两条都要有。
- **沙箱类与真实调用类测试默认不进提交门禁**：pre-commit 仍只跑 `*ArchitectureTest`（秒级），慢测试留在 `mvn verify` 与 CI。
- **M1 的 177 个测试是回归基线**，M2 结束时必须仍全绿，且数据库那一类不能变成跳过（`./scripts/assert-no-skipped-tests.sh` 盯着）。
- **每条边界都有一条测试指着**：第 37 条用户故事是这条约定的来源——没有对应测试的拦截等于没拦。

## Out of Scope

- **飞书长连接通道**（含异步 ACK 与事件去重，ADR-0009）：另立一份 spec，不在本份里
- A2A 端点、可观测（token 计量、Micrometer）、评估器进 CI、语义缓存（Redis）：M3
- 向量检索与 RAG：pgvector 已在 compose 里但不接
- **轮次压缩**：Trigger 与 Strategy 必须成对，等有真实多轮数据再定
- 行级 / 列级脱敏（ADR-0007 明确不做）
- 登录、注册、会话鉴权体系
- 1.x 的 29 个场景里**只迁 8 个**（ADR-0003 已定清单），其余 21 个不迁移、不留在仓库
- Telegram 通道（可选的第二 Channel 实现，用于证明 SPI 可替换）
- 真实模型的限流、配额、压测；`legacy/` 下的任何改动
- 沙箱的**物理形态**（容器还是受限本地进程）由票内定，本 spec 只给判据

## Further Notes

- **进 M2 时的现状**：M1 七张票全部 resolved；177 个测试绿、跳过 0；`./mvnw verify` BUILD SUCCESS。已落地的相关件：`ToolCallbackProvider`（当前只有一个）、`ToolPermissionRegistry`（工具权限由提供工具的模块登记）、`AuthorizationAdvisor`（order 在工具循环内部）、`ToolCallBudget`（3 / 6）、脚本化假模型、五条质量门禁（含新增的"跳过闸"）。这些是 M2 的前置，不在 M2 范围里重做。
- **环境**：两个真实 key 都在本机 `.env`（已被 `.gitignore` 忽略）。本机 Docker Hub 拉不动，跑全量验证要带 `CLAW4J_POSTGRES_IMAGE`（镜像站），否则数据库那一类会红。
- **由票内决定、本 spec 不预设**（避免在这里发明决策）：① MCP server 的承载形态（进程内 stdio vs 独立进程）② 沙箱的物理形态 ③ Skill 目录的位置与加载时机（启动扫描 vs 命中时读）④ Subagent 用哪个模型（沿用主模型还是走便宜模型）⑤ 静态检查危险清单的范围 ⑥ 8 个保留场景里本轮先迁哪几个。
- **决策依据**：ADR-0002（脚本执行收口与沙箱）、0003（能力三层）、0006（模型与 mock）、0007（Principal 与鉴权）、0008（交付节奏、四项做深、三条必测、调用预算）、0010（模块分层与依赖方向）。术语以根 `GLOSSARY.md` 为准（Skill / Script Package / Tool / Subagent / Skill Generator / 调用预算）。
- 本 spec 的实现票在同级 `issues/` 目录，共 7 张：`01` 工具装配（prefactor，无前置依赖，可立即开工）→ `02` MCP 跨进程 / `03` Script Package 沙箱 / `04` Skills 渐进式披露（都只依赖 01）→ `05` Subagent（01, 04）→ `06` Skill Generator 五步链路（03, 04）→ `07` 场景迁移与文档收尾（02–06）。
