---
name: create-task-spec
description: Create a precise, implementation-ready Rejuv task specification in docs/tasks/ using the repository's template and only the context relevant to that task. Use when planning, defining, or writing a numbered task before implementation.
---

# Create a Rejuv Task Specification

## Purpose

Create one implementation-ready task specification for Rejuv. This skill is for planning/specification only: do not implement application code, edit source files, or perform the task described by the spec.

The task spec is the contract between the architect and the implementing agent. It must be precise enough that the implementer can stay within scope without exploring unrelated parts of the repository.

## Context-loading policy

Use progressive, task-focused discovery. Do not read every document, all ADRs, every task report, or the whole repository by default.

Read these first:
1. `AGENTS.md` — project-wide rules and restrictions.
2. `docs/tasks/000-template.md` — required specification structure; preserve its headings and format.
3. `docs/PROJECT_CONTEXT.md` — compact, verified project state.
4. The relevant task row and dependencies in `docs/implementation-plan.md`.

Then inspect only what the requested task needs:
- `docs/mvp-spec.md` for MVP behavior, command requirements, errors, data, or AI contracts.
- `docs/features.md` for feature scope, data sources, tier, and timing.
- `docs/architecture.md` for components, package boundaries, and data flow.
- `docs/adr/ADR-XXXX...` only for decisions that govern this task. Discover the actual filenames instead of assuming their spelling.
- Existing source code and tests only to confirm real class names, signatures, conventions, current behavior, and extension points.
- `docs/task-reports/NNN-<name>.md` only when the current task depends on a previously implemented task or its actual outcome. Do not load historical reports just to build general context.
- `docs/roadmap.md`, `docs/product.md`, `docs/getting-started.md`, and `docs/setup-checklist.md` only if the specification cannot be grounded without them.

If the task is already fully specified by its task-plan entry, project context, template, relevant ADR, and source signatures, stop exploring. Do not perform broad repository scans for reassurance.

## Workflow

### 1. Resolve the requested task

- Get the task number and title from the user or from `docs/implementation-plan.md`.
- Verify that the task number exists and that its title, implementer, and dependencies match the plan.
- Check whether the requested task has already been specified or implemented; inspect only the relevant task spec/report/status to establish this.
- If the task number/title is ambiguous, duplicated, or inconsistent across project sources, do not silently invent a resolution. State the conflict and ask for a decision when it affects scope or behavior.

### 2. Establish the minimum technical context

- Read the current project state and only the relevant behavior/architecture/decision docs.
- Inspect the existing code that the task will call or extend. Use real names and signatures, not guessed APIs.
- Identify dependency-task outputs that exist now versus outputs that are merely planned.
- Separate verified facts from planned behavior. Do not claim a class, endpoint, schema, migration, or command exists unless the repository confirms it.
- If an implementation detail is unknown but not important to the contract, give the implementer a constrained choice consistent with project rules. If it would change behavior, API, data model, or architecture, flag it rather than guessing.

### 3. Write the specification

Create exactly one task spec at `docs/tasks/NNN-<kebab-case-name>.md`, following `docs/tasks/000-template.md`.

The spec should be concise but complete. Include the items below in the template's existing structure, where applicable:

- **Goal and boundaries:** what this task delivers and why; explicit out-of-scope work.
- **Dependencies and preconditions:** which prior tasks are required and what verified outputs they provide.
- **Behavior and acceptance criteria:** observable, testable outcomes; relevant happy paths, validation, edge cases, error handling, and async behavior.
- **Authorized files:** exact existing files allowed to change and new files allowed to be created. Include tests, resources, migrations, and configuration only when required. Do not use vague permission such as “related files as needed.”
- **Contracts:** exact class/method signatures, DTO fields, command names/options, repository/query signatures, configuration keys, database constraints, or JSON contract where applicable. Use only the subset relevant to the task.
- **User-facing text:** use `messages.properties` for user-facing strings in line with project conventions; list the exact keys and their intent when relevant. Do not hardcode user-facing text.
- **Tests:** name the important unit/integration/error cases and any fixtures needed. Derive expected behavior from project sources; do not invent unsupported expected values.
- **Verification:** use the commands required by `AGENTS.md` and project conventions. On this Windows project, account for the Maven wrapper form documented by the repository when appropriate; do not assume Unix commands work in PowerShell.
- **Documentation/reporting:** authorize the task report at `docs/task-reports/NNN-<kebab-case-name>.md` when the project workflow requires the implementer to create one. Follow the existing report format if one exists. Do not automatically authorize edits to `docs/PROJECT_CONTEXT.md`; follow `AGENTS.md`'s rule, including deferring context updates while multiple worktrees are active.
- **Definition of done:** build/tests pass, acceptance criteria met, no unauthorized files changed, and documentation/report requirements fulfilled.

Do not over-prescribe internal implementation details when multiple designs would satisfy the contract and project architecture. Be exact about observable behavior, interfaces, files, and tests; allow normal engineering judgment within those boundaries.

### 4. Validate the spec before finishing

Re-read only the new spec and compare it against:
- the task template;
- the task's plan entry and dependencies;
- relevant project rules and ADRs;
- the actual signatures of existing code referenced by the spec.

Check that:
- every requirement is testable or clearly explained;
- every file the implementer must change/create is authorized;
- no unauthorized implementation work has slipped into scope;
- signatures and file paths are real and consistent;
- newly planned classes are labeled as new, not described as existing;
- the tests cover the important success, failure, and edge paths;
- any report required by the workflow is included in authorized outputs;
- documentation updates respect the worktree/integration rule;
- the spec does not contradict `AGENTS.md`, the relevant ADR, or the implementation plan.

If a material conflict cannot be resolved from repository evidence, do not hide it in vague wording. Leave the spec unwritten and report the exact blocker, or write only if the repository workflow explicitly allows a draft with unresolved questions.

## Filesystem and Git boundaries

- Write only the requested new task spec under `docs/tasks/`.
- Do not modify application code, tests, ADRs, `AGENTS.md`, `prompts.md`, or `docs/PROJECT_CONTEXT.md` while creating the spec.
- Do not create a task report for a spec-writing session; task reports describe implementation work, not planning, unless the user explicitly requests a planning report.
- Do not commit, push, merge, switch branches, or edit other tasks unless explicitly requested.
- Preserve unrelated user changes. Inspect `git status` before writing if repository tools are available.

## Output to the user

Explain the important decisions in Spanish, but write the task spec itself in English, matching Rejuv's documentation convention.

Finish with a compact summary containing:
1. the exact path of the spec created;
2. the dependencies and authorized file scope;
3. any unresolved blocker or assumption;
4. confirmation that no implementation code was changed.

Do not repeat the entire project context in the response.

## Example invocation

`/create-task-spec 002 — i18n setup and /help`

Treat the example as an invocation example only. Always verify the actual task plan and repository state before writing a spec.
