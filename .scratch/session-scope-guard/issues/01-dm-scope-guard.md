# 01: `dm-scope=main` 与"多人可及"必须互相拒掉

裁决见 [ADR-0032](../../../docs/adr/0032-dm-scope-cross-check.md)。

**What to build:** 两件相关的事，都在"会话边界"这一层：

1. **`dm-scope` 认不出的值不再静默落回 `main`**，而是启动失败并列出可选值（对齐
   `session.maintenance.mode` 的做法：错的方向必须选得出来）。
2. **装配层交叉校验**：所有已启用通道的准入名单取**并集**，去重后超过 1 个人（或含 `*`）而
   `dm-scope=main` → 拒绝启动，报错说清"这会把多个人的私聊并成一段"。此外把生效值打进启动日志
   （`SessionScopeProperties` 的注释早就承诺了那一行，代码里没有——这次补上）。

## 为什么是"校验"而不是"扫旧键"

键搬家（票 01–03）不动代码逻辑，但"退回 main"这件事的根不在键上：`normalizeDmScope` 把
**没配**与**认不出**都归一成 `main`，而 `main` 在多人部署里等于串话。扫旧键只能覆盖"搬家"这一次，
下次再加键又要改一遍；交叉校验覆盖三种情况——旧键、拼错、真配错——判据是**这份配置自相矛盾**。

## 判据

```text
并集 = 所有「已启用」通道的 users 合并去重   // 通道没启用就不算一道门
main + 并集为空                 → 放行（谁都不能进）
main + 并集 = {一个人}          → 放行（单用户，多通道也只有一个身份）
main + 并集含 "*"               → 拒绝（"不设准入" = 人数未知 = 很多人）
main + 并集去重后 > 1 个人      → 拒绝
非 main（per-peer / per-channel-peer / per-account-channel-peer） → 放行
```

**为什么是并集而不是逐通道**：http 给 alice、飞书给 bob，每条通道各自只有 1 个人，
但两个人共用一段主会话照样串话。

## 放哪

`claw4j-app`。`claw4j-agent` 不认识 channel（ArchUnit 的「agent 不认识 channel」拦着），
而 app 就是拼装面：它依赖 agent 与 channel 两边，能同时拿到 `SessionScopeProperties` 与五个通道
配置。落地成一个 `InitializingBean`：构造期拿到两边，`afterPropertiesSet` 里判定，抛就启动失败。

判"已启用"用 `ObjectProvider.getIfAvailable()`：通道的 `@ConfigurationProperties` bean 只在
`claw4j.channel.<名字>.enabled=true` 时注册，拿不到就是那道门没开。

## 代价

- 本机演示若把某条通道配成 `users: ["*"]`（文档注释里写着"不设准入"），而 `dm-scope` 是默认的
  `main`，**现在起不来了**。这不是误伤：`*` 意味着陌生人也能进来，那就不该共用一段主会话。
  两行改法：把 `users` 收成自己一个人，或把 `dm-scope` 改成 `per-channel-peer`。
- `users: []`（默认）不受影响：一个人都进不来，谈不上串话。
- 生效值日志走 `SessionMemoryConfiguration`（agent 侧），所以 agent 单独跑测试也能看到那一行。

**不改**：`group-scope`。它错的方向是安全的——认不出落 `per-group`（分得开），
而"群并进主会话"必须显式写 `main`，不是默认值。

## TDD 用例清单

| 判什么行为 | 用例名 | 先红的样子 |
| --- | --- | --- |
| 认不出的 `dm-scope` 直接抛，不再落回 `main` | `BuildContractTest#unknownDmScopeIsRejected` | 把 `normalizeDmScope` 的 `default ->` 改回 `return DM_MAIN` → 红 ✅ |
| 两个人 + `main` → 拒绝启动 | `BuildContractTest#multiUserWithMainScopeIsRejected` | 删掉 `verify` 里 `size() > 1` 那一支 → 红 ✅ |
| 一个人（哪怕多通道）+ `main` → 放行 | 同上（同一方法里三种放行情形） | 判据写成"逐通道人数 > 1"（而不是并集）→ 这一支红 ✅ |
| `*` + `main` → 拒绝 | 同上 | 把 `contains("*")` 那一支删掉 → 红 ✅ |
| 两个人 + `per-channel-peer` → 放行 | 同上 | 把 `DM_MAIN.equals(dmScope)` 的判断删掉（变成无条件拒绝）→ 红 ✅ |

**复核：2026-11-20 `./scripts/verify.sh` 全绿（9 模块 0 失败 / 0 错误 / 0 跳过）**；
反例另跑了两次：把 `ApplicationAssemblyTest` 的 `dm-scope` 临时改回 `main`（那份装配有 http /
企业微信 / 钉钉三个用户）→ 上下文初始化失败，报错点名
`[assembly-guard, assembly-wecom, assembly-dingtalk]`，改回 `per-channel-peer` 后恢复绿。

**Status:** resolved
