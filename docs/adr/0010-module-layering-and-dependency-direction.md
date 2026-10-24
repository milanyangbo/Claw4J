# 模块分层：五个模块、通道按包隔离、边界由机器校验

---
status: accepted
date: 2026-10-22
---

模块数从七个收敛到五个：**`claw4j-domain` / `claw4j-agent` / `claw4j-channel` / `claw4j-tools` / `claw4j-app`**。通道按平台分包（`channel.spi` / `channel.http` / `channel.feishu` / `channel.a2a`），不再按平台分模块。模块之间的依赖方向单向，并且用 ArchUnit 写成会失败的测试，不靠口头约定。

ADR-0004 原本写的就是这五个模块；后来在写通道选型时偏离成 `channel-api` + `channel-http` + `channel-feishu` 三个。本条把口径改回来，并说明理由。

## Context

拆分发生时三个通道模块**一个 Java 文件都没有**——隔离是为想象中的未来付的现实成本。真正需要回答的是两个问题：加一个通道要不要加一个模块？以及合并之后边界由谁守？

第二个问题更实际：合并的动机是"飞书 SDK 不要污染只用 HTTP 的部署"，这个诉求是真实的，不能因为合并就丢掉。

## Considered Options

**每个通道一个模块（否决）**
隔离最彻底，但代价是每加一个通道都要新建 POM、改父 POM 的 `modules`、改 CI 矩阵，反应堆也变慢。而换来的隔离是模块级的粗粒度——它拦不住"SPI 包里混进一个飞书 import"，因为那属于同一个模块内部的事。更要命的是：三个通道其实共享同一套入站/出站语义、同一套幂等与限流策略，按模块拆开后这些公共部分要么重复实现，要么再抽一个 `-api` 模块出来，于是又回到 `-api` + 实现的老路。

**单模块、不加约束（否决）**
`oapi-sdk` 随 `claw4j-channel` 传递给每个下游模块，只用 HTTP 的部署也带着飞书的传递依赖。

**单模块 + optional 依赖 + 包级 ArchUnit 规则（采纳）**
平台 SDK 以 `<optional>true</optional>` 引入，不传递给下游；需要它的部署在 `claw4j-app` 里显式声明（见 `channel-feishu` profile）。边界改由包级规则守住，粒度比模块更细。

## Decision

### 依赖方向

```
        channel ──► domain ◄── agent
                       ▲          ▲
                       │          │
                       └── tools ─┘
                       ▲
                       │
                      app（唯一知道所有模块的地方，负责接线）
```

- `claw4j-domain` 不依赖任何框架，是唯一零框架模块。
- `claw4j-agent` 与 `claw4j-channel` **互不依赖**。这条是本轮最重要的修正：之前两者之间没有任何依赖边，"入站消息怎么交给 Agent"根本没定义，等到 M1 写 HTTP 通道时必然要临时拍脑袋。
- 接缝放在 `domain.port`：`AgentPort`（行为）+ `InboundMessage` / `OutboundMessage`（数据）。两边都只朝 domain 依赖，装配权交给 app。
- `claw4j-app` 是唯一同时依赖四个模块的地方，也是唯一打 fat jar 的模块。

### 为什么端口放在 domain 而不是调用方

`AgentPort` 若定义在 `claw4j-agent`，通道就必须依赖 agent，于是 agent 的任何改动都会波及所有通道。放在 domain，加一个通道不需要碰 agent，换一个 Agent 实现不需要碰 channel。这是六边形里端口与适配器的常规摆法。

代价：domain 承诺不依赖框架，所以流式不能用 Reactor 的 `Flux`，只能用 `Consumer<OutboundMessage>`。背压交给通道侧自己处理——对 IM 场景（一条消息一次回答）足够，不值得为它破坏零框架承诺。

### 什么时候该拆模块

给出判据，避免以后重复讨论：

- 一个模块需要**独立的发布周期或版本号**时拆。
- 一个模块需要**独立的持久化 schema 或独立团队**时拆。
- 一个模块内出现**两个以上互不相干的变化原因**且都超过千行时拆。

当前不满足任何一条：全仓库 Java 不到 400 行，五个模块已经偏多。M2 引入 Skill 引擎与 MCP client 后如果 `claw4j-agent` 明显膨胀，再按判据评估拆 `claw4j-skills`，不预先拆。

## Consequences

- 五个模块，父 POM 的 `modules` 不再随通道数量增长。
- 新增一个通道的成本变成：在 `claw4j-channel` 下加一个包 + 实现 `ChatChannel` + 平台 SDK 声明为 optional。不需要动父 POM 和 CI。
- 边界规则落在四处 ArchUnit 测试里：`ArchitectureTest`（domain 零框架）、`AgentArchitectureTest`（agent 不认识 channel）、`ChannelArchitectureTest`（SPI 平台中立、飞书 SDK 只在 feishu 包）、`ToolsArchitectureTest`（tools 不认识 agent 与 channel）。它们随 `mvn test` 跑、提交前 hook 也跑，
红了就是架构被破坏了。
- `oapi-sdk` 在 `claw4j-channel` 里是 optional，默认不进 fat jar；要用飞书必须 `-Pchannel-feishu`。这条也意味着：只用 HTTP 的部署不会被飞书的传递依赖污染。
- `claw4j-tools` 尚未被 `claw4j-agent` 依赖（装配线还没接）。M1 补 `agent → tools`，方向不变。
