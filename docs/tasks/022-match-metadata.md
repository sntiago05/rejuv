# Task 022: Match metadata (`MatchMetadataSource` port, `MatchTelemetry` model, JSON adapter)

- **Branch**: `feature/022-match-metadata`
- **Implementer**: Antigravity
- **Related ADRs**: ADR-0004 and ADR-0010 (deadlock-api, hand-written client), ADR-0002 (Caffeine), ADR-0011 (analysis design)

## Goal
Give the rest of the bot per-match detail that the match history lacks (item purchase times, stat snapshots over time, power-up pickups) through a small port, `MatchMetadataSource`, that returns our own model, `MatchTelemetry`. The first adapter reads the JSON endpoint `GET /v1/matches/{match_id}/metadata`, reuses the Task 004 HTTP configuration, and caches the mapped result. Task 007 (`MetricsService`) consumes the port; Task 023 may add a raw protobuf fallback behind the same port.

## Context (verified in the repository when this spec was written)
Do not repeat this discovery. If a fact below is false in your checkout, stop and ask.

- Plan entry: `docs/implementation-plan.md`, task 022, depends on 004 only, implementer Antigravity. Task 007 depends on 004 and 022; Task 023 depends on 022.
- Task 004 is merged in `main` (PR #25). Start the branch from the latest `origin/main`.
- **This task runs in parallel with Task 005** (`feature/005-link-unlink`). Task 005 touches `commands/`, `services/`, `messages.properties` and `MessagesIntegrationTest`. This task must touch none of them. The only shared file in the repository that both could plausibly touch is `docs/PROJECT_CONTEXT.md` (see Documentation).
- Existing code you will use (do not change it unless the table below says so):
  - `config/DeadlockApiConfig`: `public static RestClient.Builder restClientBuilder(DeadlockApiProperties)` (base URL, `Accept: application/json`, `User-Agent`, connect/read timeouts) and the beans `RestClient deadlockApiRestClient` and `Sleeper sleeper`.
  - `config/DeadlockApiProperties(String baseUrl, Duration connectTimeout, Duration readTimeout, Duration defaultRetryAfter, Duration maxRetryAfter)`; `application.yaml` has `default-retry-after: 2s`, `max-retry-after: 10s`, `read-timeout: 10s`.
  - `clients/Sleeper` (`void sleep(Duration) throws InterruptedException`).
  - `clients/DeadlockApiException(String)` / `(String, Throwable)` and `clients/DeadlockApiUnavailableException(String message, Duration retryAfter, Throwable cause)` with `Optional<Duration> retryAfter()`. The Task 004 report asks to keep these public and stable.
  - `clients/DeadlockApiClient`: its 503-retry logic (`get`, `exchange`, `readBody`, `parseRetryAfter`) is **private**. Do not refactor or change `DeadlockApiClient` in this task; the adapter has its own small request method that follows the same rules (see Behavior).
  - `config/CacheConfig`: static `CaffeineCacheManager`; caches registered with `register(cacheManager, name, ttl, maximumSize)` and names exposed as `public static final String` constants.
  - Test pattern: `DeadlockApiClientTest` binds `MockRestServiceServer` to `DeadlockApiConfig.restClientBuilder(PROPERTIES)` and passes `sleeps::add` as the `Sleeper`; `DeadlockApiClientCachingTest` uses `@SpringJUnitConfig` + `@Import({CacheConfig.class, ...})` + `@DirtiesContext(AFTER_EACH_TEST_METHOD)`. Fixtures live in `src/test/resources/fixtures/deadlock-api/` and use fake ids (account `1000001`, matches `900000001+`).
- Upstream contract, checked on 2026-10-16 against `https://api.deadlock-api.com/openapi.json` and one live public response (match `118313783`, about 1.1 MB):
  - OpenAPI: path param `match_id` (int64 >= 0); optional query `is_custom`, `disable_steam`; responses 200 (untyped `object`, "see protobuf type CMsgMatchMetaDataContents"), 400, 404 "Match metadata not found", 429, 500. Rate limits per IP: from cache 100 req/s, from S3 100 req/10 s, **from Steam 3 req/h**.
  - Live 200 body: top-level keys `match_info`, `hero_build_ids`, `pregame_hero_ids`, `banned_hero_ids`. `match_info` has `match_id`, `duration_s`, `start_time`, `players` (12) and many fields we do not use.
  - Each player: `account_id`, `team` (0/1), `hero_id`, `items`, `stats`, `power_up_buffs`, plus fields we do not use (`kills`, `net_worth`, `death_details`, `player_rank_data` (null in the sample), ...).
  - `items[]`: `game_time_s`, `item_id` (unsigned 32-bit, for example `3612874202`, so it needs a `long`), `sold_time_s` (`0` = not sold), `upgrade_id`, `flags`, `imbued_ability_id`, `upgrade_info`. The list mixes shop items and ability upgrades; resolving and filtering by the items catalog is Task 007's job.
  - `stats[]` snapshots: `time_stamp_s` at 180, 360, 540, 720, 900, 1200, 1500, 1800 s ..., plus a final one at `duration_s` (the sample had 9: `...1800, 1952`). Each has `net_worth`, `player_damage`, `player_healing` and about 45 more fields.
  - `power_up_buffs[]`: `type` (for example `spirit_permanent_pickup_lv3`, `gun_powerup_pickup`), `value`, `is_permanent` (true and false both occur), `pickup_times_s` (int array), `pickup_stat_values`.
  - A non-existent match with `disable_steam=true` returned **503** `{"status":503,"error":"Match metadata recently failed to fetch from upstream. Retry later."}`, not 404. `docs/PROJECT_CONTEXT.md` also records a 503 "Steam is busy" with `Retry-After: 1265`. Both must end as `DeadlockApiUnavailableException`.

## Files to create or modify
| File | Change |
|---|---|
| `src/main/java/sntiago05/rejuv/domain/MatchTelemetry.java` | **Create.** Own model (record). |
| `src/main/java/sntiago05/rejuv/domain/PlayerTelemetry.java` | **Create.** Record. |
| `src/main/java/sntiago05/rejuv/domain/ItemPurchase.java` | **Create.** Record. |
| `src/main/java/sntiago05/rejuv/domain/StatSnapshot.java` | **Create.** Record. |
| `src/main/java/sntiago05/rejuv/domain/PowerUpBuff.java` | **Create.** Record. |
| `src/main/java/sntiago05/rejuv/clients/MatchMetadataSource.java` | **Create.** Port interface. |
| `src/main/java/sntiago05/rejuv/clients/MatchMetadataNotFoundException.java` | **Create.** |
| `src/main/java/sntiago05/rejuv/clients/JsonMatchMetadataSource.java` | **Create.** JSON adapter, `@Component`. |
| `src/main/java/sntiago05/rejuv/clients/dto/MatchMetadataResponse.java` | **Create.** API DTO records for this endpoint (nested records allowed in this one file). |
| `src/main/java/sntiago05/rejuv/config/CacheConfig.java` | **Modify.** Add only the `DEADLOCK_MATCH_METADATA` constant and its `register(...)` line. |
| `src/test/java/sntiago05/rejuv/clients/JsonMatchMetadataSourceTest.java` | **Create.** |
| `src/test/java/sntiago05/rejuv/clients/JsonMatchMetadataSourceCachingTest.java` | **Create.** |
| `src/test/java/sntiago05/rejuv/domain/MatchTelemetryTest.java` | **Create.** |
| `src/test/resources/fixtures/deadlock-api/match-metadata.json` | **Create.** Hand-built, fake ids (see Tests). |
| `docs/task-reports/022-match-metadata.md` | **Create.** Task report (format of the Task 004 report). |
| `docs/PROJECT_CONTEXT.md` | **Modify at closeout only if allowed** by the `AGENTS.md` parallel rule (see Documentation). |

No `pom.xml`, `application.yaml`, migration, `messages.properties`, command or service change is needed. If you think one is needed, stop and ask.

## Interfaces / signatures

### Own model (`sntiago05.rejuv.domain`, plain records, no JPA, no Jackson annotations)
```java
public record MatchTelemetry(long matchId, int durationS, List<PlayerTelemetry> players) {
    // compact constructor: players = List.copyOf(players)
    /** The player with this account id, if they played in the match. */
    public Optional<PlayerTelemetry> player(long accountId);
}

public record PlayerTelemetry(long accountId, int team, int heroId,
                              List<ItemPurchase> items,          // ordered by boughtAtS ascending (stable)
                              List<StatSnapshot> snapshots,      // ordered by timeS ascending
                              List<PowerUpBuff> powerUpBuffs) {
    // compact constructor: each list = List.copyOf(...)
}

/** soldAtS is null when the item was never sold (API sends 0). */
public record ItemPurchase(long itemId, int boughtAtS, Integer soldAtS) { }

public record StatSnapshot(int timeS, long netWorth, long playerDamage, long playerHealing) { }

/** pickupTimesS ordered ascending. */
public record PowerUpBuff(String type, boolean permanent, List<Integer> pickupTimesS) {
    // compact constructor: pickupTimesS = List.copyOf(...)
}
```
Keep only these fields. Kills, deaths, net worth totals, result and rank come from the match history and player rank (Task 004), not from here. No interpolation or metric logic here (Task 007).

### Port (`sntiago05.rejuv.clients`)
```java
public interface MatchMetadataSource {
    /**
     * Detailed telemetry for a finished match.
     * @throws MatchMetadataNotFoundException   the API says the match has no metadata (HTTP 404)
     * @throws DeadlockApiUnavailableException  busy, rate limited, Steam fallback failed, timeout, I/O
     * @throws DeadlockApiException             any other failure (unexpected status, unreadable or invalid body)
     */
    MatchTelemetry getMatch(long matchId);
}
```
Callers (Task 009 degraded mode) may treat every `DeadlockApiException` as "metadata unavailable".

### `MatchMetadataNotFoundException` (`sntiago05.rejuv.clients`)
```java
public class MatchMetadataNotFoundException extends DeadlockApiException {
    public MatchMetadataNotFoundException(String message);
}
```

### `JsonMatchMetadataSource` (`sntiago05.rejuv.clients`, `@Component implements MatchMetadataSource`)
```java
public JsonMatchMetadataSource(RestClient deadlockApiRestClient, DeadlockApiProperties properties, Sleeper sleeper)

@Override
@Cacheable(cacheNames = CacheConfig.DEADLOCK_MATCH_METADATA, sync = true)
public MatchTelemetry getMatch(long matchId);
```
Path constant: `"/v1/matches/{match_id}/metadata"`. Send no query parameters (not `disable_steam`, not `is_custom`): the API decides whether to use S3 or the Steam fallback.

### API DTOs (`sntiago05.rejuv.clients.dto.MatchMetadataResponse`)
Records annotated with `@JsonIgnoreProperties(ignoreUnknown = true)` and `com.fasterxml.jackson.annotation.JsonProperty` (same package as the Task 004 DTOs). Only these JSON fields:
- top level: `match_info`
- `match_info`: `match_id`, `duration_s`, `players`
- player: `account_id`, `team`, `hero_id`, `items`, `stats`, `power_up_buffs`
- item: `item_id` (long), `game_time_s`, `sold_time_s`
- stat: `time_stamp_s`, `net_worth`, `player_damage`, `player_healing`
- buff: `type`, `is_permanent`, `pickup_times_s`

Use nullable types (wrappers, nullable lists) for every list and for the buff fields, because the API may send `null` (`power_up_buffs` and `player_rank_data` are nullable in practice). The DTOs never leave `clients/`.

### `CacheConfig` addition
```java
public static final String DEADLOCK_MATCH_METADATA = "deadlockMatchMetadata"; // 24 h, max 300
register(cacheManager, DEADLOCK_MATCH_METADATA, Duration.ofHours(24), 300);
```
Metadata of a finished match does not change; the cached value is the mapped `MatchTelemetry` (far smaller than the 1 MB JSON). Exceptions are not cached (Spring default), so a 503 can be retried by the next command.

## Behavior

### Request and errors (same rules as Task 004, own code)
1. `GET {baseUrl}/v1/matches/{matchId}/metadata` through the injected `RestClient`.
2. **2xx**: read the body as `MatchMetadataResponse`, then map (below). A parse error → `DeadlockApiException`; an I/O error while reading the body (cause `IOException` / `UncheckedIOException`) → `DeadlockApiUnavailableException`; a `null` body → `DeadlockApiException`.
3. **503**: wait = `Retry-After` header (integer seconds > 0) or `properties.defaultRetryAfter()`. If wait > `properties.maxRetryAfter()`, throw `DeadlockApiUnavailableException` (with that wait) without retrying. Otherwise sleep through `Sleeper` once and repeat the request once; if the retry is not 2xx, throw `DeadlockApiUnavailableException` with the second response's `Retry-After` or the default. Interrupted sleep → restore the interrupt flag and throw `DeadlockApiUnavailableException`.
4. **429**: `DeadlockApiUnavailableException` with the parsed `Retry-After` (or null), no retry.
5. **404**: `MatchMetadataNotFoundException`, no retry.
6. Any other non-2xx status (400, 500, ...): `DeadlockApiException`, no retry.
7. `ResourceAccessException` (I/O, timeout) → `DeadlockApiUnavailableException`; other `RestClientException` → `DeadlockApiException`.
8. Logging: `WARN` lines like Task 004, using the path template only (`/v1/matches/{match_id}/metadata`). Never log the body. Logging the numeric match id at `DEBUG` is allowed (it is public data, not a user id); never log account ids.

### Mapping DTO → `MatchTelemetry`
1. `match_info` missing, or `players` missing/empty → `DeadlockApiException` ("invalid body").
2. `matchId` and `durationS` from `match_info`. If `match_info.match_id` differs from the requested id → `DeadlockApiException`.
3. Every player becomes a `PlayerTelemetry` (keep all players, so one cache entry serves every linked player of that match).
4. `items`: `null` → empty list; each entry → `ItemPurchase(item_id, game_time_s, sold_time_s > 0 ? sold_time_s : null)`; sort by `boughtAtS` ascending, stable (keep API order for equal times).
5. `stats`: `null` → empty list; each entry → `StatSnapshot`; sort by `timeS` ascending. Keep the final snapshot even if its time is not a multiple of 180.
6. `power_up_buffs`: `null` → empty list; each entry → `PowerUpBuff(type, Boolean.TRUE.equals(is_permanent), sorted pickup_times_s or empty)`. Skip an entry whose `type` is null.
7. Nothing is filtered by item type, buff type or team: interpretation belongs to Task 007.

### Wiring
- `JsonMatchMetadataSource` is the only `MatchMetadataSource` bean in this task, so it can be injected by the interface. Do not add `@Primary` or qualifiers (Task 023 decides how a fallback is wired).
- No HTTP call at startup (`RejuvApplicationTests` must stay green without network).

## Out of scope
- Any change to `DeadlockApiClient`, its DTOs, its tests or its fixtures; extracting a shared HTTP helper (possible later refactor, needs its own task).
- The raw endpoint `/metadata/raw`, bz2/protobuf, `FallbackMatchMetadataSource`, new dependencies (Task 023, needs an ADR).
- Metrics, interpolation of net worth at 10 min, item filtering through the items catalog, benchmarks (Task 007).
- `/analyze`, persistence, degraded mode, any command or user-facing text (Task 009).
- `pom.xml`, `application.yaml`, migrations, `messages.properties`, `commands/`, `services/`, `docs/roadmap.md`, `docs/mvp-spec.md`, ADRs, other task specs.
- Sending `disable_steam` or an API key; proactive prefetching of matches.

## Tests
JUnit 5 and AssertJ; none of the new tests uses Docker or the database.

### Fixture `fixtures/deadlock-api/match-metadata.json`
Hand-built (do **not** copy a live response: it contains real account ids). Shape as in the live sample, `match_info.match_id` = `900000003`, `duration_s` = `1952`, two players:
- account `1000001`, team `1`, hero `1`: three items out of order by `game_time_s` (one with `item_id` `3612874202` to prove the long, one with `sold_time_s` > 0, two with the same `game_time_s` to check stable order); snapshots at 180, 360, 540, 720, 900, 1200, 1500, 1800, 1952 (listed out of order once); two buffs, one `is_permanent: true` with two pickup times, one `is_permanent: false`.
- account `1000002`, team `0`, hero `13`: `power_up_buffs: null`, `player_rank_data: null`, one item, one snapshot.
Include unknown fields at every level (`hero_build_ids`, `objectives`, `kills`, `upgrade_id`, `gold_player`, `pickup_stat_values`, ...) to prove `ignoreUnknown`. LF line endings.

### `JsonMatchMetadataSourceTest` (`MockRestServiceServer` bound to `DeadlockApiConfig.restClientBuilder(PROPERTIES)`, `sleeps::add` as `Sleeper`, same `PROPERTIES` values as `DeadlockApiClientTest`)
1. `parsesFixtureIntoTelemetry`: GET to `/v1/matches/900000003/metadata` with no query string; result has match id, duration, 2 players with the right account id, team and hero.
2. `mapsItemsSortedWithSoldTimeAndLongIds`: order by `boughtAtS`, stable for equal times; `soldAtS` null for `0`, set otherwise; `3612874202` kept exactly.
3. `mapsSnapshotsSortedIncludingFinal`: times ascending, last is `1952`; net worth, damage and healing values match the fixture.
4. `mapsBuffsAndNullBuffsAsEmpty`: permanent flag and sorted pickup times for player `1000001`; player `1000002` has an empty buff list.
5. `retriesOnceOn503WithinCap`: 503 with `Retry-After: 1`, then 200 → success; `sleeps` = `[1 s]`.
6. `503WithoutRetryAfterUsesDefaultWait`: 503 without the header, then 200 → `sleeps` = `[2 s]`.
7. `503AboveCapIsNotRetried`: 503 with `Retry-After: 1265` → `DeadlockApiUnavailableException`, `retryAfter()` = 1265 s, exactly one request, no sleep.
8. `503TwiceThrowsUnavailable`: 503 (`Retry-After: 1`) twice → `DeadlockApiUnavailableException`; two requests.
9. `rateLimited429IsNotRetried`: → `DeadlockApiUnavailableException`, one request.
10. `notFound404ThrowsNotFound`: → `MatchMetadataNotFoundException`, one request.
11. `serverError500ThrowsApiException`: → `DeadlockApiException` that is **not** a `DeadlockApiUnavailableException`.
12. `ioErrorThrowsUnavailable`: `withException(new IOException())` → `DeadlockApiUnavailableException`.
13. `invalidBodiesThrowApiException` (parameterized or separate): `{}`, `{"match_info":{"match_id":900000003,"duration_s":10,"players":[]}}`, a different `match_id`, and malformed JSON → `DeadlockApiException` (not unavailable).

### `JsonMatchMetadataSourceCachingTest` (pattern of `DeadlockApiClientCachingTest`)
1. `secondCallForSameMatchIsCached`: one request expected, two `getMatch(900000003)` calls return equal results.
2. `differentMatchesAreCachedSeparately`.
3. `failuresAreNotCached`: first call 500 → exception; second call 200 → success (two requests).
4. `cacheHasConfiguredTtlAndSize`: the `deadlockMatchMetadata` Caffeine cache reports `expireAfterWrite` 24 h and maximum 300 (same technique as the Task 004 caching test).

### `MatchTelemetryTest`
1. `playerFindsByAccountId` and returns empty for an unknown account.
2. `listsAreImmutableCopies`: changing the source list after construction does not change the record; the returned lists reject `add`.

## Verification
1. `./mvnw clean verify` passes (Docker running for the existing Testcontainers tests). On Windows use `.\mvnw.cmd clean verify` (or `.claude/skills/run-rejuv/driver.sh verify`). Report the real test count and the difference from the baseline you measured on your branch before changing anything (62 at the Task 004 closeout; it will be higher if Task 005 merged first).
2. Optional live check (not committed): call `getMatch` through `driver.sh eval` for a recent public match id taken from `GET /v1/matches/recently-fetched`, and report player count, snapshot count and item count. If you skip it or it gets a 503, say so; do not claim it.
3. `git diff --check`; review the final diff: only the files in the table changed; `CacheConfig` diff is exactly one constant and one `register` line; no real account id in fixtures; no secret; `.env` was never read or printed.

## Documentation
- Task report `docs/task-reports/022-match-metadata.md`: status, summary, important files, commands run with real results, deviations, known issues. Include as follow-ups: (a) when Task 023 adds a fallback, the cache should wrap the combined source, not only the JSON adapter; (b) possible later refactor to share the request/503 logic between `DeadlockApiClient` and `JsonMatchMetadataSource`; (c) a non-existent match returns 503, not 404, so callers cannot tell "never exists" from "try later".
- `docs/PROJECT_CONTEXT.md`: Task 005 is being implemented in parallel. **If the Task 005 branch is not merged into `main` at your closeout, do not edit this file**; write "PROJECT_CONTEXT update pending integration" in the report with the facts to add (port, model, adapter, cache, errors, Task 007 now unblocked by 022). If Task 005 is already merged, update it with verified facts only, without removing Task 005's entries.
- No ADR is added. Do not edit `docs/implementation-plan.md`, `docs/roadmap.md`, `docs/mvp-spec.md`, `docs/architecture.md` or other task specs.

## Acceptance criteria
- [ ] `./mvnw clean verify` passes; test count and baseline difference are in the report
- [ ] `MatchMetadataSource.getMatch` returns `MatchTelemetry` built only from the listed fields; all 12 (fixture: 2) players are kept
- [ ] Items, snapshots and pickup times are sorted ascending; `sold_time_s` 0 becomes `null`; item ids are `long`; null lists become empty lists
- [ ] 503 is retried once only when the wait is within `max-retry-after`; 429, 404 and other statuses are not retried; each failure maps to the exception in the port Javadoc
- [ ] Invalid bodies (no `match_info`, no players, other match id, malformed JSON) throw `DeadlockApiException`
- [ ] Results are cached in `deadlockMatchMetadata` (24 h, max 300); failures are not cached
- [ ] No query parameters are sent; no body, account id or secret is logged
- [ ] DTOs stay in `clients/dto`; domain records have no Jackson or JPA annotations; no JDA types anywhere in this task
- [ ] `DeadlockApiClient` and every file outside the table are unchanged; no new dependency
- [ ] Task report created; `docs/PROJECT_CONTEXT.md` updated or marked pending integration; final diff has no unrelated files

## Decisions and open questions
Decisions taken in this spec:
1. **Port in `clients/`, model in `domain/`.** The adapters are external API code, so the interface lives next to them; `MatchTelemetry` is our own record so Task 007 never sees API DTOs and Task 023 can map protobuf into the same model.
2. **Own request method instead of refactoring `DeadlockApiClient`.** Avoids touching merged, tested code while Task 005 runs in parallel. The duplication (about 60 lines) is accepted and recorded as a follow-up.
3. **Exceptions, not `Optional`, for missing metadata.** An empty `Optional` would be cached for 24 h, while metadata often appears later (503 now, 200 later was observed).
4. **Cache the mapped model for 24 h.** Finished matches are immutable; the Steam fallback allows only 3 requests per hour per IP, so repeated lookups must hit the cache.
5. **Minimal model.** Only items, three snapshot values and power-ups. Adding fields later is cheap; Task 007's spec may extend the model and DTO.
6. **No `disable_steam`.** Let the API use the Steam fallback when S3 misses; its quota errors map to "unavailable".

Open for the owner (not blocking):
1. Whether Task 007 needs more snapshot fields (for example `creep_kills`, `player_damage_taken`) or `player_rank_data.initial_display_rank`, whose shape was not observed (null in the live sample). Extend in the Task 007 spec if so.
2. `docs/roadmap.md` still lacks the Task 022 entry (owner follow-up from Task 004).
