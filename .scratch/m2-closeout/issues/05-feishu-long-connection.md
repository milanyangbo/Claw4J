# 05: 飞书长连接接上真 SDK，关掉 M2

**What to build:** SDK 主动外连（不要公网回调端点）→ 把 SDK 事件翻成入站事件交给 04 那层 →
回推接口的实现调用飞书消息 API。凭据齐全时才装配；凭据缺失时整个通道退让而 HTTP 照常；
凭据配了却连不上则启动失败（带着半死的通道跑比起不来更难排查）。

做完这一条，ADR-0008 给 M2 的验收判据（**且能在飞书里发消息问答**）才算补齐——现在是"票都关了、
里程碑没关"。

**Blocked by:** 04

**Status:** claimed

**Demo:** 在飞书群里 @ 机器人问"上周华东区成交额多少"，几秒后群里出现一条带数字与口径的回答；
同一条消息被飞书重推两次，群里也只有一条。

- [ ] 凭据齐全时启动建立长连接，群里 @ 机器人的提问拿到带数字与统计口径的回答
- [x] 凭据缺失 → 该通道不装配，HTTP 通道照常工作，启动成功
- [ ] 凭据配了但连不上 → 启动直接失败，不是带着一个收不到消息的通道继续跑
- [x] SDK 不在 classpath（不带 `-Pchannel-feishu`）→ 整个包退让，不出现 `ClassNotFoundException`
- [x] `-Pchannel-feishu` 打包后 fat jar 里含 SDK；默认包里没有
- [x] 日志按"收到事件 → 已 ACK → 开始处理 → 已回推"四段可定位
- [ ] `feishu-live` tag 的真链路用例在 `-Pfeishu-live` 下跑通（**不用 `assumeTrue`**：skipped 会被
      `verify.sh` 判红，做法照 `real-cli` 的先例）
- [x] 凭据走环境变量（`FEISHU_APP_ID` / `FEISHU_APP_SECRET`，统一进 `.env`），不进仓库、不进示例定义

**落点:** `channel.feishu` 内的 SDK 绑定层 + 条件装配；父 pom 的 `feishu-live` 排除规则与
`-Pfeishu-live`；`-Pchannel-feishu` 在 `claw4j-app` 把 SDK 声明为 compile。

**注意:** SDK 绑定层**不写单测**——它的全部内容就是"翻译 SDK 对象"与"发一条消息"，只有真凭据下才有意义。
覆盖不到的部分如实记进 guardrails（见 06）。交付时要说清这一档：没有凭据时"假事件源下 ACK/去重/回推成立"
不等于"真链路跑通了"。

**不要做:** 公网回调端点（要公网 IP、要签名校验，与"本地能调试"相悖）；Telegram 第二通道；卡片消息。

## 进展（2026-10-26，没有飞书凭据时能做到的那一档）

代码全部写完并编译过；**真链路三条没验**，所以票不置 `resolved`。

- 新增 `channel.feishu.sdk` 包：`FeishuChannelProperties`（凭据 + 角色→授权 + `users` 登记）、
  `FeishuConfiguredCondition`（"两个都非空"才算配了）、`FeishuMessageTranslator`（事件 → 入站事件）、
  `FeishuOpenApiReplySender`（`im().message().create`，`receive_id_type=chat_id`、`msg_type=text`）、
  `FeishuLongConnectionChannel`（`start()` + `awaitReady(15s)`，连不上就抛 → 启动失败；
  回调里绝不抛，避免平台重推一条处理不了的消息）、`FeishuChannelAutoConfiguration`。
- 装配三条条件实测：凭据缺失/半配 → 一个 bean 都不留；SDK 不在 classpath（`FilteredClassLoader`）→
  整个包退让；`enabled=false` → 不装。三条都在 `FeishuChannelWiringTest`（6 条）。
- 打包实测（必须 `clean`，否则切 profile 后 jar 不体现依赖变化）：
  默认包 114,440,867 字节，`oapi|gson` 命中 **0**；`-Pchannel-feishu` 142,381,540 字节，
  含 `oapi-sdk-2.8.7.jar` / `gson-2.13.2.jar` / `larksuite-oapi-shaded-protobuf-2.4.6.jar`。
- 顺带查实两件事（写给下一个人）：
  ① Boot 4 的 JSON 是 **Jackson 3**（`tools.jackson.databind`，`com.fasterxml.jackson.databind` 不在
  classpath 上）；`readTree`/`writeValueAsString` 抛的是 unchecked 的 `JacksonException`。
  ② `ApplicationContextRunner.run()` **不抛**启动失败，要断言 `assertThat(context).hasFailed()`
  ——用 `assertThatThrownBy` 包它永远"没抛"（这一条我先写错，白折腾了一轮 `@PostConstruct`/`InitializingBean`）。
- 覆盖率：channel 61.0%（SDK 层按票面约定不写单测，这 61% 里绝大部分来自调度层与 HTTP）。
  离 60% 的闸只有 1 个点，属"如实记账"而不是达标——真链路补上之后会回到 80% 以上。
- 未做（等凭据）：真连接一条、群里问答一条、`feishu-live` tag 与 `-Pfeishu-live` 排除规则。
  这三条**必须在有凭据的会话里边跑边写**：假事件能证明我们读对了 getter，证明不了飞书真会那样推。

## 怎么拿到凭据（照这个走，个人账号就行）

顺序照官方提示来：**先在控制台把应用建好、能力与权限配齐，再用凭据把本地长连接跑起来，
最后回去切订阅方式并发布版本**。官方原文是"你需先运行示例代码建立长连接，再保存该订阅方式"
——先连、后存，不要反过来。

1. **建应用**：<https://open.feishu.cn> → 右上角「开发者后台」→「创建企业自建应用」。
   个人账号可以直接建企业自建应用（ADR-0009 的事实核查结论），名称随便填。
2. **拿凭证**：「基础信息 > 凭证与基础信息」，`App ID`（`cli_` 开头）与 `App Secret`。
   Secret 只显示一次，抄下来。
3. **加机器人能力**：「应用能力 > 添加应用能力」→「机器人」。不加这个，消息事件根本不会投递。
4. **申请权限**：「开发配置 > 权限管理 > API 权限」，用「批量导入」贴：
   ```json
   {"scopes":{"tenant":[
     "im:message.p2p_msg:readonly",
     "im:message.group_at_msg:readonly",
     "im:message:send_as_bot"
   ]}}
   ```
   三条各自管一件事：私聊收（p2p）、群里 @ 收（group_at）、**以应用身份发**（send_as_bot，
   我们的回推走 `tenant_access_token` + `im().message().create`）。少最后一条的典型现象是
   "收得到、回不出去"，日志里一条 `code != 0`。
5. **先用凭据把长连接跑起来**（这一步在切订阅方式之前）：
   ```bash
   printf 'FEISHU_APP_ID=cli_xxx\nFEISHU_APP_SECRET=xxx\n' >> .env
   ./mvnw -o install -DskipTests          # 兄弟模块要先进本地仓库，-pl 单独跑才找得到
   ./mvnw -o -pl claw4j-app spring-boot:run -Pchannel-feishu -Dspring-boot.run.profiles=local
   ```
   日志出现 `飞书通道就绪：长连接已建立` 只说明**凭据与网络**都对。还要看到
   `The following 1 profile is active: "local"`，否则 `application-local.yml` 整份没加载、
   `roles.*.users` 等于没写（凭据可能来自环境变量，**"能连上"对两处都成立**，群里表现却一样）。
   `-Pchannel-feishu` 也不能省：`oapi-sdk` 在 `claw4j-channel` 里是 optional、不传递，
   少了它整个通道静默退让（只跑 HTTP）。
   库已在跑时再加 `SPRING_DOCKER_COMPOSE_ENABLED=false`：`spring-boot:run` 的工作目录是
   模块目录 `claw4j-app`，`compose.yaml` 在仓库根，local 打开 compose 后会直接失败
   `'files' content [compose.yaml] must exist`。
6. **切订阅方式并加事件**：「开发配置 > 事件与回调 > 事件配置」→「订阅方式」→
   「使用长连接接收事件」→ 保存 →「添加事件」→ `接收消息 im.message.receive_v1`。
7. **设可用范围并发布**：「应用发布 > 版本管理与发布」→ 创建版本 → 可用范围里至少包含你自己
   → 发布。**权限与事件的变更都必须走一次发布才生效**，漏了这步的现象是"应用起来了、群里 @ 了、
   日志里什么都没有"。
8. **把机器人拉进群**：群设置 → 群机器人 → 添加机器人；私聊则直接在飞书里搜应用名。

### open_id 从哪来（填 `roles.*.users` 用）

不用去后台找。**第一次 @ 机器人，然后看日志**——`InboundDispatcher` 会记一条 WARN：

```
飞书用户未登记，已拒绝：sender=ou_xxxxxxxxxxxxxxxx chatId=oc_xxxxxxxx
```

把它填进 `application-local.yml`（被 gitignore，别改仓库里那份 `application.yml`）：

```yaml
claw4j:
  channel:
    feishu:
      roles:
        read-only:
          users: ["ou_xxxxxxxxxxxxxxxx"]
```

重启后再问一句，回答会带上库里的数与口径。注意 `open_id` 是**按应用**分配的，
换个应用就变了，别把它抄进文档或示例。

### 四个典型故障对应哪一步没做

| 现象 | 卡在哪 |
| --- | --- |
| 启动直接失败，日志里 `ClientException` / `awaitReady` 超时 | 步骤 2 的 App Secret 抄错或已被重置（两个都非空才会装配，所以这一定是"配了但连不上"） |
| 启动成功但群里 @ 没有任何反应 | 步骤 6（事件没加/订阅方式还是"请求地址"）或步骤 7（没发布） |
| 日志里有"未登记"WARN，群里回"没有权限" | 正常，把 `sender=` 的 `ou_...` 填进下面那份 `application-local.yml` |
| 收到事件但回推失败、日志里 `code != 0` | 步骤 4 少 `im:message:send_as_bot`，或机器人不在那个群里 |
