## 0. Scope Guardrails

- [x] 0.1 Keep the new project guidance files limited to `openspec/project.md`, `openspec/specs/defense-rules.md`, and `openspec/specs/security-rules.md`; verify all three files exist and `agents.md` references remain valid.
- [x] 0.2 Modify only these existing shared contract files if cross-module constants, errors, or DTO contracts are needed: `claw4j-common/src/main/java/com/claw4j/common/constant/CommonConstants.java`, `claw4j-common/src/main/java/com/claw4j/common/exception/ErrorCode.java`, `claw4j-common/src/main/java/com/claw4j/common/dto/StreamingModelRequest.java`, and `claw4j-common/src/test/java/com/claw4j/common/CommonFoundationContractTest.java`; verify no model runtime behavior is added to `claw4j-common`.
- [x] 0.3 Add only these Orchestrator streaming proof-path Java files: `claw4j-orchestrator/src/main/java/com/claw4j/orchestrator/config/OrchestratorStreamingModelProperties.java`, `claw4j-orchestrator/src/main/java/com/claw4j/orchestrator/controller/OrchestratorStreamingModelController.java`, `claw4j-orchestrator/src/main/java/com/claw4j/orchestrator/dto/ContextAdaptationStatus.java`, `claw4j-orchestrator/src/main/java/com/claw4j/orchestrator/dto/ModelStreamEvent.java`, `claw4j-orchestrator/src/main/java/com/claw4j/orchestrator/dto/ModelType.java`, `claw4j-orchestrator/src/main/java/com/claw4j/orchestrator/dto/StandardModelOutput.java`, `claw4j-orchestrator/src/main/java/com/claw4j/orchestrator/dto/StreamingRequestContext.java`, `claw4j-orchestrator/src/main/java/com/claw4j/orchestrator/service/DeterministicModelStreamClient.java`, `claw4j-orchestrator/src/main/java/com/claw4j/orchestrator/service/ModelContextAdapter.java`, `claw4j-orchestrator/src/main/java/com/claw4j/orchestrator/service/ModelOutputParser.java`, `claw4j-orchestrator/src/main/java/com/claw4j/orchestrator/service/ModelStreamClient.java`, `claw4j-orchestrator/src/main/java/com/claw4j/orchestrator/service/StreamingModelService.java`, `claw4j-orchestrator/src/main/java/com/claw4j/orchestrator/service/StreamingResumePromptBuilder.java`, and `claw4j-orchestrator/src/main/java/com/claw4j/orchestrator/service/StreamingSessionStore.java`; verify all packages are approved and no `model` package is created.
- [x] 0.4 Modify only these Gateway/Orchestrator config/test/doc files for the proof path: `claw4j-api-gateway/src/main/java/com/claw4j/gateway/client/GatewayFeignRequestContextInterceptor.java`, `claw4j-api-gateway/src/main/java/com/claw4j/gateway/client/OrchestratorClient.java`, `claw4j-api-gateway/src/main/java/com/claw4j/gateway/client/OrchestratorClientFallback.java`, `claw4j-api-gateway/src/main/java/com/claw4j/gateway/controller/GatewayStreamingModelController.java`, `claw4j-api-gateway/src/main/java/com/claw4j/gateway/service/OrchestratorGatewayService.java`, `claw4j-api-gateway/src/test/java/com/claw4j/gateway/OpenFeignServiceCallContractTest.java`, `claw4j-orchestrator/src/main/resources/application.yml`, `claw4j-orchestrator/src/test/java/com/claw4j/orchestrator/StreamingModelResumeAdaptationContractTest.java`, `README.md`, and `STREAMING_MODEL_RESUME_SMOKE_TESTS.md`; verify `claw4j-tool-executor/**`, `claw4j-knowledge-memory/**`, and `claw4j-a2a-broker/**` are unchanged.
- [x] 0.5 Keep out of scope: real DeepSeek/QwQ/DashScope credentials, external model SDK calls, Redis-backed distributed replay, WebFlux migration, WebSocket support, full Agent runtime, new Maven modules, and non-standard package directories; verify `git diff --name-only` contains no out-of-scope paths.

## 1. Configuration and Shared Contracts

- [x] 1.1 Add `OrchestratorStreamingModelProperties` with documented `@ConfigurationProperties` for resume enablement, TTFB timeout, resume buffer size, session retention, primary/fallback max tokens, truncation strategy, and proof client behavior; verify invalid local values are normalized or rejected without `return null`.
- [x] 1.2 Add shared constants or error codes for stream session header, standard `Last-Event-ID` header, resume-expired, context-too-large, and output-parser failure only if they are cross-module contracts; verify `CommonFoundationContractTest` covers non-empty codes/messages and expected HTTP status categories.
- [x] 1.3 Add Orchestrator DTOs for Header-derived request context, business streaming request payload, model type, standard parsed output, context adaptation status, and SSE event payloads; verify constructors/factory methods validate required fields and never return `null`.
- [x] 1.4 Add property defaults to `claw4j-orchestrator/src/main/resources/application.yml`; verify all thresholds and model capacities are environment-overridable and not hardcoded in service logic.

## 2. Streaming State and Resume

- [x] 2.1 Implement `StreamingSessionStore` as a bounded in-memory proof store keyed by Header-derived tenant id, user id, and session id; verify tests cover ownership isolation, buffer trimming, event sequence ordering, and expired session rejection.
- [x] 2.2 Implement `StreamingResumePromptBuilder` that wraps original user query and cached assistant output in data tags and keeps recovery instructions separate; verify source scans find no direct user-input system prompt interpolation.
- [x] 2.3 Implement `ModelStreamClient` and `DeterministicModelStreamClient` to simulate primary success, primary mid-stream failure, fallback continuation, and malformed output; verify deterministic tests do not require external model credentials.
- [x] 2.4 Implement `StreamingModelService` with `SseEmitter` streaming, primary model TTFB timeout handling, upstream interruption detection, fallback continuation, duplicate-prefix trimming, completion events, and failure events; verify partial primary output can be followed by fallback output in event order.
- [x] 2.5 Add client reconnection support using validated session context and standard `Last-Event-ID` Header; verify reconnect resumes from the next buffered event and rejects mismatched tenant/user context.

## 3. Context Adaptation

- [x] 3.1 Implement `ModelContextAdapter` with deterministic token estimation and per-model context budgets; verify primary and fallback budgets are loaded from properties.
- [x] 3.2 Implement `summary`, `truncate`, and `reject` strategies; verify summary/truncate keep prompt boundaries intact and reject returns a stable context-too-large outcome before model invocation.
- [x] 3.3 Ensure fallback resume prompts pass through context adaptation before fallback model calls; verify an oversized cached-output case is adapted according to the configured fallback strategy.

## 4. Output Parser Normalization

- [x] 4.1 Implement `ModelOutputParser` for DeepSeek-like, QwQ-like, and generic outputs; verify provider-specific reasoning markers are removed from user-visible content.
- [x] 4.2 Convert parsed output to `StandardModelOutput` with visible content, model type, parser status, and reasoning-redaction metadata; verify malformed output returns a stable parser-failure outcome.
- [x] 4.3 Ensure streaming tokens are parsed or filtered before emission; verify tests prove internal reasoning markers are never emitted through SSE events.

## 5. Controller and Documentation

- [x] 5.1 Add a thin Orchestrator endpoint under `/internal/orchestrator/model/stream` that reads request, tenant, user, idempotency, session, and optional last-event-id context from Headers, accepts model input/proof controls as the business payload, and delegates to `StreamingModelService`; verify controller logic only maps Headers/body and returns `SseEmitter`.
- [x] 5.2 Add a thin Gateway endpoint under `/api/model/stream` that reads request, tenant, user, idempotency, session, and optional last-event-id context from Headers, accepts model input/proof controls as the business payload, calls Orchestrator through OpenFeign, and forwards the downstream SSE stream without model parsing or fallback policy.
- [x] 5.3 Add `STREAMING_MODEL_RESUME_SMOKE_TESTS.md` with curl checks against Gateway for normal streaming, primary mid-stream fallback, resume disabled, reconnect from `Last-Event-ID`, context reject, parser normalization, and cleanup; verify it documents required Headers and that client disconnect recovery uses a new SSE request.
- [x] 5.4 Update `README.md` with a link to the streaming model resume smoke tests; verify README remains a project overview and does not absorb the detailed curl flow.

## 6. Automated Verification

- [x] 6.1 Run `mvn clean compile -pl claw4j-api-gateway,claw4j-orchestrator -am` and verify Gateway, Orchestrator, and shared contracts compile.
- [x] 6.2 Run `mvn test -pl claw4j-common,claw4j-api-gateway,claw4j-orchestrator -am` and verify Gateway forwarding, streaming model resume, context adaptation, parser, and shared contract tests pass.
- [x] 6.3 Run `mvn clean test` from the repository root and verify all module tests pass.
- [x] 6.4 Run `openspec validate "add-streaming-model-resume-adaptation" --strict` and verify the change artifacts are valid before implementation handoff.
