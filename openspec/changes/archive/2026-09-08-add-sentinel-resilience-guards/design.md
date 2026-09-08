## Context

See `proposal.md` for motivation. Claw4J is a Maven multi-module Spring Boot 4.0.0 project using Spring Cloud Alibaba `2025.1.0.0`. The project already has Nacos Discovery, Nacos Config, OpenFeign proof calls, shared request context headers, shared error handling, and a metadata-only `@RateLimit` annotation.

The current code does not yet have production Agent execution logic or Redis-backed quota state. The first Sentinel change should therefore prove the resilience mechanics at stable service boundaries without pretending to solve distributed quota accounting or full Agent workflow governance.

## Goals / Non-Goals

**Goals:**
- Add Sentinel protection to the services that own the first runtime proof paths: Gateway and Orchestrator.
- Prove global QPS limiting and tenant-scoped quota behavior at a Gateway ingress boundary.
- Prove error-ratio circuit breaking and fallback behavior at an Orchestrator Agent-call proof boundary.
- Support Sentinel Dashboard visibility and Nacos-backed dynamic rule sources with documented local defaults.
- Reuse the existing `ApiResponse`, `BusinessException`, `ErrorCode`, and request-header contracts.
- Keep all new code inside approved module package areas.

**Non-Goals:**
- Do not add Sentinel dependencies to Tool Executor, Knowledge Memory, or A2A Broker until those modules own protected Sentinel resources.
- Do not implement Redis-backed, cluster-wide, or cross-instance tenant quota accounting.
- Do not implement authentication, authorization, or production approval workflows for rule changes.
- Do not replace OpenFeign fallback contracts or service discovery behavior.
- Do not build real Agent planning, model invocation, or tool execution workflows in this change.

## Decisions

### Protect concrete boundaries instead of every module

Add `spring-cloud-starter-alibaba-sentinel` only to Gateway and Orchestrator for this change. Gateway owns external ingress pressure, while Orchestrator is the closest current place to model an Agent-call boundary.

Alternative considered: add Sentinel starter to all five runnable modules. Rejected because three modules would receive a runtime dependency without a protected resource or smoke-test evidence.

### Use stable Sentinel resource names

Define explicit resource names for the protected proof paths, such as Gateway ingress and Orchestrator Agent call. Resource names should be constants in the owning service layer and should be reused by local rules, Nacos rule examples, tests, and smoke docs.

Alternative considered: rely only on automatic URL resource names. Rejected because URL-derived names are harder to keep stable across refactors and do not cleanly express tenant parameter limits.

### Keep context validation before Sentinel accounting

Gateway and Orchestrator should validate request id, tenant id, user id, and idempotency key before entering protected logic where those values are required. Missing or blank context should produce `INVALID_REQUEST`; it should not be counted as a tenant quota hit or disguised as a Sentinel fallback.

Alternative considered: let Sentinel block first and validate later. Rejected because tenant quota enforcement must be based on validated tenant identity.

### Apply tenant quotas through parameter flow control

Tenant limits should use Sentinel parameter flow control with `tenantId` as the protected argument. This keeps one resource name while isolating quota by tenant value for the single-instance proof path.

Alternative considered: create one resource per tenant. Rejected because resource cardinality would grow with tenants and make dashboard/rule management noisy.

### Treat this as instance-local limiting

The first implementation should document that Sentinel flow and parameter-flow rules are local to the running service instance unless Sentinel cluster flow control or an external distributed counter is added later.

Alternative considered: promise global tenant quotas across all Gateway instances. Rejected because that needs cluster flow control or centralized state and would exceed the current smoke-test scope.

### Use defensive wrappers for Sentinel block and fallback mapping

Implement protected service methods so Sentinel `BlockException` outcomes are translated into shared Claw4J exceptions or responses. Runtime errors that count toward circuit breaking should be recorded and should not be swallowed.

Alternative considered: return generic strings from block handlers. Rejected because the project requires stable shared response and error contracts.

### Load rules from local defaults and Nacos DataIds

Use local application properties for safe default rule values, and configure Nacos-backed Sentinel datasource entries for flow, parameter-flow, and degrade rules. DataIds should be service-specific and JSON-formatted, for example Gateway flow/param-flow rules and Orchestrator degrade rules.

Alternative considered: only hardcode static rules in Java. Rejected because operational rule changes need to be adjustable without source-code changes.

## Risks / Trade-offs

- [Risk] Local Sentinel rule managers are JVM-global and can leak test state. -> Mitigation: tests must reset flow, parameter-flow, and degrade rules after each case.
- [Risk] Nacos dynamic rule syntax is easy to publish incorrectly. -> Mitigation: provide copyable JSON examples in the smoke-test document and validate that invalid local config falls back safely.
- [Risk] Dashboard visibility can be mistaken for enforcement. -> Mitigation: smoke tests must prove actual blocked responses, not only dashboard registration.
- [Risk] Error-ratio circuit tests can become timing-sensitive. -> Mitigation: keep local thresholds small, recovery windows short, and tests scoped to deterministic service methods.
- [Risk] Tenant limits are not distributed across Gateway instances. -> Mitigation: document instance-local semantics and leave cluster flow control or Redis quotas for a later proposal.

## Migration Plan

1. Add Sentinel dependencies and configuration to Gateway and Orchestrator only.
2. Add shared rate-limit and circuit-open error contracts if the existing catalog cannot express them clearly.
3. Add Gateway ingress proof path with global and tenant flow protection.
4. Add Orchestrator Agent-call proof path with error-ratio circuit breaking and fallback behavior.
5. Add static contract tests and deterministic Sentinel rule tests that do not require Dashboard or Nacos.
6. Add smoke documentation for Sentinel Dashboard startup, Nacos rule publication, curl traffic, circuit opening, fallback, and cleanup.
7. Roll back by removing or lowering traffic to the protected proof endpoints, then removing the Sentinel rule DataIds if dynamic rules were published.
