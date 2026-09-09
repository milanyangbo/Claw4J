## Why

Gateway and Orchestrator already contain separate proofs for OpenFeign, Sentinel, SSE streaming, Nacos Config, and model failover, but these capabilities are not yet integrated into one clean user-facing model streaming path. The next step is to turn the scattered proof/demo paths into a production-oriented Gateway-to-Orchestrator chain that can be exercised from a browser and backed by real DeepSeek primary plus Qwen fallback behavior.

## What Changes

- Add a browser-friendly Gateway model chat entry that can stream model output through the existing Gateway -> Orchestrator boundary.
- Integrate Gateway Sentinel protection into the real model streaming ingress path instead of keeping Sentinel only on a standalone proof endpoint.
- Keep Sentinel ownership in `claw4j-api-gateway`; remove Orchestrator Sentinel runtime dependencies, configuration, proof controller, proof service, DTO, and tests.
- Keep Orchestrator model-provider resilience under Resilience4j, including primary timeout, primary circuit breaking, half-open recovery, and fallback to Qwen.
- Remove production proof/demo controls after the main chain is usable, including simulated model failure request fields, deterministic proof output configuration, and standalone proof endpoints.
- Keep provider credentials and model names on official Spring AI / Spring AI Alibaba configuration keys, while Claw4J-owned configuration is limited to business governance such as model routing, fallback, context budget, stream buffering, and user-facing entry defaults.
- **BREAKING**: Remove or replace proof-only APIs and request fields that were used only for local demonstrations.
- **BREAKING**: `claw4j-orchestrator` no longer exposes Sentinel Agent-call proof behavior; model failure governance is handled by Resilience4j.

## Capabilities

### New Capabilities

- `gateway-model-streaming-chain`: End-to-end browser/Gateway model streaming behavior, including Gateway ingress, context propagation, Orchestrator model governance, and SSE output back to the browser.

### Modified Capabilities

- `streaming-model-resume-adaptation`: Remove proof/demo request controls from the public model streaming contract and make the Gateway-backed streaming chain the primary behavior.
- `model-failover-resilience`: Remove deterministic production model mode and require real provider-backed DeepSeek/Qwen calls for runtime behavior, with tests using local fakes instead.
- `sentinel-resilience-guards`: Scope Sentinel runtime behavior to Gateway ingress protection and remove Orchestrator Agent-call Sentinel circuit requirements.
- `nacos-dynamic-config`: Replace demo/proof dynamic configuration exposure with runtime configuration support for the integrated Gateway model streaming chain.

## Impact

- Affected modules: `claw4j-api-gateway`, `claw4j-orchestrator`, and `claw4j-common`.
- Affected APIs: Gateway model streaming endpoints, Orchestrator internal streaming endpoint, removed proof-only Sentinel and dynamic-config endpoints, and `StreamingModelRequest`.
- Affected dependencies: remove Sentinel dependencies from `claw4j-orchestrator`; keep Sentinel dependencies in `claw4j-api-gateway`; keep Resilience4j and Spring AI provider dependencies in `claw4j-orchestrator`.
- Affected configuration: simplify Gateway/Orchestrator YAML, preserve official Spring AI provider keys, and move model resilience settings toward official Resilience4j configuration where practical.
- Affected docs/tests: replace proof-path smoke tests with an end-to-end browser/Gateway smoke test and update contract tests to use test doubles rather than production simulation fields.
