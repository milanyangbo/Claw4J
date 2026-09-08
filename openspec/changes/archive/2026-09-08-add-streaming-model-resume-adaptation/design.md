## Context

Claw4J currently has Spring MVC services using `spring-boot-starter-web`, shared response/error contracts in `claw4j-common`, internal Feign proof paths with request-context Header propagation, Nacos dynamic config, and Sentinel proof paths. There is not yet a production model runtime, so this change should create a usable model-governance proof path without pretending that real vendor credentials, Redis-backed distributed replay, or full Agent execution already exist.

The user-facing sample uses `Flux<ServerSentEvent>`, but SSE and WebFlux are different layers. SSE is the one-way HTTP streaming protocol and `text/event-stream` response format; WebFlux is Spring's reactive web stack. The repository currently does not use WebFlux, so the first implementation should match the existing Spring MVC stack and use `SseEmitter` unless a later proposal explicitly migrates a streaming boundary to WebFlux.

## Goals / Non-Goals

**Goals:**

- Prove upstream model-stream interruption recovery with a fallback model after partial content.
- Distinguish upstream model interruption from client SSE connection loss.
- Add bounded, tenant/user-isolated stream resume state for the proof path.
- Treat request/session metadata as Header-derived request context instead of model business parameters.
- Adapt prompt context to primary and fallback model context budgets before model calls.
- Normalize DeepSeek-like, QwQ-like, and generic model output into a stable response contract.
- Keep all thresholds, switches, buffer sizes, and model capacities property-driven.

**Non-Goals:**

- Do not add a real DeepSeek, QwQ, DashScope, or external model credential integration in this first proof path.
- Do not introduce Redis or distributed multi-node stream replay yet.
- Do not migrate the project to WebFlux.
- Do not implement a full ReAct Agent runtime or production conversation store.
- Do not expose internal reasoning traces to users.

## Decisions

### Decision: Gateway is the external SSE entrypoint and Orchestrator owns model-stream governance

The access path should be client -> Gateway -> Orchestrator. Gateway should expose the external SSE endpoint, validate and propagate Headers, and stream Orchestrator's SSE response bytes back to the caller. The Orchestrator should own model routing, context adaptation, fallback continuation, and output parsing because those concerns belong to Agent/model orchestration rather than ingress routing.

Alternative considered: implement the full streaming fallback in Gateway. Rejected because Gateway should stay an access boundary and would otherwise duplicate Orchestrator model policy.

### Decision: Request and session metadata travels through Headers

The HTTP boundary should receive cross-cutting context through Headers: existing `X-Request-Id`, `X-Tenant-Id`, `X-User-Id`, and `X-Idempotency-Key`, plus `X-Session-Id` for the stream session and the SSE-standard `Last-Event-ID` Header on reconnect. The controller should validate these values and pass a single request-context DTO into the service layer, while the business request payload only carries the original query and proof controls such as simulated failure mode.

Alternative considered: expose `requestId`, `tenantId`, `userId`, `idempotencyKey`, and `sessionId` as ordinary request parameters or duplicated body fields. Rejected because these values are cross-cutting routing, audit, tenancy, idempotency, and resume metadata that must propagate consistently across Gateway, Orchestrator, and Feign boundaries.

### Decision: Use Spring MVC `SseEmitter` for the first proof path

The repository already uses `spring-boot-starter-web` in runnable services. The implementation should use `SseEmitter` with explicit event names and event ids so it compiles within the current stack and supports curl/browser smoke tests.

Alternative considered: add WebFlux and implement `Flux<ServerSentEvent>`. Rejected for this change because it adds a new web stack, different execution model, and migration surface that are not required to prove the behavior.

### Decision: Separate upstream interruption from client reconnection

When the upstream model stream fails while the client SSE connection is still open, the service can call fallback and continue writing to the same emitter. When the client connection closes, the original HTTP connection is gone; recovery must happen through a new request using session ownership and last event id.

Alternative considered: document all failures as "same SSE connection resume". Rejected because client disconnects cannot resume on the same closed HTTP connection.

### Decision: Use a bounded in-memory stream state store for the proof path

The first implementation should use an in-memory, property-bounded session store keyed by tenant, user, and session id. It should retain event ids and emitted user-visible content only within configured limits, making the behavior deterministic for tests without introducing Redis.

Alternative considered: introduce Redis immediately. Rejected because distributed stream replay is a separate storage and lifecycle problem; it should be handled in a later proposal once the proof path is stable.

### Decision: Model clients are abstractions with deterministic proof implementations

Define a small model-stream client abstraction and deterministic local clients that can simulate primary success, primary mid-stream failure, fallback continuation, and malformed output. This proves orchestration logic without real vendor accounts.

Alternative considered: call real DeepSeek-R1 or QwQ providers immediately. Rejected because credentials, network availability, provider SDK choice, and billing would make local verification fragile.

### Decision: Context adaptation happens before each model call

The context adapter should choose the target budget from the selected model role, estimate request size, and apply configured `summary`, `truncate`, or `reject` behavior before the primary or fallback call is made. The first implementation can use a deterministic approximate token estimator and local summary/truncation strategy so tests remain stable.

Alternative considered: rely on provider-side truncation. Rejected because provider-side truncation is opaque and can drop safety instructions, session context, or already emitted content unpredictably.

### Decision: Resume prompts use explicit data sections

Fallback resume prompts must keep recovery instructions separate from the original user query and cached prior assistant output. The prompt should wrap user-controlled content in data tags such as `<user_query>` and `<cached_output>` and should never treat cached output as a new system instruction.

Alternative considered: use direct string interpolation like the sample. Rejected because it risks prompt injection and violates the project rule against placing user input directly into system prompts.

### Decision: The first summary strategy is deterministic local compression

The `summary` strategy should initially use deterministic local compression, such as retaining bounded head and tail sections with an explicit omission marker, rather than calling a second model. This keeps tests repeatable and avoids introducing another model dependency before credentials and vendor behavior are specified.

Alternative considered: use a real model summarizer for context compression immediately. Rejected because it adds provider configuration, failure modes, and cost to a proof path whose purpose is stream resilience.

### Decision: Output parser emits a standard DTO and strips reasoning markers

The parser should normalize DeepSeek-like, QwQ-like, and generic output into a standard DTO with visible content, model type, parser status, and reasoning-redaction metadata. It must remove internal reasoning markers before any user-visible SSE token or final JSON body is emitted.

Alternative considered: pass raw model text through to the client. Rejected because model-specific reasoning tags and malformed JSON would leak internal traces and destabilize clients.

## Risks / Trade-offs

- [Risk] In-memory resume state is lost on restart and cannot support multi-node replay. -> Mitigation: document it as a single-instance proof path and keep Redis/distributed replay out of scope for this change.
- [Risk] Approximate token counting differs from provider tokenization. -> Mitigation: keep budgets conservative and make tokenizer replacement a later internal detail.
- [Risk] Fallback can repeat content despite prompt instructions. -> Mitigation: track emitted prefix and add parser-side duplicate-prefix trimming before emission.
- [Risk] `SseEmitter` writes can fail after client disconnect. -> Mitigation: handle emitter completion/error callbacks and store resumable events for reconnect rather than assuming same-connection continuation.
- [Risk] Output parser can accidentally expose reasoning markers. -> Mitigation: add model-specific parser tests for DeepSeek-like and QwQ-like markers plus malformed output cases.
- [Risk] Resume prompts may include too much cached output. -> Mitigation: enforce configured buffer size before prompt construction and apply fallback context adaptation.
- [Risk] Header context can be lost between Gateway and Orchestrator. -> Mitigation: centralize Header names in shared constants and add Gateway smoke-test curl examples that require request, tenant, user, idempotency, session, and `Last-Event-ID` Headers.

## Migration Plan

1. Add project context, defense rules, and security rules documents so future changes have stable local guidance.
2. Add shared streaming request DTO and Header/error constants needed by the Gateway-to-Orchestrator boundary.
3. Add Orchestrator streaming model configuration, parser, context adapter, stream state store, deterministic model client proof path, and internal SSE endpoint.
4. Add a thin Gateway SSE endpoint that calls Orchestrator through OpenFeign and forwards the returned SSE stream without duplicating model policy.
5. Add unit and contract tests for Gateway forwarding, Header propagation, resume, reconnect, buffer expiry, context adaptation, parser normalization, and invalid context rejection.
6. Add smoke-test documentation with curl examples that call Gateway on port `8080` for normal stream, mid-stream fallback, reconnect from last event id, oversized context handling, parser normalization, and cleanup.

Rollback is straightforward before production integration: disable resume through configuration, remove the proof endpoint from routing, and revert the change files. No persistent data migration is introduced.
