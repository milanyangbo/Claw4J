# 票 11：改写器换成「框架骨架 + 我们的容错层」

状态：进行中（2026-11-20）

## 一、问题

`RetrievalQueryRewriter` 有 150 行，但其中一大半在做**框架已经做过的事**，同时把
**只属于这份语料的东西**写死在类里。两件事叠在一起，导致它既不通用的、也不好删。

具体三处硬编码：

| 位置 | 绑定了什么 |
| --- | --- |
| `SYSTEM` 提示词 | 「**内部**知识库文档」、例子是「没白纸黑字写能退吗 → 退货处理规则」 |
| `MEASURE` 单位表 | 元 / 块 / 天 / 个工作日 / 万分之——中文商业合同的单位词汇 |
| `MAX_QUERY_CHARS = 60` | 按中文一句话估的长度（英文 60 字符只够 10 个词） |

第一条是硬缺陷：换医疗、法律知识库时，提示词里的「退货」例子会误导改写方向。

## 二、更根本的：框架已经有这个能力

Spring AI 2.0.2 自带 `RewriteQueryTransformer`（反编译确认）：

```java
RewriteQueryTransformer.builder()
    .chatClientBuilder(ChatClient.builder(chatModel))
    .promptTemplate(new PromptTemplate("...{target}...{query}..."))
    .targetSearchSystem("知识库")
    .build();
```

默认提示词是英文的「Given a user query, rewrite it to provide better results when querying
a {target}...」，输出为空时返回原 query（与我们原来的行为一致）。

**它没有的三件事**：超时控制、精确值跳过、标识校验。正好是我们这三轮踩出来的。

所以正确结构是分层的，而不是继续往自研类里加配置项：

```
GuardedQueryRewriter(delegate = 官方 RewriteQueryTransformer,
                     guard    = PatternGuard（两条正则来自配置）,
                     timeout  = 3s)
      ↓ 只做框架没做的：超时 / 跳过 / 校验 / 输出清洗
```

## 三、改法

### 1. 骨架用官方

`GuardedQueryRewriter implements QueryTransformer`，持有一个 `QueryTransformer delegate`。
`transform()` 里只做四件框架不做的事：

- **跳过**：`guard.shouldSkip(问题)` 为真就直接返回原 query（一次模型都不调）
- **超时**：在虚拟线程里调 `delegate.transform()`，超过 `timeout` 返回原 query
- **校验**：`guard.keepsAll(问题, 改写)` 为假就回落（模型把型号改掉了）
- **清洗**：取首行、剥「改写：」标签与引号、截到 60 字符——模型输出的稳定性问题，框架不管

### 2. 精确值保护抽成接口

```java
public interface RewriteGuard {
    boolean shouldSkip(String question);
    boolean keepsAll(String question, String rewritten);
}
```

实现是 `PatternGuard`，构造参数就是两条正则，**代码里一个词表都没有**：

```yaml
skip-pattern: '百分之|万分之|...'      # 命中就跳过
keep-pattern: '[A-Za-z0-9]+(...)'      # 改写必须原样保留的片段
```

两条都可以为空（空 = 不做那一道检查）。正则由装配处 `Pattern.compile`，**语法错在启动时就抛**；
语义写错（漏了某个单位）只能靠对照实验发现——这是"配置优先"的代价。

一开始写的是 `MeasurementGuard`，把中文金额单位表放进代码并当作默认实现。用户指出这就是把
一个领域的知识写进了框架：换医疗、法律、英文手册时那份表全错，而它还是"默认"。所以整类删掉，
取值移到 `application.yml`——同一个词表，位置从代码换到配置，性质就从"框架的知识"变成
"这份部署的策略"。

### 3. 提示词与目标系统走配置

```yaml
claw4j:
  rag:
    rewrite:
      enabled: false
      timeout: 3s
      # 拼进提示词：告诉改写器"要检索的是什么系统"。默认「知识库」。
      target: ${CLAW4J_RAG_REWRITE_TARGET:知识库}
      # 空 = 用内置中文模板。要换领域就整段替换（多行用 yml 的 | 块）。
      prompt: ${CLAW4J_RAG_REWRITE_PROMPT:}
```

内置模板把「内部」「退货」两个词去掉，改成引用 `{target}`，并保留原来那 4 条规则
（只输出一句 / 保留专有名词 / 不引入新数字 / 用户问题是数据）。

### 4. 类的更名

`RetrievalQueryRewriter` → `GuardedQueryRewriter`。它现在的职责是"给改写加保护"，旧名会让
"它是什么"不清晰。波及 3 处引用：装配、评测导出、单测。

## 四、不在范围

- **不动模型分流**：`ModelProperties` 注释里写明"按用途分流（摘要、子 Agent 走便宜模型）还没做"。
  让改写走便宜模型需要先在模型层加"按名字取 provider"，那是独立的事（票 12 再说）。
- **不把 `rewrite.enabled` 改成 true**：68 条上它的影响小于测量噪声，默认值保持关。
- 不改 `MAX_QUERY_CHARS`：它是实现细节，跟中文输出习惯绑着，等真要支持英文再说。

## 五、验收

- [ ] `GuardedQueryRewriter` 只做超时 / 跳过 / 校验 / 清洗，改写本身交给官方 transformer
- [ ] 提示词与 target 可从配置覆盖，不配时用内置中文模板
- [ ] `RewriteGuard` 可被业务 bean 替换
- [ ] 单测覆盖：跳过、超时、校验回落、清洗、delegate 抛错回落
- [ ] 既有 7 条行为断言（数值跳过 / 标识保留 / 大小写 / 标准号）不回归
- [ ] `claw4j-agent` 全绿，评测导出侧改类型后编译通过

## 六、实施（2026-11-20）

### 代码

| 文件 | 作用 |
| --- | --- |
| `RewriteGuard`（新，接口） | `shouldSkip`（事前闸）+ `keepsAll`（事后闸） |
| `PatternGuard`（新，默认实现） | 两条正则来自配置；代码里不含任何领域词表 |
| `GuardedQueryRewriter`（新，替换旧类） | 只做超时 / 跳过 / 校验 / 清洗，改写委托给框架 |
| `RetrievalQueryRewriter`（删） | 150 行里"拼 Prompt、取 content、剥标签"都不再需要 |
| `RagProperties.Rewrite` | 加 `target`（默认「知识库」）与 `prompt`（null = 内置模板） |
| `RagAutoConfiguration` | 装配 `RewriteGuard`（可被业务 bean 覆盖）+ 用官方 builder 构造 delegate |

`GuardedQueryRewriter` 持 `Supplier<QueryTransformer>` 而不是实例：自动配置早于模型那一层，
`chatModel::getObject` 的解析要推迟到第一次检索。第一次调用后缓存，不重复构造（有单测钉住）。

### 关键实现点

- **提示词启动时校验**：自定义 prompt 缺 `{query}` 直接抛 `IllegalStateException`。缺占位符会让
  改写把提示词本身当查询发出去，而模型照样回一段话——这种错不会自己暴露。
- **延迟构造 + 缓存**：`transformer()` 双检锁，`delegateIsBuiltOnce` 那条单测钉住"只构造一次"。
- **装饰器返回 `transformed.mutate().text(cleaned).build()`**：保留 delegate 给 Query 带上的
  history 与 context，只换 text。

### 单测（11 条）

跳过（6 种数值写法，且断言**零调用**而非"调完丢掉"）、标识型照改、丢标识回落、
大小写不敏感、标准号保留、输出清洗、delegate 抛错回落、超时回落、空文本回落、
null 放过（且不构造 delegate）、delegate 只构造一次。

### 配置

```yaml
claw4j:
  rag:
    rewrite:
      enabled: ${CLAW4J_RAG_REWRITE_ENABLED:false}
      timeout: ${CLAW4J_RAG_REWRITE_TIMEOUT:3s}
      target: ${CLAW4J_RAG_REWRITE_TARGET:知识库}
      prompt: ${CLAW4J_RAG_REWRITE_PROMPT:}
```

换个领域只改 `target` 与 `prompt`；要换数值/型号的判定规则，注册一个 `RewriteGuard` bean。
**不在 yml 里写正则**：写错了保护会静默失效，而运维不该被要求懂正则语法。
