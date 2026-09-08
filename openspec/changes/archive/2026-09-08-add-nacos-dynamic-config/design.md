## Context

See `proposal.md` for motivation. Claw4J is a Maven multi-module Spring Boot 4.0.0 project using Spring Cloud Alibaba `2025.1.0.0`. The five runnable modules already register with Nacos Discovery, expose Actuator health, and use local `application.yml` files for service identity, ports, discovery metadata, and Feign settings. There is an existing `@RateLimit` annotation in `claw4j-common`, but no rate-limit enforcement mechanism yet.

Project rules require a proposal before feature code, prohibit unlisted implementation files during apply, require approved package roots, and forbid hardcoded operational values. `config`, `service`, `controller`, and `dto` are approved package areas; `claw4j-common` should remain a shared library unless the task explicitly needs shared contracts.

## Goals / Non-Goals

**Goals:**
- Add Nacos Config support to all five runnable service modules.
- Use the current Spring Cloud Alibaba config import model compatible with the existing BOM.
- Keep local defaults so services can start without a reachable Nacos Config server.
- Provide one Gateway proof path that shows a refreshable threshold can change without restarting the process.
- Document curl-based smoke verification for load, refresh, invalid-value fallback, and rollback.

**Non-Goals:**
- Do not implement Redis-backed or production traffic rate limiting.
- Do not change Nacos Discovery behavior, service ports, Feign clients, or fallback contracts.
- Do not add new Maven modules or sibling service dependencies.
- Do not introduce `bootstrap.yml` or `bootstrap.properties`.
- Do not make `claw4j-common` depend on Nacos Config.

## Decisions

### Use `spring.config.import`, not bootstrap files

Spring Cloud Alibaba 2025.x aligns with Spring Boot's Config Data import flow. Each runnable module should continue using `application.yml` and add optional Nacos imports there. This keeps configuration loading compatible with the current BOM and avoids adding legacy bootstrap files that no longer match the platform direction.

Alternative considered: add `bootstrap.yml` to every service. Rejected because it increases version drift risk and conflicts with the current config import model.

### Apply Nacos Config to runnable services only

Add `spring-cloud-starter-alibaba-nacos-config` to `claw4j-api-gateway`, `claw4j-orchestrator`, `claw4j-tool-executor`, `claw4j-knowledge-memory`, and `claw4j-a2a-broker`. Do not add it to `claw4j-common`, because common has no application lifecycle and should remain reusable without Nacos runtime dependencies.

Alternative considered: centralize config helper code in common. Rejected for this change because the first proof path is service-local and shared abstractions would be premature before real consumers exist.

### Layer shared and service-specific DataIds

Use two optional imports per runnable service:
- shared defaults such as `claw4j-shared.yaml`
- service-specific values such as `${spring.application.name}.yaml`

Service-specific config should override shared config. Namespace, group, prefix, file extension, and DataId names should be environment-overridable with local defaults, and config group should default to the existing discovery group unless explicitly overridden. This preserves environment isolation while keeping local smoke tests simple.

Alternative considered: one DataId per service only. Rejected because common knobs would be copied across five service configs.

### Prove hot refresh through a Gateway config endpoint

Create a narrow Gateway proof path that exposes only a smoke-test threshold, a demo message, fallback status, and the invalid key when fallback is active. Bind those demo values with `@ConfigurationProperties`, put parsing and fallback behavior in service logic, and keep the controller thin. Use a refresh-aware Spring bean for the current values so a Nacos change can be observed without process restart.

The proof path should not enforce request rate limiting. It only proves that a runtime threshold can be loaded, refreshed, rejected when invalid, and rolled back. A later dedicated rate-limit change can wire this threshold into real traffic control.

Alternative considered: implement a full rate limiter now. Rejected because Redis keys, idempotency, tenant isolation, and enforcement policy are broader than this config feature.

### Treat bad remote values as data, not startup blockers

Optional Nacos imports cover unavailable config centers and missing DataIds. For values used in the proof path, prefer string/raw binding plus explicit parsing and bounds checks in a service rather than fail-fast bean validation that can block application startup or refresh. The service should report whether the raw threshold is valid and which fallback value is effective.

Alternative considered: use strict `@ConfigurationProperties` validation. Rejected for the refresh proof path because the acceptance criteria require bad config to avoid startup failure.

## Risks / Trade-offs

- [Risk] A malformed Nacos file can still break config loading before application code sees it. -> Mitigation: document smoke tests for malformed value content, keep imports optional, and use YAML DataIds that match the project's `application.yml` style.
- [Risk] A refresh event might update the environment but not the object used by the endpoint. -> Mitigation: place `@RefreshScope` on the narrow proof component and add a contract test or smoke step that confirms value changes without restart.
- [Risk] Operators may confuse the smoke threshold with production rate limiting. -> Mitigation: name the endpoint and properties as smoke/proof configuration and state that no traffic enforcement is implemented.
- [Risk] Shared and service-specific DataId precedence can be misunderstood. -> Mitigation: add static tests for import order and smoke docs that change shared and service-specific values separately.
- [Risk] Local tests should not require Nacos. -> Mitigation: unit/static contract tests verify dependencies, optional imports, defaults, and fallback parsing; live Nacos checks remain in a separate smoke-test document.

## Migration Plan

1. Add dependencies and optional config imports to the five runnable services.
2. Add Gateway's refreshable proof component, status DTO, service, and thin controller endpoint.
3. Add static and unit tests for dependency presence, no bootstrap files, import order, safe defaults, `@ConfigurationProperties` binding, invalid-value fallback, and Gateway controller/service separation.
4. Add `NACOS_CONFIG_SMOKE_TESTS.md` and link it from `README.md`.
5. Deploy by publishing Nacos DataIds in the target namespace/group, then starting services with the desired environment overrides.
6. Roll back by restoring the previous Nacos config value or removing the optional DataId so local defaults take over.
