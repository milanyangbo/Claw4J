## Context

See `proposal.md` for motivation. The repository already has five runnable Spring Boot services with stable names and ports, plus a shared common library that is not a runtime service. The parent POM centrally manages Spring Boot 4.0.0 and Spring Cloud Alibaba 2025.1.0.0, so Nacos discovery should consume the managed Spring Cloud Alibaba starter rather than pinning module-local versions.

Current runtime shape:

```text
+---------------------------+          +------------------+
| claw4j-api-gateway  :8080 |  ----->  | Nacos registry   |
| claw4j-orchestrator :8081 |  ----->  | namespace/group  |
| claw4j-tool-executor:8082 |  ----->  | health metadata  |
| claw4j-knowledge    :8083 |  ----->  +------------------+
| claw4j-a2a-broker   :8084 |
+---------------------------+
```

## Goals / Non-Goals

**Goals:**

- Enable Nacos discovery for the five runnable service modules.
- Keep service names aligned with existing `spring.application.name` values.
- Configure Nacos address, namespace, group, and metadata through environment-overridable properties.
- Expose Actuator health for service-discovery health checks.
- Preserve the existing dependency direction: services may depend on `claw4j-common`, but not on sibling services.

**Non-Goals:**

- Do not make `claw4j-common` register with Nacos or expose a port.
- Do not implement business RPC, Feign clients, gateway route predicates, authentication, rate limiting, gray traffic rules, or fallback logic in this change.
- Do not require a running Nacos server for normal unit-test execution; live registry checks should be a separate smoke verification step.

## Decisions

### Decision: Put discovery dependencies only in runnable services

Each runnable service module should add `spring-cloud-starter-alibaba-nacos-discovery` and `spring-boot-starter-actuator`. `claw4j-common` remains a plain library and should not receive discovery dependencies.

Alternative considered: add discovery dependencies to the parent POM as direct dependencies. That would leak runtime behavior into the common library and weaken module boundaries.

### Decision: Use the existing managed Spring Cloud Alibaba version

The implementation should rely on the parent `spring-cloud-alibaba.version` property and dependency management. Service POMs should not redeclare starter versions.

Alternative considered: pin the Nacos starter in every service POM. That makes version drift more likely and undermines the project's BOM governance rule.

### Decision: Keep discovery configuration local-first and environment-overridable

Each service should use the same configuration shape with safe local defaults: Nacos server `127.0.0.1:8848`, namespace `public`, group `CLAW4J_DEV_GROUP`, and environment `local`. Deployments can override these through environment variables without changing source code.

Alternative considered: hardcode a shared development Nacos address. That would make local development brittle and violates the project's no-hardcoded-environment rule.

### Decision: Keep live Nacos verification manual for this change

Live registry verification should be a smoke-test task against a reachable local or shared Nacos server. This change should not introduce Testcontainers because containerized registry test infrastructure is a separate concern from enabling discovery in the service modules.

Alternative considered: add Testcontainers-based Nacos integration tests immediately. That would improve automation, but it expands the dependency surface and CI requirements before the project has container-test conventions.

### Decision: Add `@EnableDiscoveryClient` explicitly

Although discovery can often be auto-enabled by classpath, the startup classes should add explicit discovery enablement because the user-facing requirement calls it out and it makes the service boundary obvious in interviews and reviews.

Alternative considered: rely only on auto-configuration. That is lighter but hides an important architectural behavior from the module entry point.

### Decision: Treat Gateway to Orchestrator as discovery resolution, not business RPC

This change should prove that Gateway can resolve healthy `claw4j-orchestrator` instances by service name. It should not add Feign or gateway routing yet because fallback, auth, timeout budgets, and route governance need their own proposal.

Alternative considered: add a minimal Gateway route or Feign client immediately. That would satisfy a demo path but would also introduce client resilience requirements that are outside this change.

## Risks / Trade-offs

- [Risk] A developer runs services without Nacos available and sees startup or registration failures. -> Mitigation: use clear local defaults and include a smoke-test task that starts Nacos before live registry verification.
- [Risk] Namespace/group defaults accidentally mix environments. -> Mitigation: make namespace/group explicit in configuration and expose environment metadata for inspection.
- [Risk] Discovery looks enabled but health is not visible enough for operations. -> Mitigation: add Actuator health endpoints and metadata in every runnable service.
- [Risk] A future implementation adds service-to-service Maven dependencies while wiring discovery clients. -> Mitigation: include a dependency-tree verification task scoped to `com.claw4j`.

## Migration Plan

1. Add service-discovery and actuator dependencies to the five runnable service modules.
2. Add explicit discovery enablement to the five startup classes while preserving package ownership.
3. Extend each service `application.yml` with Nacos discovery, metadata, and management health configuration.
4. Add focused tests or static checks that assert each service has the expected discovery dependency and configuration shape.
5. Run Maven validation and a dependency-tree check to confirm module boundaries remain intact.
6. When a Nacos server is available, run a live smoke check for service registration, metadata visibility, service-name lookup, and instance removal.
