# 04: Agent Skills 渐进式披露

**What to build:** 让模型能**选中一个做法并按它编排工具**：`SKILL.md` 描述何时用、按什么流程用；启动时只加载元数据（`name` + `description`），命中才加载全文。Skill 不提供动作，它编排动作（GLOSSARY 的 Skill 定义）。

**Blocked by:** 01

**Status:** closed

**Demo:** 问一个命中某 Skill 的问题，模型选中它、按它写的顺序调工具（先用现有的 `querySales` 就够）、给出回答。同时演示"没命中的那个 Skill 的全文没有进上下文"。

- [x] `SKILL.md` 的加载：启动只加载元数据，命中才加载全文（渐进式披露）
- [x] 一条断言钉住"未命中的 Skill 全文不进上下文"（断言拼给模型的提示里没有它的正文）
- [x] 端到端（`AgentPort`）：模型选中 Skill 并按它编排；同步与流式都要
- [x] Skill 作为工具回调进 01 那条清单断言，并登记 `ToolPermission`（无业务表 + 沙箱目录）
- [x] 社区包 `spring-ai-agent-utils`（0.13.0）的 `SkillsTool` / `Skills` 在这一张引入，坐标现场核验；`SkillsTool.Builder.build()` 直接返回 `ToolCallback`，不要再包一层自己的工具类型
- [x] 变异检验：改成"启动时全文加载" → 渐进式披露那条断言红

**注意:** ADR-0008 把 Agent Skills 列为四项做深之二。做深的点不是"能读一个 markdown 文件"，是"命中才加载"与"Skill 编排工具"这两件事各有断言。

**不要做:** Subagent 编排（05）；Skill Generator（06）。

---

## Answer

提交 `da525d0`（实现）+ 记忆。244 个测试全绿、skipped 0（229 → 244，新增 15 条）。

### 落在哪

`claw4j-tools` 新增 `io.github.claw4j.tools.skill`：

| 类 | 职责 |
| --- | --- |
| `SkillCatalog` | 加载一个目录树里所有 `SKILL.md`；目录不存在或一份都没有即失败 |
| `AgentSkills` | 接缝：目录 → `ToolCallback`；常量 `TOOL_NAME`（`"Skill"`）与 `ACTION`（`"use_skill"`） |
| `SkillToolAutoConfiguration` | 配了 `claw4j.skills.directory` 才有这一层；同时登记权限 |

**没有自己再包一层工具类型**，按票面用了社区包 `SkillsTool.Builder.build()` 直接返回的
`ToolCallback`。理由不是"少写代码"：渐进式披露的判据落在"工具描述里有什么"上，
自己包一层就多出一个要同步维护的描述字符串，也就多一个正文可以偷偷溜进上下文的地方。
工具名由社区包定死为 `"Skill"`，所以 `AgentSkills.TOOL_NAME` 只留一份引用——
清单断言与权限登记都要用它，写散了之后改名字的代价是"某个工具忽然没人认领"。

### 坐标核验

`org.springaicommunity:spring-ai-agent-utils:0.13.0`（03 号票已引入，坐标当时核过）。
这一张用的是两个类：`org.springaicommunity.agent.utils.Skills`（`loadDirectory`）与
`org.springaicommunity.agent.tools.SkillsTool`（`builder().addSkillsDirectory().build()`）。
`Skill.toXml()` 只渲染 front matter，正文不在里面——渐进式披露是它自带的形状，
本项目要做的是**别把它改坏**，并且钉住它。

### 两条断言，两个方向

渐进式披露只钉"正文没进来"是不够的："谁的正文都没进来"（比如这一层压根没生效）也能全绿。

- `AgentSkillsTest`（不连模型，量的是拼给模型的字符串）：
  描述里**有**两份 Skill 的 `<name>` 与描述；描述里**没有任何一份正文**；
  用名字调用才取回正文，且只取回那一份。
- `AgentSkillsEndToEndTest`：把探针记下的每一轮请求摊平成一个字符串
  （系统提示、每条消息、工具调用、工具结果、清单里每个工具的名字/描述/Schema），
  断言两个 Skill 的元数据都在、**没被选中那一份的正文不在里面**。
  那条"元数据都在"就是正向对照：转储要是空的，它先红。

摊得这么全是因为漏洞可能出现在任意一个位置，只查一处，漏的那处就是假绿。

### 端到端盯的是"按它编排"，不是"它读到了"

`SKILL.md` 的正文写"先调用 querySales，再把标记原样带上"，断言两条：
回答里有那个**随机标记**（只有真加载了才拿得到），以及这一轮 `querySales` 真的被调过
（用替身执行器计数）。只验标记的话，模型凭常识也能写出一段像样的简报；
只验调过 querySales 的话，"按流程来"这几个字它就未必照做了。

**有过一次假绿要记账**：`executor.calls()` 的断言最初写在 `RealModelSupport.eventually`
**外面**，于是重试只兜住了回答，兜不住"这一轮到底调没调工具"——全量运行时它红了一次。
改成把两条断言一起放进重试里，并且用"本轮前后的计数差"而不是累计值，
否则前一次尝试调过、这一次没调也会算过。

### 权限：`use_skill`，零张业务表

登记的是动作 `use_skill` 而不是 `read`：读一张表与"把一份流程指令放进上下文"是两件事。
表一张都没有，这不是"没有约束"而是一条声明——**Skill 这一层不碰库**。
于是表级受限的身份（只看得到 `region` 的 `guest`）照样能按 Skill 办事，
它在 Skill 让它去碰别的表那一步被挡住，而不是在"选中做法"这步就被挡住。

**"沙箱目录"这一条的落点**：Skill 的结果里会宣一句"这个 Skill 的目录在哪"
（那里可能有脚本与数据文件）。这个映射是社区包的 `Workspace#display` 接缝，
本地执行时是恒等映射（宿主路径即脚本能访问的路径），换成容器沙箱时换掉实现即可——
模型不会被喂一个它用不到的路径。"技能目录必须落在沙箱根目录内"这条约束没有在这一张做：
它要等 06 把 Skill 与脚本包放进同一棵目录树，现在做是凭空加耦合。

应用侧给了 `read-only` 与 `guest` 两个能读的角色 `use_skill`（`admin` 的 `"*"` 含它）。
理由：加载一份做法既不读表也不写东西，Skill 只是把顺序写出来，真正碰表的那一步仍各自鉴权。
`guest` 的受限因此仍然纯粹是表级的——那是它要演示的那一件事。

### 变异检验

| 变异 | 结果 |
| --- | --- |
| 把正文拼进工具描述（"启动时全文加载"） | `AgentSkillsTest.描述里没有正文` 红 + `AgentSkillsEndToEndTest.未命中的全文不进上下文` 红 |
| 去掉权限登记那个 `@Bean` | 同步与流式两条端到端红（Skill 工具变成"未登记权限"） |
| 给权限登记一张业务表 `sales_order` | `AgentSkillsTest.没有业务表` 红（表级受限的身份被挡住） |

### 没做的

- 模型自己产出 SKILL.md（06）；
- 技能目录与沙箱目录的合一（06）；
- Skill 调用计入预算：它由 `ToolCallingManager` 统一管，与 MCP / 脚本层同一条路径，
  没有为它单独加断言——预算那条断言在 02 已经证明"新增来源不得把预算打开"。
