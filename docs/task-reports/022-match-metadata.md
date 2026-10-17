# Task 022: Match metadata (`MatchMetadataSource` port, `MatchTelemetry` model, JSON adapter)

## Status

Completed and verified on `feature/022-match-metadata`, after merging the latest `main` (Task 005, PR #27). `./mvnw clean verify` passes with **135 tests** (124 on the branch right after the merge, 0 failures). Review fixes applied (see Review fixes). Not merged.

`docs/PROJECT_CONTEXT.md` updated: Task 005 was merged before this closeout, so the parallel-task deferral no longer applies.

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
  - Maps upstream DTOs to `MatchTelemetry`: stable-sorts items by `boughtAtS`, sorts snapshots by `timeS` (including final snapshot), sorts buff `pickupTimesS`, converts `sold_time_s == 0` (or missing) to `null`, and handles unsigned item IDs as `long`.
  - A missing required scalar (`account_id`, `team`, `hero_id`, `item_id`, `game_time_s`, `time_stamp_s`, `net_worth`, `player_damage`, `player_healing`) or a `null` player/item/stat entry throws `DeadlockApiException("Invalid body: ...")`. Buff entries with a `null` type (or `null` entries) are skipped, as the spec says.
  - Uses `@Cacheable(cacheNames = CacheConfig.DEADLOCK_MATCH_METADATA, sync = true)`.
- Added unit and caching test suites along with fixture `fixtures/deadlock-api/match-metadata.json`.

## Important Files

- `src/main/java/sntiago05/rejuv/domain/MatchTelemetry.java`, `PlayerTelemetry.java`, `ItemPurchase.java`, `StatSnapshot.java`, `PowerUpBuff.java`
- `src/main/java/sntiago05/rejuv/clients/MatchMetadataSource.java`
- `src/main/java/sntiago05/rejuv/clients/MatchMetadataNotFoundException.java`
- `src/main/java/sntiago05/rejuv/clients/dto/MatchMetadataResponse.java`
- `src/main/java/sntiago05/rejuv/clients/JsonMatchMetadataSource.java`
- `src/main/java/sntiago05/rejuv/config/CacheConfig.java`
- `src/test/java/sntiago05/rejuv/domain/MatchTelemetryTest.java` (2)
- `src/test/java/sntiago05/rejuv/clients/JsonMatchMetadataSourceTest.java` (27)
- `src/test/java/sntiago05/rejuv/clients/JsonMatchMetadataSourceCachingTest.java` (4)
- `src/test/java/sntiago05/rejuv/clients/DeadlockApiClientCachingTest.java` (expected cache set extended, see Deviations)
- `src/test/resources/fixtures/deadlock-api/match-metadata.json`
- `docs/PROJECT_CONTEXT.md`

## Decisions

- **Domain model in `domain/`, port and adapter in `clients/`**: `MatchTelemetry` is independent of upstream DTOs so future consumers (`MetricsService` in Task 007) and future fallback adapters (protobuf in Task 023) do not depend on Jackson DTOs.
- **Independent request and 503 handling**: Implemented within `JsonMatchMetadataSource` rather than refactoring `DeadlockApiClient`, keeping Task 004 production code unchanged.
- **Cache mapped domain model**: The cached entity is `MatchTelemetry` rather than raw JSON payloads (~1 MB), keeping memory usage low for up to 300 entries.
- **Throw on missing metadata**: 404 and upstream errors throw exceptions rather than returning `Optional.empty()`, ensuring temporary errors are not cached for 24 hours.
- **Reject missing required fields** (owner decision at review): defaulting them to `0` would hand Task 007 invented data (for example a snapshot at 0 s) and cache it for 24 h.

## Verification

- **Baseline**: `.\mvnw.cmd verify` before changes: `Tests run: 62, Failures: 0, Errors: 0, Skipped: 0`.
- **First closeout**: `.\mvnw.cmd clean verify`: `Tests run: 84, Failures: 0, Errors: 0, Skipped: 0` (+22).
- **Review (2026-10-16)**: `./mvnw clean verify` on the branch: 84 tests, 0 failures. After `git merge origin/main` (Task 005): 124 tests, 0 failures.
- **After review fixes**: `./mvnw test -Dtest=JsonMatchMetadataSourceTest,JsonMatchMetadataSourceCachingTest,DeadlockApiClientCachingTest,MatchTelemetryTest`: 39 tests, 0 failures. `./mvnw clean verify`: `Tests run: 135, Failures: 0, Errors: 0, Skipped: 0`, BUILD SUCCESS (+11 over the 124 post-merge baseline, +73 over 62).
- **Live check** (first closeout, not repeated after the fixes): recent public match `118429733` from `GET /v1/matches/recently-fetched`; `GET /v1/matches/118429733/metadata` returned HTTP 200, duration 2689 s, 12 players, player 0 had 32 items, 11 snapshots and 13 buffs.
- **Diff checks**: `git diff --check` clean. No tokens, keys or real account ids; fixtures use only synthetic ids (`1000001`, `1000002`, `900000003`).

## Deviations

- **`DeadlockApiClientCachingTest` changed** (outside the spec table; approved by the owner at review on 2026-10-16). The spec adds a cache to `CacheConfig`, which necessarily broke `cacheTtlsAndSizesMatchTheDesign()`, whose exact-set assertion listed only the five Task 004 caches. The first closeout relaxed it to `containsAll`; at review it was restored to `containsExactlyInAnyOrderElementsOf` with `deadlockMatchMetadata` (24 h, 300) added to the expected maps, so an accidental extra cache is still caught. Spec issue for the architect: future specs that add a cache should list this test.
- **Interface injection in caching test**: `JsonMatchMetadataSourceCachingTest` autowires `MatchMetadataSource` instead of the concrete class, because Spring Cache wraps the bean in a JDK dynamic proxy.
- **Extra tests** beyond the spec list: `missingRequiredFieldsThrowApiException` (10 cases) and `missingSoldTimeMeansNotSold`, covering the review fix.

## Review fixes

1. Restored the strict cache-set assertion in `DeadlockApiClientCachingTest` (see Deviations).
2. Missing required scalars and `null` list entries now throw `DeadlockApiException` instead of becoming `0` (`JsonMatchMetadataSource.mapPlayer`, `required(...)`).
3. Merged `origin/main` (Task 005) and updated `docs/PROJECT_CONTEXT.md`.

## Known Issues and Follow-ups

- **Follow-up (a)**: When Task 023 adds a fallback metadata source (e.g. raw protobuf), caching should wrap the composite/fallback source rather than only `JsonMatchMetadataSource`.
- **Follow-up (b)**: A future refactor task could extract shared HTTP exchange and 503-retry logic between `DeadlockApiClient` and `JsonMatchMetadataSource`.
- **Follow-up (c)**: Upstream non-existent matches return 503 instead of 404, so callers cannot reliably distinguish "never exists" from "temporary failure".
- `docs/roadmap.md` still lacks the Task 022 entry (owner follow-up).

## Merge Risks

- Modifies `src/main/java/sntiago05/rejuv/config/CacheConfig.java` to register `DEADLOCK_MATCH_METADATA`.
- Modifies `src/test/java/sntiago05/rejuv/clients/DeadlockApiClientCachingTest.java` (expected cache set).
