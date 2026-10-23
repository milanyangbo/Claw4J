# 07: 场景迁移、文档收尾与真实模型联调

**What to build:** 把 ADR-0003 定的 8 个保留场景里剩下的迁完，把本轮在票内定下的决策写进 ADR / README / GLOSSARY，并用真实模型把三层能力跑一遍。

**Blocked by:** 02, 03, 04, 05, 06

**Status:** ready-for-agent

**Demo:** 真实模型下，三层各调一次（Java `@Tool` / MCP / 脚本），一个 Skill 被选中，一个 Subagent 被委派；README 里录的是真实 curl 输出而不是占位符。

- [ ] 保留场景迁完（ADR-0003 的清单）；真实外部 CLI 的 MCP server（`gh` 等）只做演示，不进 CI
- [ ] 复核调用预算覆盖三层：M2 新增来源之后，M1 那条"换了 builder 会静默退回 40/150"的断言仍然绿
- [ ] 复核鉴权覆盖三层：每层新工具都登记了权限，漏登记的后果是拒绝
- [ ] ADR 留痕：本轮在票内定下的决策（沙箱形态、执行后端、Skill 目录与加载时机、Subagent 的模型选择、静态检查清单范围）写进对应 ADR，不要留在 `.scratch/` 里
- [ ] GLOSSARY：核对 M2 新增或细化的术语（Skill / Subagent / Skill Generator / Script Package），把"两个执行位置"这类新事实补进去
- [ ] README：进度改到 M2、补"能力三层"一节、新的演示命令、测试计数更新
- [ ] 真实模型联调用 profile 隔离，默认不进 CI（ADR-0006、ADR-0008）
- [ ] 收尾时 `mvn verify` 全绿，且 `./scripts/assert-no-skipped-tests.sh` 报 `skipped=0`

**注意:** 真实调用不进 CI，它是一次性的闭环证明，不是回归基线。回归基线是前面六张票留下的测试（M1 收尾时是 177 个）。

**不要做:** 飞书长连接（另立 spec）；A2A 与可观测（M3）。
