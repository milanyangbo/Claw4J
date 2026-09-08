## 0. Scope Guardrails

- [x] 0.1 Modify only these existing files for dependency/config/docs work: `claw4j-api-gateway/pom.xml`, `claw4j-orchestrator/pom.xml`, `claw4j-tool-executor/pom.xml`, `claw4j-knowledge-memory/pom.xml`, `claw4j-a2a-broker/pom.xml`, `claw4j-api-gateway/src/main/resources/application.yml`, `claw4j-orchestrator/src/main/resources/application.yml`, `claw4j-tool-executor/src/main/resources/application.yml`, `claw4j-knowledge-memory/src/main/resources/application.yml`, `claw4j-a2a-broker/src/main/resources/application.yml`, `README.md`, and `NACOS_CONFIG_SMOKE_TESTS.md`; verify `git diff --name-only` contains no other docs/config/dependency files.
- [x] 0.2 Add only these Gateway proof-path Java files: `claw4j-api-gateway/src/main/java/com/claw4j/gateway/config/GatewayDynamicConfigProperties.java`, `claw4j-api-gateway/src/main/java/com/claw4j/gateway/dto/DynamicConfigStatus.java`, `claw4j-api-gateway/src/main/java/com/claw4j/gateway/service/GatewayDynamicConfigService.java`, and `claw4j-api-gateway/src/main/java/com/claw4j/gateway/controller/GatewayDynamicConfigController.java`; verify the only new Gateway package directories are approved `config` and `dto`.
- [x] 0.3 Add only these dynamic-config test files: `claw4j-api-gateway/src/test/java/com/claw4j/gateway/NacosDynamicConfigContractTest.java`, `claw4j-orchestrator/src/test/java/com/claw4j/orchestrator/NacosDynamicConfigContractTest.java`, `claw4j-tool-executor/src/test/java/com/claw4j/tool/NacosDynamicConfigContractTest.java`, `claw4j-knowledge-memory/src/test/java/com/claw4j/knowledge/NacosDynamicConfigContractTest.java`, and `claw4j-a2a-broker/src/test/java/com/claw4j/a2a/NacosDynamicConfigContractTest.java`; verify no other test packages or source roots are created.
- [x] 0.4 Keep out of scope: `claw4j-common/**`, real Redis-backed rate limiting, authentication, Nacos Discovery behavior changes, OpenFeign client behavior changes, gateway route predicates, production config approval workflows, and new Maven modules; verify no matching files are changed.

## 1. Nacos Config Dependencies and Imports

- [x] 1.1 Add `spring-cloud-starter-alibaba-nacos-config` to all five runnable service POMs; verify `mvn -q dependency:tree -pl claw4j-api-gateway,claw4j-orchestrator,claw4j-tool-executor,claw4j-knowledge-memory,claw4j-a2a-broker -Dincludes=com.alibaba.cloud:spring-cloud-starter-alibaba-nacos-config` lists the dependency for each service.
- [x] 1.2 Add optional Nacos Config imports to all five service `application.yml` files using shared YAML DataId followed by a service-specific YAML DataId; verify import order lets service-specific values override shared values and no `bootstrap.yml` or `bootstrap.properties` file exists.
- [x] 1.3 Add environment-overridable Nacos Config server address, namespace, group, prefix, file extension, DataId, refresh, and timeout settings that default to the existing local Nacos discovery conventions; verify static tests confirm defaults for `127.0.0.1:8848`, `public`, `CLAW4J_DEV_GROUP`, `claw4j-shared.yaml`, `${spring.application.name}.yaml`, YAML file extension, and refresh enabled.
- [x] 1.4 Keep local default values for the Gateway smoke-test threshold and demo message in `application.yml`; verify the Gateway can resolve defaults when remote config is absent.

## 2. Gateway Dynamic Config Proof Path

- [x] 2.1 Add a refresh-aware Gateway `@ConfigurationProperties` component for the smoke-test threshold and demo message; verify the class has Javadoc, uses approved package `com.claw4j.gateway.config`, avoids field injection, and does not fail startup for blank raw values.
- [x] 2.2 Add `DynamicConfigStatus` as an immutable Gateway-local DTO for effective value, demo message, fallback state, and invalid-key detail; verify factory/constructor behavior rejects invalid threshold output and never returns `null`.
- [x] 2.3 Add `GatewayDynamicConfigService` to parse, bound, and fallback invalid threshold values; verify non-numeric, zero, negative, and overly large values report invalid status while using the last valid or local default value.
- [x] 2.4 Add a thin Gateway controller endpoint under `/internal/gateway/config/dynamic` that delegates to `GatewayDynamicConfigService`; verify controller logic only receives the request and returns the shared `ApiResponse` envelope.

## 3. Automated Verification

- [x] 3.1 Add dynamic-config contract tests for all five runnable modules that verify POM dependencies, optional config imports, config namespace/group defaults, refresh settings, and absence of bootstrap files.
- [x] 3.2 Add Gateway tests that verify default threshold resolution, `@ConfigurationProperties` binding contract, invalid-value fallback, simplified status DTO contents, and controller/service separation; verify the tests do not require a running Nacos server.
- [x] 3.3 Verify `claw4j-common` remains free of Nacos Config dependencies by inspecting `claw4j-common/pom.xml` and running a dependency-tree check scoped to `com.alibaba.cloud:spring-cloud-starter-alibaba-nacos-config`.
- [x] 3.4 Run `mvn clean compile -pl claw4j-api-gateway,claw4j-orchestrator,claw4j-tool-executor,claw4j-knowledge-memory,claw4j-a2a-broker -am` and verify all runnable services compile with reactor dependencies.
- [x] 3.5 Run `mvn clean test` from the repository root and verify all module tests pass.

## 4. Documentation

- [x] 4.1 Add `NACOS_CONFIG_SMOKE_TESTS.md` with setup, Nacos Config publish/update/rollback instructions, curl checks for the Gateway proof endpoint, invalid-value fallback checks, and cleanup; verify it links to `DEPLOYMENT.md` instead of duplicating Docker setup.
- [x] 4.2 Update `README.md` with a documentation link to the Nacos Config smoke tests; verify README remains a project overview and does not absorb deployment or service-call test details.

## 5. Live Smoke Verification

- [x] 5.1 With local Nacos running, publish the shared and Gateway service-specific config DataIds, start Gateway, and verify `curl http://127.0.0.1:8080/internal/gateway/config/dynamic` shows the remote Gateway threshold.
- [x] 5.2 Change the Gateway threshold in Nacos Config without restarting Gateway and verify repeated curl calls show the refreshed value.
- [x] 5.3 Publish an invalid Gateway threshold value and verify Gateway stays running, reports the key as invalid, and uses the previous valid or local default threshold.
- [x] 5.4 Restore the previous Gateway threshold in Nacos Config and verify curl shows the rolled-back value without restarting Gateway.
- [x] 5.5 Run `openspec validate "add-nacos-dynamic-config" --strict` and verify the change artifacts are valid.
