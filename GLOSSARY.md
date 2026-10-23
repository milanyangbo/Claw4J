# Claw4J

一个运行在企业 IM 上的 Agent 平台：从聊天通道接收用户消息，由 Agent 编排工具与检索完成真实业务任务，再把结果发回通道。

## Agent 运行时

**Agent**:
一次具体的模型装配：系统提示词 + 一组工具 + 一条 Advisor 链。同一个进程里可以并存多个用途不同的 Agent。
_Avoid_: 机器人、Bot、助手、Assistant

**Orchestrator**:
把一个通道消息拆成"该不该用工具、用哪个工具、结果怎么回写"的执行编排。它不负责收发消息。
_Avoid_: Adapter（旧代码里的含义）、Manager、Handler

**Subagent**:
由 Orchestrator 委派、在独立上下文窗口中运行的专职 Agent，只把结论回传父 Agent。
_Avoid_: 子任务、Task

**Skill Generator**:
由 Agent 产出新 Script Package 的能力：生成、静态检查、隔离环境自测、审计留痕，通过后才能成为可被编排的 Script Package。它是本项目的核心能力，不是附属功能。
_Avoid_: 自动写技能、自进化、代码生成

**Skill**:
文件化的可复用"做法"，由一个 `SKILL.md` 描述何时用、按什么流程用，附带可选的脚本与参考资料。启动时只加载元数据，命中时才加载全文。Skill 不提供动作，它编排动作。
_Avoid_: 技能包（旧代码里指 Python 脚本包）、Prompt 模板

**Tool**:
一个 Agent 可调用的具体动作，有名字、参数 Schema 和返回值。Skill 描述流程，Tool 提供能力；一个 Skill 通常编排多个 Tool。
_Avoid_: Function（1.x 时期的叫法）、Callback、插件

**主模型 / 备模型**:
一次模型调用在多家供应商之间的主备关系：主模型先上，它失败才退到备模型。是"降级"，不是"分流"——
按用途分流（摘要、子 Agent 走便宜模型）是另一件事。流式调用的降级窗口只到第一个块吐出去之前，
吐过之后再换会把同一条回答发两遍。
_Avoid_: 模型切换（那是改配置的结果，不是运行时的行为）、负载均衡、多模型网关

## 通道与会话

**Channel**:
一条对外的消息通道，负责把外部消息转成内部请求、把内部结果发回外部。同一套编排逻辑可以同时挂多条 Channel。
_Avoid_: Proxy（旧代码里的含义）、Gateway、Bot

**Session**:
一次用户会话的完整事件流，是记忆、压缩和追踪的作用域。用户换个 Channel 进来，只要 Session 相同，上下文就连续。
_Avoid_: ChatContext、Conversation、chatId 窗口

**Turn**:
一个用户消息加上它引发的全部后续事件（工具调用、工具结果、助手回复），直到下一个用户消息为止。压缩以 Turn 为最小单位，不切断中间状态。
_Avoid_: 轮次对话、Message window

**Chat ID**:
通道侧对会话的标识，由具体 Channel 定义。它只是 Session 的寻址方式之一，不等于 Session。
_Avoid_: conversationId、userId

**Principal**:
发起请求的人，携带角色与可访问的资源集合。一次工具调用能不能发生，由它决定，而不是由模型决定。
_Avoid_: 用户、账号、User（除非特指通道侧账号）

**工具权限（Tool Permission）**:
调用一个工具所需的授权：一个动作名加一张表清单。由**提供工具的模块**登记（只有它知道自己会碰到哪张表），
Agent 侧的鉴权 Advisor 只按接口消费；没登记的工具一律按拒绝处理。粒度是表级与动作级，行级列级不做。
_Avoid_: 权限矩阵、ACL、角色继承树

## 能力单元

**Script Package**:
`skills/<name>/` 下的可执行脚本包，由 Skill 描述其用法，由具备执行能力的工具调用。脚本本体不进入上下文，只有输出进入。它换来的是"写个脚本就能扩展"的敏捷性，代价是执行环境的可控性。
_Avoid_: Skill、技能、插件

**Retrieval**:
为一次提问取回相关上下文的动作。只有走"向量检索 + 文档切分 + 重排"的完整管线时才称 RAG；仅关键词打分或全文塞入向量的，一律称 Retrieval。
_Avoid_: RAG（滥用时）、搜索、知识库查询
