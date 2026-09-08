# sentinel-resilience-guards Specification

## Purpose

This capability defines how Claw4J runtime boundaries apply Sentinel-based flow control, tenant-isolated quotas, circuit breaking, and fallback outcomes without bypassing existing defensive context validation.

## Requirements

### Requirement: Gateway ingress requests are globally rate-limited
The Gateway SHALL reject protected ingress requests that exceed the configured global QPS budget before protected business logic is executed.

#### Scenario: Request is allowed below the global limit
- **WHEN** protected Gateway ingress traffic remains within the configured global QPS budget
- **THEN** the request reaches the protected service logic and returns a successful Claw4J response

#### Scenario: Request is blocked above the global limit
- **WHEN** protected Gateway ingress traffic exceeds the configured global QPS budget
- **THEN** additional requests are rejected with a standardized Claw4J rate-limit response

### Requirement: Gateway tenant quotas are isolated
The Gateway SHALL apply tenant-scoped rate limits using the validated tenant identifier so one tenant exceeding quota does not consume another tenant's quota.

#### Scenario: One tenant exceeds quota
- **WHEN** requests for tenant A exceed the configured tenant QPS budget
- **THEN** tenant A receives a standardized Claw4J rate-limit response

#### Scenario: Another tenant remains available
- **WHEN** tenant A is rate-limited and tenant B remains within the configured tenant QPS budget
- **THEN** tenant B requests continue to reach the protected service logic

#### Scenario: Missing tenant context is rejected
- **WHEN** a protected Gateway request omits the required tenant identifier
- **THEN** the request is rejected as an invalid Claw4J request before a tenant quota is consumed

### Requirement: Agent calls open a circuit on high error ratio
Protected Agent-call boundaries SHALL open a circuit when the observed error ratio exceeds the configured threshold after the minimum request volume is reached.

#### Scenario: Error ratio crosses threshold
- **WHEN** a protected Agent-call boundary records an error ratio greater than 50 percent after the configured minimum request count
- **THEN** the circuit opens for that protected resource

#### Scenario: Open circuit short-circuits subsequent calls
- **WHEN** the circuit is open for a protected Agent-call resource
- **THEN** subsequent calls are blocked without executing the protected Agent-call logic

#### Scenario: Circuit can recover
- **WHEN** the circuit recovery window expires and a permitted probe call succeeds
- **THEN** the protected Agent-call resource can return to normal request handling

### Requirement: Sentinel blocks use explicit degradation responses
Sentinel flow-control and circuit-breaker blocks SHALL be converted into predictable Claw4J responses or business exceptions without returning `null` or exposing raw Sentinel transport details.

#### Scenario: Flow-control block is mapped
- **WHEN** Sentinel blocks a protected Gateway request due to flow control
- **THEN** the caller receives a stable Claw4J rate-limit outcome

#### Scenario: Circuit-breaker block is mapped
- **WHEN** Sentinel blocks a protected Agent call because the circuit is open
- **THEN** the caller receives a stable Claw4J degraded outcome

#### Scenario: Fallback does not hide invalid context
- **WHEN** required request context is missing or blank
- **THEN** the request is rejected as invalid instead of being converted to a rate-limit or fallback success

### Requirement: Sentinel rules are configurable and refreshable
Sentinel rule thresholds SHALL be configurable without source-code changes and SHALL support local defaults plus dynamic rule refresh through the project's configuration infrastructure.

#### Scenario: Local defaults protect startup
- **WHEN** the rule center or Sentinel Dashboard is unavailable during local startup
- **THEN** protected services start with documented local default rule values

#### Scenario: Dynamic rule update takes effect
- **WHEN** an operator updates a configured Sentinel rule source
- **THEN** subsequent protected requests use the updated rule without restarting the service

#### Scenario: Rule scope is environment isolated
- **WHEN** namespace, group, or DataId overrides are provided for Sentinel rules
- **THEN** the service reads Sentinel rules only from the configured environment scope

### Requirement: Sentinel smoke behavior is observable
Claw4J SHALL document and expose enough proof-path behavior for operators to verify global limiting, tenant limiting, circuit breaking, fallback, and rollback during local smoke tests.

#### Scenario: Dashboard can observe protected resources
- **WHEN** the protected service is started with a reachable Sentinel Dashboard
- **THEN** the protected resources become visible to the dashboard after traffic reaches them

#### Scenario: Curl smoke test proves rate limiting
- **WHEN** the documented smoke test sends traffic above the configured Gateway limit
- **THEN** the documented response proves that rate limiting is active

#### Scenario: Curl smoke test proves degradation
- **WHEN** the documented smoke test drives the protected Agent-call error ratio above threshold
- **THEN** the documented response proves that circuit breaking and fallback behavior are active
