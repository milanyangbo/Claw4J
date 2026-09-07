# openfeign-service-calls Specification

## Purpose

This capability defines how Claw4J services perform governed internal HTTP calls across module boundaries using service discovery, bounded latency, load-balanced routing, and predictable degradation.

## Requirements

### Requirement: Gateway calls Orchestrator by service name
The Gateway service SHALL call Orchestrator through the registered service name `claw4j-orchestrator` instead of a hardcoded host, port, or sibling module dependency.

#### Scenario: Gateway receives Orchestrator response
- **WHEN** `claw4j-orchestrator` is registered healthy in the same discovery namespace and group as `claw4j-api-gateway`
- **THEN** a Gateway internal HTTP call to Orchestrator succeeds through the `claw4j-orchestrator` service name

#### Scenario: Gateway avoids hardcoded Orchestrator address
- **WHEN** Gateway internal client configuration is inspected
- **THEN** it does not require a fixed Orchestrator host or port value to route the call

### Requirement: Orchestrator calls A2A Broker by service name
The Orchestrator service SHALL call A2A Broker through the registered service name `claw4j-a2a-broker` instead of a hardcoded host, port, or sibling module dependency.

#### Scenario: Orchestrator receives A2A Broker response
- **WHEN** `claw4j-a2a-broker` is registered healthy in the same discovery namespace and group as `claw4j-orchestrator`
- **THEN** an Orchestrator internal HTTP call to A2A Broker succeeds through the `claw4j-a2a-broker` service name

#### Scenario: Orchestrator avoids hardcoded A2A Broker address
- **WHEN** Orchestrator internal client configuration is inspected
- **THEN** it does not require a fixed A2A Broker host or port value to route the call

### Requirement: Internal calls use healthy load-balanced instances
Internal service-to-service HTTP calls SHALL route only to healthy instances returned by service discovery and SHALL be able to use more than one available downstream instance.

#### Scenario: Healthy instances receive calls
- **WHEN** two healthy instances of the same downstream service are registered in the caller's discovery namespace and group
- **THEN** repeated internal calls can be routed across the available healthy instances

#### Scenario: Unhealthy instance is avoided
- **WHEN** one downstream instance is unavailable or marked unhealthy in discovery
- **THEN** the caller does not route internal calls to that unavailable instance

#### Scenario: Isolated namespace is not crossed
- **WHEN** a downstream instance is registered in a different namespace or group from the caller
- **THEN** the caller does not use that isolated instance for internal service-to-service calls

### Requirement: Downstream failures trigger explicit degradation
Every internal service client SHALL define a fallback path that converts downstream unavailability, timeout, or circuit-breaker rejection into a predictable degraded outcome without returning `null` or exposing raw transport details.

#### Scenario: Unavailable Orchestrator triggers Gateway degradation
- **WHEN** Gateway calls Orchestrator and no healthy Orchestrator instance is available
- **THEN** Gateway returns or raises a standardized degraded outcome with a stable Claw4J error response

#### Scenario: Unavailable A2A Broker triggers Orchestrator degradation
- **WHEN** Orchestrator calls A2A Broker and no healthy A2A Broker instance is available
- **THEN** Orchestrator returns or raises a standardized degraded outcome with a stable Claw4J error response

### Requirement: Internal calls are bounded by timeout and retry policy
Internal service-to-service HTTP calls SHALL use explicit connect timeout, read timeout, and retry settings that are configurable without source-code changes.

#### Scenario: Timeout configuration is present
- **WHEN** caller service configuration is inspected
- **THEN** it defines internal call connect and read timeout values through configuration properties

#### Scenario: Slow downstream call is bounded
- **WHEN** a downstream service accepts a connection but does not respond within the configured read timeout
- **THEN** the caller stops waiting and enters the explicit degradation path

#### Scenario: Non-idempotent retries are not enabled by default
- **WHEN** caller retry configuration is inspected
- **THEN** retries are disabled by default unless a call is explicitly documented and verified as idempotent

### Requirement: Internal call context is propagated defensively
Internal service-to-service HTTP calls SHALL propagate trace and tenant context supplied by the inbound request and SHALL validate required context before invoking downstream services.

#### Scenario: Request identifier is propagated
- **WHEN** an inbound request contains a valid request identifier
- **THEN** downstream internal calls include the same request identifier for traceability

#### Scenario: Missing required context is rejected
- **WHEN** a caller attempts an internal service call without required validated request context
- **THEN** the call is rejected before downstream invocation with a standardized invalid-request outcome

### Requirement: HTTP client package boundary is approved
Module-local internal HTTP client contracts SHALL live in an approved `client` package under the owning module package root, and fallback implementations SHALL remain in approved module packages without creating new Maven modules.

#### Scenario: Client package is allowed for service modules
- **WHEN** package governance rules are inspected
- **THEN** `client` is listed as an approved service-module subpackage for internal HTTP client boundaries

#### Scenario: Service modules do not depend on sibling modules
- **WHEN** dependency analysis is run for Gateway and Orchestrator
- **THEN** neither module declares a direct Maven dependency on a sibling service module for internal HTTP calls
