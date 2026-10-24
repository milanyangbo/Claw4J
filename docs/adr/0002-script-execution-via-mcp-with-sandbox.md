# 脚本执行改由 MCP 收口，禁止 Java 直接拼命令行

---
status: accepted
date: 2026-10-22
---

1.x 中 Agent 执行脚本的路径是：`GenericSkill` 用 `listFiles()[0]` 取一个 `.py`，把用户自然语言当 argv 传进去，经 `sh -c` 或 `ProcessBuilder` 执行；`ShellUtils` 另有硬编码 root、关闭主机校验的 SSH 通道；`SkillGeneratorSkill` 还会让模型写出 Python 落盘执行，并允许脚本自行 `pip install --break-system-packages`。这条链路把宿主机 shell 交给了模型。新架构中 Java 侧不再直接调用任何脚本进程。

> 修订记录（2026-10-22）：初版结论是"模型生成的脚本只落盘不执行"。确认 skill-generator 是本项目核心能力后，改为"可以执行，但只在一个隔离沙箱里执行，且全程留审计"。执行位置的约束没有放宽，放宽的是能不能跑。
>
> 修订记录（2026-10-24，M2-06 落地）：初版写的"隔离沙箱（容器，网络受限、有资源上限）"里，**容器**这一项没有落成代码。判据沿用 ADR-0003 第 ① 类那条——CI 的 runner 上没有 Docker 守护进程，依赖容器的沙箱在 CI 上就是一串红灯，而跑不起来的沙箱等于没有测试。第 ② 类沙箱（未审核代码的自测位置）最终落在"临时目录副本 + 最小环境 + 5 秒上限"上（ADR-0013），给的三样是：工作目录隔离、环境变量隔离、超时。**网络隔离与内存/CPU 上限这两项它给不了**：纯 Java 起的子进程没有可移植的限制手段（`unshare` / `sandbox-exec` 都是要再起一条命令，而那正是本 ADR 禁止的事），于是规格里如实写成 `NOT_ISOLATED` 并写明原因，真正的网络隔离由部署层的容器网络策略给；联网的脚本在静态检查那一关就被挡了。README 与 GLOSSARY 不把这两项写成承诺。

## Considered Options

**保留进程调用，只做参数白名单**
否决。参数白名单挡不住脚本自身的副作用（如自动装包、读写任意路径），而白名单本身会成为长期维护负担，且面试时讲不出设计依据。

**全部能力用 Java `@Tool` 重写，不留脚本**
部分采纳，作为首选路径。但外部 CLI 依赖（`ffmpeg`、`gh`、邮件客户端）用 Java 重写的成本不划算，强行重写会拖慢主线。

**脚本经 MCP stdio server 暴露（采纳）**
脚本退化为 MCP server 的内部实现；Java 侧只见工具协议，只见 `@McpTool` 的名字与参数。连接建立时 `listTools()` 为空即 fail-fast，不等到调用时才暴露配置错误。执行在容器内，网络受限、非 root。

## Consequences

- Java 侧不再出现 `Runtime.exec` / `ProcessBuilder` / `sh -c`。
- 敏感配置（数据库连接串、API key）从 `SKILL.md` 的 YAML 块移回 Spring 配置，走环境变量与 profile。
- **skill-generator 保留，但换执行位置**：模型生成的脚本仍可被执行自测，只是执行发生在隔离沙箱（容器，非 root、网络受限、有超时与资源上限），不在应用进程、不在宿主机。自测结果写入审计，脚本成为正式 Script Package 前默认需要人工确认。这条是项目核心能力，不能因为原实现危险就整体砍掉，正确的做法是把它关进笼子。
- 模型生成脚本的自测链路固定为：生成 → 静态检查（禁止危险 import 与越权文件操作）→ 沙箱内自测 → 审计留痕 → 人审通过后才注册。
- MCP 会话丢失（`McpTransportSessionNotFoundException`）必须重新初始化连接而非重试死会话，这是连接层要处理的第一个故障。
