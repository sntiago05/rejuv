# AGENTS.md: rules for every AI agent working on Rejuv

Rejuv is an English-language Discord bot that gives Deadlock players AI coaching on their matches.
Read `docs/mvp-spec.md` for behavior and `docs/architecture.md` for structure when needed.

## Your role
- You are an **implementer**. Design decisions are already made in `docs/` (ADRs) and in the task spec you are given.
- Implement **exactly** the task spec in `docs/tasks/NNN-*.md`. Touch only the files it lists.
- If the spec is ambiguous, contradicts this file, or seems wrong: **stop and ask**. Never guess, never redesign.
- Do not add dependencies, change architecture, edit ADRs or edit other task specs unless the spec says so.

## Stack
Java 21 · Spring Boot 4.1 · JDA 5 · Maven (`./mvnw`) · PostgreSQL + Spring Data JPA + Flyway · Caffeine (Spring Cache) · Spring `RestClient` · Claude Haiku 5.5 (`claude-haiku-5-5`) · Docker Compose.

## Package layout (`sntiago05.rejuv`)
- `commands/` slash commands: validate input, call a service, build the reply. **No business logic.**
- `services/` business logic. Discord-agnostic (no JDA types in signatures).
- `clients/` external HTTP APIs (deadlock-api, Claude). Every read is cached.
- `repositories/` Spring Data JPA. `domain/` entities and records. `config/` beans and `@ConfigurationProperties`.

## Conventions
- All user-facing text lives in `src/main/resources/messages.properties` (accessed through the `Messages` helper). Never hardcode user text.
- Commands that may take > 3 s call `deferReply()` first.
- External API DTOs: Java records with `@JsonIgnoreProperties(ignoreUnknown = true)`, only the fields we use.
- deadlock-api: on HTTP 503 honor `Retry-After` once, then fail gracefully. Never fire parallel uncached analytics requests.
- Database changes only through new Flyway migrations (`V<n>__description.sql`); never edit an applied migration.
- Prefer constructor injection, `final` fields, records for data, no Lombok.
- Logging: SLF4J; one INFO line per command (user id, command, duration, outcome). Never log tokens or API keys.

## Tests
- JUnit 5 + AssertJ. Services get unit tests. HTTP clients are tested with `MockRestServiceServer` and JSON fixtures in `src/test/resources/fixtures/`.
- `./mvnw verify` must pass before you say you are done.

## Security
- Secrets only in `.env` / environment variables. Never read, print or commit `.env`.
- Never commit generated credentials, tokens or real user data.

## Git
- One branch per task: `feature/NNN-short-name`. Conventional commits: `feat:`, `fix:`, `test:`, `docs:`, `chore:`.
- Small commits. Do not push to `main`.

## Done means
1. Everything in the spec's acceptance criteria is checked.
2. `./mvnw verify` passes.
3. You reply with: files changed, how to test it manually, and anything the reviewer should double-check.
