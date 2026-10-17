# Task 012: `/counter hero [rank]`

## Status

Implemented and verified locally. `.\mvnw.cmd clean verify` passes with 332 tests (295 baseline + 37 new). Not pushed, not merged.

## Step 0 Findings

Live verification against `https://api.deadlock-api.com` performed on 2026-10-17:
1. `GET /v1/analytics/hero-counter-stats?min_unix_timestamp=<ts>`:
   - Returned a JSON array of 1,806 pair rows (for 43 heroes, 43 * 42 = 1,806 ordered pairs).
   - For hero 1 (Infernus): 42 rows with `hero_id == 1` and 42 rows with `enemy_hero_id == 1`.
   - Mirror matches (`hero_id == enemy_hero_id`): 0 rows found in the live response.
   - Response properties: `hero_id`, `enemy_hero_id`, `wins`, `matches_played`, along with unmapped stats (`kills`, `deaths`, `assists`, `networth`, etc.).
   - Matches exactly the designed `HeroCounterStat` record with `@JsonIgnoreProperties(ignoreUnknown = true)`.
2. `GET /v1/analytics/item-stats?enemy_hero_ids=1&min_unix_timestamp=<ts>`:
   - Returned 156 rows.
   - Unique item IDs: 0 duplicates found (one row per item).
   - Rows include `item_id`, `matches`, `wins`, `losses`, `avg_buy_time_s`, `avg_buy_time_relative`, etc.
   - Matches the existing `ItemStat` DTO.

## Summary

- Created `clients/dto/HeroCounterStat` record (`heroId`, `enemyHeroId`, `wins`, `matchesPlayed`).
- Created fixture `src/test/resources/fixtures/deadlock-api/hero-counter-stats.json` with 4 heroes, 9 pair rows (both directions and a mirror row), unknown fields, and invented numbers.
- Extended `clients/DeadlockApiClient`:
  - Added constants `HERO_COUNTERS_PATH = "/v1/analytics/hero-counter-stats"` and `HERO_COUNTERS_TYPE`.
  - Added `getHeroCounterStats(Integer rankTier, long minUnixTimestamp)` cached in `deadlockHeroCounters` (6 h, max 50).
  - Added `getItemStatsAgainst(int enemyHeroId, Integer rankTier, long minUnixTimestamp)` cached in `deadlockCounterItems` (6 h, max 200).
  - Reused `heroStatsFilter` for `getHeroCounterStats` and added `itemStatsAgainstFilter` helper for `getItemStatsAgainst`.
- Registered caches `CacheConfig.DEADLOCK_HERO_COUNTERS` and `CacheConfig.DEADLOCK_COUNTER_ITEMS` in `CacheConfig`.
- Created domain records:
  - `domain/CounterMatchup`: `heroId`, `heroName`, `matches`, `winRatePercent`.
  - `domain/CounterItem`: `itemId`, `itemName`, `matches`, `winRatePercent`.
  - `domain/CounterGuide`: `heroId`, `heroName`, `rankTier`, `counters`, `favorable`, `items` (immutable lists).
- Created `services/CounterService`:
  - Validates `rankTier` (null or 1..11, rejecting outside 1..11 before any API call).
  - Resolves hero via `HeroResolver.resolve(heroInput)` (reused from Task 011), throwing `HeroNotFoundException` without stats calls if unresolvable.
  - Computes 30-day window (`WINDOW`) from start of current UTC day.
  - Calls `getHeroCounterStats`, `getItemStatsAgainst`, `getHeroes`, and `getItems` sequentially.
  - Filters counters: `enemyHeroId == targetHero.id()`, `heroId != targetHero.id()`, `matchesPlayed >= 50`, deduplicates keeping first, sorts by `winRatePercent` desc, `matches` desc, `heroId` asc, capped at 5.
  - Filters favorable: `heroId == targetHero.id()`, `enemyHeroId != targetHero.id()`, `matchesPlayed >= 50`, opponent is `enemyHeroId`, deduplicates keeping first, sorts identically, capped at 5.
  - Filters items: `matches >= 100`, item resolves in `getItems()` to an asset with `type` equal to `"upgrade"` (case-insensitive) and non-blank name, deduplicates keeping first, sorts by `winRatePercent` desc, `matches` desc, `itemId` asc, capped at 5.
  - Two-constructor `Clock` pattern for deterministic testing.
- Created `commands/CounterCommand`:
  - Slash command `/counter` with required string option `hero` (max length 50) and optional integer option `rank` (11 choices 1..11).
  - Public `deferReply()`, synchronous execution of `CounterService.getCounterGuide`.
  - Error handling: all empty lists -> `counter.no_data`; `HeroNotFoundException` -> `counter.hero_not_found` (without echoing user input); `DeadlockApiUnavailableException` -> `error.data_source_busy`; unexpected exception -> log error with UUID and reply `error.generic` without rethrowing.
  - EmbedBuilder: Title formatted with hero name and rank label; non-empty fields in order (`counters`, `items`, `favorable`) with formatted entries; footer formatted with minimum match counts.
- Appended `counter.*` keys to `messages.properties` and added `/counter hero [rank]` to `help.body` after `/build hero [rank]`.

## Important Files

- `src/main/java/sntiago05/rejuv/clients/dto/HeroCounterStat.java` (create)
- `src/main/java/sntiago05/rejuv/clients/DeadlockApiClient.java` (modify)
- `src/main/java/sntiago05/rejuv/config/CacheConfig.java` (modify)
- `src/main/java/sntiago05/rejuv/domain/CounterMatchup.java` (create)
- `src/main/java/sntiago05/rejuv/domain/CounterItem.java` (create)
- `src/main/java/sntiago05/rejuv/domain/CounterGuide.java` (create)
- `src/main/java/sntiago05/rejuv/services/CounterService.java` (create)
- `src/main/java/sntiago05/rejuv/commands/CounterCommand.java` (create)
- `src/main/resources/messages.properties` (modify)
- `src/test/resources/fixtures/deadlock-api/hero-counter-stats.json` (create)
- `src/test/java/sntiago05/rejuv/clients/DeadlockApiClientTest.java` (modify: 8 new tests)
- `src/test/java/sntiago05/rejuv/clients/DeadlockApiClientCachingTest.java` (modify: 12-cache exact-set assertion + 2 new tests)
- `src/test/java/sntiago05/rejuv/services/CounterServiceTest.java` (create: 17 tests)
- `src/test/java/sntiago05/rejuv/commands/CounterCommandTest.java` (create: 10 tests)
- `src/test/java/sntiago05/rejuv/commands/HelpCommandTest.java` (modify)
- `src/test/java/sntiago05/rejuv/services/MessagesIntegrationTest.java` (modify)

## Decisions

- Global pair table cached per tier/day: `getHeroCounterStats` queries the global counter stats endpoint once per rank tier and window, allowing all hero lookups for that tier/day to be served from cache without extra API calls.
- Item filter: Only upgrade items with a known name in `/v1/assets/items` are shown; abilities, weapons, and unknown item IDs are filtered out so they do not pollute the recommendation list.
- Thresholds: Minimum 50 matches for hero matchups and 100 matches for items to avoid noisy or unrepresentative low-sample statistics.
- Footer limitation notice: Communicates that item win rates are raw percentages not adjusted for cost or timing.

## Verification

- **Baseline before changes**: `.\mvnw.cmd test` passed with 295 tests, 0 failures.
- **Narrow test runs**:
  - `.\mvnw.cmd test -Dtest=CounterServiceTest`: 17 tests, 0 failures.
  - `.\mvnw.cmd test -Dtest=CounterCommandTest`: 10 tests, 0 failures.
  - `.\mvnw.cmd test -Dtest=DeadlockApiClientTest`: 45 tests, 0 failures.
  - `.\mvnw.cmd test -Dtest=DeadlockApiClientCachingTest`: 11 tests, 0 failures.
  - `.\mvnw.cmd test -Dtest=HelpCommandTest`: 5 tests, 0 failures.
  - `.\mvnw.cmd test -Dtest=MessagesIntegrationTest`: 5 tests, 0 failures.
- **Full build**: `.\mvnw.cmd clean verify` exited 0 with **332 tests**, 0 failures, 0 errors, 0 skipped (+37 new tests).
- **Manual Discord check**: Not executed in dev guild (requires live Discord bot token and test guild).
- **Diff check**: `git diff --check` clean; no secrets, tokens or real user data committed; `.env` was never read.

## Acceptance Criteria

| Criterion | Evidence |
|---|---|
| Task 011 is merged; `HeroResolver` is reused, not copied | `HeroResolver` and `HeroNotFoundException` reused directly from `services` |
| Step 0 findings in report, DTO matches response shape | Step 0 Findings section above; `HeroCounterStat` matches live API |
| `./mvnw clean verify` passes, test count and baseline in report | 332 tests (295 baseline + 37 new), exit code 0 |
| `getHeroCounterStats` and `getItemStatsAgainst` send listed params, validate tier, cached 6 h | `heroCountersSendBadgeRangeAndTimestamp`, `itemStatsAgainstSendsEnemyHeroBadgeRangeAndTimestamp`, `heroCountersAreCachedPerTierAndTimestamp`, `itemStatsAgainstAreCachedPerHeroTierAndTimestamp` |
| `/counter` works without linked account; public defer; shows non-empty fields (<= 5 entries); all text from catalog | `defersPubliclyAndEditsWithEmbed`, `emptyListsAreOmittedFromTheEmbed`, `CounterCommandTest` |
| Matchups < 50 matches and items < 100 matches excluded; only upgrade items with name shown; mirror rows ignored | `pairsBelowMinMatchesAreDropped`, `itemsBelowMinMatchesAreDropped`, `itemsFilterToUpgradesWithAKnownName`, `mirrorRowIsIgnored` |
| Unknown hero, empty data, API unavailable, unexpected errors give own message; unknown-hero does not echo input; unexpected errors log error id | `allEmptyEditsWithNoData`, `heroNotFoundEditsWithoutEchoingInput`, `unavailableEditsWithBusyMessage`, `unexpectedErrorEditsWithGenericMessageAndDoesNotRethrow` |
| Sequential API calls; no business logic in command; no JDA types in service | `callsCountersThenItemsThenAssetsSequentially`; `CounterService` has no JDA imports; `CounterCommand` only formats |
| `help.body` lists `/counter` | `helpBodyCoversMvpTopics` passes; `/counter hero [rank]` present in `messages.properties` |
| Only table files changed; no new dependency, migration or secret | `git status --short`, `git diff --name-only origin/main...HEAD` |
| Task report created with `PROJECT_CONTEXT.md` update marked pending integration | This report; `docs/PROJECT_CONTEXT.md` update deferred per AGENTS.md rules |

## Deviations

1. In `CounterCommand`, `MIN_MATCHUP_MATCHES = "50"` and `MIN_ITEM_MATCHES = "100"` are defined as private constants within the command class because `CounterService.MIN_MATCHUP_MATCHES` is package-private in `sntiago05.rejuv.services`.
2. In `DeadlockApiClientTest`, helper `noQueryParamNamed(String name)` was introduced for `getItemStatsAgainst` tests so that the substring check does not trigger a false positive when `enemy_hero_ids` is present and contains `hero_id`/`hero_ids` as a substring.
3. In `DeadlockApiClientCachingTest.cacheTtlsAndSizesMatchTheDesign`, `Map.ofEntries` is used instead of `Map.of` because Java's `Map.of` has an upper limit of 10 key-value pairs (now 12 caches).

## Known Issues

- As with `/build` and `/meta`, API calls happen synchronously on the JDA event thread after public deferral.
- If no recent matchups or items pass the minimum match thresholds, `counter.no_data` is returned.

## PROJECT_CONTEXT integration

PROJECT_CONTEXT update pending integration. Parallel worktrees/feature branches are currently active (`006`, `007`, `008`, `011`, `013`, `022`).
Facts to integrate:
- Task 012 (`/counter hero [rank]`) done.
- Caches added to `CacheConfig`: `deadlockHeroCounters` (6 h, max 50) and `deadlockCounterItems` (6 h, max 200).
- Deadlock API observed facts:
  - `GET /v1/analytics/hero-counter-stats`: 1,806 pair rows (for 43 heroes, 43 * 42 pairs), no mirror rows in live response, pair fields `hero_id`, `enemy_hero_id`, `wins`, `matches_played`.
  - `GET /v1/analytics/item-stats?enemy_hero_ids=<id>`: 156 rows, 0 duplicate item IDs, per-item win and match stats.

## Merge Risks

- `messages.properties`, `MessagesIntegrationTest`, `CacheConfig`, `DeadlockApiClientCachingTest`, and `HelpCommandTest` are modified by parallel task branches. All changes are additive/modular and follow existing conventions, minimizing merge conflicts.
