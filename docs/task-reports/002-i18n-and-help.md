# Task 002: i18n setup (`Messages`) and `/help`

## Status

Implemented and verified.

## Summary

- Introduced the message catalog `src/main/resources/messages.properties` with the initial 6 keys required for MVP help, ping, and router errors.
- Created `Messages` helper in `sntiago05.rejuv.services` wrapping Spring's `MessageSource` with fail-fast lookup and default English locale.
- Created `/help` slash command (`HelpCommand`) that returns the catalog's ephemeral overview text covering MVP commands, linking instructions, and ingest tool information.
- Refactored `CommandRouter` and `PingCommand` to eliminate hardcoded user-facing strings, sourcing all user text from `Messages`.
- Added unit and Spring Boot integration tests for `Messages`, `/help`, and `/ping`, verifying formatting, non-grouped ping latency, fail-fast missing key handling, and slash command registration.

## Important Files

- `src/main/resources/messages.properties`
- `src/main/java/sntiago05/rejuv/services/Messages.java`
- `src/main/java/sntiago05/rejuv/commands/HelpCommand.java`
- `src/main/java/sntiago05/rejuv/commands/CommandRouter.java`
- `src/main/java/sntiago05/rejuv/commands/PingCommand.java`
- `src/test/java/sntiago05/rejuv/services/MessagesFixture.java`
- `src/test/java/sntiago05/rejuv/services/MessagesTest.java`
- `src/test/java/sntiago05/rejuv/services/MessagesIntegrationTest.java`
- `src/test/java/sntiago05/rejuv/commands/HelpCommandTest.java`
- `src/test/java/sntiago05/rejuv/commands/PingCommandTest.java`
- `src/test/java/sntiago05/rejuv/commands/CommandRouterTest.java`
- `README.md`

## Verification

- `.\mvnw.cmd verify`: Success (22 tests run, 0 failures, 0 errors, 0 skipped).
- `git diff --check`: Passed with 0 whitespace or formatting issues.
- `git grep -n "messages.get"`: Confirmed that every key requested from `Messages` corresponds to a defined key in `messages.properties`.

## Deviations

- None. Implementation matches `docs/tasks/002-i18n-and-help.md` exactly.

## Known Issues

- Manual verification against Discord was not performed because live Discord bot credentials (`DISCORD_TOKEN` and `DEV_GUILD_ID`) were not provided in the environment.
- `/help` documents upcoming MVP commands (`/link`, `/unlink`, `/profile`, `/analyze`) that are planned for tasks 005, 006, and 009, as decided in `docs/mvp-spec.md` and the task specification.
- `help.body` references the deadlock-api ingest tool by name without a URL as documented in the spec.
