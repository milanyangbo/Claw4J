## 0. Scope Guardrails

- [x] 0.1 Modify only these project-level files if dependency management or documentation links require it: `pom.xml`, `README.md`, `MODEL_FAILOVER_SMOKE_TESTS.md`, and `STREAMING_MODEL_RESUME_SMOKE_TESTS.md`; verify `git diff --name-only` contains no unrelated project-level files.
- [x] 0.2 Modify only these shared contract files if stable model-failover error/status contracts require it: `claw4j-common/src/main/java/com/claw4j/common/exception/ErrorCode.java` and `claw4j-common/src/test/java/com/claw4j/common/CommonFoundationContractTest.java`; verify no provider runtime logic is added to `claw4j-common`.
- [x] 0.3 Modify only these Orchestrator config/dependency files: `claw4j-orchestrator/pom.xml`, `claw4j-orchestrator/src/main/resources/application.yml`, `claw4j-orchestrator/src/main/java/com/claw4j/orchestrator/config/OrchestratorStreamingModelProperties.java`, `claw4j-orchestrator/src/main/java/com/claw4j/orchestrator/config/OrchestratorModelClientProperties.java`, and `claw4j-orchestrator/src/main/java/com/claw4j/orchestrator/config/OrchestratorModelResilienceProperties.java`; verify all configuration classes use `@ConfigurationProperties` and normalize or reject invalid values.
- [x] 0.4 Modify only these Orchestrator model-call files: `claw4j-orchestrator/src/main/java/com/claw4j/orchestrator/dto/ModelType.java`, `claw4j-orchestrator/src/main/java/com/claw4j/orchestrator/service/ModelStreamClient.java`, `claw4j-orchestrator/src/main/java/com/claw4j/orchestrator/service/DeterministicModelStreamClient.java`, `claw4j-orchestrator/src/main/java/com/claw4j/orchestrator/service/StreamingModelService.java`, `claw4j-orchestrator/src/main/java/com/claw4j/orchestrator/service/ModelContextAdapter.java`, `claw4j-orchestrator/src/main/java/com/claw4j/orchestrator/service/ModelOutputParser.java`, `claw4j-orchestrator/src/main/java/com/claw4j/orchestrator/client/ModelProviderClient.java`, `claw4j-orchestrator/src/main/java/com/claw4j/orchestrator/client/SpringAiModelProviderClient.java`, and `claw4j-orchestrator/src/main/java/com/claw4j/orchestrator/service/ResilientModelStreamClient.java`; verify new code stays under approved `client`, `config`, `dto`, or `service` packages.
- [x] 0.5 Modify only these Orchestrator tests for this change: `claw4j-orchestrator/src/test/java/com/claw4j/orchestrator/StreamingModelResumeAdaptationContractTest.java` and `claw4j-orchestrator/src/test/java/com/claw4j/orchestrator/ModelFailoverResilienceContractTest.java`; verify no tests require real provider credentials by default.
- [x] 0.6 Keep out of scope: Gateway API shape changes, authentication/JWT redesign, Redis-backed distributed stream state, WebFlux migration, production billing controls, full Agent planning, Tool Executor, Knowledge Memory, A2A Broker behavior, and new Maven modules; verify no matching files are changed.

## 1. Dependencies and Configuration

- [x] 1.1 Add Spring Cloud CircuitBreaker Resilience4j and compatible Spring AI / Spring AI Alibaba model-client dependencies to `claw4j-orchestrator/pom.xml`; verify `mvn -q -pl claw4j-orchestrator -am dependency:tree` resolves DeepSeek, Qwen/DashScope, and Resilience4j dependencies without version conflicts.
- [x] 1.2 Add `OrchestratorModelClientProperties` for deterministic vs real mode only; verify DeepSeek and Qwen API keys/model names are supplied through official Spring AI / Spring AI Alibaba properties.
- [x] 1.3 Add `OrchestratorModelResilienceProperties` for primary timeout, failure-rate threshold, sliding-window size, minimum call count, open-state wait duration, and half-open permitted calls; verify invalid numeric or duration values fall back to conservative defaults.
- [x] 1.4 Add official Spring AI provider defaults plus Claw4J model client and resilience defaults to `claw4j-orchestrator/src/main/resources/application.yml`; verify secrets are referenced only through environment placeholders and no API key literals are committed.

## 2. Provider Client Boundary

- [x] 2.1 Add `ModelProviderClient` as the Orchestrator outbound model-provider boundary for primary and fallback streaming calls; verify the interface exposes only model type, prompt, request context needed for invocation, and token callback behavior.
- [x] 2.2 Add `SpringAiModelProviderClient` to adapt Spring AI DeepSeek and Spring AI Alibaba DashScope/Qwen streaming responses into the existing token callback contract; verify provider-specific exceptions are sanitized before leaving the client boundary.
- [x] 2.3 Keep `DeterministicModelStreamClient` usable for deterministic mode and test doubles; verify default tests can simulate primary success, timeout, provider failure, circuit-open fallback, and half-open recovery without external network or credentials.

## 3. Resilience Guard

- [x] 3.1 Add `ResilientModelStreamClient` that delegates deterministic mode directly and wraps real primary DeepSeek calls with Resilience4j TimeLimiter and CircuitBreaker; verify fallback Qwen calls do not contribute samples to the DeepSeek circuit.
- [x] 3.2 Map primary timeout, provider failure, and circuit-open outcomes into explicit `ModelStreamClient.ModelStreamException` statuses consumed by `StreamingModelService`; verify stream events expose stable fallback statuses without raw provider transport details.
- [x] 3.3 Preserve existing partial-output resume behavior when a primary real-model stream fails after emitting tokens; verify cached output is reused in the fallback resume prompt and duplicate prefixes are trimmed.
- [x] 3.4 Preserve fail-closed validation for invalid Header-derived request, tenant, user, idempotency, and session context; verify invalid context is rejected before primary or fallback provider calls.

## 4. Tests

- [x] 4.1 Add dependency/config contract tests proving real mode requires credentials, deterministic mode does not, and all resilience properties bind through `@ConfigurationProperties`; verify tests pass without API keys.
- [x] 4.2 Add primary-success and timeout-fallback tests; verify DeepSeek success emits primary content and timeout emits Qwen fallback content with a timeout status.
- [x] 4.3 Add error-ratio circuit tests; verify failures above 50 percent open the primary circuit and subsequent calls short-circuit directly to fallback without invoking the primary provider.
- [x] 4.4 Add half-open recovery tests; verify a successful probe closes the circuit and later requests use the primary provider again, while a failed probe reopens it.
- [x] 4.5 Extend source scans to forbid `spring-boot-starter-webflux`, `Flux<ServerSentEvent>`, `System.out.println`, `return null`, empty catch blocks, and committed API-key literals; verify the scan covers all new files.

## 5. Documentation and Smoke Tests

- [x] 5.1 Add `MODEL_FAILOVER_SMOKE_TESTS.md` with curl flows against Gateway for deterministic primary success, deterministic timeout fallback, circuit-open fallback, half-open recovery, and rollback to deterministic mode; verify it documents required Headers and expected SSE events.
- [x] 5.2 Add optional real-provider smoke steps using environment variables for DeepSeek and DashScope/Qwen credentials; verify the guide explains that real-provider steps are skipped when credentials are absent.
- [x] 5.3 Update `README.md` with a link to the new model failover smoke-test document; verify README remains a project overview and does not absorb detailed curl flows.

## 6. Verification

- [x] 6.1 Run `mvn clean compile -pl claw4j-orchestrator -am` and verify Orchestrator plus shared contracts compile.
- [x] 6.2 Run `mvn test -pl claw4j-common,claw4j-orchestrator -am` and verify model failover, streaming resume, and shared contract tests pass.
- [x] 6.3 Run `mvn clean test` from the repository root and verify all module tests pass.
- [x] 6.4 Run `openspec validate "add-resilience4j-model-failover" --strict` and verify proposal, specs, design, and tasks are valid before implementation handoff.
