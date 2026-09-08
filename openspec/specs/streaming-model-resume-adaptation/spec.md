# streaming-model-resume-adaptation Specification

## Purpose

This capability defines how Claw4J handles resilient streaming model responses, model context-window adaptation, and normalized output contracts when primary and fallback models have different behavior and capacity.

## Requirements

### Requirement: Request and session metadata is header-propagated
The streaming endpoint SHALL receive cross-cutting request and session metadata from HTTP Headers or propagated request context instead of from business request parameters.

#### Scenario: Required stream context headers are validated
- **WHEN** an initial streaming request arrives
- **THEN** `X-Request-Id`, `X-Tenant-Id`, `X-User-Id`, `X-Idempotency-Key`, and `X-Session-Id` are validated before any model call is made

#### Scenario: Business payload stays model-focused
- **WHEN** the model streaming request payload is parsed
- **THEN** it contains the original query and proof controls without duplicating request, tenant, user, idempotency, or session metadata fields

#### Scenario: SSE reconnect uses standard event position header
- **WHEN** a client reconnects after receiving SSE events
- **THEN** the service uses the same ownership Headers plus the standard `Last-Event-ID` Header to select the next buffered event

### Requirement: Gateway fronts streaming model access
Clients SHALL access streaming model responses through the API Gateway, and the Gateway SHALL delegate to the internal Orchestrator streaming endpoint.

#### Scenario: Client opens stream through Gateway
- **WHEN** a client opens the streaming model endpoint
- **THEN** the request enters `claw4j-api-gateway` first and is forwarded to `claw4j-orchestrator` through the governed internal HTTP client boundary

#### Scenario: Gateway preserves SSE response
- **WHEN** Orchestrator returns SSE events for token, fallback-start, failure, or completion
- **THEN** Gateway streams those SSE bytes back to the client without parsing provider-specific model output or owning fallback policy

#### Scenario: Gateway propagates stream context
- **WHEN** Gateway calls Orchestrator for a streaming request
- **THEN** it propagates request, tenant, user, idempotency, session, and optional `Last-Event-ID` Headers before Orchestrator performs model governance

### Requirement: Streaming resume supports real model clients
The streaming model resume path SHALL support real provider-backed model clients while preserving the existing deterministic proof behavior.

#### Scenario: Real primary stream feeds existing resume buffer
- **WHEN** a real primary model emits user-visible streaming content through the Orchestrator
- **THEN** the emitted content is appended to the same bounded session buffer used by deterministic streaming output

#### Scenario: Real fallback stream resumes from cached output
- **WHEN** a real primary model stream fails after partial user-visible content
- **THEN** the fallback provider receives a resume prompt based on the cached output and continues without repeating the already emitted prefix

#### Scenario: Gateway remains provider-agnostic
- **WHEN** real DeepSeek or Qwen providers produce streaming events through Orchestrator
- **THEN** Gateway continues forwarding Orchestrator SSE bytes without parsing provider-specific model output or owning model fallback policy

### Requirement: Upstream model stream can resume through fallback
The Orchestrator SHALL continue a model streaming response with a fallback model when the upstream primary model fails after partial content has already been emitted.

#### Scenario: Fallback continues after partial primary output
- **WHEN** the primary model stream emits partial user-visible content and then fails
- **THEN** the fallback model continues from the emitted content without repeating the already delivered prefix

#### Scenario: Resume is disabled
- **WHEN** streaming resume is disabled and the primary model stream fails after partial content
- **THEN** the stream emits a stable failure event and does not start fallback continuation

#### Scenario: No partial output exists
- **WHEN** the primary model fails before user-visible content is emitted
- **THEN** the Orchestrator may call the fallback model with the original request instead of a resume prompt

### Requirement: Client SSE reconnection resumes by event position
SSE streaming responses SHALL expose enough session and event position metadata for a client to reconnect without receiving duplicate content.

#### Scenario: Client reconnects with last event id
- **WHEN** a client reconnects with the same validated Header-derived session context and the `Last-Event-ID` it received
- **THEN** the service resumes from the next available event for that session

#### Scenario: Client reconnects without ownership
- **WHEN** a reconnect request does not match the original tenant and user context for the session
- **THEN** the request is rejected before cached stream content is returned

#### Scenario: Requested event is no longer buffered
- **WHEN** a client requests an event position older than the bounded resume buffer
- **THEN** the service returns a stable resume-expired outcome instead of replaying incomplete or incorrect content

### Requirement: Streaming resume state is bounded and isolated
Streaming resume state SHALL be bounded by configured buffer size, session lifetime, and tenant/user ownership.

#### Scenario: Buffer limit is enforced
- **WHEN** emitted content exceeds the configured resume buffer size
- **THEN** the service retains only the allowed resume window and records that older content is no longer resumable

#### Scenario: Session state expires
- **WHEN** a streaming session exceeds the configured retention window
- **THEN** the service removes the cached resume state and rejects future resume attempts for that session

#### Scenario: Session is isolated by tenant and user
- **WHEN** two tenants or users use the same client-provided session identifier
- **THEN** their cached streaming state remains isolated and cannot be read across ownership boundaries

### Requirement: Context windows adapt to target model capacity
Model requests SHALL be adapted to the configured context capacity of the selected primary or fallback model before a model call is made.

#### Scenario: Primary context fits
- **WHEN** the request context fits within the primary model context budget
- **THEN** the primary model receives the complete validated context

#### Scenario: Fallback context requires adaptation
- **WHEN** fallback continuation would exceed the fallback model context budget
- **THEN** the context is adapted according to the configured `summary`, `truncate`, or `reject` strategy before fallback is called

#### Scenario: Reject strategy blocks oversized context
- **WHEN** the configured context strategy is `reject` and the request exceeds the selected model budget
- **THEN** the service returns a stable context-too-large outcome without calling the model

### Requirement: Resume prompts preserve data boundaries
Fallback resume prompts SHALL treat the original user query, already emitted content, and system recovery instruction as separate data sections.

#### Scenario: User query is wrapped as data
- **WHEN** a fallback resume prompt is constructed
- **THEN** the original user query is wrapped as user data and cannot override system recovery instructions

#### Scenario: Cached output is wrapped as data
- **WHEN** already emitted content is included in a fallback resume prompt
- **THEN** the cached content is marked as prior assistant output and is not interpreted as a new user instruction

#### Scenario: Resume prompt avoids duplicate output
- **WHEN** fallback generation starts from a resume prompt
- **THEN** the prompt instructs the fallback model to continue after the cached output while preserving tone and format

### Requirement: Heterogeneous model output is normalized
Model output SHALL be parsed into a stable Claw4J output contract that separates user-visible content from internal reasoning markers and provider-specific formatting.

#### Scenario: Deep-thinking markers are removed
- **WHEN** a model output contains provider-specific reasoning markers
- **THEN** the parser removes internal reasoning content from the user-visible response

#### Scenario: Visible answer is preserved
- **WHEN** a model output contains both reasoning markers and final answer content
- **THEN** the parser preserves the final answer content in the standard output contract

#### Scenario: Invalid model output is rejected
- **WHEN** a model output cannot be parsed into the standard contract
- **THEN** the service returns a stable parser-failure outcome instead of emitting malformed data

### Requirement: Streaming model governance is observable
Streaming fallback, context adaptation, output parsing, and resume expiration SHALL produce enough observable events for smoke tests and operational diagnosis.

#### Scenario: Fallback start is visible
- **WHEN** fallback continuation begins after primary stream interruption
- **THEN** the stream emits or records a fallback-start event with session and request context

#### Scenario: Context adaptation is visible
- **WHEN** context is summarized, truncated, or rejected
- **THEN** the response or logs identify the adaptation outcome without exposing sensitive content

#### Scenario: Stream completion is explicit
- **WHEN** a streaming response completes normally or through fallback
- **THEN** the client receives an explicit completion event for the session
