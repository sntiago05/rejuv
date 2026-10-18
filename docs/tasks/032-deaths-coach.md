# Task 032: Coach layer "deaths" (when, how often and to whom the player dies)

- **Branch**: `feature/032-deaths-coach`
- **Implementer**: Antigravity
- **Related ADRs**: none
- **Depends on**: 029 (merged). Must read `docs/coach-provider-rules.md` first.

## Goal
Give the model a factual death profile of the match: how many deaths per phase, how much time and how many souls dying cost, who kept killing the player, whether deaths came in clusters or while the team was behind, and how the death count compares with the rank curve. Verified source: `death_details[]` in the match metadata (task 029 maps it to `PlayerTelemetry.deaths()`).

## Files to create or modify
| File | Change |
|---|---|
| `services/DeathsCoachProvider.java` | **New.** |
| `src/test/java/sntiago05/rejuv/services/DeathsCoachProviderTest.java` | **New.** |
| `docs/task-reports/032-deaths-coach.md` | **New.** |

Nothing else. `docs/PROJECT_CONTEXT.md`: pending integration.

## Interfaces / signatures
```java
@Component
public class DeathsCoachProvider implements CoachInsightProvider {
    public static final String KEY = "deaths";
    public DeathsCoachProvider(DeadlockApiClient deadlockApiClient)
    @Override public String key()
    @Override public Optional<CoachInsight> provide(CoachRequest request)
}
```
Data used: `request.player().deaths()` (`DeathEvent`: `gameTimeS`, `killerSlot`, `timeToKillS`, `respawnS`), `request.player().snapshots()` and `SnapshotDetail.goldDeathLoss`, `request.telemetry().players()` and `MatchTelemetry.playerBySlot(int)`, `request.telemetry().durationS()`, `request.heroes()` for hero names, and (optional, best effort) `DeadlockApiClient.getPerformanceCurve(heroId, rankTier)`.

## Behavior
1. Return `Optional.empty()` when `telemetry` or `player` is null, when no snapshot of the player has a non-null `detail` (marker of a JSON-sourced match; the raw fallback has no deaths data and an empty list would otherwise look like "zero deaths"), or when the player has no deaths (nothing to coach).
2. **Core facts** (all from `deaths`): `total`; `by_phase` = counts for `laning` (`gameTimeS < 720`), `mid` (`720 <= t < 1200`), `late` (`>= 1200`); `seconds_dead` = sum of `respawnS`; `percent_of_match_dead` = `seconds_dead / durationS * 100`; `longest_respawn_s`; `souls_lost_on_death` = `goldDeathLoss` of the last snapshot (omit when `detail` is null on it).
3. **Clusters.** `back_to_back` = number of deaths that happen within `CLUSTER_WINDOW_S` (60 s) of the previous death (a chain of 3 quick deaths counts 2).
4. **Killers.** Resolve `killerSlot` through `playerBySlot`; ignore null slots and slots that do not resolve. Group by killer hero name (from `request.heroes()`, fallback `hero_<id>`). `top_killer` = {`hero`, `count`} for the most frequent (ties: higher count, then lower hero id). `repeat_killer` = `true` when `top_killer.count >= 3` and at least 40% of the resolved killers.
5. **Deaths while behind.** For each death find the latest snapshot time `T <= gameTimeS` that exists for **all** 12 players (compute the common times once; deaths before the first common time are skipped). `lead` = sum of net worth of the player's team minus the sum of the other team at `T`. `deaths_while_behind` = number of deaths with `lead < 0`; also report `deaths_counted` (how many deaths were evaluated). Omit both fields when `deaths_counted == 0`.
6. **Rank curve (best effort).** When `rankTier` is non-null, call `getPerformanceCurve(heroId, rankTier)` once; take the point with the largest `gameTime <= durationS` that is on the curve grid and has `deathsStd > 0`; `deaths_z` = `(deaths_at_that_time - deathsAvg) / deathsStd`, where `deaths_at_that_time` is the player's cumulative `SnapshotDetail.deaths` at the snapshot with exactly that time (skip when missing). Wrap this call (and only this) in a try/catch of `RuntimeException` that logs one WARN with the exception class and continues without `deaths_z`.
7. **Significance** (named constants with comments): `major` when `deaths_z >= 2.0`, or `percent_of_match_dead >= 15`, or `repeat_killer`, or `deaths_while_behind >= 3`; `notable` when `deaths_z >= 1.0`, or `percent_of_match_dead >= 10`, or `back_to_back >= 2`, or `total >= 8`; else `none`.
8. Payload (order as written; `top_killer` omitted when none resolves; list/object sizes fixed):
```json
{"significance":"major","total":9,"by_phase":{"laning":2,"mid":3,"late":4},"seconds_dead":410,
 "percent_of_match_dead":16.7,"longest_respawn_s":62,"souls_lost_on_death":1850,"back_to_back":2,
 "top_killer":{"hero":"Haze","count":4},"repeat_killer":true,
 "deaths_while_behind":5,"deaths_counted":8,"deaths_z":1.6}
```
Do not use death positions (`death_pos`/`killer_pos` are not mapped and there is no map-zone data).

## Out of scope
Positions or "enemy territory" claims; kills or assists analysis; prose; prompt/`AnalysisInput` changes (037); the overall economy against the curve (031).

## Tests (plus the required cases in the rules file)
- Phase boundaries at 719/720/1199/1200 seconds.
- `back_to_back` with gaps of 59, 60 and 61 s; chain of three.
- Killers: unresolved slot, null slot, tie-breaking, `repeat_killer` at 2 vs 3 kills and at 39% vs 40%.
- Deaths while behind: team ahead, team behind, death before the first common time (skipped), a snapshot missing for one player (that time is not common).
- `deaths_z`: curve present, curve empty, `deathsStd == 0`, tier null (no curve call at all), curve call throwing `DeadlockApiUnavailableException` (payload still built, no `deaths_z`).
- Raw-fallback telemetry (all `detail == null`) returns empty; zero deaths returns empty.
- Significance boundaries for every constant.

## Verification
`./mvnw clean verify` (on this machine `.\mvnw.cmd clean verify`, Docker running); counts in the report.

## Acceptance criteria
- [ ] Compiles, all tests pass, counts recorded
- [ ] Provider follows every rule in `docs/coach-provider-rules.md`; payload at most 1,200 characters
- [ ] No death position data used or claimed
- [ ] Only the three files above created; nothing else changed
- [ ] Task report created; `docs/PROJECT_CONTEXT.md` update marked pending integration
- [ ] Follows AGENTS.md conventions
