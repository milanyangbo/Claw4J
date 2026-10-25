# AGENTS.md

这一份只做**路由**：每条是一个指针，指到别处真正的内容。
它被推进每一次对话的上下文，所以正文一律不搬进来——AGENTS.md 变长不是变有用，
是每一行都在被反复付费，而真正会漏掉的往往是"还有一份文档存在"这件事。

**本仓库文档一律中文**，英文只出现在代码标识符、ADR 编号、命令与文件名里。

## 按你要做什么，读那一份

- **第一次进这个仓库** → `README.md`（它是什么、怎么跑起来）+ `CONTRIBUTING.md`（怎么验证、门禁有哪些）。
- **动手改代码之前** → `docs/design/pitfalls.md`：**踩过的坑**。这几条现在的形状都正常，
  掉进去的现象又不报错，重犯一次要花同样的时间排查——这是**最该先读的一份**。
- **评审，或判断"这是缺陷还是已知状态"** → `docs/agents/guardrails.md`：已知偏差的清单
  与改红线怎么走。不看它，已记账的偏差会被当成缺陷报出来。
- **写、改、删测试，或判断一条测试该不该留** → `docs/agents/testing.md`：四条判据、
  正反例、组织界限（每模块 ≤10 个文件、每文件 ≤20 个方法、行覆盖率 ≥60%）。
- **本机跑不动，或要改环境与门禁** → `CONTRIBUTING.md`：前置依赖、`.env`、两条校验脚本、
  迁移怎么加、哪道闸在什么时候拦。
- **想知道某个功能是按什么思路设计的** → `docs/design/`：能力三层、鉴权、Text-to-SQL、会话记忆。
- **确认一个说法对不对** → `GLOSSARY.md`，再看 `docs/adr/`。

## Agent skills

这几个 skill 在本仓库的用法，约定本身写在 `docs/agents/` 里：

- **Issue tracker** —— 问题与规格是 markdown 文件：一个功能一个目录 `.scratch/<功能-slug>/`，
  规格本身是里面的 `spec.md`。详见 `docs/agents/issue-tracker.md`。
- **Triage labels** —— 五种分诊状态（`needs-triage`、`needs-info`、`ready-for-agent`、
  `ready-for-human`、`wontfix`），在每个 issue 文件里记成一行 `Status:`。
  详见 `docs/agents/triage-labels.md`。
- **Domain docs** —— 单上下文布局：术语只在根级 `GLOSSARY.md`，决策只在 `docs/adr/`，
  没有 per-context 的 GLOSSARY。详见 `docs/agents/domain.md`。

## ADR：碰到哪儿读哪条

不是"有空读一遍"，是按改动的地方选一条：

- 改命令行、起进程 → **0002**
- 碰鉴权与权限登记表 → **0007**
- 碰模块边界与依赖方向 → **0010**
- 写测试（真模型、数据库、跳过） → **0011**
- 改模型配置（主备、温度、超时、key） → **0006**
- 碰数据库与向量检索 → **0005**
- 碰能力三层、沙箱、Skill / Subagent / 生成器 → **0003、0012、0013、0014**
