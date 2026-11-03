# P08: 凭据句柄——配置里只有名字

**What to build:** 把"真值"从配置里挪出去，只留句柄。配置项（模型 key、MCP 的 `env` 与
`headers`）里写的是句柄名，真值在**出口那一刻**才被解析：发给模型的请求头、起 MCP 子进程时的
环境、发出的 http header。模型在任何路径上看到的都是句柄，日志与审计里也只有句柄。

这不是"再加一层间接"：现在 `${ENV}` 由 Spring 在配置绑定时解析，真值进入
`ModelProperties.Provider#apiKey` 这类 String 字段并活到进程结束，任何能读到配置的组件都能
随手拿到——也答不出"这次调用用的是哪把钥匙"。要改的是**真值的生命周期**，
从"整个进程"缩到"出口调用"。

**Blocked by:** None (can start immediately)——不 gate 在任何 M7 的票上

**Status:** ready-for-agent

- [ ] 配置与示例里只有句柄名：模型两家 key、MCP 的 `env` 与 `headers` 在
      `application.yml`、`application-local.yml.example`、`docs/design/extension-points.md`
      三处都写句柄；出现非句柄形状的值时启动失败并点名是哪个配置项
      （形状由实现定，但必须可被校验，不能靠"看着像"）
- [ ] 真值只在三个出口解析：模型 HTTP 请求头、MCP 子进程环境、MCP http header。
      配置类不再持有真值——没有任何 API 能在任意时刻取到它
- [ ] 模型可见面搜不到真值：让工具回显它的入参与环境、让 MCP 的 `echo` 回显参数，
      断言输出里没有真值（含前缀与后四位）
- [ ] 审计与日志里只有句柄名：`agent_step.args_summary` / `args_digest`、应用日志、
      审批卡片三者都验，且能答出"这次用的是哪个句柄"
- [ ] 缺句柄时启动失败，报错只说句柄名，不回显值、不回显片段
- [ ] 脚本侧不退化：`SandboxPolicy.environment()` 仍然只给 `PATH`，这次改动不许把
      靠"我手上有句柄"就把 env 往子进程传的口子打开
- [ ] `./scripts/verify.sh` 全绿，跳过 0

## Comments

- 2026-11-03 拆票。依据 ADR-0020 的 P08 段原文：「现在 MCP 的 env 与模型 key 都是明文配置，
  脚本只给 PATH（那一条是对的）。改成 secret handle：配置项里只写句柄名，出口前替换成真值，
  模型在任何路径上看到的都是句柄。」判据原文：「在模型的入参与工具返回值里搜不到任何真 key；
  日志与审计记录里也只有句柄。」
- **现状核过，票面的"现在是什么"照着代码写**（别把它写成比代码更糟的样子）：
  模型 key 是 `application.yml:56` 的 `${DEEPSEEK_API_KEY:}`，一家 key 都没有时由装配方法
  fail fast（判定在 `RealChatModelCondition`），yml 里本来就没有真值；MCP 的 `env` 是
  `McpServerProperties` 的 `Map<String, String>`，`McpEndpoints` 直接 `env.putAll(declared.env())`
  交给子进程；`headers` 的 javadoc 要求写 `${ENV_VAR}` 占位符（`McpServerProperties:99`），
  但它在配置绑定时就被解析成真值，`McpEndpoints` 拿到的是解析后的字符串。
  所以真正的缺口不是"仓库里有明文"，而是**真值进了进程就成为可随手取用的字符串**，
  且 `env` 那条允许直写。
- **留给实现的三个决定**（票不替它选）：
  1. 句柄的形状与来源（`secret:name` 这类前缀、还是独立的 `claw4j.secrets` 段、
     还是沿用占位符但延迟解析）——只要求可校验、可点名到配置项；
  2. 解析失败时的行为：启动期失败还是出口期失败。倾向**启动期**——一个跑起来之后才在
     第 200 次请求上失败的系统，比起不来的系统更难查；
  3. 解析结果的缓存策略：每次出口都解析（简单、可轮换）还是缓存（省一次查找）。
     倾向每次都解析——凭据轮换是这类机制存在的理由之一，缓存把那个理由削弱了。
- **不做**：外部 secrets manager 接入（Vault / KMS 这类）、OAuth 授权码流。
  后者是 README「还没做的」里另一条限制（MCP 只有静态请求头），不是这张票的范围。
- **与 P07 相邻但不重叠**：审计已经落参数摘要与指纹，但那解决的是"不落完整参数"；
  这张票解决的是"真值根本不进配置与上下文"。两者都要，缺一个都答不出合规那句"凭据有主"。
- 拆票说明：M8 的其余八张（P09–P16）尚未拆，见 `.scratch/m8-durable/spec.md` 的 Comments。
