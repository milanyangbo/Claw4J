# 02: 宿主侧配上它，手册改成「先起 server 再起 app」

**What to build:** `application-local.yml` 与模板里默认开一段 `transport: http` 的
`java-http`；`README-capability-tests.md` / `docs/capability-tests.md` 的 Java 那一节
改成「先起 server、再起宿主」，并把退避重试写成可观测的步骤。

**Blocked by:** 01。

**Type:** docs + config（用户会照着抄的那几处）

**Status:** resolved

**裁决来源：** 票 01；`docs/design/inbound-guard.md` 式的「可观测」要求——
退避是 `McpToolProvider` 已有的行为（15 秒起、翻倍、封顶 10 分钟），这次只是让它有对端可看。

## 为什么默认开

- 用户要的就是「测的时候会看到它」。注释着等于默认没人看到退避重试。
- 代价写在文档里：没起 server 时每次运行都有一条 `MCP server broken 暂时不可用` 的 WARN，
  应用照常启动，工具清单里没有这三个——这正是「连不上 ≠ 起不来」该有的样子。

## TDD 用例清单

| 判什么行为 | 用例名 | 先红的样子 |
| --- | --- | --- |
| 发出去的那份 local 配置解得开，`java-http` 是 HTTP 端点而不是 stdio | `ApplicationAssemblyTest#httpMcpServerIsConfiguredAsAnEndpoint` | 把 `transport: http` 改成 `stdio`（或删掉 `url`）→ 断言里的 transport/url 对不上，红 |
| 手册里的命令与配置一致（端口 8082、三个工具名） | 人工核对（`grep` 端口与工具名） | 手册写 8081、配置写 8082 → 照着做连不上 |

`local` profile 在测试里不激活，所以这条用例直接读 `application-local.yml` 再用 `Binder` 绑一遍
`McpServerProperties`：判的是"仓库里发出去的那份配置解得开、解出来是 HTTP"，不是"容器里有某个 bean"。

## 验收判据

| 判什么 | 判据 |
| --- | --- |
| 先起 server 后起 app | 启动日志 `java-http → UP(工具 3 个)`；`curl /api/chat` 调得动 `runtimeSnapshot` |
| 先起 app 后起 server | 日志先 `DOWN(15 秒后重试)`，第 1 次重试后仍 DOWN、间隔翻到 30 秒；server 起好后某次重试报 `第 2 次重试后恢复，工具 3 个`，**不用重启 app** |
| 运行期 `kill` 掉 server | **已知缺陷**：下一次调用回 500（`ConnectException` 冒到 MVC），状态仍停在 UP、不退避；再下一次调用自愈。已记在票 01 的「缺口」里，未修 |
| 门禁 | `scripts/verify.sh` 全绿 |

## 缺口

- 「自动恢复要等多久」取决于当时退避到第几档，手册写的是"等一轮退避"，不写死数字。
- 运行期掉线那条（500、不退避）不在本票范围，见票 01 的缺口一节。

## 实现记录

- `application-local.yml` 与模板各加一段 `java-http`（`transport: http`、`url: http://127.0.0.1:8082`、
  `permission.action: read`），默认开着；注释里写清"先起 server"与"反过来也能跑"。
- `ApplicationAssemblyTest` 加一条读 yml + `Binder` 的绑定断言（19 条用例，文件仍在上限内）。
- `README-capability-tests.md` §2 新增「走 HTTP 的那个：先起 server」：启动命令、三个工具的问法、
  以及退避重连的三步观察；`docs/capability-tests.md` §2.4 整节改写（不再是 `command` + `args` 的样本），
  §8 改成"两种传输 vs 两种语言"的对照表。
- 同步的其余文档：`README-adding-capabilities.md` §2（自己写 server 那两段）、`examples/README.md`
  （MCP 段补 HTTP 那个）、`README.md` 模块表、`GLOSSARY.md`、
  `docs/design/extension-points.md`（Java 样本改 Spring Boot + starter，并修掉那里"30 秒起退避"
  的旧数字）、`docs/design/capability-layers.md`。
- 端到端实测（`--spring.profiles.active=local`）：server 不在 → `DOWN(15 秒后重试)` →
  `30 秒后重试第 2 次` → server 起好后 `第 2 次重试后恢复，工具 3 个`；随后
  `runtimeSnapshot` 返回 server 进程的 pid，与宿主不同。
