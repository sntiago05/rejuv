# Task 034: Coach layer "recent_form" (habit or one-off, tilt and fatigue)

- **Branch**: `feature/034-form-coach`
- **Implementer**: Antigravity
- **Related ADRs**: none
- **Depends on**: 029 (merged). Must read `docs/coach-provider-rules.md` first.

## Goal
Let the model tell a one-off bad game from a habit. From the cached 200-entry match history it computes, for the same hero, the player's recent averages, how the analyzed match deviates from them, the short-term trend, and context that matters to a coach: consecutive losses before the match and games played in the previous 24 hours. Verified: the account used for the audit has 43 Lash games among its latest 200.

## Files to create or modify
| File | Change |
|---|---|
| `services/FormCoachProvider.java` | **New.** |
| `src/test/java/sntiago05/rejuv/services/FormCoachProviderTest.java` | **New.** |
| `docs/task-reports/034-form-coach.md` | **New.** |

Nothing else. `docs/PROJECT_CONTEXT.md`: pending integration. No client call is needed: `request.history()` is already fetched and cached by `/analyze`.

## Interfaces / signatures
```java
@Component
public class FormCoachProvider implements CoachInsightProvider {
    public static final String KEY = "recent_form";
    public FormCoachProvider()
    @Override public String key()
    @Override public Optional<CoachInsight> provide(CoachRequest request)
}
```
Data used: `request.match()` and `request.history()` (`MatchHistoryEntry`: `matchId`, `heroId`, `startTime` (epoch seconds), `matchDurationS`, `matchResult`, `playerTeam`, `playerKills/Deaths/Assists`, `netWorth`, `lastHits`, `denies`). Win = `matchResult == playerTeam`. Telemetry is not used, so this layer also works in degraded mode and with the raw fallback.

## Behavior
1. **Reference sample.** Entries of `request.history()` with the same `heroId` as the analyzed match, `startTime < match.startTime()`, `matchDurationS >= MIN_DURATION_S` (600, shorter games are remakes or abandons), excluding the analyzed match id. Sort newest first by `startTime` (ties: higher match id first) and keep at most `SAMPLE_MAX` (20). If fewer than `SAMPLE_MIN` (5) remain, return `Optional.empty()`.
2. **Per-game values:** `souls_per_min = netWorth / (durationS / 60.0)`, `deaths_per_10_min = deaths / (durationS / 60.0) * 10`, `kda = (kills + assists) / max(1, deaths)`.
3. **Sample statistics** (population mean and standard deviation over the sample): `win_rate_pct`, `avg_souls_per_min`, `avg_deaths_per_10_min`, `avg_kda`, and `sample_size`.
4. **This match against the sample:** `souls_per_min_z` and `deaths_per_10_min_z` = `(current - mean) / std`, one decimal; omit a z when `std <= 0`. The analyzed match must itself last at least `MIN_DURATION_S`, otherwise omit both z values (the rest of the payload is still returned).
5. **Trend.** When the sample has at least 10 games: compare the newest 5 against the next 5 (`recent_vs_previous`): `win_rate_change_pct` (points) and `deaths_per_10_min_change` (difference of means, positive = dying more). Omit with fewer than 10.
6. **Context across all heroes** (use every history entry with `startTime < match.startTime()`, newest first, no duration filter): `losses_in_a_row_before` = count of consecutive losses starting from the game just before the analyzed match; `games_last_24h` = number of entries with `startTime` in `[match.startTime() - 86400, match.startTime())`.
7. **Significance:** `major` when `deaths_per_10_min_z >= 2.0` or `losses_in_a_row_before >= 4` or `games_last_24h >= 12`; `notable` when `|souls_per_min_z| >= 1.0` or `deaths_per_10_min_z >= 1.0` or `losses_in_a_row_before >= 3` or `games_last_24h >= 8` or `deaths_per_10_min_change >= 1.0`; else `none`. Named constants, commented.
8. Payload:
```json
{"significance":"notable","sample_size":20,"win_rate_pct":55.0,"avg_souls_per_min":912.4,"avg_deaths_per_10_min":2.6,"avg_kda":3.1,
 "souls_per_min_z":-1.2,"deaths_per_10_min_z":1.4,
 "recent_vs_previous":{"win_rate_change_pct":-20.0,"deaths_per_10_min_change":0.8},
 "losses_in_a_row_before":3,"games_last_24h":9}
```

## Out of scope
Benchmarks against other players (other layers do that); persisting form or trends; reading more than the cached history; a `/trends` command (a later feature); time zone logic.

## Tests (plus the required cases in the rules file)
- Sample filtering: other heroes excluded, later matches excluded, the analyzed match excluded, games under 600 s excluded, cap at 20 keeps the newest.
- 4 vs 5 qualifying games (below and at `SAMPLE_MIN`); 9 vs 10 games for the trend.
- Hand-computed means, population standard deviation and z for a small fixed sample; `std == 0` omits z; short analyzed match omits z.
- `losses_in_a_row_before`: 0, 3 and a streak interrupted by a win; entries of other heroes count; the analyzed match and later ones do not.
- `games_last_24h` boundaries at exactly 86,400 seconds before the match and at the same `startTime`.
- Unsorted history input gives the same result as sorted; the input list is not mutated.
- Significance boundaries for every constant.

## Verification
`./mvnw clean verify` (on this machine `.\mvnw.cmd clean verify`, Docker running); counts in the report.

## Acceptance criteria
- [ ] Compiles, all tests pass, counts recorded
- [ ] Provider follows every rule in `docs/coach-provider-rules.md`; payload at most 1,200 characters
- [ ] No client call and no telemetry use
- [ ] Only the three files above created; nothing else changed
- [ ] Task report created; `docs/PROJECT_CONTEXT.md` update marked pending integration
- [ ] Follows AGENTS.md conventions
