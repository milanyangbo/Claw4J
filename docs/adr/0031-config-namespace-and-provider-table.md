# 0031 配置分两类：链上的归 `claw4j.advisor`，供应商写成一张表

- Status: accepted
- Date: 2026-11-19
- 决策来源：用户 2026-11-19 当场裁决（"tool-search / session / memory 都放到 `claw4j.advisor.` 下面"、
  "cache 放到 advisor 下面"、"供应商改成一张表，更有扩展性"）。代码与本文同一次落地。
- 相关票：`.scratch/config-regroup/issues/01`、`02`、`03`

## 背景

配置原来是按组件名平铺的：`claw4j.tool-search.*`、`claw4j.memory.*`、`claw4j.session.*`、
`claw4j.cache.*`、`claw4j.model.deepseek.*`、`claw4j.model.qwen.*`。

四个平级前缀描述的是**同一条链上的四样东西**（会话记忆、长期记忆、工具循环、语义缓存），
翻配置时要记四个名字；模型那两段则是**同一套字段写两遍**，加一家就再嵌一层。

## 裁决

**一、链上的东西收到 `claw4j.advisor.` 下。**

| 原前缀 | 新前缀 |
| --- | --- |
| `claw4j.session.*`（含 `.maintenance` / `.reset`） | `claw4j.advisor.session.*` |
| `claw4j.memory.*` | `claw4j.advisor.memory.*` |
| `claw4j.tool-search.*` | `claw4j.advisor.tool-search.*` |
| `claw4j.cache.*` | `claw4j.advisor.cache.*` |

分类依据是**"它是给谁用的"，不是"它是什么"**：`session` 那组里的 `schema` / `maintenance` /
`reset` 实际上是存储与生命周期，不是 advisor 的一环；它们归到 `advisor` 名下，是因为消费者是
会话记忆那条链。这条选择记在这里，免得下一个人以为是笔误。

**二、模型供应商写成一张表。**

```yaml
claw4j:
  model:
    primary: deepseek
    providers:
      - name: deepseek
        base-url: …
        api-key: secret:deepseek_api_key
        model: …
```

`name` 从此是**字段**而不是键。扩展性到"加一行"为止：加端点、模型名、超时、温度不用改代码；
**新增一家供应商仍要一个 `ChatModel` bean**（适配器是 Java 类——DeepSeek 官方 API 一种、
OpenAI 兼容端点一种），没有做成"写段 yaml 就接上新厂商"——那需要反射或 SPI，隐式程度比它换来的
便利更贵。表里有一条 `name` 却没有对应 bean 时启动即失败，不静默忽略。

## 代价

- **名字不再处处诚实**：`claw4j.advisor.session.maintenance.*` 的清理不是 advisor 的一环，
  这是按"给谁用"分类的直接代价。判据写在上一节，读的人不会误以为清理挂 Advisor 上。
- **`name` 写空、写重只有校验能拦**：从"键"变成"字段"之后，绑定层不再天然去重。已在绑定期查
  （空白与重名直接启动失败）。
- **配置键与指标名从此不同名**：`claw4j.session.compaction.*` 这类**指标**名保持原样不动。
  指标是对外契约（面板、告警、历史数据），搬配置时顺手改它会打断既有查询。同名不同源是有意的。
- **一次性的部署侧迁移**：所有旧键在升级后不生效且不报错（Spring 绑定忽略未知键）。升级时按票面
  的对照表逐条改 yml；本地那份 `application-local.yml` 不在版本库里，要自己改。

## 退场条件

- 出现**第三种消费者**（既不是链上的 advisor，也不是存储/生命周期）时重新分组，本文的二分法作废。
- 某一组超过一道屏（约 40 行配置）且彼此无关时，按用途拆子节点，而不是继续往 `advisor` 下塞。
- 若真要支持"写配置即接新厂商"，那是另一次裁决：先写清 SPI 的形状与失败模式，再动这张表。
