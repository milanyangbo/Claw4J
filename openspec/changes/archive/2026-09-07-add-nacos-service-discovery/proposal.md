## Why

Claw4J's runnable microservices currently start on fixed ports but have no registry-backed discovery contract. Adding Nacos registration now turns the Maven skeleton into a service-governed microservice cluster where gateway routing, health-based removal, and environment isolation can be validated early.

## What Changes

- Add Nacos service discovery as a shared runtime capability for the five runnable service modules.
- Configure each service to register with its existing `spring.application.name` and assigned port.
- Standardize Nacos server address, namespace, group, and service metadata through configuration placeholders with safe local defaults.
- Enable Spring Cloud discovery in each runnable service without changing `claw4j-common` into a service.
- Add health-check visibility through Spring Boot Actuator so service health can support registry governance.
- Defer business RPC, Feign clients, gateway route predicates, and gray-release traffic policy to later proposals.

## Capabilities

### New Capabilities
- `nacos-service-discovery`: Defines Nacos registration, discovery metadata, namespace/group isolation, and health-check expectations for Claw4J service modules.

### Modified Capabilities
- None.

## Impact

- Affected code: root dependency management only if needed, five service module POMs, five service `application.yml` files, five service startup classes, and focused tests or static checks for service-discovery configuration.
- Affected dependencies: `com.alibaba.cloud:spring-cloud-starter-alibaba-nacos-discovery` and `org.springframework.boot:spring-boot-starter-actuator` for runnable services.
- Affected systems: a local or shared Nacos server, defaulting to `127.0.0.1:8848` unless overridden by environment configuration.
- Affected behavior: service instances register under stable names, expose metadata for environment isolation, and become discoverable by other services through Nacos.
