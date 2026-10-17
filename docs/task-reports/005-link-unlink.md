# Task 005: `/link` and `/unlink`

## Status

Completed and verified. `./mvnw clean verify` passes with 102 tests (62 baseline + 40 new). Manual Discord or driver check not run (see Verification). Not pushed, not merged.

## Summary

- Added `AccountIdParser` (`services/`, pure static): SteamID64 (17 digits, prefix `7656119`), `steamcommunity.com/profiles/<SteamID64>` URL, or Deadlock account ID (1..4294967295). Conversion `steamId64 - 76561197960265728`. Vanity URLs fail with reason `VANITY_URL`; everything else with `UNRECOGNIZED`.
- Added `InvalidAccountInputException` (reason only, never the input) and `NoMatchHistoryException`.
- Added `LinkService`: `link` checks that `DeadlockApiClient.getMatchHistory` is non-empty, then saves `LinkedAccount` (upsert by Discord user id); `unlink` deletes the row and reports whether one existed.
- Added `LinkCommand` (`/link account:<text>`, required STRING option, max 200): parses before deferring, replies ephemerally to bad input, otherwise `deferReply(true)` and edits the hook with success, no matches, data source busy, or `error.generic` with a logged error id. It never rethrows after the deferral.
- Added `UnlinkCommand` (`/unlink`, no options, no deferral): ephemeral `unlink.success` or `unlink.not_linked`.
- Appended 10 keys to `messages.properties`, including the shared `error.data_source_busy`.

## Important Files

- `src/main/java/sntiago05/rejuv/services/AccountIdParser.java`, `InvalidAccountInputException.java`, `NoMatchHistoryException.java`, `LinkService.java`
- `src/main/java/sntiago05/rejuv/commands/LinkCommand.java`, `UnlinkCommand.java`
- `src/main/resources/messages.properties`
- `src/test/java/sntiago05/rejuv/services/AccountIdParserTest.java` (20), `LinkServiceTest.java` (7)
- `src/test/java/sntiago05/rejuv/commands/LinkCommandTest.java` (9), `UnlinkCommandTest.java` (4)
- `src/test/java/sntiago05/rejuv/services/MessagesIntegrationTest.java` (new keys and command names only)

## Decisions

- `LinkCommand` catches `NoMatchHistoryException`, then `DeadlockApiUnavailableException`, then `RuntimeException`. The order matters because `DeadlockApiUnavailableException` extends `DeadlockApiException`.
- The error log line has the error id, the user id and the exception; the raw input is never logged. `NoMatchHistoryException` carries the parsed account id (not the raw input) for debugging.
- `link.success` has no apostrophe, so `MessageFormat` is safe. The other new messages also avoid apostrophes.
- A missing `account` option (cannot happen, it is required) is treated as `UNRECOGNIZED`.

## Verification

- Baseline before changes: `./mvnw clean verify` first failed with 15 errors (all Testcontainers tests) because Docker Desktop was not running. After starting Docker Desktop: BUILD SUCCESS, 62 tests, 0 failures, 0 errors (matches the Task 004 report).
- Narrow run: `./mvnw -q test -Dtest='AccountIdParserTest,LinkServiceTest,LinkCommandTest,UnlinkCommandTest'`: 40 tests, 0 failures.
- Final: `./mvnw -q clean verify`: exit 0, **102 tests**, 0 failures, 0 errors, 0 skipped. Difference from baseline: +40 new tests. `MessagesIntegrationTest` (Spring context with Testcontainers) proves `LinkCommand`, `UnlinkCommand` and `LinkService` wire up and every new key resolves.
- Manual check: **not run**. No Discord token was used. `.claude/skills/run-rejuv/driver.sh build` worked, but `list`/`cmd` fail to compile `Driver.java` on Windows (`package sntiago05.rejuv does not exist`, likely the `:` classpath separator). The driver is outside this task's scope and was not changed. The throwaway `rejuv-run` database it started was stopped with `driver.sh db-down`.
- `git diff --check`: clean. Committed files are LF. Only the files in the spec table changed. No token, key or real id in the diff; all example ids are the fake `1000001` / `76561197961265729`. `.env` was never read.

## Acceptance criteria

| Criterion | Evidence |
|---|---|
| `clean verify` passes, count in report | 102 tests, see Verification |
| Parser formats, conversion, rejections with reasons | `AccountIdParserTest` (all spec cases) |
| `/link` parses before deferring; bad input gets ephemeral reply, no API call | `unrecognizedInputRepliesEphemerallyWithoutDeferring`, `vanityUrlRepliesWithVanityMessage` (no `deferReply`, no service interaction) |
| Defers ephemerally, checks history, upserts; nothing saved on failure | `successDefersEphemerallyAndConfirms`, `LinkServiceTest` 1-5 |
| Distinct messages; unexpected error logs id, no rethrow | `noMatchesEditsWithNoMatchesMessage`, `unavailableEditsWithBusyMessage`, `unexpectedErrorEditsWithGenericMessageAndDoesNotRethrow` |
| `/unlink` deletes and reports; ephemeral | `UnlinkCommandTest`, `LinkServiceTest` 6-7 |
| Text from catalog; no apostrophe issue | `catalogTextsHaveRequiredContent`, `MessagesIntegrationTest` |
| No logic in commands; no JDA types in services | `LinkService`/`AccountIdParser` import no `net.dv8tion` type |
| Only listed files; no dependency, migration or secret | `git diff --name-only origin/main...HEAD` |

## Deviations

- None in code. The manual check was not run (allowed by the spec as optional, reason above).

## Known Issues

- Handlers run on the JDA event thread and block during the history call, up to the client's 10 s retry wait. Accepted for the MVP; **risk for Task 009** (`/analyze` makes more calls).
- **Follow-up for Task 009**: `/unlink` deletes only `linked_account`. `docs/mvp-spec.md` also requires deleting `match_analysis`; Task 009 must extend `LinkService.unlink` when that table exists. `unlink.success` already says "stored data".
- An empty history stays cached for up to 2 minutes, so a user who retries `/link` right after getting tracked may still see `link.no_matches`.
- No ownership proof: anyone can link any account id; two Discord users may link the same account (spec decisions 5 and 6; Task 016 must decide how to show duplicates).
- `run-rejuv` driver does not run on Windows (`list`, `cmd`, `eval`); owner may want a fix in a separate task.

## Merge Risks

- `messages.properties` and `MessagesIntegrationTest` are shared files; later tasks that append keys may conflict textually (append-only, easy to resolve).
- The `feature/022-match-metadata` worktree existed at closeout with no commits and no changes, so `docs/PROJECT_CONTEXT.md` was updated here.
