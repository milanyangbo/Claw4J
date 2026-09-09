## ADDED Requirements

### Requirement: Orchestrator invokes real primary and fallback model providers
The Orchestrator SHALL invoke real provider-backed model clients for runtime model streaming, using DeepSeek as the primary model and Qwen as the fallback model.

#### Scenario: Runtime model calls use configured providers
- **WHEN** provider credentials are present and model streaming is requested through the Orchestrator
- **THEN** the Orchestrator invokes Spring AI DeepSeek for primary calls and Spring AI Alibaba DashScope/Qwen for fallback calls using the configured model names

#### Scenario: Provider credentials use official configuration
- **WHEN** model provider configuration is loaded
- **THEN** provider API keys, base URLs, and model names are read from official Spring AI and Spring AI Alibaba configuration properties rather than duplicated Claw4J provider credential properties

#### Scenario: Missing credentials fail closed
- **WHEN** a required provider credential is missing or blank for runtime model streaming
- **THEN** the Orchestrator fails closed with a stable configuration error before issuing a model request

## MODIFIED Requirements

### Requirement: Model resilience settings are configurable
Model-provider selection, timeout budgets, circuit thresholds, and fallback behavior SHALL be configurable without hardcoding secrets or environment-specific values.

#### Scenario: Local defaults are safe
- **WHEN** no external provider credentials or runtime overrides are configured
- **THEN** the Orchestrator starts with safe local governance defaults but does not emit fake deterministic model output from the production runtime

#### Scenario: Real provider settings are externally supplied
- **WHEN** model streaming is enabled
- **THEN** provider API keys and model names are read from official Spring AI / Spring AI Alibaba configuration, while timeout values and circuit thresholds are read from official Resilience4j configuration or explicitly Claw4J-owned model governance configuration

#### Scenario: Sentinel does not participate in model failover
- **WHEN** primary model timeout, failure, circuit-open, or half-open recovery behavior is evaluated in `claw4j-orchestrator`
- **THEN** the behavior is implemented through Resilience4j and provider fallback code without Sentinel runtime dependencies or Sentinel rules

#### Scenario: Secret values are not logged
- **WHEN** model-provider configuration is loaded or rejected
- **THEN** logs and responses do not expose provider API keys or credential material

#### Scenario: Test doubles stay out of production configuration
- **WHEN** model failover contract tests simulate provider success, timeout, provider failure, circuit-open fallback, or half-open recovery
- **THEN** those simulations use test-only fakes or test profile configuration instead of production request fields or production YAML proof-client settings

### Requirement: Model failover behavior is smoke-testable
Claw4J SHALL document and expose enough behavior for operators to verify primary success, timeout fallback, circuit opening, half-open recovery, and real-provider rollback through the integrated Gateway model streaming chain.

#### Scenario: Smoke test proves timeout fallback
- **WHEN** the documented smoke test configures a primary timeout condition
- **THEN** the response proves Qwen fallback was used after DeepSeek did not respond within budget

#### Scenario: Smoke test proves circuit opening
- **WHEN** the documented smoke test drives primary failures above the configured ratio
- **THEN** later requests show primary short-circuit behavior and fallback execution

#### Scenario: Smoke test proves primary recovery
- **WHEN** the documented smoke test waits for the half-open probe window and sends a successful primary probe
- **THEN** later requests prove primary DeepSeek routing has recovered

#### Scenario: Smoke test uses Gateway stream
- **WHEN** model failover behavior is verified locally
- **THEN** verification goes through the Gateway-facing model streaming endpoint instead of a standalone proof endpoint

## REMOVED Requirements

### Requirement: Orchestrator supports real primary and fallback model providers
**Reason**: Replaced by a stricter runtime provider requirement that removes deterministic production behavior and keeps provider settings on official Spring AI configuration keys.
**Migration**: Use `Orchestrator invokes real primary and fallback model providers` for runtime behavior, and move deterministic or scripted model behavior into tests and test fixtures only.
