# Streaming Model Resume Smoke Tests

This guide verifies the Gateway -> Orchestrator streaming model proof path: MVC SSE streaming, primary interruption fallback, bounded reconnect replay, context adaptation, parser redaction, rollback, and cleanup.

Start Nacos, Gateway, and Orchestrator by following [Local Deployment](DEPLOYMENT.md). The proof path does not call real DeepSeek, QwQ, DashScope, Redis, WebFlux, or WebSocket infrastructure.

## Defaults

The local proof path uses these defaults:

- Gateway endpoint: `http://127.0.0.1:8080/api/model/stream`
- Internal Orchestrator endpoint: `/internal/orchestrator/model/stream` is called by Gateway through OpenFeign and is not the smoke-test entrypoint.
- Primary model type: `DEEPSEEK`
- Fallback model type: `QWQ`
- Resume enabled: `true`
- TTFB timeout: `5s`
- Resume buffer size: `10000`
- Session retention: `10m`
- Context strategy: `summary`

All protected calls require these Headers:

```bash
STREAM_HEADERS=(
  -H 'Content-Type: application/json'
  -H 'Accept: text/event-stream'
  -H 'X-Request-Id: stream-smoke-001'
  -H 'X-Tenant-Id: smoke-tenant-a'
  -H 'X-User-Id: smoke-user'
  -H 'X-Idempotency-Key: stream-smoke-001'
  -H 'X-Session-Id: stream-session-001'
)
```

## Normal Stream

```bash
curl -N -sS "${STREAM_HEADERS[@]}" \
  -X POST 'http://127.0.0.1:8080/api/model/stream' \
  -d '{"query":"Explain the Claw4J streaming proof path."}'
```

Expected evidence:

- `event: token` includes visible answer content.
- `event: complete` is emitted.
- No `<think>` or provider reasoning markers appear in the stream.

## Primary Mid-Stream Fallback

Use a new request/session pair:

```bash
curl -N -sS \
  -H 'Content-Type: application/json' \
  -H 'Accept: text/event-stream' \
  -H 'X-Request-Id: stream-fallback-001' \
  -H 'X-Tenant-Id: smoke-tenant-a' \
  -H 'X-User-Id: smoke-user' \
  -H 'X-Idempotency-Key: stream-fallback-001' \
  -H 'X-Session-Id: stream-session-fallback-001' \
  -X POST 'http://127.0.0.1:8080/api/model/stream' \
  -d '{"query":"Continue safely after interruption.","simulatePrimaryFailure":true}'
```

Expected evidence:

- A primary `event: token` is emitted before interruption.
- `event: fallback-start` is emitted.
- A fallback `event: token` continues without repeating the primary prefix.
- `event: complete` is emitted.

## Primary TTFB Timeout Fallback

```bash
curl -N -sS \
  -H 'Content-Type: application/json' \
  -H 'Accept: text/event-stream' \
  -H 'X-Request-Id: stream-ttfb-001' \
  -H 'X-Tenant-Id: smoke-tenant-a' \
  -H 'X-User-Id: smoke-user' \
  -H 'X-Idempotency-Key: stream-ttfb-001' \
  -H 'X-Session-Id: stream-session-ttfb-001' \
  -X POST 'http://127.0.0.1:8080/api/model/stream' \
  -d '{"query":"Recover when the primary model never starts.","simulatePrimaryTtfbTimeout":true}'
```

Expected evidence: `event: fallback-start` has a primary TTFB timeout status, followed by fallback token output and completion.

## Client Reconnect

client disconnect recovery requires a new SSE request. Reconnect with the same ownership Headers and standard `Last-Event-ID`.

First create a session with buffered events:

```bash
curl -N -sS \
  -H 'Content-Type: application/json' \
  -H 'Accept: text/event-stream' \
  -H 'X-Request-Id: stream-reconnect-001' \
  -H 'X-Tenant-Id: smoke-tenant-a' \
  -H 'X-User-Id: smoke-user' \
  -H 'X-Idempotency-Key: stream-reconnect-001' \
  -H 'X-Session-Id: stream-session-reconnect-001' \
  -X POST 'http://127.0.0.1:8080/api/model/stream' \
  -d '{"query":"Generate events for reconnect.","simulatePrimaryFailure":true}'
```

Then replay from the next event after id `1`:

```bash
curl -N -sS \
  -H 'Content-Type: application/json' \
  -H 'Accept: text/event-stream' \
  -H 'X-Request-Id: stream-reconnect-002' \
  -H 'X-Tenant-Id: smoke-tenant-a' \
  -H 'X-User-Id: smoke-user' \
  -H 'X-Idempotency-Key: stream-reconnect-002' \
  -H 'X-Session-Id: stream-session-reconnect-001' \
  -H 'Last-Event-ID: 1' \
  -X POST 'http://127.0.0.1:8080/api/model/stream' \
  -d '{"query":"Reconnect request payload remains model-focused."}'
```

Expected evidence: replay starts after event id `1` and does not duplicate earlier content.

## Ownership Rejection

Reconnect with a different tenant:

```bash
curl -N -sS \
  -H 'Content-Type: application/json' \
  -H 'Accept: text/event-stream' \
  -H 'X-Request-Id: stream-reconnect-cross-tenant' \
  -H 'X-Tenant-Id: smoke-tenant-b' \
  -H 'X-User-Id: smoke-user' \
  -H 'X-Idempotency-Key: stream-reconnect-cross-tenant' \
  -H 'X-Session-Id: stream-session-reconnect-001' \
  -H 'Last-Event-ID: 1' \
  -X POST 'http://127.0.0.1:8080/api/model/stream' \
  -d '{"query":"Cross-tenant replay must not read cached content."}'
```

Expected evidence: the stream returns a stable `CLAW4J-STREAM-410` failure event and does not return tenant A content.

## Context Reject

Restart Orchestrator with a tiny context budget and reject strategy:

```bash
CLAW4J_MODEL_STREAMING_PRIMARY_MAX_TOKENS=1 \
CLAW4J_MODEL_STREAMING_TRUNCATION_STRATEGY=reject \
mvn -pl claw4j-orchestrator spring-boot:run
```

Then call the stream endpoint:

```bash
curl -N -sS \
  -H 'Content-Type: application/json' \
  -H 'Accept: text/event-stream' \
  -H 'X-Request-Id: stream-context-reject-001' \
  -H 'X-Tenant-Id: smoke-tenant-a' \
  -H 'X-User-Id: smoke-user' \
  -H 'X-Idempotency-Key: stream-context-reject-001' \
  -H 'X-Session-Id: stream-session-context-reject-001' \
  -X POST 'http://127.0.0.1:8080/api/model/stream' \
  -d '{"query":"This request is intentionally too long for a one-token budget."}'
```

Expected evidence: the stream returns a stable `CLAW4J-MODEL-413` failure event before model invocation.

## Parser Normalization

```bash
curl -N -sS \
  -H 'Content-Type: application/json' \
  -H 'Accept: text/event-stream' \
  -H 'X-Request-Id: stream-parser-001' \
  -H 'X-Tenant-Id: smoke-tenant-a' \
  -H 'X-User-Id: smoke-user' \
  -H 'X-Idempotency-Key: stream-parser-001' \
  -H 'X-Session-Id: stream-session-parser-001' \
  -X POST 'http://127.0.0.1:8080/api/model/stream' \
  -d '{"query":"Show parser failure.","simulateMalformedOutput":true}'
```

Expected evidence: internal reasoning markers are not emitted, and the stream returns a stable `CLAW4J-MODEL-422` failure event.

## Resume Disabled

Restart Orchestrator with resume disabled:

```bash
CLAW4J_MODEL_STREAMING_RESUME_ENABLED=false mvn -pl claw4j-orchestrator spring-boot:run
```

Then simulate primary failure:

```bash
curl -N -sS \
  -H 'Content-Type: application/json' \
  -H 'Accept: text/event-stream' \
  -H 'X-Request-Id: stream-resume-disabled-001' \
  -H 'X-Tenant-Id: smoke-tenant-a' \
  -H 'X-User-Id: smoke-user' \
  -H 'X-Idempotency-Key: stream-resume-disabled-001' \
  -H 'X-Session-Id: stream-session-resume-disabled-001' \
  -X POST 'http://127.0.0.1:8080/api/model/stream' \
  -d '{"query":"Resume disabled should fail closed.","simulatePrimaryFailure":true}'
```

Expected evidence: no fallback token is emitted, and a stable `RESUME_DISABLED` failure event ends the stream.

## Cleanup

Stop the Gateway and Orchestrator processes, then restart Orchestrator without temporary environment overrides. Because the proof path uses in-memory session state only, no Redis keys, provider data, or persistent session rows require cleanup.
