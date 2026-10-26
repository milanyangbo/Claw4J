# 05: 飞书长连接接上真 SDK，关掉 M2

**What to build:** SDK 主动外连（不要公网回调端点）→ 把 SDK 事件翻成入站事件交给 04 那层 →
回推接口的实现调用飞书消息 API。凭据齐全时才装配；凭据缺失时整个通道退让而 HTTP 照常；
凭据配了却连不上则启动失败（带着半死的通道跑比起不来更难排查）。

做完这一条，ADR-0008 给 M2 的验收判据（**且能在飞书里发消息问答**）才算补齐——现在是"票都关了、
里程碑没关"。

**Blocked by:** 04

**Status:** ready-for-agent

**Demo:** 在飞书群里 @ 机器人问"上周华东区成交额多少"，几秒后群里出现一条带数字与口径的回答；
同一条消息被飞书重推两次，群里也只有一条。

- [ ] 凭据齐全时启动建立长连接，群里 @ 机器人的提问拿到带数字与统计口径的回答
- [ ] 凭据缺失 → 该通道不装配，HTTP 通道照常工作，启动成功
- [ ] 凭据配了但连不上 → 启动直接失败，不是带着一个收不到消息的通道继续跑
- [ ] SDK 不在 classpath（不带 `-Pchannel-feishu`）→ 整个包退让，不出现 `ClassNotFoundException`
- [ ] `-Pchannel-feishu` 打包后 fat jar 里含 SDK；默认包里没有
- [ ] 日志按"收到事件 → 已 ACK → 开始处理 → 已回推"四段可定位
- [ ] `feishu-live` tag 的真链路用例在 `-Pfeishu-live` 下跑通（**不用 `assumeTrue`**：skipped 会被
      `verify.sh` 判红，做法照 `real-cli` 的先例）
- [ ] 凭据走环境变量（`FEISHU_APP_ID` / `FEISHU_APP_SECRET`，统一进 `.env`），不进仓库、不进示例定义

**落点:** `channel.feishu` 内的 SDK 绑定层 + 条件装配；父 pom 的 `feishu-live` 排除规则与
`-Pfeishu-live`；`-Pchannel-feishu` 在 `claw4j-app` 把 SDK 声明为 compile。

**注意:** SDK 绑定层**不写单测**——它的全部内容就是"翻译 SDK 对象"与"发一条消息"，只有真凭据下才有意义。
覆盖不到的部分如实记进 guardrails（见 06）。交付时要说清这一档：没有凭据时"假事件源下 ACK/去重/回推成立"
不等于"真链路跑通了"。

**不要做:** 公网回调端点（要公网 IP、要签名校验，与"本地能调试"相悖）；Telegram 第二通道；卡片消息。
