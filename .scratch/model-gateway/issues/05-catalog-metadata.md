# 票 05：目录元数据——context-window 跟着模型走

状态：**完成**（2026-11-22）。裁决：`docs/adr/0035-model-gateway-module.md` 第二节。

## 一、目标

`context-window` 是模型的元数据，现在挂在 `claw4j.advisor.session` 下，降级到备用模型后压缩阈值仍是主模型的。
本票把元数据放回目录行，并为后面的角色、成本、缓存能力留字段。

## 二、范围

| # | 改什么 | 要点 |
| --- | --- | --- |
| 1 | 一家下面加 `models[]` | 字段：`id` / `context-window` / `context-tokens` / `max-tokens` / `cost{input,output,cache-read,cache-write}` / `compat` |
| 2 | 模型行的 `id` 取代单值 `model` | 旧键启动即抛并指出改成什么 |
| 3 | `context-tokens` = 有效提示容量 | 没写就取 `context-window`；它才是上下文重排（票 10）用的那个数 |
| 4 | `cost` **只解析不使用** | 三档字段留着，接账单调 `usage/` 时再算（ADR 第九节） |
| 5 | 会话侧改读目录 | `SessionMemoryProperties.contextWindow` 降级为"目录拿不到时的兜底" |
| 6 | `ModelRef` = `provider/model` | `roles.*.primary` / `fallbacks` 与 `policy.allow` 都接受它；解析不到启动失败并列目录 |

## 三、对照（OpenClaw）

| 它的形态 | 位置 |
| --- | --- |
| 目录行是模型，带 `contextWindow` / `contextWindows`（多档）/ `contextTokens`（有效容量）/ `maxTokens` / `cost` / `compat` | `packages/model-catalog-core/src/model-catalog-types.ts:258-292` |
| 引用写 `provider/model`；别名与 per-model 设置在另一张表 | `docs/concepts/models.md`「Selection order」 |
| 上下文窗口按**当次回答的模型**算 | `docs/concepts/model-failover.md:22-24` |

## TDD 用例清单

| 判什么行为 | 用例名 | 先红的样子 |
| --- | --- | --- |
| 一家能读多张模型表 | `ModelCatalogBindingTest#modelsAreReadById` | 第二张表读不到 |
| 引用指向目录里没有的模型就启动失败并列出可选 | `ModelCatalogBindingTest#unknownModelRefFailsAtStartup` | 静默回落 |
| 压缩阈值来自目录行 | `ModelCatalogBindingTest#contextWindowComesFromCatalogRow` | 读到会话兜底那个值 |
| 没写 `context-tokens` 时等于 `context-window` | `ModelCatalogBindingTest#contextTokensDefaultsToWindow` | 返回 null |
| 目录没写才用会话兜底 | `ModelCatalogBindingTest#sessionContextWindowIsFallbackOnly` | 两处都写时取到会话那个 |
| `cost` 解析成功但没有任何代码消费它 | `ModelCatalogBindingTest#costIsParsedButUnused` | 出现计费调用 |

## 缺口

- `contextWindows` 多档选择、阶梯价、`compat` 的具体字段消费（缓存标记在票 12）本票只解析。
- 「窗口按当次回答的模型算」要等票 10 的候选链落地，本票先保证能读到主模型的窗口。

## 实施记录

- `Provider.model`（单值）删掉，换成 `models[]`：`id` / `context-window` / `context-tokens` /
  `max-tokens` / `cost{input,output,cache-read,cache-write}` / `compat`。窗口必填且为正，
  模型 id 在同一家内不许重。
- 新增 `io.github.claw4j.model.catalog`：`ModelRef`（`provider/model`，只按**第一个**斜杠切，
  模型名里可以带斜杠）、`ModelRow`（元数据，`effectiveContextTokens()` 缺省取总窗口）、
  `ModelCatalog`（摊平成引用 → 行，`primary` 支持 `provider/model` 与只写 `provider` 取首行）。
- **一个目录行一个 ChatModel**（不是一家一个）：上下文窗口是行的属性，共用实例就没法按
  "这次回答用的是哪一行"去算阈值。`ProviderModels` 的键改成 `ModelRef`，route 名与指标
  `route` 标签随之变成 `provider/model`。
- 会话压缩改读目录：`SessionMemoryConfiguration#sessionCompactor` 取 `ObjectProvider<ModelCatalog>`，
  目录有主模型行就用它的 `context-window`；`claw4j.advisor.session.context-window` 降级为兜底
  （`compactionThreshold(int)` 新入口）。`SessionMemoryTest#sessionContextWindowIsFallbackOnly` 钉着。
- `cost` 只解析不消费，`ModelCatalogBindingTest#costIsParsedButUnused` 只断言"解析得出来、
  没配的档位是 null（不拿 0 冒充）"；真正的"没有代码消费它"要靠评审，用例断言不了不存在。
- `roles.*` 还没有（票 12 才引入），本票先把 `ModelRef` 与 `primary` 的 `provider/model` 写法打通，
  白名单（票 11）直接复用 `ModelRef`。
