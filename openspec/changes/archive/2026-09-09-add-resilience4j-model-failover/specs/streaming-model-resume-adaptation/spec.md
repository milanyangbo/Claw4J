## ADDED Requirements

### Requirement: Streaming resume supports real model clients
The streaming model resume path SHALL support real provider-backed model clients while preserving the existing deterministic proof behavior.

#### Scenario: Real primary stream feeds existing resume buffer
- **WHEN** a real primary model emits user-visible streaming content through the Orchestrator
- **THEN** the emitted content is appended to the same bounded session buffer used by deterministic streaming output

#### Scenario: Real fallback stream resumes from cached output
- **WHEN** a real primary model stream fails after partial user-visible content
- **THEN** the fallback provider receives a resume prompt based on the cached output and continues without repeating the already emitted prefix

#### Scenario: Gateway remains provider-agnostic
- **WHEN** real DeepSeek or Qwen providers produce streaming events through Orchestrator
- **THEN** Gateway continues forwarding Orchestrator SSE bytes without parsing provider-specific model output or owning model fallback policy
