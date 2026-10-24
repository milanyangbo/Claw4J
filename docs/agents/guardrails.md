# Guardrails

**这台仓库哪些事是强制的、哪些是有意不强制的。** 评审时判断"这是缺陷还是已知状态"，
看这一份就够了。决策与判据在 `docs/adr/`。

闸的清单不列在这里：本地那几条在 README「质量门禁」，`.githooks/pre-commit` 本身就是清单，
在这里抄一份只会过期。唯一值得单独记住的一条已在 `pre-commit` 的注释里
（它的模块清单必须带 `claw4j-app`，否则 ADR-0002 那两条检查在提交前跑不到）。

**CI 暂未接入**：`.github/workflows/ci.yml` 只是接线图，流水线没在跑，
任何文档都不声称它是绿的。接的时候要补两样：runner 上没有 Docker 守护进程
（数据库那一类靠 `-Dclaw4j.dbTests=required` 判红），以及 `secrets.DEEPSEEK_API_KEY`。

## 改一条红线，就新开一张票

禁令类 ADR（0002 命令行、0007 鉴权、0010 分层、0011 真模型）各有一条机器检查盯着。
改代码去迁就一条红线、和改一条红线放代码过，在 diff 里长得一样，代价完全不同。
后者单独开票，票里写清"这条红线现在拦住的是对的东西，为什么"。

实例：脚本层想把命令行改成结构化 argv，就得自己起进程，也就必须收窄
`ProcessBuilder` 那条禁令 → 票 08（`issues/08-script-layer-structured-argv.md`），
没有混在修另外两个缺陷的那一批里。

## 已知偏差

判据：**有人能在不看代码的情况下知道这个偏差是有意的**。
记账过的偏差，评审时该查的是"补偿手段够不够"，不是"这是不是 bug"。
写法照 `IsolationSpec.Network.NOT_ISOLATED`：枚举值自己带着"没有隔离"的字样，
还有一条断言盯着它不许撒谎——防的是把常量改名成 `networkIsolated` 让规格看起来漂亮。

| 偏差 | 实际给的 | 补偿 | 记在哪 |
| --- | --- | --- | --- |
| 第 ② 类沙箱没有网络隔离与资源上限 | 临时目录副本 + 只有 PATH + 5 秒，如实写 `NOT_ISOLATED` | 联网脚本在静态检查那关被挡（`NETWORK_CLIENT` / `PY_NETWORK`） | 票 06、ADR-0002、ADR-0013 |
| "非 root"取 `user.name` 而非 uid | uid 0 而 `user.name` 非 root 时会放行；macOS 与常见 runner 上恒非 root，等于从不触发 | 真正的保证是容器镜像的 `USER`；这一条只挡应用被跑成 root | 票 03 |
| 脚本层的命令行仍交给解释器 | 执行接缝 `ExecSpec` 只有 `String command`，本地实现默认加 shell 前缀 | 参数先过沙箱路径校验再按 POSIX 单引号转义；**没有自动检查覆盖这一处** | ADR-0002、README、票 08 |
| MCP 的"跨进程"在默认构建里是进程内 stdio | 进程内管道版（surefire 下拿子进程 classpath 太脆） | 真子进程的 server 打 `real-cli` tag，`-Preal-cli` 才跑 | 票 02、ADR-0014 |
| 测试里有两个假 `ChatModel` | `FixedChatModel`、`SubagentModelStub`（ADR-0011 说不对 `ChatModel` 打桩） | 只在要验的东西不在模型身上时用 | M2-real 票 01 |

最后一行本身就是例子：先例记在第一次决定的那张票里，第二处照着做却没补记，
评审时就显得像违规。记账要跟着**用的地方**走。
