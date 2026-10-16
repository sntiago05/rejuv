# Task 004: `DeadlockApiClient` (match history, match metadata, benchmarks, assets, caching, 503 handling)

- **Branch**: `feature/004-deadlock-api-client`
- **Implementer**: Antigravity
- **Related ADRs**: ADR-0004, ADR-0010 (also ADR-0002 for Caffeine)

## Goal
Give the bot a single, cached, well-tested gateway to deadlock-api.com: a player's match history (which also serves as the "account has Deadlock data" check), full match metadata, percentile benchmarks for a hero and rank tier, and the rank/hero assets. Failures (503 with `Retry-After`, rate limits, timeouts, bad responses) are translated into two small exception types that later tasks map to user-facing messages. No command, service or database code is added.

## Context (verified against the repository when this spec was written)
Do not repeat this discovery. If a fact below is false in your checkout, stop and ask.

- Plan entry: `docs/implementation-plan.md`, task 004, "`DeadlockApiClient`: match history, account check, ranks/heroes assets, caching, 503 handling", depends on 001, implementer Antigravity. **Scope decision (owner, this planning session):** the task also covers match metadata and the benchmarks endpoint, because ADR-0010 assigns them to `DeadlockApiClient` and no later task owns them (Task 007 is pure logic and depends only on 004; Tasks 005, 006, 007, 009, 011-013 consume this client).
- No task spec or report for 004 exists yet. Tasks 001, 002 and 003 are in `main` (the owner confirmed the Task 003 merge after the first draft of this spec). The facts below about `pom.xml` and `application.yaml` were read from the Task 003 tree and include its changes; re-check them on your fresh branch from `main`. Task 004 depends only on Task 001, and **no new test of this task may need a database or Docker**. Existing `@SpringBootTest` tests do need Docker (Testcontainers), so Docker must be running for `verify`.
- `pom.xml` (Spring Boot parent `4.1.1`, Java 21) has `spring-boot-starter`, `spring-boot-starter-cache`, `com.github.ben-manes.caffeine:caffeine`, `spring-boot-starter-validation`, `spring-boot-starter-test` (test), JDA 5.0.0 and the JPA/Flyway/PostgreSQL dependencies. It has **no** HTTP client starter, so `RestClient` and Jackson are not guaranteed on the classpath.
- `application.yaml` has `spring.main.web-application-type: none` (keep it), `spring.docker.compose.enabled: false`, the `bot:` block, and (from Task 003) datasource/JPA keys. There is no `clients/` package, no `@EnableCaching`, no `CacheManager`, no `src/test/resources/fixtures/`.
- Existing conventions to follow: `BotProperties` is a `@ConfigurationProperties("bot")` record registered through `@EnableConfigurationProperties` in `JdaConfig`; `Messages` is the only access to user-facing text; services and clients are Discord-agnostic (no JDA types).
- Rules from `AGENTS.md` that apply here: external DTOs are records with `@JsonIgnoreProperties(ignoreUnknown = true)` and only the fields we use; every read is cached; on HTTP 503 honor `Retry-After` once then fail gracefully; never fire parallel uncached analytics requests; constructor injection, `final` fields, no Lombok; test HTTP clients with `MockRestServiceServer` and JSON fixtures in `src/test/resources/fixtures/`; no secrets or real user data in the repo.
- ADR-0010: hand-written client on Spring `RestClient`; caches: assets 24 h, benchmarks 6 h, match history 2 min (profiles 10 min belongs to Task 006); analytics endpoints share a 200 req/min per IP limit; busy queries return 503 with `Retry-After`; "exact endpoint paths are confirmed against `https://api.deadlock-api.com/openapi.json` in task 004".
- `docs/mvp-spec.md`: `/link` verifies the account "has Deadlock match history via the API"; benchmarks are "same hero, same rank tier (badge range `tier*10` to `tier*10+9`), last 30 days, percentiles p25/p50/p75 for each metric, cached 6 h per (hero, tier)"; the metric set needs match history stats, net worth at 10/20 min, item purchase times and (since build 6712) permanent buff pickups.
- What could be read of the OpenAPI document from the planning environment (only the first ~100,000 of 103,099 characters were readable, and the match/player/metrics sections were not in the readable part): server `https://api.deadlock-api.com`; `GET /v1/assets/ranks` and `GET /v1/assets/heroes` exist on that same host; badge filters are integers (tier = leading digits, subtier = last digit, range 0-116); analytics endpoints share 200 req/min per IP; load shedding returns `503` + `Retry-After` ("wait that many seconds, do not send many uncached requests in parallel"). Everything else in "Upstream contract" below is **unverified**.

## Step 0 - confirm the upstream contract (do this before writing code)
The owner is collecting the relevant OpenAPI sections and sample responses; when they are attached to the task, they are the confirmed contract and Step 0 only checks them against the table below. Otherwise download/read `https://api.deadlock-api.com/openapi.json` (and, if useful, make a few real read-only requests) and confirm every item in the "Upstream contract" table: path, query parameters, response shape, field names and types, the meaning of `match_result`/team fields, and the status code returned for an account or match with no data. Then:

1. Write the confirmed contract into the task report (section "Confirmed upstream contract").
2. If a candidate path or field differs from the table, use the **confirmed** one; the Java signatures in "Interfaces / signatures" (method names, parameter types, return types, exception types) do not change. Record each difference as a deviation in the report.
3. **Stop and ask** only if an endpoint does not exist or cannot provide the data described (for example no endpoint exposes item purchase times), because that changes scope.
4. Record, without adding code for them: which `game_mode`/`match_mode` the benchmarks endpoint includes by default, and where (if anywhere) permanent buff pickups appear in the match metadata. These are inputs for Tasks 007/010.

## Upstream contract (candidates, host `https://api.deadlock-api.com`)
| Purpose | Candidate | Fields we need (candidate JSON names) | Status |
|---|---|---|---|
| Match history | `GET /v1/players/{account_id}/match-history` | `match_id`, `hero_id`, `start_time`, `match_duration_s`, `match_result`, `player_team`, `player_kills`, `player_deaths`, `player_assists`, `net_worth`, `last_hits`, `denies` | Unverified |
| Match metadata | `GET /v1/matches/{match_id}/metadata` | match-level: duration, winning team / outcome, start time; per player: `account_id`, `hero_id`, `team`, `kills`, `deaths`, `assists`, `last_hits`, `denies`, `net_worth`, `level`, `items[]` (`item_id`, `game_time_s`, `sold_time_s`), `stats[]` time series (`time_stamp_s`, `net_worth`, `player_damage`) | Unverified (the raw response may wrap everything in a `match_info` object) |
| Benchmarks | `GET /v1/analytics/player-stats/metrics?hero_ids=<id>&min_badge_level=<tier*10>&max_badge_level=<tier*10+9>&min_unix_timestamp=<now-30d>` | response keyed by metric name; per metric `avg`, `percentile25`, `percentile50`, `percentile75` | Unverified |
| Ranks | `GET /v1/assets/ranks` | `tier`, `name` | Path seen in OpenAPI; schema unverified |
| Heroes | `GET /v1/assets/heroes` | `id`, `name` | Path seen in OpenAPI; schema unverified |

## Files to create or modify
| File | Change |
|---|---|
| `pom.xml` | **Modify.** Add exactly one compile-scope dependency, version managed by the Spring Boot BOM (no `<version>`): `org.springframework.boot:spring-boot-starter-restclient` (brings `RestClient` and Jackson). Change nothing else. If it does not resolve, or the test classes in this spec still do not compile (for example `MockRestServiceServer` not on the test classpath), stop and ask; do not add an explicit version, another HTTP library or a generated client. |
| `src/main/resources/application.yaml` | **Modify.** Append the `deadlock-api:` block defined in "Configuration". Keep every existing key unchanged. |
| `src/main/java/sntiago05/rejuv/config/DeadlockApiProperties.java` | **Create.** |
| `src/main/java/sntiago05/rejuv/config/DeadlockApiConfig.java` | **Create.** Beans: `RestClient`, `Clock`, `Sleeper`; registers `DeadlockApiProperties`. |
| `src/main/java/sntiago05/rejuv/config/CacheConfig.java` | **Create.** `@EnableCaching`, cache name constants, Caffeine `CacheManager` with per-cache TTL. |
| `src/main/java/sntiago05/rejuv/clients/DeadlockApiClient.java` | **Create.** |
| `src/main/java/sntiago05/rejuv/clients/DeadlockApiException.java` | **Create.** |
| `src/main/java/sntiago05/rejuv/clients/DeadlockApiUnavailableException.java` | **Create.** |
| `src/main/java/sntiago05/rejuv/clients/Sleeper.java` | **Create.** |
| `src/main/java/sntiago05/rejuv/clients/dto/MatchHistoryEntry.java` | **Create.** |
| `src/main/java/sntiago05/rejuv/clients/dto/MatchDetails.java` | **Create.** (nested records) |
| `src/main/java/sntiago05/rejuv/clients/dto/MetricPercentiles.java` | **Create.** |
| `src/main/java/sntiago05/rejuv/clients/dto/RankAsset.java` | **Create.** |
| `src/main/java/sntiago05/rejuv/clients/dto/HeroAsset.java` | **Create.** |
| `src/test/java/sntiago05/rejuv/clients/DeadlockApiClientTest.java` | **Create.** |
| `src/test/java/sntiago05/rejuv/clients/DeadlockApiClientCachingTest.java` | **Create.** |
| `src/test/java/sntiago05/rejuv/config/DeadlockApiPropertiesTest.java` | **Create.** |
| `src/test/resources/fixtures/deadlock-api/match-history.json` | **Create.** |
| `src/test/resources/fixtures/deadlock-api/match-history-empty.json` | **Create.** (`[]`) |
| `src/test/resources/fixtures/deadlock-api/match-metadata.json` | **Create.** |
| `src/test/resources/fixtures/deadlock-api/player-stats-metrics.json` | **Create.** |
| `src/test/resources/fixtures/deadlock-api/assets-ranks.json` | **Create.** |
| `src/test/resources/fixtures/deadlock-api/assets-heroes.json` | **Create.** |
| `docs/task-reports/004-deadlock-api-client.md` | **Create** at closeout (AGENTS.md), following `docs/task-reports/002-i18n-and-help.md`. |
| `docs/PROJECT_CONTEXT.md` | **Update** at closeout only if no other task branch/worktree is active; otherwise leave it untouched and mark the update "pending integration" in the report (AGENTS.md, parallel tasks). |

Do not touch any other file: `messages.properties`, `CommandRouter`, `PingCommand`, `HelpCommand`, `Messages`, `JdaConfig`, `BotProperties`, `RejuvApplication`, existing tests, ADRs, other specs, `AGENTS.md`, `docs/mvp-spec.md`, `docs/architecture.md`, `README.md`, `.env.example`.

## Interfaces / signatures
All new types are new; none exists today.

### Exceptions (`sntiago05.rejuv.clients`)
```java
public class DeadlockApiException extends RuntimeException {
    public DeadlockApiException(String message);
    public DeadlockApiException(String message, Throwable cause);
}

/** The API is busy, rate limiting us, or unreachable. Callers show a "busy, try again in a minute" message. */
public class DeadlockApiUnavailableException extends DeadlockApiException {
    public DeadlockApiUnavailableException(String message, Duration retryAfter, Throwable cause); // retryAfter and cause may be null
    public Optional<Duration> retryAfter();
}
```
Messages are technical English for logs and never contain user-facing text or request bodies.

### `Sleeper` (`sntiago05.rejuv.clients`)
```java
@FunctionalInterface
public interface Sleeper {
    void sleep(Duration duration) throws InterruptedException;
}
```
Exists so tests never really sleep. The production bean is `Thread::sleep`-based (defined in `DeadlockApiConfig`).

### `DeadlockApiClient` (`sntiago05.rejuv.clients`, annotated `@Component`, non-final class, no final public methods, so Spring cache proxies work)
```java
public DeadlockApiClient(RestClient deadlockApiRestClient, DeadlockApiProperties properties,
                         Clock clock, Sleeper sleeper)

@Cacheable(cacheNames = CacheConfig.DEADLOCK_MATCH_HISTORY, sync = true)
public List<MatchHistoryEntry> getMatchHistory(long accountId)

@Cacheable(cacheNames = CacheConfig.DEADLOCK_MATCH_DETAILS, sync = true)
public Optional<MatchDetails> getMatchDetails(long matchId)

@Cacheable(cacheNames = CacheConfig.DEADLOCK_BENCHMARKS, sync = true, key = "#heroId + ':' + #rankTier")
public Map<String, MetricPercentiles> getBenchmarks(int heroId, int rankTier)

@Cacheable(cacheNames = CacheConfig.DEADLOCK_ASSETS, sync = true, key = "'ranks'")
public List<RankAsset> getRanks()

@Cacheable(cacheNames = CacheConfig.DEADLOCK_ASSETS, sync = true, key = "'heroes'")
public List<HeroAsset> getHeroes()
```
The `@Cacheable` annotations and keys above are part of the contract: the two asset methods share a cache, so the explicit keys are mandatory (default keys would collide).

Semantics:
- `getMatchHistory`: most recent matches of the account, in the order the API returns them (record the order in the report; if the API does not return newest first, sort descending by `startTime` so index 0 is always the latest). Empty list = the API has no matches for the account. This is the "account check" used by Task 005: an account is valid iff the list is not empty. There is deliberately no separate `hasMatchHistory` method (a self-call would bypass the cache proxy).
- `getMatchDetails`: `Optional.empty()` when the API reports the match does not exist (HTTP 404).
- `getBenchmarks`: `rankTier` must be in `0..11` (badge range up to 116 is `tier*10+subtier`), otherwise `IllegalArgumentException` before any HTTP call. Requests `hero_ids=heroId`, `min_badge_level=rankTier*10`, `max_badge_level=rankTier*10+9`, `min_unix_timestamp=<clock now minus 30 days, epoch seconds>`. Returns an immutable map keyed by the API's metric name; the client does not choose or rename metrics (Task 007 does).
- `getRanks`, `getHeroes`: unmodified asset lists as immutable lists.
- Every returned collection is immutable (`List.copyOf`, `Map.copyOf`); cached values must not be mutable.
- Callers must not call the analytics methods in parallel for different uncached keys (document this in the Javadoc of `getBenchmarks`); `sync = true` already prevents duplicate loads for the same key.

### DTOs (`sntiago05.rejuv.clients.dto`)
All are records annotated `@JsonIgnoreProperties(ignoreUnknown = true)` with **explicit** `@JsonProperty("snake_case_name")` on each component (do not rely on a naming-strategy setting). Use `com.fasterxml.jackson.annotation.*` annotations. Component names below are fixed; JSON names, and numeric types if Step 0 proves them different, follow the confirmed contract.
```java
public record MatchHistoryEntry(long matchId, int heroId, long startTime, int matchDurationS,
                                int matchResult, int playerTeam, int playerKills, int playerDeaths,
                                int playerAssists, long netWorth, Integer lastHits, Integer denies) { }

public record MatchDetails(int durationS, int winningTeam, long startTime, List<Player> players) {
    public record Player(long accountId, int heroId, int team, int kills, int deaths, int assists,
                         Integer lastHits, Integer denies, long netWorth, int level,
                         List<Item> items, List<Snapshot> stats) { }
    public record Item(long itemId, int gameTimeS, int soldTimeS) { }
    public record Snapshot(int timeStampS, long netWorth, long playerDamage) { }
}

public record MetricPercentiles(double avg, double p25, double p50, double p75) { }

public record RankAsset(int tier, String name) { }

public record HeroAsset(int id, String name) { }
```
- `MatchDetails` flattens the metadata wrapper (if the response nests everything under an object such as `match_info`, unwrap it in the client or with a private wrapper record; the public type stays as above). Null lists in the response become empty lists.
- `MatchDetails.Player.items` and `.stats` carry only the fields listed. Add buff-pickup fields **only** if Step 0 confirms the metadata exposes them; otherwise leave them for Task 007 and say so in the report.
- Fields documented as optional (`lastHits`, `denies`, `soldTimeS` may be absent) must be tolerated: use boxed types where absence is possible and never fail on a missing optional field.
- Do not interpret meanings here (win/loss, "latest match" selection beyond ordering, souls per minute): that is Task 007.

## Configuration

### `application.yaml` (append; keep everything else)
```yaml
deadlock-api:
  base-url: https://api.deadlock-api.com
  connect-timeout: 3s
  read-timeout: 10s
  default-retry-after: 2s
  max-retry-after: 10s
```

### `DeadlockApiProperties`
```java
@ConfigurationProperties("deadlock-api")
public record DeadlockApiProperties(String baseUrl, Duration connectTimeout, Duration readTimeout,
                                    Duration defaultRetryAfter, Duration maxRetryAfter) { }
```
No API key and no `Authorization` header in this task. No secrets are involved.

### `DeadlockApiConfig`
- `@Configuration` + `@EnableConfigurationProperties(DeadlockApiProperties.class)`.
- `public static RestClient.Builder restClientBuilder(DeadlockApiProperties p)`: `RestClient.builder()` with `baseUrl`, header `Accept: application/json`, header `User-Agent: Rejuv-Discord-Bot` (identifies us to the community API; no version, no contact data), and a `JdkClientHttpRequestFactory` built from `java.net.http.HttpClient` with `connectTimeout` and `setReadTimeout`. Public and static so tests can bind `MockRestServiceServer` to the real builder.
- `@Bean RestClient deadlockApiRestClient(DeadlockApiProperties p)` returns `restClientBuilder(p).build()`. Do not expose a `RestClient.Builder` bean (it would clash with Spring Boot's auto-configured one).
- `@Bean @ConditionalOnMissingBean Clock clock()` returns `Clock.systemUTC()`.
- `@Bean Sleeper sleeper()` returns a lambda calling `Thread.sleep(duration)`.

### `CacheConfig`
```java
@Configuration
@EnableCaching
public class CacheConfig {
    public static final String DEADLOCK_ASSETS = "deadlockAssets";             // 24 h
    public static final String DEADLOCK_BENCHMARKS = "deadlockBenchmarks";     // 6 h
    public static final String DEADLOCK_MATCH_HISTORY = "deadlockMatchHistory"; // 2 min
    public static final String DEADLOCK_MATCH_DETAILS = "deadlockMatchDetails"; // 1 h

    @Bean CacheManager cacheManager() { ... }
}
```
`CacheManager` is a `CaffeineCacheManager` in **static** mode (only the four caches above exist; any other name is not created on demand) with one Caffeine cache per name using `expireAfterWrite` with the TTL in the comments and `maximumSize` 10 (assets), 500 (benchmarks), 1000 (match history), 200 (match details). The match-details TTL (1 h) and the size limits are decisions of this spec: ADR-0010 gives no TTL for match metadata, which is immutable once the match ends; the 1 h value only avoids repeated fetches while a user re-runs a command. Failures and exceptions are never cached (Spring's default behavior; do not change it).

## Behavior
1. **Requests.** Every call is a `GET` on `<base-url><path>` through the shared `RestClient`; path variables and query parameters are encoded by `RestClient` URI templates (never string-concatenated into the URL). Response bodies are mapped to the DTOs; unknown fields are ignored.
2. **Match history / account check.** A non-empty list means the account has data; an empty array means none. HTTP 404 on this endpoint: if Step 0 shows the API documents 404 as "no data for this account", return an empty list; otherwise 404 is an unexpected status and becomes `DeadlockApiException` (point 6). Record which case applies in the report.
3. **Match metadata.** 404 returns `Optional.empty()`; any other non-2xx follows points 4-6.
4. **503 handling (ADR-0010, AGENTS.md).** On HTTP 503, read `Retry-After`: an integer number of seconds is used as given; a missing, non-integer or non-positive value falls back to `defaultRetryAfter`. If the wait is `<= maxRetryAfter`, call `sleeper.sleep(wait)` **once** and repeat the same request **once**. If the second response is not successful, or if the first wait is `> maxRetryAfter` (no sleep, no second request), throw `DeadlockApiUnavailableException` whose `retryAfter()` is the value read from the header (or the default if it was missing/invalid). Never retry more than once per call. If `sleep` is interrupted, restore the thread's interrupt flag and throw `DeadlockApiUnavailableException`.
5. **Other availability problems.** HTTP 429 (rate limit) is **not** retried: throw `DeadlockApiUnavailableException` carrying `Retry-After` when it is present and valid. Connection failures and timeouts (`ResourceAccessException` or equivalent I/O errors) throw `DeadlockApiUnavailableException` with the cause, no retry.
6. **Other errors.** Any other non-2xx status (400, 404 where not handled above, 500, ...) and any unreadable/malformed JSON body throw `DeadlockApiException` with the endpoint path and status in the message (no query string with tokens, no body). No retry.
7. **Benchmarks parameters.** `heroId`/`rankTier` validation, badge range and the 30-day window are exactly as in "Semantics"; the window uses the injected `Clock`.
8. **Caching.** Each method is cached with the cache, TTL, size and key defined above. An empty history is cached like any other value (a user who just installed the ingest tool may need to wait up to 2 minutes; that is accepted and noted in the report). `sync = true` is used so concurrent callers for the same key trigger one upstream request.
9. **Logging.** SLF4J. `WARN` on every 503/429/timeout with endpoint name, status and wait (never the full URL with query values beyond ids, never headers or bodies); `DEBUG` for each outgoing request path. No tokens exist in this task, and none may ever be logged.
10. **No user-facing text.** `messages.properties` is untouched. Mapping `DeadlockApiUnavailableException` to the "Data source is busy, try again in a minute" message, and "no recent matches" to its message, is done by the command tasks (005+).
11. **Startup.** No HTTP call happens at startup or in a constructor. The existing application context (JDA bean skipped when Discord variables are empty) must still start.

## Tests
JUnit 5 and AssertJ. Fixtures are small JSON files in `src/test/resources/fixtures/deadlock-api/`, trimmed from **real** responses or OpenAPI examples confirmed in Step 0 (keep at least one extra unknown field in each to prove `ignoreUnknown`). Replace real account IDs and any player names with obviously fake values; commit no real user data. The tests derive expected values from the fixture content, not from invented numbers.

`DeadlockApiClientTest` and `DeadlockApiClientCachingTest` do **not** use `@SpringBootTest` (no database). Build the client with `DeadlockApiConfig.restClientBuilder(properties)` bound to `MockRestServiceServer`, a fixed `Clock`, and a recording `Sleeper` that stores the requested durations and never sleeps.

### `DeadlockApiClientTest` (18 tests)
1. `historyParsesEntriesAndIgnoresUnknownFields`: request is a `GET` to the confirmed history path for the account id; every mapped field of the fixture is checked; index 0 is the latest match.
2. `historyEmptyArrayReturnsEmptyList`.
3. `historyNotFoundFollowsStep0Decision`: 404 returns an empty list **or** throws `DeadlockApiException`, whichever the report records as confirmed in Step 0.
4. `matchDetailsParsesPlayersItemsAndStats`: durations, one player's items and stats snapshots mapped; missing optional lists become empty.
5. `matchDetailsNotFoundReturnsEmpty`: 404 gives `Optional.empty()`.
6. `benchmarksSendFiltersAndParsePercentiles`: for `getBenchmarks(5, 7)` the request contains `hero_ids=5`, `min_badge_level=70`, `max_badge_level=79` and `min_unix_timestamp` equal to fixed-now minus 30 days; the returned map contains the fixture's metrics with `avg`, `p25`, `p50`, `p75`.
7. `benchmarksRejectInvalidTierWithoutHttpCall`: tiers `-1` and `12` throw `IllegalArgumentException`; the mock server receives no request.
8. `ranksAreParsed`.
9. `heroesAreParsed`.
10. `retriesOnceOn503WithRetryAfterAndSucceeds`: 503 with `Retry-After: 1`, then 200; result returned, exactly 2 requests, sleeper called once with 1 s.
11. `failsAfterSecond503WithUnavailableException`: two 503s; exactly 2 requests, one sleep, `DeadlockApiUnavailableException` with `retryAfter()` present.
12. `doesNotSleepWhenRetryAfterExceedsCap`: 503 with `Retry-After: 60` and cap 10 s; one request, no sleep, `DeadlockApiUnavailableException` with `retryAfter()` = 60 s.
13. `usesDefaultRetryAfterWhenHeaderMissingOrInvalid`: 503 without header and 503 with `Retry-After: abc`, each followed by 200; sleeper receives the default (2 s) both times.
14. `rateLimit429IsNotRetried`: one request, no sleep, `DeadlockApiUnavailableException`.
15. `serverError500ThrowsApiExceptionWithoutRetry`: thrown exception is a `DeadlockApiException` and **not** a `DeadlockApiUnavailableException`; one request.
16. `ioErrorBecomesUnavailable`: the mock responder throws `IOException`; result is `DeadlockApiUnavailableException` with a cause; one request.
17. `malformedJsonThrowsApiException`.
18. `sendsAcceptAndUserAgentHeaders`: requests carry `Accept: application/json` and `User-Agent: Rejuv-Discord-Bot`, and the request URL starts with the configured base URL.

### `DeadlockApiClientCachingTest` (7 tests)
Uses `@SpringJUnitConfig` with a small test configuration that imports `CacheConfig` and `DeadlockApiClient` and provides the `RestClient` (bound to `MockRestServiceServer`), `DeadlockApiProperties`, `Clock` and `Sleeper` beans, so the real caching proxy is exercised.
1. `secondCallWithSameArgumentsHitsCache`: two `getMatchHistory(1)` calls produce one upstream request.
2. `differentArgumentsAreCachedSeparately`: histories for two accounts, and benchmarks for `(5, 7)` vs `(5, 8)` vs `(6, 7)`, each cause their own request; repeating each causes none.
3. `ranksAndHeroesDoNotCollide`: `getRanks()` then `getHeroes()` return their own data (one request each), and repeating each is served from cache.
4. `matchDetailsAreCached`: two `getMatchDetails(id)` calls give one request.
5. `failuresAreNotCached`: a 500 on the first call, a 200 on the second; the second call succeeds with a second request.
6. `cacheTtlsAndSizesMatchTheDesign`: for each cache name, the native Caffeine cache has `expireAfterWrite` of 24 h / 6 h / 2 min / 1 h and the configured maximum size (read through the Caffeine `policy()` API).
7. `unknownCacheNamesAreNotCreatedDynamically`: `cacheManager.getCache("anything")` is `null`.

### `DeadlockApiPropertiesTest` (1 test)
`bindsDefaultsFromApplicationYaml`: with `ApplicationContextRunner`, `ConfigDataApplicationContextInitializer` and `@EnableConfigurationProperties(DeadlockApiProperties.class)`, the properties equal base URL `https://api.deadlock-api.com`, 3 s, 10 s, 2 s, 10 s.

New tests: 26. Expected total: 31 existing (Tasks 001-003, per the Task 003 spec; run `verify` once on the fresh branch before changing anything to confirm this baseline) + 26 = **57**. Docker must be running for `verify` because of the Task 003 and context tests, but no new test of this task uses it.

Existing tests are not modified. `RejuvApplicationTests` and `MessagesIntegrationTest` start the full context, so they must still pass: that proves the new beans (`DeadlockApiConfig`, `CacheConfig`, `DeadlockApiClient`) wire up next to the datasource and JDA configuration without any HTTP call at startup.

## Verification
On Windows use the wrapper form `.\mvnw.cmd` (with the `MAVEN_USER_HOME` workaround from the Task 001 report if needed).
1. `.\mvnw.cmd clean verify` passes (Docker Desktop running, needed by the Task 003 tests).
2. Review the diff and fixtures: no secret, token or real account id or player name appears in `src` or `docs`, and `.env` was never read or printed.
3. `git diff --check`, and a review of the final diff for unrelated files or line-ending-only changes (the maintainer's working tree has had CRLF-only noise before).
4. Optional manual check, only if network access is available and the implementer can do it without committing anything: a throwaway local run (for example a JUnit test or `jshell` that is not committed) calling `getRanks()`/`getHeroes()` against the live API. If it is not run, say so in the report; do not claim it.

## Documentation
- Task report `docs/task-reports/004-deadlock-api-client.md` (format of the Task 002 report). Mandatory sections beyond the usual ones: **Confirmed upstream contract** (Step 0 output: final paths, parameters, fields, ordering of the history, 404 behavior, `match_result` semantics, default modes of the benchmarks endpoint, where buff pickups appear).
- `docs/PROJECT_CONTEXT.md`: record only verified facts (client exists, caches and TTLs, exceptions, confirmed endpoints, next eligible tasks 005/007/011-013 per their dependencies) and only if no other branch is active; otherwise mark pending integration in the report.
- No ADR is added: the client, library and TTLs follow ADR-0010; the match-details TTL and size limits are recorded here and in the report.

## Out of scope
- Any slash command, `/link` parsing or Steam ID conversion (Task 005), `/profile` aggregation (Task 006), metrics computation, win/loss derivation, "latest match" business rules, benchmarks interpretation (Task 007), the Claude/AI client (Task 008).
- Item assets (item names/tiers needed to identify the first tier-3 item and readable item names): not covered by the plan or this task; see open questions.
- Persistence, repositories, Flyway migrations, `match_analysis`.
- API keys, authentication, proxies, rate-limit throttling beyond the single `Retry-After` retry, circuit breakers, scheduled refresh, cache warm-up, `@Async`, parallel fetching.
- Redis or any other cache, per-user caches, the 10-minute profile cache (Task 006).
- New dependencies other than the one listed; generated OpenAPI clients; WebFlux/`WebClient`; enabling a web server (`web-application-type: none` stays).
- Any change to `messages.properties`, existing commands, `Messages`, `JdaConfig`, `BotProperties`, existing tests or ADRs.

## Dependencies and constraints
- Depends on Task 001 only. Task 003 is already merged, so `pom.xml` and `application.yaml` already contain its changes; this task only adds to them.
- Conventions (AGENTS.md): constructor injection, `final` fields, no Lombok, DTO records with `@JsonIgnoreProperties(ignoreUnknown = true)`, SLF4J, JUnit 5 + AssertJ, `MockRestServiceServer` + JSON fixtures in `src/test/resources/fixtures/`, secrets never in the repo.
- Surefire only runs classes named `*Test`/`Tests`; name every test class that way.
- Package dependencies: `clients` may depend on `config` (cache name constants, properties) and `clients.dto`; `clients` must not depend on `commands`, `services`, `repositories` or JDA.
- Users never see exception messages; they are for logs only.

## Acceptance criteria
- [ ] Compiles, all tests pass (`.\mvnw.cmd clean verify`, 57 tests expected: 31 existing + 26 new)
- [ ] Unit tests for the client logic (26 new tests as listed; none needs a database or Docker)
- [ ] Follows AGENTS.md conventions
- [ ] Step 0 done: the confirmed upstream contract is in the task report and any difference from the candidates is listed as a deviation
- [ ] `DeadlockApiClient` public methods, annotations and keys match "Interfaces / signatures"; `getRanks`/`getHeroes` use distinct explicit cache keys
- [ ] 503 is retried at most once, honoring `Retry-After` (default 2 s when missing/invalid, no sleep above the 10 s cap); 429, I/O errors, 4xx/5xx and malformed bodies map to the specified exception types without retry
- [ ] Caches exist exactly as specified (names, TTLs, sizes, static mode); failures are not cached
- [ ] Benchmarks request uses `hero_ids`, badge range `tier*10`..`tier*10+9` and a 30-day window from the injected `Clock`; invalid tiers are rejected before any HTTP call
- [ ] `pom.xml` differs only by the single listed dependency, without an explicit version; `application.yaml` differs only by the appended `deadlock-api:` block
- [ ] No user-facing text added; `messages.properties` untouched; no secret or real user data committed
- [ ] Task report created; `docs/PROJECT_CONTEXT.md` updated or marked pending integration; final diff has no unrelated files

## Decisions and open questions
Decisions taken in this spec (supported by the repository):

1. **Full client in one task** (owner choice): history, metadata, benchmarks and assets, because ADR-0010 puts them in `DeadlockApiClient` and no other task owns them. The plan row for 004 is narrower; update `docs/implementation-plan.md` when convenient (not authorized here).
2. **Account check = non-empty match history**, using the cached `getMatchHistory`; no separate method, to avoid a self-invocation that skips the cache proxy.
3. **Static `RestClient.builder()` instead of the auto-configured builder**, so base URL, headers and timeouts are explicit and testable, and no `RestClient.Builder` bean clashes with Spring Boot's.
4. **One retry only**, bounded by `max-retry-after` (10 s) because the call may block a Discord interaction thread; longer waits fail fast with `retryAfter()` so the caller can tell the user.
5. **`Sleeper` and `Clock` are injected** so retry timing and the 30-day window are testable without real waiting.
6. **Cache design**: Caffeine per-cache TTL (assets 24 h, benchmarks 6 h, history 2 min per ADR-0010; match details 1 h and all size limits chosen here), `sync = true`, static cache names, immutable cached values.
7. **Client returns API-shaped data**, no interpretation; Task 007 owns metrics semantics.

Unresolved / assumptions (owner to confirm):

1. **Upstream endpoints and fields are unverified** except the asset paths; Step 0 settles them. The scope question below depends on it.
2. **Starter coordinates.** `spring-boot-starter-restclient` is assumed to exist and manage `RestClient` and Jackson in Boot 4.1.1 (could not be checked against the BOM from the planning environment). The implementer must stop and ask if it does not resolve.
3. **Item assets are not covered.** `docs/mvp-spec.md` needs readable item names and the "first tier-3 item"; the item catalog endpoint is outside this task and the plan. Decide before Task 007 whether to extend this client (small follow-up) or add an item-assets task.
4. **Benchmark population.** The default `game_mode`/`match_mode` of the benchmarks endpoint may include non-standard modes; Step 0 records it, and Tasks 007/010 decide whether a filter is needed.
5. **Permanent buff pickups** (metrics set, builds since 6712) may not be exposed by the metadata endpoint; Step 0 records where they appear, and Task 007 decides how to proceed if they do not.
6. **Baseline test count.** The 31 existing tests come from the Task 003 spec/report; the implementer confirms the real baseline with one `verify` run on the fresh branch and reports any difference.
