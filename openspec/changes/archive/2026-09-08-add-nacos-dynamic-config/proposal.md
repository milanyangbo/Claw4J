## Why

Claw4J services currently register with Nacos but still keep runtime configuration in local application files. Introducing Nacos Config lets operations adjust runtime knobs such as smoke-test rate-limit thresholds without restarting services, while keeping local defaults safe when the config center is unavailable.

## What Changes

- Add Nacos Config support to the runnable service modules using Spring Cloud Alibaba's current `spring.config.import` model instead of legacy `bootstrap.yml`.
- Define environment-overridable config center defaults for server address, namespace, group, imported DataIds, refresh behavior, and timeout behavior.
- Add a refreshable configuration proof path for a bounded runtime setting such as a Gateway smoke-test rate-limit threshold.
- Add defensive fallback behavior so missing or malformed remote configuration does not prevent normal local startup.
- Document local Nacos Config smoke tests, including loading config, changing a value, observing refresh, and rolling the value back.
- Keep full rate-limit enforcement, Redis-backed counters, authentication, and production config governance out of scope for this change.

## Capabilities

### New Capabilities
- `nacos-dynamic-config`: Defines how runnable services load refreshable configuration from Nacos Config, retain safe local defaults, expose a proof path for hot refresh, and support traceable config changes.

### Modified Capabilities
- None.

## Impact

- Affected dependencies: `com.alibaba.cloud:spring-cloud-starter-alibaba-nacos-config` for runnable services that participate in dynamic configuration.
- Affected configuration: service `application.yml` files, with no `bootstrap.yml` files introduced.
- Affected code: module-local `config`, `service`, and possibly thin `controller` classes only where needed to prove current configuration values and refresh behavior.
- Affected documentation: README links plus an independent Nacos Config smoke-test document.
- Affected runtime systems: local or shared Nacos Server used as a config center in the same namespace/group governance model as service discovery.
