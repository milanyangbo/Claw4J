# Maven Multi Module Skeleton Specification

## Purpose

This capability defines the initial Claw4J Maven multi-module skeleton so developers can build, run, and evolve each Agent microservice within clear module and dependency boundaries.

## Requirements

### Requirement: Repository exposes the approved module set
The project SHALL contain exactly the approved initial Maven module set for the Claw4J skeleton: `claw4j-common`, `claw4j-api-gateway`, `claw4j-orchestrator`, `claw4j-tool-executor`, `claw4j-knowledge-memory`, and `claw4j-a2a-broker`.

#### Scenario: Root module list is complete
- **WHEN** a developer inspects the parent build configuration
- **THEN** all six approved modules are declared with no extra modules

#### Scenario: Module source roots exist
- **WHEN** a developer inspects each approved module
- **THEN** each module contains a `src/main/java` source root and a `src/test/java` test source root

### Requirement: Modules use approved package ownership
Each module SHALL place Java code under its assigned package root: `com.claw4j.common`, `com.claw4j.gateway`, `com.claw4j.orchestrator`, `com.claw4j.tool`, `com.claw4j.knowledge`, or `com.claw4j.a2a`.

#### Scenario: Startup classes are package aligned
- **WHEN** a developer inspects the service startup classes
- **THEN** each startup class is located under the package root assigned to its module

### Requirement: Common module remains a shared library
The `claw4j-common` module SHALL behave as a shared library and SHALL NOT expose an independently started network service or reserve an application port.

#### Scenario: Common module is built as a library
- **WHEN** the root project is compiled
- **THEN** the common module contributes shared code to dependent modules without starting a service process

### Requirement: Service modules start independently
The five service modules SHALL be independently runnable without requiring sibling service modules to start in the same process.

#### Scenario: Gateway starts on assigned port
- **WHEN** a developer starts `claw4j-api-gateway`
- **THEN** the service binds to port `8080`

#### Scenario: Orchestrator starts on assigned port
- **WHEN** a developer starts `claw4j-orchestrator`
- **THEN** the service binds to port `8081`

#### Scenario: Tool Executor starts on assigned port
- **WHEN** a developer starts `claw4j-tool-executor`
- **THEN** the service binds to port `8082`

#### Scenario: Knowledge Memory starts on assigned port
- **WHEN** a developer starts `claw4j-knowledge-memory`
- **THEN** the service binds to port `8083`

#### Scenario: A2A Broker starts on assigned port
- **WHEN** a developer starts `claw4j-a2a-broker`
- **THEN** the service binds to port `8084`

### Requirement: Root build compiles all modules
The project SHALL support a root-level compile command that compiles all approved modules successfully.

#### Scenario: Root compile succeeds
- **WHEN** a developer runs `mvn clean compile` from the repository root
- **THEN** Maven completes successfully for the parent project and all approved child modules

### Requirement: Dependency direction prevents service cycles
Service modules SHALL declare a direct Maven dependency on `claw4j-common`, and SHALL NOT introduce direct Maven dependencies on sibling service modules.

#### Scenario: Common dependency is present for service modules
- **WHEN** a developer inspects each runnable service module POM
- **THEN** each service module declares a direct dependency on `claw4j-common`

#### Scenario: Sibling service dependency is absent
- **WHEN** Maven dependency analysis is run for the skeleton
- **THEN** no service module has a direct Maven dependency on another service module

### Requirement: Platform versions are governed centrally
The skeleton SHALL centralize platform version governance so child modules inherit Java 17, Spring Boot 4.0.0, Spring AI Alibaba 2.0.0-M1.1, and Spring Cloud Alibaba 2025.1.0.0 version decisions from the parent project.

#### Scenario: Child modules inherit versions
- **WHEN** a developer inspects child module build configuration
- **THEN** shared platform versions are inherited from the parent instead of being redeclared independently per module
