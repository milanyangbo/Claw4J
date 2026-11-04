# 06 · 核心只有一个 artifact：`claw4j-channel-core` → `claw4j-core`，`claw4j-domain` 并进来

**Status:** resolved

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

---

## 收尾核对（2026-11-04，状态从 `in-progress` 改为 `resolved`）

这张票的实质工作早已完成，只是状态一直挂着 `in-progress`——`docs/agents/review.md` 说的
第一大类缺陷（文档落后于代码）在我自己的票上出现了一次。按判据逐条核对现状：

- **`claw4j-domain` 目录消失** ✓ 根 `pom.xml` 的 `<modules>` 里只有 `claw4j-core`，
  `scripts/verify.sh` 的 `MODULES` 也是七个模块。
- **全仓搜不到 `claw4j-domain` 这个坐标** ✓ 只剩四处历史叙述（`claw4j-core/pom.xml` 的
  enforcer 注释、`domain/package-info.java`、根 `pom.xml` 的模块注释、
  `ChannelBoundaryTest` 的类注释），都不是可解析的坐标。
- **core 的依赖树没有 Spring / 厂商 SDK** ✓ 规则在 `claw4j-core/pom.xml` 的
  `bannedDependencies`（`validate` 阶段），六个模块都依赖 `claw4j-core` 这一个坐标。
- **门禁全绿** ✓ 最近一次：646 条用例、跳过 0，core 行覆盖率 90.2%。
- **fat jar 起得来、三个 MCP server 照样 `started`** ✓ 但**不是自动化断言**，是
  `spec.md` 的「冒烟记录（2026-10-28，四张票全部落地之后）」那一行：
  `claw4j-demo` / `claw4j-github` / `claw4j-mail` 三个都 `MCP server started`，
  那次起的 fat jar 已经是本票改名之后的形态。仓库里 `ApplicationAssemblyTest#mcpServerIsOffUnlessConfigured`
  验的是反向（默认关闭），所以这条判据下次再想确认一次，仍然只能手工起。

### 与票面写法的两处差异（规则没放松，形态变了）

1. 票面第 5 条写 `KernelBoundaryTest` 改名 `CoreBoundaryTest`。**实际类名是
   `CoreBoundariesTest`**（复数），位置在 `io.github.claw4j`（不是某个子包）。
2. 票面第 5 条写 `DomainBoundaryTest` **原样保留**。实际没有单独的文件：它那条规则
   （`io.github.claw4j.domain..` 只许依赖 `java..` 与自身）成了
   `CoreBoundariesTest#domainStaysFrameworkFree` 的第一条——**比 core 的白名单更严**
   （连 slf4j 也不许），所以约束强度没降，只是从"两个文件"变成"一个文件里的两条"。
   合并的直接原因是 core 的测试文件名额（10 个，已满）。
