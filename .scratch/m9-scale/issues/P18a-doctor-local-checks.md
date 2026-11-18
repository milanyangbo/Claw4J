# P18a: `doctor`——一条命令报全"不用出去就能查"的那些项

**What to build:** 一条命令，把"这套配置能不能用"逐项查一遍，报**全部**问题并给出**修复动作**，
有 FAIL 就非 0 退出。本票只做不需要网络的检查；要出去的两项（key 真的能用、MCP 连得上）归 P18b。

检查项（本票）：

1. **迁移是否最新**：读 `classpath:db/migration` 里最大的 version，与库里 `flyway_schema_history`
   的最高成功 version 比。落后就报"库里是 V{n}，还有 V{n+1}..V{m} 没跑"。
2. **目录存在且可读**：`claw4j.agent.directory` / `.skills.directory` / `.subagents.directory` /
   `.rag.directory`。**配了但不存在** = FAIL——今天这种情况会让启动直接失败，doctor 的价值是
   在不启动的前提下先说。
   （`.scripts.directory` 与 `.generator.staging-directory` 两个键已经不存在：脚本包与生成器
   在 2026-11-18 整层删除，见 `.scratch/oss-alignment/issues/02`、`03`）
3. **凭据句柄能否解析**：`claw4j.model.*.api-key` 存的是句柄（`secret:deepseek_api_key`），
   用 `SpringSecretResolver` 解析一次，**不发请求**。这一项分得开两种完全不同的故障：
   "句柄名写错了"（解析不出来）和"key 过期了"（能解析、但 P18b 那条会红）。
4. **配了不生效的键**：`backend=ssh`（未实现）、`mode=all` 但 `backend=docker` 且探测失败、
   `profile` 值与 `tools.profile` 对不上之类。这一项的判据是"配置里出现了不会起作用的组合"。

**先说清楚它不是第二个 health。** 项目已经有 `/actuator/health`（9090，只绑 127.0.0.1），
两者回答的不是同一个问题：

| | `actuator/health` | `doctor` |
| --- | --- | --- |
| 什么时候跑 | 运行期，被探针反复问 | 部署前 / 排障时，人跑一次 |
| 回答什么 | "现在活着吗"（聚合，UP/DOWN） | "这套配置能不能用"（逐项，带修复命令） |
| 起不来时 | 一起没了 | 可以在不启 web 的情况下跑 |
| 输出 | 状态码 | 人读的报告 + 退出码 |

最实的价值是**一次报全**：启动是 fail-fast，只报第一个错；修完再起，又是下一个错。配错三处就要起三次。

入口：独立启动类，`spring.main.web-application-type=none`，跑完 `System.exit(0/1)`。
不占 8080 / 9090，不等 web 就绪。

**Blocked by:** 无。与权限那批票、与 P17 都无关。

**Type:** task

**Status:** resolved（2026-11-18）

**裁决来源：** ADR-0020 §M9 · P18（"照 OpenClaw `doctor` 的形状：检查 key 有效性、迁移是否最新、
外部 server 是否连得上、目录是否存在"）。四项里"key 有效"与"外部 server"要出去，拆到 P18b；
本票做剩下两项 + 附带的一致性检查。

## 要动的地方（预计）

| 位置 | 改什么 |
| --- | --- |
| 新 `DoctorApplication`（`claw4j-app` 内独立启动类，或在 `claw4j-agent` 起最小上下文） | 不开 web、跑完退出；不注册成 `@Bean` 免得被主应用顺手带起来 |
| 新 `DoctorCheck`（接口：`name()` / `run()` → PASS / FAIL / SKIP + 修复提示） | 每项一个实现，互不依赖，可单独关 |
| 新 `MigrationCheck` | 读 classpath 里 `db/migration` 的文件名 + 查 `flyway_schema_history`；**不走 `Flyway#info()`**：`spring.flyway.enabled` 默认 false，那种部署上根本没有 Flyway bean，而"库落后了"恰恰是要查的问题 |
| 新 `DirectoryCheck` | 六个目录键；未配置的报 SKIP 而不是 PASS（"没配"与"配对了"是两件事） |
| 新 `SecretHandleCheck` | `SpringSecretResolver.of(environment)` 逐个解析；只解析、不发请求 |
| 新 `DoctorReport` | 表格输出：`PASS/FAIL/SKIP` + 项名 + 一句话结论 + 修复动作 |
| `pom.xml` / 启动脚本 | `--doctor` 的调用方式写进 CONTRIBUTING 的验收命令表 |

## TDD 用例清单

| 判什么行为 | 用例名 | 结果 |
| --- | --- | --- |
| 全配好时退出码 0，每项都 PASS | `DoctorLocalChecksTest#allGreenExitsZero` | ✅ |
| 目录配了但不存在 → 该项 FAIL，输出里有那个路径与"建它或去掉这个键" | `#missingDirectoryFailsWithFixHint` | ✅ |
| 目录**没配** → SKIP，不是 PASS | `#unconfiguredDirectoryIsSkipped` | ✅ |
| 库里落后一个迁移 → 报出缺哪几个版本号 | `#staleMigrationNamesMissingVersions` | ✅ |
| 库与 classpath 一致 → PASS | `#upToDateMigrationPasses` | ✅ |
| 凭据句柄解析不出来 → FAIL，并说清是"句柄"不是"key" | `#unresolvableSecretHandleFails` | ✅ 报的是环境变量名（该改的是它） |
| 凭据句柄能解析 → PASS（**不发任何请求**） | `#resolvableHandlePassesWithoutNetwork` | ✅ 断言结论里带"没发请求"；这一项没有 HTTP 客户端可调 |
| ~~`backend=ssh` → FAIL"未实现"~~ | ~~`#unimplementedBackendIsReported`~~ | ❌ 已删：这个键随容器化回滚一起没了（2026-11-18 晚） |
| 有 FAIL 时退出码非 0，且**其余项照跑**（不中途退出） | `#oneFailureDoesNotStopLaterChecks` | ✅ |
| 跑 doctor 不启 web、不占端口 | `#doctorDoesNotStartWebServer` | ✅ 断言没有 `WebApplicationContext` / `ServletWebServerFactory` / `DataSource` bean，且 yml 里的键读得到 |

**标记：已复核（2026-11-18），10 条全绿。**

## 验收判据

| 判什么 | 判据 |
| --- | --- |
| 一次报全 | 故意配错三处（目录不存在、迁移落后、句柄写错），一条命令三行 FAIL 全出来 |
| 退出码可用 | 接进部署脚本：`java -jar app.jar --doctor \|\| exit 1` 能拦住 |
| 不误报 | 默认 `application.yml` + local profile 下全 PASS（含 SKIP 项要标出来） |
| 不占资源 | 跑 doctor 期间 8080 / 9090 无监听；跑完进程退出 |
| 门禁 | `./scripts/verify.sh` 全绿；doctor 的单测不依赖网络 |

## Answer（2026-11-18 收票）

**入口不是一个新启动类，是 `Claw4jApplication` 里的一个分支**：`main` 看到 `--doctor`
就走 `Doctor.defaults().run(args)` 并按结果 `System.exit`。理由是多一个 `main` 会让
`spring-boot-maven-plugin` 的 repackage 找不到唯一启动类（要么配 `mainClass`、要么每次额外传参），
而"一条命令两种用途"正是部署脚本要的形状。

**doctor 的上下文只加载配置，不装配任何东西**：`SpringApplicationBuilder(DoctorConfiguration.class)`
里那个配置类**刻意不带** `@EnableAutoConfiguration`——于是没有数据源、没有 servlet 容器、
没有 MCP 连接、没有模型客户端，而 `application.yml` / profile / `spring.config.import` 照常生效
（那是 Boot 的配置数据阶段，与自动装配无关）。代价写在类注释里：它查不了"属性绑定本身失败"。

**一处与票面的偏离**：第 4 项原来要查"`mode=all` + `backend=docker` 但探测失败"。**探测做不了**——
探 docker 要起一个容器，那是有副作用的动作，doctor 只读。所以这一项当时只查
`backend=ssh`、`mode=non-main`、`mode≠off && access=ro` 三种"配了不生效"的组合。

**2026-11-18 晚：第 4 项整个删掉了。** 容器化整体回滚之后 `claw4j.tools.sandbox.*` 这五个键
不存在了，`ConfigSanityCheck` 查的三条全落在死配置上——一条"报告配置写错"的检查，自己指着
一套不存在的配置，比没有更坏。现在 doctor 只剩三项：迁移版本、能力目录、凭据句柄。

**真跑过三次**（`java -jar … --doctor`）：

| 场景 | 结果 |
| --- | --- |
| 不带 `.env` | 两条 FAIL（`DEEPSEEK_API_KEY` / `QWEN_API_KEY` 环境里没有）+ 四条 SKIP（四个目录都没配）+ 迁移 PASS（库里已到 V13），退出码 **1** |
| 带 `.env` | 3 通过 / 0 不通过 / 3 没查，退出码 **0**（删掉配置组合那一项之后的数字） |
| 故意配错（`--claw4j.skills.directory=/nope`） | 一条 FAIL 带修复动作，其余照常给出结论，退出码 **1** |

代码：`claw4j-app/.../app/doctor/`（`Doctor` / `DoctorCheck` / `DoctorReport` /
`MigrationCheck` / `DirectoryCheck` / `SecretHandleCheck`）。
`MigrationCheck` 的"已跑版本"抽成了接口，所以两种落后场景不必起真库就能断言。

## 缺口

- **覆盖不了"属性绑定本身失败"**：yaml 语法错、类型不匹配时上下文起不来，doctor 也起不来。
  那种情况靠启动日志——它本来就说得清，不必再造一个解析器。
- **不自动修**：只给修复动作，不执行。自动建目录、自动跑迁移都是有副作用的动作，进 doctor 会让它
  从"只读诊断"变成"会改东西的命令"，部署脚本里就不敢随便跑了。
- **迁移版本只比"最大值"**：中间缺号（比如 V7 没跑、V8 跑了）不单独报——Flyway 自己会拦，
  doctor 不重复实现它的校验。
