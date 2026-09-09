## ADDED Requirements

### Requirement: Runtime configuration uses optional YAML Nacos imports
Claw4J services in the integrated model streaming chain SHALL keep optional YAML Nacos imports available without requiring proof-only dynamic configuration endpoints or project-owned Sentinel rule datasources.

#### Scenario: Gateway imports shared and service YAML config
- **WHEN** Gateway starts with local defaults
- **THEN** it imports `claw4j-shared.yaml` and `${spring.application.name}.yaml` as optional YAML Nacos config sources

#### Scenario: Existing local defaults remain available
- **WHEN** no remote value is present for an optional YAML config source
- **THEN** the service keeps its local `application.yml` defaults and still starts

### Requirement: Config changes are traceable and rollbackable through runtime behavior
Claw4J dynamic configuration SHALL be documented and observable enough for operators to verify runtime behavior without relying on deleted demo endpoints.

#### Scenario: Effective runtime values are verifiable
- **WHEN** an operator performs the documented model streaming smoke test after changing an official Spring or Claw4J-owned runtime governance value in Nacos Config
- **THEN** the observed Gateway -> Orchestrator behavior reflects the effective value

#### Scenario: Rollback can be verified
- **WHEN** an operator restores the previous runtime value in Nacos Config
- **THEN** later model streaming smoke-test behavior reflects the restored value when the underlying property supports refresh

## MODIFIED Requirements

### Requirement: Invalid dynamic values fall back safely
Claw4J services SHALL handle invalid refreshable configuration values defensively so malformed remote values do not prevent startup or replace a known safe value.

#### Scenario: Invalid threshold is rejected
- **WHEN** Nacos Config provides a malformed refreshable governance value
- **THEN** the service reports or logs the value as invalid and continues using the last valid or local default value

#### Scenario: Error state is visible
- **WHEN** a refreshable runtime value is rejected
- **THEN** operators can identify the rejected key and effective fallback value through documented logs, health detail, or smoke-test evidence without relying on a demo endpoint

## REMOVED Requirements

### Requirement: Dynamic configuration refresh is observable without restart
**Reason**: Replaced by a runtime-focused refresh requirement that verifies changes through Gateway model streaming behavior rather than a standalone proof endpoint.
**Migration**: Use `Runtime configuration uses optional YAML Nacos imports`.

### Requirement: Config changes are traceable and rollbackable
**Reason**: Replaced by a runtime-focused traceability requirement that removes demo message exposure from the contract.
**Migration**: Use `Config changes are traceable and rollbackable through runtime behavior`.
