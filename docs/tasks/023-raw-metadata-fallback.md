# Task 023: Raw match metadata fallback (`/metadata/raw`, zstd + protobuf) and `FallbackMatchMetadataSource`

- **Branch**: `feature/023-raw-metadata-fallback`
- **Implementer**: Claude Code
- **Related ADRs**: ADR-0004 and ADR-0010 (deadlock-api, hand-written client), ADR-0002 (Caffeine), new **ADR-0017** (created by this task)

## Goal
Make match metadata more available. When the JSON endpoint (`JsonMatchMetadataSource`, Task 022) fails with 503, 429, an I/O error or an invalid body, read the same match from `GET /v1/matches/{match_id}/metadata/raw` (a compressed protobuf), map it into the same `MatchTelemetry` model, and cache the result once, on the combined source. Consumers (`AnalyzeService`, Task 007) keep injecting `MatchMetadataSource` and do not change.

## Context (verified when this spec was written)
Do not repeat this discovery. If a fact below is false in your checkout, stop and ask.

- Plan entry: `docs/implementation-plan.md` task 023, depends on 022 (merged). Runs **in parallel with Task 019** (`feature/019-usage-limits`): 019 touches usage limits, Flyway `V3`, `AnalyzeService`/commands. This task touches none of those. Start from the latest `origin/main`.
- Existing code (do not change unless the table says so):
  - `clients/MatchMetadataSource` (`MatchTelemetry getMatch(long matchId)`; throws `MatchMetadataNotFoundException`, `DeadlockApiUnavailableException`, `DeadlockApiException`).
  - `clients/JsonMatchMetadataSource`: `@Component`, currently `@Cacheable(cacheNames = CacheConfig.DEADLOCK_MATCH_METADATA, sync = true)` on `getMatch`; maps with private `mapToTelemetry`/`mapPlayer`/`required`. Constructor `(RestClient deadlockApiRestClient, DeadlockApiProperties properties, Sleeper sleeper)`.
  - `clients/DeadlockApiRequests` (package-private): `get(path, uriFn, BodyReader<T>, rateLimitedBodyIsSuccess, notFoundSupplier)` implements the shared rules (one 503 retry within `max-retry-after`, 429/I/O/timeout -> `DeadlockApiUnavailableException`, other statuses -> `DeadlockApiException`, 404 -> supplied exception). `BodyReader<T>` reads a `ConvertibleClientHttpResponse`; `json(...)` and `jsonArrayPrefix(...)` are the existing readers.
  - `config/DeadlockApiConfig`: the `deadlockApiRestClient` sets default header `Accept: application/json`.
  - `config/CacheConfig`: constant `DEADLOCK_MATCH_METADATA = "deadlockMatchMetadata"` (24 h, max 300) already exists. **No cache is added or changed.**
  - `services/AnalyzeService` injects `MatchMetadataSource` by interface; `AnalyzeServiceTest` mocks the interface.
  - Domain records (unchanged): `MatchTelemetry(long matchId, int durationS, List<PlayerTelemetry>)`, `PlayerTelemetry(long accountId, int team, int heroId, List<ItemPurchase>, List<StatSnapshot>, List<PowerUpBuff>)`, `ItemPurchase(long itemId, int boughtAtS, Integer soldAtS)`, `StatSnapshot(int timeS, long netWorth, long playerDamage, long playerHealing)`, `PowerUpBuff(String type, boolean permanent, List<Integer> pickupTimesS)`.
- `pom.xml`: Spring Boot parent 4.1.1. It manages `com.google.protobuf:protobuf-java` (4.35.1); it does **not** manage `zstd-jni`. No protobuf, zstd or bzip2 dependency exists today.
- Upstream contract for `GET /v1/matches/{match_id}/metadata/raw` (OpenAPI `operationId: metadata_raw`, plus one live call on 2026-10-18 for match `115316466`):
  - Optional query `is_custom`, `disable_steam` (send neither). Responses 200 (`application/octet-stream`), 400, 404 "Match metadata not found", 429, 500; 503 with `Retry-After` under load, as for every endpoint. Rate limits per IP: cache 100 req/s, S3 100 req/10 s, Steam 3 req/h.
  - **The OpenAPI text says `.meta.bz2`, but the real body is Zstandard.** Live: HTTP 200, no `Content-Type`, no `Content-Encoding`, 191,010 bytes, first bytes `28 B5 2F FD` (zstd magic `0xFD2FB528`); `bzip2` failed, `zstd` decompressed it to 370,956 bytes. Whether very old matches are still bz2 is **not verified**.
  - Decompressed payload is the outer message `CMsgMatchMetaData { optional uint32 version = 1; optional bytes match_details = 2; optional uint64 match_id = 3; }`. `match_details` holds `CMsgMatchMetaDataContents`, whose field 2 is `match_info`.
  - Schema source: `SteamDatabase/Protobufs`, `deadlock/citadel_gcmessages_common.proto` (The Unlicense). The decoder reads only the fields below; every other field is skipped.
- Field map (protobuf field numbers; all wire types are the standard ones for the listed types):

| Our model | Protobuf field | No. | Type |
|---|---|---|---|
| (outer) | `CMsgMatchMetaData.match_details` | 2 | bytes -> `CMsgMatchMetaDataContents` |
| (outer) | `CMsgMatchMetaData.match_id` | 3 | uint64 |
| (contents) | `match_info` | 2 | message `MatchInfo` |
| `durationS` | `MatchInfo.duration_s` | 1 | uint32 |
| `matchId` | `MatchInfo.match_id` | 6 | uint64 |
| players | `MatchInfo.players` | 4 | repeated message `Players` |
| `accountId` | `Players.account_id` | 1 | uint32 (read as unsigned -> `long`) |
| `team` | `Players.team` | 6 | enum (varint) |
| `heroId` | `Players.hero_id` | 12 | uint32 |
| items | `Players.items` | 4 | repeated message `Items` |
| snapshots | `Players.stats` | 5 | repeated message `PlayerStats` |
| buffs | `Players.power_up_buffs` | 30 | repeated message `PowerUpBuff` |
| `boughtAtS` | `Items.game_time_s` | 1 | uint32 |
| `itemId` | `Items.item_id` | 2 | uint32 (unsigned -> `long`, for example 3612874202) |
| `soldAtS` | `Items.sold_time_s` | 4 | uint32 (0 or absent -> `null`) |
| `timeS` | `PlayerStats.time_stamp_s` | 1 | uint32 |
| `netWorth` | `PlayerStats.net_worth` | 2 | uint32 (unsigned -> `long`) |
| `playerDamage` | `PlayerStats.player_damage` | 21 | uint32 (unsigned -> `long`) |
| `playerHealing` | `PlayerStats.player_healing` | 25 | uint32 (unsigned -> `long`) |
| `type` | `PowerUpBuff.type` | 1 | string |
| `permanent` | `PowerUpBuff.is_permanent` | 3 | bool |
| `pickupTimesS` | `PowerUpBuff.pickup_times_s` | 4 | repeated uint32, **packed or unpacked** |

- The raw payload has no `hero_build_id` / `pregame_hero_ids` (JSON-only); the model does not use them. Time units are the same seconds as in the JSON.

## Decisions taken in this spec (owner can veto before implementation)
1. **Decompression: Zstandard only, via `com.github.luben:zstd-jni`** (official bindings, native libs for Windows x64, Linux x86_64 and aarch64; about 7.5 MB; not BOM-managed, so the version is declared in `pom.xml`). BZip2 / `commons-compress` is **not added**: no evidence that any real payload is bz2, so an unknown magic number is an error (YAGNI). If the owner wants bz2 later, it is a separate task.
2. **Protobuf: `com.google.protobuf:protobuf-java` (BOM version, none in `pom.xml`) with a hand-written reader on `CodedInputStream`**, no `.proto` files, no `protoc`, no Maven plugin. This avoids a native `protoc` binary (Windows dev, ARM64 Oracle Cloud) and the Valve import chain, and ignores unused fields.
3. **Cache moves to the combined source.** `@Cacheable` is removed from `JsonMatchMetadataSource` and placed on `FallbackMatchMetadataSource` (same cache name `deadlockMatchMetadata`, `sync = true`), so a raw fallback result is cached and a JSON hit never triggers raw calls (Steam quota 3 req/h).
4. **404 is not a reason to fall back.** `MatchMetadataNotFoundException` from the JSON adapter is rethrown as is.

## Files to create or modify
| File | Change |
|---|---|
| `pom.xml` | **Modify.** Add `protobuf-java` (no version) and `zstd-jni` (explicit version, see Dependencies). Nothing else. |
| `docs/adr/0017-raw-match-metadata-fallback.md` | **Create.** From `docs/adr/0000-template.md`: decisions 1-3 above, alternatives (bz2, protoc codegen, aircompressor), consequences. |
| `src/main/java/sntiago05/rejuv/clients/RawMatchMetadataSource.java` | **Create.** Package-private or public class (not a `MatchMetadataSource` bean, see Behavior). Fetch + decompress + parse. |
| `src/main/java/sntiago05/rejuv/clients/RawMatchMetadataParser.java` | **Create.** Package-private, protobuf decoding into `MatchTelemetry`. |
| `src/main/java/sntiago05/rejuv/clients/FallbackMatchMetadataSource.java` | **Create.** `@Primary @Component implements MatchMetadataSource`, carries the `@Cacheable`. |
| `src/main/java/sntiago05/rejuv/clients/JsonMatchMetadataSource.java` | **Modify.** Remove only the `@Cacheable` annotation and its now unused imports. No other change. |
| `src/main/java/sntiago05/rejuv/clients/DeadlockApiRequests.java` | **Modify.** Add `static BodyReader<byte[]> bytes(int maxBytes)`; add an `Accept` override only if required (see Behavior step 2). |
| `src/test/java/sntiago05/rejuv/clients/RawMatchMetadataParserTest.java` | **Create.** |
| `src/test/java/sntiago05/rejuv/clients/RawMatchMetadataSourceTest.java` | **Create.** |
| `src/test/java/sntiago05/rejuv/clients/FallbackMatchMetadataSourceTest.java` | **Create.** |
| `src/test/java/sntiago05/rejuv/clients/FallbackMatchMetadataSourceCachingTest.java` | **Create.** Replaces `JsonMatchMetadataSourceCachingTest`. |
| `src/test/java/sntiago05/rejuv/clients/JsonMatchMetadataSourceCachingTest.java` | **Delete** (its cache behavior now lives on the fallback; its four cases move to the new test). |
| `src/test/java/sntiago05/rejuv/clients/RawMetadataTestData.java` | **Create.** Test helper that builds raw payloads with `CodedOutputStream` and compresses with `Zstd.compress`. No binary fixture is committed. |
| `docs/task-reports/023-raw-metadata-fallback.md` | **Create.** Task report. |

No `application.yaml`, migration, `messages.properties`, command, service, `CacheConfig`, domain or DTO change. `JsonMatchMetadataSourceTest`, `DeadlockApiClientCachingTest` and `AnalyzeServiceTest` must pass unchanged; if one needs an edit, stop and ask. If you think another file is needed, stop and ask.

## Dependencies
- Add `com.google.protobuf:protobuf-java` without a version; confirm with `./mvnw dependency:tree` that it resolves from the Boot BOM and record the resolved version.
- Add `com.github.luben:zstd-jni` with an explicit version: the latest **stable** release on Maven Central at implementation time (do not copy a version from memory); record it, and confirm it loads on this machine (the tests exercise it). Declare it as a `<zstd-jni.version>` property only if the existing `pom.xml` uses properties for versions (match its style; JDA uses an inline version).
- No other dependency. These two are authorized by this spec and ADR-0017.

## Interfaces / signatures

```java
// clients/RawMatchMetadataSource.java  (not a MatchMetadataSource bean; used by the fallback)
class RawMatchMetadataSource {
    RawMatchMetadataSource(RestClient deadlockApiRestClient, DeadlockApiProperties properties, Sleeper sleeper);
    MatchTelemetry getMatch(long matchId);   // same exceptions as MatchMetadataSource
}

// clients/RawMatchMetadataParser.java
final class RawMatchMetadataParser {
    static MatchTelemetry parse(long requestedMatchId, byte[] decompressedPayload);   // throws DeadlockApiException on invalid data
}

// clients/FallbackMatchMetadataSource.java
@Primary @Component
public class FallbackMatchMetadataSource implements MatchMetadataSource {
    public FallbackMatchMetadataSource(JsonMatchMetadataSource json, RawMatchMetadataSource raw);
    @Cacheable(cacheNames = CacheConfig.DEADLOCK_MATCH_METADATA, sync = true)
    public MatchTelemetry getMatch(long matchId);
}
```
`RawMatchMetadataSource` must be a Spring bean so it can be injected (a plain `@Component`); because it is not annotated `@Cacheable`, it is not proxied and can be injected by its concrete class, like `JsonMatchMetadataSource` after this change. Only `FallbackMatchMetadataSource` is `@Primary`.

## Behavior

### `RawMatchMetadataSource.getMatch(long matchId)`
1. Path constant `/v1/matches/{match_id}/metadata/raw`, no query parameters.
2. Use `DeadlockApiRequests.get(...)` with a `bytes(maxBytes)` reader (compressed body limit **8 MiB**; larger -> `DeadlockApiException`, never read it all into memory) and `notFound = () -> new MatchMetadataNotFoundException(...)`, `rateLimitedBodyIsSuccess = false`.
   - **Accept header**: the default client sends `Accept: application/json` and the upstream answers `application/octet-stream`. The live call in the evidence did not use that header. During your live check (see Verification) send the request through the app's `deadlockApiRestClient` with the default header. If the server rejects it (406 or similar), add a per-request `Accept: */*` (or `application/octet-stream`) option to `DeadlockApiRequests.get` through a small overload that keeps all existing callers unchanged; otherwise do not touch the request code. Report which case happened.
3. Detect the compression from the first bytes: `28 B5 2F FD` -> `ZstdInputStream`; anything else (including `BZh`, empty or shorter than 4 bytes) -> `DeadlockApiException("Unrecognized metadata compression format")`.
4. Decompress with a hard cap of **64 MiB** of output (zip-bomb guard); exceeding it -> `DeadlockApiException`. A zstd/IO error while decompressing -> `DeadlockApiException` (the data is invalid, not "unavailable").
5. Call `RawMatchMetadataParser.parse(matchId, bytes)`.
6. Never log the body or the bytes. Same logging rules as the JSON adapter (path only).

### `RawMatchMetadataParser.parse`
1. Read the outer message with `CodedInputStream`; take `match_details` (field 2) and `match_id` (field 3). Missing `match_details` -> `DeadlockApiException`. If the outer `match_id` is present and differs from `requestedMatchId` -> `DeadlockApiException("Match id mismatch ...")`.
2. In `CMsgMatchMetaDataContents` read only field 2 (`match_info`); missing -> `DeadlockApiException`.
3. `MatchInfo`: `duration_s` (1) and `match_id` (6) are required; `match_id` must equal `requestedMatchId`; at least one `players` entry (4), otherwise `DeadlockApiException`.
4. Map players, items, stats and buffs with exactly the semantics of `JsonMatchMetadataSource`:
   - required scalars (`account_id`, `team`, `hero_id`, `item_id`, `game_time_s`, `time_stamp_s`, `net_worth`, `player_damage`, `player_healing`) absent -> `DeadlockApiException("Invalid body: <field> is missing")`; never default them to 0;
   - unsigned 32-bit values (`account_id`, `item_id`, `net_worth`, `player_damage`, `player_healing`) are converted with `Integer.toUnsignedLong`;
   - `sold_time_s` absent or 0 -> `null`; items sorted by `boughtAtS` ascending (stable); snapshots by `timeS` ascending; `pickupTimesS` ascending;
   - buffs with no `type` are skipped; `is_permanent` absent -> `false`; `pickup_times_s` accepts both packed and unpacked encodings; absent -> empty list;
   - unknown fields are skipped with `skipField`; every unrelated wire type must be skipped correctly.
5. Any malformed protobuf (`InvalidProtocolBufferException`, truncated data, bad wire type) -> `DeadlockApiException("Invalid body: ...")`, with the cause. Use a recursion/size-safe approach (the CodedInputStream size limit must allow the real payload of about 371 KB and nested messages; set it explicitly rather than rely on defaults).
6. Reuse, do not copy, the JSON adapter's mapping rules where practical: if sorting/`unsigned`/`sold` normalisation can live in one small shared package-private helper used by both adapters without changing `JsonMatchMetadataSource` behavior, do so; otherwise keep the rules duplicated in the parser and mention it in the report (the JSON adapter file may only lose `@Cacheable` in this task, so extraction from it is **not authorized**).

### `FallbackMatchMetadataSource.getMatch(long matchId)`
1. Call `json.getMatch(matchId)`; on success return it (raw is never called).
2. `MatchMetadataNotFoundException` -> rethrow, no fallback.
3. `DeadlockApiUnavailableException` or `DeadlockApiException` from JSON -> log one WARN (match id, exception class and message; no body) and call `raw.getMatch(matchId)`.
4. If raw succeeds return it. If raw also fails, throw the **raw** exception with the JSON exception added via `addSuppressed`, except: if raw throws `MatchMetadataNotFoundException`, rethrow the **JSON** exception (the JSON failure is the more informative one) with the raw one suppressed.
5. Sequential calls only (never JSON and raw in parallel). Failures are not cached (thrown exceptions are not stored by Spring Cache). `AnalyzeService` keeps its existing degraded mode unchanged.

## Out of scope
- BZip2 support, `commons-compress`, `.proto` files, `protoc`, Maven protobuf plugins.
- Any change to `MatchTelemetry` or other domain records, DTOs, `CacheConfig`, `AnalyzeService`, `MetricsService`, commands, `messages.properties`, `application.yaml`, Flyway.
- Config properties or feature flags for the fallback (it is always on).
- Making `is_custom` / `disable_steam` configurable.
- Editing `docs/implementation-plan.md`, other ADRs, other task specs, `docs/mvp-spec.md`, `docs/architecture.md`.

## Tests (JUnit 5 + AssertJ; `MockRestServiceServer`; no real network; fake ids only)
`RawMetadataTestData` builds the payload bytes: outer message -> `match_details` -> contents -> `match_info` -> players, with a builder method per level, option to emit packed or unpacked pickup times, to omit any field, and `compress(byte[])` using `Zstd.compress`. Use fake ids (account `1000001`/`1000002`, match `900000003`, an item id above `Integer.MAX_VALUE`).

### `RawMatchMetadataParserTest`
1. `mapsAllFieldsFromTheFieldMap`: two players, items (including `item_id` 3612874202), snapshots, buffs; asserts the exact `MatchTelemetry`.
2. `sortsItemsSnapshotsAndPickupTimes`: input out of order -> ascending output.
3. `soldTimeZeroOrAbsentBecomesNull`, and a real sold time is kept.
4. `unsignedValuesAreNotNegative`: `account_id`, `item_id`, `net_worth` above `Integer.MAX_VALUE`.
5. `packedAndUnpackedPickupTimesAreEqual`.
6. `buffWithoutTypeIsSkipped`; `isPermanentAbsentIsFalse`.
7. `unknownFieldsAreSkipped`: extra fields with varint, fixed32, fixed64 and length-delimited wire types at every level.
8. `missingRequiredFieldsThrow`: parameterized, one case per required scalar, plus missing `match_details`, `match_info`, `duration_s`, `match_id`, and no players -> `DeadlockApiException`.
9. `matchIdMismatchThrows` (inner and outer ids).
10. `truncatedOrGarbagePayloadThrowsApiException` (not `InvalidProtocolBufferException`, not unchecked parse errors).

### `RawMatchMetadataSourceTest`
1. `sendsExpectedRequest`: `GET /v1/matches/900000003/metadata/raw`, no query string; returns the mapped telemetry from a zstd payload.
2. `retriesOnceOn503WithinCap`, `503AboveCapIsNotRetried`, `rateLimited429IsNotRetried`, `notFound404ThrowsNotFound`, `serverError500ThrowsApiException` (not unavailable), `ioErrorThrowsUnavailable` (same expectations as `JsonMatchMetadataSourceTest`).
3. `unknownCompressionThrowsApiException`: a `BZh...` body and an empty body.
4. `corruptZstdThrowsApiException`.
5. `oversizedBodyThrowsApiException` (compressed limit) and `decompressionBombThrowsApiException` (output limit; use a payload that expands past 64 MiB with a tiny compressed size, or make the limits package-private constants and test the check with a small override; do not allocate 64 MiB in the test heap unnecessarily).

### `FallbackMatchMetadataSourceTest` (Mockito mocks of the two sources)
1. JSON success: raw is never called.
2. JSON `DeadlockApiUnavailableException` -> raw result returned.
3. JSON `DeadlockApiException` (invalid body) -> raw result returned.
4. JSON `MatchMetadataNotFoundException` -> rethrown, raw never called.
5. Both fail (unavailable + api exception) -> raw exception thrown with the JSON one suppressed.
6. JSON unavailable and raw `MatchMetadataNotFoundException` -> the JSON exception is thrown, raw suppressed.
7. Calls are sequential (verify order with `InOrder`).

### `FallbackMatchMetadataSourceCachingTest` (pattern of the former `JsonMatchMetadataSourceCachingTest`; `@SpringJUnitConfig`, `@Import({CacheConfig.class, ...})`, `@DirtiesContext(AFTER_EACH_TEST_METHOD)`; autowire the interface, because Spring Cache wraps the bean in a JDK proxy)
1. `secondCallForSameMatchIsCached` with the JSON source succeeding: one JSON call.
2. `fallbackResultIsCached`: JSON fails, raw succeeds; the second call triggers neither source again.
3. `differentMatchesAreCachedSeparately`.
4. `failuresAreNotCached`: both fail on the first call, then succeed on the second.
5. `cacheHasConfiguredTtlAndSize`: `deadlockMatchMetadata` is 24 h / 300 (same technique as before).
6. `onlyTheFallbackIsPrimary`: the application context resolves `MatchMetadataSource` to `FallbackMatchMetadataSource` (a lightweight context test is enough; no database).

## Verification
1. Measure the baseline `./mvnw clean verify` on your branch **before changing anything** (Docker running for the existing Testcontainers tests; on Windows `.\mvnw.cmd clean verify`, or `.claude/skills/run-rejuv/driver.sh verify`). Report the real count and the difference. The last known baseline was 344 tests (2 skipped) before Task 015/025; do not assume it.
2. `./mvnw clean verify` passes at the end. `JsonMatchMetadataSourceTest`, `DeadlockApiClientCachingTest` and `AnalyzeServiceTest` pass unchanged.
3. `./mvnw dependency:tree` (or equivalent) output for the two new artifacts is recorded in the report (resolved versions).
4. **Live check (required for this task, not committed):** with a recent public match id from `GET /v1/matches/recently-fetched`, fetch `/metadata/raw` once, sequentially, through `RawMatchMetadataSource` (use `driver.sh eval` or a throwaway local test that you delete), report: HTTP status, bytes received, the first 4 bytes in hex, decompressed size, and the parsed player / item / snapshot counts. If any required scalar turns out to be absent in real data (for example a snapshot without `player_healing`), **stop and ask**; do not default it to 0. If you skip it or hit 503/429, say so; do not claim it.
5. `git diff --check`; inspect the final diff: only the files in the table changed; `JsonMatchMetadataSource` diff is only the removal of `@Cacheable` and unused imports; `pom.xml` diff is exactly the two dependencies; no secret and no real account id anywhere; `.env` never read or printed.

## Documentation
- Create `docs/adr/0017-raw-match-metadata-fallback.md` (English, from the template). Do not edit any existing ADR.
- Task report `docs/task-reports/023-raw-metadata-fallback.md`: status, summary, important files, commands run with real results, resolved dependency versions, the Accept-header outcome, live check result, deviations, known issues. State that the OpenAPI "bz2" text is wrong for current matches and that old matches were not tested.
- `docs/PROJECT_CONTEXT.md`: Task 019 is being implemented in parallel. **If the Task 019 branch is not merged into `main` at your closeout, do not edit this file**; write "PROJECT_CONTEXT update pending integration" in the report with the facts to add (fallback source, zstd + protobuf-java, cache now on the fallback, ADR-0017). If 019 is already merged, update it with verified facts only, without removing 019's entries.

## Acceptance criteria
- [ ] `./mvnw clean verify` passes; test count and baseline difference are in the report
- [ ] `MatchMetadataSource` has exactly one `@Primary` bean (`FallbackMatchMetadataSource`); `AnalyzeService` and its tests are unchanged
- [ ] JSON is always tried first; raw is called only after JSON unavailable / invalid body, never after a 404 or a success
- [ ] Raw payload (zstd magic `28 B5 2F FD`) is decompressed and mapped to `MatchTelemetry` with the same rules as the JSON adapter (unsigned ids as `long`, `sold_time_s` 0 -> `null`, sorted lists, required scalars never defaulted)
- [ ] Unknown compression, corrupt data, oversized compressed body, decompression bomb and malformed protobuf end as `DeadlockApiException`; 503/429/I/O follow the Task 004 rules via `DeadlockApiRequests`
- [ ] The combined result is cached once in `deadlockMatchMetadata` (24 h, max 300); failures are not cached; raw is not called on a cached match
- [ ] No body or bytes are logged; no `.proto` file, `protoc` or codegen plugin; no bzip2 dependency
- [ ] Only `protobuf-java` (BOM version) and `zstd-jni` are added; versions recorded
- [ ] ADR-0017 and the task report created; `docs/PROJECT_CONTEXT.md` updated or marked pending integration
- [ ] Live check done or honestly reported as skipped; final diff has no unrelated files

## Open questions / risks
1. **Zstd vs bz2 for old matches**: only one recent match was tested. If a real old match returns `BZh`, the owner must decide whether to add `commons-compress` in a follow-up.
2. **Required stats in real data**: protobuf may omit zero-valued optional fields for some snapshots. This spec keeps the strict rule (reject, do not invent 0) consistent with Task 022; the live check decides whether that is too strict. If it is, the owner decides the default-to-0 policy for `net_worth`, `player_damage`, `player_healing` only.
3. **Native library**: `zstd-jni` bundles native libs; if the production Docker image (Linux ARM64) fails to load it, this is a blocker for the owner, not something to work around with a different library silently.
4. The evidence file `docs/evidence/023-raw-metadata.md` (gathered by Antigravity) is untracked on the owner's machine and is not in a fresh worktree; everything needed from it is embedded in this spec.
