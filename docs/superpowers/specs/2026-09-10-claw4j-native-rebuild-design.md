# Claw4J Native Rebuild Design

Date: 2026-09-10
Status: Draft for review
Owner: Claw4J project

## 1. Purpose

Claw4J will be a local-first agent platform built natively on Spring Boot and Spring AI. The project should reproduce the useful product shape of OpenClaw4J without copying its internal LangChain4j-oriented architecture. The first goal is a reliable local agent runtime that can run against local models, expose safe tools, preserve sessions, and later grow into Gateway, MCP, Webhook, and Cron workflows.

The engineering plan intentionally prioritizes a working local CLI before platform features. A usable local agent loop gives the project a stable core and prevents Gateway, automation, and integration work from shaping the runtime too early.

## 2. Product Scope

### 2.1 Goals

- Build a Spring AI-native agent runtime around `ChatClient`, tool calling, advisors, model provider configuration, and memory/RAG extension points.
- Support local-first usage through Ollama by default, with OpenAI-compatible providers as optional profiles.
- Provide an interactive CLI that supports multiple agents, streaming responses, session switching, reset, and persistent chat history.
- Implement safe built-in tools for file access, shell execution, HTTP requests, and later MCP-provided tools.
- Add a Gateway module after the CLI runtime is stable, so external systems can trigger agent runs through REST, Webhook, and Cron.
- Keep modules small, testable, and replaceable so future UI, plugins, or distributed execution can be added without rewriting the runtime.

### 2.2 Non-Goals For The First Release

- Do not build a full web UI in the first release.
- Do not implement multi-tenant SaaS, billing, remote workspace orchestration, or cloud deployment.
- Do not copy LangChain4j abstractions into the new codebase.
- Do not support every upstream OpenClaw4J integration in the MVP.
- Do not allow unrestricted shell or filesystem access by default.

### 2.3 Target User Experience

The first public milestone should feel like a local Codex-style terminal agent:

- A developer starts the app locally.
- The app detects configured model providers.
- The developer selects or creates an agent.
- The agent streams responses in the terminal.
- The agent can call approved tools with clear policy boundaries.
- Sessions can be resumed after restart.
- Project-specific configuration lives in readable YAML files.

## 3. Recommended Architecture

### 3.1 Module Layout

```text
Claw4J
├── pom.xml                       # parent build, dependency management
├── claw-core                     # agent runtime, sessions, events, provider-neutral contracts
├── claw-model-spring-ai          # Spring AI ChatClient integration and model profiles
├── claw-tools                    # built-in file, shell, HTTP, and workspace tools
├── claw-security                 # permission policies, tool guards, path isolation, audit events
├── claw-memory                   # conversation store, memory repository, RAG ingestion
├── claw-mcp                      # MCP client integration and tool adapter
├── claw-cli                      # interactive local CLI
├── claw-gateway                  # REST API, Webhook, Cron, async run orchestration
└── claw-examples                 # sample agents, prompts, configs, demo projects
```

### 3.2 Core Boundaries

`claw-core` owns the runtime model and must not depend on CLI, Gateway, or concrete providers. It defines agents, sessions, messages, tool calls, run events, and execution lifecycle.

`claw-model-spring-ai` adapts Spring AI into the core runtime. It should hide provider-specific details behind a small interface so the rest of the project can request completions, streams, and tool-capable model calls without knowing which provider is active.

`claw-tools` exposes tools as Spring AI-compatible functions or annotated tool methods. It should depend on `claw-security` for policy decisions before performing filesystem, shell, or network operations.

`claw-cli` is the first user-facing product. It should consume `claw-core` and not contain model orchestration logic directly.

`claw-gateway` comes later and should reuse the same runtime as CLI. Gateway is an adapter layer, not a second agent system.

## 4. Key Components

### 4.1 Agent Runtime

The runtime centers on these concepts:

- `AgentDefinition`: static configuration such as name, description, system prompt, allowed tools, model profile, memory policy, and workspace root.
- `AgentSession`: a durable conversation instance with messages, selected agent, workspace metadata, and run history.
- `AgentRunner`: orchestrates one user turn, including prompt assembly, model invocation, streaming events, tool calls, retries, and final response capture.
- `RunEvent`: normalized events for token deltas, tool call started, tool call completed, tool call denied, errors, and final answer.
- `ConversationStore`: persistence interface for sessions and messages.
- `ToolRegistry`: runtime registry that exposes built-in and MCP tools according to agent policy.

The runtime should treat tool calling as an auditable workflow. A model may request a tool, but policy must approve or deny it before execution.

### 4.2 Model Layer

The model layer should expose a provider-neutral `ModelClient` interface:

- `streamChat(request)`: streams token and tool-call events.
- `completeChat(request)`: returns a full response for non-interactive jobs.
- `supportsToolCalling()`: allows CLI and Gateway to report capability gaps.
- `providerInfo()`: reports active model provider, model name, and profile.

Spring AI implementation details stay in `claw-model-spring-ai`. This module configures Ollama, OpenAI-compatible providers, advisors, tool callbacks, and future vector-store-aware retrieval advisors.

### 4.3 Tool System

MVP tools:

- `FileReadTool`: read files inside approved workspace roots.
- `FileWriteTool`: write or patch files only inside approved roots.
- `ShellTool`: execute configured allowlisted commands with timeout and output limits.
- `HttpTool`: perform GET/POST requests with host allow/deny policy.
- `WorkspaceSearchTool`: search files using a bounded local command or Java implementation.

Tool execution must return structured results with status, stdout/body, stderr/error, elapsed time, and audit metadata. Tools should never return unbounded output to the model.

### 4.4 Security And Policy

The local version still needs a strict permission model because agent tools can mutate files and execute commands.

Policy defaults:

- Filesystem access is restricted to configured workspace roots.
- Shell commands are disabled until explicitly enabled.
- Shell execution uses allowlists, timeout, maximum output size, and working-directory checks.
- HTTP access uses host allow/deny lists and request size limits.
- Dangerous commands require explicit policy approval or interactive user confirmation.
- Every tool call records an audit event.

`claw-security` should be designed early because it affects tool APIs, CLI prompts, Gateway behavior, and automated runs.

### 4.5 Memory And RAG

Memory should be built in two layers:

- Conversation memory: durable session messages and summaries.
- Knowledge memory: optional local document ingestion and vector retrieval.

The MVP should persist raw conversations first. RAG should arrive after sessions are stable, using a vector-store abstraction so the project can support a simple local store for demos and more durable stores such as PostgreSQL/vector extension, Qdrant, or other Spring AI-supported vector stores later.

### 4.6 MCP Integration

MCP belongs after built-in tools are stable. The `claw-mcp` module should:

- Read MCP server definitions from local YAML config.
- Support stdio and HTTP/SSE style transports when supported by the selected Spring AI MCP integration.
- Convert MCP tools into Claw4J `ToolDescriptor` entries.
- Namespace external tool names to avoid collisions.
- Route every MCP tool call through the same policy and audit pipeline as built-in tools.

### 4.7 Gateway And Automation

Gateway should be added only after CLI proves the runtime. It should expose:

- REST endpoint to start an agent run.
- REST endpoint to stream or poll run events.
- Webhook endpoint for external triggers.
- Cron scheduler for recurring local jobs.
- Run log and status APIs.
- Same policy checks as CLI, with stricter defaults for unattended automation.

Gateway should not create a parallel orchestration path. It calls `AgentRunner` just like CLI does.

## 5. Data Flow

### 5.1 CLI Run Flow

1. User enters a message in CLI.
2. CLI resolves active `AgentDefinition` and `AgentSession`.
3. `AgentRunner` loads conversation context and memory.
4. `ToolRegistry` exposes tools allowed by agent policy.
5. `ModelClient` streams a response through Spring AI.
6. If the model requests a tool, `claw-security` evaluates the request.
7. Approved tools execute and return bounded structured output.
8. Tool result is appended to the model conversation.
9. Final answer is streamed to CLI and persisted.

### 5.2 Gateway Run Flow

1. External request enters REST, Webhook, or Cron adapter.
2. Gateway validates request, agent id, input size, and policy context.
3. Gateway creates a run record.
4. `AgentRunner` executes the same lifecycle used by CLI.
5. Run events are emitted to stream clients or persisted for polling.
6. Final output and audit trail are stored.

## 6. Engineering Schedule

### 6.1 Phase Plan

| Phase | Timeline | Theme | Main Work | Exit Criteria |
| --- | ---: | --- | --- | --- |
| Phase 0 | Week 0 | Scope and skeleton | Parent Maven build, Java version, module layout, README, coding standards | Repository builds and empty modules are wired |
| Phase 1 | Week 1 | Spring AI baseline | Model profiles, Spring AI adapter, Ollama default config, smoke tests | One test or CLI command can call a model |
| Phase 2 | Week 2 | Agent runtime | Agent/session model, runner lifecycle, message persistence interface, event stream | Multiple sessions can run independently |
| Phase 3 | Week 3 | Native tool calling | Tool descriptors, built-in tools, Spring AI tool adapter, audit records | Agent can call approved tools and receive results |
| Phase 4 | Week 4 | CLI MVP | Interactive shell, slash commands, streaming, session restore, provider status | Local Agent MVP is usable end to end |
| Phase 5 | Week 5 | Persistence and memory | File or database-backed conversation store, summaries, config cleanup | Restart preserves usable sessions |
| Phase 6 | Week 6 | Local knowledge | Document ingestion, embeddings, vector-store adapter, retrieval advisor | Agent can answer from imported local docs |
| Phase 7 | Week 7 | MCP alpha | MCP config, server connection, tool import, namespace isolation | Agent can call tools from a local MCP server |
| Phase 8 | Weeks 8-9 | Gateway alpha | REST API, run records, streaming/polling, Webhook adapter | External HTTP calls can trigger agent runs |
| Phase 9 | Week 10 | Automation beta | Cron jobs, retry policy, run logs, unattended policy defaults | Scheduled local agent jobs run reliably |
| Phase 10 | Weeks 11-12 | Release hardening | Docker Compose, examples, E2E tests, docs, packaging | New machine can run the local platform from docs |

### 6.2 Milestones

| Milestone | Target | Description | Required Capabilities |
| --- | ---: | --- | --- |
| M0 Skeleton | End of Week 1 | Buildable Spring Boot multi-module project | Maven build, dependency management, model smoke test |
| M1 Local Agent MVP | End of Week 4 | Useful local terminal agent | CLI, Ollama, sessions, streaming, basic tools, policy checks |
| M2 Local Knowledge Alpha | End of Week 6 | Agent can retain and retrieve local context | session persistence, summaries, document ingestion, vector retrieval |
| M3 MCP Alpha | End of Week 7 | Tool ecosystem begins | MCP client config, external tool discovery, namespaced tool calls |
| M4 Gateway Alpha | End of Week 9 | External systems can trigger runs | REST, Webhook, run status, event streaming/polling |
| M5 Automation Beta | End of Week 10 | Local recurring agent jobs work | Cron, retries, logs, unattended execution policy |
| M6 Local Platform Beta | End of Week 12 | Installable local OpenClaw-style platform | Docker Compose, examples, E2E tests, release docs |

## 7. Development Backlog By Priority

### 7.1 P0

- Parent Maven project and module skeleton.
- Spring AI model adapter with Ollama profile.
- Agent/session model and `AgentRunner`.
- CLI interactive shell and slash commands.
- Built-in file read, file write, shell, and HTTP tools.
- Security policy checks for every tool call.
- Conversation persistence.
- Unit tests for runtime, policy, and tool execution.

### 7.2 P1

- OpenAI-compatible profile.
- Conversation summarization.
- Local document ingestion and RAG.
- MCP client integration.
- REST Gateway.
- Webhook trigger support.
- Run event persistence and polling.
- Docker Compose local stack.

### 7.3 P2

- Rich plugin packaging.
- Team/chat integrations.
- Web UI.
- Multi-workspace dashboard.
- Advanced approval workflows.
- Distributed execution.
- Cloud deployment profile.

## 8. Testing Strategy

### 8.1 Unit Tests

- Agent definition parsing.
- Session lifecycle.
- Prompt assembly.
- Tool registry filtering.
- Security policy allow/deny behavior.
- Tool output truncation and error mapping.
- Conversation persistence.

### 8.2 Integration Tests

- Spring context boot for each module.
- Model adapter with mock model client.
- Tool calling loop with fake model tool requests.
- CLI command parsing and session selection.
- Gateway request-to-run lifecycle.
- MCP tool discovery using a local fake MCP server.

### 8.3 End-To-End Tests

- Start app with local config.
- Create an agent.
- Ask a question.
- Trigger a safe file read.
- Persist and restore session.
- Import a small document and answer from it.
- Trigger a Gateway run and poll final status.
- Run a scheduled Cron job once in test mode.

## 9. Configuration Design

Local config should be readable and versionable:

```yaml
claw:
  workspace:
    roots:
      - .
  model:
    provider: ollama
    model: qwen2.5-coder
  agents:
    - id: local-dev
      name: Local Developer
      systemPrompt: prompts/local-dev.md
      tools:
        - file.read
        - workspace.search
        - shell.safe
  security:
    shell:
      enabled: true
      allowedCommands:
        - git
        - mvn
        - rg
      timeoutSeconds: 30
    http:
      enabled: false
```

Provider-specific settings should be isolated in Spring profiles so local development can use Ollama without requiring API keys.

## 10. Release Readiness

A release candidate is ready when:

- A new developer can clone the repository and run the CLI from README instructions.
- The default configuration works with a local Ollama model.
- Unsafe tools are denied by default.
- CLI, memory, RAG, MCP, Gateway, and Cron each have at least one happy-path test.
- Tool calls create auditable events.
- Documentation explains model setup, agent config, tool policy, and common troubleshooting.
- No module bypasses `claw-security` for tool execution.

## 11. Risks And Mitigations

| Risk | Impact | Mitigation |
| --- | --- | --- |
| Spring AI APIs evolve during implementation | Adapter churn | Keep Spring AI details isolated in `claw-model-spring-ai` |
| Tool calling semantics vary by provider | Runtime inconsistency | Capability detection and provider-specific tests |
| Shell/file tools introduce local safety risk | Data loss or unsafe execution | Default-deny policy, allowlists, workspace roots, audit events |
| Gateway adds complexity too early | Runtime design distortion | Ship CLI MVP before Gateway work |
| RAG scope expands into a search platform | Schedule slip | Start with small local ingestion and simple retrieval |
| MCP tools collide with built-in tools | Ambiguous execution | Namespace external tools and route through policy |

## 12. Implementation Principles

- Build the runtime once and reuse it from CLI, Gateway, Cron, and Webhook.
- Prefer Spring AI-native abstractions over compatibility layers.
- Keep security and audit in the execution path from the beginning.
- Make local development work without cloud credentials.
- Keep configs text-based and easy to inspect.
- Add platform features only after the local agent loop is stable.
- Treat OpenClaw4J as product inspiration, not as architecture to port line-by-line.

## 13. Suggested First Implementation Plan

The first implementation plan should cover only M0 and M1:

1. Create Maven parent and modules.
2. Add Spring Boot and Spring AI dependency management.
3. Implement provider config and model smoke test.
4. Define core agent/session/message contracts.
5. Implement a minimal `AgentRunner`.
6. Add CLI shell with streaming output.
7. Add file read and workspace search tools.
8. Add default-deny tool policy and audit events.
9. Add tests for runner, CLI parsing, and tool policy.
10. Update README with local Ollama setup and first-run instructions.

This keeps the first build focused: a local agent can talk, call safe tools, and preserve enough structure for later memory, MCP, and Gateway work.
