# Task 001: Project skeleton and `/ping`

## Status

Implemented and verified.

## Summary

- Added the Spring Boot/JDA bot skeleton for a non-web Discord bot.
- Added `/ping` command routing and dev-guild registration.
- Added a routing unit test for known, unknown, and failing slash command handlers.

## Important Files

- `pom.xml`
- `src/main/resources/application.yaml`
- `src/main/java/sntiago05/rejuv/config/BotProperties.java`
- `src/main/java/sntiago05/rejuv/config/JdaConfig.java`
- `src/main/java/sntiago05/rejuv/commands/SlashCommand.java`
- `src/main/java/sntiago05/rejuv/commands/CommandRouter.java`
- `src/main/java/sntiago05/rejuv/commands/PingCommand.java`
- `src/test/java/sntiago05/rejuv/commands/CommandRouterTest.java`
- `.env.example`
- `.gitignore`
- `README.md`

## Verification

- `.\mvnw.cmd clean verify` initially failed before Maven started because the Maven wrapper hit a Windows PowerShell null-target issue while resolving Maven home.
- `.\mvnw.cmd clean verify` passed after setting `MAVEN_USER_HOME` to a temporary junction pointing at the existing Maven user home. Result: build success; 4 tests run, 0 failures, 0 errors, 0 skipped.

## Deviations

- Added `CommandRouterTest` even though it was omitted from the file list because the acceptance criteria explicitly require it and the user approved it.

## Known Issues

- Manual Discord verification requires a valid `DISCORD_TOKEN` and `DEV_GUILD_ID`.
- The generated Maven wrapper script fails on this machine without the temporary `MAVEN_USER_HOME` junction workaround.
