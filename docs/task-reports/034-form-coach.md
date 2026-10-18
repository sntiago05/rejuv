# Task 034: Coach layer "recent_form" (habit or one-off, tilt and fatigue)

## Status
Implemented on `feature/034-form-coach`. `.\mvnw.cmd clean verify` passes (820 tests, 0 failures, 2 skipped). Not pushed, not merged.

## Summary
Task 034 implements the `"recent_form"` coaching provider (`FormCoachProvider`) enabling the AI coach to differentiate between an isolated bad match and habitual play, tilt, or fatigue:
1. **Extension Point Implementation**: Registered `FormCoachProvider` as a `@Component` implementing `CoachInsightProvider` in package `sntiago05.rejuv.services` with `KEY = "recent_form"`.
2. **Reference Sample**: Filters `request.history()` for matches matching the analyzed `heroId`, strictly preceding `match.startTime()`, lasting >= 600 s (`MIN_DURATION_S`), and excluding the analyzed match ID. Sorted newest first by `startTime` (with higher `matchId` breaking ties) and capped at 20 games (`SAMPLE_MAX`). Returns `Optional.empty()` if fewer than 5 qualifying matches (`SAMPLE_MIN`) are found.
3. **Sample Baseline Statistics**: Computes sample `win_rate_pct`, population averages for `avg_souls_per_min`, `avg_deaths_per_10_min`, and `avg_kda`, along with population standard deviations.
4. **Match Deviations (Z-scores)**: Calculates `souls_per_min_z` and `deaths_per_10_min_z` rounded to 1 decimal place. Omits z-scores if `std <= 0` or if the analyzed match lasted < 600 s.
5. **Short-Term Trend**: When the sample has >= 10 games (`TREND_MIN_GAMES`), computes `recent_vs_previous` comparing the newest 5 against the prior 5 (`win_rate_change_pct` and `deaths_per_10_min_change`). Omitted when sample size < 10.
6. **Fatigue & Tilt Context**: Across all heroes, computes consecutive losses immediately prior to the match (`losses_in_a_row_before`) and matches played within the last 24 hours (`games_last_24h` within `[match.startTime() - 86400, match.startTime())`).
7. **Significance Evaluation**: Computes machine-readable significance (`"major"`, `"notable"`, or `"none"`) based on documented conservative constants.
8. **Pure & Telemetry-Free**: No external API calls, database access, or telemetry requirements. Operates reliably across full, degraded, and raw metadata modes.

## Important Files
- **Created**:
  - `src/main/java/sntiago05/rejuv/services/FormCoachProvider.java`
  - `src/test/java/sntiago05/rejuv/services/FormCoachProviderTest.java`
  - `docs/task-reports/034-form-coach.md`
- **Modified**:
  - None.

## Decisions
- **Population Standard Deviation & Z-scores**: Computed using double-precision population variance ($\frac{1}{N}\sum(x_i - \mu)^2$) before rounding to one decimal place half up using `BigDecimal.setScale(1, RoundingMode.HALF_UP)`. Suppressed negative zero (`-0.0`) in output.
- **Zero Client / Telemetry Requirement**: Utilizes only `request.match()` and `request.history()`, guaranteeing that coaching insight generation succeeds even in degraded or raw fallback operational modes.
- **Strict Half-Open 24h Window**: `games_last_24h` evaluates `[match.startTime() - 86400, match.startTime())`, ensuring games exactly 24 hours prior are included and future or concurrent matches are excluded.

## Verification
- Baseline `.\mvnw.cmd clean verify`: 790 tests run, 0 failures, 2 skipped.
- Final `.\mvnw.cmd clean verify`: BUILD SUCCESS, 820 tests run (+30 tests), 0 failures, 2 skipped.
- `git diff --check`: Clean, no whitespace or formatting errors.
- Unit tests added in `FormCoachProviderTest` (30 tests):
  - Contract check: `key()` matches `"recent_form"` and `CoachInsight.key()`.
  - Hand-computed statistics: exact averages, population standard deviation, and z-scores for fixed 5-game sample.
  - Zero standard deviation (`std == 0`) omits z-scores.
  - Short analyzed match (< 600 s) omits z-scores while returning payload.
  - Sample filtering: ignores different heroes, later matches, same start times, analyzed match id, games < 600 s, caps at 20.
  - Sample min boundary: 4 games returns empty, 5 returns payload.
  - Trend window boundary: 9 games omits trend, 10 games calculates `recent_vs_previous`.
  - Losing streak context: 0, 3 losses, streak interrupted by win, across all heroes, excludes analyzed match.
  - 24-hour fatigue boundary: half-open interval `[matchStart - 86400, matchStart)`.
  - Determinism and immutability: unsorted history produces identical results without mutating input list.
  - Significance threshold boundaries: major, notable, none for all constants.
  - Missing data & degraded mode robustness: handles null request, null match, empty history, null telemetry/player/rank gracefully.
  - Payload JSON serialization: verified size is <= 1,200 characters (actual ~300 chars) using plain Jackson 3 `JsonMapper`.

## Deviations
None.

## Known Issues
None.

## Merge Risks
None (no shared or configuration files modified).
- `docs/PROJECT_CONTEXT.md` update pending integration:
  - Added `FormCoachProvider` (`"recent_form"`) under `sntiago05.rejuv.services`.
  - Evaluates recent form baseline, z-score deviations, 5v5 trend comparison, consecutive loss streak, and 24h fatigue volume.
