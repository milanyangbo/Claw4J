# Claw4J Project Overview

## 项目定位

Claw4J 是基于 Spring AI Alibaba 与 Spring Cloud Alibaba 构建的 Java AI Agent 微服务集群，目标是在 Java/Spring 技术栈下逐步建设类似 OpenClaw 的 Agent 平台能力。项目采用 OpenSpec 规范驱动开发，通过一组可独立提案、实现、验证和归档的能力，把传统微服务治理经验迁移到 AI Agent 系统中。

项目的核心理念是：功能实现是基础，工程防御是灵魂。Agent 系统会放大模型调用、工具执行、跨服务通信、上下文拼接和多租户访问中的风险，因此每个能力都必须配套清晰的防御机制、可观测证据和可回滚路径。

## 技术路线

Claw4J 围绕以下技术栈建设：

- Spring Boot `4.0.0`：服务基础框架。
- Spring AI Alibaba `2.0.0-M1.1`：Agent、模型接入、工具调用、Graph、MCP、A2A 等 AI 能力的建设方向。
- Spring Cloud `2025.1.0` 与 Spring Cloud Alibaba `2025.1.0.0`：服务治理、配置管理、限流熔断和服务间调用底座。
- Nacos Discovery：服务注册发现和环境隔离。
- Nacos Config：动态配置、灰度开关和规则热更新。
- OpenFeign + Spring Cloud LoadBalancer：模块间 HTTP 调用和服务名路由。
- Sentinel：限流、熔断、降级和运行时流量防护。
- Micrometer / Actuator：服务健康、指标和后续可观测性建设。

Redis、向量存储、MCP Gateway、A2A 协作、RAG 长短期记忆、模型预算治理、SSE 流式恢复等能力按 OpenSpec change 逐步建设，不通过一次性大改完成。

## 模块职责

项目是 Maven 多模块工程，模块边界固定：

- `claw4j-common`：公共组件，包含工具类、异常、常量、注解和共享 DTO。
- `claw4j-api-gateway`：接入层，默认端口 `8080`，负责外部 API、入口参数校验、服务调用入口、流式响应入口和边界防护。
- `claw4j-orchestrator`：编排层，默认端口 `8081`，负责 Agent 编排、多模型路由、模型调用治理、上下文预算、降级恢复和下游服务协调。
- `claw4j-tool-executor`：工具层，默认端口 `8082`，负责工具注册、参数校验、幂等执行、MCP 工具聚合和工具安全边界。
- `claw4j-knowledge-memory`：知识层，默认端口 `8083`，负责 RAG 检索、记忆管理、上下文窗口预算和知识库隔离。
- `claw4j-a2a-broker`：协作层，默认端口 `8084`，负责 Agent 间通信、任务状态、协作路由和 A2A 协议能力。

所有模块只通过公开的 HTTP 契约、服务发现和共享 DTO 协作，禁止通过 Maven 依赖直接耦合兄弟服务模块。

## 架构原则

Claw4J 的架构演进遵循以下原则：

- 微服务治理优先：服务发现、负载均衡、超时、限流、熔断、降级、重试策略和可观测性必须随功能一起设计。
- Agent 防御优先：模型调用必须受步数、Token、超时和成本预算约束，工具调用必须受权限、幂等和输入校验约束。
- 租户隔离优先：所有涉及用户数据、记忆、工具结果和配置规则的能力都必须以 `tenantId` 和 `userId` 作为访问边界。
- 规范驱动优先：新功能先进入 OpenSpec proposal、spec、design、tasks，再进入实现、验证、归档和提交。
- 可演示优先：每个阶段都应提供可运行的 smoke test、curl 示例或自动化测试，证明能力真实生效。

## 通信与治理

模块间调用以 OpenFeign 和服务名路由为主，调用方必须通过 Nacos Discovery 获取健康实例，并通过 Spring Cloud LoadBalancer 选择目标实例。跨服务调用必须透传请求 ID、租户 ID、用户 ID 和幂等键，避免链路丢失、跨租户访问和重复执行。

Gateway 到 Orchestrator 的普通内部调用使用 OpenFeign。流式对话类接口需要单独设计 SSE 边界，区分服务端模型流中断和客户端连接中断：上游模型流中断可以在服务端接力生成并继续向同一 SSE 连接发送事件；客户端连接中断则必须依赖 `sessionId`、事件序号或 `Last-Event-ID` 建立新连接后恢复。

## 工程防御主线

Claw4J 把传统后端工程防御迁移到 Agent 系统中：

- 限流和熔断：使用 Sentinel 管理入口流量、租户配额、Agent 调用错误率和下游服务不稳定。
- 幂等和防重：所有工具执行、写操作和跨服务副作用调用必须带幂等键。
- 超时和预算：模型调用、工具调用、RAG 检索和服务间调用必须受剩余超时预算控制。
- 上下文治理：用户输入、RAG 结果、工具输出和历史上下文必须经过校验、裁剪、摘要或拒绝策略。
- 输出适配：异构模型输出必须统一解析、过滤内部思考痕迹，并转换为稳定的业务响应结构。
- 可观测和回滚：动态配置、规则变更、限流熔断、降级和恢复都必须可观测、可复现、可回滚。

## OpenSpec 工作方式

每个新增能力都应遵循同一条路径：

1. Explore：讨论需求、边界、风险和可演示路径。
2. Propose：创建 OpenSpec change，补齐 proposal、delta spec、design 和 tasks。
3. Apply：只修改 tasks 中列出的文件，优先小步实现和验证。
4. Verify：运行 OpenSpec 校验、模块编译、单测和必要 smoke test。
5. Archive：同步主规格并归档 change。
6. Commit：按 `[ChangeID] 任务描述` 提交并推送。

这套流程让项目逐步增长，同时保留架构决策、验收标准和工程防御证据。
