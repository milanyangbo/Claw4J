## Context

See `proposal.md` for motivation. The current project has an archived Maven skeleton and a minimal `claw4j-common` module containing only a root-level constants class. The existing main skeleton spec allows service modules to depend on `claw4j-common` but the current implementation has not added those dependencies yet.

Project rules restrict package paths and require Javadoc, no field injection, no swallowed exceptions, no `return null`, and no unapproved feature implementation. This change deliberately introduces the `constant` package area because the requested common module separates constants/enums from generic utilities; implementation should document that reason in the package source.

## Goals / Non-Goals

**Goals:**

- Turn `claw4j-common` into the shared foundation module for all services.
- Provide annotations for audit, tool metadata, and rate-limit metadata.
- Provide common exception types, error codes, and reusable global exception handling.
- Provide standard response DTOs for success and error responses.
- Provide JSON and ID utilities with defensive failure behavior.
- Add `claw4j-common` dependencies to all runnable service modules without introducing service-to-service Maven dependencies.

**Non-Goals:**

- Implement audit log persistence, AOP advice, rate-limit enforcement, tool execution, authentication, Redis, RAG, or model-call behavior.
- Implement generic encryption utilities before key management, secret ownership, algorithm choice, and rotation rules are specified.
- Convert `claw4j-common` into a Spring Boot application or independently runnable service.

## Decisions

### Decision: Common foundation stays a library

`claw4j-common` remains a `jar` library. It can expose Spring-compatible classes such as an exception handler, but it must not define a service port or application entry point.

Alternative considered: make `claw4j-common` a bootable service so it can own exception infrastructure centrally. That would undermine the original module boundary because common is a code-sharing module, not a fault domain.

### Decision: Use explicit package areas

The common source tree should use this shape:

```text
com.claw4j.common
+-- annotation
|   +-- Audit
|   +-- Tool
|   +-- RateLimit
|
+-- exception
|   +-- ErrorCode
|   +-- Claw4jException
|   +-- BusinessException
|   +-- GlobalExceptionHandler
|
+-- constant
|   +-- CommonConstants
|
+-- util
|   +-- JsonUtil
|   +-- IdUtil
|
+-- dto
    +-- ApiResponse
    +-- ErrorResponse
```

`constant` is not in the current generic package allow-list, but this change explicitly needs it to separate constants/enums from general utility behavior. Implementation should include package-level documentation explaining that exception.

Alternative considered: place constants under `util`. That would satisfy the old allow-list but blur two different responsibilities and make shared enums harder to find.

### Decision: Service modules scan common components

Because service application classes live under `com.claw4j.gateway`, `com.claw4j.orchestrator`, and other sibling packages, default component scanning will not automatically discover `com.claw4j.common.exception.GlobalExceptionHandler`. The service startup classes should scan `com.claw4j` once service modules depend on common.

Alternative considered: create Spring Boot auto-configuration in common. That is cleaner for a starter-style library, but it adds framework ceremony before Claw4J has enough cross-cutting components to justify it.

### Decision: `@Tool` is a Claw4J metadata annotation

`com.claw4j.common.annotation.Tool` should describe Claw4J tool metadata such as name, description, and idempotency expectations. It should not be treated as a replacement for Spring AI's tool annotation.

Alternative considered: rename it to `ClawTool`. That avoids ambiguity, but the requested API explicitly names `@Tool`, so the package namespace and Javadoc should carry the distinction.

### Decision: JSON and ID utilities fail loudly

`JsonUtil` should return non-empty JSON for supported values and throw a Claw4J exception on serialization/deserialization failure. `IdUtil` should return non-empty identifiers and validate caller-provided prefixes rather than silently producing ambiguous IDs.

Alternative considered: return `Optional` from JSON operations. That is useful for domain absence, but serialization failure is exceptional infrastructure behavior and should preserve error-code observability.

### Decision: Use common dependency from every service module

Every runnable service module should declare a direct dependency on `claw4j-common`. Module-level verification should use the reactor when needed, for example `mvn clean compile -pl <service-module> -am`, so local builds do not require manually installing common first.

Alternative considered: keep service POMs dependency-free until a service imports a common type. That keeps the skeleton lighter but fails the new acceptance criterion that other modules can introduce and resolve the common module dependency.

## Risks / Trade-offs

- [Risk] A global exception handler in common may not be scanned by services. -> Mitigation: update service startup scanning to include `com.claw4j` and test handler behavior.
- [Risk] The `@Tool` name can be confused with Spring AI tooling annotations. -> Mitigation: document it as Claw4J metadata in Javadoc and keep it in the `com.claw4j.common.annotation` namespace.
- [Risk] Generic crypto helpers can create false security confidence. -> Mitigation: defer encryption utilities to a dedicated security change with key-management requirements.
- [Risk] Adding `spring-web` and Jackson to common increases shared dependency surface. -> Mitigation: add only the minimum dependencies needed for exception handling, HTTP status mapping, and JSON utility behavior.
- [Risk] Service POMs depending on common can make exact `mvn -pl <service>` commands fail from a clean local repository. -> Mitigation: document and verify reactor-aware module commands with `-am`.

## Migration Plan

1. Add common dependencies needed for Spring web exception contracts and JSON serialization.
2. Add common package areas and public classes with Javadoc.
3. Add service module dependencies on `claw4j-common` and update service scanning.
4. Add focused common tests for JSON, ID generation, response DTOs, and exception handling.
5. Verify `claw4j-common` compile/test, root compile/test, and reactor-aware service module builds.
