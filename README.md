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

- [Local Deployment](DEPLOYMENT.md): Docker-based Nacos and Sentinel Dashboard startup, service startup, and registration checks.
- [Model Streaming Chain Smoke Tests](MODEL_STREAMING_CHAIN_SMOKE_TESTS.md): required end-to-end browser and curl checks for Gateway -> Orchestrator SSE, DeepSeek/Qwen provider calls, Sentinel ingress protection, and Resilience4j fallback.

## Quick Build

```bash
mvn -q test
```
