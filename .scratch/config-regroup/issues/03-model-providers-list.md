# 03: 模型供应商改成一张表（`providers` 是列表，每项自带 `name`）

**What to build:** 供应商从"以名字为键的嵌套节点"改成"一张表"。

| 现在 | 改成 |
| --- | --- |
| `claw4j.model.deepseek.{base-url,api-key,model,...}` | `claw4j.model.providers[0].{name,base-url,api-key,model,...}` |
| `claw4j.model.qwen.{...}` | `claw4j.model.providers[1].{...}` |

```yaml
claw4j:
  model:
    primary: deepseek
    providers:
      - name: deepseek
        base-url: ${DEEPSEEK_BASE_URL:https://api.deepseek.com}
        api-key: secret:deepseek_api_key
        model: ${DEEPSEEK_MODEL:deepseek-chat}
      - name: qwen
        base-url: ${QWEN_BASE_URL:https://dashscope.aliyuncs.com/compatible-mode/v1}
        api-key: secret:qwen_api_key
        model: ${QWEN_MODEL:qwen-plus}
```

**为什么**：每一家要配的是同一套字段，表里一行就是一家；加一家只是多一行，不必在 yaml 里再造一层
缩进。`Provider` 因此从"抽象基类 + 两个子类"变成一个普通类，字段由绑定层填，`name` 就是键。

**要认的代价**：名字从"键"变成"字段"，写空、写重只有校验能发现。所以

- 绑定后 `@PostConstruct validateShape()` 查空白与重名，重名直接启动失败（谁生效取决于绑定顺序，
  这种不确定性不该存在于配置里）；
- `RoutingModelConfiguration` 查"表里每一条都有适配器"，没有就点名——否则那一行会静默不生效，
  "我加了一家供应商"与"它在不在链上"从此对不上。

**扩展性到哪为止**：加一行能改端点、模型名、两个超时、温度；**新增一家供应商仍要一个 ChatModel bean**
（适配器是 Java 类：DeepSeek 官方 API 一种、OpenAI 兼容端点一种）。少了那个 bean 启动时报错，
不会静默忽略。没有做成"写段 yaml 就接上新厂商"，因为那需要反射或 SPI，收益不抵它的隐式程度。

**判定条件跟着改**：`RealChatModelCondition` 不再按 `claw4j.model.<名字>.api-key` 这种把名字写进
路径的方式读属性（加一家就要改一次条件），改为把 `claw4j.model.providers` 绑一遍再按 `name` 挑。
判定表不变（本家有真值就装；一家都没有则 match，交给装配方法 fail fast）。

**验证**：`ModelAndToolSelectionTest#providerTableBindsByName`（name 写在 api-key 之后也能绑、
`provider` 给空壳、`require` 抛、重名起不来）；`ChatPipelineTest`、`SemanticCacheTest`、`EvalRunTest`
按新形状取配置；`./scripts/verify.sh` 全绿。

**Status:** resolved

**进度（2026-11-19）**：`ModelProperties` 重写（`providers` 列表 + `Provider` 普通类 +
`provider()` / `require()` 两种取法 + `validateShape()`），`ModelConfiguration` 的两个 bean 方法改为
`require(名字)`，`RealChatModelCondition` 改为 Binder 绑表按名字挑，`DeepSeekModelCondition` /
`QwenModelCondition` 只留名字，`RoutingModelConfiguration` 加 `requireAdapter`，
`EmbeddingAutoConfiguration` 用 `require`、`RagAutoConfiguration` 用 `provider`（重排不是启动前提，
沿用"取不到 key 不等于启动失败"）。`application.yml`、`docs/design/knowledge-retrieval.md`、
`CONTRIBUTING.md`、`SecretValue` 的 javadoc 示例跟着改。
