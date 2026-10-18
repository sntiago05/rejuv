# ADR-0017: Raw match metadata fallback (`/metadata/raw`, zstd + protobuf) and `FallbackMatchMetadataSource`

- **Status**: Accepted
- **Date**: 2026-10-17

## Context
Match metadata (`MatchMetadataSource`, Task 022) powers AI analysis (`AnalyzeService`, Task 007/009). The JSON endpoint (`GET /v1/matches/{match_id}/metadata`) occasionally returns HTTP 503 ("Steam is busy", with strict fallback rate limits) or fails under load.
The deadlock-api exposes a raw protobuf metadata endpoint (`GET /v1/matches/{match_id}/metadata/raw`), which remains available even when the JSON endpoint fails.
The response payload is compressed (OpenAPI documentation mentions `.meta.bz2`, but live inspection verifies current payloads use Zstandard compression `0xFD2FB528`) containing protobuf message `CMsgMatchMetaData`. We need to decompress and decode this payload into our domain `MatchTelemetry` as a resilient fallback.

## Options considered
| Option | Pros | Cons |
|---|---|---|
| Zstandard only (`zstd-jni`) | Official bindings with pre-compiled native libraries for Windows x64, Linux x86_64, and Linux aarch64; directly handles the verified live compression format. | Native binary dependency (~7.5 MB); unmanaged in Spring Boot parent POM. |
| BZip2 support (`commons-compress`) | Would support bz2 if very old match archives use it. | Adds unnecessary dependency (YAGNI); live inspection of recent matches proved payloads are Zstandard. |
| Java Zstd pure port (`aircompressor`) | Pure Java without native JNI binaries. | Incomplete/unmaintained zstd format compatibility, slower decompression, lacks native maintenance. |
| Protobuf codegen (`protoc` + Maven plugin) | Generates type-safe classes from `.proto` definitions. | Requires `protoc` compiler binaries across environments (Windows dev, ARM64 Oracle Cloud), maintenance of Valve proto import hierarchy, generates many unused classes. |
| Protobuf `CodedInputStream` hand-written parser | Lightweight, BOM-managed dependency (`protobuf-java`), zero codegen, zero native `protoc` binary requirements, parses only the exact fields needed. | Manual parsing code to maintain and test wire types and field tags. |
| Cache on individual sources | Keeps cache annotation on `JsonMatchMetadataSource`. | Raw fallback results would not be cached, risking hitting Steam 3 req/h rate limits on subsequent calls. |
| Cache on combined `FallbackMatchMetadataSource` | Caches telemetry once regardless of whether it came from JSON or raw fallback; avoids duplicate network calls. | Requires moving `@Cacheable` from `JsonMatchMetadataSource` to the composite fallback bean. |

## Decision
1. Use `com.github.luben:zstd-jni` (version `1.5.7-23`) for decompression. BZip2 is excluded since live payloads use Zstandard; unsupported compression magic numbers fail fast as `DeadlockApiException`.
2. Use `com.google.protobuf:protobuf-java` (Spring Boot BOM-managed version 4.35.1) with a hand-written parser based on `CodedInputStream`, avoiding `protoc` and codegen.
3. Move `@Cacheable(cacheNames = CacheConfig.DEADLOCK_MATCH_METADATA, sync = true)` to `FallbackMatchMetadataSource` (`@Primary @Component`), which calls `JsonMatchMetadataSource` first and falls back to `RawMatchMetadataSource` on 503, 429, I/O errors or invalid JSON bodies (404 is rethrown without fallback).

## Consequences
- Match telemetry availability is significantly increased during deadlock-api / Steam JSON upstream outages.
- Consumers (`AnalyzeService`) continue injecting `MatchMetadataSource` without code changes.
- Fallback results are cached in Caffeine (`deadlockMatchMetadata`, 24 h / max 300), protecting upstream rate limits.
- If very old historical matches with bzip2 compression are ever encountered and needed, bzip2 support can be added via a follow-up ADR and task.
