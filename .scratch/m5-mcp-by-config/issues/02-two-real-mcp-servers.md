
## 修订（2026-11-07 评审）：那两段 YAML 落在哪

判据里"原样贴进 `application.yml`"这句要改：**没有 `application-mcp-third-party.yml` 这个文件，
也不打算加。**

- 真实落点：`claw4j.capability.mcp.servers` 下面的两个条目——`README.md` 的
  "一个 MCP server"一节、`docs/capability-tests.md` 的实测段，就是它们。
  贴进任何一份 `application*.yml` 的同一层级即可。
- 为什么不随包发一份 profile：这两个 server 一个要本机有 `npx`、一个要能出网。
  随包发一份默认激活的 profile，等于让"能不能启动"取决于开发机装没装 Node，
  与"没配就不装"是同一条原则的反面。
- OpenClaw 那边也是**在配置里声明 servers**（`mcpServers`），没有"随包带一份示例服务器清单"。
