# Task 035: Coach layer "matchup" (lane opponents, enemy team and who won the lane)

## Status
Implemented on `feature/035-matchup-coach`. `.\mvnw.cmd clean verify` passes (815 tests run, 0 failures, 2 skipped). Not pushed, not merged.

## Summary
Added `MatchupCoachProvider`, a `@Component` implementing `CoachInsightProvider` under key `"matchup"` that pre-analyzes matchup draft win rates and laning phase outcomes for the AI analysis:
1. **Draft Context**: Single-pass scan over `DeadlockApiClient.getHeroCounterStats(tier, startOfWindow)` filtering rows with `heroId == player.heroId()` and `matchesPlayed >= MIN_PAIR_MATCHES` (100). Computes `enemy_avg_win_rate_pct` across all qualifying enemy hero pairs, identifies `hardest` (up to 2 lowest win rates, ties broken by lower hero ID), and identifies `easiest` (highest win rate, included only if `>= 52.0%`).
2. **Lane Outcome**: When `assignedLane` is present on the player, identifies up to 2 enemy `lane_opponents` in the same lane (ordered by hero ID). Computes net worth difference and percentage relative to opponents' average at `LANE_CHECK_S = 720` (12 minutes). Evaluates `lane_result` as `"won"` (`>= 10.0%`), `"lost"` (`<= -10.0%`), or `"even"`. Omitted if snapshot is missing for the player, missing for all opponents, match is shorter than 720s, or opponents' average net worth is `<= 0`.
3. **Significance**: Evaluates `"major"` when `lane_result == "lost"` with net worth diff percentage `<= -20.0%`, or any lane opponent or `hardest` entry has win rate `<= 45.0%`. Evaluates `"notable"` when `lane_result == "lost"`, or any `hardest` entry is `<= 48.0%`. Otherwise `"none"`.
4. **Resilience & Degraded Mode**: Catches `DeadlockApiException` from `getHeroCounterStats`, logs one WARN with the exception class name, and continues with lane-only metrics (no win rates). Returns `Optional.empty()` if telemetry/player is missing, no enemies exist, or both pair table and lane metrics are absent.

## Important Files
- Created:
  - `src/main/java/sntiago05/rejuv/services/MatchupCoachProvider.java`: Component implementing `CoachInsightProvider`, two-constructor pattern for testable `Clock`,Jackson-annotated records `MatchupPayload` and `HeroWinRate`.
  - `src/test/java/sntiago05/rejuv/services/MatchupCoachProviderTest.java`: 25 unit tests covering full 12-player happy path, hardest/easiest tie-breaking, min pair matches threshold (99 vs 100), easiest threshold boundary (51.9 vs 52.0), lane opponents count (2, 1, 0, cap at 2), missing snapshot handling, match duration boundary (< 720s), lane result boundaries (+/-9.9% vs +/-10.0%), significance boundaries (-20.0% / -19.9%, 45.0% / 45.1%, 48.0% / 48.1%), tier handling (5, 0, null, 12), `DeadlockApiException` fallback (lane-only vs empty), 2,000-row single-pass scalability, missing data handling, hero name fallback hierarchy, determinism, and payload size restriction (<= 1,200 chars).
  - `docs/task-reports/035-matchup-coach.md`: this task report.

## Decisions
- **Single Pass Lookup**: Pre-filtered counter statistics for the player's hero into a `HashMap<Integer, HeroCounterStat>` in a single linear pass over the table (~2,000 rows max), ensuring O(1) lookups per enemy player.
- **Rounding and Number Formatting**: Used `BigDecimal.valueOf(val).setScale(1, RoundingMode.HALF_UP).doubleValue()` normalized to eliminate negative zero (`-0.0`), strictly adhering to `docs/coach-provider-rules.md`. `lane_net_worth_diff` rounded to integer `Long`.
- **Hero Name Resolution**: Built a map from `request.heroes()` prioritizing `name`, falling back to `className`, and defaulting to `"hero_<id>"`.
- **Payload Order and Caps**: `@JsonPropertyOrder` ensures consistent serialized field ordering matching the specification. Capped `hardest` at 2, `lane_opponents` at 2, and `easiest` as a single nullable object.

## Verification
- Baseline `.\mvnw.cmd verify`: BUILD SUCCESS, 790 tests run, 0 failures, 2 skipped (34.443 s).
- Targeted test run `.\mvnw.cmd test -Dtest=MatchupCoachProviderTest`: BUILD SUCCESS, 25 tests run, 0 failures, 0 errors, 0 skipped (1.376 s).
- Final `.\mvnw.cmd clean verify`: BUILD SUCCESS, 815 tests run (+25 tests), 0 failures, 0 errors, 2 skipped (42.704 s).
- `git diff --check`: Clean (no trailing whitespace, no line-ending issues).
- Code inspection: Only the 3 specified files created; no existing classes, DTOs, caches, or configurations modified.

## Deviations
None.

## Known Issues
None.

## Merge Risks
None. The provider is auto-registered by Spring component scanning via `CoachInsightProvider` and `CoachContextService`. No shared configuration or existing provider files were touched.

PROJECT_CONTEXT update pending integration (parallel task branches `017`, `018`, `020`, `021`, `029`, `030`, `031`, `032`, `033`, `034`, `036`, `038` are active). Facts to add upon integration:
- `MatchupCoachProvider` added under key `"matchup"` implementing `CoachInsightProvider`.
- Exposes draft enemy win rates (`enemy_avg_win_rate_pct`, `hardest`, `easiest`), lane opponents, and laning phase net worth delta at 12 minutes (`lane_net_worth_diff`, `lane_net_worth_diff_pct`, `lane_result`).
