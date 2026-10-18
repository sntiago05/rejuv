# Task 041: Concurrent command handling (virtual-thread event pool) and a global deadlock-api request gate

- **Branch**: `feature/041-concurrent-commands`
- **Implementer**: Antigravity
- **Related ADRs**: ADR-0001 (virtual threads were intended)
- **Depends on**: 039 merged (done). Runs in parallel with task 040; both touch `src/main/resources/application.yaml` only in different sections, but update with `main` and re-run `verify` before merging, whichever merges second.
- **Evidence**: `docs/task-reports/039-performance-multiuser.md` ("Findings" on threads and "Needs owner decision" 1). Owner decision: option A (event pool on virtual threads plus a global gate in the deadlock-api client path).

## Goal
Today no JDA event pool is configured, so each event runs on the thread that delivers it (verified in JDA 5.6.1 for the handling path; the gateway-thread part is JDA's documented default). While one `/analyze` runs, other users' commands cannot be acknowledged inside Discord's 3 s window. After this task commands run concurrently, and a global gate keeps the number of simultaneous deadlock-api requests low so the rule "never fire parallel uncached analytics requests" still holds across users.

## Files to create or modify
| File | Change |
|---|---|
| `src/main/java/sntiago05/rejuv/config/JdaConfig.java` | Configure the event pool. |
| `src/main/java/sntiago05/rejuv/config/DeadlockApiConfig.java` | Build the shared `RestClient` bean with the gate interceptor; `restClientBuilder(DeadlockApiProperties)` keeps its signature and behavior. |
| `src/main/java/sntiago05/rejuv/config/DeadlockApiGateProperties.java` | New: `@ConfigurationProperties("deadlock-api.gate")` record. |
| `src/main/java/sntiago05/rejuv/clients/DeadlockApiGate.java` | New: the concurrency gate and its `ClientHttpRequestInterceptor`. |
| `src/main/resources/application.yaml` | Add the `deadlock-api.gate` keys (below). |
| `src/test/java/sntiago05/rejuv/clients/DeadlockApiGateTest.java` | New. |
| `src/test/java/sntiago05/rejuv/config/JdaConfigTest.java` | New. |
| `src/test/java/sntiago05/rejuv/config/DeadlockApiGatePropertiesTest.java` | New (binding and defaults), following `DeadlockApiPropertiesTest`. |
| `docs/task-reports/041-concurrent-commands.md` | New: task report. |

No other file. Do **not** change `DeadlockApiRequests` or any client constructor (the gate is installed once, on the shared `RestClient`, so the three adapters and all their tests keep their signatures), the caches, the commands, the services, the Anthropic client, or `spring.threads.virtual.enabled` (web server threads are out of scope).

## Interfaces / signatures

### DeadlockApiGateProperties
```java
@ConfigurationProperties("deadlock-api.gate")
public record DeadlockApiGateProperties(
        @DefaultValue("1") int maxConcurrent,
        @DefaultValue("20s") Duration acquireTimeout) { }
```
Validation in the compact constructor: `maxConcurrent >= 1`, `acquireTimeout` positive; otherwise `IllegalArgumentException`. Register it with `@EnableConfigurationProperties` on `DeadlockApiConfig`. `application.yaml` gets:
```yaml
deadlock-api:
  gate:
    max-concurrent: 1      # simultaneous in-flight deadlock-api requests, all adapters together
    acquire-timeout: 20s   # how long a request waits for its turn before failing
```
(The default of 1 respects AGENTS.md: never parallel uncached analytics requests. The owner can raise it later; the value is documented in the report with the reasoning: 200 requests/minute limit.)

### DeadlockApiGate
```java
public final class DeadlockApiGate implements ClientHttpRequestInterceptor {
    public DeadlockApiGate(DeadlockApiGateProperties properties) ...
    @Override ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution) throws IOException
}
```
Behavior of `intercept`:
1. Acquire a permit from a **fair** `java.util.concurrent.Semaphore(maxConcurrent, true)` with `tryAcquire(acquireTimeout)`. Interruption: restore the interrupt flag and throw `IOException` (the client already maps I/O errors to `DeadlockApiUnavailableException`). Timeout: throw `IOException("deadlock-api gate timeout")` after logging one WARN (no URL query, no account ids; only the path and the waited time).
2. Execute the request. If `execute` throws, release the permit and rethrow.
3. Return the response wrapped so the permit is released **exactly once** when the response is closed (the body is read inside the client's exchange callback, so the permit also covers the body download). Idempotent release (an `AtomicBoolean`), safe if `close()` is called twice.
4. The 503 `Retry-After` sleep happens in `DeadlockApiRequests` outside any request, so it does not hold a permit (leave that code as is).
The class is a plain object (no Spring annotations); `DeadlockApiConfig` creates one instance and passes it to the `RestClient` builder via `.requestInterceptor(gate)` in the bean method only.

### JdaConfig
Add a package-private static `ExecutorService eventPool()` returning `Executors.newVirtualThreadPerTaskExecutor()` and use it: `JDABuilder.createLight(...).setEventPool(eventPool(), true).addEventListeners(commandRouter).build()` (`true` = JDA shuts the pool down with the JDA instance). Keep the existing bean conditions and `destroyMethod`.

## Behavior
- Two slash commands that arrive together are handled in parallel; a long `/analyze` no longer delays other users' acknowledgements.
- At most `max-concurrent` deadlock-api requests are in flight at any time across all commands and adapters (JSON metadata, raw metadata, analytics, assets). Requests beyond that wait in arrival order; a request that waits longer than `acquire-timeout` fails as `DeadlockApiUnavailableException` (the existing "data source busy" path), never hangs.
- Caching is unchanged. `@Cacheable(sync = true)` already coalesces identical in-flight loads, so concurrent identical requests produce one upstream call.
- Known consequence to state in the report: per-user event ordering is no longer guaranteed (`/link` immediately followed by `/analyze` can interleave); the effect is a "not linked" refusal at worst.
- Database: Hikari keeps its default pool; short queries only. Do not change the pool size.

## Out of scope
- A concurrency cap or queue for Anthropic calls, a per-user cooldown or rate limit, a "busy" reply, bulkheads per command (report these as follow-ups with your recommendation if you see a risk).
- `spring.threads.virtual.enabled`, moving `deferReply` earlier, `getPlayerRank` skip, indexes, PNG settings (other owner decisions).
- Any change to cache lifetimes or providers (task 040 and earlier).

## Tests
- `DeadlockApiGateTest` (no network; fake `ClientHttpRequestExecution` returning a response whose `close()` is observable):
  - with `maxConcurrent=1`, two virtual threads calling `intercept` run one after the other (assert max observed concurrency 1) and both complete;
  - with `maxConcurrent=2`, up to 2 run together, never 3;
  - the permit is released when the wrapped response is closed, when `execute` throws, and only once if `close()` is called twice;
  - a waiter that exceeds `acquireTimeout` gets an `IOException` and does not consume a permit; the next caller can proceed after the holder closes;
  - interruption while waiting restores the interrupt flag and throws `IOException`;
  - FIFO fairness: three queued callers are served in arrival order (use latches, no sleeps longer than needed; the test must not be flaky).
- `JdaConfigTest`: `eventPool()` returns an executor whose tasks run on virtual threads (`Thread.currentThread().isVirtual()`), and two tasks that block on a shared latch run concurrently (proves no single-thread serialization); the pool is shut down by the test.
- `DeadlockApiGatePropertiesTest`: defaults `1` and `20s` when absent; custom values bind; `0` permits and a zero or negative timeout are rejected.
- An integration-style test in `DeadlockApiGateTest` using the real `RestClient` built like `DeadlockApiConfig` does (gate installed) against `MockRestServiceServer` is **not** required if the mock server bypasses interceptors; if it does, say so in the report and rely on a `JdkClientHttpRequestFactory` against a local `com.sun.net.httpserver.HttpServer` with a delayed handler to show that N parallel `RestClient` calls are serialized by the gate (max observed server-side concurrency equals `maxConcurrent`). That local-server test is the load test requested by the 039 report; keep it fast (under 3 s) and deterministic.
- Existing tests must pass unchanged.

## Verification
`.\mvnw.cmd verify` (Docker running); report baseline and final test counts. Manual check (state in the report whether it was run): start the bot, fire `/analyze` from one account and, while it runs, `/ping` or `/help` from another or the same account; the second must answer immediately. The log shows no `deadlock-api gate timeout` under normal use.

## Documentation
Task report `docs/task-reports/041-concurrent-commands.md`: what changed; measured evidence from the local-server test; the capacity reasoning with its assumptions (permits, request latency, 200 requests/minute); the per-user ordering consequence; follow-ups (Anthropic cap, per-user cooldown, `getPlayerRank` skip). Do not edit `docs/PROJECT_CONTEXT.md` while other worktrees are active; list the facts to add under "Pending integration".

## Acceptance criteria
- [ ] Compiles, all tests pass (`.\mvnw.cmd verify`)
- [ ] Unit tests for the gate, the pool and the properties as listed; no flaky timing
- [ ] No client constructor, command, service, cache or Anthropic code changed
- [ ] No user-facing text added; follows AGENTS.md conventions
- [ ] Report states whether the manual two-account check was run
