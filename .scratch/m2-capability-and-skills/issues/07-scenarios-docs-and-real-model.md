# 07: 场景迁移、文档收尾与真实模型联调

**What to build:** 把 ADR-0003 定的 8 个保留场景里剩下的迁完，把本轮在票内定下的决策写进 ADR / README / GLOSSARY，并用真实模型把三层能力跑一遍。

**Blocked by:** 02, 03, 04, 05, 06

**Status:** closed

**Demo:** 真实模型下，三层各调一次（Java `@Tool` / MCP / 脚本），一个 Skill 被选中，一个 Subagent 被委派；README 里录的是真实 curl 输出而不是占位符。

- [x] 保留场景迁完（ADR-0003 的清单）；真实外部 CLI 的 MCP server（`gh` 等）只做演示，不进 CI
      → 落点：`SystemStatusTools`（一层 `system-status`）、`examples/mcp/` 三个 Python server（二层
      `github` / `unread-mail` / `send-email`）、`examples/scripts/` 三个包（三层
      `weather-forecast` / `summarize` / `xurl`）。真实子进程那条打 `real-cli` tag，
      父 pom 的 surefire 默认排除，`-Preal-cli` 才跑。
- [x] 复核调用预算覆盖三层：M2 新增来源之后，M1 那条"换了 builder 会静默退回 40/150"的断言仍然绿
      → `BudgetAcrossLayersTest`：一层（provider 与单回调两种形态）+ 二层（真协议连接）+
      三层（脚本包）同清单，第 7 次调用仍被总数上限拦住。变异（去掉 `maxTotalToolCalls`）红。
- [x] 复核鉴权覆盖三层：每层新工具都登记了权限，漏登记的后果是拒绝
      → `PermissionsAcrossLayersTest`：装配出来的容器上验"每个工具都已登记"，并验
      "拿掉一层的权限表 → 那一层立刻按未登记被拒"、"只读角色调得见但调不动脚本"。
      变异（给某层权限表加 `@ConditionalOnMissingBean`，01 号票那个坑的形状）红两条。
- [x] ADR 留痕：本轮在票内定下的决策（沙箱形态、执行后端、Skill 目录与加载时机、Subagent 的模型选择、静态检查清单范围）写进对应 ADR，不要留在 `.scratch/` 里
      → 新增 ADR-0014（外部 CLI 走 MCP 跨真进程：结构化 argv 与 ADR-0002 的界线、
      环境默认为空、演示不进 CI 的理由与做法、8 个场景的落点表、复核结论）；
      ADR-0002 补一条修订记录（"禁的是交给解释器解释，不是给出 argv 数组"）；
      ADR-0003 补一条修订记录（8 个场景全部落位 + 两处偏差）。
- [x] GLOSSARY：核对 M2 新增或细化的术语（Skill / Subagent / Skill Generator / Script Package），把"两个执行位置"这类新事实补进去
      → 新增"两个执行位置"、"结构化 argv"、"read_system"；MCP server 那条补上
      进程内与真进程两种形态的分工。
- [x] README：进度改到 M2、补"能力三层"一节、新的演示命令、测试计数更新
      → "能力三层"一节补真进程端点那段与 `examples/` 的启用命令；新增
      "一次把三层都接上：真实模型的实录"小节（五条真实 curl 输出 + 一条鉴权拒绝）；
      测试计数 350 → 376，ADR 13 → 14 条。
- [x] 真实模型联调用 profile 隔离，默认不进 CI（ADR-0006、ADR-0008）
      → `McpDemoConfiguration` 只在 `mcp-demo` profile 下生效，三个 server 各一个开关；
      真实 CLI 那一组测试按 tag 排除（不用 `assume`：那会产出 skipped，而本仓库有闸把
      skipped 判成失败）。
- [x] 收尾时 `mvn verify` 全绿，且 `./scripts/assert-no-skipped-tests.sh` 报 `skipped=0`
      → 376 个测试、skipped=0（350 → 376，新增 26）。

## Answer：两处与票面不同的地方

1. **`summarize` 改成了抽取式摘要**。票面写的是"保留脚本本体"，本体是"调火山引擎大模型生成摘要"，
   但脚本层的子进程**只拿得到 `PATH`**（`SandboxPolicy`），任何依赖 API key 的能力在这一层都跑不通。
   抽不出凭据的能力只能做成不需要凭据的形态，或改走 MCP（那边可以显式给环境）。
   这条不是票内偏好，是被执行环境逼出来的事实，写进了 ADR-0014 与 GLOSSARY 的"两个执行位置"。
2. **`system-status` 从 Python + `psutil` 迁成纯 JDK 读数**。它要的指标本来就在本进程里；
   1.x 那个脚本缺 `psutil` 时还会自己 `pip install`。为了读几个数字先起一个 Python 进程，
   是这一层不该付的代价。容器限额读不到时如实写"未设限"（`max` 与 v1 的天文数字都归一成"没限"），
   有一条断言盯着不许把它当数字用。

真实子进程那条链路在本机实测过（5 条断言，`demo_now` 两次调用的 pid 与时间戳不同，
证明那里真的有一个进程），README 的实录也是那趟跑出来的；
它不进 CI 的理由是依赖本机装了 python3 与那些 CLI，不是"跑过一次就算"。

**注意:** 真实调用不进 CI，它是一次性的闭环证明，不是回归基线。回归基线是前面六张票留下的测试（M1 收尾时是 177 个）。

**不要做:** 飞书长连接（另立 spec）；A2A 与可观测（M3）。
