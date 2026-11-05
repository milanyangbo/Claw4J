# 05 · 压缩窗口从配置读，并在启动时说出来

**Status:** ready-for-agent
**Blocked by:** 无（与 01–04 互不影响，任何一次配置提交都能带上）

## 为什么

`SessionMemoryProperties.DEFAULT_CONTEXT_WINDOW = 65536`，注释写着"deepseek-chat 的上下文窗口是 64k"。
`application.yml` 的 `claw4j.session` 下只写了 `cleanup-interval`，所以跑的是这个默认值，
触发阈值 = `65536 × 0.5 = 32768` token。

问题不在数字，在**它不跟着模型走**，而且没人看得见：

- 换到 128k 的模型（千问那条备线）：压得太早——每段对话多压一次，丢细节、多花一次摘要调用；
- 换到 32k 的模型：压得太晚——压完这一轮仍然可能撞窗口，而这时的失败姿势是供应商那边报错，
  不是我们这边一句"上下文太长"。

ADR-0026 的未决项里记了"要不要按模型目录取窗口"。在那之前，最低限度是让它**可见**：
写在配置里，启动时打一行。

## 落点

1. `application.yml` 的 `claw4j.session` 下显式写：

   ```yaml
   claw4j:
     session:
       # 上下文窗口的 token 数，压缩阈值按它的 trigger-ratio 算（默认 0.5）。
       # 当前主模型 deepseek-chat 是 64k；换模型时这一行要跟着改——
       # 启动日志会把它与算出来的阈值一起打出来，改没改一眼能看见。
       context-window: 65536
       trigger-ratio: 0.5
       events-to-keep: 20
   ```

2. `SessionMemoryConfiguration` 装配压缩器时打一行 INFO：

   ```text
   会话压缩：窗口 65536 token，到 32768（50%）触发，保留最近 20 条
   ```

   三个数都是"改了要重启才生效"的，所以放在启动期而不是每轮。

3. 不加新配置项——`context-window` / `trigger-ratio` / `events-to-keep` 都是 `SessionMemoryProperties`
   上已有的字段，本票只是把前两个写进 yml。

## 不做什么

- **不建"模型 → 窗口"的映射表**：那需要一份模型目录（OpenClaw 有 `models.providers.*.contextWindow`），
  我们连"当前用的是哪个模型"都只在启动日志里出现过一次。要做是另一张票，前提是先有这个目录。
- **不改成按 token 动态测量**：框架的触发器就是这么算的，重写一个只会多一处口径。

## 验收判据

- [ ] **yml 里有这三个数**：`context-window` / `trigger-ratio` / `events-to-keep` 都能在
      `application.yml` 里搜到，且与代码默认值一致（现在 65536 / 0.5 / 20）。
      反例：只写在 yml 不写代码默认值 → 别的 profile 起不来。
- [ ] **启动日志有那一行**：起的上下文里断言日志（或断言 `SessionMemoryProperties#compactionThreshold()`）
      等于 `contextWindow × triggerRatio`。反例：把 yml 的 `context-window` 改成 131072 →
      阈值必须跟着变，不变说明读的还是默认值。
- [ ] **改了配置真的生效**：把 `trigger-ratio` 配成 0.9，`compactionThreshold()` 必须是 58982 而不是 32768。
      反例：配置写在 `claw4j.session` 之外的层级 → 绑定不到，静默走默认值（这正是现在的问题）。
- [ ] `./scripts/verify.sh` 全绿，跳过 0。
