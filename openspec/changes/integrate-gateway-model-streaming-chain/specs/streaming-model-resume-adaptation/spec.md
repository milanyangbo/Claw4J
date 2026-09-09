## MODIFIED Requirements

### Requirement: Request and session metadata is header-propagated
The streaming endpoint SHALL receive cross-cutting request and session metadata from HTTP Headers or propagated request context instead of from business request parameters.

#### Scenario: Required stream context headers are validated
- **WHEN** an initial internal streaming request arrives at Orchestrator
- **THEN** `X-Request-Id`, `X-Tenant-Id`, `X-User-Id`, `X-Idempotency-Key`, and `X-Session-Id` are validated before any model call is made

#### Scenario: Business payload stays model-focused
- **WHEN** the model streaming request payload is parsed
- **THEN** it contains the original query without duplicating request, tenant, user, idempotency, session, or simulated failure controls

#### Scenario: SSE reconnect uses standard event position header
- **WHEN** a client reconnects after receiving SSE events
- **THEN** the service uses the same ownership Headers plus the standard `Last-Event-ID` Header to select the next buffered event

### Requirement: Gateway fronts streaming model access
Clients SHALL access streaming model responses through the API Gateway, and the Gateway SHALL delegate to the internal Orchestrator streaming endpoint.

#### Scenario: Client opens stream through Gateway
- **WHEN** a client opens the streaming model endpoint
- **THEN** the request enters `claw4j-api-gateway` first and is forwarded to `claw4j-orchestrator` through the governed internal HTTP client boundary

#### Scenario: Gateway preserves SSE response
- **WHEN** Orchestrator returns SSE events for token, fallback-start, adaptation, failure, or completion
- **THEN** Gateway streams those SSE bytes back to the client without parsing provider-specific model output or owning fallback policy

#### Scenario: Gateway propagates stream context
- **WHEN** Gateway calls Orchestrator for a streaming request
- **THEN** it propagates request, tenant, user, idempotency, session, and optional `Last-Event-ID` Headers before Orchestrator performs model governance

#### Scenario: Browser-friendly Gateway entry remains Gateway-owned
- **WHEN** a browser-friendly model chat request enters Gateway without custom service context Headers
- **THEN** Gateway creates the required context for local browser access and still calls Orchestrator through the same internal streaming boundary

### Requirement: Streaming resume supports real model clients
The streaming model resume path SHALL support real provider-backed model clients without requiring production deterministic proof behavior.

#### Scenario: Real primary stream feeds existing resume buffer
- **WHEN** a real primary model emits user-visible streaming content through the Orchestrator
- **THEN** the emitted content is appended to the same bounded session buffer used for reconnect and fallback continuation

#### Scenario: Real fallback stream resumes from cached output
- **WHEN** a real primary model stream fails after partial user-visible content
- **THEN** the fallback provider receives a resume prompt based on the cached output and continues without repeating the already emitted prefix

#### Scenario: Gateway remains provider-agnostic
- **WHEN** real DeepSeek or Qwen providers produce streaming events through Orchestrator
- **THEN** Gateway continues forwarding Orchestrator SSE bytes without parsing provider-specific model output or owning model fallback policy
