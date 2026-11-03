# P12 · 钉钉通道：第四个入口，这次验签在 header 里

**Status:** ready-for-agent
**Blocked by:** None（P06 的通道模板已落地，`InboundDispatcher` 与 `OutboundDelivery` 都现成）

## 背景

ADR-0020 对这一对（P12 / P13）只有一句话：「扩展点已经就绪，成本线性。这是企业落地的第一道门，
但排在 M8 而不是 M7b——通道越多，答错传得越广，所以要等 M8 的评测与自检先在。」

现在通道有三条：HTTP（本机与联调）、飞书（长连接）、企业微信（回调 + 主动推）。三条之后
这个模块的形态已经稳定：`channel` 只认识 `channel.spi` 与 `channel.kernel` 两个契约包
（都在 core），适配层（`channel.<平台>`）只有入站事件、回推出口、投递实现三个类型，
"怎么跟平台说话"全部关在子包里（`feishu.sdk` / `wecom.client`），并由 `ChannelBoundaryTest`
用两条结构规则钉住。

钉钉要做的就是把这一套再走一遍。**这不是"再写一个通道"的体力活，因为钉钉的入站协议与企微
不是同一类**：企微回的是加密 XML，钉钉机器人回调回的是**明文 JSON + header 签名**。
选错入口的代价不是"多写点代码"，而是拿一条拿不到用户消息的路去接。

## 必须先回答的一个结构问题

**入站走哪一条？钉钉有三条听起来都像"接收消息"的路。**

- **机器人回调（HTTP 模式）**：应用里给机器人配一个公网地址，用户 @ 机器人或单聊时，
  钉钉往这个地址 POST 一条**明文 JSON**，header 带 `timestamp` 与 `sign`。验签用
  `HmacSHA256(appSecret, timestamp + "\n" + appSecret)` 再 Base64。这条路的载荷里有
  `senderStaffId`（企业内 userId）、`text.content`、`msgId`、`conversationType`——**正好是
  内核要的那五个字段**。
- **事件订阅（应用级回调）**：加密 JSON（`{"encrypt": ...}`）+ `msg_signature`，加密布局与
  企微几乎一样（`random(16) + msg_len(4) + msg + key`，企业内应用的 `key` 是 CorpId）。
  但它推的是通讯录、审批、群事件这类**企业事件**，"用户对机器人说了一句话"不在其中。
  而且它要求返回一个加密的 JSON 信封（`msg_signature` / `timeStamp` / `nonce` / `encrypt`），
  不是空串。
- **Stream 模式**：WebSocket 长连接，要一个 SDK（`dingtalk-stream-sdk-java`），本地仓库没有，
  离线构建会红。

选第一条。判据很简单：**只有它能拿到"谁问了什么"**。第二条的加密代码虽然能照抄企微那份
（布局一样，只是 `key` 由 corpid 变成 corpId 同名同物），但接完会发现收不到业务消息——
这是"通道装好了、日志干净、就是没人能提问"的形态。

## 三个不替实现决定的点

1. **回复走哪一条腿**。回调报文里有 `sessionWebhook`（一次性的会话地址，约 1.5 小时有效），
   直接往它 POST 就能回到原会话，不需要 accessToken；主动发单聊则要换 token 再调
   `POST /v1.0/robot/oToMessages/batchSend`（body 里 `robotCode` + `userIds` + `msgKey=sampleText`
   + `msgParam={"content":"..."}`）。前者的好处是群聊也能回到群里；代价是它是一份**带过期时间的
   地址**，而 `ReplySender` 的签名只有 `(channelRef, text)`，要让它能用就得在通道内部维护一张
   `userId → sessionWebhook` 的短缓存——那张表过期之后的行为（回落 batchSend 还是发不出去）
   是另一种"偶发收不到"。后者的好处是与 `OutboundDelivery`（A05 主动投递，可能几小时后才推）
   同一条路，代价是群聊场景下答案走单聊、用户要切窗口。两条都成立，实现时选定并写进票。

2. **群聊怎么办**。企微没有"群"这一层（自建应用消息一对一），所以 P06 不用回答这个问题。
   钉钉有：`conversationType=1` 单聊、`=2` 群聊，群聊回群要 `openConversationId`。
   第一版只做单聊（`conversationType=2` 收到后回一句"私聊我"或直接走私聊）是正当的裁剪，
   但**必须让用户看得见这个裁剪**：静默地把群里的提问当成单聊回，群里的人只会觉得机器人没反应。

3. **凭据怎么配**。既有两个通道用的是 `${FEISHU_APP_SECRET:}` / `${WECOM_CORP_SECRET:}` 这种
   环境变量占位，**不是** P08 的 `secret:` 句柄；而 `application.yml` 顶部的注释写的是
   「凭据一律写句柄……裸写明文会在启动时失败并点名是哪一行」。这句对通道凭据不成立
   （校验只落在 `claw4j.model.*.api-key` 与 MCP 的 `env` / `headers` 上）。实现时二选一：
   与既有通道一致（环境变量占位，顺手把顶部那句注释改准确），或者让钉钉第一个用 `secret:`
   句柄（那要回答"通道侧从哪里拿 resolver"——`SpringSecretResolver` 在 capability，
   channel 不依赖 capability，这是真问题）。选哪个都行，**不能选的是假装那句注释已经成立**。

## 验收判据

- [ ] **验签真的挡住了**：`sign` 对不上或 `timestamp` 与当前差超过 1 小时，这条回调**不处理**
      （不进编排、不调模型），且**返回 200 而不是 5xx**——5xx 会让钉钉重推一条同样验不过的消息。
      用例要有一条"签名错但报文合法"的反例。
- [ ] **只处理该处理的**：`msgtype` 不是 `text`（图片 / 语音 / 文件 / 富文本）回一句"打成文字"，
      不交给模型；`conversationType=2` 按上面第 2 点选定的方式处理，不能静默丢弃。
- [ ] **认人与拒绝**：`senderStaffId` 在 `claw4j.channel.dingtalk.roles.*.users` 里没有登记时，
      回"没有权限"，这句话**不进模型、不花 token**；登记了的人拿到 `Principal`（表与动作来自
      共用的 `claw4j.channel.http.roles`，角色名对不上就启动失败，与 P06 同构）。
- [ ] **身份缺失与未登记要分开**：`senderStaffId` 为空（机器人未发布上线、或外部群成员）时，
      日志要说清是"钉钉没给身份"，不是"这个人没登记"——两者在 IM 里长得一样，排查成本却差很远。
- [ ] **重推只答一次**：同一个 `msgId` 十分钟内重推，第二次只 ACK 不执行。注意 `msgId` 是加密串，
      官方文档对它写的是"暂无使用场景"——**它的稳定性没有被文档保证**，所以实现时若发现重推
      换了 `msgId`，去重就失效；这一条要如实写进票的 Comments，不假装验证过。
- [ ] **先回执后答案**：钉钉单聊没有"正在输入"，对话框在十几秒里没有任何动静与"没收到"分不清。
      回执排在去重与认人之后（`InboundDispatcher` 的第五件事，P06 已经做好），发失败只记日志。
- [ ] **发送侧**：token 缓存且提前换（`expireIn` 7200 秒）；`flowControlledStaffIdList` /
      `send.too.fast` 这类限流要能被看见（记 WARN 或抛出，别吞）；`errcode` 非 0 一律抛，
      **不吞成"发出去了"**。
- [ ] **默认关，且关着时行为不变**：配置不齐（`client-id` / `client-secret` / `robot-code` 缺一样）
      整份退让，一个 bean 都不注册，只跑 HTTP；`enabled=false` 同理。既有 539 条用例全过。
- [ ] **主动投递可用**：`OutboundDelivery.channel()` 是 `dingtalk`，与 `agent_run_delivery.channel`
      同口径；"在别处做完之后推回钉钉"这条路上有它的位置（P06 踩过的坑：条件要按**名字**判，
      不能按 `OutboundDelivery` 类型判，否则两个同类型 bean 谁先装配谁赢）。
- [ ] **结构规则跟着加**：`channel.dingtalk` 不依赖实现子包、也不依赖 JDK / slf4j / 自家代码以外的东西，
      两条规则与飞书、企微那四条同构（`ChannelBoundaryTest` 6 → 8 条）。
- [ ] **真模型闭环**：app 层一条，真回调（真签名）→ 真模型 → 记录器收到答案。回推那一层换替身，
      断言"先回执、答案里有 ```128400```"（与 P06 那条同形）。
- [ ] `./scripts/verify.sh` 全绿，跳过 0，行覆盖率不低于 60%。
- [ ] **门禁余量**：`claw4j-channel` 现在是 **10 个测试文件（上限 10）**，本票**不能新增测试文件**。
      见下面 Comments 的三条出路。

## 不做

- **不做 Stream 模式**：要 `dingtalk-stream-sdk-java`，本地仓库没有、离线构建会红。
  而且它是长连接，与"回调地址"是互斥的两条形态，一条做对就够了（ADR-0009 对飞书已经定过：
  长连接只在有 SDK 时才有意义）。
- **不做事件订阅那一条**（加密 JSON + `msg_signature`）：见上面的结构问题。加密布局与企微一样，
  真要做，是把 `WecomCipher` 抽出来的活，不是这一票的判据。
- **不做群机器人 webhook**（自定义机器人，只能推不能收）、**不做卡片与互动卡片**、
  **不做富文本 / 图片 / 语音的内容理解**（回"打成文字"，与企微一致）。
- **不做通讯录同步**：成员仍手写进 `application-local.yml`，与企微同一条路。
- **不做多实例去重**：回调是平台单点投递，单实例是既定形态（ADR-0009）。
- **不做"用 API 注册回调地址"**：钉钉要在开发者后台手填，与企微一样，写进 README 即可。

## Comments

- 拆票时的现状核对（2026-11-03，逐个打开看过）：
  - 通道三条已就绪，模块形态稳定：`channel.http` / `channel.feishu`（+`sdk`）/ `channel.wecom`
    （+`client`）。钉钉按同一形状落在 `channel.dingtalk`（三个类型）+ `channel.dingtalk.client`
    （验签、翻译、发送、装配）。
  - `InboundDispatcher` **不注册成 bean**，在 `wecomCallbackController` 里就地建（`WecomChannelAutoConfiguration:127`）。
    原因写在 `WecomChannelAutoConfiguration` 的类注释里：容器里已经有一个（飞书那份），
    按类型注入会歧义。钉钉必须照做，否则是三个同类型 bean。
  - `OutboundDelivery` 的装配条件按**名字**判（`wecomOutboundDelivery` / `feishuOutboundDelivery`），
    钉钉要用 `dingtalkOutboundDelivery`。这是 P06 修过的真 bug，注释就在那儿。
  - 授权表只有一份：`claw4j.channel.http.roles.{role}.{tables,actions}`。各通道只登记"谁是谁"
    （`roles.*.users`），角色名对不上就启动失败（`WecomPrincipalResolver#requireEveryRegisteredRoleExists`）。
  - `WecomChannel.RECEIPT_REPLY` 那句回执是通道给的，什么时候发由 `InboundDispatcher` 定。
    钉钉同样需要一句（同一个理由：单聊没有"正在输入"）。
  - 凭据现状：`feishu.app-secret` 与 `wecom.corp-secret` 是环境变量占位，不是句柄；
    `application.yml` 顶部那句"凭据一律写句柄"只对模型 key 与 MCP 成立。见上面第 3 点。
- **门禁余量（硬约束，开工前先看）**：`claw4j-channel` 的 10 个测试文件已满
  （按脚本口径：`WecomChannelTest` 19、`HttpChatChannelTest` 15、`FeishuMessageTranslatorTest` 10、
  `HttpChatControllerTest` 9、`FeishuChannelWiringTest` 9、`FeishuApprovalTest` 9、
  `ApprovalControllerTest` 7、`ChannelBoundaryTest` 6、`RunQueryControllerTest` 4、
  `FeishuOutboundDeliveryTest` 1）。三条出路，实现时选一条并写进票：
  1. **合并腾槽**（推荐）：`FeishuOutboundDeliveryTest` 只有 1 条，并进 `FeishuChannelWiringTest`（9 → 10），
     删掉原文件，腾出一个槽位建 `DingtalkChannelTest`。代价是 diff 会碰到飞书的既有测试文件。
  2. **塞进未满的文件**：钉钉的用例按"验签"与"发送"拆开塞进 `HttpChatChannelTest`（15）与
     `WecomChannelTest`（19）——语义不对，且两个文件加起来只剩 6 条余量，钉钉至少需要 8~10 条。
  3. **放宽上限**：改 `scripts/verify.sh` 的 `MAX_FILES`。这是改判据，不在本票权限内，要先问。
- 协议事实（逐个查过官方文档，不是凭记忆；链接与说法都写在下面，实现时请按同样的来源核对）：
  - **HTTP 模式回调是明文 JSON + header 签名**：`timestamp`（毫秒）与 `sign`，
    `sign = Base64(HmacSHA256(key=appSecret, data=timestamp + "\n" + appSecret))`，
    且"timestamp 与系统当前时间戳相差 1 小时以上即非法"
    （`developers.dingtalk.com/document/robots/receive-message`，例子里有完整 Java 代码）。
  - **`senderStaffId` 不是总在**：文档写"企业内部群中 @ 该机器人的成员 userId，机器人发布上线后生效，
    否则不会返回"，外部群成员同样为空。所以"认不出人"有一条与配置无关的来源。
  - **`msgId` 是加密串**，文档对它写的是"暂无使用场景，可忽略"——去重键的稳定性没有文档保证。
  - **`createAt` 是毫秒**（字符串）。企微的 `CreateTime` 是**秒**——P06 的 Comments 记过照抄会得到
    1970 年，这次方向相反，照企微抄会得到五万年后。
  - **发单聊**：`POST https://api.dingtalk.com/v1.0/robot/oToMessages/batchSend`，
    header `x-acs-dingtalk-access-token`，body `{robotCode, userIds[], msgKey, msgParam}`
    （`sampleText` 的 `msgParam` 是 `{"content": "..."}` 的**字符串**，不是嵌套对象）。
    错误码里值得先记住的三个：`robot.oto.notExist`（没有有效单聊会话，即用户没跟机器人说过话）、
    `chatbotId.notAllow.sendOTO`（机器人未启用单聊）、`send.too.fast` / `send.byToken.tooFast`（限流）。
  - **换 token**：`POST https://api.dingtalk.com/v1.0/oauth2/accessToken`，body `{appKey, appSecret}`
    → `{accessToken, expireIn}`，7200 秒。关键差异：**有效期内重复获取返回同一张并自动续期**，
    所以企微那段"平台先一步废掉旧 token，本地判没过期就会重发同一张"的重试逻辑**不要照抄**——
    抄过来是一段永远不触发的死代码，而真正需要处理的限流（`send.too.fast`）反而没有位置。
  - **事件订阅是另一条路**：加密 JSON + `msg_signature`，加密布局
    `random(16) + msg_len(4) + msg + key`（企业内应用 `key` = CorpId），返回时要回一个加密信封
    （`developers.dingtalk.com/document/development/callback-event-message-body-encryption-and-decryption`）。
    这一条本票不做，写在这里是为了下次有人问"为什么不复用 `WecomCipher`"时能直接指过来。
- **调用量上限是真实的**：文档里"机器人 Webhook 和 Stream 用量超量"有专门的错误码 20001
  （受调用量影响服务暂停，需升级专业版或买增购包）。对一个"企业里人人来问"的部署，
  这件事要在 README 里说一句，而不是等第一次被限流时才发现。
- 拆票说明：M8 其余四张（P10、P13、P14、P16）尚未拆。P13 是 Slack，与本票同一句话拆出来的
  另一半——ADR 写的是「P12 / P13 · 钉钉 / Slack 通道」，**按书写顺序取 P12 = 钉钉**，
  若要反过来，说一句即可（这一条是本票唯一的推断，其余都核过）。
