## Why

Claw4J services need a shared foundation for response envelopes, error semantics, defensive annotations, and safe utility behavior before service-specific Agent features are added. Centralizing these contracts in `claw4j-common` prevents each microservice from inventing incompatible exception handling, IDs, JSON serialization, and cross-cutting metadata.

## What Changes

- Add common module package areas for annotations, exceptions, constants, utilities, and DTOs under `com.claw4j.common`.
- Introduce shared annotations for audit metadata, tool metadata, and rate-limit metadata.
- Introduce shared error codes, business exceptions, and a global exception response contract.
- Introduce shared DTOs for standard API success/error responses.
- Introduce shared JSON and ID utility behavior for consistent serialization and traceable identifiers.
- Define `claw4j-common` as an allowed dependency for every runnable service module.
- Defer encryption/key-management utilities until a security-focused proposal defines key ownership, algorithms, rotation, and secret handling.

## Capabilities

### New Capabilities

- `common-foundation`: Defines shared annotations, exception/error handling contracts, response DTOs, and utility behavior provided by `claw4j-common`.

### Modified Capabilities

- `maven-multi-module-skeleton`: Service modules must be able to depend on `claw4j-common` while preserving the rule that sibling service modules do not depend on each other directly.

## Impact

- Affected code: `claw4j-common/pom.xml`, package directories under `claw4j-common/src/main/java/com/claw4j/common`, service module POMs, and focused tests proving exception handling and utility behavior.
- Affected behavior: services can share common response/error contracts and can import common annotations/utilities without duplicating them.
- Affected validation: `claw4j-common` must compile independently, the full reactor must compile, service modules must resolve `claw4j-common`, and exception handling behavior must be covered by tests.
