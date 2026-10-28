# 核心只有一个 artifact：`claw4j-core`

---
status: accepted
date: 2026-10-28
---

`claw4j-domain` 与 `claw4j-channel-core` 合成一个模块 **`claw4j-core`**：前者整个并进来，
后者改名。包名一个都不动（`io.github.claw4j.domain` / `io.github.claw4j.channel.*`）。

## Context

M6 把通道契约（`channel.spi`）与入站编排（`channel.kernel`）从 `claw4j-channel` 里抽出来，
新建了 `claw4j-channel-core`，理由是第三方接通道不该被飞书 SDK 污染。那个理由成立，
但它顺手造出了第二个"零框架"的 artifact：

| | `claw4j-domain` | `claw4j-channel-core` |
| --- | --- | --- |
| Spring / Spring AI | 不许 | 不许 |
| IM SDK | 不许 | 不许 |
| 编译期依赖 | 无 | `domain` + slf4j |
| 谁依赖它 | 其余所有模块 | `channel`、`app` |

两列几乎一样。于是代价落到**别人**身上：

- 接一个通道要同时写两个坐标，漏一个就编译不过；
- 写一个工具插件只写 `claw4j-domain`，看 pom 看不出"通道契约在另一个 jar 里"；
- 两份 `bannedDependencies` 规则（不许 Spring / Spring AI / 厂商 SDK / Reactor），改一处忘一处就漏一个洞。

ADR-0010 自己给过拆模块的判据：**独立发布周期**、**独立的 schema 或团队**、
**模块内两个以上互不相干的变化原因且都过千行**。这两者三条都不占——`domain` 是 9 个类、
零依赖；`channel.kernel` 是 5 个类、只依赖 `domain` 与 slf4j。它们是同一件东西的两半：
**框架的契约与不变量**。

## Considered Options

**保持两个模块（现状）**
否决。没有一条拆模块的判据成立，而两个坐标的成本是每次接入都要付的；两份规则的成本是每次
加依赖都要付的。判据写下来就是为了在这种时候用上。

**把 `channel.spi` / `channel.kernel` 并回 `claw4j-domain`**
否决。`domain` 现在的承诺比"零框架"更严一档：**连 slf4j 都不许依赖**（`DomainBoundaryTest`），
它不是洁癖——`AgentPort` 这条接缝落在那里，它一旦认识日志门面，就说明有人在里面写实现而不是写契约。
并入 `kernel` 必然要带 slf4j，那条断言就得删。名字也会开始撒谎：一个装着入站编排的模块不该叫 domain。

**新建 `claw4j-core`，`domain` 并进来、`channel-core` 改名（采纳）**
一个 artifact 装齐"所有别的模块朝它依赖、自己谁也不依赖"的那一层。

**顺手把包名改成 `io.github.claw4j.core.*`**
否决。`domain.port` 是 ADR-0010 里"接缝"的专有叫法，`GLOSSARY.md` 与设计文档里到处在用它；
改包名等于让那些句子全部失效，五个模块的 import 也全要动。换来的只是"包名与 artifact 同名"——
artifact 名是给别人写进 pom 的，包名是给读代码的人看的，两者的职责本来就不同。

## Decision

```
claw4j-core
├── io.github.claw4j.domain          —— 端口（AgentPort / InboundMessage / OutboundMessage）、
│   ├── port                            Principal、权限登记表。连 slf4j 都不许依赖。
│   └── principal
└── io.github.claw4j.channel
    ├── spi                          —— ChatChannel 契约与两个异常
    └── kernel                       —— 入站编排（去重 → 认人 → 异步 → 回推）
```

- 编译期依赖只有 `slf4j-api` 一条（`dependency:tree` 可验）。
- POM 里想加 Spring / Spring AI / 厂商 SDK / Reactor，`maven-enforcer-plugin` 在 `validate`
  阶段就红——`domain` 的那条规则搬到了这里，一条规则守一整个 artifact。
- 两条 ArchUnit 规则分开保留，不合并：
  `DomainBoundaryTest` 管 `domain` **不许依赖任何外部包**（比白名单更严，连日志都不行）；
  `CoreBoundaryTest` 管 `channel` 侧**只许** JDK / slf4j / domain / 自家代码。
  同一个 jar 里有两种强度的边界，是事实，不是重复。
- 五个依赖 `claw4j-domain` 的模块改成依赖 `claw4j-core`；`claw4j-channel` 原本两个都写，现在只写一条。

## Consequences

**代价（写在这里，不让它以后变成"意外"）**：一个只写工具插件的人，依赖面从
"9 个零依赖类"变成"`claw4j-core` + slf4j 一个 jar"。他多拿到 `channel.spi` 与 `channel.kernel`
的类——都不带框架、不进 Spring 容器，但他确实用不上。

真到了需要"最小契约面"的那天（比如给非 Java 客户端生成 SDK），正确做法是抽一个只装契约的
`claw4j-api`，**不是**把 `domain` 从 `core` 里再劈回去：劈回去就会重新出现"两个坐标漏一个编不过"
和"两份 enforcer 规则"。判据还是 ADR-0010 那三条。

其余的后果：

- 仓库的模块数从 8 变 7，父 POM 的 `modules` 与 `scripts/verify.sh` 的 `MODULES` 各少一项。
- 依赖方向不变：`channel → core ← agent`，`capability → core`，接线仍在 `app`。
- 迁移成本被刻意压到最小：包名不动，所以源码只改 javadoc 与两处测试的类名；其余全是 pom 与文档。
- 引用 `claw4j-domain` 的插件 jar 会在升级时编译失败（坐标消失），而不是运行期静默出错——
  在一个还没有外部插件生态的版本上，这是想要的那种失败。
