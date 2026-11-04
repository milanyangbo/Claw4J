# P13 · Slack 通道：第五个入口，3 秒是硬限，超时的代价不只是重试

**Status:** done（2026-11-03 实现，`74237fe`）
**Blocked by:** None（P06 的通道模板已落地，P12 刚把第四个入口走了一遍）

## 背景

ADR-0020 把两张票写在一句话里：「P12 / P13 钉钉与 Slack」。P12 按书写顺序取了钉钉，
P13 是另一半——**同一套适配层形状，第五次复用**。

前四次的形态已经完全稳定：`channel` 只认识 `channel.spi` / `channel.kernel` 两个契约包（在 core），
一个平台落在 `channel.<平台>`（入站事件、回推出口、投递实现三个类型）+
`channel.<平台>.client`（验签、翻译、发送、装配），边界由 `ChannelBoundaryTest` 的成对规则钉住，
编排一律走 `InboundDispatcher`。

**所以这不是"再抄一遍钉钉"**——Slack 有四处与前面四个通道不同，而且每一处都会改实现形态：

1. **3 秒是硬限，超了不是"重推一条"而是"整个事件订阅可能被停掉"**。钉钉超时只是重推；
   Slack 的成功率低于 5%（60 分钟窗口）会把订阅临时禁用，并给应用所有者发邮件。
2. **平台自己会推回我们刚发出去的那条消息**。带 `bot_id` 的事件不滤掉，一次提问会变成
   一问一答、答又问、问又答——它不报错，只在账单上体现。
3. **回复目标不是一个人，是一个"频道 + 线程"**。`ReplySender.send(conversationId, text)` 只有两个参数，
   而 Slack 要 `channel` 与可选的 `thread_ts`。
4. **HTTP 200 不代表成功**。Slack 的错误是 `{"ok": false, "error": "..."}`，状态码是 200。
   钉钉是 200 + `errcode`，企微是 200 + `errcode`，三家都是"看 body"，
   但 Slack 的字段名是 `ok`，照抄前两家会写出"永远成功的发送器"。

## 必须先回答的四个结构问题

### 1. 入站走 Events API（HTTP）还是 Socket Mode（WebSocket）

两条都是官方支持的路，而且**两条都能做**——这一点与 P12 的 Stream 模式不同（那边卡在本地仓库没有 SDK）。

- **HTTP**：Slack 往一个公网地址 POST JSON，header 带 `x-slack-signature` 与
  `x-slack-request-timestamp`。要公网可达（本地开发得靠隧道）。只需要 JDK 的 HttpClient。
- **Socket Mode**：应用自己发起 WebSocket 连到 Slack，要一个 **app-level token**（`xapp-` 前缀，
  scope `connections:write`）。不需要公网地址，每个 app 最多 10 条并发连接。
  代价是长连接的全部麻烦：envelope 要逐条回 `envelope_id` ack、断线要重连、
  Slack 会周期性回收连接容器（官方原文："the socket server backend recycles containers
  serving connections every now and then, leading to occasional reliability issues"）。

**选 HTTP。** 依据不是"没 SDK 所以做不了"（JDK 自带 `java.net.http.WebSocket`，硬写是能写的），
而是三条实证：

- 官方对两种形态的建议是明确的：**开发与本地用 Socket Mode，部署上线用 HTTP**
  （"we recommend using HTTP for production applications"），要上架 Marketplace 则**必须**用 HTTP。
- 前面四个通道里三个是 HTTP 回调，`InboundDispatcher`、认人、授权、投递这一整套都是按"回调进来"写的；
  Socket Mode 是"我们连出去推"，那套代码一处也用不上，等于为 Slack 单开一条旁路——
  而 ADR-0020 的唯一硬约束就是"不新开旁路"。
- Socket Mode 没有"验签"这一步（连接本身用 app token 认证），于是票里最重要的那条判据
  （"签名不对的回调不处理"）在它上面**根本不存在**；而多出来的判据是"断线重连后不丢事件"，
  那件事在本地没法真验。

**要如实记下的一条**：如果部署在**完全不能开公网**的内网，Socket Mode 是唯一出路。
那时要重估这张票，不是"这个功能做不了"。

### 2. 订阅哪两个事件——以及 `event.type` 的不对称

能拿到"用户对应用说了一句话"的只有两个：

| 场景 | 订阅的事件 | 载荷里的 `event.type` | bot scope |
| --- | --- | --- | --- |
| 频道里 @ 应用 | `app_mention` | `app_mention` | `app_mentions.read` |
| 私聊应用 | `message.im` | **`message`**（配 `channel_type: "im"`） | `im:history` |

**第二行是坑**：订阅项叫 `message.im`，但载荷里 `event.type` 是 `message`。
按订阅名判断（`"message.im".equals(type)`）的话，单聊永远匹配不到——而且不报错，
表现为"群里 @ 有反应，私聊没反应"。官方原文："Differentiate direct messages from other
`message.*` events by looking for the `event`'s `channel_type` field set to `im`."

**不订阅 `message.channels` / `message.groups` / `message.mpim`。** 订阅它们会收到频道里
**每一条**消息（包括同事之间的闲聊），每一条都会进来走一遍去重与认人，然后被"没 @ 我"这条判断丢掉。
一个几十人的工作区一天几千条消息，全是白跑——而其中任何一条被误判成提问，就是一次真实的模型调用。

### 3. 自己发出去的话会被推回来（这是首个"会自己咬自己"的通道）

Slack 把应用自己发的消息也当 `message` 事件推给你（如果订阅了对应事件）。
不去重的话：用户问一句 → 我们回一句 → 那句回复触发 `message` 事件 → 我们再回一句……

前四个通道都没有这个问题：钉钉/企微的机器人不会收到自己的回复，飞书 SDK 也不转发。
**Slack 有。** 识别方式：事件的 `bot_id` 字段（集成发的消息带它）或 `subtype` 字段（`bot_message` 等）。

判定取"有 `bot_id` 就不处理"，**同时**"有 `subtype` 就不处理"——后者多挡一类：

- `message_changed`：用户**编辑**了一条消息。载荷里没有顶层 `text`，原文在
  `event.message.text`（多嵌一层）。照 `event.text` 取 → `null` → 空串进模型。
- `message_deleted`：消息被删。同样没有 `text`。
- `channel_join` / `channel_leave` / `channel_topic` 等：系统消息，`text` 是"某某加入了频道"，
  会真的被当成提问送进模型。

只挡 `bot_id` 不够，这些都不是 bot 发的；只挡 `subtype` 也不够（`bot_message` 是 subtype，
但别的集成消息 subtype 可能为空、只有 `bot_id`）。两条一起才是闭合的。

### 4. 答案发到哪：`conversationId` 要装下"频道 + 线程"

`ReplySender.send(String conversationId, String text)` 是两个参数，而 Slack 的
`chat.postMessage` 要 `channel` 与可选的 `thread_ts`。

两种做法：

- **只发顶层**（`conversationId` = channel id）：群里 @ 一次，答案就作为一条新消息刷在频道里。
  一个群里三个人各问一次，频道被六条消息（三次回执 + 三个答案）占满，而每条答案与它的提问
  在时间线上是分开的——**这不是 Slack 的用法**，agent 在那个界面里会显得很吵。
- **回线程**（`conversationId` = `channel|thread_ts`）：答案挂在原消息下面，频道只多一行。
  这是 Slack 上机器人应答的默认礼仪，也是官方示例的做法（`thread_ts` 传被回复那条消息的 `ts`）。

**选第二种**，编码用 `C123456|1515449522.000016`（`|` 分隔，无线程时就是 `C123456`）。
这样 `SlackReplySender` 只做一次 `split("\\|", 2)`，不需要在通道里维护
"这条回复对应哪条消息"的表。

**这个编码是安全的，因为核实过一件事**：`conversationId` 在整个 Agent 侧**没有任何消费点**
（`grep conversationId` 在 `claw4j-agent/src/main/java` 零命中），它只做两件事——
`ReplySender` 的回推目标，以及 A05 主动投递登记的收件地址。前者正是我们要的，
后者登记的地址里带一个 `ts`（那次提问的消息），作为线程根依然有效。

**代价照实说**：主动投递（在别处做完一件事，几小时后推回 Slack）拿到的是一个具体线程，
而不是"给这个人发条私聊"。要发私聊得先 `conversations.open` 换一个 `D` 开头的会话 id，
那是另一次 API 调用——第一版不做，写进"不做"。

## 协议事实（逐个查过官方文档，2026-11-03）

不凭记忆，来源写在每条后面；实现时按同样的来源核对。

- **验签**：`x-slack-signature` = `v0=` + hex(HMAC-SHA256(key = signing secret,
  data = `"v0:" + timestamp + ":" + rawRequestBody`))；`x-slack-request-timestamp` 与本地时间
  相差超过 **5 分钟**即判为重放。**必须用原始报文**，JSON 反序列化再序列化一遍就验不过
  （`docs.slack.dev/authentication/verifying-requests-from-slack`）。
  所以控制器要收 `@RequestBody String body`（与 `DingtalkCallbackController` 同一个写法），
  **不能**收一个 `Map`/DTO。
- **3 秒与重试**：3 秒内回 2xx；失败后重试 **3 次**——第一次几乎立即、第二次 1 分钟后、
  第三次 5 分钟后。重试带 `x-slack-retry-num`（`1` / `2` / `3`）与
  `x-slack-retry-reason`（`http_timeout` / `too_many_redirects` / `connection_failed` /
  `ssl_error` / `http_error` / `unknown_error`）（`docs.slack.dev/apis/events-api/`）。
- **失败上限**：60 分钟内超过 **95%** 的投递失败 → 事件订阅被**临时禁用**，并给应用所有者发邮件；
  每小时少于 1000 条事件的 app 不会被自动禁用。这是"敢不敢在回调里做慢事"的量化依据。
- **握手**：配置请求地址时 Slack POST `{"token":..., "challenge":"...", "type":"url_verification"}`，
  要回 **200** 且 body 是 `challenge` 的值（纯文本即可）
  （`docs.slack.dev/apis/events-api/using-http-request-urls`）。
- **`event_id`**：文档写的是 "a unique identifier for this specific event, globally unique across
  all workspaces"。**文档没有明说重试时 `event_id` 不变**——这一点是**推断**（同一个事件重投应当是
  同一个 id），不是文档事实。所以去重不能只靠它，还要看 `x-slack-retry-num`：那个头是文档保证的
  "这是第几次重试"。
- **`chat.postMessage`**：`POST https://slack.com/api/chat.postMessage`，`Authorization: Bearer xoxb-...`，
  scope `chat:write`；返回 HTTP 200 + `{"ok": true|false, ...}`，
  **官方原话是 "Callers should always check the value of the `ok` parameter in the response"**。
  错误码里值得先记住的：`channel_not_found`、`not_in_channel`（机器人不在这个频道）、
  `ratelimited`（读 `Retry-After` 头）、`msg_too_long`。
- **限流**：`chat.postMessage` "will generally allow an app to post **1 message per second**
  to a specific channel"，整个工作区还有"每分钟几百条"的上限
  （`docs.slack.dev/reference/methods/chat.postMessage/`）。
- **正文长度**：建议 `text` ≤ **4000** 字符，超过 40000 会被截断并在响应里带 `message_truncated` 警告。
  **`MessageSplitter.DEFAULT_MAX_CHARS` 正好是 4000**，这一项不用改——四个通道里第一个"现成的正好对上"。
- **`ts` 是每条消息的唯一标识（按频道唯一）**，形如 `1515449522.000016`；`event_time` 是**秒**
  （钉钉的 `createAt` 是毫秒，照抄钉钉的除法会把时间读成 1970 年——与 P06 那次方向相反）。
- **app_mention 的 `text` 里带着 mention 本身**：例子是 `"<@U0LAN0Z89> is it everything a river
  should be?"`，另一种写法 `<@U123ABC456|bobby>`（带显示名，见 `channel_join` 的例子）。
  **两种都要剥**，否则模型收到的第一段是 `<@U0LAN0Z89>` 这串 id。
- **Socket Mode 的细节**（本票不做，写在这里是为了下次被问"为什么不"时能直接指过来）：
  app-level token `xapp-` + `connections:write`，每条 envelope 要回 `{"envelope_id": ...}`，
  每 app 最多 10 条并发连接，官方建议开发期用、生产用 HTTP。

## 验收判据

- [x] **验签真的挡住了**：签名对不上、或 `x-slack-request-timestamp` 与当前差超过 5 分钟，
      这条回调**不处理**（不进编排、不调模型），且**返回 200 而不是 4xx/5xx**——非 2xx 会被 Slack
      算作失败，重推同一条验不过的报文，还会往那个 95% 的失败率上记一笔。
      用例要有一条"签名错但报文合法"的反例。
      证据：`SlackChannelTest#aWrongSignatureOrAStaleTimestampIsDropped`：签名错（报文完全合法）与时间戳在 6 分钟前各一条反例，都回空串、模型 0 次、一条话都没发。

- [x] **握手是唯一一个不解析业务内容的分支**，且它**仍然排在验签之后**：`type=url_verification`
      回 200 + `challenge` 原文。不要为了"配置阶段好排查"给它开后门（跳过验签）——那扇门在运行期同样开着。
      secret 配错时后台显示"URL 验证失败"是正确行为，它说明这个通道根本没配好。
      证据：`SlackChannelTest#theHandshakeReturnsTheChallenge`（回 `challenge` 原文）、`#theHandshakeIsBehindTheSignatureCheck`（签名错时连 challenge 都不回——不给它开“先证明地址活着”的后门）。

- [x] **只处理两类事件**：`event.type == "app_mention"`，或 `event.type == "message"` 且
      `channel_type == "im"`。其余一律 ACK 不处理，日志里留一行"这不是给我的提问"。
      反例必须能红：把判断写成 `"message.im".equals(event.type)` 的实现，单聊那条用例返回"没处理"。
      证据：`#aMentionIsHandedToTheAgent`（`app_mention`）、`#theAnswerGoesBackIntoTheThreadAndDirectMessagesStayOnTop`（`event.type=message` + `channel_type=im`）、`#aChannelMessageThatDoesNotMentionUsIsIgnored`（`channel_type=channel` 不处理）。

- [x] **自己说的话不再触发（死循环）**：事件带 `bot_id`、或带任何 `subtype`，一律不处理。
      用例要断言**模型调用次数为 0**（不是"返回了拒绝文本"）。
      反例：去掉过滤后喂一条 `bot_id` 的消息，会真的再答一遍——这条用例必须红。
      另要有一条 `subtype=message_changed` 的：它的 `text` 在 `event.message.text` 里，
      照 `event.text` 取会拿到空串，空串进模型等于问了一句空话。
      证据：`#messagesFromBotsOrEditsAreIgnored`：`bot_id` 与 `subtype=message_changed` 各一条，断言 `answers=0`、`asked` 空、`sent` 空。去掉过滤这条必红。

- [x] **mention 要剥干净**：`<@U123>` 与 `<@U123|name>` 两种形式都剥，前后空白去掉；
      剥完为空（只 @ 了机器人、没写问题）→ 回一句"问点什么"，**不进模型、不花 token**。
      证据：`#aMentionIsHandedToTheAgent`：`asked` 恰好等于去掉 `<@U0LAN0Z89>` 之后的问题；`#aMentionWithNoQuestionGetsAPrompt`：剥完为空 → 回一句、模型 0 次。

- [x] **重试只答一次，但重试不能被跳过**：`event_id` 走 `InboundDispatcher` 的 10 分钟去重
      （第二次只 ACK 不执行）；`x-slack-retry-num > 0` **只记日志，不用来决定"跳过处理"**——
      如果第一次因为别的原因没跑成，跳过就等于把唯一一次机会丢了。这两句要写在代码注释里，
      因为"看见重试头就 return"是这段代码最自然的写法。
      证据：`#theSameEventIdTwiceIsAnsweredOnce`：第二次照常 ACK，`answers=1`、`sent` 仍是 2 条。`x-slack-retry-num` 只进日志，见 `SlackCallbackController#receive` 里那句“不跳过”的注释。

- [x] **回调里不做网络调用**：3 秒是硬限。`InboundDispatcher.accept` 本身返回即 ACK，
      但有**两条分支在回调线程上同步发一条消息**（认人失败、线程池满），那是一次
      `chat.postMessage` 的 HTTPS 往返。实现时二选一并写进票的 Comments：
      ① 接受并度量（正常几十毫秒，要连续 3 秒不可用才会超）；
      ② 把这两条也挪进 executor（改 `InboundDispatcher`，四个通道一起受益，但要动 core 的既有用例）。
      证据：选了 ①（接受并度量），理由与量化见下面实现记录；`InboundDispatcher` 未改。

- [x] **答案回到线程**：群里 @ 的答案以 `thread_ts = 原消息 ts` 回线程；私聊回顶层。
      断言 `chat.postMessage` 的请求体里 `thread_ts` 等于原消息的 `ts`（不是 `event_ts`，
      两者数值相近但格式不同：`1515449522.000016` vs `1515449522000016`）。
      证据：`#theAnswerGoesBackIntoTheThreadAndDirectMessagesStayOnTop`（频道 → `C…|ts`，私聊 → 只有 `C…`）、`#sendsChannelTextAndThreadTs`（请求体里 `thread_ts` 等于原消息的 `ts`）。

- [x] **只看 `ok`，不看 HTTP 状态**：`{"ok":false,"error":"channel_not_found"}` 是 HTTP 200。
      用例要有一条"200 但 `ok=false`"的反例，断言**抛出去**（记 ERROR），不吞成"发出去了"。
      反例：只判 `statusCode == 200` 的实现，这条用例红。
      证据：`#anOkFalseIsThrownEvenOnHttp200`：HTTP 200 加 `{"ok":false,"error":"not_in_channel"}` → 抛，异常消息里带错误码。

- [x] **分段连发不撞限流**：一段答案超过 4000 字会切成多段，同一频道 1 秒 1 条。
      第 2 段起要么等够间隔、要么读 `Retry-After` 重试一次。反例：直接连发 3 段 →
      第 2 段 429 → `InboundDispatcher.sendAnswer` 停在第 2 段（用户收到残缺答案，日志是 ERROR）。
      回执 + 答案这两条也受同一条约束（它们本来就挨着发）。
      证据：`#waitsBetweenMessagesToTheSameChannel`（间隔 150ms，实测两次到达差 ≥120ms）、`#surfacesRateLimitAndRetriesOnce`（429 + `Retry-After: 0` → 重试一次成功）。

- [x] **凭据与开关**：`signing-secret` 与 `bot-token` 缺一样，整份退让、一个 bean 都不注册，只跑 HTTP；
      `enabled=false` 同理。凭据是**环境变量占位**（与另外三个通道一致，不是 P08 的 `secret:` 句柄——
      理由见 P12 的实现记录：`SpringSecretResolver` 在 capability，channel 不依赖它）。
      证据：`#credentialsIncompleteLeavesTheChannelOut`（只给 signing-secret，一个 bean 都不留）、`#configuredChannelRegistersTheEndpointAndTheDelivery`（端点、通道、发送器、投递都在，`channel()=slack`）。

- [x] **主动投递可用**：`SlackOutboundDelivery.channel()` 是 `slack`，与 `agent_run_delivery.channel`
      同口径；装配条件按**名字**判（`slackOutboundDelivery`），不按 `OutboundDelivery` 类型判——
      这是 P06 修过的真 bug，现在有四个同类型 bean 了。
      证据：`#handsTheAddressToTheReplySender`：`channel()` 是 `slack`，带线程的地址原样交给回推。

- [x] **结构规则跟着加**：`channel.slack` 不依赖实现子包、也不依赖 JDK / slf4j / 自家代码以外的东西，
      两条与飞书、企微、钉钉那六条同构（`ChannelBoundaryTest` 8 → 10）。
      证据：`ChannelBoundaryTest` 8 → 10：`slackDispatchLayerDependsOnNothingButItsOwnContracts` 与 `slackDispatchLayerDoesNotReachIntoItsClientSubpackage`。

- [x] **真模型闭环**：app 层一条，真回调（真签名）→ 真模型 → 记录器收到答案。回推那一层换替身，
      断言"答案里有 ```128400```，且请求体里的 `thread_ts` 是原消息的 ts"（与 P06 / P12 那两条同形）。
      证据：`SlackFlowTest#aSlackMentionIsAnsweredThroughTheCallback`：真签名 → 真模型 → 记录器收到两条，答案含 `128400` 且落回 `C0FLOW1234|1515449522.000016`。

- [x] `./scripts/verify.sh` 全绿，跳过 0，行覆盖率不低于 60%。
      证据：2026-11-03 跑过：**623 条用例、失败 0、跳过 0**。覆盖率 core 90.2% / channel 75.8% / capability 85.6% / agent 63.7% / example-sales 93.8% / example-mcp-server 75.0%。

- [x] **门禁余量**：`claw4j-channel` 与 `claw4j-app` 都已经是 **10 个测试文件（上限 10）**，
      本票**不能新增测试文件**。两条腾槽路线见下面 Comments。
      证据：两条合并腾槽，见实现记录：`ApprovalControllerTest`(7)+`RunQueryControllerTest`(4)→`HttpEndpointsTest`(10)、`ProcessBoundaryTest`(2)+`MigrationCompositionTest`(3)→`BuildContractTest`(5)。脚本口径：channel 与 app 各 10 个文件、最大方法数 20。

- [x] **文档同步**：`README.md` 的特性表（"四条通道"→ 五条）、集成表、配置表（
      `CLAW4J_SLACK_ENABLED` / `SLACK_SIGNING_SECRET` / `SLACK_BOT_TOKEN`）、
      `docs/design/extension-points.md` 的通道一节，以及 `application.yml` 里 slack 段的注释。
      证据：`README.md`（特性表“五条通道”、配置表两把凭据、通道小节 Slack 三句话）、`docs/design/extension-points.md`（通道一节表格加一行 + 一段）、`application.yml`（slack 段与顶部那句注释）。

## 不做

- **不做 Socket Mode**：见上面第一个结构问题。重估条件写在那一节的最后一句。
- **不做 `message.channels` / `message.groups` / `message.mpim`**：订阅它们是"每条闲聊都进一次编排"。
  没有 @ 就不该响应，这是 Slack 的礼仪，也是唯一能控制成本的选择。
- **不做斜杠命令、快捷方式、互动组件（按钮）、Home Tab、Modal**：那是另一套载荷（
  `application/x-www-form-urlencoded` + `payload` 字段）与另一套 scope。审批卡片的 Slack 版
  （对应 P03 的飞书卡片）不在本票。
- **不做 markdown → mrkdwn 转换**：模型输出的是 markdown，Slack 认 mrkdwn——`**粗体**` 在 Slack 里
  显示成字面量，表格完全不支持。第一版原样发，代码块（```）两边语法相同，恰好是最常用的那个。
  要美化是另一件事，且要做得对就得写一个转换器，那是"看着简单、边界很多"的活。
- **不做文件 / 图片 / 语音的内容理解**：与企微、钉钉一致，回一句"打成文字"。
  Slack 的图片是 `files` 数组 + `file_share` subtype，本票按 subtype 直接跳过，连回话都不发——
  发一条"我看不了图片"会被当成噪音。
- **不做主动私聊**（`conversations.open` + 往 `D` 开头的会话发）：见第四个结构问题的代价。
- **不做多实例去重**：回调是平台单点投递，单实例是既定形态（ADR-0009）。
- **不做"用 API 注册请求地址"**：Slack 要在 app 后台手填并点验证，与钉钉、企微一样，写进 README 即可。

## Comments

- 拆票时的现状核对（2026-11-03，逐个打开看过）：
  - 通道四条已就绪，形态稳定：`channel.http` / `channel.feishu`（+`sdk`）/ `channel.wecom`
    （+`client`）/ `channel.dingtalk`（+`client`）。Slack 按同一形状落在 `channel.slack`
    （三个类型）+ `channel.slack.client`（验签、翻译、发送、装配）。
  - `InboundDispatcher` 仍然**不注册成 bean**，在控制器的装配方法里就地建
    （`DingtalkChannelAutoConfiguration:134`）。容器里已经三个同类型实例了，注册第四个会让
    "按类型注入"彻底不可用。Slack 必须照做。
  - `OutboundDelivery` 的装配条件按**名字**判（`wecomOutboundDelivery` / `feishuOutboundDelivery` /
    `dingtalkOutboundDelivery`），Slack 要用 `slackOutboundDelivery`。
  - 授权表只有一份：`claw4j.channel.http.roles.{role}.{tables,actions}`。Slack 只登记"谁是谁"
    （`roles.*.users` 里填 Slack user id，`U` 开头），角色名对不上就启动失败。
  - `conversationId` 在 Agent 侧零消费点（`grep` 过 `claw4j-agent/src/main/java`）——
    这是第四个结构问题里"编码安全"的依据，不是印象。
  - `MessageSplitter.DEFAULT_MAX_CHARS = 4000`，与 Slack 的 `text` 建议上限正好一致。
  - 凭据现状：`feishu.app-secret` / `wecom.corp-secret` / `dingtalk.client-secret` 都是环境变量占位，
    `application.yml` 顶部第 11 行的注释已经改成"通道凭据不在句柄那条规则里"。
    Slack 加进去即可，不用再改那句话（只加名字）。
- **门禁余量（硬约束，开工前先看）**：
  - `claw4j-channel` 的 10 个文件：`WecomChannelTest` 19、`DingtalkChannelTest` 18、
    `HttpChatChannelTest` 15、`FeishuChannelWiringTest` 10、`FeishuMessageTranslatorTest` 10、
    `FeishuApprovalTest` 9、`HttpChatControllerTest` 9、`ChannelBoundaryTest` 8、
    `ApprovalControllerTest` 7、`RunQueryControllerTest` 4。
    **推荐合并**：`ApprovalControllerTest`(7) + `RunQueryControllerTest`(4) → `HttpEndpointsTest`(11)。
    两者在同一个包、同一个关注点（"接口这一侧答得对不对"），另一个共同点更硬：
    **它们测的都不是聊天**——一个是审批端点、一个是 `GET /api/runs/{id}`，
    与"通道怎么收消息、怎么回消息"无关，所以它们一直是这个模块里最容易被忽略的两份。
    合并的理由取自两份各自类注释里那句"为什么单独一份"：都是"答案的形状"。
    代价是 diff 会碰到 P03 / A05 的既有测试（断言原样搬运，不重写）。
    **不推荐**：塞进 `HttpChatChannelTest`(15)——余量 5 条，Slack 至少 12 条；
    也不推荐改 `scripts/verify.sh` 的上限（改判据要先问）。
  - `claw4j-app` 的 10 个文件：`ApplicationAssemblyTest` **20（已顶格）**、`RunSchemaSqlTest` 20、
    `ApprovalFlowTest` 14、`ScheduleFlowTest` 14、`SearchSqlTest` 11、`RunBudgetTest` 6、
    `RunDeliveryTest` 6、`ExamplesSubagentsTest` 5、`MigrationCompositionTest` 3、`ProcessBoundaryTest` 2。
    **推荐合并**：`ProcessBoundaryTest`(2) + `MigrationCompositionTest`(3) → `BuildContractTest`(5)，
    腾出的槽给 `SlackFlowTest`（真模型闭环）。这两份守的都是"构建与库结构"：
    一个守进程边界（不起容器的部分不许碰 Spring），一个守迁移历史与 schema 名单。
  - **注意 `ApplicationAssemblyTest` 已经 20 条顶格**，所以"工具清单/装配"那类断言塞不进去了——
    这点与 P12 不同（那次它还是 18）。
- **P12 那次的教训可以直接用**：`msgId` 的稳定性没有文档保证；Slack 的 `event_id` 有"globally unique"
  但**没说重试时不变**（是推断）。所以两个通道的措辞要一致：不假装验证过。
- 拆票说明：M8 余下三张（P14、P16，以及 P13 本身）尚未拆。P13 是「P12 / P13」拆出来的另一半，
  按书写顺序落在 Slack，与 P12 票面里的推断一致。

### 实现记录（2026-11-03）

四个结构问题的落点，与票面定下的一致：

1. **入站走 Events API 的 HTTP 回调**，不做 Socket Mode。落成
   `SlackCallbackController`（`/api/slack/events`）+ `SlackSignature`，一个端点、一次 HMAC。
2. **只订阅 `app_mention` 与 `message.im`**。翻译器的判断顺序是本类最要紧的地方：
   先挡 `bot_id`/`subtype`，再问 `user`，最后才看事件类型。`app_mention` 与
   `event.type=message` + `channel_type=im` 两条分支各自对应一种 `threadTs` 取值。
3. **`conversationId` = `频道|线程根`**，编解码都在 `channel.slack.SlackConversation`。
   放适配层不放 `client`，因为编与解必须成对，而解码那一半在发送器里——
   放进 `client` 的话适配层就得伸进子包，`ChannelBoundaryTest` 会红。
4. **拿 `ok` 判成败**，`ok=false` 一律抛，错误码与三个常见原因写在异常消息里。

实现时定下的三处协议细节（都不是"照直觉写"能对的）：

- **hex 用小写**。`HexFormat.of().formatHex()` 默认小写，与 Slack 发来的签名一致；
  写成大写（或 `String.format("%02X")`）是"签名永远对不上"，而症状像密钥配错。
- **`v0:` 与 `v0=` 只差一个字符**：basestring 里是冒号，签名里是等号。少一个就全错。
- **时间戳是秒、窗口 5 分钟**。判窗口用 `seconds * 1000` 与 `System.currentTimeMillis()` 比；
  照抄钉钉的"按位数认毫秒/秒"会把它复杂化——Slack 文档写死了秒，不需要兜。

限流排队做的是"**预约下一个空位**"而不是"记上次发送的时刻"：回执与短答案几乎同时发，
后者两条都会看到"已经隔了 0 毫秒"然后一起发。`ConcurrentHashMap#compute` 原子的，
并发的那两条会拿到相差一个间隔的两个时刻。第一条不等（拿到的是 `now`）——这一点是刻意的：
认人失败、线程池满那两条分支是在回调线程上同步发的，多等一个间隔就可能把 3 秒的应答窗口挤掉。

**"回调里不做网络调用"那条选的是 ①（接受并度量）**：`InboundDispatcher` 的认人失败与池满
两条分支确实会在回调线程上发一次 `chat.postMessage`。量化过：正常几十毫秒，加上可能的
1 秒排队间隔仍远小于 3 秒；要超时得是 Slack API 连续不可用，而那种情况下重试也发不出去。
没改 `InboundDispatcher` 的理由是它服务四个通道，为一条通道的窗口去动它，代价大于收益。

**如实交代（五条）**：

- **没有连过真的 Slack**。验签与发送都对着测试里自己写的假服务（JDK `HttpServer` 假扮
  `chat.postMessage`），签名是照协议另写一份、不是调生产代码。真凭据联调没做，仓库里没有 Slack 应用。
- **`event_id` 在重投时是否不变，文档没有明说**。文档只说它"globally unique"，
  所以去重按它做是**推断**；真正有保证的是 `x-slack-retry-num`，但那个头不能用来说"跳过"
  （第一次若是自己慢了没处理成，跳过就丢了唯一一次机会）。两条一起才成立，
  代码注释里写清了这一层，不假装验证过。
- **群聊与私聊的边界按事件类型划，不按"是不是群"划**：`app_mention` 一律回线程，
  `message.im` 一律回顶层。一个在频道里被 @ 的私聊场景（如果有）会落到前者，行为仍是对的。
- **不做 markdown → mrkdwn**：模型输出的 `**粗体**` 在 Slack 里会显示成字面量，
  表格完全不支持。代码块（三个反引号围起来的那种）两边语法相同，恰好是最常用的那个。
- **主动投递只回到原来那条线程**，不发私聊（要 `conversations.open` 换会话 id，第一版不做）。

门禁腾槽按票面第一条路线做，两处都是"同主题合并"而不是硬塞：
- `claw4j-channel`：`ApprovalControllerTest`(7) + `RunQueryControllerTest`(4) → `HttpEndpointsTest`(10)，
  腾出的槽给 `SlackChannelTest`(20)。合并时把两条 `principalRejectionMapsTo401` 并成一条
  （两条端点共用同一个口径），所以是 10 条而不是 11。
- `claw4j-app`：`ProcessBoundaryTest`(2) + `MigrationCompositionTest`(3) → `BuildContractTest`(5)，
  腾出的槽给 `SlackFlowTest`(1，真模型闭环)。
  合并的理由是它们守的都是"不会在运行时报错、只在某天以另一种形态暴露"的约定。
