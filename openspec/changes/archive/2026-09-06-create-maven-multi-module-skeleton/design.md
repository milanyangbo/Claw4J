## Context

See `proposal.md` for motivation. The repository currently contains no Maven parent POM, no child modules, and no Java source roots. Project rules require strict module boundaries, approved package roots, constructor injection for future Spring components, Javadoc for Java classes, no unapproved directories, and no feature implementation without an OpenSpec proposal.

## Goals / Non-Goals

**Goals:**

- Create a minimal Maven multi-module skeleton that compiles from the repository root.
- Keep `claw4j-common` as a pure library module with no server port.
- Make each service module independently runnable and independently deployable.
- Enforce one-way compile-time dependencies from service modules to `claw4j-common`.
- Keep version governance centralized in the parent POM.

**Non-Goals:**

- Implement business endpoints, Agent loops, RAG behavior, tool execution, A2A protocol behavior, authentication, rate limiting, Redis, persistence, or model calls.
- Add cross-service Feign clients before service contracts exist.
- Add non-standard source packages beyond the approved module package roots.

## Decisions

### Decision: Use one parent POM with six child modules

The root project will be a Maven aggregator and parent with `packaging=pom`. It will declare the six approved modules and provide shared build properties and dependency management.

Alternative considered: create each service as a standalone Maven project. That would make initial service isolation visible, but it would weaken version governance and make root-level validation harder.

### Decision: Treat `claw4j-common` as a library, not a service

`claw4j-common` has no assigned port and exists to share safe primitives such as constants, exceptions, annotations, DTOs, and utilities. It should not have a Spring Boot startup class because making it runnable blurs the boundary between shared code and deployable services.

Alternative considered: add an `Application.java` to every module, including `common`, to satisfy a literal "every module starts" interpretation. That creates a misleading service surface, so the skeleton should instead document that only the five service modules are independently runnable.

### Decision: Keep sibling service modules uncoupled at compile time

The initial dependency graph should look like this:

```text
+------------------+
|  claw4j-common   |
+---------+--------+
          ^
          |
   +------+-------+-------+---------+-------+
   |              |       |         |       |
   |              |       |         |       |
+--+----+   +-----+--+ +--+---+ +---+---+ +-+---+
|gateway|   |orchestr| |tool  | |knowldg| |a2a  |
| 8080  |   |  8081  | |8082  | | 8083  | |8084 |
+-------+   +--------+ +------+ +-------+ +-----+
```

Each service may depend on `claw4j-common`; service-to-service behavior should later use explicit API contracts, service discovery, and defensive clients rather than direct Maven dependencies.

Alternative considered: allow Orchestrator to depend directly on Tool Executor, Knowledge Memory, or A2A Broker modules. That would make early calls convenient but would create cycles and collapse fault domains over time.

### Decision: Keep the skeleton intentionally thin

Startup classes should be enough to prove service bootstrapping and port ownership. Defensive mechanisms such as budgets, idempotency keys, tenant-scoped Redis keys, and pre-filtering should be introduced with the specific feature proposals that use them.

Alternative considered: add placeholder controllers and defensive middleware during skeleton creation. That would look more complete but would introduce behavior before the corresponding OpenSpec requirements exist.

### Decision: Use parent-managed platform versions

The parent POM should centralize Java 17, Spring Boot 4.0.0, Spring AI Alibaba 2.0.0-M1.1, and Spring Cloud Alibaba 2025.1.0.0. Spring Cloud Alibaba 2025.1.0.0 is the selected baseline for Spring Boot 4.0.x alignment. Spring AI Alibaba 2.0.0-M1.1 is retained because it is the current published 2.x Alibaba BOM, but it must be treated as a milestone dependency until a GA 2.x Alibaba BOM is available. Child modules should inherit shared versions and declare only their module-specific dependencies.

Alternative considered: redeclare versions in each module POM. That increases drift risk and makes version governance harder to demonstrate.

## Risks / Trade-offs

- [Risk] Spring AI Alibaba 2.0.0-M1.1 is a milestone release rather than a GA 2.x Alibaba BOM. -> Mitigation: keep it centrally managed, avoid relying on AI-specific APIs in the skeleton, and revisit the BOM as soon as Alibaba publishes a GA 2.x line.
- [Risk] Platform BOM compatibility can drift as Spring Boot, Spring Cloud Alibaba, and Spring AI Alibaba evolve independently. -> Mitigation: verify with `mvn clean compile` during implementation and re-check the BOM matrix before adding feature dependencies.
- [Risk] Literal acceptance wording says every module has `Application.java`, while `common` is a non-service library. -> Mitigation: encode the agreed interpretation in specs and tasks: five service modules have startup classes; common remains a library.
- [Risk] A bare service can start but provide no functional endpoint. -> Mitigation: keep this change scoped to skeleton validation only; endpoint behavior belongs to later feature proposals.
- [Risk] Future developers may introduce sibling Maven dependencies for convenience. -> Mitigation: document the dependency direction and include dependency analysis in the validation tasks.

## Migration Plan

1. Add the parent POM and six child module directories.
2. Add minimal module POMs, source roots, and test roots.
3. Add startup classes and per-service port configuration for the five runnable service modules.
4. Run root compile and module-level compile checks.
5. Run dependency analysis to confirm no sibling service module dependencies were introduced.

Rollback is simple at this stage: remove the newly introduced POMs and module directories before any later feature proposal depends on them.
