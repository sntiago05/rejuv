# Task 015: `/mates` and mate-stats

## Status

Implemented and verified locally. `.\mvnw.cmd clean verify` passes with 522 tests (498 baseline + 24 new, 2 skipped). Not pushed, not merged.

## Step 0 Findings

1. **Teammate Statistics (`GET /v1/players/{account_id}/mate-stats`)**:
   - Path confirmed: `GET /v1/players/{account_id}/mate-stats`.
   - Query parameters confirmed:
     - `min_unix_timestamp` (int64): filters matches starting at or after the timestamp.
     - `max_unix_timestamp` (int64)
     - `min_matches_played` (int64)
     - `max_matches_played` (int64)
     - `same_party` (boolean, default false)
   - Response shape: JSON array of objects.
   - Field names confirmed:
     - `mate_id` (int64): teammate account ID.
     - `matches_played` (int64): matches played together with the player.
     - `wins` (int64): wins together with the player.
     - `matches` (array of int64): match IDs played together.
   - Live query test with real account `139205933` (`?min_unix_timestamp=1784497204`): status 200, 816 teammate entries returned. Top entry: `mate_id: 329035753, matches_played: 114, wins: 69`.
   - Live query test with empty account `999999999`: status 200, returned empty array `[]`.
   - Mate rows carry no displayable names (only `mate_id`); per spec behavior, formatted with `mates.teammate_label` (`Player #{0}`).
2. **Party Statistics (`GET /v1/players/{account_id}/party-stats`)**:
   - Live query `GET /v1/players/139205933/party-stats` returned HTTP 404 Not Found.
   - OpenAPI schema (`https://api.deadlock-api.com/openapi.json`) contains no party statistics endpoint or schema.
   - Neither `PlayerMatchHistoryEntry` nor `mate-stats` provides solo vs party breakdown.
   - Escalated to the owner under Step 0 rule 3 ("If the structure differs materially from the Goal... stop and ask; do not invent fields").
   - Owner approved: implement `/mates` using `GET /v1/players/{account_id}/mate-stats`, exclude party statistics from Task 015, do not invent an alternative data source, and document the limitation.

## Summary

- Created `clients/dto/MateStat` record mapping `@JsonProperty("mate_id") long mateId`, `@JsonProperty("matches_played") long matches`, and `@JsonProperty("wins") long wins`.
- Extended `clients/DeadlockApiClient`:
  - Added constant `MATE_STATS_PATH = "/v1/players/{account_id}/mate-stats"` and `MATE_STATS_READER`.
  - Added `@Cacheable(cacheNames = CacheConfig.DEADLOCK_MATE_STATS, sync = true, key = "#accountId + ':' + #minUnixTimestamp") public List<MateStat> getMateStats(long accountId, long minUnixTimestamp)`.
- Added cache `CacheConfig.DEADLOCK_MATE_STATS = "deadlockMateStats"` (10 min TTL, max size 200).
- Created domain records `Teammate` (`accountId`, `matches`, `wins`, `winRatePercent`) and `MatesSummary` (`accountId`, `teammates`).
- Created `services/MatesService`:
  - 90-day UTC window (`WINDOW = Duration.ofDays(90)`) anchored to the start of the current UTC day via `ServiceSupport.startOfUtcDayMinus`.
  - Filters out the player themself (`mateId == accountId`) and entries with fewer than 5 matches (`matches < MIN_MATCHES_TOGETHER`).
  - Orders by win rate desc, then matches desc, then account ID asc.
  - Keeps top 5 using `ServiceSupport.top`.
  - Propagates API exceptions (`DeadlockApiUnavailableException`, `DeadlockApiException`).
- Created `commands/MatesCommand`:
  - Slash command `/mates [user]`.
  - Checks linking via `ProfileService.findLinkedAccountId` before deferring; replies ephemerally if target is not linked.
  - Public `deferReply()`, invokes `MatesService.getMates`.
  - Formats embed with brand color (`EmbedStyle.COLOR`), title `mates.title`, top field `mates.field.top` with lines fitted via `EmbedStyle.fitField`, and footer `mates.footer`.
  - Handles empty teammates with `mates.no_mates`, busy source with `error.data_source_busy`, and unexpected errors via `CommandSupport.handleUnexpectedError`.
- Updated `src/main/resources/messages.properties`:
  - Added `/mates` line to `help.body`.
  - Appended `mates.*` message block at the end.
- Added comprehensive unit, caching, and command tests.

## Important Files

- `src/main/java/sntiago05/rejuv/clients/dto/MateStat.java` (create)
- `src/main/java/sntiago05/rejuv/clients/DeadlockApiClient.java` (modify)
- `src/main/java/sntiago05/rejuv/config/CacheConfig.java` (modify)
- `src/main/java/sntiago05/rejuv/domain/Teammate.java` (create)
- `src/main/java/sntiago05/rejuv/domain/MatesSummary.java` (create)
- `src/main/java/sntiago05/rejuv/services/MatesService.java` (create)
- `src/main/java/sntiago05/rejuv/commands/MatesCommand.java` (create)
- `src/main/resources/messages.properties` (modify)
- `src/test/resources/fixtures/deadlock-api/mate-stats.json` (create)
- `src/test/java/sntiago05/rejuv/clients/MateStatsClientTest.java` (create)
- `src/test/java/sntiago05/rejuv/clients/DeadlockApiClientCachingTest.java` (modify)
- `src/test/java/sntiago05/rejuv/services/MatesServiceTest.java` (create)
- `src/test/java/sntiago05/rejuv/commands/MatesCommandTest.java` (create)
- `src/test/java/sntiago05/rejuv/commands/HelpCommandTest.java` (modify)
- `docs/task-reports/015-mates.md` (create)

## Decisions

- Mapped JSON field `matches_played` to record component `matches` using `@JsonProperty("matches_played")` to keep the Java record API clean and preserve the spec's intended component names.
- Converted numeric parameters to strings in `MatesCommand` when calling `Messages.get(...)` to prevent `MessageFormat` from injecting locale number grouping separators (e.g. `Player #2000001` instead of `Player #2,000,001`).
- Excluded party stats (`party-stats` endpoint, `PartyStat`, `PartySplit`, and `/profile` party line modifications) based on owner approval because deadlock-api does not support a party-stats endpoint.

## Verification

- **Baseline before changes**: `.\mvnw.cmd clean verify` exited 0 with **498 tests** (0 failures, 2 skipped benchmark tests).
- **Narrow test runs**:
  - `.\mvnw.cmd test -Dtest=MateStatsClientTest`: 5 tests, 0 failures.
  - `.\mvnw.cmd test -Dtest=DeadlockApiClientCachingTest`: 20 tests, 0 failures (includes 2 new tests and exact-set cache check with `deadlockMateStats`).
  - `.\mvnw.cmd test -Dtest=MatesServiceTest`: 7 tests, 0 failures.
  - `.\mvnw.cmd test -Dtest=MatesCommandTest`: 10 tests, 0 failures.
  - `.\mvnw.cmd test -Dtest=HelpCommandTest`: 5 tests, 0 failures.
  - `.\mvnw.cmd test -Dtest=MessagesIntegrationTest`: 5 tests, 0 failures.
- **Full build**: `.\mvnw.cmd clean verify` exited 0 with **522 tests** (0 failures, 0 errors, 2 skipped, +24 new tests).
- **Manual Discord check**: Not executed by implementer (requires live bot token and Discord server setup).
- **Diff check**: `git diff --check` clean; no secrets, tokens or real player data committed; `.env` was never read.

## Acceptance criteria

| Criterion | Evidence |
|---|---|
| Step 0 recorded real endpoints and field names; no field invented | Step 0 findings above; `GET /v1/players/{account_id}/mate-stats` verified live |
| `/mates` lists up to 5 teammates (at least 5 games together) with games and win rate together, over 90 days | `MatesService.getMates`, `MatesCommand.embed`, `MatesServiceTest.orderingByWinRateDescThenMatchesDescThenAccountIdAsc`, `top5Truncation` |
| `/profile` shows solo vs party only when data exists and never fails because of it | Party stats excluded per owner approval due to deadlock-api missing the endpoint; `/profile` untouched |
| Both new reads are cached and covered by exact-set cache test | `CacheConfig.DEADLOCK_MATE_STATS`; `cacheTtlsAndSizesMatchTheDesign`, `mateStatsAreCachedPerAccountAndTimestamp`, `mateStatsFailureIsNotCached` |
| Not linked, no mates, busy source and unexpected errors have distinct messages; unexpected logs error ID | `callerNotLinkedRepliesEphemerallyWithoutDeferring`, `emptyTeammatesEditsWithNoMatesMessage`, `unavailableEditsWithBusyMessage`, `unexpectedErrorEditsWithGenericMessageAndDoesNotRethrow` |
| No parallel or uncached repeated calls; no new dependency, migration or edit outside table | `DeadlockApiClient.getMateStats`, `MatesService`, `git status --short` |
| All user-facing text is in `messages.properties`; no field value exceeds 1024 characters | `allMatesCatalogKeysResolve`, `embedFieldValuesDoNotExceed1024CharactersEvenWithManyMates` |
| `./mvnw clean verify` passes; test count and baseline difference in report | 522 tests (498 baseline + 24 new), 0 failures, 2 skipped |
| Task report created; `PROJECT_CONTEXT.md` update marked pending integration; final diff has no unrelated files | This report; PROJECT_CONTEXT integration section below; `git diff --name-only origin/main...HEAD` |

## Deviations

- **Party stats excluded**: `GET /v1/players/{account_id}/party-stats` returned 404 and is absent from the Deadlock API OpenAPI specification. Under Step 0 condition 3, this was escalated to the owner who chose Option 1: implement mate statistics only and exclude party statistics from Task 015 without inventing an alternative data source. Consequently, `PartyStat.java`, `PartySplit.java`, `DEADLOCK_PARTY_STATS` cache, and modifications to `ProfileSummary.java`, `ProfileService.java`, and `ProfileCommand.java` were omitted.
- **Client test filename**: `src/test/java/sntiago05/rejuv/clients/MateStatsClientTest.java` was created instead of `MateAndPartyStatsClientTest.java` to accurately reflect the exclusion of party stats.

## Known Issues

- deadlock-api does not provide a party statistics endpoint; solo vs party win rate comparison in `/profile` remains deferred until deadlock-api introduces party tracking or a dedicated endpoint.

## PROJECT_CONTEXT integration

**PROJECT_CONTEXT update pending integration** (parallel worktree `025` active on `feature/025-matches`).

Facts to add upon integration:
- Task 015 (`/mates [user]`) implemented: public embed displaying a player's top 5 teammates over the last 90 days with games and win rate together (minimum 5 matches together).
- Endpoint `GET /v1/players/{account_id}/mate-stats` integrated via `DeadlockApiClient.getMateStats(accountId, minUnixTimestamp)`, cached in `deadlockMateStats` (10 min, max 200).
- Records `clients/dto/MateStat`, `domain/Teammate`, `domain/MatesSummary`.
- Service `MatesService` and command `MatesCommand` (`mates`).
- `/help` includes `/mates [user]`.
- Note: Party statistics endpoint `players/{id}/party-stats` does not exist in deadlock-api; party stats in `/profile` was excluded.

## Merge Risks

- Trivial conflicts expected in `CacheConfig`, `DeadlockApiClient`, `DeadlockApiClientCachingTest`, and `messages.properties` when merged alongside Task 025 or other parallel tasks. All additions are appended at the end of sections to minimize collision surface.
