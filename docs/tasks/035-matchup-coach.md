# Task 035: Coach layer "matchup" (lane opponents, enemy team and who won the lane)

- **Branch**: `feature/035-matchup-coach`
- **Implementer**: Antigravity
- **Related ADRs**: none
- **Depends on**: 029 (merged), 012 (`getHeroCounterStats`, merged). Must read `docs/coach-provider-rules.md` first.

## Goal
Add draft and lane context: which enemy heroes the player faced, how the player's hero performs against them in general (win rate from the global pair table), who the lane opponents were, and whether the player's lane was won or lost by net worth at 12 minutes. This explains results that individual metrics cannot ("you farmed well, but your hero loses 44% against both lane opponents").

## Files to create or modify
| File | Change |
|---|---|
| `services/MatchupCoachProvider.java` | **New.** |
| `src/test/java/sntiago05/rejuv/services/MatchupCoachProviderTest.java` | **New.** |
| `docs/task-reports/035-matchup-coach.md` | **New.** |

Nothing else. `docs/PROJECT_CONTEXT.md`: pending integration.

## Interfaces / signatures
```java
@Component
public class MatchupCoachProvider implements CoachInsightProvider {
    public static final String KEY = "matchup";
    public MatchupCoachProvider(DeadlockApiClient deadlockApiClient)                 // production, Clock.systemUTC()
    MatchupCoachProvider(DeadlockApiClient deadlockApiClient, Clock clock)           // tests (two-constructor pattern of ProfileService)
    @Override public String key()
    @Override public Optional<CoachInsight> provide(CoachRequest request)
}
```
Existing API: `DeadlockApiClient.getHeroCounterStats(Integer rankTier, long minUnixTimestamp)` returns the global table of `HeroCounterStat(heroId, enemyHeroId, wins, matchesPlayed)` (cached `deadlockHeroCounters` 6 h; null tier = all ranks; one row per ordered hero pair, about 42 rows per hero); `ServiceSupport.startOfUtcDayMinus(clock, ServiceSupport.WINDOW)` (30 days, package-private in `services`; same window as `/counter`); `PlayerTelemetry.team()`, `heroId()`, `assignedLane()` (nullable), `snapshots()`; `request.heroes()` for names.

## Behavior
1. Return `Optional.empty()` when `telemetry` or `player` is null or the player has no enemy players in the telemetry.
2. **Tier.** Use `request.rankTier()` when it is in 1..11, otherwise `null` (all ranks). One call: `getHeroCounterStats(tier, startOfWindow)`. A `DeadlockApiException` is an expected condition: catch it, log one WARN with the exception class, and continue with the lane section only (no win rates).
3. **Enemies** = telemetry players whose `team` differs from the player's. Build a map `enemyHeroId -> HeroCounterStat` from the rows with `heroId == player hero` (one pass over the table). A pair counts only with `matchesPlayed >= MIN_PAIR_MATCHES` (100). `win_rate_pct = wins / matchesPlayed * 100`.
4. **Fields from the pair table** (omit when no pair qualifies): `enemy_avg_win_rate_pct` = mean of the qualifying pair win rates over the enemy players; `hardest` = up to 2 enemies with the lowest win rate (`hero`, `win_rate_pct`), ties by lower hero id; `easiest` = the enemy with the highest win rate, only when its win rate is `>= 52.0`.
5. **Lane.** Only when `assignedLane` is non-null for the player: `lane_opponents` = enemy players with the same `assignedLane` (cap 2, ordered by hero id): `hero` and, when the pair qualifies, `win_rate_pct`. `lane_net_worth_diff` and `lane_net_worth_diff_pct` at `LANE_CHECK_S = 720` (the laning-phase boundary used by task 031): player net worth minus the average of the lane opponents, using the snapshot with exactly that time; percentage relative to that average (omit when the average is `<= 0`, when a snapshot is missing for the player or all opponents, or when the match lasted less than 720 s). `lane_result` = `"won"` when the percentage is `>= LANE_DECISIVE_PCT` (10.0), `"lost"` when `<= -10.0`, otherwise `"even"`.
6. **Significance:** `major` when `lane_result == "lost"` and the percentage is `<= -20.0`, or any lane opponent or `hardest` entry has `win_rate_pct <= 45.0`; `notable` when `lane_result == "lost"`, or any `hardest` entry is `<= 48.0`; else `none`. Named constants with comments. Pair win rates are global averages and correlational: no judgement words in the payload.
7. **Payload** (ordered as written; lists capped as stated):
```json
{"significance":"major","enemy_avg_win_rate_pct":47.8,
 "hardest":[{"hero":"Haze","win_rate_pct":44.6}],"easiest":{"hero":"Dynamo","win_rate_pct":53.4},
 "lane_opponents":[{"hero":"Haze","win_rate_pct":44.6}],
 "lane_net_worth_diff":-1450,"lane_net_worth_diff_pct":-14.2,"lane_result":"lost"}
```
Hero names from `request.heroes()` (`name`, fallback `className`, fallback `hero_<id>`).

## Out of scope
Counter items (`/counter` already covers `getItemStatsAgainst`; do not call it here); teammate synergy; lane partners; changes to the client, DTOs or caches; prose; anything outside the three files.

## Tests (plus the required cases in the rules file)
- Full 12-player telemetry with a hand-built pair table: exact averages, `hardest`/`easiest` selection and tie-breaking, pair with 99 vs 100 matches, `easiest` at 51.9 vs 52.0.
- Lane section: player with 2 lane opponents, with 1, with none in the same lane; `assignedLane` null (raw fallback) omits the lane fields but keeps the pair fields; snapshot missing for one opponent; match shorter than 720 s; opponents' average `<= 0`.
- Lane result boundaries at +/-9.9 and +/-10.0 percent; `major` boundary at -19.9 / -20.0 and win rate 45.0 / 45.1.
- Tier handling: tier 5 passes 5; tier 0 and `null` pass `null`; exactly one `getHeroCounterStats` call; fixed `Clock` gives the expected `minUnixTimestamp`.
- `DeadlockApiUnavailableException` from the client: lane-only payload is still returned (and empty when there is no lane data either).
- The pair-table scan is a single pass (assert behavior with a large generated table, 2,000 rows, completing quickly; no timing assertion).

## Verification
`./mvnw clean verify` (on this machine `.\mvnw.cmd clean verify`, Docker running); counts in the report.

## Acceptance criteria
- [ ] Compiles, all tests pass, counts recorded
- [ ] Provider follows every rule in `docs/coach-provider-rules.md`; payload at most 1,200 characters
- [ ] No new client method, DTO or cache
- [ ] Only the three files above created; nothing else changed
- [ ] Task report created; `docs/PROJECT_CONTEXT.md` update marked pending integration
- [ ] Follows AGENTS.md conventions
