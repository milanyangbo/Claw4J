# 04: HTTP+SSE 通道端到端打通

**What to build:** 一个人用 curl 或浏览器问一句话，能拿到 Agent 的回答；流式路径同样能逐块收到。这是 M0 唯一能保证演示的通道，不依赖任何外部账号。

**Blocked by:** 01, 03

**Status:** resolved

- [x] `claw4j-channel` 的 `channel.spi` 包定义 `ChatChannel` SPI：入站消息怎么进、结果怎么回。**这是本项目的主测试接缝**（已在 spec 里确认），形状定型后换通道实现时测试不用改 —— 已在 ADR-0010 落盘，与 `domain.port.AgentPort` 配套
- [x] `channel.http` 包实现同步问答接口与 SSE 流式接口
- [x] 通道负责解析出用户标识并交给上层，Agent 侧不自己去认人（ADR-0007 的边界）
- [x] 端到端可用：发一句话能拿到回答，流式路径能拿到逐块输出
- [x] 测试一律从通道接缝进、从通道接缝出（入站消息进、出站消息出），不测编排内部细节
- [x] 两条路径都有集成测试，覆盖流式不被 Advisor 静默跳过这个坑

**注意**：`AgentPort.stream()` 是阻塞的（domain 不依赖 Reactor，见 ADR-0010）。HTTP+SSE 通道不要把请求线程堵在上面——收请求后立刻返回 `SseEmitter`，把 stream 交给业务线程池，逐块推。

## 落地形状

`io.github.claw4j.channel.http` 四个类，各管一件事：

| 类 | 职责 |
| --- | --- |
| `HttpChatController` | 只管传输：读请求头、认人、把消息交下去。不碰 `AgentPort` |
| `HttpPrincipalResolver` | 只管认人：请求头 → `Principal`，缺用户头或未登记角色即拒绝 |
| `HttpChatChannel` | 只管"入站怎么交给 Agent"：同步直调，流式提交到业务线程池后**立刻返回 `CompletableFuture`** |
| `HttpChannelAutoConfiguration` | 装配，注册进 `AutoConfiguration.imports`；`HttpChatChannel` 用 `initMethod/destroyMethod` 触发 SPI 的 `start()/stop()` |

请求头：`X-Claw4j-User`（必填）、`X-Claw4j-Role`（缺省 `read-only`）、`X-Claw4j-Session`（缺省回落到用户标识）。
角色→授权表在 `claw4j.channel.http.roles` 里，改权限不用发版。

流式为什么必须是"立刻返回 emitter + 异步推"：`AgentPort.stream()` 是阻塞的，一次 Text-to-SQL 十几秒；HTTP 上会吃满 Tomcat 请求线程，飞书上会触发 3 秒重推。形状先在 HTTP 跑通，M2 换长连接只是把 emitter 换成主动推送接口。

## 亲手验证（本机已跑通）

```bash
mvn -B package -DskipTests
java -jar claw4j-app/target/claw4j-app-0.1.0-SNAPSHOT.jar --server.port=18080 --claw4j.model.mock=true

# 同步
curl -s -X POST http://localhost:18080/api/chat \
  -H 'Content-Type: application/json' -H 'X-Claw4j-User: ethan' -H 'X-Claw4j-Role: admin' \
  -d '{"text":"上周华东区成交额多少"}'
# => {"sessionKey":"http:ethan","text":"[mock] 上周华东区成交额多少"}

# 流式
curl -N -X POST http://localhost:18080/api/chat/stream \
  -H 'Content-Type: application/json' -H 'X-Claw4j-User: ethan' \
  -d '{"text":"上周华东区成交额多少"}'
# => event:chunk / data:[mock] 上 → ... → event:done

# 匿名请求
curl -s -w ' [%{http_code}]' -X POST http://localhost:18080/api/chat \
  -H 'Content-Type: application/json' -d '{"text":"你好"}'
# => {"error":"缺少 X-Claw4j-User 请求头"} [401]
```

## 测试落点（47 个全绿）

- `HttpPrincipalResolverTest`（5）：拒绝匿名、默认最小权限、按表授权、未登记角色拒绝、会话键回落
- `HttpChatChannelTest`（4）：**流式不占调用线程**（用 latch 证明 future 未结束）、Agent 异常经 future 传出而非被线程池吞、同步结果原样透传、结尾块带 `finalChunk`
- `HttpChatControllerTest`（5）：standalone MockMvc，断言解析出的 Principal 原样交到 Agent（ADR-0007 边界）、401、400
- `HttpChannelEndToEndTest`（4）：**真实端口 + 真实 HTTP 客户端**（SSE 是"连接先返回、内容后到"，MockMvc 测不出），覆盖一句话进一整段出、鉴权、逐块、**流式拼接与同步结果一致**
- `AdvisorNotSkippedOverHttpTest`（2）：挂进容器的 Advisor 在 SSE 与同步两条路径上都必须留痕
- `AdvisorBothPathsTest`（2，agent 模块）+ `AgentArchitectureTest` 新增两条 ArchUnit 规则：把"只实现 `CallAdvisor` 会被流式静默跳过"这件事钉死

## 过程中撞到的三个坑（已写进 ADR-0007）

1. `BaseAdvisorChain` 给流式路径取的是 `getStreamAdvisors()`，只认 `StreamAdvisor`。只写 `CallAdvisor` 的鉴权 Advisor 在 `.stream()` 上**既不报错也不生效**，客户端照常拿到回答。已用 ArchUnit + 两条测试三层拦住。
2. 匿名 `BaseAdvisor` 的 `getClass().getSimpleName()` 是空串，不覆写 `getName()` 会让观测上下文在流式路径抛 `advisorName cannot be null or empty`（同步路径不抛，所以只看同步容易漏）。
3. `Prompt.augmentUserMessage(String)` 是**替换**用户消息而不是追加——名字里的 augment 有误导性。要追加必须走 `Function` 版本。

配套改动：`MockChatModel.stream()` 改成切块返回（每 8 字符一块）。只返回一块的 mock 测不出"流式被降级成一次性响应"；`AgentAutoConfiguration` 现在把容器里所有 `Advisor` bean 按 order 挂到链上，否则测试里挂的 Advisor 进不了链路。
