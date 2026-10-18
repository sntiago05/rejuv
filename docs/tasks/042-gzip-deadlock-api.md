# Task 042: Request and decode gzip on the deadlock-api client

- **Branch**: `feature/042-gzip-deadlock-api`
- **Implementer**: Antigravity
- **Related ADRs**: none
- **Depends on**: 041 merged (done). Touches `DeadlockApiConfig`, the same file as 041.
- **Evidence** (local, gitignored): `docs/evidence/api-analysis/03-call-efficiency.md` sections 1 and 3 (`probe_log.json` probes 4-6, `TestCompression.java`).

## Goal
Today the shared deadlock-api `RestClient` is built on `java.net.http.HttpClient`, which neither sends `Accept-Encoding` nor decompresses responses, so every body arrives as plain JSON (verified in the evidence: `GET /v1/assets/items` is 6,378,476 B plain and 505,349 B with `Accept-Encoding: gzip`; the server honors gzip). After this task the client asks for gzip and transparently decompresses it, cutting the transfer of the heavy endpoints (assets, metadata, match history, counter stats) by roughly 85-92% with no change in behavior.

## Files to create or modify
| File | Change |
|---|---|
| `src/main/java/sntiago05/rejuv/clients/GzipInterceptor.java` | New: `ClientHttpRequestInterceptor` (below). |
| `src/main/java/sntiago05/rejuv/config/DeadlockApiConfig.java` | Install the interceptor in `restClientBuilder(DeadlockApiProperties)` (keep the signature and the existing gate installation in the bean method). |
| `src/test/java/sntiago05/rejuv/clients/GzipInterceptorTest.java` | New. |
| `docs/task-reports/042-gzip-deadlock-api.md` | New: task report. |

No other file. Do **not** change `DeadlockApiRequests`, the adapters, `DeadlockApiGate`, caches, DTOs, `application.yaml`, or `pom.xml` (no new dependency: use `java.util.zip.GZIPInputStream`). Do not add conditional requests (304) or 503/404 handling: both were evaluated and are out of scope (see "Out of scope").

## Interfaces / signatures
```java
public final class GzipInterceptor implements ClientHttpRequestInterceptor {
    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException
}
```
Behavior of `intercept`:
1. Set `Accept-Encoding: gzip` on the request headers unless the request already has an `Accept-Encoding` header (do not override a caller's choice). Request `gzip` only: Brotli is smaller but the JDK cannot decode it.
2. Execute the request.
3. If the response header `Content-Encoding` equals `gzip` (case-insensitive, trimmed) **and** the status is 2xx **and** the response has a body (status is not 204/205/304 and `Content-Length` is not `0`), return a wrapper whose:
   - `getBody()` returns a `GZIPInputStream` over the delegate body, created lazily on the first call and cached, so a caller that never reads the body never touches it;
   - `getHeaders()` returns a copy of the headers **without** `Content-Encoding` and **without** `Content-Length` (the length is the compressed one; `DeadlockApiRequests.bytes(maxBytes)` compares `Content-Length` to its limit and must count the decompressed bytes through its streaming loop instead);
   - `getStatusCode()`, `getStatusText()` delegate; `close()` closes the `GZIPInputStream` if it was created, then the delegate, exactly once (idempotent).
4. Any other response (no gzip, error statuses, no body) is returned untouched.
5. A corrupt or truncated gzip stream surfaces as the `IOException` thrown by `GZIPInputStream` while reading; do not catch it (the existing code already maps I/O errors while reading the body to `DeadlockApiUnavailableException`).

The class is a plain object (no Spring annotations).

### DeadlockApiConfig
In `restClientBuilder(DeadlockApiProperties)` add `.requestInterceptor(new GzipInterceptor())` so every consumer of the builder (the bean, `MockRestServiceServer` tests, any other adapter) gets it. The bean method keeps adding the gate afterwards; the resulting order is gzip outermost, gate inside it, which is fine: the gate releases its permit when the wrapped response is closed and the gzip wrapper delegates `close()`. State the resulting order in the report after checking it in the code.

## Behavior
- Every deadlock-api GET sends `Accept-Encoding: gzip`; compressed bodies are decompressed before any adapter sees them; uncompressed responses (including the raw Zstandard metadata, whose body is not `Content-Encoding: gzip`) pass through unchanged.
- The streaming prefix reader for the match history (`jsonArrayPrefix`, stops after 200 elements) keeps working over the decompressed stream and still stops early.
- The memory guard on the raw metadata (`bytes(maxBytes)`) keeps working: it no longer sees a `Content-Length` for gzip responses and enforces the limit while reading.
- No cache, TTL, retry, gate or error-mapping behavior changes.

## Out of scope
- Conditional requests (`If-Modified-Since` / 304). The evidence shows upstream sends `Last-Modified` and answers 304 on assets, but using it requires keeping stale values past their TTL and revalidating on expiry, which is a cache redesign with much less benefit once gzip is on. Report it as a possible follow-up only.
- Disambiguating 503 from 404 for unknown matches: the evidence shows upstream answers 503 with the same body ("Steam is busy") for a nonexistent match, so the two cannot be told apart safely. Do not change the 503 retry rules.
- Brotli, HTTP/2 tuning, bulk endpoints, asset pre-warming, any change to Anthropic or Discord clients.

## Tests
`GzipInterceptorTest` (JUnit 5 + AssertJ), with a local `com.sun.net.httpserver.HttpServer` (like `DeadlockApiGateTest` does) and a `RestClient` built with `DeadlockApiConfig.restClientBuilder(...)`, plus unit tests of the interceptor with a fake `ClientHttpRequestExecution`:
- the request carries `Accept-Encoding: gzip` (assert on the server side);
- a gzip body is decompressed and the JSON is read correctly through a `RestClient` call; the decompressed content equals the original (use a body of at least 100 KB so the saving is visible);
- a plain body (no `Content-Encoding`) is returned unchanged;
- `Content-Encoding` and `Content-Length` are removed from the wrapped response headers; `Content-Encoding: GZIP` (uppercase) is also handled;
- a caller-provided `Accept-Encoding` header is not overridden and an `identity` response passes through;
- 204 / 304 / error status with `Content-Encoding: gzip` and an empty body does not throw;
- a truncated gzip body throws `IOException` on read;
- `close()` is idempotent and closes the delegate once; the gate still releases its permit after a gzip response (build the client with both interceptors and run two sequential calls with `maxConcurrent=1`);
- the match-history early-stop reader still works over a gzip body (read a prefix of a long array and assert the result size).
Existing tests must pass unchanged. Do not use `Thread.sleep` for synchronization.

## Verification
`.\mvnw.cmd verify` (Docker running); report baseline and final test counts. Live check (optional, at most 3 sequential requests, never `.env` contents): one `GET /v1/assets/ranks` through a small throwaway `RestClient` built with `restClientBuilder` showing the response parsed and the transferred size gzip vs plain; say in the report whether it was run. Report the measured sizes only if you measured them.

## Documentation
Task report `docs/task-reports/042-gzip-deadlock-api.md`: what changed, the interceptor order, test counts, whether the live check ran, and the follow-up notes above. Do not edit `docs/PROJECT_CONTEXT.md` (other worktrees may be active): list the facts to add under "Pending integration" (gzip is now negotiated and decompressed; `Content-Length` is absent on decompressed responses).

## Acceptance criteria
- [ ] Compiles, all tests pass (`.\mvnw.cmd verify`)
- [ ] Unit tests as listed; no flaky timing
- [ ] No adapter, cache, DTO, `DeadlockApiRequests`, gate or `pom.xml` change
- [ ] Follows AGENTS.md conventions; no user-facing text added
- [ ] Report states whether the live check ran
