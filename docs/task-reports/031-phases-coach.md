# Task 031: Coach layer "phases" (net worth, damage and farm against the rank curve)

## Status
Implemented on `feature/031-phases-coach`. `.\mvnw.cmd clean verify` passes (803 tests, 0 failures, 2 skipped). Not pushed, not merged.

## Summary
Task 031 introduces `PhasesCoachProvider`, the coaching layer evaluating player economy, damage, and farm progression relative to the rank/hero performance curve at fixed game checkpoints (6, 12, 20, and 30 minutes), as well as evaluating phase transitions (`laning`, `mid`, `late`).

1. **Provider Implementation**:
   - Implements `CoachInsightProvider` under key `"phases"` as a `@Component` in package `sntiago05.rejuv.services`.
   - Calls cached `DeadlockApiClient.getPerformanceCurve(heroId, rankTier)` sequentially and verifies that only this call is made.
   - Evaluates checkpoints at 360, 720, 1200, and 1800 seconds when the match duration covers them, matching player snapshots exist, and the curve point has `netWorthStd > 0`.
   - Calculates per-checkpoint z-scores (`net_worth_z`, `damage_z`, and `farm_z`) rounded to 1 decimal place.
   - Calculates teammate net worth comparison (`vs_teammates_pct`), omitting when no teammate has a snapshot at that timestamp or average net worth is <= 0.
   - Safely omits `farm_z` when `SnapshotDetail` is null (e.g. raw fallback metadata) or `creepKillsStd <= 0`.
   - Safely omits `damage_z` when `playerDamageStd <= 0`.
   - Builds phase progression for `laning` (360 -> 720s), `mid` (720 -> 1200s), and `late` (1200 -> 1800s) when both endpoint checkpoints are present.
   - Identifies `strongest_phase` (highest change >= 1.0) and `weakest_phase` (lowest change <= -1.0), resolving ties in favor of earlier phases.
   - Computes significance: `major` when any checkpoint `|net_worth_z| >= 2.0` or any phase `|change| >= 2.0`; `notable` when any `|net_worth_z| >= 1.0` or any `|change| >= 1.0`; else `none`.
2. **Payload Serialization & Strict Rules**:
   - Jackson `@JsonPropertyOrder` and `@JsonInclude(NON_NULL)` ensure clean, snake_case JSON output strictly under 1,200 characters (~500 chars for a full 40-minute match).
   - No AI calls, no database access, no JDA types, no unhandled exceptions on missing/degraded data.
   - Single-pass / hash-indexed lookups over snapshots and telemetry players.

## Important Files
- **Created**:
  - `src/main/java/sntiago05/rejuv/services/PhasesCoachProvider.java`
  - `src/test/java/sntiago05/rejuv/services/PhasesCoachProviderTest.java`
  - `docs/task-reports/031-phases-coach.md`
- **Modified**:
  - None.

## Decisions
- **Checkpoints Usability**: Strictly requires match duration >= checkpoint seconds, player snapshot at that exact timestamp, curve point at that exact timestamp, and `netWorthStd > 0`. If fewer than 2 checkpoints can be built, returns `Optional.empty()`.
- **Phase Definitions & Ties**: Only three canonical phases (`laning`, `mid`, `late`) are evaluated. If a phase checkpoint is missing, non-adjacent intervals are not bridged. Ties in minimum or maximum change resolve to the earlier phase first, preserving chronological progression.
- **Teammate Comparison**: Filters out the target player, ignores enemies, and only averages teammates who have recorded snapshots at that exact checkpoint time.
- **Degraded Telemetry Support**: When `StatSnapshot.detail` is null (as from the raw fallback metadata parser), `farm_z` is omitted while `net_worth_z` and `damage_z` remain fully functional.

## Verification
- Baseline `.\mvnw.cmd verify`: BUILD SUCCESS, 790 tests run, 0 failures, 2 skipped (1:01 min).
- Final `.\mvnw.cmd clean verify`: BUILD SUCCESS, 803 tests run (+13 tests), 0 failures, 2 skipped (1:26 min).
- Targeted test executions:
  - `.\mvnw.cmd test -Dtest=PhasesCoachProviderTest`: 13 tests passed, 0 failures (covering 40-min full match, 15-min match, missing data, detail null, teammate comparison variants 0/1/5, API client mock verification, threshold boundaries for significance and phase swing, ties, determinism, and payload size).
  - `.\mvnw.cmd test "-Dtest=CoachContextServiceTest,RejuvApplicationTests"`: passed.
- Payload size verification: Asserted payload length <= 1200 characters with Jackson `JsonMapper`.
- `git diff --check`: clean (exit code 0).

## Deviations
None.

## Known Issues
None.

## Merge Risks
None. No existing source files modified; `PhasesCoachProvider` is automatically discovered by Spring component scanning.

PROJECT_CONTEXT update pending integration (parallel task branches `017`, `018`, `020`, `021`, `029`, `030`, `032`-`036`, `038` are active). Facts to add upon integration:
- `PhasesCoachProvider` (`key: "phases"`) provides economy and damage progression vs the rank curve at minutes 6, 12, 20, and 30, along with phase transitions and swing evaluation.
