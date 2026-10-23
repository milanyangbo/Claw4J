# 05: CI 与质量门禁

**What to build:** 任何人 clone 下来跑一条命令，构建、检查、测试全绿；CI 上同样绿，且不会因为没有模型 key 而变红。

**Blocked by:** 02, 03, 04

**Status:** resolved

- [x] CI 只构建 `claw4j-*`，不构建 `legacy/`
- [x] `mvn verify` 在全新环境下通过，测试不依赖网络与真实模型
- [x] 提交前检查能拦住"新代码里出现框架依赖越界"（domain 模块不引入 Spring）
- [x] README 写明：本地怎么起、key 怎么配、legacy 是什么

## 落地

### 一条命令

```
./mvnw verify          # wrapper 固定在 Maven 3.9.16，不依赖机器上装了什么版本
```

wrapper 四个文件全部入库：`mvnw`、`mvnw.cmd`、`.mvn/wrapper/maven-wrapper.properties`、
`.mvn/wrapper/maven-wrapper.jar`（`distributionType=script`）。

为什么连 jar 一起收：它是**运行时**要用的（脚本会去 Central 下载 Maven 发行包，实测删掉也能
BUILD SUCCESS），但那要求能连上 Central。本机出现过 Docker Hub 不可达的情况，
同理中央仓库也可能抽风；一个 60KB 的 jar 换"clone 下来一定能跑"，值得。
国内拉 distribution 慢就改 `maven-wrapper.properties` 里的 `distributionUrl` 换镜像。

### CI（`.github/workflows/ci.yml`）

- JDK 21（temurin）+ `cache: maven`，单 job，`./mvnw -B -ntp verify`
- 一条显式检查拦住"有人把 legacy 加回根 POM 的 modules"——ADR-0004 的这条决定现在会自己报警
- `CLAW4J_MODEL_MOCK: 'true'` 兜底：CI 上没有也不该有 key，测试自己也会强制 mock，双保险
- 并发组按 ref 取消在跑的旧构建

### 四层门禁

| 层 | 拦什么 | 触发时机 |
| --- | --- | --- |
| `mvn verify` | 编译 + 64 个测试 + 打包 | CI 每次 push/PR |
| ArchUnit | domain 零框架、agent↔channel 互不依赖、通道 SPI 平台中立、飞书 SDK 只在 feishu 包、Advisor 必须双实现 | `mvn test` + 提交前 |
| maven-enforcer | JDK/Maven 版本基线；**domain 的 POM 里出现 Spring / Spring AI / 厂商 SDK / Reactor 直接失败** | `validate` 阶段 |
| pre-commit hook | 改了 `claw4j-*` 下的 java/xml 就跑架构规则（秒级） | `git commit` |

ArchUnit 拦"源码里 import 了框架"，enforcer 拦"POM 里声明了框架依赖"。两者缺一不可：
前者管不住有人先把依赖加进来、代码慢慢写；后者管不住有人绕过 POM 直接用全限定名。

enforcer 的 bannedDependencies 用违规探针反向验证过：给 domain 临时加 `spring-context`，
`validate` 阶段立刻红，报 `org.springframework:spring-context:jar:7.0.10 <--- banned`。

hook 启用：`git config core.hooksPath .githooks`（已在本机设好）。只改文档或 legacy 时自动跳过，
不等 Maven；紧急情况 `git commit --no-verify`。

### 顺手修掉的构建告警

`spring-boot-docker-compose` 原来写的是 Gradle 的 `developmentOnly` scope，**Maven 不认这个 scope**，
每次构建都打一条"'dependencies.dependency.scope' must be one of [...] but is 'developmentOnly'"，
并提示"未来版本可能不再支持这种不合法的项目"。改成 `provided` + `optional`：
编译与测试可见，被 spring-boot-maven-plugin 排除出 fat jar（已确认产物里 0 个 docker-compose 条目）。

### 全新环境验证

用 `-Dmaven.repo.local` 指向一个空目录跑完整 `mvn verify`，等于模拟"clone 下来第一次构建"：
所有依赖从远端解析、没有任何本地缓存可用。测试不联网、不碰真实模型（mock 强制开启），
不依赖 Docker（数据源是懒连接，compose 默认关闭）。

### Testcontainers 为什么递延到 M1

M0 的四层门禁里**没有** Testcontainers，是刻意的：M0 还没有任何 SQL 要跑
（数据访问与 Text-to-SQL 都在 M1），现在引它只能测"容器能不能起来"，测不到业务。
而且它把 CI 与本机都绑死在 Docker 上——本机已经出现过 Docker Hub 不可达，
那时候"没 Docker 就跑不了测试"会直接卡住开发。

代价记账：M1 加了 JDBC 与 pgvector 之后，必须有能验证真实 SQL 的测试，
那时候再引，并沿用 ADR-0005 的口径（Postgres 一库多用）。
引的时候要一并决定两件事，否则会变成本地绿 CI 红：
- 镜像源可配（`CLAW4J_POSTGRES_IMAGE` 之类的环境变量），国内拉不到官方镜像时能换
- CI 上用服务容器还是 Testcontainers，二选一，不要两套并存

## README 重写

旧 README 还在描述 1.x（Python 依赖、MySQL、Telegram 通道），重建后完全对不上，已整体重写：
技术栈表、模块依赖图、三条本地起法（最省事 / 带数据库 / 带真实模型）、key 配置与
`claw4j.model.mock` 三态表、可直接复制的 curl 示例、四层门禁表、legacy 说明、ADR 索引。

## 状态

M0 五张票全部 resolved。
