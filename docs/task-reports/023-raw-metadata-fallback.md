# Task 023: Raw match metadata fallback (`/metadata/raw`, zstd + protobuf) and `FallbackMatchMetadataSource`

## Status

Completed on `feature/023-raw-metadata-fallback`.
`.\mvnw.cmd clean verify` passes with **606 tests** (0 failures, 2 skipped benchmark tests).
`docs/PROJECT_CONTEXT.md` update marked **pending integration** because parallel task branch `feature/019-usage-limits` is active in another worktree.
Not merged.

## Summary

- Added `com.google.protobuf:protobuf-java` (version 4.35.1, resolved from Spring Boot parent BOM) and `com.github.luben:zstd-jni` (version 1.5.7-23) to `pom.xml`.
- Added `DeadlockApiRequests.bytes(int maxBytes)` body reader with an 8 MiB limit on compressed bodies.
- Created `RawMatchMetadataParser` using `CodedInputStream` with recursion/size limit guards to decode `CMsgMatchMetaData` into `MatchTelemetry` with the exact mapping semantics of Task 022 (unsigned 32-bit values converted to `long`, `sold_time_s == 0` mapped to `null`, items/snapshots/pickup times sorted ascending, packed and unpacked pickup times supported, unknown protobuf fields skipped, missing required scalars throwing `DeadlockApiException`).
- Created `RawMatchMetadataSource` (`@Component`), fetching `GET /v1/matches/{match_id}/metadata/raw`, verifying Zstandard magic header `28 B5 2F FD`, decompressing with a 64 MiB output guard, and parsing telemetry.
- Created `FallbackMatchMetadataSource` (`@Primary @Component implements MatchMetadataSource`) carrying `@Cacheable(cacheNames = CacheConfig.DEADLOCK_MATCH_METADATA, sync = true)`. Calls `JsonMatchMetadataSource` first; on 503, 429, I/O errors or invalid JSON bodies, logs a single WARN and calls `RawMatchMetadataSource`. 404 from JSON is rethrown immediately without fallback. If raw throws 404, the JSON exception is rethrown with raw suppressed; if raw fails otherwise, the raw exception is thrown with JSON suppressed.
- Removed `@Cacheable` annotation and its unused imports from `JsonMatchMetadataSource`.
- Created ADR-0017 (`docs/adr/0017-raw-match-metadata-fallback.md`) documenting decisions and alternatives.
- Replaced `JsonMatchMetadataSourceCachingTest` with `FallbackMatchMetadataSourceCachingTest`, and added test suites `RawMatchMetadataParserTest`, `RawMatchMetadataSourceTest`, `FallbackMatchMetadataSourceTest`, and test data builder `RawMetadataTestData`.

## Important Files

- `pom.xml`
- `docs/adr/0017-raw-match-metadata-fallback.md`
- `src/main/java/sntiago05/rejuv/clients/DeadlockApiRequests.java`
- `src/main/java/sntiago05/rejuv/clients/JsonMatchMetadataSource.java`
- `src/main/java/sntiago05/rejuv/clients/RawMatchMetadataParser.java`
- `src/main/java/sntiago05/rejuv/clients/RawMatchMetadataSource.java`
- `src/main/java/sntiago05/rejuv/clients/FallbackMatchMetadataSource.java`
- `src/test/java/sntiago05/rejuv/clients/RawMetadataTestData.java`
- `src/test/java/sntiago05/rejuv/clients/RawMatchMetadataParserTest.java`
- `src/test/java/sntiago05/rejuv/clients/RawMatchMetadataSourceTest.java`
- `src/test/java/sntiago05/rejuv/clients/FallbackMatchMetadataSourceTest.java`
- `src/test/java/sntiago05/rejuv/clients/FallbackMatchMetadataSourceCachingTest.java`
- `docs/task-reports/023-raw-metadata-fallback.md`

## Decisions

- **Zstandard decompression via `zstd-jni` (1.5.7-23)**: The OpenAPI spec says `.meta.bz2`, but live inspection proves current payloads use Zstandard (magic `0xFD2FB528`). Unsupported compression formats fail fast with `DeadlockApiException`. BZip2 support was omitted as YAGNI; old matches were not tested.
- **Hand-written Protobuf parser via `CodedInputStream`**: Used Spring Boot BOM-managed `protobuf-java` (4.35.1) without `protoc` code generation or Maven plugins, keeping the build portable across developer and production platforms and skipping unused fields.
- **Cache on composite `FallbackMatchMetadataSource`**: Moved `@Cacheable` from `JsonMatchMetadataSource` to the composite fallback source so both JSON hits and raw fallback results are cached in Caffeine (`deadlockMatchMetadata`, 24 h / max 300), protecting the strict Steam 3 req/h rate limit.
- **404 is not a reason to fall back**: `MatchMetadataNotFoundException` from JSON is rethrown without calling raw.

## Verification

- **Baseline**: `.\mvnw.cmd clean verify` before changes: `Tests run: 563, Failures: 0, Errors: 0, Skipped: 2`.
- **Final**: `.\mvnw.cmd clean verify`: `Tests run: 606, Failures: 0, Errors: 0, Skipped: 2` (+43 net tests: +23 parser, +11 raw source, +7 fallback, +6 fallback caching, -4/5 deleted json caching tests).
- **Existing tests unchanged**: `JsonMatchMetadataSourceTest`, `DeadlockApiClientCachingTest`, and `AnalyzeServiceTest` all pass unchanged.
- **Resolved dependencies (`mvn dependency:tree`)**:
  - `com.google.protobuf:protobuf-java:jar:4.35.1:compile`
  - `com.github.luben:zstd-jni:jar:1.5.7-23:compile`
- **Accept header outcome**:
  - Sent request with default `Accept: application/json` header from `DeadlockApiConfig.restClientBuilder`.
  - Server answered HTTP 200 with `Content-Type: application/octet-stream` without rejecting the request (no 406). No Accept header override was required.
- **Live check against `api.deadlock-api.com`**:
  - Match ID: `114238834` (fetched from `/v1/matches/recently-fetched`)
  - HTTP status: 200
  - Bytes received: 208,511
  - First 4 bytes hex: `28 B5 2F FD` (Zstandard magic `0xFD2FB528`)
  - Decompressed size: 399,117 bytes
  - Parsed players count: 12
  - Parsed total items count: 408
  - Parsed total snapshots count: 132
  - Parsed total buffs count: 165
  - Duration: 2455 s
  - All required fields present in real data (no missing scalars).
- **Diff checks**:
  - `git diff --check` clean.
  - No secrets, tokens, or real user identifiers committed.

## Deviations

None.

## Known Issues and Follow-ups

- **OpenAPI documentation mismatch**: Upstream OpenAPI documentation specifies `.meta.bz2`, but current responses use Zstandard compression. Historical matches from early closed beta were not tested; if any return `BZh`, a follow-up task can introduce bzip2 decompression if needed.
- **Native library dependency**: `zstd-jni` bundles native binaries for Windows x64, Linux x86_64, and Linux aarch64. Verified working on this Windows x64 development machine.

## Project Context Update (Pending Integration)

Parallel task `feature/019-usage-limits` is active in another worktree. `docs/PROJECT_CONTEXT.md` update is deferred until integration. Facts to add upon integration:
- Task 023 implemented: `RawMatchMetadataSource` and `FallbackMatchMetadataSource` (`@Primary`) provide resilient fallback to `GET /v1/matches/{match_id}/metadata/raw` using Zstandard decompression and hand-written Protobuf decoding.
- Added dependencies: `com.google.protobuf:protobuf-java` (4.35.1) and `com.github.luben:zstd-jni` (1.5.7-23).
- Caching on `deadlockMatchMetadata` (24 h / 300) moved to `FallbackMatchMetadataSource`.
- Added ADR-0017 (`docs/adr/0017-raw-match-metadata-fallback.md`).
