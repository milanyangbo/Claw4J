## Context

See `proposal.md` for motivation. The current Orchestrator already exposes an MVC SSE proof path, validates Header-derived request/session context, adapts context windows, removes provider reasoning markers, stores bounded resume state, and uses a deterministic `ModelStreamClient` for repeatable tests. Gateway is already the external SSE entrypoint and forwards Orchestrator SSE bytes through OpenFeign.

The missing production-shaped boundary is real provider invocation. Today simulated DeepSeek/Qwen behavior proves the stream contract, but the system cannot yet demonstrate actual Spring AI / Spring AI Alibaba model calls protected by per-model timeout, failure-rate circuit breaking, and half-open recovery.

## Goals / Non-Goals

**Goals:**

- Add a real-model path for DeepSeek primary and Qwen fallback while keeping deterministic mode as the default local/CI proof path.
- Guard primary DeepSeek calls with Resilience4j timeout and circuit-breaker policy.
- Reuse the existing streaming fallback resume, context adaptation, output parser, and session buffer instead of creating a second stream pipeline.
- Keep credentials outside source code and expose only non-secret model/resilience status in logs and smoke-test responses.
- Provide repeatable tests for deterministic mode and optional curl-based verification for real providers when API keys are supplied.

**Non-Goals:**

- Do not move model fallback decisions into Gateway.
- Do not replace Sentinel ingress and Agent-call proof guards; Sentinel remains responsible for flow control and current traffic-governance demos.
- Do not introduce Redis-backed distributed stream state, JWT/session redesign, WebFlux migration, billing controls, or full Agent planning.
- Do not make real provider credentials mandatory for compile or automated unit tests.

## Decisions

### 1. Keep Orchestrator as the model-governance owner

Gateway remains provider-agnostic:

```text
--------+       OpenFeign/SSE       +---------------+
| Client | --> claw4j-api-gateway --> claw4j-orchestrator
+--------+                           +-------+-------+
                                             |
                                             v
                                  StreamingModelService
                                             |
                                             v
                               ResilientModelStreamClient
                                  |                    |
                                  v                    v
                               DeepSeek              Qwen
```

Rationale: Gateway already fronts streaming access and preserves SSE bytes. Moving model fallback into Gateway would duplicate Orchestrator context adaptation and parser logic, and it would make Gateway aware of provider-specific decisions.

Alternative considered: Gateway-level model routing. Rejected because it breaks the current separation where Gateway owns access and Orchestrator owns model governance.

### 2. Add a `ModelStreamClient` implementation for real providers

Keep the existing `ModelStreamClient` boundary and add a real implementation selected by configuration:

- `deterministic`: existing local client, no API keys, stable test output.
- `real`: provider-backed client using Spring AI `DeepSeekChatModel` for the primary model and Spring AI Alibaba `DashScopeChatModel` for Qwen fallback. The legacy `QWQ` model type remains a Qwen-family parser alias only, while real fallback defaults to `QWEN`.

Rationale: the current interface is already the boundary used by `StreamingModelService`; replacing it would create churn across a recently stabilized stream contract.

Alternative considered: inject Spring AI clients directly into `StreamingModelService`. Rejected because it would mix provider transport with stream orchestration and make deterministic tests harder to keep.

### 3. Apply Resilience4j only to primary provider calls

The Resilience4j TimeLimiter and CircuitBreaker protect DeepSeek primary calls. Fallback Qwen calls remain outside the DeepSeek circuit metrics so fallback success/failure does not distort primary health.

Primary outcomes map into existing stream statuses:

- Success: emit DeepSeek tokens and complete normally.
- Timeout before first token: emit fallback-start with timeout status and call Qwen using the original request.
- Failure after partial output: emit fallback-start with interruption status and call Qwen with a resume prompt.
- Circuit open: skip DeepSeek and call Qwen directly with a circuit-open fallback status.

Rationale: this matches the user-visible behavior already specified for streaming resume while adding provider-health memory across requests.

Alternative considered: use Sentinel circuit breaking for the provider call. Rejected for this change because Sentinel already covers traffic and Agent-call proof paths; Resilience4j gives a clearer per-provider timeout/circuit abstraction for model SDK calls.

### 4. Use configuration-driven provider and resilience settings

Suggested configuration shape:

```yaml
spring:
  ai:
    model:
      chat: ${CLAW4J_SPRING_AI_MODEL_CHAT:none}
    deepseek:
      api-key: ${CLAW4J_DEEPSEEK_API_KEY:}
      chat:
        options:
          model: ${CLAW4J_DEEPSEEK_MODEL_NAME:deepseek-reasoner}
    dashscope:
      api-key: ${CLAW4J_DASHSCOPE_API_KEY:}
      chat:
        options:
          model: ${CLAW4J_QWEN_MODEL_NAME:qwen-plus}
          stream: true
          incremental-output: true

claw4j:
  model:
    client:
      mode: ${CLAW4J_MODEL_CLIENT_MODE:deterministic}
    resilience:
      primary-timeout: ${CLAW4J_MODEL_PRIMARY_TIMEOUT:30s}
      failure-rate-threshold: ${CLAW4J_MODEL_FAILURE_RATE_THRESHOLD:50}
      sliding-window-size: ${CLAW4J_MODEL_SLIDING_WINDOW_SIZE:10}
      minimum-number-of-calls: ${CLAW4J_MODEL_MIN_CALLS:4}
      wait-duration-in-open-state: ${CLAW4J_MODEL_OPEN_WAIT:30s}
      permitted-calls-in-half-open-state: ${CLAW4J_MODEL_HALF_OPEN_CALLS:1}
```

Rationale: Spring AI remains the source of truth for provider-specific API keys, base URLs, model names, and chat options. Claw4J configuration only controls whether the Orchestrator uses deterministic or real model clients plus provider-agnostic resilience policy.

Alternative considered: duplicate provider settings under `claw4j.model.client.deepseek/qwen`. Rejected because it creates two configuration surfaces for the same provider credentials and model names.

### 5. Keep Spring MVC SSE for this change

The Orchestrator currently uses `SseEmitter` and `spring-boot-starter-web`. Real provider streaming should adapt provider events into the existing token consumer, preserving the current MVC SSE surface.

Rationale: the project already discussed SSE vs WebFlux and intentionally kept the current proof path on MVC. Adding WebFlux now would widen the change beyond the model-failover goal.

Alternative considered: migrate to WebFlux `Flux<ServerSentEvent<?>>`. Deferred until a future change explicitly targets reactive end-to-end streaming.

## Risks / Trade-offs

- [Provider starter compatibility] Spring Boot `4.0.0`, Spring AI, and Spring AI Alibaba milestone versions may not all align cleanly -> Mitigation: make dependency resolution and Orchestrator compile the first implementation task; if DashScope starter is not managed by the current BOM, add an explicit project property or use the compatible Spring AI provider adapter selected during implementation.
- [Real streaming cancellation] A timed-out primary stream may continue running at provider/client level if the SDK does not cancel promptly -> Mitigation: keep timeout budgets conservative, close/cancel provider subscriptions when available, and verify no additional primary tokens are forwarded after fallback starts.
- [Circuit state surprises] Aggressive thresholds can make demos look flaky -> Mitigation: expose small local smoke-test thresholds separately from safer defaults and document rollback.
- [Credential leakage] API keys can leak through logs or exception messages -> Mitigation: validate secrets as presence-only, sanitize provider exceptions, and never include credential values in status DTOs or SSE events.
- [Duplicate fallback output] Real providers may paraphrase cached output despite resume instructions -> Mitigation: retain parser and duplicate-prefix trimming, and keep resume prompt sections explicit as data.

## Migration Plan

1. Add dependencies and configuration defaults while keeping `deterministic` as the default mode.
2. Bind provider API keys, model names, and chat options through official Spring AI / Spring AI Alibaba properties.
3. Add real provider client wiring behind `ModelStreamClient`.
4. Wrap primary model calls with Resilience4j timeout/circuit policy and map outcomes into existing stream fallback statuses.
5. Extend tests for deterministic failover and add optional real-provider smoke documentation.
6. Roll back by setting `CLAW4J_MODEL_CLIENT_MODE=deterministic` or removing provider credentials; public Gateway API shape remains unchanged.

## Open Questions

- Confirm the exact compatible Spring AI / Spring AI Alibaba artifact versions during implementation because the project currently uses Spring Boot `4.0.0` and Spring AI Alibaba `2.0.0-M1.1`.
- Decide during implementation whether Qwen fallback should use `qwen-plus` for cost/speed or `qwen-max` for quality in local smoke tests; the official Spring AI Alibaba property default can start conservative and remain configurable.
