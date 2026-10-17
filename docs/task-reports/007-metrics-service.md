# Task 007: `MetricsService` (match metrics and benchmarks)

## Status

Implemented and verified locally. `./mvnw clean verify` passes with 214 tests (183 baseline + 31 new). Not reviewed, not pushed, not merged. Optional live check not run (owner declined).

## Summary

- Added record `domain/MatchRank(tier, subrank)`: the rank whose benchmarks a match is compared with.
- Added record `services/MetricsRequest`: all pre-fetched data for one analysis (account id, history entry, optional telemetry, rank, benchmarks, item stats, item/hero/rank assets). Compact constructor rejects a null `match` and copies every collection (`Map.copyOf`, `List.copyOf`).
- Added `services/MetricsService` (`@Service`, stateless, no dependencies, no I/O):
  - `latestMatch(history)`: newest by `startTime`, ties keep the first in list order.
  - `benchmarkRank(match, currentRank)`: match badge (`badge / 10`, `badge % 10`) when non-null and > 0, else the current rank when its badge > 0, else empty; a tier outside 0..11 gives empty.
  - `buildInput(request)`: validates duration > 0 and telemetry match id; uses the player's telemetry when present, otherwise logs one `WARN` (match id, account id) and runs in degraded mode. Builds the 12 metrics of the spec table in table order (`LinkedHashMap` wrapped with `Collections.unmodifiableMap`), with exact benchmark keys (missing key → null percentiles), net worth at 600 s / 1200 s (exact snapshot or linear interpolation, omitted without a bracketing snapshot or when the match is shorter), buff metrics only from `BUFF_DATA_SINCE` (2026-09-29), and items (first 3 `upgrade` purchases plus the first tier-3 upgrade when later, average buy time in minutes or null). Hero, rank and result names follow the spec fallbacks. Every double is rounded to 1 decimal (`Math.round(x * 10) / 10.0`).
  - All metric keys, benchmark keys and thresholds are package-private constants at the top of the class, so Task 010 can tune them in one place.

## Important Files

- `src/main/java/sntiago05/rejuv/domain/MatchRank.java`
- `src/main/java/sntiago05/rejuv/services/MetricsRequest.java`
- `src/main/java/sntiago05/rejuv/services/MetricsService.java`
- `src/test/java/sntiago05/rejuv/services/MetricsServiceTest.java` (31 tests)

## Decisions

- `first_permanent_buff_min` percentiles are the `first_permanent_buff_time_s` percentiles divided by 60 before rounding (one shared `compare(value, benchmark, divisor)` helper).
- "Tier-3 not already among them" is decided by the candidate's position (index < 3), not by record equality, so a re-bought identical purchase cannot confuse it.
- Hero name: an asset with both `name` and `className` null falls through to `hero_<id>`. Rank name: an asset with a null `name` is treated as missing (rank `null`).
- The telemetry duration is never used; per-minute values use `match.matchDurationS()` in both modes, as the spec decides.
- The `WARN` for a player missing from telemetry logs the Deadlock account id (no Discord id, no token).

## Verification

- Baseline before changes (Git Bash, Docker Desktop started for Testcontainers): `./mvnw -q clean verify`: exit 0, **183 tests**, 0 failures (135 + 31 from Task 006 + 17 from Task 008). No `MAVEN_USER_HOME` workaround was needed.
- Narrow run: `./mvnw -q test -Dtest=MetricsServiceTest`: exit 0, 31 tests, 0 failures, 0 errors, 0 skipped.
- Final: `./mvnw -q clean verify`: exit 0, **214 tests**, 0 failures, 0 errors, 0 skipped. Difference: +31 (30 spec tests + 1 extra, see Deviations). `MessagesIntegrationTest` loads the full Spring context, so the new `@Service` bean wires up.
- `git diff --check origin/main...HEAD`: clean. `git diff --name-only origin/main...HEAD`: only the four table files plus this report. No token, key or real id in the diff (fake ids `1000001`, `1000002`, `900000003`, item ids 101-601; the fixture test reads existing fixtures only). `.env` was never read.
- Live check (Verification step 0, one `getBenchmarks` call): **not run**, the owner declined. The metric keys `kills`, `assists`, `denies`, `deaths`, `permanent_buffs`, `first_permanent_buff_time_s` are therefore not confirmed against a live `include_buff_metrics=false` response.
- Manual check: no runtime behavior (no command, no I/O); covered by unit tests.

## Acceptance criteria

| Criterion | Evidence |
|---|---|
| `clean verify` passes, count and baseline in report | 214 tests vs 183 baseline, see Verification |
| Exact signatures; no dependencies, no I/O | `MetricsService` has no constructor arguments and no client/JDA imports; tests use `new MetricsService()` |
| Formulas, benchmark keys, modes, null percentiles, rounding, order | `historyMetricsAndRoundingAreComputed`, `missingBenchmarkKeyGivesNullPercentiles`, `metricsKeepTableOrder`, `playerDamagePerMinUsesLastSnapshot`, `buffMetricsInBuffEra` |
| Degraded mode without throwing | `degradedModeWithoutTelemetryHasOnlyHistoryMetricsAndNoItems`, `playerMissingFromTelemetryFallsBackToDegradedMode` |
| Net worth at 10 min interpolated, 20 min exact, omissions | `netWorthAt10MinIsInterpolatedBetween540And720`, `netWorthAt20MinUsesExactSnapshot`, `netWorthAtTimeOmittedWhenMatchTooShortOrNoSnapshotAfter` |
| Buff metrics only in the buff era; 0 without pickups | `buffMetricsOmittedBeforeBuffEra`, `noPermanentPickupGivesZeroAndNoFirstBuff` (start exactly at `BUFF_DATA_SINCE`), `nonPermanentBuffsAreIgnored` |
| Items: 3 upgrades + later tier 3, avg in minutes or null | `itemsAreFirstThreeUpgradesPlusFirstTierThree`, `tierThreeAmongFirstThreeIsNotDuplicated`, `itemWithoutStatHasNullAverage`, `soldItemsStillCount` |
| Win rule; rank from badge, then current rank, else none | `resultIsWin...`, `resultIsLoss...` (`playerMatchOutcome` 0), the four `benchmarkRank*` tests |
| All listed tests exist and pass; no JDA types | 31/31 pass; no `net.dv8tion` import in the new files |
| Only table files; no dependency, fixture, migration or secret | `git diff --name-only origin/main...HEAD` |
| Report created, context pending integration | This file, section below |

## Deviations

- **One extra test** `itemNameFallsBackToClassName` covers the item `name` → `className` fallback of the Items rule, which no listed test named. The loss case of test 3 is named `resultIsLossWhenMatchResultDiffersFromPlayerTeam` (the spec gives no name).
- Otherwise none.

## Known Issues

- Benchmark keys `kills`, `assists`, `denies`, `deaths` and the buff keys are unverified for the request without `include_buff_metrics`; any absent key yields null percentiles for that metric (by design). The buff benchmarks are probably absent until a later task sends `include_buff_metrics=true` (open question 1 of the spec).
- Unranked players (no badge, current badge 0) get `rank = null` and no percentiles (open question 2 of the spec, Task 010).
- `permanent_buffs` replaces "buffs per minute" from `docs/mvp-spec.md` (spec decision 4); the owner should update `docs/mvp-spec.md` if accepted. Not edited here.
- The fixture `match-history.json` first entry predates the buff era, so the fixture test exercises the non-buff path only (buff paths are covered by hand-built records).

## Merge Risks

- Task 013 runs in parallel; this branch touches none of its files (`DeadlockApiClient`, `CacheConfig`, `messages.properties`, `HelpCommand*`, `MessagesIntegrationTest`, `DeadlockApiClientCachingTest`). No conflict expected; the combined test count must be re-measured after both merge.

## PROJECT_CONTEXT update pending integration

Task 013 is active in another worktree, so `docs/PROJECT_CONTEXT.md` was not edited. After 007 and 013 merge, add:
- Task 007 done: pure `services/MetricsService` (`latestMatch`, `benchmarkRank`, `buildInput` → `AnalysisInput`), records `services/MetricsRequest` and `domain/MatchRank`; degraded mode without telemetry; report `docs/task-reports/007-metrics-service.md`; 214 tests on this branch.
- Constraint: metric and benchmark keys are constants in `MetricsService`; never rename or derive an API key at run time. Buff metrics only for matches since 2026-09-29.
- Task 009 wiring contract: `latestMatch(history)` → `benchmarkRank(match, getPlayerRank)` → if a rank exists, fetch `getBenchmarks` and `getItemStats` sequentially for `(match.heroId(), rank.tier())` → `MatchMetadataSource.getMatch` (on `MatchMetadataNotFoundException` or `DeadlockApiUnavailableException`, pass `telemetry = null`) → assets → `buildInput`. The fallback key numbers can come from `AnalysisInput.metrics()`.
- Known limitations: live benchmark keys not verified; unranked players get no percentiles; `docs/mvp-spec.md` still says "buffs per minute".
- Move Task 007 out of "In progress"; Task 009 becomes eligible (005, 007, 008 merged).
