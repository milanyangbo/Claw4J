# 02: AskUserQuestion 走 HTTP 与 IM 通道

**What to build:** 待定。这一票先记事实与两个形态的代价，形态由用户裁决后再动手。

**Blocked by:** 形态裁决。

**Type:** 新增交互能力（涉及暂停语义与 HTTP 接口面）

**Status:** open

**裁决来源：** 2026-11-16 用户问「AskUserQuestionTool，可以改写成支持 http 和 im 吗？」——
这是本票要回答的问题，不是结论。

## 现场

库里那个类**不是拿来就能装的**：

- `AskUserQuestionTool.builder().build()` 在 `questionHandler` 为 null 时直接
  `IllegalArgumentException`（`AskUserQuestionTool.java:323`）。
- 包自带的 `CommandLineQuestionHandler` 读 `System.in`（`CommandLineQuestionHandler.java:42`）：
  Maven / 容器里 stdin 可能是 EOF（返回空 map → 答案校验异常），`docker -it` 或 IDE 里则**永久阻塞**
  那个工具线程。
- `QuestionHandler` 的契约是**同步**的：`Map<String,String> handle(List<Question>)`，调用线程
  一直等到有答案为止。而这个调用线程正是「这一轮」的模型调用线程。

## 两个形态，代价完全不同

### A. 真挂起（与 Claude Code 行为一致）

handler 落一条待答记录，然后等。HTTP 侧要新增 `POST /api/answers/{id}`（或复用下一条消息）；
IM 侧要在入站流水线最前面加一段「这段对话有没有待答的问题」——有就把这条消息当答案喂回去，
而不是开新的一轮。多实例必须靠库/Redis 汇合，进程内 `Future` 在答案落到别的实例时永远醒不过来。

- 拿到的：模型真的带着答案继续，一轮说完。
- 代价：一条待答记录 + 一个被占住的模型调用线程（5 分钟 × N 并发 = 线程池见底）；
  超时、断线、用户答非所问都要定义；**与 ADR-0027 相反**——那里明确写了「唤醒的是会话，不是那一次运行」。

### B. 审批同款（不挂起）

handler 把问题推到那段对话（IM 卡片 / HTTP 响应里带一个问题块），工具立刻返回
「问题已经发给你了」，这一轮以那句话收尾；用户下一条消息就是答案，模型在下一轮接着做。

- 拿到的：无挂起线程、无新表、多实例天然正确；与 `ConversationApprovalGate` 同一形状
  （`docs/adr/0027`、`ConversationApprovalGate` 类注释）。
- 代价：工具返回值不是「答案」，而模型要理解「这轮到此为止」；客户端要认识那个响应形状。

## 缺口

- `AskUserQuestionTool` 的 schema 是「问题 + 选项 + 多选」，与 IM 卡片能力要对齐（飞书卡片能放
  下拉与按钮，企业微信文本消息放不了，只能退化成编号选项）。真做起来这是第二块工作。
- 目前 `application.yml` 里**没有**任何 `AskUserQuestion` 相关配置——这一票不落地就一直是"不装"。
