# nacos-service-discovery Specification

## Purpose

This capability defines how Claw4J runnable services register with Nacos, expose discovery metadata, and use registry health state so service instances can be found and removed without hardcoded peer addresses.

## Requirements

### Requirement: Runnable services register with Nacos
Each runnable Claw4J service SHALL register with Nacos using its configured `spring.application.name` and assigned service port when service discovery is enabled.

#### Scenario: Gateway registers with its service name
- **WHEN** `claw4j-api-gateway` starts with a reachable Nacos registry
- **THEN** Nacos exposes an instance for service name `claw4j-api-gateway` on port `8080`

#### Scenario: Orchestrator registers with its service name
- **WHEN** `claw4j-orchestrator` starts with a reachable Nacos registry
- **THEN** Nacos exposes an instance for service name `claw4j-orchestrator` on port `8081`

#### Scenario: Tool executor registers with its service name
- **WHEN** `claw4j-tool-executor` starts with a reachable Nacos registry
- **THEN** Nacos exposes an instance for service name `claw4j-tool-executor` on port `8082`

#### Scenario: Knowledge memory registers with its service name
- **WHEN** `claw4j-knowledge-memory` starts with a reachable Nacos registry
- **THEN** Nacos exposes an instance for service name `claw4j-knowledge-memory` on port `8083`

#### Scenario: A2A broker registers with its service name
- **WHEN** `claw4j-a2a-broker` starts with a reachable Nacos registry
- **THEN** Nacos exposes an instance for service name `claw4j-a2a-broker` on port `8084`

### Requirement: Registry configuration supports environment isolation
Each runnable Claw4J service SHALL make the Nacos server address, namespace, and group configurable so environments can be isolated without changing source code.

#### Scenario: Default local discovery configuration is present
- **WHEN** a developer starts a service without discovery environment overrides
- **THEN** the service uses the project's documented local Nacos defaults

#### Scenario: Namespace and group can isolate environments
- **WHEN** a deployment provides Nacos namespace or group overrides
- **THEN** the service registers into the provided namespace or group instead of the local defaults

### Requirement: Registered instances expose service metadata
Each runnable Claw4J service SHALL publish discovery metadata that identifies the module, role, configured port, and deployment environment for the registered instance.

#### Scenario: Metadata is visible in registry
- **WHEN** a service registers successfully with Nacos
- **THEN** the registered instance metadata includes module, role, port, and environment values

### Requirement: Service-name discovery supports Gateway to Orchestrator lookup
The Gateway service SHALL be able to resolve healthy Orchestrator instances through the service name `claw4j-orchestrator` instead of a hardcoded host and port.

#### Scenario: Gateway discovers healthy Orchestrator instances
- **WHEN** `claw4j-orchestrator` is registered and healthy in the same namespace and group as `claw4j-api-gateway`
- **THEN** Gateway-side discovery can resolve at least one healthy `claw4j-orchestrator` instance by service name

#### Scenario: Gateway does not discover isolated Orchestrator instances
- **WHEN** `claw4j-orchestrator` is registered in a different namespace or group from `claw4j-api-gateway`
- **THEN** Gateway-side discovery does not resolve that isolated instance as an available peer

### Requirement: Registry health state controls instance availability
Claw4J services SHALL expose health information compatible with Nacos discovery so unhealthy or stopped instances are removed from available discovery results.

#### Scenario: Healthy instance is discoverable
- **WHEN** a service instance is running and passes health checks
- **THEN** Nacos marks the instance as healthy and includes it in discovery results

#### Scenario: Stopped instance is removed from discovery
- **WHEN** a registered service instance stops or stops sending heartbeats
- **THEN** Nacos removes or marks the instance unavailable so discovery clients no longer route to it as healthy
