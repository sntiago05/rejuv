# Task 031: Coach layer "phases" (net worth, damage and farm against the rank curve)

- **Branch**: `feature/031-phases-coach`
- **Implementer**: Antigravity
- **Related ADRs**: none (uses the extension point of task 029)
- **Depends on**: 029 (merged). Must read `docs/coach-provider-rules.md` first; it defines the provider contract and test requirements.

## Goal
Tell the model how the player's economy and damage evolved against players of the same hero and rank at fixed game times (6, 12, 20 and 30 minutes), and in which phase (laning, mid, late) the game was won or lost. This fills the gap shown by the audit (`Net worth at 20 min: 19371 / p50 not provided`): the performance curve gives a mean and a standard deviation at every time.

## Files to create or modify
| File | Change |
|---|---|
| `services/PhasesCoachProvider.java` | **New.** |
| `src/test/java/sntiago05/rejuv/services/PhasesCoachProviderTest.java` | **New.** |
| `docs/task-reports/031-phases-coach.md` | **New.** |

Nothing else (see "Files a provider task may touch" in the rules file). `docs/PROJECT_CONTEXT.md`: pending integration.

## Interfaces / signatures
```java
@Component
public class PhasesCoachProvider implements CoachInsightProvider {
    public static final String KEY = "phases";
    public PhasesCoachProvider(DeadlockApiClient deadlockApiClient)
    @Override public String key()                       // KEY
    @Override public Optional<CoachInsight> provide(CoachRequest request)
}
```
Data used (all existing): `request.player().snapshots()` (`StatSnapshot` with optional `SnapshotDetail`), `request.telemetry().players()` (teammates), `request.match().heroId()`, `request.rankTier()`, and `DeadlockApiClient.getPerformanceCurve(int heroId, int rankTier)` (cached, returns `PerformanceCurvePoint` list with `gameTime`, `netWorthAvg/Std`, `playerDamageAvg/Std`, `creepKillsAvg/Std`, `deathsAvg/Std`).

## Behavior
1. Return `Optional.empty()` when: `telemetry`, `player` or `rankTier` is null; the curve is empty; or fewer than 2 checkpoints can be built.
2. **Checkpoints.** `CHECKPOINTS_S = {360, 720, 1200, 1800}`. A checkpoint is usable when the match lasted at least that long, the player has a snapshot with exactly that `timeS`, the curve has a point with exactly that `gameTime`, and the curve `netWorthStd > 0`. No interpolation (verified: snapshots and the curve share the grid 180, 360, 540, 720, 900, 1200, 1500, 1800, 2100, 2400 s, and both are cumulative values).
3. **Per checkpoint** (`z = (value - avg) / std`, one decimal):
   - `net_worth`, `net_worth_avg`, `net_worth_z`.
   - `damage_z` from `StatSnapshot.playerDamage` against the curve player damage (omit when `playerDamageStd <= 0`).
   - `farm_z` from `SnapshotDetail.creepKills` against the curve creep kills (omit when `detail == null` or `creepKillsStd <= 0`).
   - `vs_teammates_pct`: `(playerNetWorth - avgTeammateNetWorth) / avgTeammateNetWorth * 100` using the other players with the same `team` that have a snapshot at that exact time; omit with no such teammate or an average `<= 0`.
4. **Phases.** Phase `laning` = checkpoint 360 -> 720, `mid` = 720 -> 1200, `late` = 1200 -> 1800, each only when both end checkpoints exist. `change` = `net_worth_z(end) - net_worth_z(start)`. `weakest_phase` is the phase with the lowest `change` if that value is `<= -PHASE_SWING_Z` (1.0), else omitted; `strongest_phase` the highest `change` if `>= PHASE_SWING_Z`, else omitted (ties: earlier phase first). Both can be omitted.
5. **Significance.** `major` when any checkpoint `|net_worth_z| >= 2.0` or any phase `|change| >= 2.0`; `notable` when any `|net_worth_z|` or `|change|` is `>= 1.0`; else `none`. Name the constants and explain them in comments (about 1 standard deviation is common, 2 is rare).
6. Payload record (fields in this order; lists capped at 4 checkpoints, 3 phases):
```json
{"significance":"notable",
 "checkpoints":[{"minute":6,"net_worth":4368,"net_worth_avg":3777,"net_worth_z":1.1,"damage_z":0.9,"farm_z":1.3,"vs_teammates_pct":8.2}],
 "phases":[{"name":"laning","change":0.4}],
 "weakest_phase":"late","strongest_phase":"mid"}
```
7. Do not look at `request.history()`; do not call any other client method.

## Out of scope
Prompt or `AnalysisInput` changes (037); other coach layers; interpolation between grid points; benchmarks for kills/assists (deaths belong to task 032); caching or new client methods.

## Tests (in addition to the required cases in the rules file)
- A 40-minute match with all four checkpoints: exact z-scores, phase changes, strongest/weakest phase, significance.
- A 15-minute match: only the 360 and 720 checkpoints (laning only, no `mid`/`late`).
- Checkpoint skipped when the snapshot time or the curve point is missing; skipped when `netWorthStd == 0`; fewer than 2 checkpoints returns empty.
- `detail == null` (raw fallback source): payload still built from net worth and damage, without `farm_z`.
- Teammate comparison with 0, 1 and 5 teammates; teammate without that snapshot is ignored.
- Mocked `DeadlockApiClient` verifies exactly one `getPerformanceCurve(heroId, rankTier)` call and no other client call; a curve returning an empty list yields empty.

## Verification
`./mvnw clean verify` (on this machine `.\mvnw.cmd clean verify`, Docker running). Record baseline and final test counts. No live API call is needed.

## Acceptance criteria
- [ ] Compiles, all tests pass, counts recorded
- [ ] Provider follows every rule in `docs/coach-provider-rules.md`
- [ ] Payload at most 1,200 characters for a 40-minute match
- [ ] Only the three files above are created; nothing else changed
- [ ] Task report created; `docs/PROJECT_CONTEXT.md` update marked pending integration
- [ ] Follows AGENTS.md conventions
