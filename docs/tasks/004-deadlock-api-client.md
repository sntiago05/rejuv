# Task 004: `DeadlockApiClient` (match history, benchmarks, item stats, assets, player rank, caching, 503 handling)

- **Branch**: `feature/004-deadlock-api-client`
- **Implementer**: Antigravity
- **Related ADRs**: ADR-0004, ADR-0010 (also ADR-0002 for Caffeine)

## Goal
Give the bot a single, cached, well-tested gateway to deadlock-api.com for everything except match metadata: a player's match history (which also serves as the "account has Deadlock data" check), percentile benchmarks for a hero and rank tier, average item buy times for a hero and rank tier, the player's current rank, and the rank/hero/item assets. Failures (503 with `Retry-After`, rate limits, timeouts, bad responses) are translated into two small exception types that later tasks map to user-facing messages. No command, service or database code is added. Match metadata (`/v1/matches/{match_id}/metadata`) is **not** part of this task: it is Task 022 (JSON adapter behind a port) and Task 023 (raw protobuf fallback), both of which reuse the HTTP infrastructure created here.

## Context (verified against the repository and real API responses when this spec was written)
Do not repeat this discovery. If a fact below is false in your checkout, stop and ask.

- Plan entry: `docs/implementation-plan.md`, task 004, depends on 001, implementer Antigravity. Tasks 005, 006, 007, 009 and 011-013 consume this client; Task 022 reuses its `RestClient` configuration, `Sleeper` and exceptions.
- Tasks 001, 002 and 003 are in `main` (Task 003 merge confirmed by the owner). The facts below about `pom.xml` and `application.yaml` were read from the Task 003 tree; re-check them on your fresh branch from `main`. **No new test of this task may need a database or Docker.** Existing `@SpringBootTest` tests do need Docker (Testcontainers), so Docker must be running for `verify`.
- `pom.xml` (Spring Boot parent `4.1.1`, Java 21) has `spring-boot-starter`, `spring-boot-starter-cache`, `com.github.ben-manes.caffeine:caffeine`, `spring-boot-starter-validation`, `spring-boot-starter-test` (test), JDA 5.0.0 and the JPA/Flyway/PostgreSQL/Testcontainers dependencies. It has **no** HTTP client starter, so `RestClient` and Jackson are not guaranteed on the classpath.
- `application.yaml` has `spring.main.web-application-type: none` (keep it), `spring.docker.compose.enabled: false`, the `bot:` block, and the Task 003 datasource/JPA keys. There is no `clients/` package, no `@EnableCaching`, no `CacheManager`, no `src/test/resources/fixtures/`.
- Existing conventions: `BotProperties` is a `@ConfigurationProperties("bot")` record registered through `@EnableConfigurationProperties` in `JdaConfig`; `Messages` is the only access to user-facing text; services and clients are Discord-agnostic (no JDA types).
- Rules from `AGENTS.md` that apply: external DTOs are records with `@JsonIgnoreProperties(ignoreUnknown = true)` and only the fields we use; every read is cached; on HTTP 503 honor `Retry-After` once then fail gracefully; never fire parallel uncached analytics requests; constructor injection, `final` fields, no Lombok; test HTTP clients with `MockRestServiceServer` and JSON fixtures in `src/test/resources/fixtures/`; no secrets or real user data in the repo.
- ADR-0010: hand-written client on Spring `RestClient`; caches: assets 24 h, benchmarks 6 h, match history 2 min (profile/rank 10 min); analytics endpoints share a 200 req/min per IP limit; busy queries return 503 with `Retry-After`.
- `docs/mvp-spec.md`: `/link` verifies the account "has Deadlock match history via the API"; benchmarks are "same hero, same rank tier (badge range `tier*10` to `tier*10+9`), last 30 days, percentiles p25/p50/p75".

## Step 0 - confirm the remaining upstream details (short, do this before writing code)
The contract below comes from **real responses collected by the owner on 2026-10-16** (host `https://api.deadlock-api.com`). Most of it is confirmed. Before coding, open `https://api.deadlock-api.com/openapi.json` once and check only the items marked "To verify" in the table; then:

1. Write the final contract (confirmed + verified items) into the task report, section "Confirmed upstream contract".
2. If a "To verify" item differs from the table, use the verified one. The Java signatures in "Interfaces / signatures" do not change. Record each difference as a deviation in the report.
3. **Stop and ask** if `GET /v1/analytics/item-stats` has no way to filter by hero and by average badge (see Upstream contract, row "Item stats"), because the item benchmark would then not be comparable to the player's hero and rank; that changes scope.

## Upstream contract (host `https://api.deadlock-api.com`)
| Purpose | Request | Response and fields we use | Status |
|---|---|---|---|
| Match history | `GET /v1/players/{account_id}/match-history` (never send `force_refetch`) | JSON array, newest first. Entry fields we use: `match_id` (int64), `hero_id`, `hero_level`, `start_time` (int32 epoch s), `match_duration_s`, `match_mode`, `match_result`, `player_team`, `player_match_outcome`, `player_kills`, `player_deaths`, `player_assists`, `net_worth`, `last_hits`, `denies`, `ranked_display_badge` (nullable). All others are ignored. Account without data: `200 []` (not 404). A 6,800-match history is about 5 MB. | Confirmed (real sample). Documented but not observed: on `429` the body is the stored history (valid array) |
| Benchmarks | `GET /v1/analytics/player-stats/metrics?hero_ids=<id>&min_average_badge=<tier*10>&max_average_badge=<tier*10+9>` | JSON object `metricName -> { avg, std, percentile1 ... percentile99 }`. 29 metrics (for example `net_worth_per_min`, `player_damage_per_min`, `last_hits`, `denies`, `deaths`, `kda`, `permanent_buffs`, `first_permanent_buff_time_s`). Default window is the last 30 days, so no timestamp is sent. `average_badge` is the average of both teams. Do not send `include_buff_metrics` (adds 3 metrics, not needed now). There is **no** benchmark for net worth at 10/20 min. | Confirmed (real sample). To verify: JSON key names `percentile25/50/75` |
| Item stats | `GET /v1/analytics/item-stats?hero_id=<id>&min_average_badge=<tier*10>&max_average_badge=<tier*10+9>` | JSON array. Entry fields we use: `item_id`, `matches`, `wins`, `losses`, `avg_buy_time_s`, `avg_buy_time_relative`. Others (`players`, `sold`, `avg_sell_time_*`, `bucket`) are ignored. Only averages exist (no percentiles). | Response shape confirmed (real sample, 156 items, `bucket` always 0). **To verify**: the query parameter names and that hero and badge filters exist (the sample was collected without known filters) |
| Player rank | `GET /v1/players/{account_id}/rank` | Object `{ badge, rank, subrank, last_match{...} }`; we use `badge`, `rank`, `subrank`. An unranked player returns `200` with zeros (never 404 observed). Bucket limit 20 req/min/IP. | Confirmed (real sample) |
| Ranks asset | `GET /v1/assets/ranks` | Array of `{ tier, name, color, images }`; tiers 0 (Obscurus) to 11 (Eternus). We use `tier`, `name`. | Confirmed |
| Heroes asset | `GET /v1/assets/heroes` | Array of 65 heroes `{ id, class_name, name, ... }` (about 1.8 MB). We use `id`, `class_name`, `name`. Do not send `only_active`. | Confirmed |
| Items asset | `GET /v1/assets/items` | Array of 764 entries (about 8.2 MB): upgrades (`item_tier` 1-5), abilities (no tier), weapons. We use `id`, `class_name`, `name`, `type`, `item_tier`, `item_slot_type`, `cost`; only upgrade entries carry a tier. | Confirmed |

Rate limits (informational): analytics endpoints share 200 req/min/IP; player rank 20 req/min/IP; load shedding returns `503` + `Retry-After`.

## Files to create or modify
| File | Change |
|---|---|
| `pom.xml` | **Modify.** Add exactly one compile-scope dependency, version managed by the Spring Boot BOM (no `<version>`): `org.springframework.boot:spring-boot-starter-restclient` (brings `RestClient` and Jackson). Change nothing else. If it does not resolve, or the test classes in this spec still do not compile (for example `MockRestServiceServer` not on the test classpath), stop and ask; do not add an explicit version, another HTTP library or a generated client. |
| `src/main/resources/application.yaml` | **Modify.** Append the `deadlock-api:` block defined in "Configuration". Keep every existing key unchanged. |
| `src/main/java/sntiago05/rejuv/config/DeadlockApiProperties.java` | **Create.** |
| `src/main/java/sntiago05/rejuv/config/DeadlockApiConfig.java` | **Create.** Beans: `RestClient`, `Sleeper`; registers `DeadlockApiProperties`. |
| `src/main/java/sntiago05/rejuv/config/CacheConfig.java` | **Create.** `@EnableCaching`, cache name constants, Caffeine `CacheManager` with per-cache TTL. |
| `src/main/java/sntiago05/rejuv/clients/DeadlockApiClient.java` | **Create.** |
| `src/main/java/sntiago05/rejuv/clients/DeadlockApiException.java` | **Create.** |
| `src/main/java/sntiago05/rejuv/clients/DeadlockApiUnavailableException.java` | **Create.** |
| `src/main/java/sntiago05/rejuv/clients/Sleeper.java` | **Create.** |
| `src/main/java/sntiago05/rejuv/clients/dto/MatchHistoryEntry.java` | **Create.** |
| `src/main/java/sntiago05/rejuv/clients/dto/MetricPercentiles.java` | **Create.** |
| `src/main/java/sntiago05/rejuv/clients/dto/ItemStat.java` | **Create.** |
| `src/main/java/sntiago05/rejuv/clients/dto/PlayerRank.java` | **Create.** |
| `src/main/java/sntiago05/rejuv/clients/dto/RankAsset.java` | **Create.** |
| `src/main/java/sntiago05/rejuv/clients/dto/HeroAsset.java` | **Create.** |
| `src/main/java/sntiago05/rejuv/clients/dto/ItemAsset.java` | **Create.** |
| `src/test/java/sntiago05/rejuv/clients/DeadlockApiClientTest.java` | **Create.** |
| `src/test/java/sntiago05/rejuv/clients/DeadlockApiClientCachingTest.java` | **Create.** |
| `src/test/java/sntiago05/rejuv/config/DeadlockApiPropertiesTest.java` | **Create.** |
| `src/test/resources/fixtures/deadlock-api/match-history.json` | **Create.** |
| `src/test/resources/fixtures/deadlock-api/match-history-empty.json` | **Create.** (`[]`) |
| `src/test/resources/fixtures/deadlock-api/player-stats-metrics.json` | **Create.** |
| `src/test/resources/fixtures/deadlock-api/item-stats.json` | **Create.** |
| `src/test/resources/fixtures/deadlock-api/player-rank.json` | **Create.** |
| `src/test/resources/fixtures/deadlock-api/player-rank-unranked.json` | **Create.** |
| `src/test/resources/fixtures/deadlock-api/assets-ranks.json` | **Create.** |
| `src/test/resources/fixtures/deadlock-api/assets-heroes.json` | **Create.** |
| `src/test/resources/fixtures/deadlock-api/assets-items.json` | **Create.** |
| `docs/task-reports/004-deadlock-api-client.md` | **Create** at closeout (AGENTS.md), following `docs/task-reports/002-i18n-and-help.md`. |
| `docs/PROJECT_CONTEXT.md` | **Update** at closeout only if no other task branch/worktree is active; otherwise leave it untouched and mark the update "pending integration" in the report (AGENTS.md, parallel tasks). |

Do not touch any other file: `messages.properties`, `CommandRouter`, `PingCommand`, `HelpCommand`, `Messages`, `JdaConfig`, `BotProperties`, `RejuvApplication`, existing tests, ADRs, other specs, `AGENTS.md`, `docs/mvp-spec.md`, `docs/architecture.md`, `docs/implementation-plan.md`, `docs/roadmap.md`, `README.md`, `.env.example`.

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
public DeadlockApiClient(RestClient deadlockApiRestClient, DeadlockApiProperties properties, Sleeper sleeper)

@Cacheable(cacheNames = CacheConfig.DEADLOCK_MATCH_HISTORY, sync = true)
public List<MatchHistoryEntry> getMatchHistory(long accountId)

@Cacheable(cacheNames = CacheConfig.DEADLOCK_PLAYER_RANK, sync = true)
public PlayerRank getPlayerRank(long accountId)

@Cacheable(cacheNames = CacheConfig.DEADLOCK_BENCHMARKS, sync = true, key = "#heroId + ':' + #rankTier")
public Map<String, MetricPercentiles> getBenchmarks(int heroId, int rankTier)

@Cacheable(cacheNames = CacheConfig.DEADLOCK_ITEM_STATS, sync = true, key = "#heroId + ':' + #rankTier")
public Map<Long, ItemStat> getItemStats(int heroId, int rankTier)

@Cacheable(cacheNames = CacheConfig.DEADLOCK_ASSETS, sync = true, key = "'ranks'")
public List<RankAsset> getRanks()

@Cacheable(cacheNames = CacheConfig.DEADLOCK_ASSETS, sync = true, key = "'heroes'")
public List<HeroAsset> getHeroes()

@Cacheable(cacheNames = CacheConfig.DEADLOCK_ASSETS, sync = true, key = "'items'")
public List<ItemAsset> getItems()
```
The `@Cacheable` annotations and keys above are part of the contract: the three asset methods share a cache, so the explicit keys are mandatory (default keys would collide). A public constant `MAX_HISTORY_ENTRIES = 200` lives in the client.

Semantics:
- `getMatchHistory`: matches of the account in the order the API returns them (newest first, confirmed on a real response; index 0 is the latest match). The client keeps **only the first `MAX_HISTORY_ENTRIES` (200)** entries, so a 6,800-match history is never held in the cache. Empty list = the API has no matches for the account. This is the "account check" used by Task 005: an account is valid iff the list is not empty. There is deliberately no separate `hasMatchHistory` method (a self-call would bypass the cache proxy). The client never sends `force_refetch`.
- `getPlayerRank`: the player's current rank. `badge == 0` (all fields 0) means unranked, returned as a normal `PlayerRank`, not as an error. Interpreting the badge (`tier = badge / 10`, `subtier = badge % 10`) belongs to the callers.
- `getBenchmarks`: `rankTier` must be in `0..11`, otherwise `IllegalArgumentException` before any HTTP call. Requests `hero_ids=heroId`, `min_average_badge=rankTier*10`, `max_average_badge=rankTier*10+9`; no timestamp (the API default of the last 30 days is used) and no `include_buff_metrics`. Returns an immutable map keyed by the API's metric name; the client does not choose or rename metrics (Task 007 does).
- `getItemStats`: same `heroId`/`rankTier` validation and badge range; returns an immutable map keyed by `item_id`. Averages only (no percentiles), so a caller compares a player's buy time against `avgBuyTimeS`, not against a distribution.
- `getRanks`, `getHeroes`, `getItems`: immutable lists. `getItems` maps the whole catalog to the minimal `ItemAsset` record (the 8.2 MB body is parsed once per 24 h and only the minimal records are cached).
- Every returned collection is immutable (`List.copyOf`, `Map.copyOf`); cached values must not be mutable.
- Callers must not call the analytics methods (`getBenchmarks`, `getItemStats`) in parallel for different uncached keys (document this in their Javadoc); `sync = true` already prevents duplicate loads for the same key.

### DTOs (`sntiago05.rejuv.clients.dto`)
All are records annotated `@JsonIgnoreProperties(ignoreUnknown = true)` with **explicit** `@JsonProperty("snake_case_name")` on each component (do not rely on a naming-strategy setting). Use the Jackson annotation package that the project's Jackson version actually provides (check the classpath after adding the starter; do not hard-code `com.fasterxml` if the Boot 4 starter ships Jackson 3 `tools.jackson` annotations under a different package, and record the choice in the report). Component names below are fixed.
```java
public record MatchHistoryEntry(long matchId, int heroId, int heroLevel, long startTime, int matchDurationS,
                                int matchMode, int matchResult, int playerTeam, int playerMatchOutcome,
                                int playerKills, int playerDeaths, int playerAssists,
                                long netWorth, int lastHits, int denies, Integer rankedDisplayBadge) { }

public record MetricPercentiles(double avg, double p25, double p50, double p75) { }

public record ItemStat(long itemId, long matches, long wins, long losses, double avgBuyTimeS, double avgBuyTimeRelative) { }

public record PlayerRank(int badge, int rank, int subrank) { }

public record RankAsset(int tier, String name) { }

public record HeroAsset(int id, String className, String name) { }

public record ItemAsset(long id, String className, String name, String type,
                        Integer itemTier, String itemSlotType, Integer cost) { }
```
- `MetricPercentiles` maps JSON `avg`, `percentile25`, `percentile50`, `percentile75` (use `@JsonProperty` on the record components; Step 0 verifies these key names).
- `rankedDisplayBadge`, `itemTier`, `itemSlotType` and `cost` are boxed/nullable because the API returns null or omits them (`ranked_display_badge` is null for non-ranked matches; abilities have no tier). Never fail on a missing optional field.
- Do not interpret meanings here. In particular: win/loss derivation (`player_match_outcome == 1` is a win; for older matches where it is `0`, `match_result == player_team` matched every scored match in the real sample, an inference to be handled in Task 007), which `match_mode` is ranked, and "latest match" selection belong to Task 007.

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
- `public static RestClient.Builder restClientBuilder(DeadlockApiProperties p)`: `RestClient.builder()` with `baseUrl`, header `Accept: application/json`, header `User-Agent: Rejuv-Discord-Bot` (identifies us to the community API; no version, no contact data), and a `JdkClientHttpRequestFactory` built from `java.net.http.HttpClient` with `connectTimeout` and `setReadTimeout`. Public and static so tests (and Task 022) can bind `MockRestServiceServer` to the real builder.
- `@Bean RestClient deadlockApiRestClient(DeadlockApiProperties p)` returns `restClientBuilder(p).build()`. Do not expose a `RestClient.Builder` bean (it would clash with Spring Boot's auto-configured one).
- `@Bean Sleeper sleeper()` returns a lambda calling `Thread.sleep(duration)`.

### `CacheConfig`
```java
@Configuration
@EnableCaching
public class CacheConfig {
    public static final String DEADLOCK_ASSETS = "deadlockAssets";               // 24 h, max 10
    public static final String DEADLOCK_BENCHMARKS = "deadlockBenchmarks";       // 6 h, max 500
    public static final String DEADLOCK_ITEM_STATS = "deadlockItemStats";        // 6 h, max 500
    public static final String DEADLOCK_MATCH_HISTORY = "deadlockMatchHistory";  // 2 min, max 1000
    public static final String DEADLOCK_PLAYER_RANK = "deadlockPlayerRank";      // 10 min, max 1000

    @Bean CacheManager cacheManager() { ... }
}
```
`CacheManager` is a `CaffeineCacheManager` in **static** mode (only the five caches above exist; any other name is not created on demand) with one Caffeine cache per name using `expireAfterWrite` with the TTL in the comments and the `maximumSize` in the comments. TTLs follow ADR-0010 (assets 24 h, benchmarks 6 h, history 2 min, profile/rank 10 min); the item stats TTL (6 h, same as benchmarks) and all size limits are decisions of this spec. Failures and exceptions are never cached (Spring's default behavior; do not change it). Task 022 will add its own cache name for match telemetry in its own spec.

## Behavior
1. **Requests.** Every call is a `GET` on `<base-url><path>` through the shared `RestClient`; path variables and query parameters are encoded by `RestClient` URI templates (never string-concatenated into the URL). Response bodies are mapped to the DTOs; unknown fields are ignored.
2. **Match history / account check.** A non-empty list means the account has data; an empty array (`200 []`) means none. After parsing, keep only the first `MAX_HISTORY_ENTRIES` entries. HTTP 404 on this endpoint is not documented and is an unexpected status: it becomes `DeadlockApiException` (point 7).
3. **History rate limit.** The API documents that on HTTP 429 the history endpoint returns the stored history as the body. If the status is 429 **and** the body is a valid JSON array, treat it as a successful response (log `WARN`, no retry, no sleep) and apply the same cap; if the body is not a readable array, throw `DeadlockApiUnavailableException`. This exception applies to the history endpoint only; every other endpoint follows point 6.
4. **Player rank.** `200` with zeros is a valid result (unranked). 404 is not expected: it becomes `DeadlockApiException`.
5. **503 handling (ADR-0010, AGENTS.md).** On HTTP 503, read `Retry-After`: an integer number of seconds is used as given; a missing, non-integer or non-positive value falls back to `defaultRetryAfter`. If the wait is `<= maxRetryAfter`, call `sleeper.sleep(wait)` **once** and repeat the same request **once**. If the second response is not successful, or if the first wait is `> maxRetryAfter` (no sleep, no second request), throw `DeadlockApiUnavailableException` whose `retryAfter()` is the value read from the header (or the default if it was missing/invalid). Never retry more than once per call. If `sleep` is interrupted, restore the thread's interrupt flag and throw `DeadlockApiUnavailableException`.
6. **Other availability problems.** HTTP 429 (outside the history rule) is **not** retried: throw `DeadlockApiUnavailableException` carrying `Retry-After` when it is present and valid. Connection failures and timeouts (`ResourceAccessException` or equivalent I/O errors) throw `DeadlockApiUnavailableException` with the cause, no retry.
7. **Other errors.** Any other non-2xx status (400, 404, 500, ...) and any unreadable/malformed JSON body throw `DeadlockApiException` with the endpoint path and status in the message (no query string, no body). No retry.
8. **Benchmarks and item stats parameters.** `heroId`/`rankTier` validation and the badge range are exactly as in "Semantics". `hero_ids` is used for `player-stats/metrics`, and the verified hero parameter name for `item-stats` (candidate `hero_id`, see Step 0).
9. **Caching.** Each method is cached with the cache, TTL, size and key defined above. An empty history is cached like any other value (a user who just started uploading matches may need to wait up to 2 minutes; accepted and noted in the report). `sync = true` is used so concurrent callers for the same key trigger one upstream request.
10. **Logging.** SLF4J. `WARN` on every 503/429/timeout with endpoint name, status and wait (never headers or bodies); `DEBUG` for each outgoing request path. No tokens exist in this task, and none may ever be logged.
11. **No user-facing text.** `messages.properties` is untouched. Mapping `DeadlockApiUnavailableException` to the "Data source is busy, try again in a minute" message, and "no recent matches" to its message, is done by the command tasks (005+).
12. **Startup.** No HTTP call happens at startup or in a constructor. The existing application context (JDA bean skipped when Discord variables are empty) must still start.

## Tests
JUnit 5 and AssertJ. Fixtures are small JSON files in `src/test/resources/fixtures/deadlock-api/`, **trimmed from the real responses described above** (history: a handful of entries, at least one with `ranked_display_badge: null`, one with `player_match_outcome: 0`; metrics: 3-4 metrics; item stats: 3-4 items; assets: 3 entries each, items including one upgrade with tier and one ability without). Keep at least one extra unknown field in each fixture to prove `ignoreUnknown`. **Replace every real account id, match id and player name with obviously fake values** (for example account `1000001`, matches `900000001+`); do not copy the owner's account id or any other player's id from the evidence files, and commit no real user data. The tests derive expected values from the fixture content, not from invented numbers.

`DeadlockApiClientTest` and `DeadlockApiClientCachingTest` do **not** use `@SpringBootTest` (no database). Build the client with `DeadlockApiConfig.restClientBuilder(properties)` bound to `MockRestServiceServer` and a recording `Sleeper` that stores the requested durations and never sleeps.

### `DeadlockApiClientTest` (24 tests)
1. `historyParsesEntriesAndIgnoresUnknownFields`: request is a `GET` to `/v1/players/{id}/match-history` with no `force_refetch`; every mapped field is checked; index 0 is the first entry of the fixture; nullable `rankedDisplayBadge` is `null` where the fixture has null.
2. `historyEmptyArrayReturnsEmptyList`.
3. `historyIsCappedToMostRecentEntries`: a generated body of 250 entries (built in the test) returns exactly `MAX_HISTORY_ENTRIES` entries, the first 200 in the original order.
4. `history429WithArrayBodyIsTreatedAsSuccess`: status 429 with the fixture array returns the parsed list; one request, no sleep.
5. `history429WithoutArrayBodyThrowsUnavailable`: status 429 with `{}` or an empty body throws `DeadlockApiUnavailableException`; one request, no sleep.
6. `historyNotFoundThrowsApiException`: 404 gives `DeadlockApiException` that is not `DeadlockApiUnavailableException`.
7. `benchmarksSendFiltersAndParsePercentiles`: for `getBenchmarks(5, 7)` the request has `hero_ids=5`, `min_average_badge=70`, `max_average_badge=79` and **no** `min_unix_timestamp` or `include_buff_metrics`; the returned map contains the fixture's metrics with `avg`, `p25`, `p50`, `p75`.
8. `benchmarksRejectInvalidTierWithoutHttpCall`: tiers `-1` and `12` throw `IllegalArgumentException`; the mock server receives no request.
9. `itemStatsSendFiltersAndParseByItemId`: for `getItemStats(5, 7)` the request carries the verified hero and badge parameters; the map is keyed by `item_id` and `avgBuyTimeS`, `matches`, `wins`, `losses` match the fixture.
10. `itemStatsRejectInvalidTierWithoutHttpCall`.
11. `playerRankIsParsed`: `badge`, `rank`, `subrank` from `player-rank.json`.
12. `playerRankUnrankedReturnsZeros`: `200` with zeros gives `PlayerRank(0, 0, 0)`, no exception.
13. `ranksAreParsed`.
14. `heroesAreParsedWithClassName`.
15. `itemsAreParsedToMinimalRecords`: an upgrade keeps tier/slot/cost; an ability has `null` tier, slot and cost.
16. `retriesOnceOn503WithRetryAfterAndSucceeds`: 503 with `Retry-After: 1`, then 200; result returned, exactly 2 requests, sleeper called once with 1 s.
17. `failsAfterSecond503WithUnavailableException`: two 503s; exactly 2 requests, one sleep, `DeadlockApiUnavailableException` with `retryAfter()` present.
18. `doesNotSleepWhenRetryAfterExceedsCap`: 503 with `Retry-After: 60` and cap 10 s; one request, no sleep, `retryAfter()` = 60 s.
19. `usesDefaultRetryAfterWhenHeaderMissingOrInvalid`: 503 without header and 503 with `Retry-After: abc`, each followed by 200; sleeper receives the default (2 s) both times.
20. `rateLimit429OutsideHistoryIsNotRetried`: use `getPlayerRank`; one request, no sleep, `DeadlockApiUnavailableException`.
21. `serverError500ThrowsApiExceptionWithoutRetry`: a `DeadlockApiException` that is **not** a `DeadlockApiUnavailableException`; one request.
22. `ioErrorBecomesUnavailable`: the mock responder throws `IOException`; result is `DeadlockApiUnavailableException` with a cause; one request.
23. `malformedJsonThrowsApiException`.
24. `sendsAcceptAndUserAgentHeaders`: requests carry `Accept: application/json` and `User-Agent: Rejuv-Discord-Bot`, and the request URL starts with the configured base URL.

### `DeadlockApiClientCachingTest` (6 tests)
Uses `@SpringJUnitConfig` with a small test configuration that imports `CacheConfig` and `DeadlockApiClient` and provides the `RestClient` (bound to `MockRestServiceServer`), `DeadlockApiProperties` and `Sleeper` beans, so the real caching proxy is exercised.
1. `secondCallWithSameArgumentsHitsCache`: two `getMatchHistory(1)` calls produce one upstream request.
2. `differentArgumentsAreCachedSeparately`: histories and player ranks for two accounts, and benchmarks and item stats for `(5, 7)` vs `(5, 8)` vs `(6, 7)`, each cause their own request; repeating each causes none.
3. `assetsDoNotCollide`: `getRanks()`, `getHeroes()` and `getItems()` return their own data (one request each), and repeating each is served from cache.
4. `failuresAreNotCached`: a 500 on the first call, a 200 on the second; the second call succeeds with a second request.
5. `cacheTtlsAndSizesMatchTheDesign`: for each cache name, the native Caffeine cache has `expireAfterWrite` of 24 h / 6 h / 6 h / 2 min / 10 min and the configured maximum size (read through the Caffeine `policy()` API).
6. `unknownCacheNamesAreNotCreatedDynamically`: `cacheManager.getCache("anything")` is `null`.

### `DeadlockApiPropertiesTest` (1 test)
`bindsDefaultsFromApplicationYaml`: with `ApplicationContextRunner`, `ConfigDataApplicationContextInitializer` and `@EnableConfigurationProperties(DeadlockApiProperties.class)`, the properties equal base URL `https://api.deadlock-api.com`, 3 s, 10 s, 2 s, 10 s.

New tests: 31. Expected total: 31 existing (Tasks 001-003, per the Task 003 report; run `verify` once on the fresh branch before changing anything to confirm this baseline) + 31 = **62**. Docker must be running for `verify` because of the Task 003 and context tests, but no new test of this task uses it.

Existing tests are not modified. `RejuvApplicationTests` and `MessagesIntegrationTest` start the full context, so they must still pass: that proves the new beans (`DeadlockApiConfig`, `CacheConfig`, `DeadlockApiClient`) wire up next to the datasource and JDA configuration without any HTTP call at startup.

## Verification
On Windows use the wrapper form `.\mvnw.cmd` (with the `MAVEN_USER_HOME` workaround from the Task 001 report if needed).
1. `.\mvnw.cmd clean verify` passes (Docker Desktop running, needed by the Task 003 tests).
2. Review the diff and fixtures: no secret, token, real account id, real match id or player name appears in `src` or `docs`, and `.env` was never read or printed.
3. `git diff --check`, and a review of the final diff for unrelated files or line-ending-only changes (the maintainer's working tree has had CRLF-only noise before).
4. Optional manual check, only if network access is available and the implementer can do it without committing anything: a throwaway local run (for example a JUnit test or `jshell` that is not committed) calling `getRanks()`/`getHeroes()` against the live API. If it is not run, say so in the report; do not claim it.

## Documentation
- Task report `docs/task-reports/004-deadlock-api-client.md` (format of the Task 002 report). Mandatory section beyond the usual ones: **Confirmed upstream contract** (Step 0 output: final paths, parameters, JSON key names, the item-stats filters, and any deviation).
- `docs/PROJECT_CONTEXT.md`: record only verified facts (client exists, caches and TTLs, exceptions, confirmed endpoints, next eligible tasks 005/011-013 per their dependencies and 022 for metadata) and only if no other branch is active; otherwise mark pending integration in the report.
- No ADR is added: the client, library and TTLs follow ADR-0010; the item-stats TTL and size limits are recorded here and in the report.
- Follow-ups for the owner, **not part of this task** (do not edit these files here): `docs/roadmap.md` still needs the 022 entry; `docs/mvp-spec.md` metrics must change because there is no net-worth-at-10/20-min benchmark and the item benchmark is an average buy time (`avg_buy_time_s`), not a p50.

## Out of scope
- Match metadata in any form (`getMatchDetails`, `MatchDetails`, `/metadata`, `/metadata/raw`, protobuf, bz2): Tasks 022 and 023.
- Any slash command, `/link` parsing or Steam ID conversion (Task 005), `/profile` aggregation (Task 006), metrics computation, win/loss derivation, ranked-mode detection, net worth interpolation, "latest match" business rules, benchmarks interpretation (Task 007), the Claude/AI client (Task 008).
- Other analytics endpoints (`build-item-stats`, hero stats, counters; Tasks 011-013 extend the client in their own specs).
- Persistence, repositories, Flyway migrations, `match_analysis`.
- API keys, authentication, proxies, rate-limit throttling beyond the single `Retry-After` retry, circuit breakers, scheduled refresh, cache warm-up, `@Async`, parallel fetching.
- Redis or any other cache, per-user caches.
- New dependencies other than the one listed; generated OpenAPI clients; WebFlux/`WebClient`; enabling a web server (`web-application-type: none` stays).
- Any change to `messages.properties`, existing commands, `Messages`, `JdaConfig`, `BotProperties`, existing tests or ADRs.

## Dependencies and constraints
- Depends on Task 001 only. Task 003 is already merged, so `pom.xml` and `application.yaml` already contain its changes; this task only adds to them.
- Conventions (AGENTS.md): constructor injection, `final` fields, no Lombok, DTO records with `@JsonIgnoreProperties(ignoreUnknown = true)`, SLF4J, JUnit 5 + AssertJ, `MockRestServiceServer` + JSON fixtures in `src/test/resources/fixtures/`, secrets never in the repo.
- Surefire only runs classes named `*Test`/`Tests`; name every test class that way.
- Package dependencies: `clients` may depend on `config` (cache name constants, properties) and `clients.dto`; `clients` must not depend on `commands`, `services`, `repositories` or JDA.
- Users never see exception messages; they are for logs only.
- Task 022 depends on this one: keep `DeadlockApiConfig.restClientBuilder`, `Sleeper` and both exception types public and stable.

## Acceptance criteria
- [ ] Compiles, all tests pass (`.\mvnw.cmd clean verify`, 62 tests expected: 31 existing + 31 new)
- [ ] Unit tests for the client logic (31 new tests as listed; none needs a database or Docker)
- [ ] Follows AGENTS.md conventions
- [ ] Step 0 done: the "To verify" items are resolved, the confirmed contract is in the task report and any difference from this spec is listed as a deviation
- [ ] `DeadlockApiClient` public methods, annotations and keys match "Interfaces / signatures"; `getRanks`/`getHeroes`/`getItems` use distinct explicit cache keys
- [ ] History keeps at most 200 entries; history 429 with an array body is a success; every other 429, I/O error, 4xx/5xx and malformed body maps to the specified exception types without retry
- [ ] 503 is retried at most once, honoring `Retry-After` (default 2 s when missing/invalid, no sleep above the 10 s cap)
- [ ] Caches exist exactly as specified (names, TTLs, sizes, static mode); failures are not cached
- [ ] Benchmarks use `hero_ids` and `min_average_badge`/`max_average_badge` (`tier*10`..`tier*10+9`) with no timestamp and no buff flag; invalid tiers are rejected before any HTTP call
- [ ] `pom.xml` differs only by the single listed dependency, without an explicit version; `application.yaml` differs only by the appended `deadlock-api:` block
- [ ] No match metadata code; no user-facing text added; `messages.properties` untouched; no secret, real account id or real user data committed
- [ ] Task report created; `docs/PROJECT_CONTEXT.md` updated or marked pending integration; final diff has no unrelated files

## Decisions and open questions
Decisions taken in this spec (supported by the repository and the real responses):

1. **Scope split (owner choice)**: 004 = client infrastructure + history, benchmarks, item stats, assets, player rank. Match metadata moves to Task 022 (port `MatchMetadataSource`, JSON adapter) and Task 023 (raw protobuf fallback) so the metadata source can be swapped without touching consumers.
2. **Account check = non-empty match history**, using the cached `getMatchHistory`; no separate method, to avoid a self-invocation that skips the cache proxy.
3. **History cap of 200 entries** (decision of this spec; the real history of a heavy player is 5 MB). 200 matches cover any `/analyze` and `/profile` need described in the MVP spec.
4. **Item benchmark = average buy time** from `item-stats` (the only source found). It replaces the unavailable p50 purchase-time benchmark; `docs/mvp-spec.md` must be adjusted by the owner.
5. **Static `RestClient.builder()` instead of the auto-configured builder**, so base URL, headers and timeouts are explicit and testable, and no `RestClient.Builder` bean clashes with Spring Boot's.
6. **One retry only**, bounded by `max-retry-after` (10 s) because the call may block a Discord interaction thread; longer waits fail fast with `retryAfter()` so the caller can tell the user.
7. **`Sleeper` is injected** so retry timing is testable without real waiting; no `Clock` is needed because the benchmark window uses the API default.
8. **`include_buff_metrics` is not sent** (3 extra metrics not needed by the MVP); can be added later.
9. **Cache design**: Caffeine per-cache TTL (assets 24 h, benchmarks 6 h, history 2 min, rank 10 min per ADR-0010; item stats 6 h and all size limits chosen here), `sync = true`, static cache names, immutable cached values.
10. **Client returns API-shaped data**, no interpretation; Task 007 owns metric semantics.

Unresolved / assumptions (owner to confirm):

1. **`item-stats` request parameters.** The owner's sample response has the right shape but the request URL used is unknown. The spec assumes `hero_id`, `min_average_badge` and `max_average_badge`; Step 0 verifies them in the OpenAPI document. If no hero/badge filter exists, the implementer stops and asks.
2. **Starter coordinates.** `spring-boot-starter-restclient` is assumed to exist and manage `RestClient` and Jackson in Boot 4.1.1 (could not be checked against the BOM from the planning environment). The implementer must stop and ask if it does not resolve. The Jackson annotation package (`com.fasterxml.jackson.annotation` vs Jackson 3) must also be confirmed on the classpath.
3. **Benchmark population.** `player-stats/metrics` defaults to normal ranked/unranked matches in the last 30 days; Tasks 007/010 decide whether a stricter filter is needed.
4. **History 429 behavior** is documented by the API but was not observed; test 4 relies on the documentation.
5. **Baseline test count.** The 31 existing tests come from the Task 003 report; the implementer confirms the real baseline with one `verify` run on the fresh branch and reports any difference.
6. **Metadata availability** (503 "Steam is busy", strict Steam quota) is a risk handled by Tasks 022/023 and the degraded mode of Task 009, not here.
