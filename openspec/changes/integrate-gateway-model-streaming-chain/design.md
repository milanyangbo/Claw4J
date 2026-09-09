## Context

See `proposal.md` for motivation. The current codebase already has Gateway-to-Orchestrator Feign calls, MVC SSE streaming, model output parsing, context adaptation, stream resume buffering, Sentinel proof endpoints, and Resilience4j model failover. The design problem is that these pieces are still scattered across proof/demo paths and production configuration, so the next implementation must converge them into one Gateway-facing stream path and delete the production proof code.

Current target shape:

```text
+------------------+
| Browser / curl   |
+--------+---------+
         |
         | GET /ai/chat?query=... or POST /api/model/stream
         v
+--------+---------+
| claw4j-api-gateway |
| - create/validate context
| - Sentinel ingress quota
| - Feign + LoadBalancer
+--------+---------+
         |
         v
+--------+---------+
| claw4j-orchestrator |
| - context budget
| - DeepSeek primary
| - Resilience4j guard
| - Qwen fallback resume
| - output parser
+--------+---------+
         |
         v
+------------------+
| SSE response     |
+------------------+
```

## Goals / Non-Goals

**Goals:**
- Make the browser/Gateway model streaming path the primary end-to-end runtime behavior.
- Keep Sentinel only in `claw4j-api-gateway` for ingress protection and Dashboard visibility, while avoiding project-owned Sentinel rule datasource/configuration blocks in this change.
- Keep model-provider timeout, fallback, circuit-open, and half-open recovery in `claw4j-orchestrator` under Resilience4j.
- Remove production proof/demo code after the main chain works, including standalone proof endpoints and simulated request fields.
- Preserve official Spring AI / Spring AI Alibaba provider configuration for DeepSeek and Qwen/DashScope.
- Keep tests credential-free by using test-only provider fakes rather than production deterministic mode.

**Non-Goals:**
- No new Maven modules.
- No WebFlux migration; keep Spring MVC SSE unless a later proposal changes the web stack.
- No JWT redesign; continue using propagated context headers for this change.
- No Redis/distributed SSE session store; keep bounded in-memory session state unless a later persistence proposal changes it.
- No Tool Executor, Knowledge Memory, or A2A behavior changes beyond compile fallout from shared DTO cleanup.

## Decisions

### 1. Gateway owns Sentinel; Orchestrator owns Resilience4j

Sentinel remains in Gateway because Gateway is the ingress point for external requests, Dashboard visibility, and optional flow-control rules managed by Sentinel's standard runtime. Orchestrator removes Sentinel because model-provider failure handling is a dependency-resilience problem already covered by Resilience4j. This change intentionally removes Claw4J-owned Sentinel threshold properties and Nacos datasource rule blocks from `application.yml`; later dynamic rule management can be added with a focused proposal.

Runtime ownership:

| Layer | Owns | Must not own in this change |
| --- | --- | --- |
| `claw4j-api-gateway` | Browser-facing entry, request context creation/validation, Sentinel ingress protection, Feign forwarding, SSE byte relay | Provider parsing, DeepSeek/Qwen fallback decisions, model-output normalization, project-owned Sentinel rule schemas |
| `claw4j-orchestrator` | Context budget adaptation, DeepSeek primary call, Resilience4j timeout/circuit/half-open behavior, Qwen fallback, resume prompt construction, output normalization | Sentinel dependencies, Sentinel Dashboard/client configuration, standalone Sentinel proof endpoints |

Alternatives considered:
- Keep Sentinel in both Gateway and Orchestrator: rejected because it duplicates circuit semantics and keeps standalone proof endpoints alive.
- Use only Resilience4j everywhere: rejected because Gateway ingress visibility and optional flow-control rules are a better fit for Sentinel.

### 2. Browser-friendly entry wraps the formal internal contract

The formal service contract continues to use request context headers and a model-focused request body. A browser-friendly Gateway entry may accept `query` as a query parameter, generate bounded local context values, and call the same Gateway service method used by the formal stream endpoint.

Alternatives considered:
- Make browser users supply all headers: rejected because a browser address-bar flow cannot conveniently set custom headers.
- Put context in JWT for this change: rejected because identity/session redesign is separate from chain integration.

### 3. Production request DTO contains no simulation switches

`StreamingModelRequest` should carry the model query only, plus future business-safe fields if needed. Failure simulation belongs in tests through fake `ModelProviderClient` implementations and test profile wiring.

Alternatives considered:
- Keep `simulate*` fields hidden from docs: rejected because shared DTO fields remain part of the contract and invite production misuse.
- Keep deterministic mode in production YAML: rejected because it makes runtime behavior look like a demo path.

### 4. Provider configuration stays official

DeepSeek and DashScope/Qwen credentials, base URLs, and model names stay under official `spring.ai.*` properties. Claw4J configuration is limited to governance that is not provider-owned, such as routing names, context budgets, resume settings, and local browser-entry defaults.

When two model providers are needed in one service, the implementation may keep an explicit provider boundary that consumes official property objects. The design requirement is not "zero adapter code"; it is "no duplicated Claw4J credential/model-name schema."

### 5. Resilience4j configuration should move toward official instance keys

Where practical, primary model timeout and circuit thresholds should be represented under official Resilience4j instance configuration, using a stable instance name such as `deepseek-primary-model`. If code-level construction remains necessary for streaming callback control, the configuration source should still align with Resilience4j semantics and avoid parallel names that drift.

### 6. Smoke tests follow one product path

Smoke docs should validate the chain through Gateway, not through direct Orchestrator URLs or standalone proof endpoints. Old proof documents can be rewritten, merged, or marked obsolete as long as README links guide users to the new end-to-end flow.

## Risks / Trade-offs

- Browser address-bar SSE is limited because `EventSource` cannot set arbitrary custom headers -> Gateway must generate local demo context for the browser-friendly path while formal clients keep header-driven context.
- Removing deterministic production mode makes local runtime require real provider credentials for actual model output -> contract tests must provide fake providers so CI remains credential-free.
- Removing Orchestrator Sentinel changes previously archived Sentinel behavior -> delta specs explicitly remove Orchestrator Agent-call Sentinel requirements and migrate that behavior to model failover resilience.
- OpenFeign streaming over MVC is blocking -> acceptable for this stage, but document concurrency limits and avoid WebFlux until a separate proposal.
- In-memory resume state is single-instance only -> acceptable for local proof and early runtime, with Redis/distributed resume deferred.
- Official provider auto-configuration may not produce two simultaneous default chat models cleanly -> keep a small provider adapter if needed, but it must consume official Spring AI properties and not define duplicate secret fields.

## Migration Plan

1. Add Gateway browser-friendly stream entry and wire it through the existing Gateway service method.
2. Move Gateway Sentinel protection onto the model streaming ingress path.
3. Remove Orchestrator Sentinel dependencies, YAML, controller, service, DTO, and tests.
4. Remove production proof/demo model configuration and simulated request fields.
5. Refactor Orchestrator model streaming to use real provider-backed runtime behavior with test-only fakes.
6. Align model timeout/circuit configuration with Resilience4j instance semantics where practical.
7. Rewrite smoke documentation around the single Gateway-facing model streaming chain.
8. Run module compile, relevant contract tests, full `mvn clean test`, OpenSpec strict validation, and at least one local smoke test through Gateway.

Rollback strategy:
- Revert this change commit if the integrated chain cannot start.
- Keep Nacos/Sentinel/Docker infrastructure rollback instructions in deployment docs.
- Do not preserve deleted proof endpoints as a rollback path; use Git rollback instead to avoid keeping dead production APIs.
