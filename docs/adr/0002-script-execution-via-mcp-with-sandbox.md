# 脚本执行改由 MCP 收口，禁止 Java 直接拼命令行

---
status: accepted
date: 2026-10-22
---

1.x 中 Agent 执行脚本的路径是：`GenericSkill` 用 `listFiles()[0]` 取一个 `.py`，把用户自然语言当 argv 传进去，经 `sh -c` 或 `ProcessBuilder` 执行；`ShellUtils` 另有硬编码 root、关闭主机校验的 SSH 通道；`SkillGeneratorSkill` 还会让模型写出 Python 落盘执行，并允许脚本自行 `pip install --break-system-packages`。这条链路把宿主机 shell 交给了模型。新架构中 Java 侧不再直接调用任何脚本进程。

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
- 模型生成的脚本只落盘、不执行，等人审；"AI 自动创建并执行技能"这条能力在新版本中不再自动闭环。
- MCP 会话丢失（`McpTransportSessionNotFoundException`）必须重新初始化连接而非重试死会话，这是连接层要处理的第一个故障。
