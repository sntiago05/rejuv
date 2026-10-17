# Task 022: Match metadata (`MatchMetadataSource` port, `MatchTelemetry` model, JSON adapter)

## Status

Completed and verified. `.\mvnw.cmd clean verify` passes with **84 tests** (62 existing baseline + 22 new). Live check against `https://api.deadlock-api.com/v1/matches/118429733/metadata` verified the live payload structure. Not pushed, not merged.

PROJECT_CONTEXT update pending integration (parallel branch `feature/005-link-unlink` is active).

## Summary

- Created domain records in `sntiago05.rejuv.domain`: `MatchTelemetry`, `PlayerTelemetry`, `ItemPurchase`, `StatSnapshot`, `PowerUpBuff`. Plain Java records with immutable copy constructors (`List.copyOf`), no JPA, no Jackson annotations.
- Created port interface `MatchMetadataSource` and `MatchMetadataNotFoundException` in `sntiago05.rejuv.clients`.
- Created internal API DTO records in `sntiago05.rejuv.clients.dto.MatchMetadataResponse` with `@JsonIgnoreProperties(ignoreUnknown = true)` and nullable types for upstream resilience.
- Registered Caffeine cache `deadlockMatchMetadata` (24 h TTL, max 300 entries) in `CacheConfig`.
- Implemented `JsonMatchMetadataSource` (`@Component implements MatchMetadataSource`) backed by Spring `RestClient`:
  - Queries `GET /v1/matches/{match_id}/metadata` with no query parameters.
  - Handles 503 with one retry if `Retry-After` is within the configured cap (10 s), otherwise throws `DeadlockApiUnavailableException`.
  - Handles 429 (rate limit) and I/O/timeouts as `DeadlockApiUnavailableException`.
  - Handles 404 as `MatchMetadataNotFoundException`.
  - Maps upstream DTOs to `MatchTelemetry`: stable-sorts items by `boughtAtS`, sorts snapshots by `timeS` (including final snapshot), sorts buff `pickupTimesS`, converts `sold_time_s == 0` to `null`, and handles unsigned item IDs as `long`.
  - Uses `@Cacheable(cacheNames = CacheConfig.DEADLOCK_MATCH_METADATA, sync = true)`.
- Added unit, integration, and caching test suites along with fixture `fixtures/deadlock-api/match-metadata.json`.

## Important Files

- `src/main/java/sntiago05/rejuv/domain/MatchTelemetry.java`, `PlayerTelemetry.java`, `ItemPurchase.java`, `StatSnapshot.java`, `PowerUpBuff.java`
- `src/main/java/sntiago05/rejuv/clients/MatchMetadataSource.java`
- `src/main/java/sntiago05/rejuv/clients/MatchMetadataNotFoundException.java`
- `src/main/java/sntiago05/rejuv/clients/dto/MatchMetadataResponse.java`
- `src/main/java/sntiago05/rejuv/clients/JsonMatchMetadataSource.java`
- `src/main/java/sntiago05/rejuv/config/CacheConfig.java`
- `src/test/java/sntiago05/rejuv/domain/MatchTelemetryTest.java` (2)
- `src/test/java/sntiago05/rejuv/clients/JsonMatchMetadataSourceTest.java` (16)
- `src/test/java/sntiago05/rejuv/clients/JsonMatchMetadataSourceCachingTest.java` (4)
- `src/test/java/sntiago05/rejuv/clients/DeadlockApiClientCachingTest.java` (updated assertion to allow additional application caches)
- `src/test/resources/fixtures/deadlock-api/match-metadata.json`

## Decisions

- **Domain model in `domain/`, port and adapter in `clients/`**: `MatchTelemetry` is independent of upstream DTOs so future consumers (`MetricsService` in Task 007) and future fallback adapters (protobuf in Task 023) do not depend on Jackson DTOs.
- **Independent request and 503 handling**: Implemented within `JsonMatchMetadataSource` rather than refactoring `DeadlockApiClient`, keeping Task 004 production code unchanged while parallel tasks run.
- **Cache mapped domain model**: The cached entity is `MatchTelemetry` rather than raw JSON payloads (~1 MB), keeping memory usage low for up to 300 entries.
- **Throw on missing metadata**: 404 and upstream errors throw exceptions rather than returning `Optional.empty()`, ensuring temporary errors are not cached for 24 hours.

## Verification

- **Baseline**: `.\mvnw.cmd verify` before changes completed with `Tests run: 62, Failures: 0, Errors: 0, Skipped: 0`.
- **Narrow test runs**:
  - `.\mvnw.cmd test "-Dtest=MatchTelemetryTest,JsonMatchMetadataSourceTest,JsonMatchMetadataSourceCachingTest,DeadlockApiClientCachingTest"`: `Tests run: 28, Failures: 0, Errors: 0, Skipped: 0`.
- **Full verification**:
  - `.\mvnw.cmd clean verify`: `Tests run: 84, Failures: 0, Errors: 0, Skipped: 0` (BUILD SUCCESS, +22 tests over baseline).
- **Live check**:
  - Queried recent public match `118429733` from `GET https://api.deadlock-api.com/v1/matches/recently-fetched`.
  - Fetched `GET https://api.deadlock-api.com/v1/matches/118429733/metadata`: returned HTTP 200, duration 2689s, 12 players, player 0 had 32 items, 11 snapshots, and 13 buffs.
- **Diff checks**:
  - `git diff --check` clean (no whitespace or line-ending warnings).
  - Grepped for tokens, keys, passwords, and real account IDs: none found. Test fixtures use only synthetic IDs (`1000001`, `1000002`, `900000003`).

## Deviations

- **`DeadlockApiClientCachingTest` assertion relaxed**: Authorized by user via interactive prompt. `cacheTtlsAndSizesMatchTheDesign()` previously asserted `containsExactlyInAnyOrderElementsOf(...)` across only the initial 5 caches from Task 004. Adding `DEADLOCK_MATCH_METADATA` to the centralized `CacheConfig` caused that assertion to fail because `deadlockMatchMetadata` was unexpected. The assertion was changed to `containsAll(ttls.keySet())`, verifying all Task 004 caches without forbidding additional registered application caches.
- **Interface injection in caching test**: In `JsonMatchMetadataSourceCachingTest`, `@Autowired private MatchMetadataSource source` is used instead of the concrete class `JsonMatchMetadataSource` because Spring Cache with JDK dynamic proxies wraps beans implementing interfaces.

## Known Issues and Follow-ups

- **Follow-up (a)**: When Task 023 adds a fallback metadata source (e.g. raw protobuf), caching should wrap the composite/fallback source rather than only `JsonMatchMetadataSource`.
- **Follow-up (b)**: A future refactor task could extract shared HTTP exchange and 503-retry logic between `DeadlockApiClient` and `JsonMatchMetadataSource`.
- **Follow-up (c)**: Upstream non-existent matches with `disable_steam=true` return 503 instead of 404, so callers cannot reliably distinguish "never exists" from "temporary failure".

## PROJECT_CONTEXT update pending integration

The following facts should be incorporated into `docs/PROJECT_CONTEXT.md` once parallel work on `feature/005-link-unlink` is merged:
- Port `MatchMetadataSource` and domain model `MatchTelemetry` (`PlayerTelemetry`, `ItemPurchase`, `StatSnapshot`, `PowerUpBuff`) implemented in `sntiago05.rejuv.clients` and `sntiago05.rejuv.domain`.
- Primary adapter `JsonMatchMetadataSource` implemented for `GET /v1/matches/{match_id}/metadata`, cached in Caffeine cache `deadlockMatchMetadata` (24 h TTL, max 300).
- Honors 503 `Retry-After` within cap (10 s), maps 404 to `MatchMetadataNotFoundException`, 429/timeout/I/O to `DeadlockApiUnavailableException`, and other non-2xx to `DeadlockApiException`.
- Task 007 (`MetricsService`) dependency on Task 022 is now unblocked.

## Merge Risks

- Modifies `src/main/java/sntiago05/rejuv/config/CacheConfig.java` to register `DEADLOCK_MATCH_METADATA`.
- Modifies `src/test/java/sntiago05/rejuv/clients/DeadlockApiClientCachingTest.java` to use `containsAll` instead of `containsExactlyInAnyOrderElementsOf`.
