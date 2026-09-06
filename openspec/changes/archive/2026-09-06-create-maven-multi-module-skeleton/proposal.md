## Why

Claw4J needs a clean Maven multi-module foundation before any Agent capability can be implemented safely. The project is positioned as a Spring AI Alibaba and Spring Cloud Alibaba microservice cluster, so the initial skeleton must make module boundaries, service isolation, dependency direction, and version governance explicit from day one.

## What Changes

- Create a parent Maven project with `packaging=pom` and six declared child modules.
- Establish `claw4j-common` as a pure shared library module for constants, DTOs, exceptions, annotations, and utilities.
- Establish five independently runnable Spring Boot service modules: API Gateway, Orchestrator, Tool Executor, Knowledge Memory, and A2A Broker.
- Configure each service module with its own package root, startup class, and server port.
- Centralize Java 17, Spring Boot 4.0.0, Spring AI Alibaba 2.0.0-M1.1, and Spring Cloud Alibaba 2025.1.0.0 through parent POM properties and BOM dependency management.
- Define dependency direction so service modules may depend on `claw4j-common`, while sibling service modules do not depend on each other directly.

## Capabilities

### New Capabilities

- `maven-multi-module-skeleton`: Defines the Maven parent project, six module boundaries, service startup behavior, port assignments, and dependency direction rules for the initial Claw4J microservice skeleton.

### Modified Capabilities

- None.

## Impact

- Affected code: root `pom.xml`, six module `pom.xml` files, module source/test directories, service startup classes, and service configuration files.
- Affected systems: Maven build lifecycle, Spring Boot application startup, module-level dependency management, and future microservice deployment boundaries.
- Validation impact: `mvn clean compile` must pass at the root, and each runnable service module must remain independently startable without introducing sibling-module compile-time coupling.
