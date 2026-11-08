# 02: 通道连接失败分两类——可恢复的走后台重连，永久错误的仍失败

**What to build:** `FeishuLongConnectionChannel#start()` 现在把所有失败判成一类、一律抛出去让应用起不来。
拆成两类：**凭据/配置类**（永久）继续抛；**连接超时、握手失败、后续断开**（可恢复）改成
"起一个后台退避重连 + 上报状态"，应用照常启动，其它通道照常工作。

**Blocked by:** 无。

**Type:** task

**Status:** resolved（2026-11-08）

**Demo:** 把 `.env` 里的 `FEISHU_APP_ID` 改成一个不存在的应用 → 启动仍然失败，错误句点名"凭据"；
在能连上的环境里拔网线再插回来 → 进程没退、`curl localhost:8080/...` 一直可用，
日志里出现"第 N 次重试后连上"，群里 @ 机器人照常回答。

## 背景：这条是偏差，不是选择

`docs/channels/slack/transports.md:71` 原文（OpenClaw，讲通道连接，飞书通道页没有相反的规定）：

> Recoverable start, start-wait, and disconnect failures **retry until the channel stops**.
> Permanent account and credential errors such as invalid auth, revoked tokens, or missing scopes
> **fail fast instead of retrying forever.**

它分的是**"这个错会不会自己好"**；我们现在把两类都判成"不会"：

| 情形 | 它 | 我们 |
| --- | --- | --- |
| App ID / Secret 写错（永久） | 立刻失败，报凭据错 | 失败 ✅ 一致 |
| 飞书限流、DNS 抖动、握手超时（可恢复） | 后台退避重连，进程照常 | **进程退出** → K8s 上是 CrashLoopBackOff，**连 HTTP 通道也一起不可用** |

一次对端重启，对我们就是一次全站不可用。这不是"我们选择更严格"——严格判的是**配置**，
而这里判错的是**时序**。

## 现状（代码证据）

- 契约本身就写着一律抛：`ChatChannel.java:25-28`——"建立连接或注册端点。失败要抛异常让启动失败"。
- `FeishuLongConnectionChannel#start():88-124`：`new Client.Builder(appId, secret)`（`:109-111`）→
  `start()` + `awaitReady(timeout)`（`:112-114`）→ `catch (Exception)`（`:115-120`）里
  `closeQuietly` + `throw new IllegalStateException("飞书长连接没建起来…")`。**`catch (Exception)` 这一句
  就是那条"一律判成不可恢复"的落点**：凭据错、握手超时、DNS 失败在这里已经分不开了。
- 抛出去之后确实会拦住启动：`FeishuChannelAutoConfiguration#feishuChannel` 上是
  `@Bean(initMethod = "start", destroyMethod = "stop")`，`start()` 抛 = bean 创建失败 = 上下文失败。
- **这条行为今天没有任何用例守着。** `FeishuChannelWiringTest:31-32` 明确写了不写"凭据齐全 → 真连上"：
  那会去连飞书，让回归依赖网络。于是"配了连不上就起不来"只存在于人工验证里——
  也就是说，改成两类之后，也没有现成的用例会替我们挡住回退。

## 要做（三件，缺一件这条就退化成"看起来在重连、其实测不到"）

### 1. 一个分类函数，两处共用

落在 `feishu.sdk` 包里，形如 `FeishuConnectionFailures.classify(Throwable)` → `PERMANENT` / `RECOVERABLE`，
返回时带上"为什么这么判"的一句话（要进日志，也要进启动失败的报错）。

判据：

- **永久**：凭据错、应用未发布/未开通长连接权限、`app_id` 形状不对——这些**换一个时间点重试还是错**，
  重试只是在等一个不会变的事实。
- **可恢复**：连接期 `IOException` / `SocketTimeoutException`、`awaitReady` 超时、
  建连后收到的断开事件——这些是"等一个会变的事实"。

**具体的异常形状开工时必须现场钉住**，不许照这张票猜：读 `com.lark.oapi.core.exception.*` 与
`com.lark.oapi.ws.Client` 的实现，把"哪个 `code`/哪个异常类属于哪一类"写进这个方法上方的注释，
并各留一条用例。如果 SDK 只给一个笼统的 `ClientException`、区分不了，
**那就按"默认可恢复 + 明确认得出的凭据错才永久"来写**，并把这条不确定写进 Comments——
默认可恢复的代价是"凭据错会先重试几次再报"，比"抖动一次就整站下线"小得多。

### 2. 把建连抽成一个可注入的窄端口（否则这条永远只能手验）

现在 `start()` 里直接 `new Client.Builder(...)`，默认构建里既没有凭据也没有网络——
**没有任何接缝可以把"第一次抛 `IOException`、第二次成功"这件事喂进去**。

抽两个动作，不要更多：

```java
interface FeishuConnection extends AutoCloseable {
    void start() throws Exception;          // 对应 Client#start
    void awaitReady(long timeoutMillis) throws Exception;   // 对应 Client#awaitReady
}

interface FeishuConnectionFactory {
    FeishuConnection open();                // 生产实现包住 new Client.Builder(...).build()
}
```

`FeishuLongConnectionChannel` 改成依赖 `FeishuConnectionFactory`；生产实现放在 `sdk` 包内，
装配处注入它。**卡片回调（`onP2CardActionTrigger`）仍留在 `start()` 里注册**——它建的是
`EventDispatcher`，与连接生命周期无关，不要顺手搬进端口。

### 3. 重连状态机

- `start()` 里对 `PERMANENT`：仍然 `closeQuietly` + 抛（行为不变，只是报错句要分类：
  "凭据错，重启不会好" vs "连不上，会在 30 秒后重试"）。
- 对 `RECOVERABLE`：**不抛**。状态置 `DISCONNECTED`（初值 `CONNECTING`），
  起一个 `ScheduledExecutorService` 做退避重试：**30 秒起、每次翻倍、上限 10 分钟**
  （OpenClaw 的数字，`docs/tools/mcp.md:61` 用的是同一组，两处一致便于对日志）。
  - 每次失败：`WARN` 一行，带 `attempt`、`原因`、`下次重试时间`、`appId`（脱敏）。
  - 重连成功：换掉连接、状态回 `CONNECTED`、`INFO` 一行带"第 N 次重试后连上"。
- `stop()`：取消定时任务、`shutdown` 那个 scheduler、状态置 `STOPPED`，再关连接。
  **顺序不能反**：先关连接再取消任务，中间那一秒重连会重新建一条已经没人管的连接。
  scheduler 必须是守护线程或能被 `stop()` 关掉——关不掉的线程会让 JVM 不退出，
  那是比"通道没连上"更坏的故障（部署时表现为"停不下来，最后被 SIGKILL"）。
- 状态要能读：`FeishuLongConnectionChannel#state()` 返回那个枚举。**今天没有健康端点**
  （全仓没有 `HealthIndicator`），所以状态的第一落点是日志与这个方法，别在票里假装有 `/health`。

## 验收判据

- [x] **可恢复失败不再让应用起不来**：`ApplicationContextRunner` + 假工厂（`start()` 抛
      `ServerUnreachableException`）→ `hasNotFailed()`，bean 在，`state()` 是 `DISCONNECTED`。
      - `FeishuChannelWiringTest#recoverableConnectFailureDoesNotFailStartup`。
      反例：把分类改成"一律永久" → 这条红（且另外三条重连用例一起红，见 Comments）。
- [x] **退避真的递增**：注入记录型调度器（`ScheduledThreadPoolExecutor` 的子类，只记 delay 并同步跑），
      三次的延迟是 **30s / 60s / 120s**。
      - `#backoffDoublesFromThirtySeconds`。反例：`backoff` 写成固定 30s → 这条与下一条都红。
- [x] **重连成功之后状态回 `CONNECTED`**，且之后不再有新的重试任务。
      - `#reconnectSucceedsAndStopsRetrying`。反例同上（固定退避时"期望两个任务、实际三个"）。
- [x] **`stop()` 之后不再有任务**：`isShutdown()` 为真、每条开出来的连接都 `closed`、
      `stop()` 可重复调用不抛。
      - `#stopCancelsScheduledReconnectAndClosesTheConnection`。反例：`stop()` 里漏掉
      `shutdownNow()` → 这条红。
- [x] **永久错误仍然启动失败**：假工厂抛 `ClientException(10014, "app not found")` →
      `hasFailed()`，报错句含"重启不会好"与"是否已发布"。
      - `#permanentCredentialFailureStillFailsStartup`。
- [x] 现有四条退让不变（缺凭据 / 半配 / SDK 不在 classpath / `enabled=false`），
      `FeishuChannelWiringTest` 原来那几条照旧绿（本文件 15 条全绿）。
- [x] `ChatChannel#start()` 的 javadoc 同步成"永久失败才抛，可恢复失败由实现自己重试"。
- [x] `docs/agents/guardrails.md` 里"SDK 层不写单测"那条改了：翻译与发送仍不测，**建连这一层进了默认构建**。
- [x] ADR-0009 加了修订记录（正文未改）：那条"连不上就起不来"收窄成"只有永久错才起不来"。
- [x] `./scripts/verify.sh` 全绿：**666 条用例、失败 0、错误 0、跳过 0**；
      core 90.4% / **channel 77.7%**（自 75.4% 升）/ capability 85.4% / agent 73.2% / app 80.8%；
      模块文件数 10、单文件方法数 20。

## 门禁余量

`claw4j-channel` 已是 10 个测试文件（上限）。`FeishuChannelWiringTest` 现 10 条 `@Test`（上限 20），
本票的 5 条左右塞进它——它测的就是"装配与连接"，主题不冲突。
**不要新开 `FeishuReconnectTest`**：开一个就要合并掉另一个现存文件，而这条改动不值得动别的测试。

## 不要做

- **不要给飞书加公网回调端点**（ADR-0009 已经否过一次，理由不变）。
- **不要把这个状态机推广到 `HttpChatChannel`**：它没有"连接"这件事，`start()` 只是注册端点。
- **不要在重连里重放已经 ACK 的消息**——那是 `04` 号票的范围，两者混在一起会让"丢消息"这个问题
  看起来被这张票解决了。
- **不要把状态接到 actuator 上顺手做个 `/health`**：那是新的观测面，要有自己的判据，不在本票。

## Comments

- 2026-11-08 实现。新增五个类（`feishu.sdk` 下 `FeishuConnection` / `FeishuConnectionFactory` /
  `LarkFeishuConnectionFactory` / `FeishuConnectionFailures` / `FeishuConnectionState`），
  改两个（`FeishuLongConnectionChannel`、`FeishuChannelAutoConfiguration`），文档三处
  （`ChatChannel#start` javadoc、`guardrails.md` 一行、ADR-0009 修订记录）。
- **SDK 的异常形状是按 2.8.7 的字节码现场钉的**，不是按名字猜的（`javap -p -c com.lark.oapi.ws.Client`）：
  1. `awaitReady` 超时会抛 `ServerUnreachableException`（"websocket handshake did not complete
     within %dms"），`close()` 也用这个类收掉还在等的 future（"websocket client closed"）；
  2. 取连接地址那步：HTTP 非 200 或无响应体 → `ServerException`；业务码 `1`（system busy）
     与 `1000040343` → 也是 `ServerException`；
  3. **其它任何非 0 业务码 → `ClientException(code, msg)`**；本地凭据缺失也用它
     （`appSecret` 与 `clientAssertionProvider` 都空 → `7104`，client assertion 空 → `7101`）；
  4. `autoReconnect` 默认 `true`（构造器里 `if null → true`），但它只在**已连上之后断开**时有用；
     首连失败只 `reconnect()` 一次就停。所以重连必须我们自己来。
- **一处不确定，写在这里不藏**：`ClientException` 被整体判成永久。它的两个来源（服务端拒绝握手、
  本地凭据缺失）都是"换个时间点还是错"，但如果哪天服务端把**限流**也做成非 0 业务码，
  它会落进同一个类，于是一次限流会让应用起不来。当前取舍是"宁可错杀在启动"，
  并把 `toString()`（形如 `ClientException: 10014: app not found`）整段写进日志让运维看见码。
  SDK 没有 `getCode()`，要按码细分只能解析这个字符串——那时再改，不预先写一个会漂的解析器。
- **与票面的一处偏离**：端口方法签成了 `open(EventDispatcher events)`，不是 `open()`。
  `EventDispatcher` 是纯对象（不建连、不联网），而回调属于通道——让工厂自己造一个等于把
  "收到消息怎么办"搬进 SDK 包里，那正是这条通道唯一需要被默认构建测到的部分。
- **三组反例真跑了**（改完即还原，只有对应用例红）：
  1. `backoff` 固定 30s + `stop()` 漏掉 `shutdownNow()` → 3 红（退避、成功后即停、stop 取消），
     其余 12 条绿；
  2. `ServerUnreachableException` 判成 PERMANENT → 4 红（含"可恢复不失败"那条），
     其余 11 条绿；
  3. 上面两次改动的组合都只影响重连那几条，装配与认人那 10 条一直绿。
- 没做：健康端点（票面明确排除，那是新的观测面）、真实网络环境下的拔网线手验
  （本机没有真凭据，只能靠日志与状态字段，票面 Demo 里那条要等带凭据的部署验一次）。
