## Why

Claw4J is moving from basic service-governance proof paths toward model-governance proof paths. Streaming model calls need explicit recovery, context-window adaptation, and output normalization so a deep-thinking primary model can fail or switch without duplicating content, leaking reasoning traces, or bypassing the project's defensive budgets.

## What Changes

- Add a Gateway-facing SSE proof path that calls Orchestrator through OpenFeign while Orchestrator owns model-stream governance and fallback continuation.
- Add bounded streaming state with session ownership, event sequence, resume buffer limits, and explicit distinction between upstream model interruption and client SSE reconnection.
- Standardize `requestId`, `tenantId`, `userId`, `idempotencyKey`, `sessionId`, and SSE resume position as HTTP headers/request context instead of business request parameters.
- Add context-window adaptation for primary and fallback models with configurable max-token budgets and `summary`, `truncate`, or `reject` strategies.
- Add a unified model-output parser that normalizes heterogeneous model output into a stable Claw4J response shape and removes provider-specific reasoning markers from user-visible content.
- Add configuration for model fallback timeout, resume enablement, buffer sizing, context limits, and parser behavior without hardcoding thresholds.
- Add contract tests and smoke documentation that prove partial-output resume, fallback continuation, context adaptation, output parsing, and defensive failure modes.

## Capabilities

### New Capabilities

- `streaming-model-resume-adaptation`: Covers SSE streaming interruption recovery, model context-window adaptation, and heterogeneous model-output normalization for model-routing proof paths.

### Modified Capabilities

- `sentinel-resilience-guards`: Clarify that model-stream fallback and circuit-breaker degradation must preserve invalid-context rejection and stable degradation outcomes.

## Impact

- Affected modules: `claw4j-api-gateway` exposes the external SSE entrypoint, `claw4j-orchestrator` owns streaming governance, and `claw4j-common` carries shared headers, errors, and the streaming request DTO.
- Affected APIs: new Gateway endpoint for client access plus an internal Orchestrator endpoint for governed streaming model resume behavior, using validated Header/request context for request, tenant, user, idempotency, session, and `Last-Event-ID` resume position while keeping the business payload focused on model input and proof controls.
- Affected configuration: model fallback timeout, resume enablement, resume buffer size, primary/fallback context limits, truncation strategy, and model output parser settings.
- Affected documentation: README link and independent smoke-test document for streaming resume, context adaptation, parser normalization, rollback, and cleanup.
- Non-goals: production vendor integration, real DeepSeek/QwQ account wiring, persistent distributed session storage, WebSocket support, full Agent runtime, and multi-node stream replay are not required for this first proof path.
