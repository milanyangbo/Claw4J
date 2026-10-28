# 扩展点：核心不含业务，示例一律外置

## 为什么做这件事

README 的定位从"企业 IM 上的业务数据助手"改成"企业 Agent 骨架"之后，仓库里躺着一处
跟这个定位直接冲突的东西：**示例业务活在框架模块里**。

具体三处，都是"代码照跑、测试全绿、只是长错了地方"：

| 位置 | 现状 | 为什么错 |
| --- | --- | --- |
| `claw4j-capability/builtin/sales` | `SalesTools` / `SalesSqlGuard` / 只读执行器 | 它是某个业务的能力，不是框架能力；和 `mcp` / `script` / `skill` 这些接缝并排放着，读代码的人会以为核心自带销售业务 |
| `claw4j-capability/src/main/resources/db/migration/V1`、`V2` | 业务四张表 + 种子数据 | **每个装了这个库的人都自动建一遍假订单**。框架不该往别人的库里写业务表 |
| `claw4j-capability/mcp/stdio`（主代码） | 一个 Java MCP server，但只被测试用 | 测试替身放在 main 里；同时"Java 也能写 MCP server"这件事没有任何能独立运行的样本，`examples/mcp/` 三个全是 Python |

## 要做成什么样

**判据只有一条：示例能被删掉而核心照常构建、照常启动。**

具体化：

1. **`claw4j-example-sales`**：示例业务插件，独立 Maven 模块。它只依赖
   `claw4j-domain`（权限契约）+ Spring AI（`@Tool`）——**不依赖 `claw4j-capability`**。
   业务迁移跟着走，于是"装核心等于建业务表"这件事不再成立。
2. **`claw4j-example-mcp-server`**：一个能 `java -jar` 独立运行的 Java MCP server（stdio）。
   它证明的是"MCP 那一层不绑定 Python、也不绑定本进程"，并且它只能通过**协议**被接进来——
   与 `examples/mcp/*.py` 平级，谁都不能 import 谁。
3. **能力层只留接缝**：`mcp` / `script` / `skill` / `generator` / `builtin/system`。
   `builtin/system` 留着，因为"这个进程的堆多大"与任何业务无关。
4. **集成指南**：README 要说清"接一个新 tool / MCP / Skill / 通道"分别要写哪几个文件、
   依赖什么、有哪些坑（尤其是 `@ConditionalOnMissingBean` 会让别人的工具静默消失那条）。

## 边界（明确的"不做"）

- **不做插件热加载**。插件是 classpath 上的 jar，启动期装配；`loader.path` 的外部加载方式
  这轮不验证，也就**不写进 README 当承诺**。
- **不抽通道的中性编排层**。它该抽（认人/幂等/ACK/回推现在长在 `feishu` 包里），
  但那是独立的活；这轮只在 README 里如实写"第三方通道复用它会连带飞书 SDK"。
- **不动** `claw4j-domain` / `claw4j-agent` 的分层，不新增 SPI 接口。
  扩展契约已经是现成的：`ToolCallbackProvider` + `ToolPermissionRegistry` + `AgentPort`。
  再加一层"Claw4jPlugin"接口是给自己找活干——**Spring Boot 的自动配置就是那层 SPI**。

## 验证

- `./scripts/verify.sh` 全绿，且新模块进了 `MODULES` 列表（文件数/方法数/覆盖率三条闸对它同样生效）。
- 真跑一次：`java -jar claw4j-example-mcp-server/target/*.jar` 能被 `ProcessStdioMcpEndpoint` 连上
  （真子进程，不是进程内管道）。
- 删依赖实验：从 `claw4j-app` 拿掉 `claw4j-example-sales`，除它自己的用例外全绿
  （做一次，结论写进票里，不进 CI——为一条一次性判断加一套构建太重）。

## 票

- `01-example-sales-module.md`
- `02-java-mcp-server-module.md`
- `03-integration-guide.md`（依赖 01、02 的结论，所以排最后）

## 补记：定位的措辞也在这一轮对齐

README 改了定位，仓库里还有两处仍在用旧说法，且**都在核心路径上**，所以同一天一并改掉：

| 位置 | 旧 | 新 |
| --- | --- | --- |
| `AgentAutoConfiguration.SYSTEM_PROMPT` | 你是 Claw4J，运行在企业 IM 上的**业务助手** | 你是 Claw4J，一个接入企业协作通道的 Agent |
| `GLOSSARY.md` 首段 | 运行在企业 IM 上的 Agent **平台** | 可接入企业协作通道的 Agent **骨架**（并补一句"都是插件，核心不带业务"） |

提示词这一处不是纯文档改动：措辞会直接改变模型"先调工具还是先编"的倾向，
而 `ApplicationAssemblyTest` 的判据就是"库里的 128400 有没有出现在回答里"。
所以改完必须带 `.env` 重跑整机用例——实测 254 条仍全绿，模型照旧先查再答。
这个依赖关系写进了那条提示词的注释里，免得下次有人以为它只是文案。

`docs/adr/0008` 里那句"企业 IM 里的助手"没动：ADR 是当时决策的记录，不是现状说明。

