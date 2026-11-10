# 05 · 压缩窗口从配置读，并在启动时说出来

**Status:** done（2026-11-10 实现并验证）
**Blocked by:** 无

**裁决来源:** 不是 ADR-0026（那份只钉"会话是什么"，全篇没有窗口/压缩/token 的任何一句——
上一版票面引错了）。真正的出处是 `docs/reviews/2026-11-05-openclaw-alignment.md` 第 5.4 节
"压缩窗口写死"，以及 `docs/agents/alignment.md` 的三条判据。

## 一、为什么

`SessionMemoryProperties.DEFAULT_CONTEXT_WINDOW = 65536`，注释写着"deepseek-chat 的上下文窗口是 64k"。
`application.yml` 的 `claw4j.session` 下只写了 `cleanup-interval`，所以跑的是这个默认值，
触发阈值 = `65536 × 0.5 = 32768` token。

问题不在数字，在**它不跟着模型走**，而且没人看得见：

- 换到 128k 的模型（千问那条备线）：压得太早——每段对话多压一次，丢细节、多花一次摘要调用；
- 换到 32k 的模型：压得太晚——压完这一轮仍然可能撞窗口，而这时的失败姿势是供应商那边报错，
  不是我们这边一句"上下文太长"。

最低限度是让它**可见**：写在配置里，启动时打一行。

## 二、评审时去 OpenClaw 核到的四件事（都带出处）

| # | 它的事实 | 出处 | 对我们的约束 |
| --- | --- | --- | --- |
| 1 | `contextWindow` 是**模型条目**的元数据；另有 `contextTokens` 作主动输入上限 | `docs/gateway/config-tools/custom-providers.md:153-154` | 窗口不属于"会话"这个命名空间。我们没有模型目录，只能暂放 `claw4j.session`，**并写明是临时的** |
| 2 | 阈值是**算出来的**：`min(75% 窗口, 窗口 − reserve)`，reserve = `min(20000, 窗口/4)` → 恒等于 **75%** | `docs/reference/session-management-compaction/compaction.md:52,54-55,96` | `trigger-ratio` 这种旋钮**它没有**（`src/config/zod-schema.agent-defaults-base.ts:133-175` 的 compaction 段里没有任何比例或窗口字段）。我们把它写进 yml 等于给一个它刻意不暴露的旋钮开配置面 |
| 3 | 压缩保留量是 `keepRecentTokens`，默认 **20000 token** | `docs/gateway/config-agents/heartbeat-compaction-and-streaming.md:100` | 我们是 `events-to-keep: 20` **条事件**——框架的 `RecursiveSummarizationCompactionStrategy` 只给条数。差异记账，本票改不了 |
| 4 | 可见性靠**目录/状态面**（`openclaw models list` 同时显示两者、协议里有 `contextWindows`），不是启动日志 | `docs/gateway/config-tools/custom-providers.md:154`、`docs/gateway/protocol/rpc-bootstrap-and-events.md:53` | 我们没有 `models` 命令，等价物就是启动日志那一行。这条要写明是"等价物"，不是照抄 |

## 三、落点（按上面四条改过）

1. `application.yml` 的 `claw4j.session` 下**只加一行窗口**：

   ```yaml
   # 主模型的上下文窗口（token）。这是模型的元数据，不是会话的偏好：OpenClaw 放在模型条目上
   # （models.providers.*.models[].contextWindow），我们没有模型目录，暂放这里，有了目录要挪过去。
   # 压缩阈值 = 它 × 0.5（比例刻意不做配置项）；启动日志把三个数一起打出来。
   context-window: 65536
   ```

2. `SessionMemoryConfiguration#sessionCompactor` 装配时打一行 INFO（三个数都来自 properties，不写死）：

   ```text
   会话压缩已启用：窗口 65536 token、到 32768 触发（50%）、保留最近 20 条事件
   ```

3. **不把 `trigger-ratio` / `events-to-keep` 写进 yml**（与上一版的差别）：理由见第二节第 2、3 行，
   以及 ADR-0028 对 `memory.search.chunking` 的同一条口径——旋钮越多，"结果为什么变了"越难回答。
   两个字段仍然是可绑定的（record 组件在那儿），只是**不作为部署面暴露**；要知道生效值，看日志。

4. **不改 0.5**（与上一版一致，但理由要写清）：它按 75% 触发的前提是有 overflow recovery
   （供应商报 context 溢出 → 压缩后重试，`compaction.md:44`）。我们**全仓没有**这个处理
   （`grep 'context length|request_too_large|context overflow'` 命中 0），所以留一半当安全边际。

## 四、与 OpenClaw 的三处偏差（记账，不是缺陷）

| 偏差 | 它 | 我们 | 退场条件 |
| --- | --- | --- | --- |
| 窗口放哪 | 模型条目上 | `claw4j.session.context-window` | 有了模型目录就挪过去，键名保持 |
| 触发比例 | 75%（算出来的，无配置项） | 50%（代码默认） | 先有 overflow recovery，再谈调高 |
| 保留量口径 | `keepRecentTokens`（token） | `events-to-keep`（条） | 框架策略支持 token 保留量时换 |

## 五、TDD 用例清单

| # | 判什么行为 | 用例 | 先红的样子 |
| --- | --- | --- | --- |
| 1 | 窗口从**这一层**的键读：`claw4j.session.context-window=131072` → 阈值 65536（默认是 32768） | `SessionMemoryTest#configuredContextWindowIsWhatTheStartupLineSays` | 把 `ApplicationContextRunner` 的属性写成 `claw4j.context-window`（或换任意别的层级）→ 仍取默认值 32768 → 红 |
| 2 | 启动那行打的是**生效值**，不是写死的字面量 | 同上（同一个用例的后半段，`OutputCaptureExtension` 抓 `sessionCompactor(...)` 的输出） | 把日志里的三个参数改成常量 `65536 / 32768 / 20` → 传 131072 的 properties 时日志仍是 65536 → 红 |
| 3 | `application.yml` 里这一行在 `claw4j.session` 之下、且等于代码默认 | `BuildContractTest#applicationYmlDeclaresTheContextWindow` | 删掉那一行，或把键缩进错一层（变成 `claw4j.context-window`）→ 红 |

为腾位（`SessionMemoryTest` 已 20/20）：把"会话还没建出来时不记失败"与"摘要模型挂了只留痕"合成一条
——同一个被测对象（压缩失败的两种口径），条数不变。

## 六、验收判据

- [x] 三条用例先红后绿；用例 1、2 的反例各只红对应那一条（实测见 Comments）
- [x] `application.yml` 里 `claw4j.session` 下只有 `context-window` 一个窗口相关的键
      （`trigger-ratio` / `events-to-keep` 不出现——出现了就是又开了它没有的旋钮）
- [x] 启动日志那一行的三个数都来自 properties（反例见用例 2）
- [x] `./scripts/verify.sh` 全绿、跳过 0、覆盖率 ≥60%、文件数 ≤10、单文件 ≤20

## 七、不做什么

- **不建"模型 → 窗口"的映射表**：那需要一份模型目录（OpenClaw 有 `models.providers.*.models[].contextWindow`），
  我们连"当前用的是哪个模型"都只在启动日志里出现过一次。要做是另一张票，前提是先有这个目录。
- **不改成按 token 动态测量**：框架的触发器就是这么算的，重写一个只会多一处口径。
- **不加 overflow recovery**：那是"供应商报溢出 → 压缩 → 重试"整条路，属另一张票；它不在，比例就只能保守。

## Comments

- 2026-11-10 评审：票面原来引的是"ADR-0026 的未决项"，实际那份 ADR 里没有这一项——已改成复盘
  第 5.4 节。同时按 OpenClaw 的 compaction schema 撤掉了"把 `trigger-ratio` 写进 yml"这一条：
  那个旋钮它没有，摆出来就是自创配置面。窗口、比例、保留量三处的偏差记在第四节。
- 2026-11-10 实现：`application.yml` 加 `context-window: 65536`（注释写明"模型元数据、暂放这里、
  有目录就挪走"）；`SessionMemoryConfiguration#sessionCompactor` 装配时打
  `会话压缩已启用：窗口 65536 token、到 32768 触发（50%）、保留最近 20 条事件`；
  `SessionMemoryProperties` 三个参数各补"与它的差别"。用例：`SessionMemoryTest` 两条
  （并成一条腾位 + 新增一条，仍是 20）、`BuildContractTest` 一条（13 → 14）。
- 反例三条，各只红一条：
  ① 属性名改成 `claw4j.context-window` → 阈值 32768（expected 65536）；
  ② 日志三个参数写成字面量 `65536, 32768, 50, 20` → 传 131072 时日志不含"窗口 131072 token"；
  ③ 删掉 yml 那一行 → `expected: 65536 but was: null`。
- 门禁：674 条（打印值）、失败 0、跳过 0；core 87.0 / channel 78.6 / capability 85.8 /
  agent 69.8 / example-sales 93.8 / example-mcp-server 75.0 / app 80.8 / eval 82.6；
  文件数 10/10/10，单文件最大 20。
- **门禁打印的用例数偏低 13 条**（不是本次引入）：`BuildContractTest` 的 surefire 报告里
  `tests` 属性写着 2，而文件里有 15 个 `<testcase>`；`verify.sh` 累加的是属性，所以少算。
  按 `<testcase>` 数实际是 687 条（680 个 `@Test` + 7 条 ArchUnit 规则）。
  **同日裁决：条数不重要，直接去掉**——`verify.sh` 不再打印用例数，只守"失败 / 错误 / 跳过都为 0"
  与覆盖率；`CONTRIBUTING` 那一行同步改写。
