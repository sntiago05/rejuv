# Task 029: Coach foundation (richer telemetry, performance curve, extension point)

- **Branch**: `feature/029-coach-foundation`
- **Implementer**: Antigravity
- **Related ADRs**: ADR-0010 (hand-written DTOs), ADR-0017 (raw metadata fallback)
- **Depends on**: 009, 022, 023 (all merged). Evidence: `docs/evidence/029-coaching-analytics/` (read-only, do not edit, do not copy the 1.3 MB metadata file into `src/test`).

## Goal
Give the coaching layers of tasks 031-035 everything they share, so they can run in parallel without touching the same files: (1) richer match telemetry (death events, per-snapshot combat/economy detail, player slot and assigned lane), (2) a cached client for the hero + rank performance curve (the missing net worth / kills / deaths benchmark), (3) an extension point (`CoachInsightProvider`) that lets later tasks add a section to the AI input by adding one class. **No behavior visible to users changes**: with zero providers registered, the AI input JSON must be identical to today's, and the prompt, schema and embed stay untouched.

## Files to create or modify
| File | Change |
|---|---|
| `domain/SnapshotDetail.java` | **New.** Record, see signatures. |
| `domain/DeathEvent.java` | **New.** Record, see signatures. |
| `domain/StatSnapshot.java` | Modify: add component `SnapshotDetail detail`; keep the 4-argument constructor (detail = `null`). |
| `domain/PlayerTelemetry.java` | Modify: add `Integer playerSlot`, `Integer assignedLane`, `List<DeathEvent> deaths`; keep the 6-argument constructor (nulls and empty list). |
| `domain/MatchTelemetry.java` | Modify: add `playerBySlot(int)`. |
| `domain/AnalysisInput.java` | Modify: add component `coach`; keep the 7-argument constructor; add `withCoach(Map)`. |
| `clients/dto/MatchMetadataResponse.java` | Modify: map the extra fields (see Behavior 1). |
| `clients/JsonMatchMetadataSource.java` | Modify: fill the new fields. |
| `clients/dto/PerformanceCurvePoint.java` | **New.** DTO record. |
| `clients/DeadlockApiClient.java` | Modify: add `getPerformanceCurve` and its path constant and reader. Nothing else. |
| `config/CacheConfig.java` | Modify: add cache `deadlockPerformanceCurve`. |
| `services/CoachInsightProvider.java`, `CoachInsight.java`, `CoachRequest.java`, `CoachContextService.java` | **New.** |
| `services/AnalyzeService.java` | Modify: call `CoachContextService` on the fresh-AI path only. |
| `src/test/resources/fixtures/deadlock-api/match-metadata-coach.json` | **New.** Small fake match (3 players, see Tests). |
| `src/test/resources/fixtures/deadlock-api/player-performance-curve.json` | **New.** 4 fake points. |
| Tests | Modify: `JsonMatchMetadataSourceTest`, `MatchTelemetryTest`, `DeadlockApiClientCachingTest`, `AnalyzeServiceTest`. **New**: `PerformanceCurveClientTest`, `CoachContextServiceTest`, `AnalysisInputJsonTest`. |
| `docs/task-reports/029-coach-foundation.md` | **New.** Task report. |

Do **not** edit `RawMatchMetadataParser`, `RawMatchMetadataSource`, `FallbackMatchMetadataSource`, `MetricsService`, `AnalysisClient`, the prompt, `messages.properties`, `AnalyzeCommand` or any other file. `docs/PROJECT_CONTEXT.md`: mark the update as pending integration (parallel worktrees are active).

## Interfaces / signatures

```java
// domain/SnapshotDetail.java  (all values cumulative since the match start, as in the API)
public record SnapshotDetail(int kills, int deaths, int assists,
                             int creepKills, int possibleCreeps,
                             int shotsHit, int shotsMissed,
                             int denies,
                             long playerDamageTaken,
                             long goldLaneCreep, long goldNeutralCreep,
                             long goldDenied, long goldDeathLoss) {}

// domain/StatSnapshot.java
public record StatSnapshot(int timeS, long netWorth, long playerDamage, long playerHealing, SnapshotDetail detail) {
    public StatSnapshot(int timeS, long netWorth, long playerDamage, long playerHealing) { this(..., null); }
}

// domain/DeathEvent.java
// killerSlot and timeToKillS may be null. respawnS is the API's death_duration_s (time spent dead).
public record DeathEvent(int gameTimeS, Integer killerSlot, Double timeToKillS, int respawnS) {}

// domain/PlayerTelemetry.java: components appended after powerUpBuffs, deaths copied with List.copyOf and sorted by gameTimeS
public record PlayerTelemetry(long accountId, int team, int heroId,
        List<ItemPurchase> items, List<StatSnapshot> snapshots, List<PowerUpBuff> powerUpBuffs,
        Integer playerSlot, Integer assignedLane, List<DeathEvent> deaths) {
    public PlayerTelemetry(long accountId, int team, int heroId, List<ItemPurchase> items,
            List<StatSnapshot> snapshots, List<PowerUpBuff> powerUpBuffs) { /* slot null, lane null, deaths List.of() */ }
}

// domain/MatchTelemetry.java
public Optional<PlayerTelemetry> playerBySlot(int playerSlot)

// clients/dto/PerformanceCurvePoint.java  (@JsonIgnoreProperties(ignoreUnknown = true); JSON keys in the API are snake_case)
public record PerformanceCurvePoint(int gameTime,
        double netWorthAvg, double netWorthStd,
        double killsAvg, double killsStd,
        double deathsAvg, double deathsStd,
        double assistsAvg, double assistsStd,
        double playerDamageAvg, double playerDamageStd,
        double deniesAvg, double deniesStd,
        double creepKillsAvg, double creepKillsStd) {}
// JSON keys: game_time, net_worth_avg, net_worth_std, kills_avg, kills_std, deaths_avg, deaths_std, assists_avg,
// assists_std, player_damage_avg, player_damage_std, denies_avg, denies_std, creep_kills_avg, creep_kills_std.

// clients/DeadlockApiClient.java
@Cacheable(cacheNames = CacheConfig.DEADLOCK_PERFORMANCE_CURVE, sync = true, key = "#heroId + ':' + #rankTier")
public List<PerformanceCurvePoint> getPerformanceCurve(int heroId, int rankTier)

// services/CoachInsight.java
public record CoachInsight(String key, Object payload) {}      // key: lowercase snake_case, non-blank; payload: JSON-serializable, non-null

// services/CoachRequest.java  (collections copied; telemetry and player may be null = degraded mode; rankTier null = unknown rank)
public record CoachRequest(long accountId, MatchHistoryEntry match, List<MatchHistoryEntry> history,
        MatchTelemetry telemetry, PlayerTelemetry player, Integer rankTier,
        List<HeroAsset> heroes, List<ItemAsset> items) {}

// services/CoachInsightProvider.java  (implemented by tasks 031-035; each implementation is a Spring @Component)
public interface CoachInsightProvider {
    String key();                                   // the same key as in the CoachInsight it returns
    Optional<CoachInsight> provide(CoachRequest request);   // empty = nothing to say; may call cached clients
}

// services/CoachContextService.java
@Service
public Map<String, Object> build(CoachRequest request)   // never throws
```

## Behavior

### 1. Telemetry mapping (`JsonMatchMetadataSource`, `MatchMetadataResponse`)
Source fields (verified in `docs/evidence/029-coaching-analytics/match-119330211-metadata.json`):
- Player: `player_slot` -> `playerSlot`, `assigned_lane` -> `assignedLane` (both optional; absent = `null`), `death_details[]` -> `deaths`.
- `death_details[]` item: `game_time_s` (required), `killer_player_slot` (optional), `time_to_kill_s` (optional), `death_duration_s` (optional, absent = 0). Fields `death_pos` and `killer_pos` are **not** mapped (no map-zone data exists; out of scope).
- Each `stats[]` entry (already mapped for the 3 old fields) also supplies `SnapshotDetail`: `kills`, `deaths`, `assists`, `creep_kills`, `possible_creeps`, `shots_hit`, `shots_missed`, `denies`, `player_damage_taken`, `gold_lane_creep`, `gold_neutral_creep`, `gold_denied`, `gold_death_loss`. A missing individual field counts as 0 (the detail is always non-null for this source). The 3 old fields keep their current "required" validation; new fields never cause `DeadlockApiException`.
- `death_details` null or absent = empty list. Deaths are sorted ascending by `gameTimeS`.
- **The raw adapter is not changed.** Telemetry that comes from `RawMatchMetadataParser` (fallback) has `detail == null`, `playerSlot == null`, `assignedLane == null` and no deaths, because its protobuf field numbers for these are not verified. Consumers (tasks 031-035) must treat these as "no data". Record this limitation in the report.

### 2. Performance curve
- `GET /v1/analytics/player-performance-curve` with query `hero_ids={heroId}`, `min_average_badge={tier*10}`, `max_average_badge={tier*10+9}` (reuse the private `badgeRange` helper) and **`resolution=0`** (verified live: with 0 the API returns absolute game times in seconds, 16 points from 180 to 2400 s for hero 31 tier 10; the default 10 returns percentage buckets, which are not wanted). Do not send `min_unix_timestamp` (the API default window applies; not verified, note it in the report).
- Returns the points sorted ascending by `gameTime`, as an immutable list. Empty list is a valid result (no data for that hero/rank).
- `rankTier` outside 0..11 throws `IllegalArgumentException` before any HTTP call (`requireRankTier`).
- Cache `deadlockPerformanceCurve`: 6 h, max 500. Failures are not cached. Same 503/429 rules as the other analytics methods (via `DeadlockApiRequests`).
- Only the fields in the DTO are read; the many `gold_*`/`*_orbs` fields are ignored.

### 3. Extension point
- `CoachContextService` receives `List<CoachInsightProvider>` through its constructor (Spring injects an empty list when there are no providers).
- `build(request)`: iterate providers **sequentially**, ordered by `key()` ascending (deterministic JSON). For each: call `provide`, ignore empty results, and put `key -> payload` in a `LinkedHashMap`. Never run providers in parallel (AGENTS.md: no parallel uncached analytics requests).
- Failure isolation: any `RuntimeException` from a provider (including `DeadlockApiException`) is caught, logged once at WARN with the provider key and the exception class name (no payload, no stack trace of API keys), and that provider is skipped. A returned insight whose key differs from the provider's `key()`, or a null/blank key, or a null payload, is discarded with a WARN. Duplicate provider keys: the first (in sorted order) wins, the rest are skipped with a WARN at construction time or first use (implementer's choice, test it).
- Returns an unmodifiable map; empty map when there are no providers or nothing to say.

### 4. Wiring in `AnalyzeService`
- Constructor gains a final field `CoachContextService coachContextService` (constructor injection, appended as the last parameter).
- On the **fresh path only** (after the usage check and after the telemetry / benchmark / item-stat reads, immediately before `analysisClient.analyze`): build a `CoachRequest` (history = `deadlockApiClient.getMatchHistory(accountId)`, already cached; player = `telemetry.player(accountId)` or `null`; rankTier = `rank.map(MatchRank::tier).orElse(null)`; `heroes`, `items` as already fetched) and call `input = input.withCoach(coachContextService.build(request))`.
- The stored-analysis path, the fallback path and `storedVisuals` are unchanged: providers never run for them (no AI call, no extra API calls).
- Providers must not change the saved `AnalysisResult`, the usage rules or the cache of any existing method.

### 5. `AnalysisInput.coach`
- New last component `Map<String, Object> coach` annotated so that an empty or null map is **omitted** from the JSON (`@JsonInclude(JsonInclude.Include.NON_EMPTY)` on the component). The 7-argument constructor sets an empty map. `withCoach(Map)` returns a copy with the map copied immutably (null = empty).
- With an empty `coach`, `ObjectMapper` output for any `AnalysisInput` must be byte-identical to the output before this task (test it against an input built with the old constructor).

## Out of scope
Prompt, `AnalysisResult` schema, embed or web changes; `MetricsService` changes; any provider implementation (tasks 031-035); mapping `death_pos`, `damage_matrix`, `objectives`, `mid_boss`; protobuf field numbers for the raw adapter; changing `max_tokens`; new dependencies; Flyway migrations; storing coach data in the database.

## Tests
- `JsonMatchMetadataSourceTest` (+ fixture `match-metadata-coach.json`: 3 invented players, one with 2 `death_details` out of order and one without any, one without `assigned_lane`, snapshots with and without optional detail fields): deaths sorted; slot/lane mapped; absent lane = `null`; missing detail field = 0; `death_details` absent = empty; old fixture `match-metadata.json` still maps unchanged.
- `MatchTelemetryTest`: `playerBySlot` found, not found, and players with null slot are skipped.
- `PerformanceCurveClientTest` (pattern of `PlayerRanksClientTest`, fixture `player-performance-curve.json` with 4 points given out of order): exact query (`hero_ids`, `min_average_badge`, `max_average_badge`, `resolution=0`), sorted result, empty array, invalid tier throws before any HTTP call, 503 then success follows the existing retry rule.
- `DeadlockApiClientCachingTest`: second call with the same key does not hit HTTP; `cacheTtlsAndSizesMatchTheDesign` extended with `deadlockPerformanceCurve` (6 h / 500).
- `CoachContextServiceTest`: no providers gives an empty map; two providers return keys in sorted order; empty `Optional` skipped; throwing provider skipped while others still run; key mismatch discarded; duplicate key handled; providers called in key order (verify with a recording fake).
- `AnalysisInputJsonTest`: empty coach omitted and JSON identical to the legacy output; non-empty coach serialized as a top-level `"coach"` object; `withCoach(null)` equals empty.
- `AnalyzeServiceTest`: fresh path passes `coach` to `analysisClient.analyze` (capture the argument with a fake provider via a mocked `CoachContextService`); stored path and fallback outcome never call `coachContextService`; a `CoachRequest` carries the telemetry player, rank tier and history; existing tests updated only for the new constructor argument.
- All existing tests that construct `PlayerTelemetry`/`StatSnapshot` with the old arities must keep compiling without edits (compatibility constructors).

## Verification
`./mvnw clean verify` (on this machine `.\mvnw.cmd clean verify`; see `docs/PROJECT_CONTEXT.md` for the `MAVEN_USER_HOME` note). Docker must be running. No live Discord or Claude call is required. One optional live read-only check of the curve endpoint with the exact query above is allowed (one sequential request) to confirm `resolution=0`; record the result in the report.

## Acceptance criteria
- [ ] Compiles, all tests pass (`./mvnw clean verify`), test count and baseline recorded in the report
- [ ] `AnalysisInput` JSON is identical to the previous output when `coach` is empty
- [ ] `JsonMatchMetadataSource` fills slot, lane, deaths and snapshot detail; the raw adapter is unchanged and its limitation is documented
- [ ] `getPerformanceCurve` sends `resolution=0`, is cached 6 h / 500, and rejects invalid tiers before HTTP
- [ ] `CoachContextService` is sequential, deterministic, failure-isolated and never throws
- [ ] Providers run only on the fresh-AI path
- [ ] No unauthorized file changed; no new dependency; no user-facing text added
- [ ] Task report created; `docs/PROJECT_CONTEXT.md` update marked pending integration
- [ ] Follows AGENTS.md conventions
