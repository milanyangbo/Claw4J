# AGENTS.md

这一份只做**路由**：每条是一个指针，指到别处真正的内容。
它被推进每一次对话的上下文，所以正文一律不搬进来——AGENTS.md 变长不是变有用，
是每一行都在被反复付费，而真正会漏掉的往往是"还有一份文档存在"这件事。

## 按你现在要做什么，选一处读

- **评审，或判断"这是缺陷还是已知状态"** → `docs/agents/guardrails.md`：**已知偏差的清单**
  与改红线怎么走。不看它，已记账的偏差会被当成缺陷报出来。
- **确认一个说法对不对** → `GLOSSARY.md`，再看 `docs/adr/`。

## Agent skills

### Issue tracker

Issues and specs live as markdown files under `.scratch/<feature-slug>/`. See `docs/agents/issue-tracker.md`.

### Triage labels

Five canonical triage roles with the default vocabulary (`needs-triage`, `needs-info`, `ready-for-agent`, `ready-for-human`, `wontfix`), recorded as a `Status:` line in each issue file. See `docs/agents/triage-labels.md`.

### Domain docs

Single-context layout: one `GLOSSARY.md` at the repo root plus `docs/adr/`. See `docs/agents/domain.md`.

### ADR：碰到哪儿读哪条

不是"有空读一遍"，是按改动的地方选一条：

- 改命令行、起进程 → **0002**
- 碰鉴权与权限登记表 → **0007**
- 碰模块边界与依赖方向 → **0010**
- 写测试（真模型、数据库、跳过） → **0011**
- 碰能力三层、沙箱、Skill / Subagent / 生成器 → **0003、0012、0013、0014**
