## 0. Implementation Scope Guardrail

- [x] 0.1 Modify only the files listed in this `tasks.md`; if implementation requires any additional file, update this planning artifact first and explain the reason before writing code.
- [x] 0.2 Allowed implementation files for service dependencies:
  - `claw4j-api-gateway/pom.xml`
  - `claw4j-orchestrator/pom.xml`
  - `claw4j-tool-executor/pom.xml`
  - `claw4j-knowledge-memory/pom.xml`
  - `claw4j-a2a-broker/pom.xml`
- [x] 0.3 Allowed implementation files for service bootstrap:
  - `claw4j-api-gateway/src/main/java/com/claw4j/gateway/GatewayApplication.java`
  - `claw4j-orchestrator/src/main/java/com/claw4j/orchestrator/OrchestratorApplication.java`
  - `claw4j-tool-executor/src/main/java/com/claw4j/tool/ToolExecutorApplication.java`
  - `claw4j-knowledge-memory/src/main/java/com/claw4j/knowledge/KnowledgeMemoryApplication.java`
  - `claw4j-a2a-broker/src/main/java/com/claw4j/a2a/A2aBrokerApplication.java`
- [x] 0.4 Allowed implementation files for service configuration:
  - `claw4j-api-gateway/src/main/resources/application.yml`
  - `claw4j-orchestrator/src/main/resources/application.yml`
  - `claw4j-tool-executor/src/main/resources/application.yml`
  - `claw4j-knowledge-memory/src/main/resources/application.yml`
  - `claw4j-a2a-broker/src/main/resources/application.yml`
- [x] 0.5 Allowed implementation files for tests or static checks:
  - `claw4j-api-gateway/src/test/java/com/claw4j/gateway/DiscoveryConfigurationContractTest.java`
  - `claw4j-orchestrator/src/test/java/com/claw4j/orchestrator/DiscoveryConfigurationContractTest.java`
  - `claw4j-tool-executor/src/test/java/com/claw4j/tool/DiscoveryConfigurationContractTest.java`
  - `claw4j-knowledge-memory/src/test/java/com/claw4j/knowledge/DiscoveryConfigurationContractTest.java`
  - `claw4j-a2a-broker/src/test/java/com/claw4j/a2a/DiscoveryConfigurationContractTest.java`
- [x] 0.6 Allowed documentation files:
  - `README.md`
- [x] 0.7 Out of scope for this change: `claw4j-common/**`, root `pom.xml`, `target/**`, business controllers/services, Feign clients, gateway routes, authentication, rate limiting, gray traffic policy, and any new Maven module.

## 1. Discovery Dependencies

- [x] 1.1 Add `spring-cloud-starter-alibaba-nacos-discovery` and `spring-boot-starter-actuator` to `claw4j-api-gateway`, `claw4j-orchestrator`, `claw4j-tool-executor`, `claw4j-knowledge-memory`, and `claw4j-a2a-broker`; verify `mvn validate -pl claw4j-api-gateway,claw4j-orchestrator,claw4j-tool-executor,claw4j-knowledge-memory,claw4j-a2a-broker -am` succeeds.
- [x] 1.2 Verify `claw4j-common` remains a shared library without Nacos discovery or actuator runtime dependencies by inspecting `claw4j-common/pom.xml` and running a dependency-tree check scoped to discovery artifacts.
- [x] 1.3 Verify service modules still declare no direct Maven dependencies on sibling service modules by running a dependency-tree check scoped to `com.claw4j`.

## 2. Service Bootstrap

- [x] 2.1 Add explicit Spring Cloud discovery enablement to the five service startup classes; verify every startup class remains under its approved module package root.
- [x] 2.2 Preserve existing class-level Javadocs and public `main` method Javadocs while adding discovery annotations.
- [x] 2.3 Compile each runnable service with its reactor dependencies using `mvn clean compile -pl <service-module> -am` for all five service modules.

## 3. Nacos Configuration

- [x] 3.1 Extend all five service `application.yml` files with Nacos discovery configuration using environment-overridable `server-addr`, `namespace`, `group`, and metadata values; verify a static configuration check confirms default server `127.0.0.1:8848`, default namespace `public`, default group `CLAW4J_DEV_GROUP`, and default environment `local`.
- [x] 3.2 Preserve existing `spring.application.name` and `server.port` values for all five services; verify service names and ports still match `claw4j-api-gateway:8080`, `claw4j-orchestrator:8081`, `claw4j-tool-executor:8082`, `claw4j-knowledge-memory:8083`, and `claw4j-a2a-broker:8084`.
- [x] 3.3 Add discovery metadata values for `module`, `role`, `port`, and `environment` to all five services; verify metadata remains environment-overridable where appropriate.
- [x] 3.4 Add Actuator health exposure configuration for all five services; verify each service configuration exposes health information without changing business endpoints.

## 4. Contract Tests and Static Checks

- [x] 4.1 Add focused service-discovery contract tests or static checks only in the allowed test files that verify each runnable service declares the required discovery dependencies and configuration metadata.
- [x] 4.2 Verify Gateway and Orchestrator share the same default Nacos namespace and group so Gateway-side discovery can resolve `claw4j-orchestrator` by service name when both are healthy.
- [x] 4.3 Verify package paths comply with AGENTS.md: no code outside `com.claw4j.gateway`, `com.claw4j.orchestrator`, `com.claw4j.tool`, `com.claw4j.knowledge`, or `com.claw4j.a2a`.
- [x] 4.4 Run `mvn clean test` from the repository root and verify the full test lifecycle passes.

## 5. Live Smoke and OpenSpec Validation

- [x] 5.1 With a Nacos server reachable at the configured address, start `claw4j-api-gateway` and verify Nacos shows service `claw4j-api-gateway` on port `8080` with module, role, port, and environment metadata.
- [x] 5.2 With a Nacos server reachable at the configured address, start `claw4j-orchestrator` and verify Gateway-side discovery can resolve healthy service name `claw4j-orchestrator` in the same namespace and group.
- [x] 5.3 Stop a registered service instance and verify Nacos removes it or marks it unavailable so healthy discovery results no longer include it.
- [x] 5.4 Run `mvn clean compile` from the repository root and verify all six modules compile.
- [x] 5.5 Run `openspec validate "add-nacos-service-discovery" --strict` and verify the change artifacts remain valid.
- [x] 5.6 Document a Docker-based local Nacos standalone setup in `README.md`, including start, health check, service registration verification, and stop commands.
