# 01: 工具装配改成收集所有 ToolCallback —— 三层能力的共同入口

**What to build:** 今天能力层只导出一个工具来源，而且它的注册条件按"整个 provider 缺失"来判。再加第二个来源（MCP、Script Package、Skills）时，第二个 provider 会让位，工具**静默消失**——失效姿势是"模型手上没有这个工具，于是它开始编答案"，从聊天界面上完全看不出来（这条坑已经写在提供工具的那个自动配置的类注释上）。这张票先把装配改成收集容器内所有 `ToolCallback` / `ToolCallbackProvider`，让"加一种能力"变成往容器里多放一个工具。

**Blocked by:** None (can start immediately)

**Status:** resolved

**Demo:** 起应用，两个不同来源的工具（现有的 `querySales` 加一个第二来源的探针工具）同时出现在模型可见的工具清单里，两个都调得到；把其中一个来源的注册去掉，有一条断言立刻红。

- [x] 装配改为收集容器内所有 `ToolCallback` / `ToolCallbackProvider`，不再按"单个 provider 缺失"判条件
- [x] 一条断言钉住"容器里到底有哪些工具"（含现有的 `querySales`）—— 少一个工具必须红，不能靠人发现模型变笨了
- [x] 两个来源同时存在时都在清单里；去掉第一个来源的注册 → 对应断言红（变异检验）
- [x] 只有一个来源时行为与今天一致：M1 的 177 个测试仍全绿
- [x] 工具权限的登记规则不变：漏登记一律按拒绝（GLOSSARY 的"工具权限"）
- [x] 调用预算（每工具 3 / 整轮 6）在多来源下仍生效 —— M1 那条"换了 builder 会静默退回 40/150"的断言仍然绿

**注意:** 这是 prefactor，不是能力。它本身不交付任何用户可见的新能力，交付的是"后面三张票能各自加一种能力而不互相踩"。做完它之前不要开始 02 / 03 / 04。

**不要做:** 不要在这张票里引 MCP 或脚本依赖，也不要决定 Skill 目录长什么样。

## Answer

提交 `dd90a73`（实现）+ `8632892`（消化 review）。183 个测试全绿、skipped 0
（`CLAW4J_POSTGRES_IMAGE=docker.m.daocloud.io/pgvector/pgvector:pg16 ./mvnw clean verify`，
再跑 `./scripts/assert-no-skipped-tests.sh` 报 `tests=183 skipped=0`）。

### 一、两处改动，两处都能让工具静默消失

**① 提供工具那一侧（tools 模块）**：`salesToolCallbackProvider` 上的
`@ConditionalOnMissingBean(ToolCallbackProvider.class)` 语义是"已经有 provider 了就让位"。
M2 要在一个容器里放三种来源，任何一种先注册，先来的那种就没了。
条件换成 `@ConditionalOnBean(ReadOnlySqlExecutor.class)`——**按"本层能不能成立"判，
不按"容器里还有谁"判**。

这条 `@ConditionalOnBean` 依赖本类里两个 `@Bean` 方法的**声明顺序**（执行器在前、来源在后）：
同一个自动配置类里的条件按方法顺序求值，顺序反了条件永远为假，工具静默消失——
正是这个类一直在防的那种失效（它身上已经有一个 `afterName` 的同类坑，见 M1-05）。
顺序被两条测试盯着：有数据源时 `querySales` 在清单里，没有时清单是空的、且上下文不失败。

**② 装配那一侧（agent 模块）**：原来只收 `ToolCallbackProvider`。但三层的形态并不统一：
有的来源导出"一组工具"（provider），有的只导出"一个工具"（单个 `ToolCallback`）。
只收 provider 时，单工具形态**不进清单**——不报错、不告警，只是模型看不见它。
现在两种形态一起收成一个列表。重名不在这里去重：两个来源导出同名工具是配置错误，
该在清单断言上红，而不是被收集点悄悄吞掉一个。

### 二、断言钉在哪儿，是被 review 纠正的

第一版把票面的 Demo 劈成了两半：tools 那条只查容器里的 bean（测不到"装配有没有挂上链"），
agent 那条只有假工具（测不到"第二个来源进来时第一个还在不在"）。
补了第三条——同时起两个自动配置，断言**模型可见的清单**里 `querySales` 与探针工具都在。

"模型可见"不是从容器里读的，是从假模型收到的 `Prompt` 的 `ChatOptions`（`ToolCallingChatOptions`）
上读工具名：**清单里没有的工具，模型根本不会去调**。为此给 `ScriptedChatModel` 加了 `prompts()`。

### 三、调用预算：多来源不重置，但第一版没有测

票面写的是"预算在多来源下仍生效"，第一版只靠推理（预算挂在 `ToolCallingManager` 上，
与来源无关）。review 指出这是空的——"新增一种来源"正是改装配的入口，最容易顺手绕过去。
补了一条：三个工具分在两个来源里（provider 出 alpha/beta、单工具 bean 出 gamma），
各调 2 次共 6 次，第 7 次落在 gamma 的第 3 次上，只有总数上限能拦。

| 变异 | 结果 |
| --- | --- |
| 恢复 `@ConditionalOnMissingBean(ToolCallbackProvider.class)` | tools 2 红、agent 1 红 |
| 收集时去掉单个 `ToolCallback` 那一路 | agent 2 红（`No ToolCallback found`） |
| 装配退回单参 `ChatClient.builder(chatModel)` | 预算那条红（7 次调用真的发生了） |

### 四、一条被否掉的自主决定

第一版顺手把执行器从硬依赖降级成 `ObjectProvider`，没有数据源时返回一个空 provider。
review 否掉：这正是票面通篇反对的失效姿势（"工具静默消失，从聊天界面上看不出来"）。
改回硬依赖 + `@ConditionalOnBean`——**没有数据源时是整层能力不存在**（配了库立刻就有），
这与"有工具却被另一个来源挤掉"不是一回事，已在类注释里写清这条区别。

### 留给后面

- 02（MCP）接进来时，Boot 的 MCP 自动配置本身就会注册一个 provider；
  有了这张票它不会再挤掉 `querySales`，但要确认它注册的形态是 provider 还是单个回调，
  两种现在都收得下。
- `ToolPermissionRegistry` 的合并语义没动：多个来源各登记各的，鉴权按名字认领。
  两个来源登记了同名工具的不同权限时，谁赢没有定论——02/03 落地时如果真撞上，补一条断言。
- 生产侧目前仍只有一个来源，"两个来源"的演示由测试给出。
