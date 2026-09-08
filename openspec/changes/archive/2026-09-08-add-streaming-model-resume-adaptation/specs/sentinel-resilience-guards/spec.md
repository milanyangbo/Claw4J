## MODIFIED Requirements

### Requirement: Sentinel blocks use explicit degradation responses
Sentinel flow-control, circuit-breaker blocks, and model-stream degradation boundaries SHALL be converted into predictable Claw4J responses or business exceptions without returning `null` or exposing raw Sentinel transport details.

#### Scenario: Flow-control block is mapped
- **WHEN** Sentinel blocks a protected Gateway request due to flow control
- **THEN** the caller receives a stable Claw4J rate-limit outcome

#### Scenario: Circuit-breaker block is mapped
- **WHEN** Sentinel blocks a protected Agent call because the circuit is open
- **THEN** the caller receives a stable Claw4J degraded outcome

#### Scenario: Fallback does not hide invalid context
- **WHEN** required Header-derived request context is missing or blank
- **THEN** the request is rejected as invalid instead of being converted to a rate-limit or fallback success

#### Scenario: Model-stream fallback preserves invalid-context rejection
- **WHEN** a model-stream fallback or resume path is requested with missing or mismatched Header-derived request, tenant, user, idempotency, or session context
- **THEN** the request is rejected as invalid before fallback output or cached stream content is emitted
