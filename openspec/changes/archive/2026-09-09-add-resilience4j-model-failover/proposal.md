## Why

Claw4J already has Gateway-to-Orchestrator streaming, context adaptation, output normalization, Sentinel runtime guards, and deterministic model proof paths, but it still lacks a real primary/fallback model invocation boundary. This change connects DeepSeek and Qwen through Spring AI / Spring AI Alibaba and wraps the primary model with Resilience4j so timeout, provider failures, circuit-open short-circuiting, and half-open recovery become demonstrable model-governance behavior.

## What Changes

- Add a real model client mode in `claw4j-orchestrator` that can call DeepSeek as the primary model and Qwen as the fallback model through Spring AI / Spring AI Alibaba.
- Keep the existing deterministic model client mode for local contract tests and smoke paths that must run without provider credentials.
- Add Resilience4j time limiter and circuit breaker protection around the primary DeepSeek model call.
- Convert primary timeout, 5xx/provider failure, and open-circuit outcomes into stable model-stream fallback behavior that reuses the existing Qwen resume path.
- Use Spring AI official provider configuration for DeepSeek/Qwen API keys and model names, while Claw4J keeps only model client mode, timeout, circuit-breaker threshold, sliding window, open-state wait duration, and half-open probe count.
- Preserve the existing Gateway boundary: clients still enter through `claw4j-api-gateway`, and `claw4j-orchestrator` owns model selection, resilience policy, and fallback continuation.
- Add tests and smoke documentation for real-model configuration, deterministic fallback proof behavior, primary timeout failover, error-ratio circuit opening, half-open recovery, and credential-safe local execution.

## Capabilities

### New Capabilities

- `model-failover-resilience`: Defines real DeepSeek/Qwen model invocation, Resilience4j timeout and circuit-breaker protection, fallback selection, and half-open recovery for Orchestrator model calls.

### Modified Capabilities

- `streaming-model-resume-adaptation`: Clarify that the existing streaming resume path can be backed by real Spring AI / Spring AI Alibaba model clients in addition to deterministic proof clients.

## Impact

- Affected modules: `claw4j-orchestrator` for model clients, resilience configuration, guarded model invocation, and tests; `claw4j-common` only if stable shared error/status contracts need small additions.
- Affected dependencies: add Spring Cloud CircuitBreaker Resilience4j and Spring AI / Spring AI Alibaba model-client dependencies to the Orchestrator module only.
- Affected configuration: Spring AI official `spring.ai.deepseek.*` / `spring.ai.dashscope.*` provider settings, `claw4j.model.client.mode`, primary timeout, circuit-breaker thresholds, sliding windows, open-state wait duration, and half-open probe settings.
- Affected APIs: no public API shape change; Gateway remains the external SSE entrypoint and Orchestrator remains the internal governed model endpoint.
- Affected documentation: README link plus an independent Resilience4j/model-failover smoke-test guide covering deterministic proof tests and optional real-provider curl tests.
- Non-goals: authentication/JWT session design, Redis-backed distributed stream state, production billing controls, prompt orchestration beyond the current streaming request contract, and replacing Sentinel ingress/traffic protection.
