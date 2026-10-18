# Task 029: Coach foundation (richer telemetry, performance curve, extension point)

## Status
Implemented on `feature/029-coach-foundation`. `.\mvnw.cmd clean verify` passes (697 tests, 0 failures, 2 skipped). Not pushed, not merged.

## Summary
Task 029 introduces the foundational telemetry, performance benchmark client, and extensible coach aggregation layer required for future coaching rules (Tasks 031–035) without introducing any user-facing changes:
1. **Richer Match Telemetry**: Mapped `player_slot`, `assigned_lane`, and `death_details[]` (`DeathEvent`, sorted ascending by `gameTimeS`) in `PlayerTelemetry`, as well as combat and economy counters (`SnapshotDetail`) in `StatSnapshot` via `JsonMatchMetadataSource`. Backward-compatible constructors preserved on domain records. Added `playerBySlot` helper on `MatchTelemetry`.
2. **Performance Curve Client**: Added `DeadlockApiClient.getPerformanceCurve(heroId, rankTier)` querying `GET /v1/analytics/player-performance-curve` with `resolution=0` (returning absolute-seconds buckets, 180 to 2400 s), validated via `requireRankTier` (0..11), sorted ascending by `gameTime`, and cached under `deadlockPerformanceCurve` (6 h, max 500).
3. **Extension Point**: Introduced `CoachInsight`, `CoachRequest`, `CoachInsightProvider`, and `CoachContextService`. `CoachContextService` collects insights sequentially ordered by `key()` ascending, catches any `RuntimeException` per provider (logging WARN and skipping), discards mismatched keys / nulls, and resolves duplicate keys deterministically (first provider in sorted order wins).
4. **Wiring in AnalyzeService**: Injected `CoachContextService` into `AnalyzeService` and attached `coachContextService.build(request)` to `AnalysisInput` on the fresh-AI path only. Stored analysis, fallback, and rate-limited paths remain untouched.
5. **Byte-Identical AnalysisInput**: Added `@JsonInclude(JsonInclude.Include.NON_EMPTY) Map<String, Object> coach` to `AnalysisInput`. Output JSON remains strictly byte-identical to legacy output when `coach` is empty.

## Important Files
- **Created**:
  - `src/main/java/sntiago05/rejuv/domain/SnapshotDetail.java`
  - `src/main/java/sntiago05/rejuv/domain/DeathEvent.java`
  - `src/main/java/sntiago05/rejuv/clients/dto/PerformanceCurvePoint.java`
  - `src/main/java/sntiago05/rejuv/services/CoachInsight.java`
  - `src/main/java/sntiago05/rejuv/services/CoachRequest.java`
  - `src/main/java/sntiago05/rejuv/services/CoachInsightProvider.java`
  - `src/main/java/sntiago05/rejuv/services/CoachContextService.java`
  - `src/test/java/sntiago05/rejuv/clients/PerformanceCurveClientTest.java`
  - `src/test/java/sntiago05/rejuv/domain/AnalysisInputJsonTest.java`
  - `src/test/java/sntiago05/rejuv/services/CoachContextServiceTest.java`
  - `src/test/resources/fixtures/deadlock-api/match-metadata-coach.json`
  - `src/test/resources/fixtures/deadlock-api/player-performance-curve.json`
- **Modified**:
  - `src/main/java/sntiago05/rejuv/domain/StatSnapshot.java`
  - `src/main/java/sntiago05/rejuv/domain/PlayerTelemetry.java`
  - `src/main/java/sntiago05/rejuv/domain/MatchTelemetry.java`
  - `src/main/java/sntiago05/rejuv/domain/AnalysisInput.java`
  - `src/main/java/sntiago05/rejuv/clients/dto/MatchMetadataResponse.java`
  - `src/main/java/sntiago05/rejuv/clients/JsonMatchMetadataSource.java`
  - `src/main/java/sntiago05/rejuv/clients/DeadlockApiClient.java`
  - `src/main/java/sntiago05/rejuv/config/CacheConfig.java`
  - `src/main/java/sntiago05/rejuv/services/AnalyzeService.java`
  - `src/test/java/sntiago05/rejuv/clients/JsonMatchMetadataSourceTest.java`
  - `src/test/java/sntiago05/rejuv/domain/MatchTelemetryTest.java`
  - `src/test/java/sntiago05/rejuv/clients/DeadlockApiClientCachingTest.java`
  - `src/test/java/sntiago05/rejuv/services/AnalyzeServiceTest.java`

## Decisions
- **`resolution=0` on Performance Curve**: Confirmed from API research (`docs/evidence/029-coaching-analytics/README.md`); with `resolution=0` the API returns points at absolute match timestamps (180 to 2400 s) rather than percentage buckets. `min_unix_timestamp` is omitted, leaving the API's default time window in effect.
- **Provider Ordering & Deduplication**: Providers are sorted by `key()` ascending before invocation to guarantee deterministic ordering. In case of duplicate keys among providers, the first encountered in sorted order wins, and subsequent duplicates are logged at WARN and skipped.
- **Failure Isolation**: Any `RuntimeException` from a provider is caught, logged once at WARN with the provider key and exception class name (preventing any payload or API key leakage in logs), and that provider is skipped while others continue.
- **Raw Metadata Fallback Limitation**: `RawMatchMetadataParser` remains untouched per specification; it produces `null` for `detail`, `playerSlot`, `assignedLane`, and an empty list for `deaths`. Downstream coach providers in tasks 031–035 must handle these missing fields as degraded "no data".
- **Backwards Compatibility**: Arity-preserving constructors were added on `StatSnapshot`, `PlayerTelemetry`, and `AnalysisInput` so all existing call sites and tests compile without changes.

## Verification
- Baseline `./mvnw clean verify`: 675 tests run, 0 failures, 2 skipped.
- Final `.\mvnw.cmd clean verify`: BUILD SUCCESS, 697 tests run (+22 tests), 0 failures, 2 skipped.
- `git diff --check`: Clean, no whitespace or formatting errors.
- Tests added:
  - `PerformanceCurveClientTest`: 6 tests verifying query parameters, ascending time sort, empty list handling, tier bounds validation, and 503 retry behavior.
  - `DeadlockApiClientCachingTest`: Verifies `deadlockPerformanceCurve` cache hit and eviction configuration (6 h / 500).
  - `CoachContextServiceTest`: 9 tests verifying empty providers, deterministic key sorting, empty optional skipping, exception isolation, key mismatch rejection, and duplicate key handling.
  - `AnalysisInputJsonTest`: 3 tests verifying byte-identical JSON when coach map is empty or null, and proper serialization of top-level `"coach"` object when populated.
  - `JsonMatchMetadataSourceTest`: Added `mapsCoachFixtureWithRichTelemetry` to verify slot, lane, deaths sorting, snapshot detail mapping, and updated `mapsSnapshotsSortedIncludingFinal`.
  - `MatchTelemetryTest`: Verifies `playerBySlot` finds matching player and skips entries with null slot.
  - `AnalyzeServiceTest`: Verifies `coachContextService` is invoked only on fresh path and passes full coach context into `AnalysisInput`.

## Deviations
None.

## Known Issues
- `RawMatchMetadataParser` does not extract the new telemetry fields (by design, raw protobuf fallback retains its previous behavior).

## Merge Risks
- `CacheConfig.cacheTtlsAndSizesMatchTheDesign` checks all registered caches; parallel tasks introducing a new cache will conflict on this assertion.
- `AnalyzeService` constructor parameter appended: any concurrent task instantiating `AnalyzeService` directly will need to supply `CoachContextService`.
- `docs/PROJECT_CONTEXT.md` update pending integration:
  - Added `deadlockPerformanceCurve` cache (6 h, max 500).
  - Added `DeadlockApiClient.getPerformanceCurve(heroId, rankTier)` (`GET /v1/analytics/player-performance-curve?hero_ids=...&min_average_badge=...&max_average_badge=...&resolution=0`).
  - Added `PlayerTelemetry.playerSlot`, `PlayerTelemetry.assignedLane`, `PlayerTelemetry.deaths`, and `StatSnapshot.detail` (`SnapshotDetail`).
  - Added `CoachInsightProvider` and `CoachContextService` extension point for AI analysis context.
  - Added `AnalysisInput.coach` (`@JsonInclude(NON_EMPTY)`).
