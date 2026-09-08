# Model Failover Smoke Tests

This guide verifies Gateway -> Orchestrator model failover: deterministic primary success, timeout fallback, provider-failure fallback, circuit-open short-circuiting, half-open recovery, rollback, and optional real DeepSeek/Qwen provider checks.

Start Nacos, Gateway, and Orchestrator by following [Local Deployment](DEPLOYMENT.md). The external smoke-test entrypoint is always Gateway:

- Gateway endpoint: `http://127.0.0.1:8080/api/model/stream`
- Internal Orchestrator endpoint: `/internal/orchestrator/model/stream` is called by Gateway through OpenFeign.
- Default local mode: `CLAW4J_MODEL_CLIENT_MODE=deterministic`
- Primary model: DeepSeek
- Fallback model: Qwen through DashScope
- Real provider client: Spring AI `DeepSeekChatModel` plus Spring AI Alibaba `DashScopeChatModel`
- Provider settings source: official Spring AI `spring.ai.deepseek.*` and `spring.ai.dashscope.*`

All streaming calls require these Headers:

```bash
MODEL_HEADERS=(
  -H 'Content-Type: application/json'
  -H 'Accept: text/event-stream'
  -H 'X-Request-Id: model-failover-001'
  -H 'X-Tenant-Id: smoke-tenant-a'
  -H 'X-User-Id: smoke-user'
  -H 'X-Idempotency-Key: model-failover-001'
  -H 'X-Session-Id: model-session-001'
)
```

## Deterministic Primary Success

```bash
curl -N -sS "${MODEL_HEADERS[@]}" \
  -X POST 'http://127.0.0.1:8080/api/model/stream' \
  -d '{"query":"Explain Claw4J model failover."}'
```

Expected evidence:

- `event: token` contains deterministic primary content.
- `event: fallback-start` is absent.
- `event: complete` is emitted.

## Deterministic Timeout Fallback

```bash
curl -N -sS \
  -H 'Content-Type: application/json' \
  -H 'Accept: text/event-stream' \
  -H 'X-Request-Id: model-timeout-001' \
  -H 'X-Tenant-Id: smoke-tenant-a' \
  -H 'X-User-Id: smoke-user' \
  -H 'X-Idempotency-Key: model-timeout-001' \
  -H 'X-Session-Id: model-session-timeout-001' \
  -X POST 'http://127.0.0.1:8080/api/model/stream' \
  -d '{"query":"Fallback when primary TTFB times out.","simulatePrimaryTtfbTimeout":true}'
```

Expected evidence:

- `event: fallback-start` has status `PRIMARY_TTFB_TIMEOUT`.
- Fallback token output is emitted after the fallback start event.
- `event: complete` is emitted.

## Deterministic Provider Failure Resume

```bash
curl -N -sS \
  -H 'Content-Type: application/json' \
  -H 'Accept: text/event-stream' \
  -H 'X-Request-Id: model-interrupt-001' \
  -H 'X-Tenant-Id: smoke-tenant-a' \
  -H 'X-User-Id: smoke-user' \
  -H 'X-Idempotency-Key: model-interrupt-001' \
  -H 'X-Session-Id: model-session-interrupt-001' \
  -X POST 'http://127.0.0.1:8080/api/model/stream' \
  -d '{"query":"Resume after partial primary output.","simulatePrimaryFailure":true}'
```

Expected evidence:

- A primary `event: token` appears before fallback.
- `event: fallback-start` has status `PRIMARY_INTERRUPTED`.
- Fallback output continues without duplicating the primary prefix.

## Circuit-Open Fallback

For deterministic contract tests, the circuit-open path is covered by `ModelFailoverResilienceContractTest`. For a manual local proof, restart Orchestrator in real mode with intentionally invalid provider behavior only in a controlled dev environment, or run:

```bash
mvn -pl claw4j-orchestrator -Dtest=ModelFailoverResilienceContractTest#errorRatioOpensPrimaryCircuitAndShortCircuitsToFallback test
```

Expected evidence:

- After failures cross `CLAW4J_MODEL_FAILURE_RATE_THRESHOLD`, the next call skips DeepSeek.
- `event: fallback-start` has status `PRIMARY_CIRCUIT_OPEN`.
- Qwen fallback output still uses the same session id and request id event shape.

## Half-Open Recovery

The deterministic recovery proof is also covered by the contract test:

```bash
mvn -pl claw4j-orchestrator -Dtest=ModelFailoverResilienceContractTest#halfOpenSuccessfulProbeRestoresPrimaryRouting test
```

Expected evidence:

- After `CLAW4J_MODEL_OPEN_WAIT`, one DeepSeek probe is permitted.
- A successful probe closes the circuit.
- Later requests route to primary DeepSeek again.

## Optional Real Provider Smoke

Real provider smoke tests are skipped unless both credentials are present. Do not commit credentials; supply them through environment variables:

```bash
export CLAW4J_MODEL_CLIENT_MODE=real
export CLAW4J_DEEPSEEK_API_KEY='<your-deepseek-api-key>'
export CLAW4J_DASHSCOPE_API_KEY='<your-dashscope-api-key>'
export CLAW4J_DEEPSEEK_MODEL_NAME='deepseek-reasoner'
export CLAW4J_QWEN_MODEL_NAME='qwen-plus'
export CLAW4J_MODEL_STREAMING_FALLBACK_MODEL_TYPE='qwen'
export CLAW4J_MODEL_PRIMARY_TIMEOUT=30s
```

These environment variables bind to official Spring AI configuration:

- `CLAW4J_DEEPSEEK_API_KEY` -> `spring.ai.deepseek.api-key`
- `CLAW4J_DEEPSEEK_MODEL_NAME` -> `spring.ai.deepseek.chat.options.model`
- `CLAW4J_DASHSCOPE_API_KEY` -> `spring.ai.dashscope.api-key`
- `CLAW4J_QWEN_MODEL_NAME` -> `spring.ai.dashscope.chat.options.model`

Restart Orchestrator after setting the variables, then call Gateway:

```bash
curl -N -sS \
  -H 'Content-Type: application/json' \
  -H 'Accept: text/event-stream' \
  -H 'X-Request-Id: model-real-001' \
  -H 'X-Tenant-Id: smoke-tenant-a' \
  -H 'X-User-Id: smoke-user' \
  -H 'X-Idempotency-Key: model-real-001' \
  -H 'X-Session-Id: model-session-real-001' \
  -X POST 'http://127.0.0.1:8080/api/model/stream' \
  -d '{"query":"Give a short explanation of Resilience4j model failover."}'
```

Expected evidence:

- Real DeepSeek content streams when the provider responds within budget.
- If DeepSeek times out or fails, `event: fallback-start` appears with a stable status and Qwen fallback continues.
- Provider API keys never appear in SSE events, logs, or committed files.

## Rollback

Return to local deterministic mode and restart Orchestrator:

```bash
unset CLAW4J_DEEPSEEK_API_KEY
unset CLAW4J_DASHSCOPE_API_KEY
export CLAW4J_MODEL_CLIENT_MODE=deterministic
```

Expected evidence: the same Gateway curl calls return deterministic proof output without requiring real provider credentials.
