# Task 014: `/heroes [user]`

## Status

Implemented and verified locally. `.\mvnw.cmd clean verify` passes with 384 tests (344 baseline + 40 new, 2 skipped). Not pushed, not merged.

## Step 0 Findings

1. **First live GET (`GET /v1/players/hero-stats?account_ids=139205933&min_unix_timestamp=1784497204`)**:
   - Status 200, length 40,549 bytes, 31 hero items.
   - Fields confirmed: `account_id`, `hero_id`, `matches_played`, `wins`, `kills`, `deaths`, `assists`, `last_played`, `time_played`, `matches`.
   - With `min_unix_timestamp` 90 days ago (1784497204), total `matches_played` was 501 across 31 heroes; with 30 days ago (1789681204), total `matches_played` was 194 across 23 heroes. A later timestamp returns fewer matches (194 <= 501), verifying start-time filtering.
2. **Second live GET (`GET /v1/analytics/hero-stats?min_unix_timestamp=1789681204&min_average_badge=70&max_average_badge=79`)**:
   - Status 200, length 26,801 bytes, 43 rows, 43 unique hero IDs (exactly one row per hero, no duplicates).
   - Fields `total_kills`, `total_deaths`, `total_assists` confirmed present.
   - Per-match averages for hero 1: kills ~6.38, deaths ~6.69, assists ~10.88.
   - Per-match deaths (6.69) are of the same order of magnitude as player stats deaths per match (5.86 for hero 1), confirming they are sums over the row's `matches`.

## Summary

- Created `clients/dto/PlayerHeroStat` record (`heroId`, `matchesPlayed`, `wins`, `kills`, `deaths`, `assists`).
- Extended `clients/dto/HeroStat` with `totalKills`, `totalDeaths`, `totalAssists`, keeping the 4-argument constructor and adding `@JsonCreator` with nullable totals defaulting to 0 for backward compatibility with payloads omitting totals.
- Extended `clients/DeadlockApiClient`:
  - Added constants `PLAYER_HERO_STATS_PATH = "/v1/players/hero-stats"` and `PLAYER_HERO_STATS_READER`.
  - Added `@Cacheable(cacheNames = CacheConfig.DEADLOCK_PLAYER_HERO_STATS, sync = true, key = "#accountId + ':' + #minUnixTimestamp") public List<PlayerHeroStat> getPlayerHeroStats(long accountId, long minUnixTimestamp)`.
- Added cache `CacheConfig.DEADLOCK_PLAYER_HERO_STATS = "deadlockPlayerHeroStats"` (10 min TTL, max size 200).
- Created domain records `HeroPerformance` and `HeroPoolSummary`.
- Created `services/HeroPoolService`:
  - Computes 90-day UTC window (`PLAYER_WINDOW`) for player hero stats and 30-day UTC window (`ServiceSupport.WINDOW`) for hero benchmarks.
  - Returns empty summary immediately without making rank or benchmark calls if the player has no matches in the window.
  - Resolves rank tier (1..11, or null if unranked/badge 0).
  - Benchmarks heroes with >= 100 matches (`MIN_BENCHMARK_MATCHES`), indexing by hero ID (first row wins).
  - Computes win rate, KDA (safe division using `Math.max(deaths, 1)`), delta points vs benchmark, and benchmark KDA.
  - Top 5 heroes sorted by matches desc, then heroId asc (`ServiceSupport.top`).
  - Selects `best` pick (> 0 delta, >= 10 matches) and `weakest` pick (< 0 delta, >= 10 matches) with tie-breakers (more matches, lower heroId).
- Created `commands/HeroesCommand`:
  - Registered `/heroes [user]` with optional `USER` option (defaulting to the caller).
  - Performs link check before deferring via `ProfileService.findLinkedAccountId` (replies ephemerally if not linked).
  - Public `deferReply()`, calls `HeroPoolService.getHeroPool`.
  - Maps empty summary to `heroes.no_matches`, 503 to `error.data_source_busy`, and unexpected errors to generic error with UUID error ID logged without rethrowing.
  - Embed with top heroes, win rate comparison vs rank average, KDA vs average, pool tips, and footer.
- Appended `heroes.*` block to `src/main/resources/messages.properties`.

## Important Files

- `src/main/java/sntiago05/rejuv/clients/dto/PlayerHeroStat.java` (create)
- `src/main/java/sntiago05/rejuv/clients/dto/HeroStat.java` (modify)
- `src/main/java/sntiago05/rejuv/clients/DeadlockApiClient.java` (modify)
- `src/main/java/sntiago05/rejuv/config/CacheConfig.java` (modify)
- `src/main/java/sntiago05/rejuv/domain/HeroPerformance.java` (create)
- `src/main/java/sntiago05/rejuv/domain/HeroPoolSummary.java` (create)
- `src/main/java/sntiago05/rejuv/services/HeroPoolService.java` (create)
- `src/main/java/sntiago05/rejuv/commands/HeroesCommand.java` (create)
- `src/main/resources/messages.properties` (modify)
- `src/test/resources/fixtures/deadlock-api/hero-stats.json` (modify)
- `src/test/java/sntiago05/rejuv/clients/DeadlockApiClientTest.java` (modify: 1 new test)
- `src/test/java/sntiago05/rejuv/services/MetaServiceTest.java` (modify: 1 new test)
- `src/test/resources/fixtures/deadlock-api/player-hero-stats.json` (create)
- `src/test/java/sntiago05/rejuv/clients/PlayerHeroStatsClientTest.java` (create: 5 tests)
- `src/test/java/sntiago05/rejuv/clients/DeadlockApiClientCachingTest.java` (modify: cache config exact check + 2 new tests)
- `src/test/java/sntiago05/rejuv/services/HeroPoolServiceTest.java` (create: 16 tests)
- `src/test/java/sntiago05/rejuv/commands/HeroesCommandTest.java` (create: 15 tests)
- `docs/task-reports/014-heroes.md` (create)

## Decisions

- Player window is 90 days (`Duration.ofDays(90)`) while benchmark window is 30 days (`ServiceSupport.WINDOW`), both anchored to the start of the current UTC day.
- Extended `HeroStat` with kill, death, and assist totals using `@JsonCreator` with boxed `Long` parameters defaulting to 0L so payloads without totals deserialize cleanly without breaking primitive constraints.
- Reused `ProfileService.findLinkedAccountId` for the link check before deferring, mirroring `ProfileCommand`.
- Tips require >= 10 matches (`MIN_MATCHES_FOR_TIP`) and non-zero delta; ties are broken by more matches, then lower hero ID.
- Embed field lengths are guaranteed <= 1024 characters; tested with long 80-character hero names.

## Verification

- **Baseline before changes**: `.\mvnw.cmd clean verify` exited 0 with 344 tests (0 failures, 2 skipped benchmark tests).
- **Narrow test runs**:
  - `.\mvnw.cmd test -Dtest=DeadlockApiClientTest`: 53 tests, 0 failures.
  - `.\mvnw.cmd test -Dtest=PlayerHeroStatsClientTest`: 5 tests, 0 failures.
  - `.\mvnw.cmd test -Dtest=DeadlockApiClientCachingTest`: 18 tests, 0 failures.
  - `.\mvnw.cmd test -Dtest=MetaServiceTest`: 18 tests, 0 failures.
  - `.\mvnw.cmd test -Dtest=HeroPoolServiceTest`: 16 tests, 0 failures.
  - `.\mvnw.cmd test -Dtest=HeroesCommandTest`: 15 tests, 0 failures.
- **Full build**: `.\mvnw.cmd clean verify` exited 0 with **384 tests**, 0 failures, 0 errors, 2 skipped (+40 new tests).
- **Manual Discord check**: Not executed by the implementer (requires live bot token and Discord server setup).
- **Diff check**: `git diff --check` clean; no secrets, tokens or real player data committed; `.env` was never read.

## Acceptance criteria

| Criterion | Evidence |
|---|---|
| `getPlayerHeroStats` is cached (`deadlockPlayerHeroStats`, 10 min, max 200) and covered by exact-set cache test | `CacheConfig.DEADLOCK_PLAYER_HERO_STATS`; `cacheTtlsAndSizesMatchTheDesign`, `playerHeroStatsAreCachedPerAccountAndTimestamp`, `playerHeroStatsFailureIsNotCached` |
| `/heroes` shows up to 5 heroes with matches, win rate, win rate comparison, KDA with hero average, pool tips | `HeroesCommand.embed`; `HeroesCommandTest.successDefersPubliclyAndEditsWithEmbed` |
| `HeroStat` has 3 new totals, 4-arg constructor works, `/meta` output unchanged | `DeadlockApiClientTest.heroStatsParsesWithoutTotals`, `MetaServiceTest.metaIgnoresTotals`, existing `MetaCommandTest` passes |
| Unranked players compared with all ranks; heroes with < 100 benchmark matches show no comparison | `unrankedPlayerBadgeZeroUsesNullRankTierForBenchmark`, `benchmarkRowBelowMinMatchesGivesNullBenchmarkValues`, `nullDeltaLineUsesVsAverageUnknown` |
| Player with no matches in 90 days gets plain `heroes.no_matches` reply after single API call | `emptyPlayerStatsReturnsEmptySummaryAndNeverCallsRankOrBenchmark`, `emptyTopHeroesEditsWithNoMatchesMessage` |
| Not linked, busy source and unexpected errors have distinct messages; unexpected logs error ID | `callerNotLinkedRepliesEphemerallyWithoutDeferring`, `unavailableEditsWithBusyMessage`, `unexpectedErrorEditsWithGenericMessageAndDoesNotRethrow` |
| No parallel or uncached repeated calls; no new dependency, migration or shared-file edit outside table | `DeadlockApiClient.getPlayerHeroStats`, `HeroPoolService`, `git status --short` |
| All user-facing text is in `messages.properties`; no field value exceeds 1024 characters | `allHeroesCatalogKeysResolve`, `embedFieldValuesDoNotExceed1024CharactersEvenWithLongHeroNames` |
| `./mvnw clean verify` passes; test count and baseline difference in report | 384 tests (344 baseline + 40 new), 0 failures, 2 skipped |
| Task report created; `PROJECT_CONTEXT.md` update marked pending integration; final diff has no unrelated files | This report; PROJECT_CONTEXT integration section below; `git diff --name-only origin/main...HEAD` |

## Deviations

None.

## Known Issues

None.

## PROJECT_CONTEXT integration

**PROJECT_CONTEXT update pending integration** (parallel task branches `feature/024-rich-presentation`, `feature/009-analyze`, `feature/016-leaderboard-server` active).

Facts to add upon integration:
- Task 014 (`/heroes [user]`) done: public embed displaying player's hero pool over last 90 days, win rate, KDA, comparisons vs rank 30-day average, and pool tips.
- Endpoint `GET /v1/players/hero-stats` integrated via `DeadlockApiClient.getPlayerHeroStats(accountId, minUnixTimestamp)`, cached in `deadlockPlayerHeroStats` (10 min, max 200).
- `HeroStat` extended with `total_kills`, `total_deaths`, `total_assists` while preserving 4-argument constructor and `/meta` behavior.
- Service `HeroPoolService` and command `HeroesCommand` (`heroes`).
- `/help` does not list `/heroes` yet (follow-up after Task 024).

## Merge Risks

- Expected, trivial conflicts in `CacheConfig`, `DeadlockApiClient`, `DeadlockApiClientCachingTest`, and `messages.properties` when merged alongside Task 024, Task 009, or Task 016. All additions in these files follow the designated insertion points at the end of sections to minimize collision surface.
