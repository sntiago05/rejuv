# AGENTS.md — Rules for every AI agent working on Rejuv

Rejuv is an English-language Discord bot that gives Deadlock players AI coaching on their matches.
Read `docs/mvp-spec.md` for behavior and `docs/architecture.md` for structure when needed.

## Your role

- You are an **implementer**. Design decisions are already made in `docs/` (ADRs) and in the task spec you are given.
- Implement **exactly** the task spec in `docs/tasks/NNN-*.md`.
- Touch only the implementation files listed in the task spec, except for the mandatory documentation closeout files defined in **Project memory and task closeout** below.
- If the spec is ambiguous, contradicts this file, or seems wrong: **stop and ask**. Never guess, never redesign.
- Do not add dependencies, change architecture, edit ADRs, or edit other task specs unless the spec explicitly authorizes it.
- The documentation closeout exception does not authorize expanding the implementation scope.

## Stack

Java 21 · Spring Boot 4.1 · JDA 5 · Maven (`./mvnw`) · PostgreSQL + Spring Data JPA + Flyway · Caffeine (Spring Cache) · Spring `RestClient` · Claude Haiku 5.5 (`claude-haiku-5-5`) · Docker Compose.

## Package layout (`sntiago05.rejuv`)

- `commands/`: slash commands. Validate input, call a service, build the reply. **No business logic.**
- `services/`: business logic. Discord-agnostic; no JDA types in method signatures.
- `clients/`: external HTTP APIs (deadlock-api, Claude). Every read is cached.
- `repositories/`: Spring Data JPA.
- `domain/`: entities and records.
- `config/`: beans and `@ConfigurationProperties`.

## Conventions

- All user-facing text lives in `src/main/resources/messages.properties` and is accessed through the `Messages` helper. Never hardcode user-facing text.
- Commands that may take more than 3 seconds call `deferReply()` first.
- External API DTOs are Java records annotated with `@JsonIgnoreProperties(ignoreUnknown = true)` and contain only the fields we use.
- For deadlock-api HTTP 503 responses, honor `Retry-After` once, then fail gracefully. Never fire parallel uncached analytics requests.
- Database changes happen only through new Flyway migrations (`V<n>__description.sql`). Never edit an applied migration.
- Prefer constructor injection, `final` fields, records for data, and no Lombok.
- Use SLF4J logging: one INFO line per command (user id, command, duration, outcome). Never log tokens or API keys.

## Tests

- Use JUnit 5 + AssertJ.
- Services get unit tests.
- Test HTTP clients with `MockRestServiceServer` and JSON fixtures in `src/test/resources/fixtures/`.
- `./mvnw verify` must pass before you say the task is done. If it fails, report the actual failure; do not claim completion.

## Security

- Secrets belong only in `.env` or environment variables. Never read, print, or commit `.env`.
- Never commit generated credentials, tokens, API keys, or real user data.

## Git

- Use one branch per task: `feature/NNN-short-name`.
- Use Conventional Commits: `feat:`, `fix:`, `test:`, `docs:`, `chore:`.
- Make small, focused commits.
- Do not push directly to `main`.

## Project memory and task closeout

Project memory is maintained in version-controlled Markdown files. Keep it concise, factual, and useful to the next agent or session. The repository is the source of truth; do not treat an earlier chat or an unverified report as proof that a feature exists.

### Before implementation

1. Read this `AGENTS.md` and the assigned task spec in `docs/tasks/`.
2. Read `docs/PROJECT_CONTEXT.md` if it exists.
3. Read `docs/agent-workflow.md` if it exists.
4. Consult `docs/mvp-spec.md`, `docs/architecture.md`, relevant ADRs, and source code whenever needed to understand behavior or constraints.
5. Verify the current repository state. Do not assume another task was completed, merged, or deployed without evidence.

### After implementation

Before declaring the task complete:

1. **Create or update the task report** at `docs/task-reports/NNN-short-name.md`, using the same task number as the assigned specification. Include:
    - Task identifier and status.
    - A concise summary of what changed and why.
    - Important files created or modified.
    - Verification commands that were actually executed and their actual results.
    - Any deviations from the specification and why they were necessary or approved.
    - Known issues, blockers, limitations, and follow-up work.
2. **Update `docs/PROJECT_CONTEXT.md`** with durable, verified information that future tasks need. Keep it a compact snapshot, not a copy of the task report. Include, when relevant:
    - Current project phase and verified implementation status.
    - Important constraints or architectural facts future tasks must respect.
    - Unresolved blockers and known limitations.
    - The next eligible task and its dependencies, if these can be verified.
    - Links to relevant task reports and ADRs.
3. If `docs/PROJECT_CONTEXT.md` does not exist, create it from the repository's verified state. Do not describe planned functionality as implemented.
4. Update canonical documentation such as `docs/mvp-spec.md` or `docs/architecture.md` only when the task spec or approved workflow authorizes that change. Do not silently change an architectural decision.
5. Never edit an existing ADR to rewrite history. A new significant technical decision requires authorization and a new ADR according to the project workflow.
6. Keep documentation concise. Do not copy entire conversations, terminal logs, source files, secrets, temporary reasoning, or redundant descriptions of the code.
7. Inspect the final diff and check that no unrelated files or accidental changes were included.
8. Never claim a test passed unless it was actually executed successfully. Never claim a task was merged, deployed, or released unless that has actually happened.

### Parallel tasks and worktrees

- Each task must maintain its own task report on its task branch.
- If multiple task branches or worktrees are active at the same time, avoid simultaneous edits to `docs/PROJECT_CONTEXT.md`.
- In that case, update the task-specific report and mark the shared context update as pending integration. Synchronize `docs/PROJECT_CONTEXT.md` after the relevant task branches have been reviewed and integrated, using only verified outcomes.
- Do not overwrite context updates from another task. Resolve merge conflicts by checking the integrated code, task reports, and relevant specifications—not by blindly accepting one version.

## Done means

A task is done only when:

1. Every acceptance criterion in the task spec has been checked.
2. `./mvnw verify` passes.
3. The task report has been created or updated, and the project context has been updated—or the shared context update is explicitly marked pending integration because parallel tasks are active.
4. The final diff has been inspected and contains no unrelated changes.
5. You reply with:
    - Files changed.
    - How to test it manually.
    - Verification commands and results.
    - Documentation updated.
    - Anything the reviewer should double-check.
    - Remaining risks, blockers, or follow-up work.
