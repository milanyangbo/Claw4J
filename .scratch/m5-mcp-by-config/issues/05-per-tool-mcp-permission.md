# 05: MCP 权限按工具覆盖，而不只是按 server

**What to build:** `claw4j.capability.mcp.servers.<名字>.tools.<工具名>` 这一段可选配置，
让**同一个 server 里的不同工具可以有不同权限**；不写就沿用 server 那一条（今天的默认）。

**Blocked by:** 01（配置形态已定）。

**Status:** resolved

## 为什么

第三方 server 常常是"只读查询 + 会改东西的动作"混装：一个 issue server 同时导出
`list_issues` 与 `close_issue`，一个 mail server 同时导出 `list_inbox` 与 `send_email`。
今天整条 server 只能声明一个 `permission`，于是接进来只有两个选择：

- 声明成 `read`：那两个写动作也能被只读身份调用（**权限放大**）；
- 声明成 `send_email`：连只读身份查一下收件箱都不行（**整条不可用**）。

两者都不对。ADR-0003 的第二层（按工具授权）在这里不是"更细的粒度"，而是
"能不能把这类 server 接进来"的前提。

## 要做

- [x] `McpServerProperties.Server` 加 `Map<String, Permission> tools`（默认空）。
- [x] `McpServerEndpoint` 携带"按工具覆盖"的那张表，并提供 `permissionFor(String)`：
      命中覆盖用覆盖，否则用 server 那一条。
- [x] `McpToolProvider` 登记时按**工具**取权限，而不是按连接取。
- [x] 覆盖表里写了 server 并不导出的工具名 → **启动失败**，报错里带 server 名与工具名。
      理由与工具重名一致：拼错一个名字而静默沿用兜底，等于让人以为加严了、其实没有。
- [x] 用例：覆盖生效、未覆盖回落、拼错名字启动失败、空覆盖表等于没写。
- [x] 文档：`McpServerProperties` 注释、`docs/design/extension-points.md` 的权限一段、
      README 那两段示例配置补上"写工具与读工具混装"的写法。

## 判据

- 不写 `tools` 的配置，行为与今天**完全一致**（同一条用例覆盖）。
- 覆盖只影响被点名的工具，同一个 server 的其余工具仍走 server 那一条。
- 权限仍然是 `action + tables` 两段式，没有引入"角色/白名单"这类新概念。

## 不要做

- 不做"按工具的表级豁免"（一个工具能碰 A 表、另一个能碰 B 表，但都要在身份范围内——
  这一点本来就已经支持，不需要新机制）。
- 不做"按工具禁用"：要禁就把它从 `tools` 里声明成一个不可满足的 action，
  或者干脆不接那个 server。加一个 `enabled` 字段会让"这个工具到底在不在"有两种表达。
- 不改 `ToolPermission` / `AuthorizationAdvisor`：它们本来就是按工具名查表的，
  缺的只是登记时多一个来源。

## 实现记录

### 配置长什么样

```yaml
claw4j:
  capability:
    mcp:
      servers:
        mail:
          command: npx
          args: ["-y", "@modelcontextprotocol/server-mail"]
          permission:            # 兜底：没被 tools 点名的工具走这一条
            action: read
          tools:
            send_email:          # 只有这一个工具要 send_email 这个动作
              action: send_email
              tables: [customer]
```

### 改动落点

- `McpServerProperties.Server` 加 `Map<String, Permission> tools`（默认空）。
- `McpServerEndpoint` 从"一个权限"变成"一个权限 + 一张覆盖表"，新增
  `permissionFor(toolName)` 与 `declaredToolPermissions()`；原来的
  `of(name, permission, transport)` 保留并委托给新重载，于是既有调用一行不改。
- `McpConnection.permissionFor` 只是转发；`McpToolProvider` 登记时按**工具**取，
  并在拿到工具清单之后校验"点名的名字都真的导出了"。
- 两个端点类的 `.endpoint(permission)` 旁边加了 `.endpoint(permission, toolPermissions)`。

### 为什么"点名了不存在的工具"要失败

与工具重名同一个取向：**宁可红，不要静默少一个**。写错一个名字（`send_mail` vs `send_email`）
而静默采用兜底权限，症状是"我明明给那个写动作加严了"，而现场一切正常——
这种错误只有翻到 yml 里数冒号才找得到。失败信息里同时给出"你点名的"与"它实际导出的"。

### 用例（5 条 + 3 次变异）

- `McpServersConfigTest`：覆盖只影响被点名的工具（另一条仍回落）、空工具名按配置错误失败、
  YAML 真的绑得进嵌套 map-of-record、握手/调用两个超时的默认值与可配性。
- `McpToolTest`：覆盖进了登记表、点名不存在的工具在建 provider 时失败并把实际清单报出来。
- 变异验证（都先改红再改回）：把 `permissionFor` 换回 `requiredPermission` →
  `perToolPermissionReachesTheRegistry` 红；删掉未知工具名的校验 → 对应用例红。
  两条都不是恒绿。

### 顺带记一笔

这条票原本不在计划里（spec 的边界写着"权限仍按 server 声明"）。改掉它的触发点是
03/04 那批示例 readme 的写法：`weekly-digest` 这类"查数 + 发出去"的场景，天然是
一个 server 里混装读写工具。**如果只按 server 声明，示例给的配置抄出去就是权限放大。**

