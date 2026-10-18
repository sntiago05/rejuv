# Task 032: Coach layer "deaths" (when, how often and to whom the player dies)

## Status
Implemented on `feature/032-deaths-coach`. `.\mvnw.cmd clean verify` passes (803 tests run, 0 failures, 2 skipped; +13 tests over baseline 790). Not pushed, not merged.

## Summary
Task 032 implements the "deaths" coach provider (`DeathsCoachProvider`) under the `CoachInsightProvider` extension point introduced in Task 029:
1. **Factual Death Profile**: Computes death metrics from `PlayerTelemetry.deaths()` and `StatSnapshot.detail()`:
   - Total deaths and phase breakdown: `laning` (< 720 s), `mid` (720..1199 s), `late` (>= 1200 s).
   - Time dead metrics: `seconds_dead` (sum of `respawnS`), `percent_of_match_dead` (`(seconds_dead / durationS) * 100`, rounded to one decimal place half-up), and `longest_respawn_s`.
   - Economic cost: `souls_lost_on_death` from `lastSnapshot.detail().goldDeathLoss()`.
2. **Death Clustering**: Detects `back_to_back` rapid deaths occurring within `CLUSTER_WINDOW_S` (60 s) of the preceding death.
3. **Killers & Repeat Killer**: Resolves killer identities through `MatchTelemetry.playerBySlot(killerSlot)` and groups by killer hero name using `request.heroes()` (fallback `hero_<id>`). Resolves ties by higher count, then lower hero id. Flags `repeat_killer = true` when top killer has at least 3 kills and represents >= 40% of all resolved kills.
4. **Deaths While Behind**: Identifies common snapshot timestamps existing across all 12 players in standard matches. Precomputes team net worth lead (`targetTeamNw - enemyTeamNw`) at each common time. For each death, evaluates against the latest common snapshot `T <= gameTimeS`. Skips deaths before the first common timestamp; omits `deaths_while_behind` and `deaths_counted` if no deaths are evaluated.
5. **Rank Performance Curve Comparison (Best Effort)**: When `rankTier` is non-null, fetches `DeadlockApiClient.getPerformanceCurve(heroId, rankTier)` wrapped in a `RuntimeException` try-catch that logs a single WARN with the exception class on failure. Computes `deaths_z = round((deathsAtThatTime - deathsAvg) / deathsStd)` against the matching timestamp with the largest `gameTime <= durationS` and `deathsStd > 0`.
6. **Machine-Readable Significance**:
   - `major` when `deaths_z >= 2.0`, `percent_of_match_dead >= 15.0`, `repeat_killer == true`, or `deaths_while_behind >= 3`.
   - `notable` when `deaths_z >= 1.0`, `percent_of_match_dead >= 10.0`, `back_to_back >= 2`, or `total >= 8`.
   - `none` otherwise.
7. **Clean Degradation**: Returns `Optional.empty()` for null telemetry, null player, `durationS <= 0`, missing snapshot detail (raw protobuf fallback), or matches with zero deaths.

## Important Files
- **Created**:
  - `src/main/java/sntiago05/rejuv/services/DeathsCoachProvider.java`
  - `src/test/java/sntiago05/rejuv/services/DeathsCoachProviderTest.java`
  - `docs/task-reports/032-deaths-coach.md`

## Decisions
- **Tie-Breaking Killers**: Tracked by `heroId` during aggregation so that count ties are deterministically resolved by smaller `heroId` as specified.
- **Common Snapshot Times for Deaths While Behind**: Evaluated only when all 12 players are present in telemetry. Uses `floorEntry` on precomputed common timestamp leads to efficiently evaluate deaths without repeated iterations over players.
- **Floating-point Tolerance on Share**: Used `(double) count / total >= 0.40 - 1e-9` to avoid IEEE 754 precision issues at the 40% boundary.
- **Failure Isolation**: Checked `rankTier != null` before making the client call; only the `getPerformanceCurve` invocation is wrapped in the try-catch for `RuntimeException`, continuing gracefully without `deaths_z` on API error.

## Verification
- Baseline `.\mvnw.cmd clean verify`: 790 tests run, 0 failures, 2 skipped.
- Final `.\mvnw.cmd clean verify`: BUILD SUCCESS, 803 tests run (+13 tests), 0 failures, 2 skipped.
- `DeathsCoachProviderTest` (13 tests):
  - `keyMatchesContract`: Verifies `key()` returns `"deaths"`.
  - `happyPathRealisticMatchMatchesExpectedJsonAndSizeLimit`: Verifies realistic 2450s match, exact JSON structure, and payload size <= 1200 characters (~280 characters actual).
  - `missingDataReturnsEmpty`: Verifies empty optional for null request, null telemetry, null player, and non-positive duration.
  - `rawFallbackTelemetryWithAllNullDetailReturnsEmpty`: Verifies degraded raw source with all null details returns empty.
  - `zeroDeathsReturnsEmpty`: Verifies empty optional when player had 0 deaths.
  - `phaseBoundariesAtExactSeconds`: Verifies boundary classification at 719s (laning), 720s (mid), 1199s (mid), 1200s (late).
  - `backToBackClustersWithGapsAndChainOfThree`: Verifies gaps of 59s, 60s, 61s, and a chain of three rapid deaths.
  - `killersHandlingUnresolvedSlotNullSlotAndTieBreaking`: Verifies unresolved slots, null slots, and tie-breaking by lower hero id.
  - `repeatKillerThresholds`: Verifies 2 vs 3 kills, 39% vs 40% share, and small-match fraction thresholds.
  - `deathsWhileBehindEvaluations`: Verifies team ahead, team behind, death before first common timestamp skipped, and snapshot missing for one player excluded from common times.
  - `rankCurveBestEffortScenarios`: Verifies curve present, curve empty, `deathsStd == 0`, tier null (no API call), and `DeadlockApiUnavailableException` failure isolation.
  - `significanceThresholdBoundaries`: Verifies boundary conditions just below, at, and just above for all 8 named threshold constants.
  - `determinismAndNoMutation`: Verifies pure deterministic output and zero mutation on input request.

## Deviations
None.

## Known Issues
None.

## Merge Risks
- `docs/PROJECT_CONTEXT.md` update pending integration:
  - Added `DeathsCoachProvider` (`@Component`, key `"deaths"`).
  - Produces `DeathsPayload` section under `"deaths"` in `AnalysisInput.coach` map.
