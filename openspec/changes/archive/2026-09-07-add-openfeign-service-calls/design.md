## Context

See `proposal.md` for motivation. The repository already has five independently runnable Spring Boot services, stable Nacos registration names, and a common library for shared response/error contracts. There are no business controllers yet, so this change should prove the service-to-service RPC pattern with minimal internal endpoints rather than inventing a full orchestration workflow.

Current shape:

```text
+--------------------------+        Nacos         +--------------------------+
| claw4j-api-gateway       |  service discovery   | claw4j-orchestrator      |
| :8080                    | ------------------>  | :8081                    |
| calls orchestrator       |                      | exposes internal probe   |
+--------------------------+                      | calls a2a broker         |
                                                  +------------+-------------+
                                                               |
                                                               v
                                                  +--------------------------+
                                                  | claw4j-a2a-broker        |
                                                  | :8084                    |
                                                  | exposes internal probe   |
                                                  +--------------------------+
```

The current package rule allows `controller`, `service`, `repository`, `config`, `dto`, `entity`, `exception`, `util`, `annotation`, and `constant`. The user approved adding `client` if it is justified as an industry-standard HTTP client boundary, so the implementation should update `agents.md` before creating module-local `client` package directories.

## Goals / Non-Goals

**Goals:**

- Establish the approved `client` package as the home for Feign client interfaces and their fallback implementations.
- Use service names `claw4j-orchestrator` and `claw4j-a2a-broker` for internal calls, relying on Nacos and Spring Cloud LoadBalancer for routing.
- Make degradation explicit with one fallback per Feign client and no bare Feign interfaces.
- Bound latency with configured connect/read timeouts and conservative retry defaults.
- Preserve the existing module boundary: services depend on `claw4j-common`, not sibling service modules.

**Non-Goals:**

- Do not implement full agent orchestration, A2A protocol behavior, authentication, gateway route predicates, gray traffic policy, Redis state, model calls, or tool execution.
- Do not create a shared service-client module.
- Do not put provider-specific business logic in controllers beyond minimal request/response handling for the internal proof path.
- Do not hardcode downstream hosts, ports, secrets, tenant values, or timeout magic numbers in Java code.

## Decisions

### Decision: Add `client` as an approved package category

Feign clients are neither controllers nor business services; they are outbound HTTP adapters. A `client` package makes this boundary obvious, keeps fallback classes close to the interface they implement, and matches common Spring Cloud practice.

Alternative considered: place Feign interfaces under `service`. That avoids a package-rule change but blurs outbound adapter code with business orchestration services and makes fallbacks look like core service logic.

### Decision: Add Spring Cloud dependency management explicitly

The root POM should add a `spring-cloud.version` property and import `org.springframework.cloud:spring-cloud-dependencies` so OpenFeign and LoadBalancer artifacts are version-governed centrally. The existing Spring Cloud Alibaba BOM should remain for Nacos and Sentinel-related artifacts.

Alternative considered: rely on Spring Cloud Alibaba dependency management for all Spring Cloud artifacts. The local dependency metadata shows Alibaba manages its own starters and Sentinel circuit breaker, while Spring Cloud OpenFeign is governed by the Spring Cloud release train.

### Decision: Add Feign only to caller modules

`claw4j-api-gateway` and `claw4j-orchestrator` should receive OpenFeign dependencies and `@EnableFeignClients`. `claw4j-a2a-broker` only needs to expose the minimal provider endpoint for this change.

Alternative considered: add OpenFeign to every runnable service. That would be convenient for later work but violates the proposal's narrow call graph and increases dependency surface before each service needs a client.

### Decision: Make LoadBalancer support explicit

Caller modules should explicitly include load-balancer support or have a contract test proving it is present. OpenFeign and Nacos starter metadata mark load-balancer artifacts as optional, so relying on incidental transitive behavior would weaken the load-balancing acceptance criterion.

Alternative considered: assume discovery starter transitivity is enough. That keeps POMs shorter but risks a Feign client resolving a service name without an actual load-balanced client in the runtime graph.

### Decision: Use Spring Cloud CircuitBreaker with Sentinel implementation for fallback

The project already uses Spring Cloud Alibaba, and its BOM manages `spring-cloud-circuitbreaker-sentinel`. Feign circuit breaker support should be enabled by configuration, with each `@FeignClient` declaring `fallback = ...` and each fallback registered as a Spring bean.

Alternative considered: use Resilience4j. It is a strong generic Spring Cloud choice, but Sentinel better matches the existing Alibaba stack and the user's engineering-defense angle around degradation.

### Decision: Keep the proof contract minimal and internal

Orchestrator and A2A Broker should expose small internal status/probe endpoints that return a shared response envelope and enough data to prove which service instance responded. Gateway and Orchestrator service-layer adapters can call those endpoints through Feign.

Alternative considered: define real orchestration and A2A business APIs in this change. That would make the demo richer but would force domain decisions unrelated to OpenFeign, fallback, and load balancing.

### Decision: Fail closed on fallback

Fallback implementations should throw a `BusinessException` or return a clearly degraded envelope supported by the existing common error model. They must not return `null`, swallow causes silently, or fake a successful downstream result.

Alternative considered: return a default successful payload. That improves demo ergonomics but hides downstream failure and undermines defensive observability.

### Decision: Disable retries by default

The default retry policy should avoid retrying internal calls unless a call is documented as idempotent. Retrying non-idempotent orchestration calls can duplicate side effects once real agent/tool flows are added.

Alternative considered: configure a small fixed retry count globally. That may smooth transient failures, but it is unsafe before idempotency keys and side-effect semantics are fully defined.

## Risks / Trade-offs

- [Risk] OpenFeign 5.x, Spring Boot 4.0.0, and Spring Cloud Alibaba 2025.1.0.0 compatibility may expose dependency-management gaps. -> Mitigation: add Spring Cloud BOM governance, compile caller modules early, and include dependency-tree checks for OpenFeign, LoadBalancer, and Sentinel circuit breaker.
- [Risk] Minimal probe endpoints could be mistaken for final business APIs. -> Mitigation: name and document them as internal proof endpoints and avoid adding domain behavior beyond the RPC contract.
- [Risk] Adding `client` weakens the "no new directories" rule if not governed. -> Mitigation: update `agents.md` first, list every new package directory in `tasks.md`, and forbid other new package names.
- [Risk] Fallback tests can pass without exercising actual Nacos routing. -> Mitigation: combine static/contract tests with a live smoke path that starts multiple downstream instances and observes repeated calls.
- [Risk] Fallback that throws common exceptions may surface HTTP 500 rather than a domain-specific unavailable code. -> Mitigation: add or reuse a stable common error code for downstream service unavailability and assert sanitized response shape.

## Migration Plan

1. Update package governance so `client` is an approved module-local subpackage for outbound HTTP client boundaries.
2. Add Spring Cloud release-train dependency management in the parent POM while preserving existing Alibaba BOM governance.
3. Add OpenFeign, LoadBalancer support, and Sentinel circuit-breaker support to caller modules only.
4. Enable Feign clients in Gateway and Orchestrator startup classes with package scanning scoped to each module.
5. Add minimal internal provider endpoints in Orchestrator and A2A Broker, keeping controller logic thin and delegating response creation to service classes where needed.
6. Add Gateway and Orchestrator Feign clients with explicit fallbacks, bounded timeout configuration, disabled retries by default, and request-context propagation.
7. Add focused tests for dependency presence, package compliance, configuration, fallback behavior, no sibling service Maven dependencies, and no hardcoded downstream addresses.
8. Run module and root compile/test verification, then perform a live Nacos smoke test with at least two downstream instances for load-balancing evidence.

Rollback is straightforward before business APIs depend on these clients: remove the Feign dependencies/configuration, client/fallback classes, minimal internal proof endpoints, and the `client` package rule addition.
