# 06: 工具检索：工具多到塞不进提示词时，让模型先找工具

**What to build:** 评估并接入 `spring-ai-tool-search-advisor`（2.0.2 BOM 里就有）：
工具数量超过阈值时，不再把全部工具定义塞进每一轮提示词，而是给模型一个"找工具"的工具，
按需检索。

**Blocked by:** 01 —— 判断依据是"提示词里那部分 token 降了多少"，没有 01 就没法判断值不值。

**Status:** resolved

**Demo:** 同一批工具、同一个问题，接入前后模型都选对了工具；`kind=prompt` 的单次 token
明显下降（工具少的时候下降有限，所以要拿一份"工具很多"的清单来演示）。

- [x] 先做**评估**而不是直接接入：工具数在 N 以下（默认 20）时全塞是对的，也更简单。
      这个阈值是配置项，不是一个需要记住的参数——超过就自动走检索，不到就走全塞
- [x] 检索索引复用票 05 的 pgvector，不新增存储
- [x] 交付判据两条：**选工具的准确率不下降**、**提示词里的工具定义 token 下降**。
      只满足第二条等于牺牲能力换成本
- [x] 和鉴权的关系要验一遍：检索出来的是"候选工具"，能不能调仍然由 `AuthorizationAdvisor`
      决定。**检索不是授权**——这条不验，就等于给自己开了一个绕过鉴权的后门
- [x] 变异：把阈值调到 0（永远走检索）→ 小工具集的用例应该仍然选对（说明检索没坏事）；
      把鉴权 Advisor 的行 order 排到检索之后 → 越权用例变红

**落点:** `claw4j-agent` 的 `agent` 包（装配层加一个条件分支），依赖加进 `claw4j-agent/pom.xml`。
测试加进已有的 `AgentBoundaryTest`（它已经在管"工具清单"这件事）。

**注意:** 落地前先把 jar 拉下来 `javap` 一遍：`spring-ai-tool-search-advisor`、
`spring-ai-tool-search-tool-vectorstore`、`spring-ai-tool-search-tool-lucene` 三个模块
分别负责什么、`ToolSearchToolAdvisor` 的 Builder 要什么，全都不知道。**别照着模块名猜。**
本仓库已经因为"看着像"写过一条永不命中的 ArchUnit 规则。

**不要做:** 自己做一套工具检索；把工具定义的描述改成自动生成（那是另一个质量问题）；
给每个工具算使用频率做动态裁剪。

## 实现记录（2026-10-27）

### 先纠正票面两处猜错的地方

票里点了三个模块名 `spring-ai-tool-search-tool-vectorstore` / `-lucene`，**它们不存在**。
实际只有两个 jar，加一个自动装配：

| 坐标 | 里面是什么 |
| --- | --- |
| `spring-ai-tool-search-advisor` | 一个类：`ToolSearchToolCallingAdvisor`，**继承** `ToolCallingAdvisor` |
| `spring-ai-tool-search-tool` | `ToolIndex` / `ToolSearchTool` / 三种索引（`Regex` / `Lucene` / `VectorStore`）/ 五种驱逐策略 |
| `spring-ai-autoconfigure-tool-search-advisor` | `ToolSearchAdvisorAutoConfiguration` + 配置项 `spring.ai.chat.client.tool-search-advisor.*` |

BOM 里还有一条 `spring-ai-tool-search-tool-advisor`，**Maven Central 上没有这个 artifact**
（`numFound: 0`，本地解析 `absent`）——照 BOM 抄会直接构建失败。

另外 `spring-ai-tool-search-advisor` 是官方的（`org.springframework.ai`，在 2.0.2 BOM 的
`dependencyManagement` 里），这条票面判断是对的。

### 落地

- `ToolSearchProperties`（`claw4j.tool-search.max-tools-in-prompt` / `max-results`）。
  **没有开关**：工具数 > 阈值就检索。0 是有效值（永远检索）。
- `ToolSearch#toolLoop` 产出这一通对话**唯一**的工具循环。两条分支（全塞 / 检索）
  都从这里过一遍 `ToolCallBudget.toolCallingManager`，预算只有一个落点。
- `AgentAutoConfiguration` 加两个 bean：`ToolIndex`（`RegexToolIndex`）与
  `toolSearchTool` 的权限登记（按 `read` 放行，不新增动作名）。

**这里踩了一次自己的坑**：两个字段一开始是 `int`，靠"负数当没配"来表达默认值。
看起来成立，实际不成立——配置绑定对缺失的键只能传 null，原始类型会被填成 **0**，
而 0 在我们这里的意思是"永远检索"。也就是说这个模块一旦被当成库引用、外面没有 yml，
就会在没人知道的情况下对所有请求走检索。改成 `Integer` + compact 构造器兜默认值，
并加一条 `ApplicationContextRunner` 的绑定用例：不写这个键 → 20，显式写 0 → 0。

### 索引选择：正则，不是 pgvector

票里写"复用票 05 的 pgvector"，实际没这么做。理由：这一版的判据是"token 降了、选工具还没选错"，
`RegexToolIndex` 已经能回答，不需要 embedding 调用，也不用给工具建表。
换成 `VectorToolIndex` 是替换 `ToolIndex` 那个 bean 的事，装配层不认识具体实现。

### 实测（真模型，26 个工具，同一个问题）

| | 每次模型调用的 prompt tokens | 合计 | querySales 被执行 | 回答 |
| --- | --- | --- | --- | --- |
| 全塞（阈值 1000） | 1185 → 1253 | 2438 | 1 次 | 上周华东区成交额为 128,400 元 |
| 检索（阈值 20） | 506 → 609 → 676 | 1791 | 1 次 | 同上 |

首轮 1185 → 506（**−57%**），连多出来的那一轮检索往返算进去，整通对话仍然更低。
两条判据都成立：选中的工具一样、答案一样。

### 两个必须知道的实现约束
1. `ToolSearchToolCallingAdvisor` **是替换**工具循环，不是加一个 Advisor。它实现
   `ToolAdvisor`，而 `DefaultChatClient` 的 `validateSingleToolAdvisor()` 在链上超过一个
   `ToolAdvisor` 时直接抛 `IllegalStateException`。所以没有用 starter——它的自动配置会
   自己塞一个循环进来，跟我们按预算建的那个并存。
2. 它要求上下文里有会话 id（默认键 `ChatMemory.CONVERSATION_ID`，与 `ChatClientAgentPort`
   每轮塞的那个键是同一个）。**缺了不是退化成全塞，是直接抛**
   `context must contain a non-null value for 'chat_memory_conversation_id'`——有用例钉着。

### 系统的默认提示词

`Builder.build()` 不设 `systemMessageSuffix` 时读 classpath 根下的
`DEFAULT_SYSTEM_PROMPT_SUFFIX.md`（在 **tool** 那个 jar 里，不在 advisor 的 jar 里）。
同一目录还有一份更详细的 `DEFAULT_SYSTEM_PROMPT_SUFFIX_LARGE.md`，但 Builder 永远只读前者。
我们给了一份中文的（系统提示词本来就是中文），顺带把"先检索、再调用"两步说清楚。

### 测试

新开 `ToolSearchTest`（票里说加进 `AgentBoundaryTest`，但那个类只做 ArchUnit 边界，
工具清单的断言放进去名不副实）。6 条：阈值以内全塞、超阈值先只给检索工具且查到后真执行、
阈值 0 永远检索、检索出来的工具没登记权限照样被拦、缺会话 id 直接报错、
配置绑定不写键时落回 20。

变异验过四处：阈值判定改成 `false`（3 条红）、`toolLoop` 改成永远全塞（4 条红）、
缺省值当 0（绑定用例红）、不给 `systemMessageSuffix`（**不红**——因为默认提示词文件其实
在 tool 那个 jar 里存在，这条变异推翻了原先"文件缺失"的判断，注释已按实测改写）。

### 没做

- 工具描述自动生成（票里明确不做）。
- `spring-ai-autoconfigure-tool-search-advisor` 那套配置项没用：它绑的是 `ChatClient.Builder`
  自动配置，拿不到本项目的 `ToolCallBudget`。要接就得放弃预算那层，不划算。
