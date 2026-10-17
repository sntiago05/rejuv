# Task 013: `/meta [rank]`

## Status

Implemented and verified locally. `.\mvnw.cmd clean verify` passes with 212 tests (183 baseline + 29 new). Not pushed, not merged.

## Step 0 Findings

Checked `GET /v1/analytics/hero-stats` in `https://api.deadlock-api.com/openapi.json`:
1. **Query parameters**:
   - `min_average_badge` and `max_average_badge`: integer (0..116), filters by average badge level of both teams involved.
   - `min_unix_timestamp`: int64 unix timestamp, filters matches based on start time. Default when absent is 30 days ago (OpenAPI example default `1789603200`).
   - `bucket`: string enum (`no_bucket`, `avg_badge`, etc.) changes grouping; intentionally not sent.
2. **Response shape**:
   - JSON array of `AnalyticsHeroStats` objects.
   - Per-hero fields include `hero_id` (int32), `matches` (int64), `wins` (int64), `losses` (int64), along with bucket and total metrics (e.g. `matches_per_bucket`, `total_kills`).
   - The field names match the designed `HeroStat` DTO; extra fields are ignored via `@JsonIgnoreProperties(ignoreUnknown = true)`.

## Summary

- Created `clients/dto/HeroStat` record (`heroId`, `matches`, `wins`, `losses`).
- Created fixture `src/test/resources/fixtures/deadlock-api/hero-stats.json` with 5 heroes and invented numbers.
- Extended `clients/DeadlockApiClient`:
  - Added constants `HERO_STATS_PATH = "/v1/analytics/hero-stats"` and `HERO_STATS_TYPE`.
  - Added `@Cacheable(cacheNames = CacheConfig.DEADLOCK_HERO_STATS, sync = true, key = "#rankTier + ':' + #minUnixTimestamp") public List<HeroStat> getHeroStats(Integer rankTier, long minUnixTimestamp)` which validates `rankTier` 0..11 when non-null and delegates to `get(..., false)`.
  - Added private `heroStatsFilter` helper adding `min_unix_timestamp` and optional badge range `rankTier*10` .. `rankTier*10+9`.
- Added cache `CacheConfig.DEADLOCK_HERO_STATS = "deadlockHeroStats"`, 6 hours TTL, max size 50.
- Created domain records and enum:
  - `domain/HeroTier`: S (>= 53%), A (>= 51%), B (>= 49%), C (>= 47%), D (< 47%).
  - `domain/MetaHero`: `heroId`, `heroName`, `matches`, `winRatePercent`, `tier`.
  - `domain/MetaTierList`: `rankTier`, `minMatches`, `heroes` (defensively copied to immutable list).
- Created `services/MetaService`:
  - Validates `rankTier` (null or 1..11, rejecting outside 1..11 before any API call).
  - Truncates start of current UTC day minus 30 days (`WINDOW`) to epoch seconds.
  - Calls `deadlockApiClient.getHeroStats` then `deadlockApiClient.getHeroes` sequentially.
  - Filters out heroes with `< 100` matches (`MIN_MATCHES`), deduplicates keeping first, maps hero names, and sorts by `winRatePercent` desc then `heroId` asc.
  - Two-constructor `Clock` pattern (public `@Autowired` with `Clock.systemUTC()`, package-private with `Clock` for tests).
- Created `commands/MetaCommand`:
  - Registered `/meta [rank]` with optional `INTEGER` option having 11 choices for tiers 1..11 labeled with catalog names.
  - Public `deferReply()`, synchronous call to `MetaService.getTierList(rankTier)`.
  - Maps empty result to `meta.no_data`, `DeadlockApiUnavailableException` to `error.data_source_busy`, and unexpected exceptions to `error.generic` with UUID error id logged without rethrowing.
  - Embed with title, fields only for non-empty tiers in S..D order, formatted hero entries (`meta.hero_entry` or `meta.hero_unknown`), and footer with `minMatches`.
- Appended `meta.*` keys to `messages.properties` and added `/meta [rank]` to `help.body` after `/analyze`.

## Important Files

- `src/main/java/sntiago05/rejuv/clients/dto/HeroStat.java` (create)
- `src/main/java/sntiago05/rejuv/clients/DeadlockApiClient.java` (modify)
- `src/main/java/sntiago05/rejuv/config/CacheConfig.java` (modify)
- `src/main/java/sntiago05/rejuv/domain/HeroTier.java` (create)
- `src/main/java/sntiago05/rejuv/domain/MetaHero.java` (create)
- `src/main/java/sntiago05/rejuv/domain/MetaTierList.java` (create)
- `src/main/java/sntiago05/rejuv/services/MetaService.java` (create)
- `src/main/java/sntiago05/rejuv/commands/MetaCommand.java` (create)
- `src/main/resources/messages.properties` (modify)
- `src/test/resources/fixtures/deadlock-api/hero-stats.json` (create)
- `src/test/java/sntiago05/rejuv/clients/DeadlockApiClientTest.java` (modify: 4 new tests)
- `src/test/java/sntiago05/rejuv/clients/DeadlockApiClientCachingTest.java` (modify: cache config exact check + 1 new test)
- `src/test/java/sntiago05/rejuv/services/MetaServiceTest.java` (create: 16 tests)
- `src/test/java/sntiago05/rejuv/commands/MetaCommandTest.java` (create: 8 tests)
- `src/test/java/sntiago05/rejuv/commands/HelpCommandTest.java` (modify)
- `src/test/java/sntiago05/rejuv/services/MessagesIntegrationTest.java` (modify)

## Decisions

- Tier classification uses fixed win-rate thresholds (53/51/49/47) as decided by the spec.
- 30-day window truncates to the start of the current UTC day to keep cache keys stable for a full day.
- Static `INTEGER` choices 1..11 avoid external API calls during slash command registration.
- Used lowercase catalog keys `meta.tier.s` ... `meta.tier.d` to comply with the project message catalog convention (`KEY_PATTERN`).
- Permitted numeric dot segments in `MessagesIntegrationTest.KEY_PATTERN` (`^[a-z][a-z0-9_]*(\\.[a-z0-9_]+)+$`) so `meta.rank.1`..`11` pass catalog validation.

## Verification

- **Baseline before changes**: `.\mvnw.cmd clean verify` exited 0 with 183 tests, 0 failures.
- **Narrow test runs**:
  - `.\mvnw.cmd test "-Dtest=DeadlockApiClientTest,DeadlockApiClientCachingTest"`: 35 tests, 0 failures.
  - `.\mvnw.cmd test -Dtest=MetaServiceTest`: 16 tests, 0 failures.
  - `.\mvnw.cmd test "-Dtest=MetaCommandTest,HelpCommandTest"`: 13 tests, 0 failures.
  - `.\mvnw.cmd test -Dtest=MessagesIntegrationTest`: 5 tests, 0 failures.
- **Full build**: `.\mvnw.cmd clean verify` exited 0 with **212 tests**, 0 failures, 0 errors, 0 skipped (+29 new tests).
- **Manual Discord check**: Not executed by the implementer (requires live bot credentials and guild).
- **Diff check**: `git diff --check` clean; no secrets, tokens or real player data committed; `.env` was never read.

## Acceptance criteria

| Criterion | Evidence |
|---|---|
| Step 0 findings in report, DTO matches response shape | Step 0 Findings section above; `HeroStat` matches `AnalyticsHeroStats` |
| `./mvnw clean verify` passes, test count and baseline in report | 212 tests (183 baseline + 29 new), exit code 0 |
| `getHeroStats` sends listed params, validates tier, cached 6 h (max 50) | `heroStatsSendsBadgeRangeAndTimestamp`, `heroStatsWithoutRankSendsNoBadgeFilter`, `heroStatsRejectsTierOutOfRange`, `heroStatsAreCachedPerTierAndTimestamp` |
| `/meta` works without linked account; public defer; non-empty tiers S..D sorted by win rate | `defersPubliclyAndEditsWithEmbed`, `titleUsesAllRanksWithoutOption`, `MetaCommandTest` |
| Heroes < 100 matches excluded; tier thresholds 53/51/49/47 | `heroesBelowMinMatchesAreDropped`, `tierThresholdBoundaries` |
| `/meta rank:<tier>` filters badge range; 30-day window from start of UTC day | `rankOptionIsPassedToService`, `timestampIsStartOfUtcDayMinus30Days`, `MetaServiceTest` |
| Empty result, API unavailable, unexpected errors give own message with logged error id | `emptyListEditsWithNoData`, `unavailableEditsWithBusyMessage`, `unexpectedErrorEditsWithGenericMessageAndDoesNotRethrow` |
| Sequential API calls; no business logic in command; no JDA types in service | `callsStatsThenHeroesSequentially`; `MetaService` has no JDA imports; `MetaCommand` only formats |
| `help.body` lists `/meta` | `helpBodyCoversMvpTopics` passes; `/meta [rank]` present in `messages.properties` |
| Only table files changed; no new dependency, migration or secret | `git status --short`, `git diff --name-only origin/main...HEAD` |
| Task report created, `PROJECT_CONTEXT.md` updated | This report; `PROJECT_CONTEXT.md` updated after merging `main` |

## Deviations

1. Used lowercase keys `meta.tier.s` ... `meta.tier.d` instead of uppercase `meta.tier.S` ... `meta.tier.D` to comply with the catalog lower snake case naming convention. Approved by owner.
2. Updated `KEY_PATTERN` in `MessagesIntegrationTest` to `^[a-z][a-z0-9_]*(\\.[a-z0-9_]+)+$` to permit numeric dot segments like `meta.rank.1` ... `meta.rank.11`. Approved by owner.

## Known Issues

- Command choice options for rank are 1 to 11 (tier 0 `Obscurus` is omitted as decided in the spec).
- If a rank has no hero with at least 100 matches in the 30-day window, `meta.no_data` is returned.
- API calls happen synchronously on the JDA event thread after public deferral.

## PROJECT_CONTEXT integration

Incoming changes from `main` (Task 007: PR #33) were merged cleanly into `feature/013-meta`. `docs/PROJECT_CONTEXT.md` has been updated with:
- Task 007 (`MetricsService`) merged and verified.
- Task 013 (`/meta [rank]`) done: hero tier list (S to D) by 30-day win rate (min 100 matches); public deferred embed; `DeadlockApiClient.getHeroStats` and cache `deadlockHeroStats` 6 h / 50; `MetaService` and `MetaCommand`. 243 combined tests on merged branch.
- Deadlock API facts: `GET /v1/analytics/hero-stats` filters by `min_average_badge`/`max_average_badge` (0..116) and `min_unix_timestamp` (default 30 days ago); returns array of per-hero stats.
- Messages catalog constraint: `KEY_PATTERN` in `MessagesIntegrationTest` allows numeric dot segments (`(\\.[a-z0-9_]+)+`).
- Next: Task 009 (`/analyze`).

## Merge Risks

- `messages.properties`, `MessagesIntegrationTest`, `CacheConfig` and `DeadlockApiClientCachingTest` are shared with parallel tasks. All additions are modular and append-only, so textual conflicts will be easy to resolve.
