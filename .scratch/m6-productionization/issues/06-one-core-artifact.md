# 06 · 核心只有一个 artifact：`claw4j-channel-core` → `claw4j-core`，`claw4j-domain` 并进来

**Status:** in-progress

## 为什么

`02` 把入站编排从 `channel.feishu` 提出来时，新建了 `claw4j-channel-core`，而它与
`claw4j-domain` 的依赖姿态是同一个：不依赖 Spring、不依赖任何 IM SDK。于是"零框架"这件事
被劈成了两个 artifact，代价落在**别人**身上：

- 接一个新通道要同时依赖 `claw4j-domain` 与 `claw4j-channel-core`，两个坐标，缺一个就编译不过；
- 写一个工具插件要依赖 `claw4j-domain`，而"通道契约在另一个 jar 里"这件事看 pom 看不出来；
- 两个模块各有一份几乎一样的 enforcer（不许引 Spring / Spring AI / SDK），改一处忘一处就会漏。

ADR-0010 自己给过拆模块的判据：**独立发布周期、独立 schema/团队、两个以上互不相干的变化原因
且都过千行**。`domain`（9 个类、零依赖）与 `channel.kernel`（5 个类、只依赖 domain + slf4j）
三条都不占——它们本来就是同一件东西的两半：**框架的契约与不变量**。

## 做什么

1. `claw4j-channel-core` 改名为 `claw4j-core`（artifactId、`<name>`、description、父 POM 的
   `modules`、`scripts/verify.sh` 的 `MODULES`）。
2. `claw4j-domain` 的源码与测试整棵搬进 `claw4j-core`，删掉 `claw4j-domain` 模块。
3. domain 的 enforcer（`bannedDependencies`：Spring / Spring AI / 厂商 SDK / 响应式库）
   搬进 core 的 pom——一条规则守一整个 artifact。
4. 依赖 `claw4j-domain` 的五个模块改依赖 `claw4j-core`；`claw4j-channel` 原本同时依赖两者，
   改成一条。
5. 边界测试：`KernelBoundaryTest` 改名为 `CoreBoundaryTest`；`DomainBoundaryTest` 原样保留
   （它守的是"domain 连 slf4j 都不许依赖"，比 core 的白名单更严，不能因为合模块就放松）。
6. ADR-0015 记录这次合并与它的代价；ADR-0010 的模块清单加一行指向。

## 不做什么

- **不改包名**。`io.github.claw4j.domain` 与 `io.github.claw4j.channel.*` 保持不变：
  `domain.port` 是 ADR-0010 里"接缝"的专有叫法，改包名等于把那份文档里的每句话都作废；
  而且五个模块的 import 一个都不用动。
- 不把 `claw4j-channel` 的实现（HTTP / 飞书）也并进来——那才是真的把 SDK 拖进核心。
- 不顺手拆 `claw4j-capability`（46 个类那件事另算）。

## 代价（写在这里，不让它以后变成"意外"）

合并后一个**只写工具插件**的人依赖 `claw4j-core`，比原来的 `claw4j-domain` 多拿到两样东西：
`slf4j-api` 与 `channel.spi` / `channel.kernel` 的类。两者都不带框架、不进 Spring 容器，
但"插件的最小依赖面"确实从 9 个零依赖类变成了一个核心 jar。

真到了需要"最小面"的那天，正确的做法是抽一个只装契约的 `claw4j-api`，
而不是把 `domain` 从 `core` 里再劈回去——劈回去就会重新出现"两个坐标缺一个编不过"。

## 判据

- `claw4j-domain` 目录消失，全仓搜不到 `claw4j-domain` 这个坐标（文档里的历史叙述除外）。
- `claw4j-core` 的依赖树里没有 Spring、没有任何厂商 SDK（enforcer 在 `validate` 阶段就拦）。
- 门禁全绿，覆盖率不掉到 60% 以下。
- fat jar 起得来，三个 MCP server 照样 `started`——**模块改名不该改变运行时行为**。
