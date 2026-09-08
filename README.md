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

## Quick Build

```bash
mvn -q test
```
