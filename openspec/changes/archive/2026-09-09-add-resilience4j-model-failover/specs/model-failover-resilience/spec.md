## Purpose

This capability defines how Claw4J invokes real primary and fallback chat models through a governed Orchestrator boundary, using timeout control, circuit breaking, and fallback selection so model-provider instability does not leak into client-facing streaming behavior.

## ADDED Requirements

### Requirement: Orchestrator supports real primary and fallback model providers
The Orchestrator SHALL support a configurable real-model mode that invokes DeepSeek as the primary model and Qwen as the fallback model while preserving the existing deterministic mode for credential-free tests.

#### Scenario: Deterministic mode remains available
- **WHEN** model client mode is configured as deterministic
- **THEN** the Orchestrator uses local deterministic model output without requiring provider API keys

#### Scenario: Real model mode selects configured providers
- **WHEN** model client mode is configured as real and provider credentials are present
- **THEN** the Orchestrator invokes Spring AI DeepSeek for primary calls and Spring AI Alibaba DashScope/Qwen for fallback calls using the configured model names

#### Scenario: Real model mode requires credentials
- **WHEN** model client mode is configured as real and a required provider credential is missing or blank
- **THEN** the Orchestrator fails closed with a stable configuration error before issuing a model request

### Requirement: Primary model calls are time-bounded
Primary model calls SHALL be constrained by a configurable timeout budget before fallback selection is attempted.

#### Scenario: Primary model responds within timeout
- **WHEN** the primary DeepSeek model returns output within the configured timeout
- **THEN** the Orchestrator emits the primary model response without invoking the fallback model

#### Scenario: Primary model exceeds timeout
- **WHEN** the primary DeepSeek model does not return within the configured timeout
- **THEN** the Orchestrator stops waiting for the primary call and continues through the configured fallback model path

#### Scenario: Timeout result is observable
- **WHEN** fallback starts because the primary model timed out
- **THEN** the stream emits or records a stable timeout-driven fallback status without exposing provider transport internals

### Requirement: Primary model failures trigger fallback
Primary provider failures SHALL be mapped into stable fallback behavior instead of returning raw provider errors to clients.

#### Scenario: Primary provider returns an error
- **WHEN** the primary DeepSeek provider call fails with a provider error or 5xx-class failure
- **THEN** the Orchestrator invokes the fallback Qwen path using the validated request context

#### Scenario: Fallback result preserves stream contract
- **WHEN** fallback model output is emitted after a primary provider failure
- **THEN** the client receives the same event shape, session id, request id, and completion semantics as other streaming responses

#### Scenario: Invalid request context is not downgraded
- **WHEN** request, tenant, user, idempotency, or session context is missing or invalid
- **THEN** the request is rejected before primary or fallback model invocation

### Requirement: Primary circuit opens on high failure ratio
The Orchestrator SHALL open the primary model circuit when the observed primary failure ratio crosses the configured threshold after the configured minimum sample volume.

#### Scenario: Failure ratio crosses threshold
- **WHEN** primary DeepSeek calls exceed the configured failure-rate threshold after the minimum call count
- **THEN** subsequent primary calls are short-circuited without attempting the DeepSeek provider

#### Scenario: Open circuit uses fallback directly
- **WHEN** a streaming request arrives while the primary circuit is open
- **THEN** the Orchestrator invokes the fallback Qwen path and emits or records a stable circuit-open fallback status

#### Scenario: Circuit metrics are isolated to primary model
- **WHEN** fallback Qwen calls succeed or fail
- **THEN** those outcomes do not count as DeepSeek primary-call samples for opening or closing the primary circuit

### Requirement: Primary circuit recovers through half-open probes
The Orchestrator SHALL allow configured half-open probe calls after the open-state wait duration and automatically restore primary routing when probes succeed.

#### Scenario: Probe window opens after wait duration
- **WHEN** the primary circuit has been open for the configured wait duration
- **THEN** the Orchestrator allows no more than the configured number of trial DeepSeek calls

#### Scenario: Successful probe closes circuit
- **WHEN** a half-open DeepSeek probe succeeds
- **THEN** the primary circuit closes and later requests use DeepSeek as the primary model again

#### Scenario: Failed probe reopens circuit
- **WHEN** a half-open DeepSeek probe times out or fails
- **THEN** the circuit returns to open state and later requests use the fallback Qwen path until the next wait duration expires

### Requirement: Model resilience settings are configurable
Model-provider selection, timeout budgets, circuit thresholds, and fallback behavior SHALL be configurable without hardcoding secrets or environment-specific values.

#### Scenario: Local defaults are safe
- **WHEN** no external configuration is provided
- **THEN** the Orchestrator starts in deterministic mode with conservative local resilience defaults

#### Scenario: Real provider settings are externally supplied
- **WHEN** real mode is enabled
- **THEN** provider API keys and model names are read from official Spring AI / Spring AI Alibaba configuration, while timeout values and resilience thresholds are read from Claw4J resilience configuration or environment variables

#### Scenario: Secret values are not logged
- **WHEN** model-provider configuration is loaded or rejected
- **THEN** logs and responses do not expose provider API keys or credential material

### Requirement: Model failover behavior is smoke-testable
Claw4J SHALL document and expose enough behavior for operators to verify primary success, timeout fallback, circuit opening, half-open recovery, and deterministic rollback.

#### Scenario: Smoke test proves timeout fallback
- **WHEN** the documented smoke test configures a primary timeout condition
- **THEN** the response proves Qwen fallback was used after DeepSeek did not respond within budget

#### Scenario: Smoke test proves circuit opening
- **WHEN** the documented smoke test drives primary failures above the configured ratio
- **THEN** later requests show primary short-circuit behavior and fallback execution

#### Scenario: Smoke test proves primary recovery
- **WHEN** the documented smoke test waits for the half-open probe window and sends a successful primary probe
- **THEN** later requests prove primary DeepSeek routing has recovered
