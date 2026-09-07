## Why

Claw4J services can now register with Nacos, but cross-module HTTP calls still lack a governed client contract for service-name routing, load balancing, timeout budgets, and degradation. Adding OpenFeign now turns discovery into a usable, defensive RPC path without introducing direct Maven dependencies between service modules.

## What Changes

- Add OpenFeign-based service clients for Gateway to call Orchestrator and Orchestrator to call A2A Broker by registered service name.
- Add explicit fallback classes for every Feign client so unavailable downstream services degrade predictably instead of failing as raw transport errors.
- Configure Feign connect/read timeouts and retry behavior through environment-overridable application properties.
- Ensure service-name calls use Spring Cloud LoadBalancer with Nacos-discovered instances and preserve namespace/group isolation.
- Update the project package rules to allow a module-local `client` package for HTTP client boundaries, then place Feign client interfaces under that package.
- Add focused contract tests and smoke-verification guidance for normal calls, fallback activation, load-balanced routing, timeout behavior, and dependency boundaries.

## Capabilities

### New Capabilities

- `openfeign-service-calls`: Defines governed OpenFeign HTTP calls between Claw4J service modules, including service-name routing, load balancing, fallback behavior, timeout budgets, and package-boundary expectations.

### Modified Capabilities

- None.

## Impact

- Affected code: `agents.md`, Gateway and Orchestrator POMs, Gateway and Orchestrator startup/configuration files, module-local Feign client and fallback classes, minimal provider endpoints in Orchestrator and A2A Broker, service-layer adapters where needed, and focused tests.
- Affected dependencies: `org.springframework.cloud:spring-cloud-starter-openfeign`, load-balancer support if not already guaranteed by the selected starter graph, and a circuit-breaker/fallback dependency compatible with Spring Cloud Alibaba Sentinel or Spring Cloud CircuitBreaker.
- Affected APIs: a small internal Orchestrator endpoint callable by Gateway and a small internal A2A Broker endpoint callable by Orchestrator, both returning safe shared response envelopes.
- Affected systems: Nacos service discovery remains the registry source of truth for resolving `claw4j-orchestrator` and `claw4j-a2a-broker` service names.
