# 02: 入站编排独立成模块

**What to build:** 新建 `claw4j-channel-core`，装 `channel.spi`（通道契约）与 `channel.kernel`
（入站编排：去重 → 认人 → 异步 → 分段回推）。`claw4j-channel` 依赖它，飞书与 HTTP 各自留在原模块。

**Blocked by:** 无。

**Status:** resolved

## 为什么

`InboundDispatcher` 现在做的事**与飞书无关**：立刻 ACK、按 eventId 去重、把 sender 认成 Principal、
在线程池里跑 Agent、按段把回答推回去、失败也回一句。它只认 JDK 类型与 `claw4j-domain`。

它跨通道成立，这一点有据可查，不是感觉：

| | 超时重推 | 幂等键 | 单条上限 | 认人标识 |
| --- | --- | --- | --- | --- |
| 飞书 | 3 秒 | `event_id` | 平台上限，按 4000 切 | `open_id` |
| Telegram | 约 7 秒（实测 0/8/16/32/64s 退避） | `update_id`（重试时不变） | 4096 字符 | `from.id` |
| Slack | 3 秒 | `event_id` + `X-Slack-Retry-Num` | 按 block 切 | `user` |

四列都是**同一个形状、不同参数**。所以结论是"骨架通用"，不是"飞书特有"。

但它现在在两个层面绑住了飞书：
1. 类的参数类型是 `FeishuInboundEvent` / `FeishuReplySender`；
2. 它住在 `claw4j-channel` 里，而那个模块的 pom 依赖 `oapi-sdk`（非 optional）。

后果很具体：第三方接 Telegram，实现 `ChatChannel` 要依赖 `claw4j-channel`，于是把飞书 SDK
拖进自己的 classpath；不想拖，就得把 ACK/去重/认人/异步/失败回话这五件事再抄一遍。

## 要做

新模块 `claw4j-channel-core`（`io.github.claw4j:claw4j-channel-core`），依赖只有
`claw4j-domain` + `spring-boot-starter`（为 `@ConfigurationProperties` 之类；实际按需）。

- `io.github.claw4j.channel.spi`：`ChatChannel`、`PrincipalRejectedException`、`TooBusyException`
  （从 `claw4j-channel` 整包挪过来，**包名不变**，所以引用它的类一行 import 都不用改）。
- `io.github.claw4j.channel.kernel`：
  - `InboundEvent`（`eventId` / `conversationId` / `senderId` / `text` / `receivedAt`）
  - `ReplySender`（`send(conversationId, text)`）
  - `InboundDispatcher`（`accept(InboundEvent)`，构造函数多一个 `platformName` 用于文案）
  - `MessageSplitter`（包私有，原样搬）
- `claw4j-channel` 的 pom 加一条依赖；飞书侧：
  - `FeishuInboundEvent extends InboundEvent`（`chatId()` 保留，`conversationId()` 给 default 桥接）
  - `FeishuReplySender extends ReplySender`
  - `FeishuChannelAutoConfiguration` 构造 dispatcher 时传 `"飞书"`

## 判据

- 新模块的 `pom.xml` 里没有 `com.larksuite.oapi`，`dependency:tree` 里也搜不到。
- `mvn -pl claw4j-channel-core dependency:tree` 只有 `claw4j-domain` 与 slf4j 一类。
- 原有 42 条通道用例搬过去仍全绿（数量不减）。
- 一条新用例：用**一个假的第三方通道**（只有 5 个字段的事件 + 一个记录发送的实现）
  跑通"去重 → 认人 → 异步 → 分段回推"，证明这套编排不需要飞书。
- ArchUnit：`channel.kernel` 不依赖 `channel.feishu`、`channel.http`。

## 不要做

- 不把 `InboundDispatcher` 做成接口 + 多实现：它的四种行为没有第二种合理做法，
  抽象出来只是多一层。
- 不把 HTTP 通道也改成走 dispatcher：HTTP 请求本来就能同步返回，加一层异步只会让
  `curl` 拿到 202 而不是答案。
- 不为 Telegram 真写一个通道实现（没有凭据，写出来也测不了）。

## 实现记录

**做了什么**

- 新模块 `claw4j-channel-core`，`pom.xml` 的 compile 依赖**只有两条**（实测 `dependency:tree`）：
  ```
  io.github.claw4j:claw4j-channel-core:jar:0.1.0-SNAPSHOT
  +- io.github.claw4j:claw4j-domain:jar:0.1.0-SNAPSHOT:compile
  +- org.slf4j:slf4j-api:jar:2.0.20:compile
  ```
  **比票面写的少一条**：没有引入 `spring-boot-starter`。`channel.kernel` 里唯一用到 Spring 的地方是
  `FeishuChannelAutoConfiguration`（构造 dispatcher），它在 `claw4j-channel` 里。少一条依赖 =
  第三方接 Telegram 时 classpath 更干净。
- `channel.spi` 四个文件整包 `git mv` 到新模块（**包名不变**），除了 `InboundDispatcher` 那一处 import，
  飞书侧与 HTTP 侧一行都没改。
- `channel.kernel` 四个文件：`InboundEvent`（五字段接口）、`ReplySender`、`InboundDispatcher`、`MessageSplitter`。
- `FeishuInboundEvent extends InboundEvent`，只加一个默认方法：
  ```java
  @Override
  default String conversationId() {
      return chatId();
  }
  ```
  `chatId()` 保留原名，飞书侧读起来仍然是飞书的词。
- `FeishuReplySender extends ReplySender`，方法签名保持 `send(String chatId, String text)`。
- 拒绝文案从常量 `NO_PERMISSION_REPLY` 变成实例字段，由构造参数 `platformName` 拼出来：
  ```java
  this.noPermissionReply = "没有权限：你的" + platform + "账号还没有在 Claw4J 里登记。请联系管理员开通后重新提问。";
  ```
  飞书那段文案逐字不变（`FeishuChannelWiringTest` 仍然断言它）。

**判据核对**

| 判据 | 结果 |
| --- | --- |
| 新模块 pom 里没有 `com.larksuite.oapi` | 成立；依赖树只有 domain + slf4j |
| `dependency:tree` 只有 domain 与 slf4j 一类 | 成立（见上） |
| 原有通道用例搬过去仍全绿 | `InboundDispatchTest` **13 条 → 16 条**，全绿（不是"数量不减"，是加了 3 条） |
| 一条"假第三方通道"跑通整条编排 | 成立。测试里没有任何飞书字样，用 `record Event(...) implements InboundEvent` |
| ArchUnit：`channel.kernel` 不依赖 `channel.feishu` / `channel.http` | 换成**白名单**写法（见下），2 条规则 |

**两条 ArchUnit 规则**（`KernelBoundaryTest`）：

```java
classes().that().resideInAPackage("io.github.claw4j.channel..")
        .should().onlyDependOnClassesThat().resideInAnyPackage(
                "java..", "org.slf4j..",
                "io.github.claw4j.domain..", "io.github.claw4j.channel..");
```

**为什么写成白名单而不是点名飞书**：点名 `channel.feishu` 的规则在本模块里**恒绿**——本模块的
扫描路径下根本没有那个包，ArchUnit 找不到被引用的类，也就报不出违规。白名单把"只许这几家"写死，
任何新依赖（包括 `org.springframework..`）都会红。

**变异验证**（不做这一步，规则是否在检查类是无从判断的）：把白名单里的 `io.github.claw4j.domain..`
删掉 → `Architecture Violation ... was violated (11 times)`，11 处正是 `InboundDispatcher` 与
`MessageSplitter` 用到 domain 的地方；恢复后转绿。

**新增的 3 条用例**（原 13 条之外）：
- `missingEventIdStillProcesses`：平台没给幂等键时**不能把消息丢掉**（去重不能变成过滤器）；
- `resolverFailureStillAnswersTheUser`：认人抛异常时用户仍然收到一句话，而不是静默；
- `sendFailureStopsFurtherParts`：第 2 段发送失败后不再发第 3 段（用三段各 3000 字保证切出 ≥3 段）。

**门禁**

```
用例 299 条，失败 0，错误 0，跳过 0
claw4j-channel-core       2         16      86.3%
claw4j-channel            5         15      63.8%
全绿：299 条用例全过，跳过 0，行覆盖率均不低于 60%。
```

**踩到的两个坑**

1. `mvn -pl claw4j-channel test` 报 `Cannot access ... in offline mode`：新模块还没进本地仓库，
   必须带 `-am`（`-pl claw4j-channel -am test`）。
2. **搬走一块高覆盖代码会把剩余模块的覆盖率打下来**：`claw4j-channel` 从原来的一路绿掉到 42.3%
   （`InboundDispatcher` 约 100 行全绿被搬走，剩下的 `FeishuLongConnectionChannel` 0/60、
   `FeishuMessageTranslator` 0/39、`FeishuOpenApiReplySender` 0/25 露了出来）。
   这是既有缺陷被暴露，不是拆分引入的问题。补 `FeishuMessageTranslatorTest`（10 条）与
   `HttpChatControllerTest`（6 条）后回到 63.8%。
