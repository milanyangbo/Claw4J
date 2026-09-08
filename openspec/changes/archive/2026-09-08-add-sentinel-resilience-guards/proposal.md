## Why

Claw4J already has service discovery, OpenFeign fallbacks, Nacos Config, and declarative rate-limit metadata, but it does not yet enforce runtime traffic protection. Adding Sentinel gives the runnable services a demonstrable guardrail for request spikes, tenant isolation, downstream error storms, and controlled degradation.

## What Changes

- Add Sentinel runtime protection to the service modules that own the first proof paths.
- Protect a Gateway ingress proof endpoint with global QPS limiting and tenant-scoped quotas.
- Protect an Orchestrator Agent-call proof path with error-ratio circuit breaking and fallback degradation.
- Configure Sentinel Dashboard connectivity and Nacos-backed rule DataIds with local defaults suitable for smoke testing.
- Return standardized Claw4J error responses for rate-limited and degraded outcomes.
- Document curl-based smoke tests for global limiting, tenant limiting, circuit breaking, fallback behavior, and rule refresh.
- Keep distributed quota accounting, Redis-backed counters, authentication, and production governance workflows out of scope.

## Capabilities

### New Capabilities
- `sentinel-resilience-guards`: Defines how Claw4J applies Sentinel-based flow control, tenant quotas, circuit breaking, and fallback outcomes for protected runtime boundaries.

### Modified Capabilities
- None.

## Impact

- Affected dependencies: Sentinel starter for protected runnable services, using the existing Spring Cloud Alibaba dependency management.
- Affected configuration: Sentinel transport, dashboard address, Nacos rule datasource DataIds, group, namespace, and conservative local defaults.
- Affected code: Gateway and Orchestrator module-local `config`, `controller`, `service`, and `dto` areas for proof paths; `claw4j-common` only for shared constants or error-code contracts if required by standardized responses.
- Affected documentation: README link plus an independent Sentinel smoke-test document; deployment docs may link Sentinel Dashboard setup without absorbing service-call test details.
- Affected runtime systems: local Sentinel Dashboard and Nacos Config for dynamic rule smoke tests.
