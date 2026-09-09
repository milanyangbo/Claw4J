# Claw4J M0/M1 Native Rebuild Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the M0/M1 slice of Claw4J: a Spring Boot/Spring AI-native multi-module project with a local CLI agent, session persistence, safe file/search tools, and default-deny tool policy.

**Architecture:** The runtime lives in `claw-core` behind provider-neutral interfaces. `claw-model-spring-ai` adapts Spring AI and Ollama into those interfaces, while `claw-cli` is only a user-facing adapter. Built-in tools execute through `claw-security` policy checks before returning bounded structured results to the runtime.

**Tech Stack:** Java 21, Maven 3.9+, Spring Boot 4.1.1, Spring AI BOM 2.0.1, Reactor, Jackson, JUnit 5, AssertJ, Mockito, Ollama local model profile.

**Spec:** `docs/superpowers/specs/2026-09-10-claw4j-native-rebuild-design.md`

## Global Constraints

- Build the runtime once and reuse it from CLI, Gateway, Cron, and Webhook.
- Prefer Spring AI-native abstractions over compatibility layers.
- Keep security and audit in the execution path from the beginning.
- Make local development work without cloud credentials.
- Keep configs text-based and easy to inspect.
- Add platform features only after the local agent loop is stable.
- Treat OpenClaw4J as product inspiration, not as architecture to port line-by-line.
- Use Maven modules under group id `io.github.claw4j`.
- Use root Java package `io.github.claw4j`.
- Use Java 21 as the compiler release for M0/M1.
- Default model provider is Ollama.
- Do not make `claw-core` depend on Spring AI, Spring Boot, CLI code, Gateway code, or concrete tool implementations.
- Do not implement Gateway, RAG, MCP, shell execution, HTTP tools, or file writing in this plan; reserve interfaces only where M1 needs them.
- Route every M1 tool execution through `claw-security`.
- Keep tool output bounded with explicit character limits.
- Keep root `AGENTS.md` as the source of truth for engineering conventions, module ownership, dependency direction, code placement, and implementation discipline.
- Use frequent commits: each task below ends with one commit.

---

## File Structure

### Repository Root

- `pom.xml`: parent Maven build, module list, Java version, dependency management, plugin management.
- `.gitignore`: ignore Java build output, IDE files, local runtime state, and Ollama/session scratch data.
- `AGENTS.md`: engineering instructions for humans and agentic workers, including module placement, package naming, testing, dependency boundaries, and coding standards.
- `README.md`: user-facing first-run instructions for the M1 CLI.

### `claw-core`

- `claw-core/pom.xml`: Java library module with Reactor and Jackson annotations only when needed.
- `claw-core/src/main/java/io/github/claw4j/core/agent/AgentDefinition.java`: immutable static agent configuration.
- `claw-core/src/main/java/io/github/claw4j/core/agent/AgentSession.java`: immutable session metadata.
- `claw-core/src/main/java/io/github/claw4j/core/agent/AgentRunner.java`: one-turn orchestration entry point.
- `claw-core/src/main/java/io/github/claw4j/core/chat/ChatMessage.java`: role/content chat message record.
- `claw-core/src/main/java/io/github/claw4j/core/chat/ChatRole.java`: system/user/assistant/tool role enum.
- `claw-core/src/main/java/io/github/claw4j/core/model/ModelClient.java`: provider-neutral model interface.
- `claw-core/src/main/java/io/github/claw4j/core/model/ModelRequest.java`: request passed to model clients.
- `claw-core/src/main/java/io/github/claw4j/core/model/ModelResponse.java`: non-streaming response value.
- `claw-core/src/main/java/io/github/claw4j/core/run/RunEvent.java`: sealed event model for streaming output.
- `claw-core/src/main/java/io/github/claw4j/core/session/ConversationStore.java`: session/message persistence port.
- `claw-core/src/main/java/io/github/claw4j/core/tool/ToolDescriptor.java`: tool metadata used by agents and model layer.
- `claw-core/src/main/java/io/github/claw4j/core/tool/ToolInvocation.java`: normalized tool invocation request.
- `claw-core/src/main/java/io/github/claw4j/core/tool/ToolResult.java`: normalized bounded tool response.
- `claw-core/src/main/java/io/github/claw4j/core/tool/ToolExecutor.java`: executable tool contract.
- `claw-core/src/main/java/io/github/claw4j/core/tool/ToolRegistry.java`: filters tools by name and agent policy.

### `claw-security`

- `claw-security/pom.xml`: Java library module depending on `claw-core`.
- `claw-security/src/main/java/io/github/claw4j/security/ToolPolicy.java`: policy interface.
- `claw-security/src/main/java/io/github/claw4j/security/ToolDecision.java`: allow/deny decision record.
- `claw-security/src/main/java/io/github/claw4j/security/DefaultToolPolicy.java`: default-deny policy with workspace-root checks.
- `claw-security/src/main/java/io/github/claw4j/security/AuditEvent.java`: audit record for every tool attempt.
- `claw-security/src/main/java/io/github/claw4j/security/AuditSink.java`: audit event destination.
- `claw-security/src/main/java/io/github/claw4j/security/InMemoryAuditSink.java`: test-friendly audit sink.

### `claw-memory`

- `claw-memory/pom.xml`: Java library module depending on `claw-core`.
- `claw-memory/src/main/java/io/github/claw4j/memory/file/FileConversationStore.java`: JSON file-backed `ConversationStore`.
- `claw-memory/src/main/java/io/github/claw4j/memory/file/StoredConversation.java`: serialized conversation envelope.

### `claw-tools`

- `claw-tools/pom.xml`: Java library module depending on `claw-core` and `claw-security`.
- `claw-tools/src/main/java/io/github/claw4j/tools/file/FileReadTool.java`: safe file reader inside workspace roots.
- `claw-tools/src/main/java/io/github/claw4j/tools/search/WorkspaceSearchTool.java`: bounded local text search.
- `claw-tools/src/main/java/io/github/claw4j/tools/DefaultTools.java`: factory for M1 built-in tools.

### `claw-model-spring-ai`

- `claw-model-spring-ai/pom.xml`: Spring Boot/Spring AI adapter module.
- `claw-model-spring-ai/src/main/java/io/github/claw4j/model/springai/SpringAiModelClient.java`: `ModelClient` implementation.
- `claw-model-spring-ai/src/main/java/io/github/claw4j/model/springai/SpringAiModelAutoConfiguration.java`: Spring configuration for `ModelClient`.
- `claw-model-spring-ai/src/main/java/io/github/claw4j/model/springai/ClawModelProperties.java`: model provider properties.

### `claw-cli`

- `claw-cli/pom.xml`: executable Spring Boot application module.
- `claw-cli/src/main/java/io/github/claw4j/cli/ClawCliApplication.java`: application entry point.
- `claw-cli/src/main/java/io/github/claw4j/cli/ClawCliRunner.java`: terminal loop.
- `claw-cli/src/main/java/io/github/claw4j/cli/SlashCommand.java`: slash command parser.
- `claw-cli/src/main/java/io/github/claw4j/cli/CliSessionState.java`: active agent/session state holder.
- `claw-cli/src/main/resources/application.yml`: local Ollama defaults and storage path.
- `claw-cli/src/main/resources/prompts/local-dev.md`: default local developer agent prompt.

### Reserved Skeleton Modules

- `claw-mcp/pom.xml`: empty buildable module reserved for M3.
- `claw-gateway/pom.xml`: empty buildable module reserved for M4.
- `claw-examples/pom.xml`: empty buildable module for examples and sample configs.

---

### Task 1: Maven Multi-Module Skeleton

**Files:**
- Create: `pom.xml`
- Create: `.gitignore`
- Create: `AGENTS.md`
- Create: `claw-core/pom.xml`
- Create: `claw-security/pom.xml`
- Create: `claw-memory/pom.xml`
- Create: `claw-tools/pom.xml`
- Create: `claw-model-spring-ai/pom.xml`
- Create: `claw-cli/pom.xml`
- Create: `claw-mcp/pom.xml`
- Create: `claw-gateway/pom.xml`
- Create: `claw-examples/pom.xml`

**Interfaces:**
- Consumes: none
- Produces: a buildable Maven reactor with Java 21, Spring Boot 4.1.1, Spring AI 2.0.1, and empty modules for the M0/M1 runtime.

- [ ] **Step 1: Replace the root README smoke marker**

Add this short project heading to `README.md` so the repository has a visible M0 direction before code exists:

```markdown
# Claw4J

Claw4J is a local-first agent platform built natively on Spring Boot and Spring AI.

The first implementation milestone is a terminal-based local agent that runs with Ollama, preserves sessions, and executes safe built-in tools through an auditable policy layer.
```

- [ ] **Step 2: Create root AGENTS.md engineering guide**

Create `AGENTS.md` with this content:

```markdown
# Claw4J Engineering Guide

This file is the working agreement for humans and agentic workers contributing to Claw4J. Read it before creating files, moving code, adding dependencies, or implementing a task.

## Project Direction

- Build Claw4J as a Spring Boot and Spring AI-native local-first agent platform.
- Treat OpenClaw4J as product inspiration, not as code or architecture to copy.
- Prioritize the local CLI agent runtime before Gateway, MCP, RAG, Cron, Webhook, UI, or cloud features.
- Keep local development usable without cloud credentials.

## Module Ownership

- Put provider-neutral runtime contracts in `claw-core`.
- Put Spring AI-specific adapter code in `claw-model-spring-ai`.
- Put policy, permission, path isolation, and audit code in `claw-security`.
- Put built-in executable tools in `claw-tools`.
- Put session persistence and future memory/RAG code in `claw-memory`.
- Put terminal UI and slash commands in `claw-cli`.
- Reserve `claw-mcp` for MCP integration only.
- Reserve `claw-gateway` for REST, Webhook, Cron, and external run orchestration only.
- Put sample configs and demo assets in `claw-examples`.

## Dependency Rules

- `claw-core` must not depend on Spring Boot, Spring AI, CLI, Gateway, MCP, or concrete tool implementations.
- `claw-model-spring-ai` may depend on `claw-core` and Spring AI.
- `claw-security` may depend on `claw-core`; tool execution policy must live here.
- `claw-tools` may depend on `claw-core` and `claw-security`.
- `claw-memory` may depend on `claw-core`.
- `claw-cli` may depend on all M1 runtime modules, but must not contain model orchestration logic directly.
- `claw-gateway` must reuse `AgentRunner` when implemented; do not create a second agent runtime.

## Package And File Placement

- Use root package `io.github.claw4j`.
- Match package names to module boundaries, for example `io.github.claw4j.core.agent` and `io.github.claw4j.tools.file`.
- Do not place Java source files directly under a module root.
- Do not add shared utility classes until two real call sites need the same behavior.
- Keep classes focused; split files when a class starts mixing runtime orchestration, configuration, IO, and formatting concerns.
- Keep resource files under `src/main/resources` in the module that owns them.

## Coding Standards

- Use Java 21 language features where they make code smaller and clearer.
- Prefer immutable records for simple value objects.
- Validate required constructor and record fields at boundaries.
- Prefer explicit, descriptive names over abbreviations.
- Keep public APIs small and stable; widen visibility only when another module truly needs it.
- Use bounded outputs for tool results and model-visible text.
- Avoid static global mutable state.
- Add comments only where they clarify non-obvious policy, lifecycle, or safety behavior.

## Security Rules

- Every tool execution must pass through `claw-security` before touching files, processes, network, or other external state.
- Default policy is deny unless a tool is explicitly allowed.
- File tools must stay inside configured workspace roots.
- Shell execution is out of scope for M1 unless a later approved plan adds it.
- HTTP tools are out of scope for M1 unless a later approved plan adds them.
- Every allowed or denied tool attempt must create an audit event.

## Testing Rules

- Add or update tests in the same task as the implementation.
- Prefer focused unit tests for core contracts, policy decisions, parsing, and persistence.
- Use integration tests only where Spring wiring or module interaction is the behavior under test.
- Run the narrow module test first, then the full reactor before claiming a milestone is done.
- Do not skip failing tests by changing assertions to match broken behavior.

## Configuration Rules

- Keep local config readable and versionable in YAML.
- Keep provider-specific details isolated behind Spring profiles or model properties.
- Never require OpenAI or other cloud API keys for the default local path.
- Do not commit local session data, logs, generated build output, or machine-specific IDE files.

## Documentation Rules

- Update `README.md` when a user-facing command, setup step, model requirement, or local workflow changes.
- Keep examples copyable and tested against the current module layout.
- Document safety defaults when adding new tools or execution modes.
```

- [ ] **Step 3: Create the parent Maven build**

Create `pom.xml` with this content:

```xml
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>

  <groupId>io.github.claw4j</groupId>
  <artifactId>claw4j-parent</artifactId>
  <version>0.1.0-SNAPSHOT</version>
  <packaging>pom</packaging>

  <name>Claw4J</name>
  <description>Local-first Spring AI agent platform</description>

  <modules>
    <module>claw-core</module>
    <module>claw-security</module>
    <module>claw-memory</module>
    <module>claw-tools</module>
    <module>claw-model-spring-ai</module>
    <module>claw-cli</module>
    <module>claw-mcp</module>
    <module>claw-gateway</module>
    <module>claw-examples</module>
  </modules>

  <properties>
    <java.version>21</java.version>
    <maven.compiler.release>${java.version}</maven.compiler.release>
    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    <spring-boot.version>4.1.1</spring-boot.version>
    <spring-ai.version>2.0.1</spring-ai.version>
    <junit.version>5.13.4</junit.version>
    <assertj.version>3.27.4</assertj.version>
    <mockito.version>5.20.0</mockito.version>
  </properties>

  <dependencyManagement>
    <dependencies>
      <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-dependencies</artifactId>
        <version>${spring-boot.version}</version>
        <type>pom</type>
        <scope>import</scope>
      </dependency>
      <dependency>
        <groupId>org.springframework.ai</groupId>
        <artifactId>spring-ai-bom</artifactId>
        <version>${spring-ai.version}</version>
        <type>pom</type>
        <scope>import</scope>
      </dependency>
      <dependency>
        <groupId>org.assertj</groupId>
        <artifactId>assertj-core</artifactId>
        <version>${assertj.version}</version>
      </dependency>
      <dependency>
        <groupId>org.mockito</groupId>
        <artifactId>mockito-junit-jupiter</artifactId>
        <version>${mockito.version}</version>
      </dependency>
    </dependencies>
  </dependencyManagement>

  <build>
    <pluginManagement>
      <plugins>
        <plugin>
          <groupId>org.apache.maven.plugins</groupId>
          <artifactId>maven-compiler-plugin</artifactId>
          <version>3.14.1</version>
        </plugin>
        <plugin>
          <groupId>org.apache.maven.plugins</groupId>
          <artifactId>maven-surefire-plugin</artifactId>
          <version>3.5.3</version>
        </plugin>
        <plugin>
          <groupId>org.springframework.boot</groupId>
          <artifactId>spring-boot-maven-plugin</artifactId>
          <version>${spring-boot.version}</version>
        </plugin>
      </plugins>
    </pluginManagement>
  </build>
</project>
```

- [ ] **Step 4: Add `.gitignore`**

Create `.gitignore`:

```gitignore
target/
.idea/
*.iml
.vscode/
.DS_Store
.claw4j/
*.log
```

- [ ] **Step 5: Create module POMs**

Create each module POM. Use this library template for `claw-core`, `claw-mcp`, `claw-gateway`, and `claw-examples`, changing only `artifactId` and `name`:

```xml
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <parent>
    <groupId>io.github.claw4j</groupId>
    <artifactId>claw4j-parent</artifactId>
    <version>0.1.0-SNAPSHOT</version>
  </parent>
  <artifactId>claw-core</artifactId>
  <name>Claw4J Core</name>

  <dependencies>
    <dependency>
      <groupId>io.projectreactor</groupId>
      <artifactId>reactor-core</artifactId>
    </dependency>
    <dependency>
      <groupId>org.junit.jupiter</groupId>
      <artifactId>junit-jupiter</artifactId>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>org.assertj</groupId>
      <artifactId>assertj-core</artifactId>
      <scope>test</scope>
    </dependency>
  </dependencies>
</project>
```

Use this dependency shape for `claw-security`:

```xml
<dependencies>
  <dependency>
    <groupId>io.github.claw4j</groupId>
    <artifactId>claw-core</artifactId>
    <version>${project.version}</version>
  </dependency>
  <dependency>
    <groupId>org.junit.jupiter</groupId>
    <artifactId>junit-jupiter</artifactId>
    <scope>test</scope>
  </dependency>
  <dependency>
    <groupId>org.assertj</groupId>
    <artifactId>assertj-core</artifactId>
    <scope>test</scope>
  </dependency>
</dependencies>
```

Use the same shape for `claw-memory`, plus Jackson:

```xml
<dependency>
  <groupId>com.fasterxml.jackson.core</groupId>
  <artifactId>jackson-databind</artifactId>
</dependency>
<dependency>
  <groupId>com.fasterxml.jackson.datatype</groupId>
  <artifactId>jackson-datatype-jsr310</artifactId>
</dependency>
```

Use this shape for `claw-tools`:

```xml
<dependency>
  <groupId>io.github.claw4j</groupId>
  <artifactId>claw-core</artifactId>
  <version>${project.version}</version>
</dependency>
<dependency>
  <groupId>io.github.claw4j</groupId>
  <artifactId>claw-security</artifactId>
  <version>${project.version}</version>
</dependency>
```

Use this shape for `claw-model-spring-ai`:

```xml
<dependency>
  <groupId>io.github.claw4j</groupId>
  <artifactId>claw-core</artifactId>
  <version>${project.version}</version>
</dependency>
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-autoconfigure</artifactId>
</dependency>
<dependency>
  <groupId>org.springframework.ai</groupId>
  <artifactId>spring-ai-starter-model-ollama</artifactId>
</dependency>
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-test</artifactId>
  <scope>test</scope>
</dependency>
<dependency>
  <groupId>org.mockito</groupId>
  <artifactId>mockito-junit-jupiter</artifactId>
  <scope>test</scope>
</dependency>
```

Use this shape for `claw-cli`:

```xml
<dependencies>
  <dependency>
    <groupId>io.github.claw4j</groupId>
    <artifactId>claw-core</artifactId>
    <version>${project.version}</version>
  </dependency>
  <dependency>
    <groupId>io.github.claw4j</groupId>
    <artifactId>claw-security</artifactId>
    <version>${project.version}</version>
  </dependency>
  <dependency>
    <groupId>io.github.claw4j</groupId>
    <artifactId>claw-memory</artifactId>
    <version>${project.version}</version>
  </dependency>
  <dependency>
    <groupId>io.github.claw4j</groupId>
    <artifactId>claw-tools</artifactId>
    <version>${project.version}</version>
  </dependency>
  <dependency>
    <groupId>io.github.claw4j</groupId>
    <artifactId>claw-model-spring-ai</artifactId>
    <version>${project.version}</version>
  </dependency>
  <dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter</artifactId>
  </dependency>
  <dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-test</artifactId>
    <scope>test</scope>
  </dependency>
</dependencies>
```

- [ ] **Step 6: Run the reactor build**

Run:

```bash
mvn -q test
```

Expected: PASS. If dependency resolution fails, fix the POM coordinate that Maven names in the failure and rerun the same command.

- [ ] **Step 7: Commit**

```bash
git add README.md AGENTS.md .gitignore pom.xml claw-*/pom.xml
git commit -m "chore: bootstrap Maven modules"
```

---

### Task 2: Core Agent, Chat, Model, Run, And Tool Contracts

**Files:**
- Create: `claw-core/src/main/java/io/github/claw4j/core/agent/AgentDefinition.java`
- Create: `claw-core/src/main/java/io/github/claw4j/core/agent/AgentSession.java`
- Create: `claw-core/src/main/java/io/github/claw4j/core/chat/ChatMessage.java`
- Create: `claw-core/src/main/java/io/github/claw4j/core/chat/ChatRole.java`
- Create: `claw-core/src/main/java/io/github/claw4j/core/model/ModelClient.java`
- Create: `claw-core/src/main/java/io/github/claw4j/core/model/ModelRequest.java`
- Create: `claw-core/src/main/java/io/github/claw4j/core/model/ModelResponse.java`
- Create: `claw-core/src/main/java/io/github/claw4j/core/run/RunEvent.java`
- Create: `claw-core/src/main/java/io/github/claw4j/core/session/ConversationStore.java`
- Create: `claw-core/src/main/java/io/github/claw4j/core/tool/ToolDescriptor.java`
- Create: `claw-core/src/main/java/io/github/claw4j/core/tool/ToolExecutor.java`
- Create: `claw-core/src/main/java/io/github/claw4j/core/tool/ToolInvocation.java`
- Create: `claw-core/src/main/java/io/github/claw4j/core/tool/ToolRegistry.java`
- Create: `claw-core/src/main/java/io/github/claw4j/core/tool/ToolResult.java`
- Create: `claw-core/src/test/java/io/github/claw4j/core/agent/AgentDefinitionTest.java`
- Create: `claw-core/src/test/java/io/github/claw4j/core/tool/ToolRegistryTest.java`

**Interfaces:**
- Consumes: build skeleton from Task 1.
- Produces: immutable records and interfaces used by every later task:
  - `AgentDefinition(String id, String name, String systemPrompt, String modelProfile, List<String> allowedTools, Path workspaceRoot)`
  - `AgentSession(String id, String agentId, Instant createdAt, Instant updatedAt)`
  - `ModelClient.stream(ModelRequest request): Flux<RunEvent>`
  - `ToolExecutor.execute(ToolInvocation invocation): ToolResult`

- [ ] **Step 1: Write failing tests for agent defaults**

Create `AgentDefinitionTest.java`:

```java
package io.github.claw4j.core.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class AgentDefinitionTest {

  @Test
  void normalizesAllowedToolsToAnImmutableCopy() {
    var tools = new java.util.ArrayList<>(List.of("file.read"));

    var definition = new AgentDefinition(
        "local-dev",
        "Local Developer",
        "You help with local code.",
        "ollama",
        tools,
        Path.of("."));

    tools.add("workspace.search");

    assertThat(definition.allowedTools()).containsExactly("file.read");
  }
}
```

Run:

```bash
mvn -q -pl claw-core -Dtest=AgentDefinitionTest test
```

Expected: FAIL because `AgentDefinition` does not exist.

- [ ] **Step 2: Implement core value records**

Create these records and enums:

```java
package io.github.claw4j.core.chat;

public enum ChatRole {
  SYSTEM,
  USER,
  ASSISTANT,
  TOOL
}
```

```java
package io.github.claw4j.core.chat;

import java.time.Instant;

public record ChatMessage(ChatRole role, String content, Instant createdAt) {
  public ChatMessage {
    if (role == null) {
      throw new IllegalArgumentException("role is required");
    }
    if (content == null || content.isBlank()) {
      throw new IllegalArgumentException("content is required");
    }
    if (createdAt == null) {
      createdAt = Instant.now();
    }
  }

  public static ChatMessage user(String content) {
    return new ChatMessage(ChatRole.USER, content, Instant.now());
  }

  public static ChatMessage assistant(String content) {
    return new ChatMessage(ChatRole.ASSISTANT, content, Instant.now());
  }
}
```

```java
package io.github.claw4j.core.agent;

import java.nio.file.Path;
import java.util.List;

public record AgentDefinition(
    String id,
    String name,
    String systemPrompt,
    String modelProfile,
    List<String> allowedTools,
    Path workspaceRoot) {

  public AgentDefinition {
    if (id == null || id.isBlank()) {
      throw new IllegalArgumentException("id is required");
    }
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("name is required");
    }
    if (systemPrompt == null || systemPrompt.isBlank()) {
      throw new IllegalArgumentException("systemPrompt is required");
    }
    if (modelProfile == null || modelProfile.isBlank()) {
      modelProfile = "ollama";
    }
    allowedTools = List.copyOf(allowedTools == null ? List.of() : allowedTools);
    workspaceRoot = workspaceRoot == null ? Path.of(".") : workspaceRoot.normalize();
  }
}
```

```java
package io.github.claw4j.core.agent;

import java.time.Instant;

public record AgentSession(String id, String agentId, Instant createdAt, Instant updatedAt) {
  public AgentSession {
    if (id == null || id.isBlank()) {
      throw new IllegalArgumentException("id is required");
    }
    if (agentId == null || agentId.isBlank()) {
      throw new IllegalArgumentException("agentId is required");
    }
    if (createdAt == null) {
      createdAt = Instant.now();
    }
    if (updatedAt == null) {
      updatedAt = createdAt;
    }
  }
}
```

- [ ] **Step 3: Implement model and run contracts**

Create:

```java
package io.github.claw4j.core.model;

import io.github.claw4j.core.agent.AgentDefinition;
import io.github.claw4j.core.agent.AgentSession;
import io.github.claw4j.core.chat.ChatMessage;
import io.github.claw4j.core.tool.ToolExecutor;
import java.util.List;
import java.util.Map;

public record ModelRequest(
    AgentDefinition agent,
    AgentSession session,
    List<ChatMessage> messages,
    Map<String, ToolExecutor> tools) {

  public ModelRequest {
    messages = List.copyOf(messages == null ? List.of() : messages);
    tools = Map.copyOf(tools == null ? Map.of() : tools);
  }
}
```

```java
package io.github.claw4j.core.model;

public record ModelResponse(String content) {
  public ModelResponse {
    if (content == null) {
      content = "";
    }
  }
}
```

```java
package io.github.claw4j.core.model;

import io.github.claw4j.core.run.RunEvent;
import reactor.core.publisher.Flux;

public interface ModelClient {
  Flux<RunEvent> stream(ModelRequest request);

  default boolean supportsToolCalling() {
    return true;
  }

  default String providerInfo() {
    return "unknown";
  }
}
```

```java
package io.github.claw4j.core.run;

import java.time.Instant;

public sealed interface RunEvent permits RunEvent.Token, RunEvent.ToolStarted,
    RunEvent.ToolCompleted, RunEvent.ToolDenied, RunEvent.Failed, RunEvent.Completed {

  Instant createdAt();

  record Token(String text, Instant createdAt) implements RunEvent {}

  record ToolStarted(String toolName, String invocationId, Instant createdAt) implements RunEvent {}

  record ToolCompleted(String toolName, String invocationId, String result, Instant createdAt)
      implements RunEvent {}

  record ToolDenied(String toolName, String invocationId, String reason, Instant createdAt)
      implements RunEvent {}

  record Failed(String message, Instant createdAt) implements RunEvent {}

  record Completed(String content, Instant createdAt) implements RunEvent {}
}
```

- [ ] **Step 4: Write failing tests for tool filtering**

Create `ToolRegistryTest.java`:

```java
package io.github.claw4j.core.tool;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.claw4j.core.agent.AgentDefinition;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class ToolRegistryTest {

  @Test
  void returnsOnlyToolsAllowedByAgent() {
    ToolExecutor fileRead = new StubTool("file.read");
    ToolExecutor search = new StubTool("workspace.search");
    ToolExecutor hidden = new StubTool("hidden");
    var registry = new ToolRegistry(List.of(fileRead, search, hidden));
    var agent = new AgentDefinition(
        "local-dev",
        "Local Developer",
        "system",
        "ollama",
        List.of("file.read", "workspace.search"),
        Path.of("."));

    assertThat(registry.allowedExecutors(agent)).containsOnlyKeys("file.read", "workspace.search");
  }

  private record StubTool(String name) implements ToolExecutor {
    @Override
    public ToolDescriptor descriptor() {
      return new ToolDescriptor(name, "stub", java.util.Map.of());
    }

    @Override
    public ToolResult execute(ToolInvocation invocation) {
      return ToolResult.success(name, "ok", 2);
    }
  }
}
```

Run:

```bash
mvn -q -pl claw-core -Dtest=ToolRegistryTest test
```

Expected: FAIL because the tool contracts do not exist.

- [ ] **Step 5: Implement tool contracts and registry**

Create:

```java
package io.github.claw4j.core.tool;

import java.util.Map;

public record ToolDescriptor(String name, String description, Map<String, String> parameters) {
  public ToolDescriptor {
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("name is required");
    }
    description = description == null ? "" : description;
    parameters = Map.copyOf(parameters == null ? Map.of() : parameters);
  }
}
```

```java
package io.github.claw4j.core.tool;

import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

public record ToolInvocation(String id, String toolName, Map<String, Object> arguments, Path workspaceRoot) {
  public ToolInvocation {
    id = id == null || id.isBlank() ? UUID.randomUUID().toString() : id;
    if (toolName == null || toolName.isBlank()) {
      throw new IllegalArgumentException("toolName is required");
    }
    arguments = Map.copyOf(arguments == null ? Map.of() : arguments);
    workspaceRoot = workspaceRoot == null ? Path.of(".") : workspaceRoot.normalize();
  }
}
```

```java
package io.github.claw4j.core.tool;

public record ToolResult(String toolName, boolean success, String content, int contentLength) {
  public static ToolResult success(String toolName, String content, int limit) {
    return new ToolResult(toolName, true, trim(content, limit), Math.min(nullSafe(content).length(), limit));
  }

  public static ToolResult failure(String toolName, String content, int limit) {
    return new ToolResult(toolName, false, trim(content, limit), Math.min(nullSafe(content).length(), limit));
  }

  private static String trim(String value, int limit) {
    var safe = nullSafe(value);
    if (limit < 0 || safe.length() <= limit) {
      return safe;
    }
    return safe.substring(0, limit) + "\n[output truncated]";
  }

  private static String nullSafe(String value) {
    return value == null ? "" : value;
  }
}
```

```java
package io.github.claw4j.core.tool;

public interface ToolExecutor {
  ToolDescriptor descriptor();

  ToolResult execute(ToolInvocation invocation);

  default String name() {
    return descriptor().name();
  }
}
```

```java
package io.github.claw4j.core.tool;

import io.github.claw4j.core.agent.AgentDefinition;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ToolRegistry {
  private final Map<String, ToolExecutor> executors;

  public ToolRegistry(List<ToolExecutor> executors) {
    this.executors = new LinkedHashMap<>();
    for (ToolExecutor executor : executors) {
      this.executors.put(executor.name(), executor);
    }
  }

  public Map<String, ToolExecutor> allowedExecutors(AgentDefinition agent) {
    var allowed = new LinkedHashMap<String, ToolExecutor>();
    for (String name : agent.allowedTools()) {
      var executor = executors.get(name);
      if (executor != null) {
        allowed.put(name, executor);
      }
    }
    return Map.copyOf(allowed);
  }
}
```

- [ ] **Step 6: Add persistence port**

Create:

```java
package io.github.claw4j.core.session;

import io.github.claw4j.core.agent.AgentSession;
import io.github.claw4j.core.chat.ChatMessage;
import java.util.List;
import java.util.Optional;

public interface ConversationStore {
  AgentSession saveSession(AgentSession session);

  Optional<AgentSession> findSession(String sessionId);

  List<AgentSession> listSessions(String agentId);

  void appendMessage(String sessionId, ChatMessage message);

  List<ChatMessage> loadMessages(String sessionId);
}
```

- [ ] **Step 7: Run core tests**

Run:

```bash
mvn -q -pl claw-core test
```

Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add claw-core
git commit -m "feat: define core agent runtime contracts"
```

---

### Task 3: Default-Deny Tool Policy And Audit Trail

**Files:**
- Create: `claw-security/src/main/java/io/github/claw4j/security/ToolPolicy.java`
- Create: `claw-security/src/main/java/io/github/claw4j/security/ToolDecision.java`
- Create: `claw-security/src/main/java/io/github/claw4j/security/DefaultToolPolicy.java`
- Create: `claw-security/src/main/java/io/github/claw4j/security/AuditEvent.java`
- Create: `claw-security/src/main/java/io/github/claw4j/security/AuditSink.java`
- Create: `claw-security/src/main/java/io/github/claw4j/security/InMemoryAuditSink.java`
- Create: `claw-security/src/test/java/io/github/claw4j/security/DefaultToolPolicyTest.java`
- Modify: `claw-security/pom.xml`

**Interfaces:**
- Consumes: `ToolInvocation` and `ToolResult` from Task 2.
- Produces:
  - `ToolPolicy.evaluate(ToolInvocation invocation): ToolDecision`
  - `AuditSink.record(AuditEvent event): void`
  - `DefaultToolPolicy(Path workspaceRoot, Set<String> allowedTools)`

- [ ] **Step 1: Write failing tests for default-deny behavior**

Create `DefaultToolPolicyTest.java`:

```java
package io.github.claw4j.security;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.claw4j.core.tool.ToolInvocation;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DefaultToolPolicyTest {

  @Test
  void deniesToolsNotInAllowlist() {
    var policy = new DefaultToolPolicy(Path.of("/workspace"), Set.of("file.read"));
    var invocation = new ToolInvocation("1", "shell.exec", Map.of(), Path.of("/workspace"));

    assertThat(policy.evaluate(invocation).allowed()).isFalse();
    assertThat(policy.evaluate(invocation).reason()).contains("not allowed");
  }

  @Test
  void allowsKnownToolInsideWorkspaceRoot() {
    var policy = new DefaultToolPolicy(Path.of("/workspace"), Set.of("file.read"));
    var invocation = new ToolInvocation(
        "1",
        "file.read",
        Map.of("path", "README.md"),
        Path.of("/workspace"));

    assertThat(policy.evaluate(invocation).allowed()).isTrue();
  }

  @Test
  void deniesParentTraversalOutsideWorkspace() {
    var policy = new DefaultToolPolicy(Path.of("/workspace"), Set.of("file.read"));
    var invocation = new ToolInvocation(
        "1",
        "file.read",
        Map.of("path", "../secret.txt"),
        Path.of("/workspace"));

    assertThat(policy.evaluate(invocation).allowed()).isFalse();
    assertThat(policy.evaluate(invocation).reason()).contains("outside workspace");
  }
}
```

Run:

```bash
mvn -q -pl claw-security -am -Dtest=DefaultToolPolicyTest test
```

Expected: FAIL because the security classes do not exist.

- [ ] **Step 2: Implement policy and decision records**

Create:

```java
package io.github.claw4j.security;

public record ToolDecision(boolean allowed, String reason) {
  public static ToolDecision allow() {
    return new ToolDecision(true, "allowed");
  }

  public static ToolDecision deny(String reason) {
    return new ToolDecision(false, reason);
  }
}
```

```java
package io.github.claw4j.security;

import io.github.claw4j.core.tool.ToolInvocation;

public interface ToolPolicy {
  ToolDecision evaluate(ToolInvocation invocation);
}
```

```java
package io.github.claw4j.security;

import io.github.claw4j.core.tool.ToolInvocation;
import java.nio.file.Path;
import java.util.Set;

public final class DefaultToolPolicy implements ToolPolicy {
  private final Path workspaceRoot;
  private final Set<String> allowedTools;

  public DefaultToolPolicy(Path workspaceRoot, Set<String> allowedTools) {
    this.workspaceRoot = workspaceRoot.toAbsolutePath().normalize();
    this.allowedTools = Set.copyOf(allowedTools);
  }

  @Override
  public ToolDecision evaluate(ToolInvocation invocation) {
    if (!allowedTools.contains(invocation.toolName())) {
      return ToolDecision.deny("tool is not allowed: " + invocation.toolName());
    }
    Object pathArg = invocation.arguments().get("path");
    if (pathArg instanceof String path) {
      Path resolved = workspaceRoot.resolve(path).normalize();
      if (!resolved.startsWith(workspaceRoot)) {
        return ToolDecision.deny("path is outside workspace: " + path);
      }
    }
    return ToolDecision.allow();
  }
}
```

- [ ] **Step 3: Implement audit records**

Create:

```java
package io.github.claw4j.security;

import java.time.Instant;

public record AuditEvent(
    String invocationId,
    String toolName,
    boolean allowed,
    String reason,
    Instant createdAt) {

  public AuditEvent {
    if (createdAt == null) {
      createdAt = Instant.now();
    }
  }
}
```

```java
package io.github.claw4j.security;

public interface AuditSink {
  void record(AuditEvent event);
}
```

```java
package io.github.claw4j.security;

import java.util.ArrayList;
import java.util.List;

public final class InMemoryAuditSink implements AuditSink {
  private final List<AuditEvent> events = new ArrayList<>();

  @Override
  public synchronized void record(AuditEvent event) {
    events.add(event);
  }

  public synchronized List<AuditEvent> events() {
    return List.copyOf(events);
  }
}
```

- [ ] **Step 4: Run security tests**

Run:

```bash
mvn -q -pl claw-security -am test
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add claw-security
git commit -m "feat: add default tool policy"
```

---

### Task 4: File-Backed Conversation Store

**Files:**
- Create: `claw-memory/src/main/java/io/github/claw4j/memory/file/FileConversationStore.java`
- Create: `claw-memory/src/main/java/io/github/claw4j/memory/file/StoredConversation.java`
- Create: `claw-memory/src/test/java/io/github/claw4j/memory/file/FileConversationStoreTest.java`
- Modify: `claw-memory/pom.xml`

**Interfaces:**
- Consumes: `ConversationStore`, `AgentSession`, and `ChatMessage` from Task 2.
- Produces: `new FileConversationStore(Path storageRoot)` with durable JSON session files under `{storageRoot}/sessions/{sessionId}.json`.

- [ ] **Step 1: Write failing persistence test**

Create `FileConversationStoreTest.java`:

```java
package io.github.claw4j.memory.file;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.claw4j.core.agent.AgentSession;
import io.github.claw4j.core.chat.ChatMessage;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;

class FileConversationStoreTest {

  @TempDir
  Path tempDir;

  @Test
  void persistsSessionAndMessagesAcrossStoreInstances() {
    var first = new FileConversationStore(tempDir);
    var session = new AgentSession("session-1", "local-dev", Instant.EPOCH, Instant.EPOCH);
    first.saveSession(session);
    first.appendMessage("session-1", ChatMessage.user("hello"));

    var second = new FileConversationStore(tempDir);

    assertThat(second.findSession("session-1")).contains(session);
    assertThat(second.loadMessages("session-1"))
        .extracting(ChatMessage::content)
        .containsExactly("hello");
  }
}
```

Run:

```bash
mvn -q -pl claw-memory -am -Dtest=FileConversationStoreTest test
```

Expected: FAIL because the file store does not exist.

- [ ] **Step 2: Implement serialized envelope**

Create:

```java
package io.github.claw4j.memory.file;

import io.github.claw4j.core.agent.AgentSession;
import io.github.claw4j.core.chat.ChatMessage;
import java.util.ArrayList;
import java.util.List;

record StoredConversation(AgentSession session, List<ChatMessage> messages) {
  StoredConversation {
    messages = new ArrayList<>(messages == null ? List.of() : messages);
  }
}
```

- [ ] **Step 3: Implement file conversation store**

Create:

```java
package io.github.claw4j.memory.file;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.github.claw4j.core.agent.AgentSession;
import io.github.claw4j.core.chat.ChatMessage;
import io.github.claw4j.core.session.ConversationStore;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

public final class FileConversationStore implements ConversationStore {
  private final Path sessionsDir;
  private final ObjectMapper objectMapper;

  public FileConversationStore(Path storageRoot) {
    this.sessionsDir = storageRoot.resolve("sessions");
    this.objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    createDirectories(sessionsDir);
  }

  @Override
  public synchronized AgentSession saveSession(AgentSession session) {
    var existing = read(session.id()).orElse(new StoredConversation(session, List.of()));
    write(session.id(), new StoredConversation(session, existing.messages()));
    return session;
  }

  @Override
  public synchronized Optional<AgentSession> findSession(String sessionId) {
    return read(sessionId).map(StoredConversation::session);
  }

  @Override
  public synchronized List<AgentSession> listSessions(String agentId) {
    try (Stream<Path> paths = Files.exists(sessionsDir) ? Files.list(sessionsDir) : Stream.empty()) {
      return paths
          .filter(path -> path.getFileName().toString().endsWith(".json"))
          .flatMap(path -> read(path.getFileName().toString().replace(".json", "")).stream())
          .map(StoredConversation::session)
          .filter(session -> session.agentId().equals(agentId))
          .toList();
    } catch (IOException exception) {
      throw new UncheckedIOException(exception);
    }
  }

  @Override
  public synchronized void appendMessage(String sessionId, ChatMessage message) {
    var stored = read(sessionId).orElseThrow(() -> new IllegalArgumentException("unknown session: " + sessionId));
    var messages = new ArrayList<>(stored.messages());
    messages.add(message);
    write(sessionId, new StoredConversation(stored.session(), messages));
  }

  @Override
  public synchronized List<ChatMessage> loadMessages(String sessionId) {
    return read(sessionId).map(StoredConversation::messages).orElse(List.of());
  }

  private Optional<StoredConversation> read(String sessionId) {
    var path = pathFor(sessionId);
    if (!Files.exists(path)) {
      return Optional.empty();
    }
    try {
      return Optional.of(objectMapper.readValue(path.toFile(), StoredConversation.class));
    } catch (IOException exception) {
      throw new UncheckedIOException(exception);
    }
  }

  private void write(String sessionId, StoredConversation stored) {
    try {
      objectMapper.writerWithDefaultPrettyPrinter().writeValue(pathFor(sessionId).toFile(), stored);
    } catch (IOException exception) {
      throw new UncheckedIOException(exception);
    }
  }

  private Path pathFor(String sessionId) {
    return sessionsDir.resolve(sessionId + ".json").normalize();
  }

  private static void createDirectories(Path path) {
    try {
      Files.createDirectories(path);
    } catch (IOException exception) {
      throw new UncheckedIOException(exception);
    }
  }
}
```

- [ ] **Step 4: Run memory tests**

Run:

```bash
mvn -q -pl claw-memory -am test
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add claw-memory
git commit -m "feat: persist conversations to local files"
```

---

### Task 5: Safe File Read And Workspace Search Tools

**Files:**
- Create: `claw-tools/src/main/java/io/github/claw4j/tools/file/FileReadTool.java`
- Create: `claw-tools/src/main/java/io/github/claw4j/tools/search/WorkspaceSearchTool.java`
- Create: `claw-tools/src/main/java/io/github/claw4j/tools/DefaultTools.java`
- Create: `claw-tools/src/test/java/io/github/claw4j/tools/file/FileReadToolTest.java`
- Create: `claw-tools/src/test/java/io/github/claw4j/tools/search/WorkspaceSearchToolTest.java`

**Interfaces:**
- Consumes: `ToolExecutor`, `ToolInvocation`, `ToolResult`, `ToolPolicy`, and `AuditSink`.
- Produces:
  - `FileReadTool(ToolPolicy policy, AuditSink auditSink, int maxChars)`
  - `WorkspaceSearchTool(ToolPolicy policy, AuditSink auditSink, int maxMatches, int maxChars)`
  - `DefaultTools.safeLocalTools(Path workspaceRoot, Set<String> allowedTools): List<ToolExecutor>`

- [ ] **Step 1: Write failing file read policy test**

Create `FileReadToolTest.java`:

```java
package io.github.claw4j.tools.file;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.claw4j.core.tool.ToolInvocation;
import io.github.claw4j.security.DefaultToolPolicy;
import io.github.claw4j.security.InMemoryAuditSink;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileReadToolTest {

  @TempDir
  Path tempDir;

  @Test
  void readsAllowedFileAndRecordsAudit() throws Exception {
    Files.writeString(tempDir.resolve("README.md"), "hello");
    var audit = new InMemoryAuditSink();
    var tool = new FileReadTool(new DefaultToolPolicy(tempDir, Set.of("file.read")), audit, 100);

    var result = tool.execute(new ToolInvocation("1", "file.read", Map.of("path", "README.md"), tempDir));

    assertThat(result.success()).isTrue();
    assertThat(result.content()).isEqualTo("hello");
    assertThat(audit.events()).hasSize(1);
    assertThat(audit.events().getFirst().allowed()).isTrue();
  }

  @Test
  void deniesTraversalAndRecordsAudit() {
    var audit = new InMemoryAuditSink();
    var tool = new FileReadTool(new DefaultToolPolicy(tempDir, Set.of("file.read")), audit, 100);

    var result = tool.execute(new ToolInvocation("1", "file.read", Map.of("path", "../secret.txt"), tempDir));

    assertThat(result.success()).isFalse();
    assertThat(result.content()).contains("outside workspace");
    assertThat(audit.events().getFirst().allowed()).isFalse();
  }
}
```

Run:

```bash
mvn -q -pl claw-tools -am -Dtest=FileReadToolTest test
```

Expected: FAIL because `FileReadTool` does not exist.

- [ ] **Step 2: Implement file read tool**

Create:

```java
package io.github.claw4j.tools.file;

import io.github.claw4j.core.tool.ToolDescriptor;
import io.github.claw4j.core.tool.ToolExecutor;
import io.github.claw4j.core.tool.ToolInvocation;
import io.github.claw4j.core.tool.ToolResult;
import io.github.claw4j.security.AuditEvent;
import io.github.claw4j.security.AuditSink;
import io.github.claw4j.security.ToolPolicy;
import java.io.IOException;
import java.nio.file.Files;
import java.time.Instant;
import java.util.Map;

public final class FileReadTool implements ToolExecutor {
  private final ToolPolicy policy;
  private final AuditSink auditSink;
  private final int maxChars;

  public FileReadTool(ToolPolicy policy, AuditSink auditSink, int maxChars) {
    this.policy = policy;
    this.auditSink = auditSink;
    this.maxChars = maxChars;
  }

  @Override
  public ToolDescriptor descriptor() {
    return new ToolDescriptor("file.read", "Read a text file inside the workspace.", Map.of("path", "string"));
  }

  @Override
  public ToolResult execute(ToolInvocation invocation) {
    var decision = policy.evaluate(invocation);
    auditSink.record(new AuditEvent(invocation.id(), invocation.toolName(), decision.allowed(), decision.reason(), Instant.now()));
    if (!decision.allowed()) {
      return ToolResult.failure(name(), decision.reason(), maxChars);
    }
    Object path = invocation.arguments().get("path");
    if (!(path instanceof String relativePath) || relativePath.isBlank()) {
      return ToolResult.failure(name(), "path argument is required", maxChars);
    }
    var resolved = invocation.workspaceRoot().toAbsolutePath().normalize().resolve(relativePath).normalize();
    try {
      return ToolResult.success(name(), Files.readString(resolved), maxChars);
    } catch (IOException exception) {
      return ToolResult.failure(name(), exception.getMessage(), maxChars);
    }
  }
}
```

- [ ] **Step 3: Write failing workspace search test**

Create `WorkspaceSearchToolTest.java`:

```java
package io.github.claw4j.tools.search;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.claw4j.core.tool.ToolInvocation;
import io.github.claw4j.security.DefaultToolPolicy;
import io.github.claw4j.security.InMemoryAuditSink;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceSearchToolTest {

  @TempDir
  Path tempDir;

  @Test
  void returnsBoundedMatchesInsideWorkspace() throws Exception {
    Files.writeString(tempDir.resolve("a.txt"), "alpha\nbeta\n");
    Files.writeString(tempDir.resolve("b.txt"), "alpha beta\n");
    var audit = new InMemoryAuditSink();
    var tool = new WorkspaceSearchTool(new DefaultToolPolicy(tempDir, Set.of("workspace.search")), audit, 10, 500);

    var result = tool.execute(new ToolInvocation("1", "workspace.search", Map.of("query", "alpha"), tempDir));

    assertThat(result.success()).isTrue();
    assertThat(result.content()).contains("a.txt:1:alpha");
    assertThat(result.content()).contains("b.txt:1:alpha beta");
  }
}
```

Run:

```bash
mvn -q -pl claw-tools -am -Dtest=WorkspaceSearchToolTest test
```

Expected: FAIL because `WorkspaceSearchTool` does not exist.

- [ ] **Step 4: Implement Java-based workspace search**

Create:

```java
package io.github.claw4j.tools.search;

import io.github.claw4j.core.tool.ToolDescriptor;
import io.github.claw4j.core.tool.ToolExecutor;
import io.github.claw4j.core.tool.ToolInvocation;
import io.github.claw4j.core.tool.ToolResult;
import io.github.claw4j.security.AuditEvent;
import io.github.claw4j.security.AuditSink;
import io.github.claw4j.security.ToolPolicy;
import java.io.IOException;
import java.nio.file.Files;
import java.time.Instant;
import java.util.Map;

public final class WorkspaceSearchTool implements ToolExecutor {
  private final ToolPolicy policy;
  private final AuditSink auditSink;
  private final int maxMatches;
  private final int maxChars;

  public WorkspaceSearchTool(ToolPolicy policy, AuditSink auditSink, int maxMatches, int maxChars) {
    this.policy = policy;
    this.auditSink = auditSink;
    this.maxMatches = maxMatches;
    this.maxChars = maxChars;
  }

  @Override
  public ToolDescriptor descriptor() {
    return new ToolDescriptor("workspace.search", "Search text files inside the workspace.", Map.of("query", "string"));
  }

  @Override
  public ToolResult execute(ToolInvocation invocation) {
    var decision = policy.evaluate(invocation);
    auditSink.record(new AuditEvent(invocation.id(), invocation.toolName(), decision.allowed(), decision.reason(), Instant.now()));
    if (!decision.allowed()) {
      return ToolResult.failure(name(), decision.reason(), maxChars);
    }
    Object query = invocation.arguments().get("query");
    if (!(query instanceof String needle) || needle.isBlank()) {
      return ToolResult.failure(name(), "query argument is required", maxChars);
    }
    var root = invocation.workspaceRoot().toAbsolutePath().normalize();
    var matches = new StringBuilder();
    try (var paths = Files.walk(root)) {
      var files = paths.filter(Files::isRegularFile).toList();
      int count = 0;
      for (var file : files) {
        var relative = root.relativize(file);
        if (relative.toString().contains("target")) {
          continue;
        }
        var lines = Files.readAllLines(file);
        for (int index = 0; index < lines.size(); index++) {
          if (lines.get(index).contains(needle)) {
            matches.append(relative).append(":").append(index + 1).append(":").append(lines.get(index)).append('\n');
            count++;
            if (count >= maxMatches) {
              return ToolResult.success(name(), matches.toString(), maxChars);
            }
          }
        }
      }
    } catch (IOException exception) {
      return ToolResult.failure(name(), exception.getMessage(), maxChars);
    }
    return ToolResult.success(name(), matches.toString(), maxChars);
  }
}
```

- [ ] **Step 5: Implement built-in tool factory**

Create:

```java
package io.github.claw4j.tools;

import io.github.claw4j.core.tool.ToolExecutor;
import io.github.claw4j.security.DefaultToolPolicy;
import io.github.claw4j.security.InMemoryAuditSink;
import io.github.claw4j.tools.file.FileReadTool;
import io.github.claw4j.tools.search.WorkspaceSearchTool;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

public final class DefaultTools {
  private DefaultTools() {}

  public static List<ToolExecutor> safeLocalTools(Path workspaceRoot, Set<String> allowedTools) {
    var auditSink = new InMemoryAuditSink();
    var policy = new DefaultToolPolicy(workspaceRoot, allowedTools);
    return List.of(
        new FileReadTool(policy, auditSink, 8_000),
        new WorkspaceSearchTool(policy, auditSink, 25, 8_000));
  }
}
```

- [ ] **Step 6: Run tools tests**

Run:

```bash
mvn -q -pl claw-tools -am test
```

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add claw-tools
git commit -m "feat: add safe local tools"
```

---

### Task 6: Spring AI Model Adapter With Ollama Profile

**Files:**
- Create: `claw-model-spring-ai/src/main/java/io/github/claw4j/model/springai/ClawModelProperties.java`
- Create: `claw-model-spring-ai/src/main/java/io/github/claw4j/model/springai/SpringAiModelAutoConfiguration.java`
- Create: `claw-model-spring-ai/src/main/java/io/github/claw4j/model/springai/SpringAiModelClient.java`
- Create: `claw-model-spring-ai/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- Create: `claw-model-spring-ai/src/test/java/io/github/claw4j/model/springai/ClawModelPropertiesTest.java`
- Create: `claw-model-spring-ai/src/test/java/io/github/claw4j/model/springai/SpringAiModelAutoConfigurationTest.java`

**Interfaces:**
- Consumes: `ModelClient`, `ModelRequest`, and `RunEvent`.
- Produces: a Spring bean of type `ModelClient` when Spring AI `ChatClient.Builder` is available.

- [ ] **Step 1: Write failing properties test**

Create `ClawModelPropertiesTest.java`:

```java
package io.github.claw4j.model.springai;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ClawModelPropertiesTest {

  @Test
  void defaultsToOllamaAndStreaming() {
    var properties = new ClawModelProperties();

    assertThat(properties.provider()).isEqualTo("ollama");
    assertThat(properties.model()).isEqualTo("qwen2.5-coder");
    assertThat(properties.stream()).isTrue();
  }
}
```

Run:

```bash
mvn -q -pl claw-model-spring-ai -am -Dtest=ClawModelPropertiesTest test
```

Expected: FAIL because `ClawModelProperties` does not exist.

- [ ] **Step 2: Implement model properties**

Create:

```java
package io.github.claw4j.model.springai;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "claw.model")
public class ClawModelProperties {
  private String provider = "ollama";
  private String model = "qwen2.5-coder";
  private boolean stream = true;

  public String provider() {
    return provider;
  }

  public void setProvider(String provider) {
    this.provider = provider;
  }

  public String model() {
    return model;
  }

  public void setModel(String model) {
    this.model = model;
  }

  public boolean stream() {
    return stream;
  }

  public void setStream(boolean stream) {
    this.stream = stream;
  }
}
```

- [ ] **Step 3: Write failing auto-configuration test**

Create `SpringAiModelAutoConfigurationTest.java`:

```java
package io.github.claw4j.model.springai;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.claw4j.core.model.ModelClient;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class SpringAiModelAutoConfigurationTest {

  private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
      .withBean(ChatClient.Builder.class, () -> org.mockito.Mockito.mock(ChatClient.Builder.class))
      .withUserConfiguration(SpringAiModelAutoConfiguration.class);

  @Test
  void createsModelClientBean() {
    contextRunner.run(context ->
        assertThat(context).hasSingleBean(ModelClient.class));
  }
}
```

Run:

```bash
mvn -q -pl claw-model-spring-ai -am -Dtest=SpringAiModelAutoConfigurationTest test
```

Expected: FAIL because auto-configuration does not exist.

- [ ] **Step 4: Implement Spring AI model client**

Create:

```java
package io.github.claw4j.model.springai;

import io.github.claw4j.core.chat.ChatRole;
import io.github.claw4j.core.model.ModelClient;
import io.github.claw4j.core.model.ModelRequest;
import io.github.claw4j.core.run.RunEvent;
import java.time.Instant;
import org.springframework.ai.chat.client.ChatClient;
import reactor.core.publisher.Flux;

public final class SpringAiModelClient implements ModelClient {
  private final ChatClient.Builder chatClientBuilder;
  private final ClawModelProperties properties;

  public SpringAiModelClient(ChatClient.Builder chatClientBuilder, ClawModelProperties properties) {
    this.chatClientBuilder = chatClientBuilder;
    this.properties = properties;
  }

  @Override
  public Flux<RunEvent> stream(ModelRequest request) {
    var prompt = new StringBuilder(request.agent().systemPrompt()).append("\n\n");
    for (var message : request.messages()) {
      if (message.role() != ChatRole.SYSTEM) {
        prompt.append(message.role().name()).append(": ").append(message.content()).append('\n');
      }
    }
    return chatClientBuilder.build()
        .prompt(prompt.toString())
        .stream()
        .content()
        .map(text -> new RunEvent.Token(text, Instant.now()))
        .cast(RunEvent.class)
        .concatWithValues(new RunEvent.Completed("", Instant.now()));
  }

  @Override
  public String providerInfo() {
    return properties.provider() + ":" + properties.model();
  }
}
```

This first adapter streams plain content. Spring AI-native tool callbacks are connected in Task 7 after `AgentRunner` owns the tool execution path.

- [ ] **Step 5: Implement auto-configuration**

Create:

```java
package io.github.claw4j.model.springai;

import io.github.claw4j.core.model.ModelClient;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@EnableConfigurationProperties(ClawModelProperties.class)
public class SpringAiModelAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
  ModelClient modelClient(ChatClient.Builder chatClientBuilder, ClawModelProperties properties) {
    return new SpringAiModelClient(chatClientBuilder, properties);
  }
}
```

Create `claw-model-spring-ai/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`:

```text
io.github.claw4j.model.springai.SpringAiModelAutoConfiguration
```

- [ ] **Step 6: Run model adapter tests**

Run:

```bash
mvn -q -pl claw-model-spring-ai -am test
```

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add claw-model-spring-ai
git commit -m "feat: add Spring AI model adapter"
```

---

### Task 7: Minimal AgentRunner With Session Persistence

**Files:**
- Create: `claw-core/src/main/java/io/github/claw4j/core/agent/AgentRunner.java`
- Create: `claw-core/src/test/java/io/github/claw4j/core/agent/AgentRunnerTest.java`

**Interfaces:**
- Consumes: `ModelClient`, `ConversationStore`, `ToolRegistry`, `AgentDefinition`, `AgentSession`, and `ChatMessage`.
- Produces: `AgentRunner.run(AgentDefinition agent, AgentSession session, String userInput): Flux<RunEvent>`.

- [ ] **Step 1: Write failing runner test**

Create `AgentRunnerTest.java`:

```java
package io.github.claw4j.core.agent;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.claw4j.core.chat.ChatMessage;
import io.github.claw4j.core.model.ModelClient;
import io.github.claw4j.core.run.RunEvent;
import io.github.claw4j.core.session.ConversationStore;
import io.github.claw4j.core.tool.ToolRegistry;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

class AgentRunnerTest {

  @Test
  void persistsUserMessageAndAssistantCompletion() {
    var store = new InMemoryConversationStore();
    var session = new AgentSession("s1", "local-dev", Instant.EPOCH, Instant.EPOCH);
    store.saveSession(session);
    ModelClient model = request -> Flux.just(
        new RunEvent.Token("hi", Instant.EPOCH),
        new RunEvent.Completed("hi", Instant.EPOCH));
    var runner = new AgentRunner(model, store, new ToolRegistry(List.of()));
    var agent = new AgentDefinition("local-dev", "Local Developer", "system", "ollama", List.of(), Path.of("."));

    var events = runner.run(agent, session, "hello").collectList().block();

    assertThat(events).hasSize(2);
    assertThat(store.loadMessages("s1")).extracting(ChatMessage::content).containsExactly("hello", "hi");
  }

  private static final class InMemoryConversationStore implements ConversationStore {
    private final List<ChatMessage> messages = new ArrayList<>();
    private AgentSession session;

    @Override
    public AgentSession saveSession(AgentSession session) {
      this.session = session;
      return session;
    }

    @Override
    public java.util.Optional<AgentSession> findSession(String sessionId) {
      return java.util.Optional.ofNullable(session);
    }

    @Override
    public List<AgentSession> listSessions(String agentId) {
      return session == null ? List.of() : List.of(session);
    }

    @Override
    public void appendMessage(String sessionId, ChatMessage message) {
      messages.add(message);
    }

    @Override
    public List<ChatMessage> loadMessages(String sessionId) {
      return List.copyOf(messages);
    }
  }
}
```

Run:

```bash
mvn -q -pl claw-core -Dtest=AgentRunnerTest test
```

Expected: FAIL because `AgentRunner` does not exist.

- [ ] **Step 2: Implement AgentRunner**

Create:

```java
package io.github.claw4j.core.agent;

import io.github.claw4j.core.chat.ChatMessage;
import io.github.claw4j.core.model.ModelClient;
import io.github.claw4j.core.model.ModelRequest;
import io.github.claw4j.core.run.RunEvent;
import io.github.claw4j.core.session.ConversationStore;
import io.github.claw4j.core.tool.ToolRegistry;
import java.util.ArrayList;
import reactor.core.publisher.Flux;

public final class AgentRunner {
  private final ModelClient modelClient;
  private final ConversationStore conversationStore;
  private final ToolRegistry toolRegistry;

  public AgentRunner(ModelClient modelClient, ConversationStore conversationStore, ToolRegistry toolRegistry) {
    this.modelClient = modelClient;
    this.conversationStore = conversationStore;
    this.toolRegistry = toolRegistry;
  }

  public Flux<RunEvent> run(AgentDefinition agent, AgentSession session, String userInput) {
    conversationStore.appendMessage(session.id(), ChatMessage.user(userInput));
    var messages = new ArrayList<>(conversationStore.loadMessages(session.id()));
    var request = new ModelRequest(agent, session, messages, toolRegistry.allowedExecutors(agent));

    return modelClient.stream(request)
        .doOnNext(event -> {
          if (event instanceof RunEvent.Completed completed && !completed.content().isBlank()) {
            conversationStore.appendMessage(session.id(), ChatMessage.assistant(completed.content()));
          }
        });
  }
}
```

- [ ] **Step 3: Make streamed completion content reliable**

If the model adapter emits `Completed("")`, update `AgentRunner` to collect token text before writing the assistant message:

```java
public Flux<RunEvent> run(AgentDefinition agent, AgentSession session, String userInput) {
  conversationStore.appendMessage(session.id(), ChatMessage.user(userInput));
  var messages = new ArrayList<>(conversationStore.loadMessages(session.id()));
  var request = new ModelRequest(agent, session, messages, toolRegistry.allowedExecutors(agent));
  var assistant = new StringBuilder();

  return modelClient.stream(request)
      .doOnNext(event -> {
        if (event instanceof RunEvent.Token token) {
          assistant.append(token.text());
        }
        if (event instanceof RunEvent.Completed completed) {
          var content = completed.content().isBlank() ? assistant.toString() : completed.content();
          if (!content.isBlank()) {
            conversationStore.appendMessage(session.id(), ChatMessage.assistant(content));
          }
        }
      });
}
```

- [ ] **Step 4: Run core tests**

Run:

```bash
mvn -q -pl claw-core test
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add claw-core
git commit -m "feat: add minimal agent runner"
```

---

### Task 8: CLI Application And Slash Commands

**Files:**
- Create: `claw-cli/src/main/java/io/github/claw4j/cli/ClawCliApplication.java`
- Create: `claw-cli/src/main/java/io/github/claw4j/cli/ClawCliRunner.java`
- Create: `claw-cli/src/main/java/io/github/claw4j/cli/SlashCommand.java`
- Create: `claw-cli/src/main/java/io/github/claw4j/cli/CliSessionState.java`
- Create: `claw-cli/src/main/resources/application.yml`
- Create: `claw-cli/src/main/resources/prompts/local-dev.md`
- Create: `claw-cli/src/test/java/io/github/claw4j/cli/SlashCommandTest.java`
- Create: `claw-cli/src/test/java/io/github/claw4j/cli/CliSessionStateTest.java`

**Interfaces:**
- Consumes: `AgentRunner`, `AgentDefinition`, `AgentSession`, `FileConversationStore`, `DefaultTools`, and `ModelClient`.
- Produces: executable `claw-cli` application with `/agents`, `/use`, `/reset`, `/help`, and `/exit`.

- [ ] **Step 1: Write failing slash command parser tests**

Create `SlashCommandTest.java`:

```java
package io.github.claw4j.cli;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SlashCommandTest {

  @Test
  void parsesUseCommandWithArgument() {
    var command = SlashCommand.parse("/use local-dev");

    assertThat(command.name()).isEqualTo("use");
    assertThat(command.argument()).isEqualTo("local-dev");
  }

  @Test
  void treatsPlainTextAsMessage() {
    var command = SlashCommand.parse("hello agent");

    assertThat(command.name()).isEqualTo("message");
    assertThat(command.argument()).isEqualTo("hello agent");
  }
}
```

Run:

```bash
mvn -q -pl claw-cli -am -Dtest=SlashCommandTest test
```

Expected: FAIL because `SlashCommand` does not exist.

- [ ] **Step 2: Implement slash command parser**

Create:

```java
package io.github.claw4j.cli;

public record SlashCommand(String name, String argument) {

  public static SlashCommand parse(String input) {
    if (input == null || input.isBlank()) {
      return new SlashCommand("empty", "");
    }
    var trimmed = input.trim();
    if (!trimmed.startsWith("/")) {
      return new SlashCommand("message", trimmed);
    }
    var withoutSlash = trimmed.substring(1);
    var space = withoutSlash.indexOf(' ');
    if (space < 0) {
      return new SlashCommand(withoutSlash, "");
    }
    return new SlashCommand(withoutSlash.substring(0, space), withoutSlash.substring(space + 1).trim());
  }
}
```

- [ ] **Step 3: Write failing session state test**

Create `CliSessionStateTest.java`:

```java
package io.github.claw4j.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.claw4j.core.agent.AgentDefinition;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class CliSessionStateTest {

  @Test
  void createsDefaultSessionForInitialAgent() {
    var agent = new AgentDefinition("local-dev", "Local Developer", "system", "ollama", List.of("file.read"), Path.of("."));
    var state = CliSessionState.initial(List.of(agent));

    assertThat(state.activeAgent().id()).isEqualTo("local-dev");
    assertThat(state.activeSession().agentId()).isEqualTo("local-dev");
  }
}
```

Run:

```bash
mvn -q -pl claw-cli -am -Dtest=CliSessionStateTest test
```

Expected: FAIL because `CliSessionState` does not exist.

- [ ] **Step 4: Implement CLI session state**

Create:

```java
package io.github.claw4j.cli;

import io.github.claw4j.core.agent.AgentDefinition;
import io.github.claw4j.core.agent.AgentSession;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class CliSessionState {
  private final List<AgentDefinition> agents;
  private AgentDefinition activeAgent;
  private AgentSession activeSession;

  private CliSessionState(List<AgentDefinition> agents, AgentDefinition activeAgent, AgentSession activeSession) {
    this.agents = List.copyOf(agents);
    this.activeAgent = activeAgent;
    this.activeSession = activeSession;
  }

  public static CliSessionState initial(List<AgentDefinition> agents) {
    if (agents.isEmpty()) {
      throw new IllegalArgumentException("at least one agent is required");
    }
    var agent = agents.getFirst();
    var now = Instant.now();
    return new CliSessionState(agents, agent, new AgentSession(UUID.randomUUID().toString(), agent.id(), now, now));
  }

  public AgentDefinition activeAgent() {
    return activeAgent;
  }

  public AgentSession activeSession() {
    return activeSession;
  }

  public List<AgentDefinition> agents() {
    return agents;
  }

  public boolean useAgent(String agentId) {
    return agents.stream()
        .filter(agent -> agent.id().equals(agentId))
        .findFirst()
        .map(agent -> {
          activeAgent = agent;
          var now = Instant.now();
          activeSession = new AgentSession(UUID.randomUUID().toString(), agent.id(), now, now);
          return true;
        })
        .orElse(false);
  }

  public void resetSession() {
    var now = Instant.now();
    activeSession = new AgentSession(UUID.randomUUID().toString(), activeAgent.id(), now, now);
  }
}
```

- [ ] **Step 5: Implement Spring Boot application entry point**

Create:

```java
package io.github.claw4j.cli;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "io.github.claw4j")
public class ClawCliApplication {
  public static void main(String[] args) {
    SpringApplication.run(ClawCliApplication.class, args);
  }
}
```

- [ ] **Step 6: Implement CLI runner**

Create:

```java
package io.github.claw4j.cli;

import io.github.claw4j.core.agent.AgentDefinition;
import io.github.claw4j.core.agent.AgentRunner;
import io.github.claw4j.core.model.ModelClient;
import io.github.claw4j.core.run.RunEvent;
import io.github.claw4j.core.session.ConversationStore;
import io.github.claw4j.core.tool.ToolRegistry;
import io.github.claw4j.memory.file.FileConversationStore;
import io.github.claw4j.tools.DefaultTools;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public final class ClawCliRunner implements ApplicationRunner {
  private final ModelClient modelClient;

  public ClawCliRunner(ModelClient modelClient) {
    this.modelClient = modelClient;
  }

  @Override
  public void run(ApplicationArguments args) throws Exception {
    var workspace = Path.of(".").toAbsolutePath().normalize();
    var agent = new AgentDefinition(
        "local-dev",
        "Local Developer",
        readPrompt(),
        "ollama",
        List.of("file.read", "workspace.search"),
        workspace);
    var state = CliSessionState.initial(List.of(agent));
    ConversationStore store = new FileConversationStore(workspace.resolve(".claw4j"));
    store.saveSession(state.activeSession());
    var tools = DefaultTools.safeLocalTools(workspace, Set.copyOf(agent.allowedTools()));
    var runner = new AgentRunner(modelClient, store, new ToolRegistry(tools));

    System.out.println("Claw4J local agent ready. Provider: " + modelClient.providerInfo());
    System.out.println("Type /help for commands.");

    var reader = new BufferedReader(new InputStreamReader(System.in));
    while (true) {
      System.out.print("claw4j> ");
      var line = reader.readLine();
      if (line == null) {
        break;
      }
      var command = SlashCommand.parse(line);
      if (command.name().equals("empty")) {
        continue;
      }
      if (command.name().equals("exit")) {
        break;
      }
      if (command.name().equals("help")) {
        System.out.println("/agents, /use <agentId>, /reset, /help, /exit");
        continue;
      }
      if (command.name().equals("agents")) {
        state.agents().forEach(item -> System.out.println(item.id() + " - " + item.name()));
        continue;
      }
      if (command.name().equals("reset")) {
        state.resetSession();
        store.saveSession(state.activeSession());
        System.out.println("Session reset: " + state.activeSession().id());
        continue;
      }
      if (command.name().equals("use")) {
        boolean changed = state.useAgent(command.argument());
        if (changed) {
          store.saveSession(state.activeSession());
          System.out.println("Active agent: " + state.activeAgent().id());
        } else {
          System.out.println("Unknown agent: " + command.argument());
        }
        continue;
      }
      runner.run(state.activeAgent(), state.activeSession(), command.argument())
          .doOnNext(event -> {
            if (event instanceof RunEvent.Token token) {
              System.out.print(token.text());
            }
            if (event instanceof RunEvent.Failed failed) {
              System.out.println("Error: " + failed.message());
            }
          })
          .blockLast();
      System.out.println();
    }
  }

  private String readPrompt() {
    return "You are Claw4J, a local-first coding assistant. Be concise, safe, and helpful.";
  }
}
```

- [ ] **Step 7: Add local config and prompt resource**

Create `application.yml`:

```yaml
spring:
  main:
    web-application-type: none
  ai:
    ollama:
      base-url: http://localhost:11434
      chat:
        options:
          model: qwen2.5-coder

claw:
  model:
    provider: ollama
    model: qwen2.5-coder
    stream: true
```

Create `prompts/local-dev.md`:

```markdown
You are Claw4J, a local-first coding assistant.

Rules:
- Be concise and direct.
- Use tools only when they are relevant.
- Stay inside the configured workspace.
- Explain denied tool calls briefly.
```

- [ ] **Step 8: Run CLI tests and package**

Run:

```bash
mvn -q -pl claw-cli -am test
mvn -q -pl claw-cli -am package -DskipTests
```

Expected: PASS.

- [ ] **Step 9: Commit**

```bash
git add claw-cli
git commit -m "feat: add local CLI agent"
```

---

### Task 9: M1 Documentation And Smoke Verification

**Files:**
- Modify: `README.md`
- Create: `claw-examples/local-agent/application.yml`
- Create: `claw-examples/local-agent/prompts/local-dev.md`

**Interfaces:**
- Consumes: M1 executable CLI from Task 8.
- Produces: first-run docs and copyable local configuration.

- [ ] **Step 1: Update README with exact first-run flow**

Replace `README.md` with:

````markdown
# Claw4J

Claw4J is a local-first agent platform built natively on Spring Boot and Spring AI.

The M1 milestone is a terminal-based local agent that runs with Ollama, preserves sessions, and executes safe built-in tools through an auditable policy layer.

## Requirements

- Java 21
- Maven 3.9+
- Ollama running at `http://localhost:11434`
- Local model `qwen2.5-coder`

## First Run

Pull the default local model:

```bash
ollama pull qwen2.5-coder
```

Build the project:

```bash
mvn -q test
```

Start the CLI:

```bash
mvn -q -pl claw-cli -am spring-boot:run
```

Useful commands:

- `/help` prints commands.
- `/agents` lists configured agents.
- `/use local-dev` switches to the default agent.
- `/reset` starts a fresh session.
- `/exit` closes the CLI.

Local session data is written under `.claw4j/sessions`.
````

- [ ] **Step 2: Add example config**

Create `claw-examples/local-agent/application.yml`:

```yaml
spring:
  main:
    web-application-type: none
  ai:
    ollama:
      base-url: http://localhost:11434
      chat:
        options:
          model: qwen2.5-coder

claw:
  workspace:
    roots:
      - .
  model:
    provider: ollama
    model: qwen2.5-coder
    stream: true
  agents:
    - id: local-dev
      name: Local Developer
      systemPrompt: prompts/local-dev.md
      tools:
        - file.read
        - workspace.search
```

Create `claw-examples/local-agent/prompts/local-dev.md`:

```markdown
You are Claw4J, a local-first coding assistant.

Rules:
- Stay inside the configured workspace.
- Prefer reading project files before making claims about the codebase.
- Keep tool output concise.
- Explain policy denials in one sentence.
```

- [ ] **Step 3: Run full reactor verification**

Run:

```bash
mvn -q test
```

Expected: PASS.

- [ ] **Step 4: Run packaging verification**

Run:

```bash
mvn -q -pl claw-cli -am package -DskipTests
```

Expected: PASS and `claw-cli/target/claw-cli-0.1.0-SNAPSHOT.jar` exists.

- [ ] **Step 5: Optional local model smoke**

Run this only when Ollama is running locally:

```bash
mvn -q -pl claw-cli -am spring-boot:run
```

Manual check:

```text
claw4j> /help
claw4j> hello
claw4j> /exit
```

Expected: `/help` prints commands, `hello` streams a model response, and `.claw4j/sessions` contains one JSON session file.

- [ ] **Step 6: Commit**

```bash
git add README.md claw-examples
git commit -m "docs: add local agent first-run guide"
```

---

## M0/M1 Definition Of Done

- `mvn -q test` passes for the full reactor.
- `mvn -q -pl claw-cli -am package -DskipTests` creates the CLI jar.
- Root `AGENTS.md` defines engineering conventions, module ownership, dependency rules, security rules, testing rules, and documentation rules.
- `claw-core` has no Spring Boot or Spring AI dependencies.
- CLI starts with Ollama configuration and no cloud credentials.
- `/help`, `/agents`, `/use local-dev`, `/reset`, and `/exit` are available.
- Conversation files persist under `.claw4j/sessions`.
- `file.read` and `workspace.search` tool executions pass through `DefaultToolPolicy`.
- Tool denials produce audit events.
- README explains Java, Maven, Ollama, build, run, and CLI commands.

## Deferred To Next Plans

- M2: conversation summaries, local document ingestion, embeddings, vector store, and retrieval advisor.
- M3: MCP client configuration, MCP tool discovery, tool namespacing, and MCP tool policy routing.
- M4: Gateway REST API, run records, streaming or polling, and Webhook trigger support.
- M5: Cron definitions, retry policy, run logs, and unattended execution defaults.
