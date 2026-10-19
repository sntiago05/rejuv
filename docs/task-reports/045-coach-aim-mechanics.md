# Task 045: Coach layer `aim_mechanics` (shot accuracy for gun-dominant heroes) and prompt lines

## Status
Implemented on `feature/045-coach-aim-mechanics`. `.\mvnw.cmd verify` passes (1060 tests run, 0 failures, 14 skipped). Not pushed, not merged.

## Summary
Added coach insight layer under key `aim_mechanics`, calculating shot accuracy and crit rate for gun-dominant heroes using the final stat snapshot already downloaded by `GET /v1/matches/{id}/metadata` (zero new HTTP calls):
1. **DTO & Domain Telemetry Mapping**:
   - `MatchMetadataResponse.Stat` maps `@JsonProperty("hero_bullets_hit") Long heroBulletsHit` and `@JsonProperty("hero_bullets_hit_crit") Long heroBulletsHitCrit` (nullable).
   - `SnapshotDetail` extended with `Long heroBulletsHit` and `Long heroBulletsHitCrit` (nullable = absent). 13-argument constructor preserved as an overload delegating with `null, null`.
   - `JsonMatchMetadataSource` maps both fields without `zeroIfNull` (absent stays `null`).
2. **Coach Provider (`AimMechanicsCoachProvider`)**:
   - `@Component` implementing `CoachInsightProvider` under key `"aim_mechanics"`.
   - Evaluates only gun-dominant heroes (`GUN_HEROES = Set.of(13, 25, 58, 80, 84)`: Haze, Warden, Vyper, Silver, Rat King).
   - Degrades to `Optional.empty()` if request/telemetry/player is null, hero not in `GUN_HEROES`, no snapshots, final `detail == null` (raw fallback), or `shots < MIN_SHOTS_NOTABLE` (500).
   - Computes:
     - `accuracy_pct`: `(shots_hit / shots) * 100`, rounded to 1 decimal place.
     - `shots`: total `shots_hit + shots_missed` of the final snapshot (greatest `timeS`).
     - `crit_pct`: `(hero_bullets_hit_crit / hero_bullets_hit) * 100`, rounded to 1 decimal place; omitted unless `hero_bullets_hit >= MIN_BULLETS_FOR_CRIT` (250) and both fields non-null.
   - Significance evaluation:
     - `major`: `accuracy_pct < MAJOR_ACCURACY_PCT` (32.0) and `shots >= MIN_SHOTS_MAJOR` (1000).
     - `notable`: not major and `accuracy_pct < NOTABLE_ACCURACY_PCT` (48.0).
     - `none`: otherwise.
     - Crit rate never changes significance.
   - Serializes nested `AimMechanicsInsight` with `@JsonPropertyOrder({"significance", "accuracy_pct", "shots", "crit_pct"})` and `@JsonInclude(NON_NULL)`.
3. **Analysis Result & Prompt**:
   - Added `"aim_mechanics"` to `AnalysisResult.FOCUS_AREAS`.
   - Updated `prompts/analysis-system.txt`:
     - Added `aim_mechanics` to coach key list in section 1.
     - Added meaning line after `objectives`: `aim_mechanics: significance, accuracy_pct (shots hit over shots fired), shots, crit_pct (share of hero bullets that were crits, only present with enough bullets); only for gun-heroes, crit_pct is context and never the leak by itself.`
     - Updated tie-break order: `deaths, phases, objectives, aim_mechanics, build, matchup, recent_form`.
     - Added `"aim_mechanics"` to the `focus_area` list in section 6.
     - Final prompt length is 5,107 characters (well under the 5,500 limit).

## Important Files
- Created:
  - `src/main/java/sntiago05/rejuv/services/AimMechanicsCoachProvider.java`: Coach insight provider for key `"aim_mechanics"`.
  - `src/test/java/sntiago05/rejuv/services/AimMechanicsCoachProviderTest.java`: 21 unit tests covering happy path, gun heroes set, non-gun rejection, shot count boundaries (499 vs 500, 999 vs 1000), accuracy boundaries (47.9 vs 48.0, 31.9 vs 32.0), crit thresholds (249 vs 250 bullets, null fields), final snapshot selection, crit independence on significance, determinism, and payload size limit.
  - `src/test/resources/fixtures/deadlock-api/match-metadata-aim.json`: Test fixture with 2 players and snapshot variations (crit fields present vs absent).
  - `docs/task-reports/045-coach-aim-mechanics.md`: This task report.
- Modified:
  - `src/main/java/sntiago05/rejuv/clients/dto/MatchMetadataResponse.java`: Added `heroBulletsHit` and `heroBulletsHitCrit` to `Stat`.
  - `src/main/java/sntiago05/rejuv/domain/SnapshotDetail.java`: Added `heroBulletsHit` and `heroBulletsHitCrit` components with delegating 13-argument constructor.
  - `src/main/java/sntiago05/rejuv/clients/JsonMatchMetadataSource.java`: Mapped both fields to `SnapshotDetail` without `zeroIfNull`.
  - `src/main/java/sntiago05/rejuv/domain/AnalysisResult.java`: Added `"aim_mechanics"` to `FOCUS_AREAS`.
  - `src/main/resources/prompts/analysis-system.txt`: Added `aim_mechanics` key, meaning line, new tie-break order, and focus_area enum value.
  - `src/test/java/sntiago05/rejuv/clients/AnalysisPromptTest.java`: Raised cap to 5,500, updated tie-break assertion, verified `aim_mechanics`, `accuracy_pct`, `crit_pct`, and focus_area line.
  - `src/test/java/sntiago05/rejuv/clients/JsonMatchMetadataSourceTest.java`: Added fixture mapping test verifying present and absent crit fields.
  - `src/test/java/sntiago05/rejuv/domain/AnalysisResultTest.java`: Added test asserting `aim_mechanics` is accepted as `focus_area` with whitespace/case normalization.

## Decisions & Calibration Facts
- **Silver Caveat**: Silver (hero ID 80) had only N=4 matches in the calibration dataset (verification marked it inconclusive); the owner explicitly chose to include it in `GUN_HEROES`. Documented in code comment next to the set and in this report.
- **Mitigation Deferred**: Per owner decision, `mitigation` layer is deferred due to lack of calibration (only 10 matches available).
- **Major Uses Accuracy Only**: Calibration data showed the combination rule (`acc < 28`, `crit < 5`) fired 0.0% of the time, whereas `major` using accuracy only (`< 32.0%` with >= 1,000 shots) cleanly captures anomalous aim leaks.
- **Unverified Shot-Counting Semantics**: It remains open and unverified upstream whether pellet/burst weapons count one shot or several, and whether post-death hits count; neither is claimed in code or prompt.
- **Pending Live `/analyze` Check**: Until a live `/analyze` is verified against Anthropic's API, it is unverified whether the model properly handles the expanded enum or accepts the key semantics under real match conditions.
- **Rounding**: Reused standard `BigDecimal.valueOf(value).setScale(1, RoundingMode.HALF_UP).doubleValue()` from existing coach providers to ensure consistency and avoid floating-point rounding quirks on boundary checks.

## Verification
- Baseline `.\mvnw.cmd test`: BUILD SUCCESS, 1036 tests run, 0 failures, 14 skipped (53.360 s).
- Targeted test run: `.\mvnw.cmd test "-Dtest=AimMechanicsCoachProviderTest,JsonMatchMetadataSourceTest,AnalysisPromptTest,AnalysisResultTest"`: BUILD SUCCESS, 81 tests run, 0 failures (12.972 s).
- Unchanged test run: `.\mvnw.cmd test -Dtest=AnalysisClientTest`: BUILD SUCCESS, 18 tests run, 0 failures (6.095 s).
- Full `.\mvnw.cmd verify` (Docker Desktop running): BUILD SUCCESS, 1060 tests run (+24 new tests), 0 failures, 14 skipped (39.786 s).
- `git diff --check`: Clean (no whitespace issues).
- Prompt length check: 5,107 characters (cap: 5,500).

## Deviations
None. Implemented strictly according to specification.

## Merge Risks
None. Provider is picked up automatically via Spring component scanning implementing `CoachInsightProvider`. Existing code and fallback parsers continue to operate unchanged.

## Pending Integration
`docs/PROJECT_CONTEXT.md` was not edited directly per parallel worktree policy.
Facts to integrate upon merge:
- New coach layer `aim_mechanics` (`AimMechanicsCoachProvider`, key `"aim_mechanics"`).
- Target heroes: `GUN_HEROES = Set.of(13, 25, 58, 80, 84)` (Haze, Warden, Vyper, Silver [inconclusive calibration, owner included], Rat King).
- Thresholds: `MIN_SHOTS_NOTABLE = 500`, `MIN_SHOTS_MAJOR = 1000`, `NOTABLE_ACCURACY_PCT = 48.0`, `MAJOR_ACCURACY_PCT = 32.0`, `MIN_BULLETS_FOR_CRIT = 250`.
- Significance: `major` if `accuracy_pct < 32.0` and `shots >= 1000`; `notable` if not major and `accuracy_pct < 48.0`; `none` otherwise. Crit rate is informational and does not affect significance.
- Telemetry model updates: `SnapshotDetail.heroBulletsHit` and `heroBulletsHitCrit` (`Long`, nullable), mapped from `Stat` by `JsonMatchMetadataSource` (raw fallback leaves them `null`).
- Prompt & schema updates: `prompts/analysis-system.txt` extended with `aim_mechanics` key and field description; tie-break order updated to `deaths, phases, objectives, aim_mechanics, build, matchup, recent_form`; `AnalysisResult.FOCUS_AREAS` includes `"aim_mechanics"`.
