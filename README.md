# Claw4J

Claw4J is a Java AI Agent microservice cluster based on Spring AI Alibaba and Spring Cloud Alibaba.

## Modules

- `claw4j-common`: shared utilities, exceptions, annotations, and DTO contracts.
- `claw4j-api-gateway`: API access layer, default port `8080`.
- `claw4j-orchestrator`: orchestration layer, default port `8081`.
- `claw4j-tool-executor`: tool execution layer, default port `8082`.
- `claw4j-knowledge-memory`: knowledge and memory layer, default port `8083`.
- `claw4j-a2a-broker`: agent collaboration layer, default port `8084`.

## Documentation

- [Local Deployment](DEPLOYMENT.md): Docker-based Nacos setup, service startup, and registration checks.
- [OpenFeign Smoke Tests](OPENFEIGN_SMOKE_TESTS.md): curl checks for service-name calls, load balancing, and fallback degradation.
- [Nacos Config Smoke Tests](NACOS_CONFIG_SMOKE_TESTS.md): curl checks for dynamic config loading, hot refresh, fallback, and rollback.
- [Sentinel Smoke Tests](SENTINEL_SMOKE_TESTS.md): curl checks for global limiting, tenant isolation, circuit breaking, fallback, and rollback.
- [Streaming Model Resume Smoke Tests](STREAMING_MODEL_RESUME_SMOKE_TESTS.md): curl checks for Gateway -> Orchestrator SSE fallback resume, reconnect, context adaptation, parser normalization, and cleanup.
- [Model Failover Smoke Tests](MODEL_FAILOVER_SMOKE_TESTS.md): curl and contract-test checks for DeepSeek primary, Qwen fallback, Resilience4j timeout, circuit opening, half-open recovery, and deterministic rollback.

## Quick Build

```bash
mvn -q test
```
