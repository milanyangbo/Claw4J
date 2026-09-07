---
name: openspec-verify
description: Use when the user wants to verify an implemented OpenSpec change before archive, commit, push, or handoff, proving artifacts, tasks, code checks, and acceptance criteria are complete.
allowed-tools: Bash(openspec:*), Bash(mvn:*), Bash(git:*), Bash(curl:*)
license: MIT
metadata:
  author: openspec
  version: "1.0"
  generatedBy: "manual"
---

Verify an implemented OpenSpec change. This workflow proves completion; it does not implement, sync, archive, or edit project files.

**Store selection:** If the user names a store (a store is a standalone OpenSpec repo registered on this machine) or the work lives in one, run `openspec store list --json` to discover registered store ids, then pass `--store <id>` on the commands that read specs and changes (`status`, `instructions`, `list`, `show`, `validate`, `doctor`, `context`, `schemas`, `view`). Once selected, treat `--store <id>` as sticky for the rest of the workflow. Every unscoped example below is shorthand: before running it, append the flag. Without a store, commands act on the nearest local `openspec/` root.

**Input:** Optionally specify a change name. If omitted, infer it from conversation context, auto-select the only active change, or run `openspec list --json` and ask the user when ambiguous.

**Steps**

1. **Select the change**

   Announce: "Using change: <name>" and how to override (for example, `$openspec-verify <other>`).

2. **Resolve status and context**

   Run:
   ```bash
   openspec status --change "<name>" --json
   ```

   Parse `schemaName`, `planningHome`, `changeRoot`, `artifactPaths`, `artifacts`, and `actionContext`. Use these paths instead of assuming repo-local locations or spec-driven artifact names.

   If available, run:
   ```bash
   openspec instructions apply --change "<name>" --json
   ```

   Use `context`, `operationGuidance`, and `contextFiles` as prompt-level inputs only. They inform verification scope, but they do not prove completion and must not be copied into output files.

3. **Check artifacts and tasks**

   - Confirm required artifacts are `done` or explicitly `skipped`.
   - Read existing artifact files from `artifactPaths.<id>.existingOutputPaths`.
   - If an artifact is a task/checklist file, count incomplete `- [ ]` items and completed `- [x]` items.
   - Do not mark checkboxes complete during verification. If tasks are incomplete, report them and stop before archive/push recommendations.

4. **Run OpenSpec validation**

   Run the change validation in strict mode:
   ```bash
   openspec validate "<name>" --strict
   ```

   If main specs were already synced or the change is archived, also run the relevant specs/archive check:
   ```bash
   openspec validate --specs --strict
   openspec validate --archived
   ```

   Treat validation as artifact correctness only. It does not prove runtime behavior.

5. **Run implementation checks**

   Build the check list from the change's tasks, design, specs, and project instructions:
   - Prefer exact commands already named in `tasks.md` or project instructions.
   - For Maven projects, run module-level compile/test commands named by the tasks before broad root commands.
   - Compare changed files with task scope using `git status --short` and `git diff --name-only`; report any file outside the allowed list instead of normalizing it silently.
   - For live service checks (`curl`, Docker, Nacos, load balancing, fallback), first determine whether the required dependencies and services are running. If not, report them as blocked/manual with the exact commands or documentation section to run. Never claim runtime verification from static tests alone.

6. **Assess acceptance criteria**

   For each requirement or task acceptance point, classify evidence as:
   - `pass`: command output, test result, or observed runtime behavior proves it.
   - `blocked`: required dependency or service is unavailable.
   - `fail`: command/test/runtime check failed.
   - `not-run`: intentionally skipped by user direction.

   Load-balancing and fallback claims require runtime evidence, such as repeated successful service-name calls across more than one healthy instance or a downstream outage triggering the configured fallback.

7. **Report the result**

   Output:
   - Change name and schema.
   - Artifact/task completion summary.
   - Commands run and pass/fail status.
   - Runtime smoke-test evidence or explicit blockers.
   - Final verdict: `VERIFIED`, `FAILED`, or `BLOCKED`.
   - Next command only when appropriate: archive after `VERIFIED`, apply/fix after `FAILED`, or start dependencies and rerun after `BLOCKED`.

**Guardrails**

- Verification is read-only for project artifacts and code. Do not edit implementation files, planning files, checkboxes, specs, or archives.
- Do not archive, sync specs, commit, or push as part of this skill unless the user separately asks after verification succeeds.
- Do not treat `openspec validate` or a green compile as proof of business behavior when the spec requires runtime behavior.
- Do not hide skipped checks. Every acceptance point needs evidence or an explicit blocker.
- Do not silently broaden or narrow the change scope; report mismatches between tasks, changed files, and observed implementation.
