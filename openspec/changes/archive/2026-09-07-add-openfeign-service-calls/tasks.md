## 0. Implementation Scope Guardrail

- [x] 0.1 Modify only files listed in this `tasks.md`; verify `git diff --name-only` contains no unlisted implementation file before committing.
- [x] 0.2 Update only `agents.md` for package-governance changes and verify the approved subpackage list includes `client` with a short rationale.
- [x] 0.3 Update only root `pom.xml`, `claw4j-api-gateway/pom.xml`, and `claw4j-orchestrator/pom.xml` for build changes; verify child dependencies declare no local versions.
- [x] 0.4 Update only `claw4j-common/src/main/java/com/claw4j/common/constant/CommonConstants.java`, `claw4j-common/src/main/java/com/claw4j/common/exception/ErrorCode.java`, `claw4j-common/src/main/java/com/claw4j/common/dto/ApiResponse.java`, `claw4j-common/src/main/java/com/claw4j/common/dto/InternalServiceStatus.java`, and `claw4j-common/src/test/java/com/claw4j/common/CommonFoundationContractTest.java` for shared contracts; verify no runtime-service behavior is added to `claw4j-common`.
- [x] 0.5 Update only these Gateway files for the caller proof path: `claw4j-api-gateway/src/main/java/com/claw4j/gateway/GatewayApplication.java`, `claw4j-api-gateway/src/main/resources/application.yml`, `claw4j-api-gateway/src/main/java/com/claw4j/gateway/client/OrchestratorClient.java`, `claw4j-api-gateway/src/main/java/com/claw4j/gateway/client/OrchestratorClientFallback.java`, `claw4j-api-gateway/src/main/java/com/claw4j/gateway/client/GatewayFeignRequestContextInterceptor.java`, `claw4j-api-gateway/src/main/java/com/claw4j/gateway/controller/GatewayInternalCallController.java`, `claw4j-api-gateway/src/main/java/com/claw4j/gateway/service/OrchestratorGatewayService.java`, and `claw4j-api-gateway/src/test/java/com/claw4j/gateway/OpenFeignServiceCallContractTest.java`; verify the only new Gateway package directory is `client`.
- [x] 0.6 Update only these Orchestrator files for provider and caller proof paths: `claw4j-orchestrator/src/main/java/com/claw4j/orchestrator/OrchestratorApplication.java`, `claw4j-orchestrator/src/main/resources/application.yml`, `claw4j-orchestrator/src/main/java/com/claw4j/orchestrator/client/A2aBrokerClient.java`, `claw4j-orchestrator/src/main/java/com/claw4j/orchestrator/client/A2aBrokerClientFallback.java`, `claw4j-orchestrator/src/main/java/com/claw4j/orchestrator/client/OrchestratorFeignRequestContextInterceptor.java`, `claw4j-orchestrator/src/main/java/com/claw4j/orchestrator/controller/OrchestratorInternalCallController.java`, `claw4j-orchestrator/src/main/java/com/claw4j/orchestrator/service/OrchestratorStatusService.java`, `claw4j-orchestrator/src/main/java/com/claw4j/orchestrator/service/A2aBrokerGatewayService.java`, and `claw4j-orchestrator/src/test/java/com/claw4j/orchestrator/OpenFeignServiceCallContractTest.java`; verify the only new Orchestrator package directory is `client`.
- [x] 0.7 Update only these A2A Broker files for the provider proof path: `claw4j-a2a-broker/src/main/java/com/claw4j/a2a/controller/A2aInternalCallController.java`, `claw4j-a2a-broker/src/main/java/com/claw4j/a2a/service/A2aBrokerStatusService.java`, and `claw4j-a2a-broker/src/test/java/com/claw4j/a2a/OpenFeignServiceCallContractTest.java`; verify no new A2A Broker package directory is created.
- [x] 0.8 Keep out of scope: Tool Executor, Knowledge Memory, real agent orchestration, real A2A protocol behavior, authentication, Redis, model calls, gateway route predicates, gray traffic policy, and any new Maven module; verify no matching files are changed.

## 1. Package Governance and Dependency Management

- [x] 1.1 Add `client` to the approved subpackage list in `agents.md` with rationale that it is reserved for outbound HTTP client boundaries; verify the file still forbids non-standard directories.
- [x] 1.2 Add a root `spring-cloud.version` property and import `org.springframework.cloud:spring-cloud-dependencies` in root `pom.xml`; verify `mvn -q help:effective-pom -DskipTests` shows OpenFeign managed by Spring Cloud dependency management.
- [x] 1.3 Add OpenFeign, explicit LoadBalancer support, and Sentinel circuit-breaker support to `claw4j-api-gateway/pom.xml`; verify `mvn -q dependency:tree -pl claw4j-api-gateway -Dincludes=org.springframework.cloud:spring-cloud-starter-openfeign,org.springframework.cloud:spring-cloud-starter-loadbalancer,com.alibaba.cloud:spring-cloud-circuitbreaker-sentinel` lists all expected dependencies.
- [x] 1.4 Add OpenFeign, explicit LoadBalancer support, and Sentinel circuit-breaker support to `claw4j-orchestrator/pom.xml`; verify `mvn -q dependency:tree -pl claw4j-orchestrator -Dincludes=org.springframework.cloud:spring-cloud-starter-openfeign,org.springframework.cloud:spring-cloud-starter-loadbalancer,com.alibaba.cloud:spring-cloud-circuitbreaker-sentinel` lists all expected dependencies.
- [x] 1.5 Verify `claw4j-api-gateway` and `claw4j-orchestrator` still do not declare sibling service-module dependencies by running `mvn -q dependency:tree -pl claw4j-api-gateway,claw4j-orchestrator -Dincludes=com.claw4j`.

## 2. Shared Defensive Contracts

- [x] 2.1 Add shared header constants for tenant, user, and idempotency context to `CommonConstants.java`; verify constants are non-empty and existing request-id behavior remains unchanged through `CommonFoundationContractTest`.
- [x] 2.2 Add a stable downstream-unavailable error code to `ErrorCode.java`; verify the code has an external-safe message and an appropriate HTTP status through `CommonFoundationContractTest`.
- [x] 2.3 Add `InternalServiceStatus` as an immutable shared DTO with service name, role, instance port, and request identifier fields; verify constructor/factory behavior rejects blank required fields and never returns `null`.
- [x] 2.4 Extend `CommonFoundationContractTest` for the new constants, error code, and DTO; verify `mvn test -pl claw4j-common` passes.

## 3. Provider Proof Endpoints

- [x] 3.1 Add an Orchestrator service method that builds an `InternalServiceStatus` response from configured service metadata; verify it validates inbound request context and uses constants instead of magic strings.
- [x] 3.2 Add a thin Orchestrator controller endpoint for Gateway's internal status call; verify controller logic only receives parameters, delegates to service, and returns the shared `ApiResponse` envelope.
- [x] 3.3 Add an A2A Broker service method that builds an `InternalServiceStatus` response from configured service metadata; verify it validates inbound request context and uses constants instead of magic strings.
- [x] 3.4 Add a thin A2A Broker controller endpoint for Orchestrator's internal status call; verify controller logic only receives parameters, delegates to service, and returns the shared `ApiResponse` envelope.
- [x] 3.5 Add provider-side contract tests for Orchestrator and A2A Broker endpoints; verify invalid context returns a standardized invalid-request outcome and valid context returns service identity data.

## 4. Gateway to Orchestrator Feign Call

- [x] 4.1 Enable Feign client scanning in `GatewayApplication.java` scoped to `com.claw4j.gateway.client`; verify discovery enablement and existing Javadoc remain intact.
- [x] 4.2 Add `OrchestratorClient` under `com.claw4j.gateway.client` using service name `claw4j-orchestrator` and explicit `fallback`; verify no hardcoded host or port appears in the client.
- [x] 4.3 Add `OrchestratorClientFallback` as a Spring bean that throws or returns a standardized downstream-unavailable degraded outcome; verify it never returns `null` and does not swallow exceptions silently.
- [x] 4.4 Add `GatewayFeignRequestContextInterceptor` to propagate request id, tenant id, user id, and idempotency key when present; verify missing required context is rejected before downstream invocation.
- [x] 4.5 Add `OrchestratorGatewayService` to validate inbound context and call `OrchestratorClient`; verify service logic is outside the controller and uses configured client behavior.
- [x] 4.6 Add a thin Gateway controller endpoint to trigger the Orchestrator proof call; verify it delegates to `OrchestratorGatewayService` and returns a shared response envelope.
- [x] 4.7 Add Gateway contract tests for Feign annotation shape, fallback declaration, timeout configuration, context propagation, no hardcoded downstream address, and controller/service separation; verify `mvn test -pl claw4j-api-gateway -am` passes.

## 5. Orchestrator to A2A Broker Feign Call

- [x] 5.1 Enable Feign client scanning in `OrchestratorApplication.java` scoped to `com.claw4j.orchestrator.client`; verify discovery enablement and existing Javadoc remain intact.
- [x] 5.2 Add `A2aBrokerClient` under `com.claw4j.orchestrator.client` using service name `claw4j-a2a-broker` and explicit `fallback`; verify no hardcoded host or port appears in the client.
- [x] 5.3 Add `A2aBrokerClientFallback` as a Spring bean that throws or returns a standardized downstream-unavailable degraded outcome; verify it never returns `null` and does not swallow exceptions silently.
- [x] 5.4 Add `OrchestratorFeignRequestContextInterceptor` to propagate request id, tenant id, user id, and idempotency key when present; verify missing required context is rejected before downstream invocation.
- [x] 5.5 Add `A2aBrokerGatewayService` to validate inbound context and call `A2aBrokerClient`; verify service logic is outside the controller and uses configured client behavior.
- [x] 5.6 Add a thin Orchestrator controller endpoint to trigger the A2A Broker proof call; verify it delegates to `A2aBrokerGatewayService` and returns a shared response envelope.
- [x] 5.7 Add Orchestrator contract tests for Feign annotation shape, fallback declaration, timeout configuration, context propagation, no hardcoded downstream address, and controller/service separation; verify `mvn test -pl claw4j-orchestrator -am` passes.

## 6. Timeout, Retry, and Circuit-Breaker Configuration

- [x] 6.1 Add Gateway OpenFeign configuration for `claw4j-orchestrator` with environment-overridable connect timeout and read timeout values; verify configuration contains no Java hardcoded timeout magic numbers.
- [x] 6.2 Add Orchestrator OpenFeign configuration for `claw4j-a2a-broker` with environment-overridable connect timeout and read timeout values; verify configuration contains no Java hardcoded timeout magic numbers.
- [x] 6.3 Enable Feign circuit-breaker support in Gateway and Orchestrator configuration; verify fallbacks are exercised by tests when downstream clients fail.
- [x] 6.4 Disable retries by default for Gateway and Orchestrator internal calls; verify retry configuration is explicit and any future retry enablement requires an idempotency-specific task.

## 7. Verification and Smoke Tests

- [x] 7.1 Run `mvn clean compile -pl claw4j-api-gateway -am` and verify Gateway plus required reactor dependencies compile.
- [x] 7.2 Run `mvn clean compile -pl claw4j-orchestrator -am` and verify Orchestrator plus required reactor dependencies compile.
- [x] 7.3 Run `mvn clean compile -pl claw4j-a2a-broker -am` and verify A2A Broker plus required reactor dependencies compile.
- [x] 7.4 Run `mvn clean test` from the repository root and verify all module tests pass.
- [x] 7.5 Start Nacos, Gateway, Orchestrator, and A2A Broker locally; verify Gateway can call Orchestrator and Orchestrator can call A2A Broker through service names.
- [x] 7.6 Start a second downstream instance with a different port and verify repeated calls can hit more than one healthy instance through service discovery.
- [x] 7.7 Stop Orchestrator and verify Gateway enters fallback degradation; stop A2A Broker and verify Orchestrator enters fallback degradation.
- [x] 7.8 Run `openspec validate "add-openfeign-service-calls" --strict` and verify the change artifacts are valid.
