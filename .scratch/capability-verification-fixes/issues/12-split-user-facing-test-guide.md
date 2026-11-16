# 12: 拆分"给用户看的验收流程"与"给 AI 跑的全量回归清单"

**What to build:**
`docs/capability-tests.md` 保留为**机器向**的全量回归清单（每个工具/子 Agent 一行、含数据库断言、
负向用例），并在开头标明它的读者；另建根目录 `README-capability-tests.md`，按
`README-adding-capabilities.md` 的八节顺序，写**用户怎么验自己刚加的那个能力**——
前提、一句触发的话、期望看到什么、不对时先看哪里。

**Blocked by:** 无。

**Type:** docs

**Status:** resolved

**Demo:** 刚按 `README-adding-capabilities.md` 加了一个工具的人，只打开 `README-capability-tests.md`
第 1 节，就能把"它到底生效了没有"验完，不需要读 `docs/capability-tests.md` 里那 20 多行表格。

## 两份文档的分工（写进两份的开头，避免第三份）

| | `README-capability-tests.md` | `docs/capability-tests.md` |
| --- | --- | --- |
| 读者 | 刚加了能力的人 | 跑回归的 AI / 维护者 |
| 粒度 | 八个能力各一条主流程 | 每个工具、每个子 Agent、每个通道各一行 |
| 判据 | 一句触发话 + 期望结果 + 一个计数/日志 | 逐条接口调用、期望值、数据库断言、负向用例 |
| 不含 | 全量清单、负向矩阵、审计/审批/目标那些非"加能力"的话题 | —— |

## 要改的文件

| 文件 | 改什么 |
| --- | --- |
| `README-capability-tests.md`（新建） | 八节 + 一个"共同前提"，每节四段：前提 → 触发 → 期望 → 不对时看哪里 |
| `docs/capability-tests.md` | 顶部加一句"这份是机器向全量清单，用户向流程见 README-capability-tests.md"；顺手把"回一句'这一步做了太多次'"改成实测的框架原文 |
| `README.md` | "门禁与验收"一节加一行指向新文件 |
| `README-adding-capabilities.md` | 八节的"怎么确认"末尾各加一句"完整流程见 README-capability-tests.md 第 N 节" |

## 内容必须与实测一致（八条主干，全部今天跑过）

1. **工具**：三条时间窗（128400 / 99999 / 77777）——只对上一条说明不了时间窗写对。
2. **MCP**：`demo_now` 两次 **pid 相同、时间戳变大**（证明是同一个外部进程的真读数）。
3. **脚本包**：`summarize sample.md` 要**逐字**对上原文；"三句以内"会让模型改写，判不出来。
4. **Skill**：需要先配人设（口径在 persona 的 glossary），否则模型会正确地"先问口径"。
5. **子 Agent**：白名单里的工具必须在容器里，否则**首次委派**失败（不是启动失败）。
6. **人设**：启动日志那行 `agent 是 … 档位 MESSAGING（来自 人设文件的 tier）`。
7. **知识库**：本地 profile 默认开；问一句要出处，再问一个库里没有的要"查不到"。
8. **通道**：HTTP 验问答与无身份被拒；重复事件只对 IM 通道成立（HTTP 没有事件 id）。

## 实现记录

见 `README-capability-tests.md`。三条卫生规则写在"共同前提"里：关语义缓存、每次换用户、
先重灌种子（`docker exec psql` 两条命令，票 10 的脚本还没落地，先给命令）。
