# 同仓库重建：旧代码移入 legacy/，新代码用 claw4j-* 模块

---
status: accepted
date: 2026-10-22
---

重建不新建仓库。1.x 的源码整体移入仓库根的 `legacy/` 目录（含它自己的 `pom.xml`，但**不作为新父 POM 的模块**，Maven 不会构建它），新代码在根下按 `claw4j-*` 多模块展开。旧代码在 `v1.x-legacy` tag 上已冻结，之后再打一次 tag 标记移动。这样一次 clone 就能看到 before 与 after 的对照，不需要在两个仓库之间跳。

## Considered Options

**新建独立仓库**
否决。before/after 对照是这次重建最重要的物证，拆成两个仓库后，讲演进时要靠口头描述加截图，说服力弱很多。

**开长期分支，主干留旧代码**
否决。分支之间的文件 diff 同样是割裂的，而且主干永远停在 1.x，README 与 CI 都会跟着分裂。

**同仓库 + `legacy/`（采纳）**
代价是仓库里长期躺着 6793 行不再维护的代码，且 Maven、IDE 索引、全局搜索都要显式排除它。换来的是演进路径完整可见。

## Consequences

- 新代码 group id 与根包名统一为 `io.github.claw4j`，与 1.x 的 `com.xteammors.openclaw` 彻底分开，避免同名类混淆。
- 模块划分：`claw4j-domain`（不依赖 Spring AI / Spring Boot）、`claw4j-agent`（ChatClient 装配、Advisor 链、Session、鉴权）、`claw4j-channel`（Channel SPI + HTTP/SSE + A2A）、`claw4j-tools`（`@Tool`、`@McpTool`、Script Package 执行器）、`claw4j-app`（启动、配置、容器编排、测试）。
- 分支约定：`rebuild-spring-ai-2x` 为重建主线，`claw` 分支停在 `v1.x-legacy` 不再提交。
- `legacy/` 不进任何新模块的依赖；CI 只构建 `claw4j-*`，不构建 `legacy/`。
- 1.x 里的明文凭证（Teammors token、IMAP 测试账号）在冻结提交前已替换为占位符，避免进入 git 历史；`legacy/` 目录后续也不允许再放真实凭证。
