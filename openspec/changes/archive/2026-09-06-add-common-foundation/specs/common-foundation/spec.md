## Purpose

This capability defines the shared Claw4J common foundation so every microservice can reuse the same annotations, error contracts, response DTOs, and defensive utility behavior without duplicating cross-cutting code.

## ADDED Requirements

### Requirement: Common module exposes approved foundation packages
The `claw4j-common` module SHALL expose shared source packages for annotations, exceptions, constants, utilities, and DTOs under `com.claw4j.common`.

#### Scenario: Common package areas are available
- **WHEN** a developer inspects the common module source tree
- **THEN** the module exposes `annotation`, `exception`, `constant`, `util`, and `dto` package areas under `com.claw4j.common`

#### Scenario: Constant package is justified
- **WHEN** the common module introduces the `constant` package area
- **THEN** the implementation documents that the package exists to separate shared constants and enums from general utilities

### Requirement: Common annotations provide declarative metadata
The common module SHALL provide declarative annotations for audit metadata, tool metadata, and rate-limit metadata that can be inspected at runtime by service modules.

#### Scenario: Audit metadata is declared
- **WHEN** a service method is annotated for auditing
- **THEN** the annotation exposes runtime metadata that can identify the audit action and resource

#### Scenario: Tool metadata is declared
- **WHEN** a service declares a Claw4J tool boundary
- **THEN** the annotation exposes runtime metadata that can identify the tool name, description, and idempotency expectation

#### Scenario: Rate limit metadata is declared
- **WHEN** a service method is annotated for rate limiting
- **THEN** the annotation exposes runtime metadata that can identify the limit key, maximum requests, and window duration

### Requirement: Common errors use a shared error-code contract
The common module SHALL define a shared error-code contract that includes a stable code, human-readable message, and HTTP status category for service responses.

#### Scenario: Business errors use stable codes
- **WHEN** a service raises a business exception from the common error model
- **THEN** the exception exposes a non-empty shared error code and message

#### Scenario: Unknown errors are sanitized
- **WHEN** an unexpected exception is converted to an external response
- **THEN** the response uses a generic internal error code and does not expose stack traces or implementation details

### Requirement: Global exception handling returns consistent responses
The common module SHALL provide reusable exception-handling behavior that converts known Claw4J exceptions and unexpected exceptions into the shared error response DTO.

#### Scenario: Known exception is handled
- **WHEN** a service using the common exception handler raises a known Claw4J business exception
- **THEN** the HTTP response body contains the shared error response shape with the exception's error code and message

#### Scenario: Unexpected exception is handled defensively
- **WHEN** a service using the common exception handler raises an unexpected exception
- **THEN** the HTTP response body contains the shared error response shape with the generic internal error code

### Requirement: Common DTOs standardize API response shapes
The common module SHALL provide shared DTOs for successful API responses and error API responses.

#### Scenario: Success response is standard
- **WHEN** a service returns data through the common success response DTO
- **THEN** the response includes a success indicator, message, and payload field

#### Scenario: Error response is standard
- **WHEN** a service returns an error through the common error response DTO
- **THEN** the response includes a success indicator, error code, message, and request identifier field

### Requirement: JSON utilities fail safely
The common module SHALL provide JSON serialization and deserialization utility behavior that returns valid JSON for supported values and fails with a Claw4J exception for invalid JSON operations.

#### Scenario: Object serializes to JSON
- **WHEN** a supported value is serialized through the common JSON utility
- **THEN** the result is a non-empty JSON string

#### Scenario: Invalid JSON raises common exception
- **WHEN** invalid JSON is deserialized through the common JSON utility
- **THEN** the utility raises a Claw4J exception instead of returning `null`

### Requirement: ID utilities generate traceable identifiers
The common module SHALL provide ID generation utility behavior that returns non-empty identifiers suitable for request tracing and idempotency keys.

#### Scenario: Identifier is generated
- **WHEN** a service requests a generated identifier from the common ID utility
- **THEN** the utility returns a non-empty identifier string

#### Scenario: Identifier supports contextual prefix
- **WHEN** a service requests a generated identifier with an allowed prefix
- **THEN** the generated identifier includes that prefix without removing the unique portion
