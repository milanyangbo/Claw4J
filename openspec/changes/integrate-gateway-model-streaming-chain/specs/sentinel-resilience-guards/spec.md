## ADDED Requirements

### Requirement: Sentinel runtime is Gateway-only
Sentinel SHALL be used only by `claw4j-api-gateway` for external ingress protection and Dashboard-visible runtime resources in this change.

#### Scenario: Gateway keeps Sentinel ingress protection
- **WHEN** the integrated model streaming path is started through `claw4j-api-gateway`
- **THEN** Gateway enters a stable Sentinel resource before forwarding browser-friendly or formal model-stream requests

#### Scenario: Orchestrator has no Sentinel runtime dependency
- **WHEN** `claw4j-orchestrator` production dependencies, configuration, and source imports are inspected
- **THEN** they do not include Sentinel runtime starters, Sentinel datasource configuration, Sentinel annotations, Sentinel APIs, or Sentinel proof endpoints

#### Scenario: Provider failure is not handled by Sentinel
- **WHEN** DeepSeek times out, fails, or is circuit-open during Orchestrator model streaming
- **THEN** fallback is governed by Resilience4j and the model-failover service path rather than by Sentinel rules

### Requirement: Gateway Sentinel blocks use explicit rate-limit responses
Sentinel flow-control blocks at Gateway ingress SHALL be converted into predictable Claw4J responses or business exceptions without returning `null` or exposing raw Sentinel transport details.

#### Scenario: Flow-control block is mapped
- **WHEN** Sentinel blocks a protected Gateway model streaming request due to flow control
- **THEN** the caller receives a stable Claw4J rate-limit outcome

#### Scenario: Fallback does not hide invalid context
- **WHEN** required Header-derived request context is missing or blank on protected internal calls
- **THEN** the request is rejected as invalid instead of being converted to a rate-limit or fallback success

#### Scenario: Model-stream fallback preserves invalid-context rejection
- **WHEN** a model-stream fallback or resume path is requested with missing or mismatched Header-derived request, tenant, user, idempotency, or session context
- **THEN** the request is rejected as invalid before fallback output or cached stream content is emitted

### Requirement: Gateway Sentinel smoke behavior is observable
Claw4J SHALL document and expose enough Gateway model-stream behavior for operators to verify Sentinel Dashboard visibility during local smoke tests.

#### Scenario: Dashboard can observe protected Gateway resources
- **WHEN** Gateway is started with a reachable Sentinel Dashboard
- **THEN** the protected Gateway model streaming resource becomes visible to the dashboard after traffic reaches it

#### Scenario: Curl smoke test proves Sentinel resource visibility
- **WHEN** the documented smoke test sends model streaming traffic through Gateway
- **THEN** Sentinel Dashboard can show the Gateway application and model-stream resource after traffic reaches the endpoint

#### Scenario: Optional Dashboard rule proves rate limiting
- **WHEN** an operator adds a temporary flow rule for the documented Gateway model-stream resource through Sentinel Dashboard
- **THEN** over-limit requests return the stable Claw4J rate-limit outcome through the integrated Gateway stream path

## MODIFIED Requirements

### Requirement: Sentinel configuration uses official defaults
Gateway Sentinel runtime configuration SHALL use official `spring.cloud.sentinel.*` properties and SHALL avoid project-owned Gateway Sentinel threshold properties or Nacos datasource rule blocks in this change.

#### Scenario: Local defaults protect startup without rule sources
- **WHEN** Sentinel Dashboard or an external rule source is unavailable during local startup
- **THEN** Gateway starts with the documented Sentinel runtime defaults and no project-owned rule DataIds

#### Scenario: Project-owned rule datasource configuration is absent
- **WHEN** Gateway `application.yml` is inspected
- **THEN** it does not contain `spring.cloud.sentinel.datasource`, `gateway-flow-rules`, `gateway-param-flow-rules`, or `claw4j.sentinel.gateway`

#### Scenario: Standard Sentinel rule management remains possible
- **WHEN** an operator needs local rate-limit proof behavior
- **THEN** the operator can add a temporary rule to the Dashboard-visible Gateway resource without adding Claw4J-specific configuration aliases

## REMOVED Requirements

### Requirement: Agent calls open a circuit on high error ratio
**Reason**: Orchestrator model-provider failure governance belongs to Resilience4j, while Sentinel is scoped to Gateway ingress protection and Dashboard-visible runtime resources.
**Migration**: Use the `model-failover-resilience` capability for Orchestrator primary-model timeout, circuit-open, fallback, and half-open recovery behavior.

### Requirement: Sentinel blocks use explicit degradation responses
**Reason**: The previous Sentinel block contract mixed Gateway ingress flow control with Orchestrator circuit behavior; this change makes Sentinel Gateway-only.
**Migration**: Use `Gateway Sentinel blocks use explicit rate-limit responses` for Gateway flow-control behavior and `model-failover-resilience` for Orchestrator model circuit behavior.

### Requirement: Sentinel smoke behavior is observable
**Reason**: The previous smoke-test contract covered standalone Sentinel proof endpoints; the new contract verifies Sentinel through the integrated Gateway model stream.
**Migration**: Use `Gateway Sentinel smoke behavior is observable` for Gateway-only Sentinel smoke tests.
