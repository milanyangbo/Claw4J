# 03: ChatClient 装配与 mock 模型跑通全链路

**What to build:** 在没有模型 key 的情况下，Agent 模块也能装配出一个可用的 `ChatClient` 并跑通一次问答；拿到 key 后只改配置就能切到真实供应商。领域层的 Principal 同期落地，且不依赖任何框架。

**Blocked by:** 01

**Status:** resolved

- [x] `claw4j-domain` 里定义 Principal 与会话标识，模块内不出现 Spring / Spring AI 的 import
- [x] `claw4j-agent` 装配 `ChatClient`：`defaultSystem` + Advisor 链骨架
- [x] 提供 mock `ChatModel` 实现，无 key 时自动生效，跑通一次 `prompt().call().content()`
- [x] DeepSeek 与通义（OpenAI 兼容端点）的 starter 与配置项就位，凭据走环境变量
- [x] 单测全部 mock 模型调用，不烧 token、不依赖网络

## Answer

9 个测试通过（domain 6 + agent 3），`mvn verify` 全绿，应用无 key 启动成功。

**一个判断要推翻我原来的设计**：我原本打算把 DeepSeek starter 直接放进默认依赖，让"拿到 key 只改配置"就生效。实测发现 **DeepSeek 的自动配置在缺 key 时直接抛 `IllegalArgumentException: DeepSeek API key must be set`**，应用根本起不来 —— 这跟"没有 key 也要能跑通"是死冲突。

改成：真实供应商依赖放进 Maven profile `real-model`，默认构建只带 mock。key 到位后 `mvn -Preal-model` 并把 `CLAW4J_MODEL_MOCK` 置为 false。多一步，但换来确定性：默认构建永远能起。

其余实现要点：

- **mock 模型做回声而不是固定串**。返回 `[mock] <用户原话>`，端到端测试才能断言"这句话确实走完了全链路"，而不是只看有没有返回。
- **装配注册为自动配置**（`AutoConfiguration.imports`）而不是 `@Configuration`，这样别的模块引入依赖即生效，不必把 `io.github.claw4j.agent` 加进组件扫描路径。
- **同步与流式两条路径都写了测试**。后面每个自定义 Advisor 都要重复这个双路径验证 —— 只实现同步接口的 Advisor 在流式路径上会被静默跳过（ADR-0007），这个坑必须先有测试兜住。
- Principal 做了防御性拷贝并测试：外部修改传入的 Set 不影响内部状态，越权读取这条路堵死。
