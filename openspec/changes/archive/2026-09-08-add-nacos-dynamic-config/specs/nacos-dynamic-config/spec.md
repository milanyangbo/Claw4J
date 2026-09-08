## Purpose

This capability defines how Claw4J runnable services load refreshable runtime configuration from Nacos Config while retaining safe local defaults and observable rollback paths.

## ADDED Requirements

### Requirement: Runnable services load Nacos Config with local defaults
Each runnable Claw4J service SHALL be able to load remote configuration from Nacos Config while retaining local defaults that allow startup when the config center is unavailable or a remote DataId is missing.

#### Scenario: Remote configuration is loaded
- **WHEN** a runnable service starts with a reachable Nacos Config server containing its configured DataIds
- **THEN** the service reads the matching remote configuration for its namespace, group, and service identity

#### Scenario: Missing config center does not block startup
- **WHEN** a runnable service starts without a reachable Nacos Config server
- **THEN** the service starts using documented local default values instead of failing during bootstrap

#### Scenario: Missing remote DataId does not block startup
- **WHEN** a runnable service starts and an optional remote DataId is absent from Nacos Config
- **THEN** the service starts using the corresponding local default values

### Requirement: Dynamic configuration refresh is observable without restart
Refreshable Claw4J runtime configuration SHALL become observable in a running service after the remote configuration changes, without requiring the service process to restart.

#### Scenario: Operations changes a Gateway threshold
- **WHEN** an operator changes the Gateway smoke-test rate-limit threshold in Nacos Config
- **THEN** a running Gateway instance exposes the updated threshold through its configuration proof endpoint without a restart

#### Scenario: Existing local defaults remain available
- **WHEN** no remote value is present for the refreshable Gateway threshold
- **THEN** the Gateway exposes the documented local default threshold value

### Requirement: Invalid dynamic values fall back safely
Claw4J services SHALL handle invalid refreshable configuration values defensively so malformed remote values do not prevent startup or replace a known safe value.

#### Scenario: Invalid threshold is rejected
- **WHEN** Nacos Config provides a non-numeric or out-of-range Gateway threshold
- **THEN** the Gateway reports the value as invalid and continues using the last valid or local default threshold

#### Scenario: Error state is visible
- **WHEN** a refreshable value is rejected
- **THEN** the service exposes enough configuration status for operators to identify the rejected key and the fallback value being used

### Requirement: Config scope is environment-isolated
Nacos dynamic configuration SHALL use configurable namespace, group, and DataId values so local, test, and future deployment environments do not read each other's runtime configuration.

#### Scenario: Namespace and group select the config source
- **WHEN** a deployment provides Nacos Config namespace or group overrides
- **THEN** the service reads remote configuration only from the provided namespace and group

#### Scenario: Service-specific config overrides shared config
- **WHEN** both shared and service-specific remote configuration define the same refreshable setting
- **THEN** the service-specific configuration takes precedence for that service

### Requirement: Config changes are traceable and rollbackable
Claw4J dynamic configuration SHALL be documented and observable enough for operators to verify a change and roll back to a previous value through Nacos Config.

#### Scenario: Effective demo values are visible
- **WHEN** an operator queries the configuration proof endpoint
- **THEN** the response identifies the effective threshold, demo message, fallback status, and invalid key when fallback is active

#### Scenario: Rollback can be verified
- **WHEN** an operator restores the previous threshold value in Nacos Config
- **THEN** the running service exposes the restored value without a restart
