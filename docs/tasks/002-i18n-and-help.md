# Task 002: i18n setup (`Messages`) and `/help`

- **Branch**: `feature/002-i18n-help`
- **Implementer**: Antigravity
- **Related ADRs**: ADR-0008, ADR-0001

## Goal
Introduce the i18n infrastructure required by AGENTS.md (`messages.properties` plus a `Messages` helper on top of Spring's `MessageSource`), move the user-facing text that Task 001 hardcoded into it, and add the ephemeral `/help` slash command whose text comes entirely from the catalog.

## Context (verified against the repository when this spec was written)
Do not repeat this discovery. If any fact below is false in your checkout, stop and ask.

- Prerequisite: Task 001 (`docs/tasks/001-project-skeleton.md`, report `docs/task-reports/001-project-skeleton.md`). Your branch must contain its files. This spec does not assume Task 001 is merged into `main`; check your base.
- Base package: `sntiago05.rejuv`. Existing packages: `commands`, `config`. There is no `services/`, `clients/`, `repositories/` or `domain/` directory yet.
- `src/main/resources/` contains only `application.yaml`. There is no `messages.properties` and no `src/test/resources/`.
- Existing command API (do not change it):

  ```java
  package sntiago05.rejuv.commands;

  public interface SlashCommand {
      String name();
      SlashCommandData data();
      void handle(SlashCommandInteractionEvent event);
  }
  ```

- `CommandRouter` (`@Component`, `extends ListenerAdapter`) currently has the constructor `CommandRouter(BotProperties botProperties, List<SlashCommand> commands)`, collects all `SlashCommand` beans by `name()`, registers all of them as guild commands in `bot.dev-guild-id` on `ReadyEvent`, and routes `onSlashCommandInteraction`. User-facing text it hardcodes today:
  - `event.reply("Unknown command")` (ephemeral) for an unknown command name.
  - `event.reply("Something went wrong. Error id: " + errorId)` (ephemeral) when a handler throws a `RuntimeException`.
- `PingCommand` (`@Component`, no constructor arguments) hardcodes the description `"Check the bot gateway latency"` and the reply `"Pong! " + event.getJDA().getGatewayPing() + " ms"` (ephemeral).
- `BotProperties` is `record BotProperties(String token, String devGuildId)`. `JdaConfig` builds the `JDA` bean only when `bot.token` and `bot.dev-guild-id` are both non-empty (`@ConditionalOnExpression`), so a context without credentials starts without JDA.
- Build: Spring Boot parent `4.1.1`, Java 21, JDA `5.0.0`, `spring-boot-starter-test` (JUnit 5, AssertJ, Mockito). No new dependency is needed: `spring-boot-starter` already brings `spring-context`.
- Spring Boot 4.1.1 ships `org.springframework.boot.autoconfigure.context.MessageSourceAutoConfiguration`. It creates the `messageSource` bean (a `ResourceBundleMessageSource`) when the `messages` bundle exists on the classpath. Defaults (from the 4.1.1 configuration metadata): `spring.messages.basename=messages`, `encoding=UTF-8`, `fallback-to-system-locale=true`, `always-use-message-format=false`, `use-code-as-default-message=false`. The existing `spring.autoconfigure.exclude` list in `application.yaml` does not exclude it. Adding `src/main/resources/messages.properties` is therefore enough; do not configure `spring.messages.*`.
- Existing tests: `RejuvApplicationTests` (`@SpringBootTest`, `contextLoads`) and `CommandRouterTest` (3 tests, Mockito-mocked `SlashCommandInteractionEvent` and `ReplyCallbackAction`, AssertJ). The Task 001 report states 4 tests, 0 failures, and `target/surefire-reports` agrees (1 + 3).
- Task 001 report caveat: on the maintainer's Windows machine the Maven wrapper may need `MAVEN_USER_HOME` pointed at a temporary junction to the existing Maven home. That is an environment workaround, not part of this task.

## Files to create or modify
| File | Change |
|---|---|
| `src/main/resources/messages.properties` | **Create.** The message catalog defined in "Message catalog". |
| `src/main/java/sntiago05/rejuv/services/Messages.java` | **Create.** `@Component` helper over `MessageSource`. |
| `src/main/java/sntiago05/rejuv/commands/HelpCommand.java` | **Create.** `/help` slash command. |
| `src/main/java/sntiago05/rejuv/commands/CommandRouter.java` | **Modify.** Inject `Messages`; replace the two hardcoded replies with catalog lookups. No other behavior change. |
| `src/main/java/sntiago05/rejuv/commands/PingCommand.java` | **Modify.** Inject `Messages`; description and reply come from the catalog. Same English output as before. |
| `src/test/java/sntiago05/rejuv/services/MessagesFixture.java` | **Create.** Test helper that builds a `Messages` over the real catalog without a Spring context. |
| `src/test/java/sntiago05/rejuv/services/MessagesTest.java` | **Create.** Unit tests for `Messages`. |
| `src/test/java/sntiago05/rejuv/services/MessagesIntegrationTest.java` | **Create.** `@SpringBootTest` test of the Boot-configured catalog and command registration. |
| `src/test/java/sntiago05/rejuv/commands/HelpCommandTest.java` | **Create.** Unit tests for `/help`. |
| `src/test/java/sntiago05/rejuv/commands/PingCommandTest.java` | **Create.** Unit tests for the refactored `/ping`. |
| `src/test/java/sntiago05/rejuv/commands/CommandRouterTest.java` | **Modify.** Only the `new CommandRouter(...)` calls (new third argument). Assertions stay as they are. |
| `README.md` | **Modify.** One sentence: the dev guild now gets `/ping` and `/help`. |
| `docs/task-reports/002-i18n-and-help.md` | **Create** at closeout (AGENTS.md). |
| `docs/PROJECT_CONTEXT.md` | **Update** at closeout (AGENTS.md), or mark pending integration if parallel task branches are active. |

Do not touch any other file (`pom.xml`, `application.yaml`, `.env.example`, `.gitignore`, ADRs, other specs, `AGENTS.md`, existing reports).

## Interfaces / signatures

### `Messages`
```java
package sntiago05.rejuv.services;

import java.util.Locale;

import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;

@Component
public class Messages {

    private static final Locale DEFAULT_LOCALE = Locale.ENGLISH;

    private final MessageSource messageSource;

    public Messages(MessageSource messageSource) {
        this.messageSource = messageSource;
    }

    /**
     * Resolves a catalog message in English.
     *
     * @param key  catalog key, for example "help.body"
     * @param args positional MessageFormat arguments ({0}, {1}); may be empty
     * @return the resolved text
     * @throws org.springframework.context.NoSuchMessageException if the key is not defined
     */
    public String get(String key, Object... args) {
        return messageSource.getMessage(key, args, DEFAULT_LOCALE);
    }
}
```
Rules:

- One public method. No `Locale` parameter or overload yet (per-user/server language is a later task; adding an overload later does not break callers).
- A missing key must **fail fast** by letting `NoSuchMessageException` propagate. Never return the key, an empty string, or a default text.
- It lives in `services/` because it is Discord-agnostic (no JDA types) and is meant to be used by both commands and future services. `commands/` classes may depend on it; it must not depend on `commands/`.
- Do not log message text.

### `HelpCommand`
```java
package sntiago05.rejuv.commands;

@Component
public class HelpCommand implements SlashCommand {

    private final Messages messages;

    public HelpCommand(Messages messages) {
        this.messages = messages;
    }

    @Override
    public String name() { return "help"; }

    @Override
    public SlashCommandData data() {
        return Commands.slash(name(), messages.get("help.description"));
    }

    @Override
    public void handle(SlashCommandInteractionEvent event) {
        event.reply(messages.get("help.body"))
                .setEphemeral(true)
                .queue();
    }
}
```
Contract: name `help`, no options, ephemeral plain-text reply (Discord markdown allowed), no `deferReply()` (static text, no I/O), no service call, no logging of its own (`CommandRouter` already logs one INFO line per command).

### `CommandRouter` (changes only)
```java
private final Messages messages;

public CommandRouter(BotProperties botProperties, List<SlashCommand> commands, Messages messages) {
    // existing assignments unchanged, plus:
    this.messages = messages;
}
```
- Unknown command: `event.reply(messages.get("error.unknown_command")).setEphemeral(true).queue();`
- Handler failure: `event.reply(messages.get("error.generic", errorId)).setEphemeral(true).queue();` where `errorId` is the existing `UUID.randomUUID().toString()` (pass the String, not a number).
- Log statements (SLF4J messages, structured fields) are developer-facing, not user-facing: leave them hardcoded and unchanged.
- Add `import sntiago05.rejuv.services.Messages;`. Registration, routing, error id generation and logging stay exactly as they are.

### `PingCommand` (changes only)
```java
private final Messages messages;

public PingCommand(Messages messages) { this.messages = messages; }

// data():   Commands.slash(name(), messages.get("ping.description"))
// handle(): event.reply(messages.get("ping.reply", String.valueOf(event.getJDA().getGatewayPing())))
//                .setEphemeral(true).queue();
```
Pass the latency as a `String`: `MessageFormat` formats a `Number` with grouping (`1234` becomes `1,234`), which would change the Task 001 output.

### `MessagesFixture` (test helper)
```java
package sntiago05.rejuv.services;

public final class MessagesFixture {
    private MessagesFixture() { }

    /** Messages backed by the real classpath "messages" bundle, no Spring context. */
    public static Messages english() {
        ResourceBundleMessageSource source = new ResourceBundleMessageSource();
        source.setBasename("messages");
        source.setDefaultEncoding("UTF-8");
        source.setFallbackToSystemLocale(false);
        return new Messages(source);
    }
}
```
It is named `MessagesFixture`, not `TestMessages`, so Surefire's default `Test*` include pattern does not pick it up as a test class.

## Message catalog
Create `src/main/resources/messages.properties` with exactly this content (UTF-8 without BOM, ASCII characters only in this task, `\n\` continuation lines as shown):

```properties
# User-facing text for Rejuv (English). All user-facing text must live in this file.
# Key format: <area>.<name> in lower snake case (for example help.body, error.generic).
# Messages that take arguments use java.text.MessageFormat ({0}, {1}): pass pre-formatted
# Strings as arguments and write every apostrophe in those messages as two ('').

help.description=Show what Rejuv does and how to get started
help.body=**Rejuv** is an AI coach for Deadlock. Link your account, then get a short, specific coaching report on your latest match, compared with players of the same hero and rank.\n\
\n\
**Commands**\n\
`/link account:<ID or profile URL>` - Link your Deadlock (Steam) account.\n\
`/unlink` - Remove the link and your stored data.\n\
`/profile [user]` - See your rank, your last 10 matches and your top heroes.\n\
`/analyze` - Get an AI coaching report on your latest match.\n\
`/help` - Show this message.\n\
\n\
**How to link**\n\
Run `/link` with one of these:\n\
- Your SteamID64 (17 digits, starts with 7656119)\n\
- Your profile URL: `https://steamcommunity.com/profiles/<SteamID64>`\n\
- Your Deadlock account ID (the number shown on deadlock-api and Statlocker)\n\
Custom profile URLs (steamcommunity.com/id/name) are not supported yet.\n\
\n\
**Missing matches?**\n\
Rejuv only sees matches that deadlock-api has tracked. If your latest match is missing, it may not be tracked yet. Installing the deadlock-api ingest tool helps fill the gaps.

ping.description=Check the bot gateway latency
ping.reply=Pong! {0} ms

error.unknown_command=Unknown command
error.generic=Something went wrong. Error id: {0}
```

| Key | Arguments | Used by | Notes |
|---|---|---|---|
| `help.description` | none | `HelpCommand.data()` | 43 characters; Discord allows 1-100. |
| `help.body` | none | `HelpCommand.handle()` | 991 characters; Discord message content limit is 2000. Source: `docs/mvp-spec.md` section `/help`. |
| `ping.description` | none | `PingCommand.data()` | Same text as Task 001. |
| `ping.reply` | `{0}` = gateway ping in ms, as a `String` | `PingCommand.handle()` | Same output as Task 001: `Pong! 42 ms`. |
| `error.unknown_command` | none | `CommandRouter` | Same text as Task 001. |
| `error.generic` | `{0}` = error id (UUID string) | `CommandRouter` | Same text as Task 001. |

Every key used by main code is in this table and in the file; no key is defined without a user. Do not add other keys.

Properties-file notes the implementer must respect:

- `help.body` has no arguments, so `MessageFormat` is not applied (`spring.messages.always-use-message-format=false`). Apostrophes and braces in no-argument messages stay literal. Messages **with** arguments (`ping.reply`, `error.generic`) go through `MessageFormat`: any apostrophe there must be doubled and numbers must be passed as pre-formatted Strings.
- The `/link` text in `help.body` describes the MVP behavior from `docs/mvp-spec.md`. Do not implement `/link` here.
- `/ping` is intentionally not listed in `help.body` (dev-guild health check only, per `docs/mvp-spec.md`).

## Behavior
1. **Startup.** Spring Boot auto-configures the `messageSource` bean from `messages.properties`. `Messages` is a singleton component wrapping it. No configuration property is added.
2. **Registration.** `HelpCommand` is a `@Component`, so `CommandRouter` collects it through `List<SlashCommand>` and, on `ReadyEvent`, registers `help` together with `ping` as guild commands in `bot.dev-guild-id`. Global registration remains out of scope (see below).
3. **`/help`.** The router routes `help` to `HelpCommand.handle`, which replies with `help.body` as an **ephemeral** message. It does not call `deferReply()`, does not touch the network, database or cache, and has no options.
4. **`/ping`.** Behavior and English output are unchanged (`Pong! <n> ms`, ephemeral); only the text source changed.
5. **Unknown command.** Unchanged behavior: ephemeral `Unknown command` (now key `error.unknown_command`), plus the existing warn log and INFO outcome line.
6. **Handler failure.** Unchanged behavior: the `RuntimeException` is caught, a UUID error id is logged at ERROR with the stack trace, and the user gets the ephemeral `Something went wrong. Error id: <uuid>` (now key `error.generic`).
7. **Missing key.** `Messages.get` throws `NoSuchMessageException`. Inside a command handler this is a `RuntimeException`, so the router's existing handler-failure path answers the user with `error.generic`. The catalog test suite guarantees every key used by main code exists, so this path signals a developer error, not a user-facing fallback. Keys are never displayed to users.
8. **Language.** Always English (`Locale.ENGLISH`). There is no locale negotiation or fallback chain in this task.

## Tests
Use JUnit 5, AssertJ and Mockito as in `CommandRouterTest`. No new dependency.

### `MessagesTest` (unit, no Spring context; `StaticMessageSource` from `spring-context`)
1. `returnsMessageForKey`: `get("greeting")` returns the registered English text.
2. `formatsPositionalArguments`: a message `Hello {0}, you have {1} items` called with `"Ana"`, `"3"` returns `Hello Ana, you have 3 items`.
3. `keepsApostrophesAndBracesWhenNoArguments`: a message `don't use {0}` called with no arguments returns it unchanged (documents the `always-use-message-format=false` behavior that `help.body` relies on).
4. `throwsNoSuchMessageExceptionForMissingKey`: `get("missing.key")` throws `NoSuchMessageException`.
5. `alwaysResolvesInEnglishRegardlessOfDefaultLocale`: register different texts for `Locale.ENGLISH` and `Locale.forLanguageTag("es")` under the same key, set `Locale.setDefault(Locale.forLanguageTag("es-CO"))` in a `try`, and restore the original default in `finally`; `get` returns the English text.

### `MessagesIntegrationTest` (`@SpringBootTest(properties = {"bot.token=", "bot.dev-guild-id="})`)
The empty properties guarantee the `JDA` bean is not created even if the developer's shell has `DISCORD_TOKEN` or `DEV_GUILD_ID` set. Autowire `Messages` and `List<SlashCommand>`.
1. `resolvesEveryRequiredKey`: for each of `help.description`, `help.body`, `ping.description`, `ping.reply`, `error.unknown_command`, `error.generic`, `messages.get(key)` is not blank.
2. `formatsParameterizedMessages`: `get("ping.reply", "42")` equals `Pong! 42 ms`; `get("error.generic", "abc-123")` equals `Something went wrong. Error id: abc-123`.
3. `failsFastOnMissingKey`: `get("does.not.exist")` throws `NoSuchMessageException` (proves Boot's `use-code-as-default-message=false` is in effect).
4. `catalogFileIsWellFormed`: load `messages.properties` from the classpath as UTF-8 (`EncodedResource` + `PropertiesLoaderUtils`); every value is not blank and every key matches `^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+$`. Do not assert the exact key set, so later tasks can add keys without editing this test.
5. `registersHelpAndPingAsSlashCommands`: the names of the autowired `SlashCommand` beans contain `help` and `ping` (use `contains`, not an exact match, so later commands do not break it).

### `HelpCommandTest` (unit; real `Messages` from `MessagesFixture.english()`; mocked JDA event as in `CommandRouterTest`)
1. `nameIsHelp`.
2. `dataUsesCatalogDescriptionAndHasNoOptions`: `data().getName()` is `help`, `data().getDescription()` equals `messages.get("help.description")` and is 1-100 characters, `data().getOptions()` is empty.
3. `handleRepliesEphemerallyWithHelpBody`: `verify(event).reply(messages.get("help.body"))`, then `setEphemeral(true)` and `queue()` on the returned `ReplyCallbackAction`; `verify(event, never()).deferReply()`.
4. `helpBodyCoversMvpTopics`: the body contains `/link`, `/unlink`, `/profile`, `/analyze`, `/help`, `7656119`, `https://steamcommunity.com/profiles/`, `ingest tool`; it does not contain `/ping`.
5. `helpBodyFitsDiscordMessageLimit`: `help.body` length is at most 2000.

### `PingCommandTest` (unit; real `Messages`; mocked event and `JDA`)
1. `dataUsesCatalogDescription`: name `ping`, description `Check the bot gateway latency`.
2. `repliesEphemerallyWithGatewayPing`: with `getGatewayPing()` returning `42L`, `event.reply("Pong! 42 ms")` is called, then `setEphemeral(true)` and `queue()`.
3. `doesNotGroupLargePingValues`: with `getGatewayPing()` returning `1234L`, the reply is `Pong! 1234 ms` (guards the String-argument rule).

### Existing tests
- `CommandRouterTest`: change only construction to `new CommandRouter(new BotProperties("token", "123"), List.of(...), MessagesFixture.english())`. The three existing tests must pass without changing their assertions (the English strings are identical).
- `RejuvApplicationTests`: unchanged and must pass.

## Out of scope
- Any other slash command (`/link`, `/unlink`, `/profile`, `/analyze`), and any database, Flyway, `DeadlockApiClient`, AI or cache work (tasks 003-009). The command names inside `help.body` are text only.
- Further message keys from the `docs/mvp-spec.md` error table (not linked, no recent matches, API busy, Claude API error). The tasks that implement those flows add their own keys.
- Real localization: `messages_es.properties` or any second locale, per-user or per-guild language, a `Locale` parameter or overload on `Messages`, Discord interaction locale (`getUserLocale`), JDA `LocalizationFunction`/`DiscordLocale` command localization, AI-prompt language switching.
- `spring.messages.*` configuration, `application.yaml` changes (including removing the Task 001 TEMP database exclusions, which belongs to task 003), `pom.xml` changes, `.env.example`, `.gitignore`.
- Global command registration or any change to how `CommandRouter` registers commands. `/help` is registered in the dev guild like `/ping`. Making `/help` global is a launch concern.
- Embeds, buttons, pagination, or building the help text dynamically from registered commands.
- Refactoring Task 001 beyond injecting `Messages` and externalizing the strings listed above. Do not change logging, error ids or routing.
- Externalizing developer-facing text (log lines, exception messages, test data).
- Lint rules or tooling that detect hardcoded strings.
- Deferred Task 001 items: manual verification against Discord and the Maven wrapper workaround on Windows.
- New ADRs. The decision is already recorded in ADR-0008.

## Dependencies and constraints
- Task 001 must be present on the base branch. Reuse `SlashCommand`, `CommandRouter` and `PingCommand` as they exist; the public `SlashCommand` interface does not change.
- Conventions to preserve (AGENTS.md): constructor injection with `final` fields, no Lombok, no hardcoded user-facing text, commands contain no business logic, one INFO log line per command (already done by the router), JUnit 5 + AssertJ.
- Architecture: `commands` depends on `services`; `services` must not depend on `commands` or on JDA types.
- Tasks 002 and 003 can be developed in parallel. This task intentionally does not edit `pom.xml` or `application.yaml`, so it cannot conflict with task 003 there. If parallel task branches are active, follow the AGENTS.md rule for `docs/PROJECT_CONTEXT.md` (mark the update pending integration).
- Verification: run `./mvnw verify` (on Windows `.\mvnw.cmd verify`, with the `MAVEN_USER_HOME` workaround from the Task 001 report if needed). Report the actual result; do not claim a pass that was not run.

## Acceptance criteria
- [ ] Compiles, all tests pass
- [ ] Unit tests for the service logic
- [ ] Follows AGENTS.md conventions
- [ ] `src/main/resources/messages.properties` exists and defines exactly the six keys of the catalog table with the values shown in "Message catalog".
- [ ] Every string literal passed to `messages.get(` under `src/main/java` is a key defined in `messages.properties` (check with a search).
- [ ] No hardcoded user-facing text remains in `CommandRouter`, `PingCommand` or `HelpCommand` (reply texts and slash-command descriptions all come from `Messages`); log messages are unchanged.
- [ ] `Messages` has the exact package, constructor and `get(String, Object...)` signature above, and a missing key throws `NoSuchMessageException`.
- [ ] `HelpCommand` has name `help`, no options, replies ephemerally with `help.body`, and never calls `deferReply()`.
- [ ] `help.body` mentions the MVP commands, the accepted `/link` inputs, the data-coverage tip and the ingest tool, and is at most 2000 characters.
- [ ] `/ping`, the unknown-command reply and the generic-error reply produce the same English text as in Task 001.
- [ ] `MessagesTest`, `MessagesIntegrationTest`, `HelpCommandTest` and `PingCommandTest` exist with the scenarios above; `CommandRouterTest` and `RejuvApplicationTests` pass with no assertion changes.
- [ ] `pom.xml`, `application.yaml`, `.env.example` and `.gitignore` are unchanged; no new dependency.
- [ ] Manual check, if `DISCORD_TOKEN` and `DEV_GUILD_ID` are available: in the dev guild `/help` answers with the ephemeral help text and `/ping` still works. If credentials are not available, say so in the task report; do not claim it was verified.
- [ ] Task report `docs/task-reports/002-i18n-and-help.md` created and `docs/PROJECT_CONTEXT.md` updated (or marked pending integration), per AGENTS.md; the final diff contains no unrelated files.

## Decisions and open questions
Decisions taken in this spec (supported by the repository):

1. **Task 001 strings move in this task.** AGENTS.md ("never hardcode user-facing text"), the Definition of Done in `docs/implementation-plan.md` and ADR-0008 require it, and `/help` errors flow through the router. English values are unchanged, so existing tests keep passing.
2. **`Messages` lives in `sntiago05.rejuv.services`.** The docs do not name a package. It is Discord-agnostic and shared by commands and future services; `config/` is for beans and properties, and `commands/` would force services to depend on commands.
3. **One `help.body` key with plain-text content.** `docs/mvp-spec.md` calls `/help` "static text from `messages.properties`", ephemeral.
4. **Fail fast on missing keys** instead of returning the key or a default, so typos surface in tests.
5. **No `application.yaml` change.** Defaults are sufficient for a single locale and this avoids a merge conflict with task 003.

Unresolved (not decided by this spec):

1. **`/help` lists commands that do not exist yet.** `/link`, `/unlink`, `/profile` and `/analyze` arrive in tasks 005, 006 and 009. This spec follows `docs/mvp-spec.md` and lists them, which is acceptable only because the bot is not public yet. Owner to confirm, or to ask for a reduced text until those tasks land.
2. **Ingest tool link.** The repository documents the ingest tool only by name (`docs/mvp-spec.md`, `docs/setup-checklist.md`) with no URL, so `help.body` does not link it. Owner to supply the URL; adding it later is a catalog-only change.
3. **Generic error wording.** `docs/mvp-spec.md` says "Generic apology + error id"; the Task 001 text (`Something went wrong. Error id: {0}`) has no apology. Preserved as is; wording can change later by editing `error.generic`.
4. **Future locale fallback.** With the Boot default `spring.messages.fallback-to-system-locale=true`, once a second locale file exists a machine whose system language differs could pick it for an English request. The future localization task must set this property to `false`. No action now.
