## MODIFIED Requirements

### Requirement: Dependency direction prevents service cycles
Service modules SHALL declare a direct Maven dependency on `claw4j-common`, and SHALL NOT introduce direct Maven dependencies on sibling service modules.

#### Scenario: Common dependency is present for service modules
- **WHEN** a developer inspects each runnable service module POM
- **THEN** each service module declares a direct dependency on `claw4j-common`

#### Scenario: Sibling service dependency is absent
- **WHEN** Maven dependency analysis is run for the skeleton
- **THEN** no service module has a direct Maven dependency on another service module
