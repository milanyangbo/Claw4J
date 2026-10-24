# AGENTS.md

## Agent skills

### Issue tracker

Issues and specs live as markdown files under `.scratch/<feature-slug>/`. See `docs/agents/issue-tracker.md`.

### Triage labels

Five canonical triage roles with the default vocabulary (`needs-triage`, `needs-info`, `ready-for-agent`, `ready-for-human`, `wontfix`), recorded as a `Status:` line in each issue file. See `docs/agents/triage-labels.md`.

### Domain docs

Single-context layout: one `GLOSSARY.md` at the repo root plus `docs/adr/`. See `docs/agents/domain.md`.

### Coding standards

本仓库没有 CONTRIBUTING.md。`CODING_STANDARDS.md` 只收机器查不了的判断项
（红线怎么改、闸门断言怎么成对、偏差记在哪）；查得了的都在架构测试与源码扫描里，那里是它们的唯一来源。

写代码与评审的判据主要在 `docs/adr/`：**改命令行或起进程前读 0002，碰鉴权读 0007，
碰模块边界读 0010，写测试前读 0011**。它们就是这里的标准——仓库没有第二份。
