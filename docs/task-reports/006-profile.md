# Task 006: `/profile`

## Status

Implemented and verified locally. `./mvnw clean verify` passes with 166 tests (135 baseline + 31 new). Manual Discord check not run (see Verification). Not pushed, not merged.

## Summary

- Added records `domain/ProfileSummary` (rank tier/name/subrank, last-N stats, top heroes; `topHeroes` copied to an immutable list) and `domain/HeroUsage`.
- Added `services/ProfileService`:
  - `findLinkedAccountId(discordUserId)`: database only.
  - `getProfile(accountId)`: `@Cacheable(profileSummary, sync = true)`. Calls the client sequentially: `getMatchHistory`, then `getPlayerRank`, `getRanks`, `getHeroes`. Empty history throws `NoMatchHistoryException` before any other call. Badge 0 = unranked (null tier and name, subrank 0). Recent stats over the first `min(10, size)` entries; win = `matchResult == playerTeam`; win rate `Math.round`; unrounded averages. Top 3 heroes over the whole fetched history with `startTime >= now - 30 days`, sorted by count desc then hero id asc; unknown hero id gives a null name.
  - Two constructors (public `@Autowired` with `Clock.systemUTC()`, package-private with a `Clock` for tests), as the spec decides. No `Clock` bean.
- Added `commands/ProfileCommand` (`/profile [user]`, optional USER option): resolves the target (option or caller), checks the link **before** deferring (ephemeral `profile.not_linked_self` / `profile.not_linked_other` with the mention), then `deferReply()` (public) and edits the hook with the embed, `profile.no_matches`, `error.data_source_busy`, or `error.generic` with a logged error id. Never rethrows after the deferral.
- `config/CacheConfig`: `PROFILE_SUMMARY = "profileSummary"`, 10 min, max 1000.
- Appended the 17 `profile.*` keys to `messages.properties` (no apostrophes; numbers passed as pre-formatted Strings).

## Important Files

- `src/main/java/sntiago05/rejuv/domain/ProfileSummary.java`, `HeroUsage.java`
- `src/main/java/sntiago05/rejuv/services/ProfileService.java`
- `src/main/java/sntiago05/rejuv/commands/ProfileCommand.java`
- `src/main/java/sntiago05/rejuv/config/CacheConfig.java` (constant + `register` line)
- `src/main/resources/messages.properties` (appended keys only)
- `src/test/java/sntiago05/rejuv/services/ProfileServiceTest.java` (15), `ProfileServiceCachingTest.java` (4)
- `src/test/java/sntiago05/rejuv/commands/ProfileCommandTest.java` (12)
- `src/test/java/sntiago05/rejuv/services/MessagesIntegrationTest.java` (new keys and `profile` command name only)
- `src/test/java/sntiago05/rejuv/clients/DeadlockApiClientCachingTest.java` (approved deviation, see below)

## Decisions

- Catch order in `ProfileCommand`: `NoMatchHistoryException`, `DeadlockApiUnavailableException`, `RuntimeException` (the busy exception extends `DeadlockApiException`).
- "Target is the caller" is decided by comparing Discord ids, so `/profile user:@me` behaves like `/profile`.
- The error log line has error id, caller id and target account id.
- Hero assets without a name are skipped when building the id→name map (`Collectors.toMap` rejects null values); such heroes show as `profile.hero_unknown`.
- `ProfileServiceCachingTest` stubs the raw `DeadlockApiClient` mock held by the test config, because the client bean is wrapped in Spring's caching proxy (its class has `@Cacheable` methods). It also asserts the `profileSummary` cache contents directly, so the test cannot pass on the client's own history cache.
- An extra test `callsTheClientSequentiallyInTheSpecifiedOrder` (`InOrder`) covers the call-order criterion; `embedShowsRankNameAloneWhenSubrankIsZero` covers the remaining rank branch.

## Verification

- Baseline before changes: `./mvnw -q clean verify` (Git Bash, Docker running): exit 0, 135 tests, 0 failures (matches the Task 022 report). No `MAVEN_USER_HOME` workaround was needed.
- Narrow runs: `./mvnw -q test -Dtest='ProfileServiceTest,ProfileServiceCachingTest,ProfileCommandTest,DeadlockApiClientCachingTest'`. First run: 3 failures in `ProfileServiceCachingTest` (stubbing through the caching proxy, see Decisions); after the fix, `ProfileServiceCachingTest` 4/4 pass, others pass.
- Final: `./mvnw -q clean verify`: exit 0, **166 tests**, 0 failures, 0 errors, 0 skipped. Difference: +31 new tests. `MessagesIntegrationTest` (Spring context with Testcontainers) proves `ProfileCommand`/`ProfileService` wire up and every new key resolves.
- Manual Discord check: **not run**. It needs `DISCORD_TOKEN`/`DEV_GUILD_ID` (`.env` not read), and `driver.sh cmd` does not work on Windows (known issue). Owner should run `/profile` linked and not linked, and `/profile user:@other` linked and not linked, in the dev guild.
- `git diff --check origin/main...HEAD`: clean. `git diff --name-only origin/main...HEAD`: only table files plus the approved test edit. No token, key or real id in the diff (fake ids `1000001`, `1000002`, `222`, `333`). `.env` was never read.

## Acceptance criteria

| Criterion | Evidence |
|---|---|
| `clean verify` passes, count in report | 166 tests, see Verification |
| Caller by default, `user` option otherwise | `successDefersPubliclyAndEditsWithEmbed`, `optionUserIsUsedAsTarget` |
| Not linked: ephemeral, before deferral, no API call | `callerNotLinkedRepliesEphemerallyWithoutDeferring`, `otherUserNotLinkedRepliesWithMention` (no `deferReply`, `getProfile` never called) |
| Public deferred embed, all text from catalog | `successDefersPubliclyAndEditsWithEmbed`, `embedShows*` tests |
| Win rule, at most 10 newest, tie by hero id | `winIsMatchResultEqualsPlayerTeam`, `usesOnlyTheTenNewestMatchesForRecentStats`, `topHeroesCountsOnlyLast30DaysSortedByCountThenId` |
| Cached 10 min per account, failures not cached, sequential calls | `ProfileServiceCachingTest` (4), `callsTheClientSequentiallyInTheSpecifiedOrder` |
| Distinct messages; unexpected error logs id, no rethrow | `noMatchesEditsWithNoMatchesMessage`, `unavailableEditsWithBusyMessage`, `unexpectedErrorEditsWithGenericMessageAndDoesNotRethrow` |
| No logic in commands; no JDA types in service | `ProfileService` imports no `net.dv8tion` type; command only formats |
| Only listed files; no dependency, migration or secret | `git diff --name-only origin/main...HEAD` (plus approved deviation) |
| Report created, context pending integration | This file, section below |

## Deviations

- **`DeadlockApiClientCachingTest` modified** (not in the spec table), approved by the owner during implementation. Its `cacheTtlsAndSizesMatchTheDesign` asserts the cache manager holds exactly the known caches, so registering `profileSummary` broke it. Added `PROFILE_SUMMARY` → 10 min / 1000 to its two maps (4 lines). No other change.

## Known Issues

- No cache eviction on `/link` or `/unlink`: a re-linked or unlinked account can show the old profile for up to 10 minutes (accepted by the spec).
- Top heroes count only the up to 200 history entries the client keeps; very active players may have more matches in 30 days (accepted by the spec).
- The handler runs on the JDA event thread; four sequential uncached calls can block it (same risk as `/link`, noted for Task 009).

## PROJECT_CONTEXT update pending integration

Task 008 is planned in parallel, so `docs/PROJECT_CONTEXT.md` was not edited. After 006 and 008 merge, add:
- Task 006 done: `/profile [user]` (public deferred embed: rank, last 10 matches W/L/win rate/avg K/D/A, top 3 heroes last 30 days); `ProfileService` with cache `profileSummary` 10 min/1000; not-linked replies ephemeral before deferral; report `docs/task-reports/006-profile.md`; 166 tests at merge time of this branch.
- Known limitations: no profile cache eviction on link changes; top heroes limited to 200 history entries.
- Constraint: adding a cache to `CacheConfig` requires updating `DeadlockApiClientCachingTest.cacheTtlsAndSizesMatchTheDesign` (exact set of cache names).
- Next: Task 015 (party stats in `/profile`) becomes eligible.

## Merge Risks

- `messages.properties`, `MessagesIntegrationTest`, `CacheConfig` and `DeadlockApiClientCachingTest` are shared; tasks that add keys or caches (for example 008 if it adds a cache) will conflict textually. Append-only, easy to resolve; keep both cache entries in the exact-set test.
