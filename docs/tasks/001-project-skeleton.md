# Task 001: Project skeleton and `/ping`

- **Branch**: `feature/001-skeleton`
- **Implementer**: Antigravity
- **Related ADRs**: ADR-0001, ADR-0002, ADR-0005

## Goal
A Spring Boot 4.1 / Java 21 app that connects to Discord with JDA 5 and answers `/ping` in the dev guild. No database or external APIs yet.

## Files to create or modify
| File | Change |
|---|---|
| `pom.xml` | Add JDA 5 (exclude opus audio) and Caffeine. Spring Cache is already in the baseline. Keep starters from Initializr |
| `src/main/resources/application.yaml` (existing file) | `spring.main.web-application-type: none`, bot properties, `spring.docker.compose.enabled: false`, and `spring.autoconfigure.exclude` for DataSource, Hibernate JPA and Flyway auto-configuration (use the Spring Boot 4.1 class names) |
| `.../config/BotProperties.java` | `@ConfigurationProperties("bot")`: `token`, `devGuildId` |
| `.../config/JdaConfig.java` | Builds `JDA` bean with minimal intents, registers `CommandRouter` listener |
| `.../commands/SlashCommand.java` | Interface: `String name()`, `SlashCommandData data()`, `void handle(SlashCommandInteractionEvent e)` |
| `.../commands/CommandRouter.java` | `ListenerAdapter`; injects `List<SlashCommand>`; routes by name; on ready registers commands in dev guild |
| `.../commands/PingCommand.java` | Replies ephemeral `Pong! <gateway ping> ms` |
| `.env.example` | `DISCORD_TOKEN=`, `DEV_GUILD_ID=` |
| `.gitignore` | `target/`, `.env`, `.idea/`, `*.iml`, `.vscode/` |
| `README.md` | What it is, prerequisites, how to run locally |

## Temporary database exclusion
The project already has JPA, PostgreSQL, Flyway and Docker Compose dependencies, but no database exists until task 003. Exclude their auto-configuration in `application.yaml` (not in code) so the app and the context test start. Add a comment `# TEMP: removed in task 003`. Do not remove the dependencies from `pom.xml`.

## Behavior
- On startup, the bot logs in and registers all `SlashCommand` beans as guild commands in `DEV_GUILD_ID` (instant update, unlike global commands).
- `/ping` replies ephemerally with gateway latency.
- Unknown command → ephemeral "Unknown command" and a warning log.
- Exceptions in a handler are caught by `CommandRouter`, logged with an error id, user gets a generic ephemeral error.

## Out of scope
Database, Docker Compose, i18n, any other command, global command registration.

## Acceptance criteria
- [ ] `./mvnw verify` passes, including the existing `RejuvApplicationTests` context test
- [ ] Running with a valid `.env` brings the bot online in the dev server and `/ping` works
- [ ] Unit test for `CommandRouter` routing (mock event and commands)
- [ ] No token or IDs committed

## Notes
`AGENTS.md`, `CLAUDE.md`, `.agent/` and `.claude/` already exist in the repo; do not modify them.
