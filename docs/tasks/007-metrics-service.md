# Task 007: `MetricsService` (match metrics and benchmarks)

- **Branch**: `feature/007-metrics-service`
- **Implementer**: Claude Code
- **Related ADRs**: ADR-0011 (single-match analysis, backend computes every number), ADR-0010 (deadlock-api integration), ADR-0014 (AI only for interpretation)

## Goal
Add a pure, Discord-agnostic `MetricsService` that turns the data Task 009 will fetch (latest history entry, optional match telemetry, benchmarks, item stats, assets) into the `AnalysisInput` defined by Task 008. It owns the business rules the earlier tasks deferred here: latest-match selection, win/loss, the rank tier used for benchmarks, metric formulas, net-worth interpolation, item selection and buff metrics. It also supports a degraded mode without telemetry. No I/O, no API calls, no AI call, no command.

## Context (verified in the repository when this spec was written)
Do not repeat this discovery. If a fact below is false in your checkout, stop and ask.

- Plan entry: `docs/implementation-plan.md`, task 007, depends on 004 and 022, implementer Claude Code. Tasks 004, 005, 006, 008 and 022 are merged in `main` (PR #25, #27, #31, #32, #28). Start the branch from the latest `origin/main`.
- **Task 013 is planned to run in parallel** in another worktree. It changes `DeadlockApiClient`, `CacheConfig`, `messages.properties`, `HelpCommand*`, `MessagesIntegrationTest` and `DeadlockApiClientCachingTest`. This task must not touch any of those files.
- Existing code you will use (do not change it):
  - `domain/AnalysisInput(String hero, String rank, String result, double durationMin, Map<String, MetricComparison> metrics, List<ItemTiming> items, String language)` with nested `MetricComparison(double value, Double p25, Double p50, Double p75)` and `ItemTiming(String name, double boughtAtMin, Double avgBoughtAtMin)`. Null benchmarks are omitted in JSON (`@JsonInclude(NON_NULL)` on the nested records). The record does not copy `metrics`/`items`.
  - `domain/MatchTelemetry(long matchId, int durationS, List<PlayerTelemetry> players)` with `Optional<PlayerTelemetry> player(long accountId)`. `PlayerTelemetry(long accountId, int team, int heroId, List<ItemPurchase> items, List<StatSnapshot> snapshots, List<PowerUpBuff> powerUpBuffs)`; items sorted by `boughtAtS` ascending, snapshots by `timeS` ascending. `ItemPurchase(long itemId, int boughtAtS, Integer soldAtS)`. `StatSnapshot(int timeS, long netWorth, long playerDamage, long playerHealing)`. `PowerUpBuff(String type, boolean permanent, List<Integer> pickupTimesS)` (pickups sorted ascending). All lists immutable.
  - `clients/dto/MatchHistoryEntry` fields used here: `matchId`, `heroId`, `startTime` (epoch seconds), `matchDurationS`, `matchResult`, `playerTeam`, `playerKills`, `playerDeaths`, `playerAssists`, `netWorth`, `lastHits`, `denies`, `rankedDisplayBadge` (`Integer`, non-null only for ranked matches, `match_mode == 4`).
  - `clients/dto/PlayerRank(int badge, int rank, int subrank)`: an unranked player is `(0, 0, 0)`.
  - `clients/dto/MetricPercentiles(double avg, double p25, double p50, double p75)`; `DeadlockApiClient.getBenchmarks(heroId, rankTier)` returns `Map<String, MetricPercentiles>` keyed by the API metric name, requested **without** `include_buff_metrics`.
  - `clients/dto/ItemStat(long itemId, ..., double avgBuyTimeS, ...)`; `getItemStats(heroId, rankTier)` returns `Map<Long, ItemStat>`.
  - `clients/dto/ItemAsset(long id, String className, String name, String type, Integer itemTier, String itemSlotType, Integer cost)`; types are `upgrade`, `ability`, `weapon`. `HeroAsset(int id, String className, String name)`. `RankAsset(int tier, String name)`, tiers 0-11.
- Facts from real responses (`docs/PROJECT_CONTEXT.md`): win is `matchResult == playerTeam` (`playerMatchOutcome` is 0 for older matches); badge = `tier * 10 + subrank`; benchmark metric names seen live include `net_worth_per_min`, `player_damage_per_min`, `last_hits`, `denies`, `deaths`, `kda`, `permanent_buffs`, `first_permanent_buff_time_s` (29 metrics without buff metrics, 32 with; which three are buff-only is **not** verified). The existing fixture `fixtures/deadlock-api/player-stats-metrics.json` holds only `net_worth_per_min`, `player_damage_per_min`, `last_hits`. There is no benchmark for net worth at 10/20 min, deaths per 10 min or item timings (item timings use `avg_buy_time_s`). Snapshots exist at 180, 360, 540, 720, 900, 1200, ... s plus a final one, so 10 min (600 s) must be interpolated and 20 min (1200 s) is exact. Buff pickup data exists only for matches since build 6712 (2026-09-29).
- Rules from `AGENTS.md`: services use no JDA types; constructor injection, `final` fields, records, no Lombok. This task has no user-facing text.

## Files to create or modify
| File | Change |
|---|---|
| `src/main/java/sntiago05/rejuv/domain/MatchRank.java` | **Create.** Record (see Interfaces). |
| `src/main/java/sntiago05/rejuv/services/MetricsRequest.java` | **Create.** Record (see Interfaces). |
| `src/main/java/sntiago05/rejuv/services/MetricsService.java` | **Create.** |
| `src/test/java/sntiago05/rejuv/services/MetricsServiceTest.java` | **Create.** |
| `docs/task-reports/007-metrics-service.md` | **Create.** Task report (format of the Task 006 report). |

No `pom.xml`, `application.yaml`, `messages.properties`, cache, migration, client, DTO or command change is needed. If you think one is needed, stop and ask. Existing JSON fixtures may be **read** by the test; do not modify them and do not add new ones.

## Interfaces / signatures

### `MatchRank` (`sntiago05.rejuv.domain`, new)
```java
/** Rank used to pick benchmarks. tier 0..11, subrank 0 when unknown. */
public record MatchRank(int tier, int subrank) { }
```

### `MetricsRequest` (`sntiago05.rejuv.services`, new)
```java
/**
 * Everything MetricsService needs for one analysis. Collections are copied (immutable).
 * telemetry: null = degraded mode (history data only).
 * rank: null = no rank known (no benchmarks); benchmarks and itemStats must then be empty.
 * benchmarks / itemStats: empty when not fetched or not available.
 */
public record MetricsRequest(long accountId,
                             MatchHistoryEntry match,
                             MatchTelemetry telemetry,
                             MatchRank rank,
                             Map<String, MetricPercentiles> benchmarks,
                             Map<Long, ItemStat> itemStats,
                             List<ItemAsset> items,
                             List<HeroAsset> heroes,
                             List<RankAsset> ranks) { }
```
The compact constructor copies the collections (`Map.copyOf`, `List.copyOf`) and rejects a null `match` (`NullPointerException`). It does no other validation.

### `MetricsService` (`sntiago05.rejuv.services`, `@Service`, new, stateless, no dependencies)
```java
public static final String LANGUAGE = "en";
static final Instant BUFF_DATA_SINCE = Instant.parse("2026-09-29T00:00:00Z");

/** Newest entry by startTime (ties: first in list order), or empty for an empty history. */
public Optional<MatchHistoryEntry> latestMatch(List<MatchHistoryEntry> history);

/**
 * Rank whose benchmarks the match is compared with:
 * 1. match.rankedDisplayBadge() non-null and > 0 → MatchRank(badge / 10, badge % 10);
 * 2. else currentRank non-null and currentRank.badge() > 0 → MatchRank(currentRank.rank(), currentRank.subrank());
 * 3. else empty (unranked match and unranked or unknown player).
 * A tier outside 0..11 gives empty.
 */
public Optional<MatchRank> benchmarkRank(MatchHistoryEntry match, PlayerRank currentRank);

/**
 * Builds the AI input. Pure: no I/O, never calls the API.
 * @throws IllegalArgumentException if match.matchDurationS() <= 0, or telemetry is non-null
 *         and telemetry.matchId() != match.matchId()
 */
public AnalysisInput buildInput(MetricsRequest request);
```
Private helpers and their shape are your choice. Keep the formulas below in one place so Task 010 can tune them.

## Behavior

### General rules
- Duration: `durationMin = match.matchDurationS() / 60.0`. All per-minute values use it, in both modes (history duration, not telemetry duration).
- Rounding: every `double` written to `AnalysisInput` (values, percentiles, minutes) is rounded to 1 decimal, half up (`Math.round(x * 10) / 10.0` is fine for non-negative values).
- Effective telemetry: `request.telemetry()` if non-null **and** it contains the player (`telemetry.player(accountId)`). If telemetry is non-null but the player is missing, log one `WARN` (match id, account id) and continue in degraded mode. Telemetry-only metrics and items are omitted in degraded mode.
- Benchmark lookup: by exact API key in `request.benchmarks()`. Missing key → `p25`, `p50`, `p75` all `null` (the metric is still included). Never rename, guess or derive an API key at run time.
- `metrics` keeps the order of the table below: build it in a `LinkedHashMap` and wrap it with `Collections.unmodifiableMap` (not `Map.copyOf`, which loses order).

### `AnalysisInput` fields
- `hero`: the `HeroAsset.name` whose `id == match.heroId()`; else its `className`; else `"hero_" + heroId`.
- `rank`: `null` when `request.rank()` is null. Otherwise the `RankAsset.name` for `rank.tier()`, plus `" " + subrank` when `subrank > 0` (for example `Archon 3`); `null` when the tier is missing from the assets.
- `result`: `"win"` when `matchResult == playerTeam`, else `"loss"`.
- `durationMin`: rounded `durationMin`.
- `language`: `LANGUAGE`.

### Metrics (in this order)
| Key | Value | Benchmark API key | Mode |
|---|---|---|---|
| `souls_per_min` | `match.netWorth() / durationMin` | `net_worth_per_min` | always |
| `kills` | `playerKills` | `kills` | always |
| `deaths` | `playerDeaths` | `deaths` | always |
| `assists` | `playerAssists` | `assists` | always |
| `deaths_per_10_min` | `playerDeaths / durationMin * 10` | none (always null) | always |
| `last_hits` | `lastHits` | `last_hits` | always |
| `denies` | `denies` | `denies` | always |
| `player_damage_per_min` | `playerDamage` of the **last** snapshot / `durationMin` | `player_damage_per_min` | telemetry, at least one snapshot |
| `net_worth_at_10_min` | net worth interpolated at 600 s | none | telemetry, `matchDurationS >= 600` |
| `net_worth_at_20_min` | net worth interpolated at 1200 s | none | telemetry, `matchDurationS >= 1200` |
| `permanent_buffs` | number of pickups across buffs with `permanent == true` | `permanent_buffs` | telemetry, buff era |
| `first_permanent_buff_min` | earliest permanent pickup in seconds / 60 | `first_permanent_buff_time_s`, each percentile / 60 | telemetry, buff era, at least one permanent pickup |

- **Interpolation at time `t`**: if a snapshot has `timeS == t`, use its `netWorth`. Otherwise take the nearest snapshot before `t` and the nearest after `t` and interpolate linearly. If either is missing, omit the metric.
- **Buff era**: `Instant.ofEpochSecond(match.startTime())` is not before `BUFF_DATA_SINCE`. Before it, omit both buff metrics (an empty buff list means "no data", not zero). In the buff era with no permanent pickup, `permanent_buffs` is `0` and `first_permanent_buff_min` is omitted.

### Items (`items`, telemetry only; empty list in degraded mode)
1. Candidates: the player's purchases (already ascending by `boughtAtS`) whose `itemId` matches an `ItemAsset` with `type` equal to `upgrade`. Unknown ids and other types are skipped. Sold items still count.
2. Selected: the first 3 candidates, then the first candidate with `itemTier == 3` if it is not already among them. That gives at most 4 entries, in purchase order.
3. Each entry becomes `ItemTiming(name, boughtAtS / 60.0, avg)`: `name` is the asset `name`, else its `className`; `avg = itemStats.get(itemId).avgBuyTimeS() / 60.0`, or `null` when the id has no item stat. Values are rounded.
4. The returned list is immutable.

## Notes for Task 009 (informative, not part of this task)
Expected wiring: `latestMatch(history)` → `benchmarkRank(match, getPlayerRank)` → if a rank exists, fetch `getBenchmarks` and `getItemStats` sequentially for `(match.heroId(), rank.tier())` → `MatchMetadataSource.getMatch` (on `MatchMetadataNotFoundException` or `DeadlockApiUnavailableException`, pass `telemetry = null`) → assets → `buildInput`. The fallback summary for the key numbers can be built from `AnalysisInput.metrics()`. Do not implement any of this here.

## Out of scope
- Any API call, caching, persistence, `/analyze` command, embed or fallback text (Task 009).
- Changing `AnalysisInput`, `AnalysisClient`, the prompt, `DeadlockApiClient` (including sending `include_buff_metrics`), DTOs, `CacheConfig` or `messages.properties`.
- Choosing which metrics are leaks (the model does it, per `docs/mvp-spec.md`), multi-match trends, `kda` or other metrics not in the table, ability order, a raw protobuf fallback (Task 023).
- Changing `docs/mvp-spec.md` (see Decisions).

## Tests
JUnit 5, AssertJ. `MetricsServiceTest` uses the service directly (no Spring context). Build `MatchHistoryEntry`, `MatchTelemetry` and asset records in code for exact control. Assert rounded values exactly.

1. `latestMatchIsNewestByStartTime` (out-of-order list), `latestMatchEmptyForEmptyHistory`.
2. `benchmarkRankUsesMatchBadgeWhenRanked` (badge 73 → tier 7, subrank 3), `...FallsBackToCurrentRankForUnrankedMatch`, `...EmptyWhenUnrankedMatchAndUnrankedPlayer` (badge 0 and `null` current rank), `...EmptyWhenTierOutOfRange` (for example badge 125).
3. `resultIsWinWhenMatchResultEqualsPlayerTeam` (with `playerMatchOutcome` 0) and loss case.
4. `historyMetricsAndRoundingAreComputed`: `souls_per_min`, K/D/A, `deaths_per_10_min`, `last_hits`, `denies`, `durationMin`, with percentiles from the map and `deaths_per_10_min` without percentiles.
5. `missingBenchmarkKeyGivesNullPercentiles` (for example no `kills` key).
6. `metricsKeepTableOrder`: the key order of the map equals the table order (full telemetry, buff era).
7. `degradedModeWithoutTelemetryHasOnlyHistoryMetricsAndNoItems`.
8. `playerMissingFromTelemetryFallsBackToDegradedMode`.
9. `telemetryForAnotherMatchIsRejected` and `nonPositiveDurationIsRejected` (`IllegalArgumentException`).
10. `playerDamagePerMinUsesLastSnapshot`.
11. `netWorthAt10MinIsInterpolatedBetween540And720` (for example 540 s → 6000, 720 s → 9000 gives 7000.0) and `netWorthAt20MinUsesExactSnapshot`.
12. `netWorthAtTimeOmittedWhenMatchTooShortOrNoSnapshotAfter`.
13. `buffMetricsInBuffEra` (count across several permanent buffs, earliest pickup, percentiles divided by 60), `noPermanentPickupGivesZeroAndNoFirstBuff`, `buffMetricsOmittedBeforeBuffEra`, `nonPermanentBuffsAreIgnored`.
14. `itemsAreFirstThreeUpgradesPlusFirstTierThree` (abilities and unknown ids skipped, tier-3 appended when later), `tierThreeAmongFirstThreeIsNotDuplicated`, `itemWithoutStatHasNullAverage`, `soldItemsStillCount`.
15. `heroAndRankNamesResolveWithFallbacks`: hero name → className → `hero_<id>`; rank with and without subrank; `null` rank when `request.rank()` is null or the tier is missing.
16. `buildsInputFromRecordedFixtures`: deserialize the existing `match-history.json`, `player-stats-metrics.json`, `item-stats.json`, `assets-items.json` and `assets-heroes.json` with the same JSON mapper style the client tests use. Combine them with a hand-built telemetry for the first history entry and build the input. Assert structure only: hero resolved, the three fixture metrics carry percentiles, `language` is `en`, at most 4 items. Do not assert unverified real-world numbers.
17. `inputSerializesWithinBudget`: serialize a full input (all metrics, 4 items) with a JSON mapper and assert the JSON is under 6,000 characters, a rough stand-in for the 1,500-token target in `docs/mvp-spec.md`.

## Verification
0. **Optional live check, only with owner approval** (the owner decides; ask before any network call): one `DeadlockApiClient.getBenchmarks` call for a popular hero and a mid tier, listing the returned metric keys. Use the approach of the Task 008 report, because `run-rejuv` `driver.sh cmd/eval` fails on Windows. Record the key list in the report. Report every table key that is absent (for example `kills`, `assists`, buff keys). Do not change the table without asking. If the check is not approved, say so in the report.
1. `./mvnw clean verify` passes with Docker running (on Windows `.\mvnw.cmd clean verify`; see the `MAVEN_USER_HOME` note in `docs/PROJECT_CONTEXT.md` if the wrapper fails). Report the real test count against the baseline you measure on `origin/main` before your first change.
2. `git diff --check` and a review of the final diff: only the files in the table changed; no secret, token, real account id or real user data; `.env` never read or printed.

## Documentation
- Task report `docs/task-reports/007-metrics-service.md`: status, summary, files, commands run with real results, live-check outcome or "not run", deviations, known issues.
- `docs/PROJECT_CONTEXT.md`: **do not edit in this branch.** Task 013 runs in parallel; mark the context update as "pending integration" in the report (`AGENTS.md`, Parallel tasks). List the facts to add after both branches merge, including the Task 009 wiring contract above.
- No ADR. Do not edit `docs/mvp-spec.md`, `docs/architecture.md`, `docs/features.md`, other specs or `docs/roadmap.md`.

## Acceptance criteria
- [ ] `./mvnw clean verify` passes; test count and baseline difference are in the report
- [ ] `latestMatch`, `benchmarkRank` and `buildInput` have exactly the signatures above; `MetricsService` has no dependencies and performs no I/O
- [ ] Every metric in the table uses the stated formula, benchmark key and mode; missing benchmark keys give null percentiles; values are rounded to 1 decimal; map order equals table order
- [ ] Degraded mode (no telemetry, or player missing from telemetry) produces history metrics only and no items, without throwing
- [ ] Net worth at 10 min is interpolated and at 20 min exact; both are omitted when the match is too short or a bracketing snapshot is missing
- [ ] Buff metrics only in the buff era; no permanent pickup gives `permanent_buffs` 0
- [ ] Items: first 3 upgrades plus the first tier-3 upgrade if later, with average buy time in minutes or null
- [ ] Win = `matchResult == playerTeam`; rank tier from the match badge, then the current rank, else none
- [ ] All tests in "Tests" exist and pass; `MetricsService` uses no JDA types
- [ ] Only the files in the table changed; no new dependency, fixture change, migration or secret
- [ ] Task report created with `docs/PROJECT_CONTEXT.md` update marked pending integration; final diff has no unrelated files

## Decisions and open questions
Decisions taken in this spec:
1. **Pure service, data passed in.** `MetricsService` never calls the API. Task 009 fetches sequentially (`AGENTS.md`: no parallel uncached analytics requests) and passes the data in. That makes every rule testable with records.
2. **Degraded mode lives here.** A null telemetry is a normal input, so Task 009 only has to catch the metadata exceptions.
3. **Benchmark tier = match badge first.** The match's own badge reflects the lobby; the current rank is the fallback for unranked matches. With neither, there are no benchmarks and `rank` is `null` (unranked players still get an analysis, without percentiles).
4. **`permanent_buffs` (count per match) instead of "buffs per minute"** from `docs/mvp-spec.md`, because the count has an API benchmark and a rate does not. The owner should update `docs/mvp-spec.md` if accepted.
5. **Shop items only (`type == upgrade`).** Ability and weapon entries are not purchases a player can time.
6. **History duration and net worth** are used for `souls_per_min` and all per-minute metrics, so both modes give the same values for the shared metrics.

Open for the owner (not blocking):
1. Buff benchmarks are likely missing because the client does not send `include_buff_metrics`. If the live check confirms it, a later task can add the flag to `getBenchmarks`.
2. Unranked players get no percentiles. Task 010 may decide to benchmark them against a default tier.
