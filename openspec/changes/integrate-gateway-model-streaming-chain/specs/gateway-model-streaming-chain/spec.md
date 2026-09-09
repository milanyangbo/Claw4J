## Purpose

This capability defines the production-facing Gateway-to-Orchestrator model streaming chain so a browser request can reach the API Gateway, traverse governed service-to-service calls, and receive streamed model output from the Orchestrator.

## ADDED Requirements

### Requirement: Browser can start a Gateway model stream
The Gateway SHALL expose a browser-friendly model chat entry that accepts a user query and returns model output as an SSE stream.

#### Scenario: Browser address starts streaming response
- **WHEN** a browser requests the Gateway model chat entry with a nonblank query
- **THEN** the Gateway starts an SSE response that streams model events back through the browser-visible HTTP response

#### Scenario: Blank browser query is rejected
- **WHEN** a browser requests the Gateway model chat entry without a nonblank query
- **THEN** the Gateway rejects the request with a stable invalid-request outcome before calling Orchestrator

#### Scenario: Browser entry creates bounded local context
- **WHEN** the browser-friendly entry is used without caller-supplied service context Headers
- **THEN** the Gateway creates bounded local request, tenant, user, idempotency, and session context values before invoking Orchestrator

### Requirement: Gateway governs model stream ingress
The Gateway SHALL apply ingress governance before forwarding model stream requests to Orchestrator.

#### Scenario: Gateway admits request under quota
- **WHEN** the model streaming request is within the configured global and tenant ingress limits
- **THEN** the Gateway forwards the request to Orchestrator through the internal HTTP client boundary

#### Scenario: Gateway blocks request above quota
- **WHEN** the model streaming request exceeds the configured global or tenant ingress limits
- **THEN** the Gateway returns a stable rate-limit outcome without invoking Orchestrator

#### Scenario: Gateway preserves downstream stream bytes
- **WHEN** Orchestrator emits token, fallback-start, adaptation, failure, or completion events
- **THEN** the Gateway forwards those SSE bytes without parsing provider-specific model output or owning model fallback policy

### Requirement: Orchestrator owns model stream governance
The Orchestrator SHALL own model context adaptation, primary provider invocation, fallback provider invocation, stream resume, and output normalization for Gateway-originated model streams.

#### Scenario: Primary model succeeds
- **WHEN** DeepSeek returns model output within the configured budgets
- **THEN** the Orchestrator streams normalized user-visible output to Gateway without invoking Qwen fallback

#### Scenario: Primary model fails over
- **WHEN** DeepSeek times out, fails, or is short-circuited by the primary model circuit
- **THEN** the Orchestrator uses Qwen fallback according to the configured resume and context rules

#### Scenario: Orchestrator failure is stable
- **WHEN** neither primary nor fallback model output can produce a valid stream result
- **THEN** the Orchestrator emits or returns a stable Claw4J failure outcome without exposing provider secrets, raw transport details, or internal reasoning content

### Requirement: End-to-end stream behavior is smoke-testable
Claw4J SHALL document an end-to-end local smoke test that proves the browser/Gateway model stream path works through Gateway, Orchestrator, provider configuration, fallback governance, and SSE output.

#### Scenario: Smoke test proves browser path
- **WHEN** the documented local smoke test starts required infrastructure and requests the browser-friendly Gateway model chat entry
- **THEN** the response contains streamed model events from the Gateway URL rather than a direct Orchestrator URL

#### Scenario: Smoke test proves fallback path
- **WHEN** the documented local smoke test configures or triggers primary-provider failure behavior in a controlled way
- **THEN** the response shows Qwen fallback behavior through the same Gateway-facing stream contract

#### Scenario: Smoke test avoids proof-only APIs
- **WHEN** the documented local smoke test verifies the model streaming chain
- **THEN** it does not depend on standalone Sentinel, dynamic-config, deterministic model, or simulated failure proof endpoints
