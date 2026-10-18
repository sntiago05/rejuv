# Task 042: Request and decode gzip on the deadlock-api client

## Status
Implemented on `feature/042-gzip-deadlock-api`. `.\mvnw.cmd verify` passes: 1008 tests run, 0 failures, 0 errors, 14 skipped (baseline: 998 tests run, 0 failures, 0 errors, 14 skipped). Not pushed, not merged.

## Summary
- **Gzip request negotiation and transparent decompression**: Created `GzipInterceptor`, a plain `ClientHttpRequestInterceptor` that automatically sets `Accept-Encoding: gzip` on outgoing deadlock-api requests unless the caller has explicitly set an `Accept-Encoding` header.
- **Decompression wrapper**: For 2xx responses with `Content-Encoding: gzip` (case-insensitive, trimmed) and a non-empty body (not status 204, 205, 304, and `Content-Length` not 0), the response is wrapped in a `ClientHttpResponse` decorator that lazily instantiates a `java.util.zip.GZIPInputStream` on the first call to `getBody()`.
- **Header stripping**: The wrapper strips `Content-Encoding` and `Content-Length` from the returned headers so downstream consumers (specifically `DeadlockApiRequests.bytes(maxBytes)`) count uncompressed streaming bytes instead of trusting the compressed content length.
- **Idempotent close**: `close()` idempotently closes the `GZIPInputStream` (if created) and delegates to the underlying response's `close()` exactly once, properly releasing any underlying resources.
- **Untouched pass-through**: Non-gzip responses, error statuses, 204/205/304 statuses, and empty responses (`Content-Length: 0`) pass through untouched without wrapping or failing on missing gzip headers. Corrupted or truncated streams surface as standard `IOException`s on read.
- **Configuration**: Installed `GzipInterceptor` on `DeadlockApiConfig.restClientBuilder(properties)` so all client consumers, mock servers, and adapters inherit gzip negotiation and decompression by default.

## Interceptor Order
The resulting interceptor chain on the shared `deadlockApiRestClient` bean is:
1. **Outer interceptor**: `GzipInterceptor` (registered in `DeadlockApiConfig.restClientBuilder`).
2. **Inner interceptor**: `DeadlockApiGate` (registered in `DeadlockApiConfig.deadlockApiRestClient`).

Execution flow:
- **Request**: `GzipInterceptor` sets `Accept-Encoding: gzip` -> `DeadlockApiGate` acquires a permit from its fair semaphore -> JDK `HttpClient` executes the HTTP request.
- **Response**: JDK `HttpClient` returns response -> `DeadlockApiGate` wraps response in `GatedClientHttpResponse` -> `GzipInterceptor` wraps in `GzipClientHttpResponse` (if gzip 2xx with body) -> adapter receives decompressed response.
- **Close**: Caller closes response -> `GzipClientHttpResponse.close()` closes `GZIPInputStream` (if instantiated) and calls delegate `close()` -> `GatedClientHttpResponse.close()` calls delegate close and releases the semaphore permit in a `finally` block.
Because `GzipClientHttpResponse.close()` delegates idempotently, the concurrency gate permit is guaranteed to be released once on response close.

## Important Files
- `src/main/java/sntiago05/rejuv/clients/GzipInterceptor.java` (new): `ClientHttpRequestInterceptor` managing `Accept-Encoding` advertisement, gzip response validation, lazy `GZIPInputStream` body wrapping, `Content-Encoding`/`Content-Length` header stripping, and idempotent close delegation.
- `src/main/java/sntiago05/rejuv/config/DeadlockApiConfig.java`: added `.requestInterceptor(new GzipInterceptor())` inside `restClientBuilder(DeadlockApiProperties)`. Maintained method signatures and existing gate installation in the bean method.
- `src/test/java/sntiago05/rejuv/clients/GzipInterceptorTest.java` (new, 10 tests):
  - `requestCarriesAcceptEncodingGzip`: verified on local `HttpServer` that client sends `Accept-Encoding: gzip`.
  - `gzipBodyDecompressedAndJsonReadCorrectlyOver100Kb`: verified decompression and JSON parsing on a >100 KB payload, showing transfer size reduction of >80%.
  - `plainBodyReturnedUnchanged`: verified uncompressed responses pass through unchanged.
  - `compressionHeadersRemovedFromWrappedResponseAndUppercaseHandled`: verified `Content-Encoding` and `Content-Length` removal; tested uppercase `Content-Encoding: GZIP`.
  - `callerProvidedAcceptEncodingHeaderNotOverriddenAndIdentityPassesThrough`: verified caller's `Accept-Encoding: identity` is not overridden and identity response passes through untouched.
  - `emptyBodyWithGzipEncodingOn204Or304OrErrorDoesNotThrow`: verified 204, 205, 304, 500, and 200 with `Content-Length: 0` are not wrapped and do not throw on read or close.
  - `truncatedGzipBodyThrowsIoExceptionOnRead`: verified truncated gzip streams throw `IOException` on read.
  - `closeIsIdempotentAndClosesDelegateOnce`: verified delegate `close()` is called exactly once both when body was read and when body was never read (lazy creation).
  - `gateStillReleasesPermitAfterGzipResponse`: verified sequential calls with `maxConcurrent = 1` across both `GzipInterceptor` and `DeadlockApiGate` succeed without permit leakage or timeout.
  - `matchHistoryEarlyStopReaderWorksOverGzipBody`: verified `DeadlockApiRequests.jsonArrayPrefix` early-stop reader reads 5 elements of a 50-element array over a gzip stream.
- `docs/task-reports/042-gzip-deadlock-api.md` (new): this task report.

## Verification
- Baseline build (`.\mvnw.cmd verify`): 998 tests, 0 failures, 0 errors, 14 skipped (36.4 s).
- Final build (`.\mvnw.cmd verify`): 1008 tests, 0 failures, 0 errors, 14 skipped (37.6 s) (+10 tests).
- `git diff --check`: clean (no whitespace or formatting errors).
- Scope check: only the 3 implementation files listed in the spec plus this report were created or modified. No changes to `DeadlockApiRequests`, adapters, `DeadlockApiGate`, caches, DTOs, `application.yaml`, or `pom.xml`.

### Live Check (Optional)
The optional live check against upstream was executed via a throwaway script calling `GET /v1/assets/ranks` through a `RestClient` configured with `restClientBuilder`:
- Plain response transfer size (`Accept-Encoding: identity`): **37,220 bytes**.
- Gzip response transfer size (`Accept-Encoding: gzip`): **1,790 bytes**.
- Measured bandwidth reduction: **95.2%**.
- Upstream `Content-Encoding` received: `gzip`.
- Parsing result: Parsed successfully into 12 `RankAsset` records (tier 0 through 11).

## Out-of-Scope and Follow-ups
- **Conditional requests (`If-Modified-Since` / 304)**: Upstream returns `Last-Modified` and responds with 304 for assets, but supporting this would require keeping expired entities in cache and revalidating them asynchronously. With gzip reducing transfer sizes by ~95%, the bandwidth and latency savings of conditional caching are significantly diminished relative to the complexity.
- **Disambiguating 503 from 404 for unknown matches**: Upstream returns HTTP 503 with body "Steam is busy" for nonexistent match IDs, making it indistinguishable from actual Steam downtime. 503 retry rules remain unchanged.
- **Brotli**: Upstream supports Brotli, but the standard JDK runtime has no built-in Brotli decoder. Using standard `java.util.zip.GZIPInputStream` avoids adding external native/JNI dependencies.

## Deviations
None.

## Pending Integration into PROJECT_CONTEXT.md
- Gzip compression negotiation (`Accept-Encoding: gzip`) and transparent decompression enabled across all deadlock-api client calls via `GzipInterceptor` installed on `DeadlockApiConfig.restClientBuilder`.
- On decompressed gzip responses, `Content-Encoding` and `Content-Length` headers are stripped from the response wrapper so streaming readers (`DeadlockApiRequests.bytes`) accurately enforce memory limits on uncompressed byte counts.
- Interceptor chain order on the shared `deadlockApiRestClient` bean: `GzipInterceptor` (outer) -> `DeadlockApiGate` (inner). Concurrency gate permits are released idempotently upon closing the response wrapper.
- Test count on `verify`: 1008 tests, 0 failures, 14 skipped.
