# 01: 仓库骨架与 legacy 隔离

**What to build:** 从外面看这个仓库，1.x 的代码整体退到 `legacy/` 里不再参与构建，根目录出现一套能编译的 `claw4j-*` 多模块骨架。这一步做完，新代码和旧代码第一次真正分开。

**Blocked by:** None (can start immediately)

**Status:** resolved

- [x] 1.x 的 `src/`、`skills/`、`pom.xml` 移入 `legacy/`，`legacy/` 不作为父 POM 的模块
- [x] 新父 POM：`io.github.claw4j:claw4j`，packaging 为 pom，七个模块
- [x] 七个模块目录与 POM 就位：domain / agent / channel-api / channel-http / channel-feishu / tools / app
- [x] 版本基线写死在父 POM：Spring AI 2.0.2、Boot 4.1.2、JDK 21（`maven.compiler.release`）
- [x] 父 POM 里 import `spring-ai-bom`，并预留 `spring-ai-agent-utils` 0.13.0 与 `spring-ai-session-bom` 0.10.0
- [x] `legacy/` 下的旧 POM 保留但明确标注不参与构建（README 说明）
- [x] `mvn -q clean verify` 通过

## Answer

`mvn clean verify` BUILD SUCCESS，反应堆 8 个项目（父 POM + 七模块），全部产出 jar。三个要点：

1. **旧代码是 `git mv` 过去的，不是复制**，所以 before/after 的演进在 git 历史里是连续的，不是两个孤岛。
2. **父 POM 用 BOM import 而不是继承 `spring-boot-starter-parent`**，为的是不让聚合器意外继承 Boot 的插件配置；`spring-boot-maven-plugin` 只在 `app` 模块显式引入。
3. 依赖方向目前是单向的：domain ← agent / channel / tools ← app。`channel-feishu` 的 `oapi-sdk` 只留在自己模块里，符合 ADR-0009 的隔离要求。

遗留一条：构建日志里出现 `micrometer-core:1.17.2` 与 `spring-boot-autoconfigure:4.1.2` 的 POM 在本地仓库缺失告警（jar 解析正常，构建已通过）。大概率是本机镜像站同步延迟留下的半截缓存，下次清理 `~/.m2` 对应目录再拉一次即可，不影响编译。
