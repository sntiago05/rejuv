# Task 005: `/link` and `/unlink`

- **Branch**: `feature/005-link-unlink`
- **Implementer**: Antigravity
- **Related ADRs**: ADR-0002 (PostgreSQL), ADR-0004 and ADR-0010 (deadlock-api), ADR-0008 (English, i18n-ready)

## Goal
Let a Discord user link their Deadlock account with `/link account:<text>` and remove the link with `/unlink`. `/link` parses the input into a Deadlock account ID, checks that deadlock-api has match history for it, then stores `linked_account`. `/unlink` deletes the stored link. Both replies are ephemeral.

## Context (verified in the repository when this spec was written)
Do not repeat this discovery. If a fact below is false in your checkout, stop and ask.

- Plan entry: `docs/implementation-plan.md`, task 005, depends on 003 and 004, implementer Antigravity. Tasks 006, 009, 014, 015 and 016 need a linked account.
- Tasks 003 and 004 are merged in `main` (PR #24 and PR #25). Start the branch from the latest `origin/main`.
- Existing code you will use (do not change it):
  - `domain/LinkedAccount(long discordUserId, long accountId, Instant linkedAt)`: JPA entity, `@Id` is `discord_user_id`, no `@Version`. So `save` on an existing id replaces the row (upsert).
  - `repositories/LinkedAccountRepository extends JpaRepository<LinkedAccount, Long>`: no custom methods.
  - `clients/DeadlockApiClient.getMatchHistory(long accountId)` returns `List<MatchHistoryEntry>`, cached 2 min, max 200 entries. An empty list means "no data" (the API returns `200 []`, not 404). It throws `DeadlockApiUnavailableException` (503 after its single retry, 429, timeout, I/O) or `DeadlockApiException` (unexpected status, bad body).
  - `commands/SlashCommand` has `name()`, `data()`, `handle(SlashCommandInteractionEvent)`. Every `@Component` that implements it is registered by `CommandRouter` in the dev guild. `CommandRouter` catches a `RuntimeException` from `handle` and answers with `event.reply(...)`. That call fails after `deferReply()`, so a deferred command must handle its own unexpected errors (see Behavior).
  - `services/Messages.get(String key, Object... args)` is the only access to user-facing text. `help.body` already lists `/link` and `/unlink`; do not change it.
  - There is no `match_analysis` table yet (Task 009). `/unlink` can only delete `linked_account` now.
- Rules from `AGENTS.md` that apply: no business logic in `commands/`; services have no JDA types; all user-facing text in `messages.properties`; `deferReply()` first for commands that may take more than 3 s; constructor injection, `final` fields, no Lombok; one INFO line per command is already written by `CommandRouter`; never log tokens.
- `docs/mvp-spec.md`: accepted inputs are SteamID64 (17 digits, starts with `7656119`), the profile URL `https://steamcommunity.com/profiles/<SteamID64>`, or a Deadlock account ID. Conversion: `account_id = steamId64 - 76561197960265728`. Vanity URLs (`/id/<name>`) are not supported; the reply explains how to find the numeric ID.

## Files to create or modify
| File | Change |
|---|---|
| `src/main/java/sntiago05/rejuv/services/AccountIdParser.java` | **Create.** Pure static parsing and conversion. |
| `src/main/java/sntiago05/rejuv/services/InvalidAccountInputException.java` | **Create.** |
| `src/main/java/sntiago05/rejuv/services/NoMatchHistoryException.java` | **Create.** |
| `src/main/java/sntiago05/rejuv/services/LinkService.java` | **Create.** |
| `src/main/java/sntiago05/rejuv/commands/LinkCommand.java` | **Create.** |
| `src/main/java/sntiago05/rejuv/commands/UnlinkCommand.java` | **Create.** |
| `src/main/resources/messages.properties` | **Modify.** Append the keys in "User-facing text". Change no existing key. |
| `src/test/java/sntiago05/rejuv/services/AccountIdParserTest.java` | **Create.** |
| `src/test/java/sntiago05/rejuv/services/LinkServiceTest.java` | **Create.** |
| `src/test/java/sntiago05/rejuv/commands/LinkCommandTest.java` | **Create.** |
| `src/test/java/sntiago05/rejuv/commands/UnlinkCommandTest.java` | **Create.** |
| `src/test/java/sntiago05/rejuv/services/MessagesIntegrationTest.java` | **Modify.** Only: add the new keys to `requiredKeys`, and add `"link"`, `"unlink"` to the `registersHelpAndPingAsSlashCommands` assertion. Change nothing else. |
| `docs/task-reports/005-link-unlink.md` | **Create.** Task report (format of the Task 004 report). |
| `docs/PROJECT_CONTEXT.md` | **Modify at closeout** under the `AGENTS.md` rule (see Documentation). |

No `pom.xml`, `application.yaml`, migration, entity, repository or client change is needed. If you think one is needed, stop and ask.

## Interfaces / signatures

### `AccountIdParser` (`sntiago05.rejuv.services`)
```java
public final class AccountIdParser {
    public static final long STEAM_ID64_BASE = 76561197960265728L;
    /** Returns the Deadlock account ID (1..4294967295). */
    public static long parse(String input);   // throws InvalidAccountInputException
}
```
Rules (after `trim()`; `null` or blank is `UNRECOGNIZED`):
1. **SteamID64**: exactly 17 digits and starts with `7656119`. `accountId = value - STEAM_ID64_BASE`; it must be `>= 1`, otherwise `UNRECOGNIZED`.
2. **Profile URL**: matches `^https?://(www\.)?steamcommunity\.com/profiles/(\d{17})/?$` (case-insensitive). The 17 digits follow rule 1.
3. **Deadlock account ID**: 1 to 10 digits only, value `1..4294967295`.
4. **Vanity URL**: matches `^https?://(www\.)?steamcommunity\.com/id/[^/\s]+/?$` (case-insensitive) fails with reason `VANITY_URL`.
5. Anything else fails with reason `UNRECOGNIZED`: other URLs, 11-16 digits, 17 digits not starting with `7656119`, signs, spaces inside, letters, `0`, values above 4294967295.

### `InvalidAccountInputException` (`sntiago05.rejuv.services`)
```java
public class InvalidAccountInputException extends RuntimeException {
    public enum Reason { UNRECOGNIZED, VANITY_URL }
    public InvalidAccountInputException(Reason reason);
    public Reason reason();
}
```
Never put the user input in the message (logs only, and the input is not trusted).

### `NoMatchHistoryException` (`sntiago05.rejuv.services`)
```java
public class NoMatchHistoryException extends RuntimeException {
    public NoMatchHistoryException(long accountId);
}
```

### `LinkService` (`sntiago05.rejuv.services`, `@Service`)
```java
public LinkService(DeadlockApiClient deadlockApiClient, LinkedAccountRepository repository)

/** Verifies match history, then upserts the link. Returns nothing; the caller already knows the account id. */
public void link(long discordUserId, long accountId);
    // throws NoMatchHistoryException (empty history, nothing saved)
    // lets DeadlockApiUnavailableException / DeadlockApiException propagate (nothing saved)

/** @return true if a link existed and was deleted, false if the user had no link. */
public boolean unlink(long discordUserId);
```
`link` saves `new LinkedAccount(discordUserId, accountId, Instant.now())`. A second `/link` by the same user replaces the previous account. The same account may be linked by more than one Discord user (decision of this spec: no unique constraint exists and none is added).

### Commands (`sntiago05.rejuv.commands`, both `@Component implements SlashCommand`)
- `LinkCommand(LinkService linkService, Messages messages)`: `name()` = `link`; `data()` = `Commands.slash("link", messages.get("link.description"))` with one option: type `STRING`, name `account`, description `messages.get("link.option.account")`, required, max length 200.
- `UnlinkCommand(LinkService linkService, Messages messages)`: `name()` = `unlink`; no options.

## Behavior

### `/link account:<text>`
1. Read option `account`. Call `AccountIdParser.parse` **before** deferring.
   - `InvalidAccountInputException` with `UNRECOGNIZED` → `event.reply(messages.get("link.invalid_input")).setEphemeral(true).queue()`; stop.
   - With `VANITY_URL` → same with `link.vanity_unsupported`; stop.
2. `event.deferReply(true).queue()` (ephemeral; the history call can take more than 3 s).
3. Call `linkService.link(event.getUser().getIdLong(), accountId)` synchronously, then answer through `event.getHook().editOriginal(text).queue()`:
   - success → `link.success` with the account id as `{0}` (a pre-formatted String);
   - `NoMatchHistoryException` → `link.no_matches`;
   - `DeadlockApiUnavailableException` → `error.data_source_busy`;
   - any other `RuntimeException` (including `DeadlockApiException`) → generate `UUID.randomUUID().toString()` as error id, log `ERROR` with the id, user id and the exception (never the raw input), and answer `error.generic` with the error id. Do not rethrow (the router cannot reply after a deferral).
4. Do not store anything on any failure path.

### `/unlink`
1. Call `linkService.unlink(event.getUser().getIdLong())`. No deferral (database only).
2. `true` → ephemeral reply `unlink.success`; `false` → ephemeral reply `unlink.not_linked`.
3. Unexpected exceptions are not caught; `CommandRouter` handles them.

### Notes
- Handlers run on the JDA event thread and may block up to the client's retry wait (max 10 s). This is accepted for the MVP; mention it in the report as a risk for Task 009.
- `/unlink` deletes only `linked_account` today. The MVP spec also says it deletes `match_analysis` for that user; Task 009 must extend `LinkService.unlink` when that table exists. Record this as follow-up in the report.
- Cached history is not evicted on link or unlink (an empty result can stay cached up to 2 min; accepted).

## User-facing text (`messages.properties`, append, English)
Messages that take an argument (`link.success`) must not contain an apostrophe, or must write it as two (`''`). Keep the existing header comments and style.

| Key | Intent |
|---|---|
| `link.description` | Command description, 1-100 chars: link your Deadlock account to your Discord user |
| `link.option.account` | Option description, 1-100 chars: SteamID64, steamcommunity.com/profiles URL, or Deadlock account ID |
| `link.success` | `{0}` = account id. Confirms the link and suggests `/analyze` |
| `link.invalid_input` | Says the input was not understood; lists the three accepted formats with examples `76561197961265729` (SteamID64), `https://steamcommunity.com/profiles/76561197961265729` and `1000001` (account ID) |
| `link.vanity_unsupported` | Custom URLs (`steamcommunity.com/id/name`) are not supported yet; tell the user to use the numeric SteamID64 or the Deadlock account ID shown on deadlock-api / Statlocker |
| `link.no_matches` | No Deadlock matches found for that account; check the ID, the account may not be tracked yet; mention the deadlock-api ingest tool (text must contain `ingest tool`); try again in a few minutes |
| `error.data_source_busy` | The data source is busy, try again in a minute (shared key, later commands reuse it) |
| `unlink.description` | Command description, 1-100 chars: remove your linked Deadlock account and stored data |
| `unlink.success` | The link and the stored data were removed |
| `unlink.not_linked` | The user has no linked account; suggest `/link` |

The example ids are fake: `1000001` + `76561197960265728` = `76561197961265729`.

## Tests
JUnit 5 and AssertJ. Mockito is available through `spring-boot-starter-test`. None of the new test classes uses `@SpringBootTest`, a database or Docker (Docker is still needed by the existing tests in `verify`). Use `MessagesFixture.english()` for `Messages`. Mock `DeadlockApiClient` and `LinkedAccountRepository` with Mockito. Follow the style of `HelpCommandTest` and `CommandRouterTest` for JDA mocks.

### `AccountIdParserTest`
1. `parsesSteamId64`: `76561197961265729` → `1000001`.
2. `parsesProfileUrl`: `https://steamcommunity.com/profiles/76561197961265729`, with trailing `/`, with `http://`, with `www.`, and with upper-case host → `1000001`.
3. `parsesDeadlockAccountId`: `1000001` → `1000001`; `1` → `1`; `4294967295` → `4294967295`.
4. `trimsWhitespace`: `"  1000001  "` → `1000001`.
5. `rejectsVanityUrl`: `https://steamcommunity.com/id/somename` and with trailing `/` → reason `VANITY_URL`.
6. `rejectsUnrecognizedInput` (parameterized): `null`, empty, blank, `abc`, `0`, `-5`, `4294967296`, `12345678901` (11 digits), `1234567890123456` (16 digits), `12345678901234567` (17 digits, wrong prefix), `76561197960265728` (account 0), `76561190000000000` (below the base), `https://example.com/profiles/76561197961265729`, `https://steamcommunity.com/profiles/123`, `1000001 2` → reason `UNRECOGNIZED`.

### `LinkServiceTest`
1. `linkSavesAccountWhenHistoryExists`: history non-empty → `repository.save` called once with the given discord id and account id, and `linkedAt` between two `Instant.now()` reads taken around the call.
2. `linkWithEmptyHistoryThrowsAndSavesNothing`: `NoMatchHistoryException`, `save` never called.
3. `linkPropagatesUnavailableAndSavesNothing`: client throws `DeadlockApiUnavailableException` → same exception out, `save` never called.
4. `linkPropagatesApiExceptionAndSavesNothing`: same with `DeadlockApiException`.
5. `linkAgainSavesTheNewAccount`: two calls with different account ids for the same user → two `save` calls, the second with the new id.
6. `unlinkDeletesExistingLink`: `existsById` true → returns `true`, `deleteById` called once.
7. `unlinkWithoutLinkReturnsFalse`: `existsById` false → returns `false`, `deleteById` never called.

### `LinkCommandTest`
1. `nameIsLink`, and `dataHasRequiredStringOptionAccount`: option named `account`, type `STRING`, required, max length 200, description equals the catalog text, length 1-100; command description equals the catalog text, length 1-100.
2. `unrecognizedInputRepliesEphemerallyWithoutDeferring`: input `abc` → `reply(link.invalid_input)` + `setEphemeral(true)` + `queue()`; `deferReply` never called; service never called.
3. `vanityUrlRepliesWithVanityMessage`: same shape with `link.vanity_unsupported`.
4. `successDefersEphemerallyAndConfirms`: input `76561197961265729` → `deferReply(true)`; service called with the caller id and `1000001`; hook edited with `link.success` formatted with `1000001`.
5. `noMatchesEditsWithNoMatchesMessage`.
6. `unavailableEditsWithBusyMessage`.
7. `unexpectedErrorEditsWithGenericMessageAndDoesNotRethrow`: service throws `RuntimeException`; the edit text starts with `Something went wrong. Error id: `; `handle` does not throw.
8. `catalogTextsHaveRequiredContent`: `link.invalid_input` contains `7656119`, `https://steamcommunity.com/profiles/` and `1000001`; `link.no_matches` contains `ingest tool`; `link.success` formatted with `1000001` contains `1000001` and `/analyze`.

### `UnlinkCommandTest`
1. `nameIsUnlinkAndHasNoOptions`; description equals the catalog text, length 1-100.
2. `linkedUserGetsEphemeralSuccess`: service returns `true` → `reply(unlink.success)` + `setEphemeral(true)` + `queue()`; `deferReply` never called.
3. `unlinkedUserGetsEphemeralNotLinked`: service returns `false` → `reply(unlink.not_linked)`, ephemeral.
4. `serviceReceivesCallerId`.

### `MessagesIntegrationTest` (existing, minimal edit)
`requiredKeys` includes every new key; the command-name assertion includes `link` and `unlink`. This test needs Docker.

## Verification
1. `./mvnw clean verify` passes (Docker running for the Testcontainers tests). On Windows use `.\mvnw.cmd clean verify`. Report the real test count and compare it with the baseline of 62 tests from the Task 004 report.
2. `git diff --check` and a review of the final diff: only the files in the table changed; no secret, token, real account id or real user data; `.env` was never read or printed.
3. Optional manual check (needs `DISCORD_TOKEN` and `DEV_GUILD_ID` in `.env`, PostgreSQL via `docker compose up -d postgres`): run the bot, then in the dev guild try `/link` with `1000001`, with an invalid text, with a vanity URL, then `/unlink` twice. Check the row with `psql`. If you do not run it, say so in the report; do not claim it.

## Documentation
- Task report `docs/task-reports/005-link-unlink.md`: status, summary, important files, commands run with real results, deviations, known issues. Include the follow-ups above (`match_analysis` delete in Task 009, blocking on the JDA thread).
- `docs/PROJECT_CONTEXT.md`: update with verified facts only (`/link` and `/unlink` exist, parser rules, `LinkService`, shared key `error.data_source_busy`, next eligible tasks 006, 014, 016 and 009 dependencies). Also correct the stale line that says Task 004 is "not merged yet": it is merged (PR #25). If another task branch with changes is active at closeout, do not edit the file; mark the update as "pending integration" in the report (`AGENTS.md`, Parallel tasks).
- No ADR is added. Do not edit `docs/mvp-spec.md`, `docs/architecture.md`, other task specs or `docs/roadmap.md`.

## Out of scope
- `/profile`, `/analyze`, any usage limit, premium or Patreon check.
- Steam Web API calls, vanity URL resolution, Steam login or ownership proof (the MVP trusts the typed ID).
- Deleting `match_analysis` (table does not exist), cache eviction, a unique constraint on `account_id`, a migration, changes to `LinkedAccount` or `LinkedAccountRepository`.
- Changes to `DeadlockApiClient`, `CommandRouter`, `HelpCommand`, `help.body`, `Messages`, `pom.xml`, `application.yaml` or global command registration.
- Async or background execution of the handlers.

## Acceptance criteria
- [ ] `./mvnw clean verify` passes; test count and baseline difference are in the report
- [ ] `AccountIdParser` accepts SteamID64, profile URL and account ID, converts with `value - 76561197960265728`, and rejects every case listed in the tests with the right reason
- [ ] `/link` parses before deferring; invalid and vanity inputs get an ephemeral reply and no API call
- [ ] `/link` defers ephemerally, checks non-empty match history, then upserts `linked_account`; nothing is saved on any failure
- [ ] Empty history, API unavailable and unexpected errors each give their own message; the unexpected error path logs an error id and does not rethrow
- [ ] `/unlink` deletes the link and tells the user whether one existed; both replies are ephemeral
- [ ] All user-facing text comes from `messages.properties` through `Messages`; no hardcoded text; no apostrophe problem in `link.success`
- [ ] Commands contain no business logic; `LinkService` and `AccountIdParser` use no JDA types
- [ ] Only the files in the table changed; no new dependency, migration or secret
- [ ] Task report created; `docs/PROJECT_CONTEXT.md` updated or marked pending integration; final diff has no unrelated files

## Decisions and open questions
Decisions taken in this spec:
1. **Parse in the command, before deferring.** Bad input is cheap to reject and needs no 3 s window. The parser is a pure static class in `services/` so it stays testable and Discord-agnostic.
2. **`LinkService.link(long, long)` takes the account id, not the raw text**, so the service has one job (verify and store).
3. **Account check = non-empty history** from the cached `getMatchHistory` (decision 2 of Task 004). No new client method.
4. **SteamID64 range follows `docs/mvp-spec.md`** (prefix `7656119`). IDs from `76561200000000000` upward are rejected; real accounts are in the `7656119...` range today.
5. **Re-linking replaces the old link** without a confirmation step.
6. **No ownership proof.** Anyone can link any public account ID. Accepted for the MVP; revisit before premium features that expose personal data.

Open for the owner (not blocking):
1. `/unlink` must also delete `match_analysis` once Task 009 creates it. Add this to the Task 009 spec.
2. Whether two Discord users may link the same account (allowed here). Task 016 (`/leaderboard server`) may need to decide how to show duplicates.
