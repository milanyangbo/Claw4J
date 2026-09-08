## 0. Scope Guardrails

- [x] 0.1 Modify only these existing files for dependency/config/docs work: `claw4j-api-gateway/pom.xml`, `claw4j-orchestrator/pom.xml`, `claw4j-api-gateway/src/main/resources/application.yml`, `claw4j-orchestrator/src/main/resources/application.yml`, `README.md`, `DEPLOYMENT.md`, and `SENTINEL_SMOKE_TESTS.md`; verify `git diff --name-only` contains no other docs/config/dependency files.
- [x] 0.2 Modify only these common shared contract files if new response codes are needed: `claw4j-common/src/main/java/com/claw4j/common/exception/ErrorCode.java` and `claw4j-common/src/test/java/com/claw4j/common/CommonFoundationContractTest.java`; verify no Sentinel runtime dependency or service behavior is added to `claw4j-common`.
- [x] 0.3 Add only these Gateway Sentinel proof-path Java files: `claw4j-api-gateway/src/main/java/com/claw4j/gateway/config/GatewaySentinelProperties.java`, `claw4j-api-gateway/src/main/java/com/claw4j/gateway/dto/SentinelGuardStatus.java`, `claw4j-api-gateway/src/main/java/com/claw4j/gateway/service/GatewaySentinelGuardService.java`, `claw4j-api-gateway/src/main/java/com/claw4j/gateway/controller/GatewaySentinelGuardController.java`, and `claw4j-api-gateway/src/test/java/com/claw4j/gateway/SentinelResilienceGuardContractTest.java`; verify all packages are approved and no new module is created.
- [x] 0.4 Add only these Orchestrator Sentinel proof-path Java files: `claw4j-orchestrator/src/main/java/com/claw4j/orchestrator/config/OrchestratorSentinelProperties.java`, `claw4j-orchestrator/src/main/java/com/claw4j/orchestrator/dto/AgentCallGuardStatus.java`, `claw4j-orchestrator/src/main/java/com/claw4j/orchestrator/service/AgentCallGuardService.java`, `claw4j-orchestrator/src/main/java/com/claw4j/orchestrator/controller/OrchestratorSentinelGuardController.java`, and `claw4j-orchestrator/src/test/java/com/claw4j/orchestrator/SentinelResilienceGuardContractTest.java`; verify any new package directory is one of the approved package areas.
- [x] 0.5 Keep out of scope: `claw4j-tool-executor/**`, `claw4j-knowledge-memory/**`, `claw4j-a2a-broker/**`, Redis-backed quota storage, Sentinel cluster flow control, authentication, real Agent/model/tool execution workflows, existing OpenFeign client contracts, and new Maven modules; verify no matching files are changed.

## 1. Sentinel Dependencies and Configuration

- [x] 1.1 Add `spring-cloud-starter-alibaba-sentinel` to Gateway and Orchestrator only; verify `mvn dependency:tree -pl claw4j-api-gateway,claw4j-orchestrator -Dincludes=com.alibaba.cloud:spring-cloud-starter-alibaba-sentinel` lists the dependency for both services.
- [x] 1.2 Add Sentinel Dashboard transport configuration to Gateway and Orchestrator with environment-overridable local defaults; verify static tests confirm dashboard address, client port, and eager initialization settings are configurable without code changes.
- [x] 1.3 Add Nacos-backed Sentinel datasource configuration for Gateway flow rules, Gateway parameter-flow rules, and Orchestrator degrade rules using JSON DataIds; verify static tests confirm `data-type: json`, rule types `flow`, `param-flow`, and `degrade`, and namespace/group defaults align with existing Nacos conventions.
- [x] 1.4 Add local default Sentinel rule properties for Gateway global QPS, Gateway tenant QPS, Orchestrator error-ratio threshold, minimum request amount, stat interval, and recovery window; verify these values are property-driven and not hardcoded magic numbers in guard logic.

## 2. Shared Error Contracts

- [x] 2.1 Add stable shared error codes for rate-limited and circuit-open outcomes if existing codes are not specific enough; verify `CommonFoundationContractTest` checks non-empty codes, messages, and expected HTTP status categories.
- [x] 2.2 Ensure Sentinel block and fallback outcomes use `BusinessException` or the shared `ApiResponse` envelope without `return null`; verify a source scan finds no `return null`, empty catch block, or `System.out.println` in the new Sentinel files.

## 3. Gateway Flow Control Proof Path

- [x] 3.1 Add `GatewaySentinelProperties` as a documented `@ConfigurationProperties` component for resource names and default flow thresholds; verify it has Javadoc, uses constructor or setter binding safely, and rejects or normalizes invalid local values without breaking startup.
- [x] 3.2 Add `SentinelGuardStatus` as an immutable Gateway-local DTO for resource name, tenant id, request id, user id, idempotency key, and guard result; verify constructors/factory methods never return `null` and reject invalid required fields.
- [x] 3.3 Add `GatewaySentinelGuardService` to validate request context before Sentinel entry, apply global flow control and tenant parameter-flow control, and map `BlockException` to the shared rate-limit outcome; verify deterministic unit tests cover below-limit success, above-limit global block, tenant A blocked while tenant B succeeds, and missing tenant rejected before quota accounting.
- [x] 3.4 Add a thin Gateway controller endpoint under `/internal/gateway/sentinel/guarded` that delegates to `GatewaySentinelGuardService`; verify controller logic only receives headers and returns the shared response envelope.
- [x] 3.5 Ensure Gateway Sentinel tests reset Sentinel rule managers after each test to prevent global JVM rule leakage; verify repeated `mvn test -pl claw4j-api-gateway -am` runs pass consistently.

## 4. Orchestrator Circuit-Breaker Proof Path

- [x] 4.1 Add `OrchestratorSentinelProperties` as a documented `@ConfigurationProperties` component for Agent-call resource name, error-ratio threshold, minimum request amount, stat interval, and recovery window; verify threshold defaults model `> 50%` error-ratio behavior.
- [x] 4.2 Add `AgentCallGuardStatus` as an immutable Orchestrator-local DTO for resource name, request id, tenant id, fallback state, and result message; verify constructors/factory methods never return `null` and reject invalid required fields.
- [x] 4.3 Add `AgentCallGuardService` to validate request context, protect the proof Agent-call resource, record runtime errors for Sentinel degrade rules, and return an explicit fallback outcome when the circuit is open; verify deterministic tests drive errors above the threshold, observe short-circuit fallback, and confirm protected logic is not executed while open.
- [x] 4.4 Add a thin Orchestrator controller endpoint under `/internal/orchestrator/sentinel/agent-call` that can drive success or simulated failure for smoke testing; verify invalid context is rejected before Sentinel fallback and controller logic only delegates to service.
- [x] 4.5 Ensure Orchestrator Sentinel tests reset Sentinel rule managers after each test to prevent global JVM rule leakage; verify repeated `mvn test -pl claw4j-orchestrator -am` runs pass consistently.

## 5. Documentation and Smoke Tests

- [x] 5.1 Update `DEPLOYMENT.md` with a local Sentinel Dashboard startup section or link that uses a pinned, reproducible runtime option; verify Nacos deployment details remain separate from Sentinel service-call smoke behavior.
- [x] 5.2 Add `SENTINEL_SMOKE_TESTS.md` with setup, Nacos rule DataId publication, Gateway global limit curl checks, Gateway tenant isolation curl checks, Orchestrator error-ratio circuit checks, fallback checks, dashboard visibility checks, rollback, and cleanup; verify it links to `DEPLOYMENT.md` instead of duplicating all Docker setup.
- [x] 5.3 Update `README.md` with a documentation link to the Sentinel smoke tests; verify README remains a project overview and does not absorb deployment or traffic-test details.

## 6. Automated and Live Verification

- [x] 6.1 Run `mvn clean compile -pl claw4j-api-gateway,claw4j-orchestrator -am` and verify Gateway, Orchestrator, and shared reactor dependencies compile.
- [x] 6.2 Run `mvn test -pl claw4j-common,claw4j-api-gateway,claw4j-orchestrator -am` and verify Sentinel-specific and shared contract tests pass.
- [x] 6.3 Run `mvn clean test` from the repository root and verify all module tests pass.
- [x] 6.4 With local Nacos, Sentinel Dashboard, Gateway, and Orchestrator running, execute `SENTINEL_SMOKE_TESTS.md` and verify curl evidence for global limiting, tenant isolation, error-ratio circuit opening, fallback degradation, dynamic rule update, and rollback.
- [x] 6.5 Run `openspec validate "add-sentinel-resilience-guards" --strict` and verify the change artifacts are valid before archive or commit.
