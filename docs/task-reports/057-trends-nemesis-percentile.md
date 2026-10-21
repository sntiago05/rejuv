# Task 057: `/trends`, `/nemesis` and rank percentile in `/profile`

## Status
Implemented on branch `feature/057-trends-nemesis-percentile`.
`env -u ANTHROPIC_BASE_URL -u ANTHROPIC_CUSTOM_HEADERS .claude/skills/run-rejuv/driver.sh verify` passes: 1,185 tests run, 0 failures, 0 errors, 14 skipped (baseline before changes: 1,137 run, 0 failures, 14 skipped; net +48 new tests).
Not pushed, not merged.
`docs/PROJECT_CONTEXT.md` update pending integration (parallel tasks 055 and 056 active in separate worktrees; `PROJECT_CONTEXT.md` left untouched).

## Summary
1. **`/trends` command & `TrendsService`**:
   - Computes recent trajectory over 10, 20 (default), or 50 matches for linked accounts.
   - Requires at least 5 matches with known outcome (skips and ignores unknown outcomes where `matchResult` or `playerTeam` is not 0 or 1).
   - Single pass over history to calculate: win rate, average K/D/A, KDA ratio `(K+A)/max(1,D)`, average net worth, win rate comparison between newest half and older half (`pointDifference = winRateRecentHalf - winRateOlderHalf`), direction indicator (`Improving` if diff > +5.0 pts, `Declining` if diff < -5.0 pts, `Steady` if within ±5.0 pts), and top 3 heroes played at least twice sorted by games descending, win rate descending, and hero ID ascending.
   - Embed presentation formatted with mobile-friendly short lines, `EmbedStyle.COLOR`, hero icons via `IconCatalog` and names via `GameAssets`.
2. **`/nemesis` command & `NemesisService`**:
   - Queries `GET /v1/players/{account_id}/enemy-stats` within a 90-day window (`WINDOW = Duration.ofDays(90)`).
   - Filters out self-encounters and opponents with fewer than 5 games (`MIN_NEMESIS_MATCHES = 5`).
   - Ranks opponents by win rate ascending (worst win rate first), then by matches played descending, then by enemy ID ascending; limits to top 5 worst matchups.
   - Reuses shared best-effort persona lookup via `ServiceSupport.fetchPlayerNames`, falling back to `Player #<accountId>` when names are missing or the lookup fails.
3. **Rank percentile in `/profile` & `RankPercentileService`**:
   - Queries `GET /v1/players/rank/distribution` to compute mid-rank percentile:
     `percentile = (playersBelow + (playersSame / 2.0)) / totalPlayers * 100.0`, rounded to one decimal place.
   - Displayed in `/profile` rank field as `Top X% of ranked players` where `X = 100.0 - percentile`.
   - Omitted when player is unranked (badge 0), rank is unknown, or API fails.
   - Failures during percentile fetching in `ProfileService` are caught and logged with a single `WARN`, never breaking the `/profile` command.
4. **Caching**:
   - Added `deadlockEnemyStats` cache (5 min TTL, max 500) and `deadlockRankDistribution` cache (6 h TTL, max 1).
5. **Help**:
   - Listed `/trends` and `/nemesis` in `/help` body.

## Important Files
- **New files**:
  - `src/main/java/sntiago05/rejuv/clients/dto/EnemyStat.java`
  - `src/main/java/sntiago05/rejuv/clients/dto/RankDistributionEntry.java`
  - `src/main/java/sntiago05/rejuv/services/TrendsService.java`
  - `src/main/java/sntiago05/rejuv/services/NemesisService.java`
  - `src/main/java/sntiago05/rejuv/services/RankPercentileService.java`
  - `src/main/java/sntiago05/rejuv/commands/TrendsCommand.java`
  - `src/main/java/sntiago05/rejuv/commands/NemesisCommand.java`
  - `src/test/java/sntiago05/rejuv/clients/EnemyStatsClientTest.java`
  - `src/test/java/sntiago05/rejuv/clients/RankDistributionClientTest.java`
  - `src/test/java/sntiago05/rejuv/services/TrendsServiceTest.java`
  - `src/test/java/sntiago05/rejuv/services/NemesisServiceTest.java`
  - `src/test/java/sntiago05/rejuv/services/RankPercentileServiceTest.java`
  - `src/test/java/sntiago05/rejuv/commands/TrendsCommandTest.java`
  - `src/test/java/sntiago05/rejuv/commands/NemesisCommandTest.java`
  - `src/test/resources/fixtures/deadlock-api/enemy-stats.json`
  - `src/test/resources/fixtures/deadlock-api/rank-distribution.json`
- **Modified files**:
  - `src/main/java/sntiago05/rejuv/clients/DeadlockApiClient.java` (`getEnemyStats`, `getRankDistribution`)
  - `src/main/java/sntiago05/rejuv/config/CacheConfig.java` (registered `deadlockEnemyStats`, `deadlockRankDistribution`)
  - `src/main/java/sntiago05/rejuv/domain/ProfileSummary.java` (added `rankPercentile` nullable component and backwards-compatible constructor)
  - `src/main/java/sntiago05/rejuv/services/ProfileService.java` (integrated `RankPercentileService`, error handling with WARN)
  - `src/main/java/sntiago05/rejuv/services/ServiceSupport.java` (extracted `fetchPlayerNames` helper)
  - `src/main/java/sntiago05/rejuv/services/MatesService.java` (delegated persona lookup to `ServiceSupport.fetchPlayerNames`)
  - `src/main/java/sntiago05/rejuv/commands/ProfileCommand.java` (rendered percentile line when present)
  - `src/main/resources/messages.properties` (added `/trends` and `/nemesis` to `help.body`, appended keys at end)
  - `src/test/java/sntiago05/rejuv/clients/DeadlockApiClientCachingTest.java` (updated cache assertions and added caching tests)
  - `src/test/java/sntiago05/rejuv/services/ProfileServiceTest.java` (percentile present, distribution failure handling, unranked)
  - `src/test/java/sntiago05/rejuv/commands/ProfileCommandTest.java` (percentile rendering tests)
  - `src/test/java/sntiago05/rejuv/commands/HelpCommandTest.java` (verified `/trends` and `/nemesis` in help body)

## Real-Call Findings
1. **`enemy_id` meaning**:
   - Confirmed against live `https://api.deadlock-api.com/v1/players/{account_id}/enemy-stats` using the linked developer account.
   - `enemy_id` returned values such as `839065898`, `458049803`, `1138292169`. These are numeric 32-bit Steam account IDs (identical to `mate_id` in `mate-stats`), not Deadlock hero IDs (which are small integers 1..60).
   - The stop-and-ask condition ("If enemy_id turns out to be a hero id") did not trigger.
2. **Rank distribution and `badge` ordering**:
   - Confirmed against live `https://api.deadlock-api.com/v1/players/rank/distribution`.
   - Badges range from 11 (`rank: 1`, `subrank: 1` — Initiate 1) up to 116 (`rank: 11`, `subrank: 6` — Eternus 6).
   - Badges strictly follow `rank * 10 + subrank`, so sorting by `badge` ascending orders ranks from lowest to highest.
3. **Win/Loss encoding reused**:
   - Reused the standard project encoding: `entry.matchResult() == entry.playerTeam()` (consistent with `ProfileService`, `MatchSearchService`, and `FormCoachProvider`).
   - Matches with unknown outcomes (`matchResult` or `playerTeam` not in `{0, 1}`) are filtered out and not counted in `/trends`.

## Decisions
- **Shared persona lookup**: Extracted `fetchPlayerNames` to `ServiceSupport.java` to deduplicate name resolution between `MatesService` and `NemesisService`, keeping logging format identical (`"Persona names unavailable for {} {}: {}: {}"`).
- **Graceful profile percentile degradation**: In `ProfileService`, injected `RankPercentileService` with `@Autowired(required = false)` and fallback to direct instantiation when not in Spring context, preserving existing tests (`ProfileServiceCachingTest`) without editing them. Percentile failures log a single WARN and leave `rankPercentile` null.
- **Mid-rank formula**: `(playersBelow + playersSame / 2.0) / totalPlayers * 100.0`, rounded to one decimal place via `Math.round(val * 10.0) / 10.0`. `ProfileCommand` formats the top percentage as `100.0 - percentile`.

## Verification
- Baseline verify: `1,137` tests run, 0 failures, 14 skipped.
- Final verify (`env -u ANTHROPIC_BASE_URL -u ANTHROPIC_CUSTOM_HEADERS .claude/skills/run-rejuv/driver.sh verify`):
  `1,185` tests run, 0 failures, 14 skipped.
- Direct unit tests executed and passed:
  - `EnemyStatsClientTest`: fixture parsing, query parameters, empty list, 503 retry, 500 error.
  - `RankDistributionClientTest`: fixture parsing, empty list, 503 retry, 500 error.
  - `DeadlockApiClientCachingTest`: TTLs/sizes exact-set match, repeated-call cache hits for enemy stats and rank distribution.
  - `TrendsServiceTest`: hand-checked math calculations (win rate, K/D/A, KDA ratio, net worth), half comparisons, direction thresholds (+20, 0, -60), unknown outcome skipping, sample size limiting, and `< 5` games handling.
  - `NemesisServiceTest`: ordering (win rate ascending, matches descending, account ID ascending), minimum 5 matches filter, self-exclusion, persona name resolution and fallback on error.
  - `RankPercentileServiceTest`: hand-made distribution (first rank, middle, top), unknown rank, missing badge, empty distribution, and API error propagation.
  - `ProfileServiceTest`: percentile included, unranked handling, and profile rendering intact when distribution call throws `DeadlockApiException`.
  - `ProfileCommandTest`: embeds percentile line when present (`"Archon 3\nTop 18.4% of ranked players"`), omits when null.
  - `TrendsCommandTest` & `NemesisCommandTest`: slash command metadata, unlinked caller/other replies, parameter options, embed structure, error handling.
  - `HelpCommandTest`: MVP topics check including `/trends` and `/nemesis`.

## Deviations
- **Modification of `ServiceSupport.java` and `MatesService.java`**: Authorized by owner to satisfy acceptance criterion 4 ("no duplicated persona-lookup code") and the task spec row 29 guidance ("extract to ServiceSupport or a small shared component only if MatesService already has it private").

## Known Issues
- None. All 1,185 tests are green.

## Merge Risks
- Tasks 055 and 056 are active in parallel worktrees:
  - `CacheConfig.java` added entries at the end of definitions and registrations.
  - `DeadlockApiClientCachingTest.java` added cache map entries.
  - `messages.properties` appended keys at the end.
  - Merging with 056 will encounter trivial additions in these files; keep both sides as instructed in "Parallel work".
- `docs/PROJECT_CONTEXT.md` was deliberately not edited to avoid conflicting with concurrent worktrees.
