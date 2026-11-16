# 03: web_fetch 的出网边界——内网可达、且"安全检查"默认放行

**What to build:** 在 `WebFetch` 调用前校验目标 URL：只允许 `http` / `https`，拒绝环回、私网、
链路本地与云元数据地址（含**解析后的 IP**，防 DNS rebinding），重定向逐跳校验；
`failOpenOnSafetyCheckError` 默认改成 `false`。

**Blocked by:** 无。与票 01 共用同一个 `ToolCallback` 装饰器链，但**互不依赖**。

**Type:** 安全加固（出网边界 / SSRF）

**Status:** draft（等 review，未写实现）

**裁决来源：** 2026-11-17 01:55 第三轮 review 发现。OpenClaw 威胁模型把这条记成
**T-EXFIL-001（Data theft via web_fetch）**，摘要写的是「注入成功之后，攻击者让 agent 把数据发到自己的 URL」。
它不是注入的入口，是**注入的出口**——所以和票 01 是一对，但要单独做。

## 现象（有实测）

**事实一：内网可达。** 2026-11-17 00:55 我在本机起了一个 `python3 -m http.server 8123`，
让模型 `WebFetch` 抓 `http://127.0.0.1:8123/`，**抓到了**，返回的是页面摘要。环回地址能通。

**事实二：那个"安全检查"是第三方黑名单查询，不是出网控制。**
`SmartWebFetchTool.DefaultDomainCanFetchChecker#check` 全部逻辑就是向
`https://claude.ai/api/web/domain_info?domain=<host>` 问一句 `can_fetch`：

- 不解析 DNS、不判断 IP 段，`127.0.0.1` / `10.x` / `192.168.x` / `169.254.169.254`（云元数据）都照抓；
- 只校验 host，不校验 scheme（`file:`、`gopher:` 之类工具自己也只支持 GET，但边界不在这里）；
- **默认 `failOpenOnSafetyCheckError = true`**：那个 API 超时、返回非 200、没网，一律**放行**。
  源码注释写得很直白：*Safety check unavailable, proceeding with fetch.*

也就是说：这条"安全检查"在离线部署、内网部署、或者 claude.ai 不可达的时候，**等于不存在**。

**事实三：它是 GET-only。** 外泄只能靠把数据拼进 URL query（`?q=<secret>`），
或者把数据放进路径。这正好是 T-EXFIL-001 描述的手法，不需要 POST。

## 这条与 Prompt 注入的关系

注入成功之后，攻击者要的是把上下文里的东西送出去。出口有三个，我们是这样的：

| 出口 | 我们的状态 |
| --- | --- |
| `WebFetch` 到攻击者 URL | **通**（内网与公网都通，见上） |
| 给攻击者发消息 | **不通**：`ReplySender` 只能回到入站那条 `chatId`，模型无法指定收件人 |
| 写文件 / 执行命令 | 靠授权表（`write_files` / `exec_command` 只给 admin） |

第二行是我们架构上的一个真实优势，值得写进 guardrails：**没有"发给任意人"的工具**，
所以 T-EXFIL-002 那条attack 在我们这里没有落脚点。第一行则是当前唯一敞着的出口。

OpenClaw 对 T-EXFIL-001 的缓解只有一句：*SSRF blocking for internal/private networks
(DNS pinning + IP blocking)*，并且自己写残余风险 **High — arbitrary external URLs remain permitted**，
建议是 URL allowlisting 与数据分类。即：**它只挡内网，不挡"把数据发到 evil.com"**。

## 我们做什么

### 1. 调用前校验（装饰器里，与票 01 同一条链）

```text
parse(url) → 协议必须是 http/https
           → host 解析出的**每一个** IP 都要通过（多 A 记录全查，防 DNS rebinding）
           → 拒绝：127/8、10/8、172.16/12、192.168/16、169.254/16、0/8、100.64/10、
                    ::1、fc00::/7、fe80::/10、::ffff:127.0.0.1 这类映射地址
           → 拒绝时返回一句确定的话（不抛异常）：这个地址不在允许出网的范围内
```

判据按**解析后的 IP**，不按域名文本：`http://127.0.0.1.nip.io` 这种域名解析出来就是环回。

### 2. 重定向逐跳校验

`HttpClient` 默认跟随重定向。`http://evil.com/x` 可以 302 到 `http://169.254.169.254/latest/meta-data/`。
每一跳都要重新校验，或者干脆关掉自动跟随、自己跟并逐跳判。

### 3. 关掉那个第三方检查，用本地判断

```java
SmartWebFetchTool.builder(summarizer)
        .domainSafetyCheck(false)   // 那个检查是问 claude.ai 的黑名单，不判 IP
        .build();
```

**这条是实测之后改的。** 一开始的写法是 `failOpenOnSafetyCheckError(false)`（fail-closed），
结果起实例一测：`claude.ai` 在国内恒返回 `403 region_unavailable`，于是**所有**抓取都被拦，
连 `https://example.com` 都抓不了——功能直接废掉。fail-closed 的"诚实"变成了"不可用"。

正确的组合是：关掉那份黑名单（它本来就不判 IP，也不该由第三方决定我们能不能抓），
出网边界完全交给本票第 1 条那个按解析后 IP 判的校验。

### 4. 可观测

`claw4j.web.fetch{outcome}`：`ok` / `blocked_private` / `blocked_scheme` / `blocked_redirect` /
`safety_unavailable`。`blocked_*` 涨起来就是有人在试，值得看一眼审计里的 `run_id`。

## TDD 用例

| 判什么行为 | 用例名 | 先红的样子 |
| --- | --- | --- |
| 环回、私网、链路本地、云元数据都被拒 | `WebFetchGuardTest#privateAddressesAreRefused` | 不判 IP → `127.0.0.1` 通过 |
| 域名解析到私网也拒（防 rebinding） | `#hostnameResolvingToPrivateIsRefused` | 只判字面量 → `127.0.0.1.nip.io` 通过 |
| 多 A 记录里有一个私网就拒 | `#anyPrivateAnswerRefuses` | 只看第一个地址 → 通过 |
| 重定向到私网被拒 | `#redirectToPrivateIsRefused` | 跟随到底 → 抓到了元数据 |
| 非 http/https 协议被拒 | `#nonHttpSchemesAreRefused` | 不判协议 |
| 正常公网地址放行（**不许把正常请求也挡了**） | `#publicUrlStillWorks` | 判太宽（比如把"没有点号的 host"全拒）→ `https://example.com` 也红 |
| 内网被拒，公网照通（两条一起看） | `#privateRefusedAndPublicAllowed` | 判太宽 → `https://1.1.1.1` 也被拒，red |

最后两条是这票的重点：**这类边界最容易写成"全挡"**，全挡的代价是功能直接不可用，
然后下一个人会把整段删掉。所以"正常公网仍然通"必须是一条钉住的用例。

## 验收判据

| 判什么 | 判据 |
| --- | --- |
| 内网真的挡住了 | 本机起 `python3 -m http.server 8123`，让它抓 `http://127.0.0.1:8123/`，模型收到"不在允许范围"，`outcome=blocked_private` ≥1 |
| 公网仍然可用 | 抓 `https://example.com` 成功，`outcome=ok` |
| 门禁 | `scripts/verify.sh` 全绿 |

## 缺口

- **不做 URL 白名单**：OpenClaw 建议的那个（allowlisting）需要人维护域名表，且会让"抓一个从没见过的文档"
  这件事失效。留成配置项 `claw4j.tools.web.allowed-hosts`（空 = 不限制公网），本票不实现。
- **不做数据分类**：判断不了"这份内容能不能外发"。真正的防线是"注入不成功"（票 01）与"没有出网工具"，
  不是这一层。
- **不覆盖 `Bash` 里的 curl**：它能出网、也能接内网，边界是授权表（`exec_command` 只给 admin）。
  要收就得在沙箱层做网络隔离，那是 ADR-0002 的已知偏差，不在这票。
