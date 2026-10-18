# Task 041: Concurrent command handling (virtual-thread event pool) and global deadlock-api request gate

## Status
Implemented on `feature/041-concurrent-commands`. `.\mvnw.cmd verify` passes: 994 tests, 0 failures, 0 errors, 14 skipped (baseline: 978 tests, 0 failures, 14 skipped). Not pushed, not merged.

## Summary
- **Virtual-thread event pool in JDA**: Configured `JDABuilder.setEventPool(eventPool(), true)` in `JdaConfig` using `Executors.newVirtualThreadPerTaskExecutor()`. Incoming Discord slash commands and events now execute on lightweight virtual threads rather than serializing on JDA's single gateway thread. Commands can acknowledge (`deferReply()`) inside Discord's 3-second window even while long-running commands (such as `/analyze`) are executing.
- **Global deadlock-api concurrency gate**: Created `DeadlockApiGate`, a `ClientHttpRequestInterceptor` backed by a fair `Semaphore(maxConcurrent, true)` with `acquireTimeout`. It is installed on the shared `RestClient` bean in `DeadlockApiConfig`. All deadlock-api HTTP calls (analytics, metadata, history, assets) share this gate.
- **Fair queueing and response wrapping**: Callers acquire permits in strict FIFO order. Permits are held while the response body is consumed inside `RestClient`'s exchange callback, and released idempotently (via `AtomicBoolean`) when `ClientHttpResponse.close()` is called or if execution throws.
- **Timeout and safety**: If a request waits longer than `acquire-timeout` (default 20s), it logs a single WARN (with sanitized path and waited time, stripping query parameters and masking digits so no account or match IDs are logged) and throws `IOException("deadlock-api gate timeout")`, which existing clients map to `DeadlockApiUnavailableException`.

## Important Files
- `src/main/java/sntiago05/rejuv/config/JdaConfig.java`: added package-private `eventPool()` returning a virtual-thread-per-task executor; configured `.setEventPool(eventPool(), true)` on `JDABuilder`.
- `src/main/java/sntiago05/rejuv/config/DeadlockApiGateProperties.java` (new): `@ConfigurationProperties("deadlock-api.gate")` record with defaults (`maxConcurrent = 1`, `acquireTimeout = 20s`) and constructor validation.
- `src/main/java/sntiago05/rejuv/clients/DeadlockApiGate.java` (new): `ClientHttpRequestInterceptor` implementing fair semaphore acquisition, timeout logging, error handling, and response-close permit release wrapping.
- `src/main/java/sntiago05/rejuv/config/DeadlockApiConfig.java`: registered `DeadlockApiGateProperties` via `@EnableConfigurationProperties` and attached `DeadlockApiGate` to the `deadlockApiRestClient` bean. `restClientBuilder` signature and behavior preserved.
- `src/main/resources/application.yaml`: added `deadlock-api.gate.max-concurrent: 1` and `acquire-timeout: 20s`.
- Tests:
  - `src/test/java/sntiago05/rejuv/clients/DeadlockApiGateTest.java` (new, 9 tests): serialization at `maxConcurrent=1`, concurrency at `maxConcurrent=2`, permit release on response close (idempotent), permit release when `execute` throws, timeout without permit leak, thread interrupt handling and flag restoration, FIFO queue fairness order `[A, B, C]`, `MockRestServiceServer` interceptor invocation check, and local `HttpServer` load test.
  - `src/test/java/sntiago05/rejuv/config/JdaConfigTest.java` (new, 1 test): verified `eventPool()` tasks run on virtual threads and execute concurrently without single-thread serialization.
  - `src/test/java/sntiago05/rejuv/config/DeadlockApiGatePropertiesTest.java` (new, 6 tests): verified YAML default binding, absent default binding, custom property binding, and rejection of 0 permits or non-positive timeouts.
- `docs/task-reports/041-concurrent-commands.md` (new): this task report.

## Measured Evidence (Local Server Load Test)
- Verified in `DeadlockApiGateTest.localHttpServerSerializesParallelRestClientCalls`: 4 parallel `RestClient` calls issued on virtual threads against a local `com.sun.net.httpserver.HttpServer` with a 50 ms artificial delay handler.
- Measured server-side concurrency: `maxServerConcurrency` was exactly 1 (equals `maxConcurrent = 1`), with the 4 calls completing sequentially in ~200 ms total. This proves that the gate serializes parallel requests across threads before hitting the network.
- `MockRestServiceServer` test: Verified in `mockRestServiceServerHonorsInterceptors` that Spring's `DefaultRestClient` wraps `MockClientHttpRequestFactory` in `InterceptingClientHttpRequestFactory` when interceptors are present, executing `DeadlockApiGate` as expected.

## Capacity Reasoning and Assumptions
- *Assumptions:*
  - deadlock-api upstream limit: 200 requests/minute per IP.
  - Upstream request latency: 150-600 ms (median ~300 ms).
  - Cold `/analyze` calls: 12 deadlock-api requests (6 analytics). Warm hero/rank caches: ~3-4 requests per analysis (history, rank, metadata).
  - Gate concurrency: `maxConcurrent = 1`.
- *Throughput analysis:*
  - With `maxConcurrent = 1` and an average round-trip of 300 ms, the gate serves at most ~3.3 requests/second = ~200 requests/minute. Thus, `maxConcurrent = 1` naturally acts as a ceiling that respects deadlock-api's 200 req/min rate limit without overwhelming upstream infrastructure or triggering 429/503 responses.
  - For warm-cache analyses (~3 calls each), this allows up to ~66 analyses per minute bot-wide.
  - Under burst conditions, excess requests queue in arrival order for up to 20 seconds (`acquire-timeout`), failing gracefully with `DeadlockApiUnavailableException` if wait times exceed the cap rather than hanging indefinitely.

## Per-User Ordering Consequence
- Because JDA events are now dispatched onto virtual threads, per-user command execution ordering is no longer strictly guaranteed across millisecond arrival intervals.
- If a user sends `/link` followed immediately by `/analyze` within milliseconds, `/analyze` could theoretically begin executing before `/link` commits in PostgreSQL.
- Consequence: at worst, the user receives an ephemeral "not linked" response and retries once `/link` finishes. Under human interaction timings (seconds between commands), this condition is negligible.

## Follow-ups and Recommendations
- **Anthropic concurrency cap**: Currently Haiku AI calls run without an explicit gate across virtual threads. Under heavy concurrent command bursts, simultaneous AI calls could hit Anthropic rate limits. A lightweight semaphore or queue for Anthropic calls is recommended if traffic grows.
- **Per-user cooldown**: Without a per-user rate limit, a single user could spam `/analyze`, monopolizing the gate queue and causing other users' requests to time out. A per-user cooldown (e.g. 1 analysis per user per minute) is recommended.
- **Skip `getPlayerRank` in `AnalyzeService`**: For ranked matches with an existing badge, `MetricsService.benchmarkRank` uses the badge and does not require the player's current rank. Skipping this call saves 1 of the cold requests (~8%), reducing gate queue time.

## Verification
- Baseline build (`.\mvnw.cmd verify`): 978 tests, 0 failures, 0 errors, 14 skipped (47.4 s).
- Final build (`.\mvnw.cmd verify`): 994 tests, 0 failures, 0 errors, 14 skipped (39.0 s) (+16 tests).
- `git diff --check`: clean (no whitespace or formatting warnings).
- Scope check: only the 8 implementation files in the task spec table plus this report were created or modified.
- No client constructor, command, service, cache or Anthropic code was modified.
- No user-facing messages or text keys were added.
- **Manual two-account check**: Not run. No live Discord bot token or second test Discord account was available in this non-interactive terminal environment. Thread concurrency and gate serialization were verified deterministically via unit and integration tests.

## Deviations
None.

## Known Issues and Merge Risks
- Parallel worktree `feature/040-cache-freshness-and-ai-input` is active and touches `src/main/resources/application.yaml` in another section. Whichever branch merges second should update with `main` and re-run verify.
- Per `AGENTS.md` parallel worktree rules, `docs/PROJECT_CONTEXT.md` was not directly modified.

## Pending Integration into PROJECT_CONTEXT
- JDA event pool configured on virtual threads (`JDABuilder.setEventPool(eventPool(), true)` in `JdaConfig`). Commands run concurrently; acknowledgements no longer serialize on the gateway thread.
- Global deadlock-api concurrency gate (`DeadlockApiGate`) installed on shared `deadlockApiRestClient` (`maxConcurrent = 1`, `acquireTimeout = 20s`). Requests queue in FIFO order; timeouts throw `IOException("deadlock-api gate timeout")` after logging masked paths without account IDs.
- Per-user command ordering across millisecond windows is no longer strictly guaranteed.
- Test count on `verify`: 994 tests, 0 failures, 14 skipped.
