# MCP：接第三个 server 应该只写配置

## 为什么做这件事

今天接一个 MCP server 要**写 Java**：`claw4j-app/McpDemoConfiguration` 里一个 server 一个
`@Bean`，三个 Python server 就是三个方法。文档（`docs/design/extension-points.md`）里那句
"接一个 MCP server 只要一个 bean"是准确的描述，但它不是**第三方**想要的答案——
别人的 server 装在别人机器上，接进来不该需要改我们的源码再重新打包。

而且只支持一种传输：stdio。MCP 的另一半（HTTP 上的 JSON-RPC，官方叫 Streamable HTTP）在
`McpServerEndpoint` 这层接缝上本来就是"换一个 `openTransport()`"就能接的，但仓库里
既没有实现也没有演示，等于对外只承认了一半协议。

所以这一轮要办两件事：

1. **配置化**：`claw4j.capability.mcp.servers` 下一段 YAML 就是一个 server，重启即生效。
2. **两种传输都演示**：stdio（第三方 npm 包）与 HTTP（公开端点），而且**都是配置**。

## 判据

- 在 yml 里加一段配置就能接一个第三方 MCP server，**不改任何 Java**。
- 删掉那一段，框架照常构建、照常启动——和"插件"那条判据同一个方向。
- 配错了（命令不存在、URL 为空、传输写错）在**启动时**失败，报错里带 server 名，
  不留到用户问起才表现为"模型说它没这个能力"。
- 现有 254 条用例仍全绿；新增的门禁用例**不依赖外网**。

## 边界（明确的"不做"）

- **不做 OAuth 交互式授权**：DeepWiki 那类端点带 token 的用法只留 `headers` 这个口子，
  凭据从环境变量来。浏览器授权流不做。
- **不做 MCP 注册中心/市场**、不做 server 的热插拔：仍是启动期装配，与 ADR-0002 一致。
- **权限仍按 server 声明**，不按工具逐个声明（ADR-0003 的第二层不做）。
- 不再新增一个"传输插件"接口：`McpServerEndpoint` 就是那层接缝，加传输＝加一个实现类。

## 票

- `01-config-driven-endpoints.md`
- `02-third-party-demo.md`（依赖 01 的配置形态，所以排后）
