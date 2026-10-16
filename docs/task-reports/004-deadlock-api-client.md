# Task 004: `DeadlockApiClient` (match history, benchmarks, item stats, assets, player rank, caching, 503 handling)

## Status

Completed and verified. `driver.sh verify` passes with 62 tests (31 existing + 31 new). Live check against `https://api.deadlock-api.com` passed for assets, benchmarks and item stats. Not pushed, not merged.

## Summary

- Added `spring-boot-starter-restclient` (BOM-managed, no version) to `pom.xml` and the `deadlock-api:` block to `application.yaml`.
- Added `DeadlockApiClient` (`clients/`) on Spring `RestClient`: match history (capped at 200 entries, also the account check), player rank, `player-stats/metrics` benchmarks, `item-stats` average buy times, and the ranks/heroes/items assets. No match metadata code.
- Errors map to `DeadlockApiException` (unexpected status, unreadable body) and `DeadlockApiUnavailableException` (503, 429, I/O error, timeout). HTTP 503 is retried once when `Retry-After` is within the 10 s cap; a history 429 with an array body is a success.
- Added `CacheConfig` (`@EnableCaching`, static `CaffeineCacheManager`, five caches with per-cache TTL and size), `DeadlockApiConfig` (`restClientBuilder`, `RestClient` and `Sleeper` beans) and `DeadlockApiProperties`.
- Added seven DTO records in `clients/dto/` and three test classes with nine JSON fixtures.

## Important Files

- `pom.xml`, `src/main/resources/application.yaml`
- `src/main/java/sntiago05/rejuv/config/DeadlockApiProperties.java`, `DeadlockApiConfig.java`, `CacheConfig.java`
- `src/main/java/sntiago05/rejuv/clients/DeadlockApiClient.java`, `DeadlockApiException.java`, `DeadlockApiUnavailableException.java`, `Sleeper.java`
- `src/main/java/sntiago05/rejuv/clients/dto/` (`MatchHistoryEntry`, `MetricPercentiles`, `ItemStat`, `PlayerRank`, `RankAsset`, `HeroAsset`, `ItemAsset`)
- `src/test/java/sntiago05/rejuv/clients/DeadlockApiClientTest.java` (24), `DeadlockApiClientCachingTest.java` (6)
- `src/test/java/sntiago05/rejuv/config/DeadlockApiPropertiesTest.java` (1)
- `src/test/resources/fixtures/deadlock-api/*.json` (9 files)

## Confirmed upstream contract

Checked against `https://api.deadlock-api.com/openapi.json` and live calls on 2026-10-16.

| Purpose | Request | Notes |
|---|---|---|
| Match history | `GET /v1/players/{account_id}/match-history` | Array of `PlayerMatchHistoryEntry`. Documented statuses 200, 400, 429, 500. 429: "Returns stored match history from ClickHouse as a fallback" (unless `force_refetch=true`, which we never send). |
| Benchmarks | `GET /v1/analytics/player-stats/metrics?hero_ids=&min_average_badge=&max_average_badge=` | Response `metric -> {avg, std, percentile1, 5, 10, 25, 50, 75, 90, 95, 99}`. **Key names `percentile25/50/75` confirmed.** Live call (hero 1, tier 7) returned 29 metrics. |
| Item stats | `GET /v1/analytics/item-stats?hero_ids=&min_average_badge=&max_average_badge=` | **Hero and badge filters exist.** `hero_ids` (string, comma separated) is current; `hero_id` (int) is marked `deprecated`. Badge filter uses the average badge of both teams (0..116). Default window: last 30 days. Fields `item_id`, `wins`, `losses`, `matches`, `avg_buy_time_s`, `avg_buy_time_relative` confirmed. Live call returned 140 items. |
| Player rank | `GET /v1/players/{account_id}/rank` | `RankResponse` with required `badge`, `rank`, `subrank`; `badge` is 0 when no recent ranked match reports a rank. |
| Assets | `GET /v1/assets/ranks`, `/v1/assets/heroes`, `/v1/assets/items` | Live: 12 ranks, 65 heroes, 774 items (250 with a tier), about 6.4 MB for items. |

## Decisions

- Jackson: the Boot 4.1.1 restclient starter brings Jackson 3 (`tools.jackson.core:jackson-databind` 3.1.5). Jackson 3 keeps its annotations in `com.fasterxml.jackson.annotation` (`jackson-annotations` 2.21), so the DTOs use that package.
- `CacheConfig` makes the `CaffeineCacheManager` static with `setCacheNames(List.of())`, then registers each cache with `registerCustomCache` (its own `expireAfterWrite` and `maximumSize`).
- On a failed retry (any non-2xx after the one 503 retry), the client throws `DeadlockApiUnavailableException` with `retryAfter()` from the second response header, or the default 2 s.
- An I/O error while reading a 2xx body (cause `IOException`) maps to `DeadlockApiUnavailableException`; a parse error maps to `DeadlockApiException`.
- Exception and log messages use the path template (for example `/v1/players/{account_id}/rank`), so no account id, query string or body is logged.
- `getItemStats` keeps the first entry if the API ever returns the same `item_id` twice.
- An empty history is cached for 2 minutes like any other value: a user who just started uploading matches may wait up to 2 minutes (accepted in the spec).

## Verification

- Baseline before changes: `.claude/skills/run-rejuv/driver.sh verify`: BUILD SUCCESS, 31 tests, 0 failures.
- Narrow runs: `./mvnw test -Dtest=DeadlockApiClientTest` (24 passed); `./mvnw test -Dtest='DeadlockApiClientCachingTest,DeadlockApiPropertiesTest'` (7 passed).
- Final: `.claude/skills/run-rejuv/driver.sh verify`: BUILD SUCCESS, **62 tests**, 0 failures, 0 errors, 0 skipped (`DeadlockApiClientTest` 24, `DeadlockApiClientCachingTest` 6, `DeadlockApiPropertiesTest` 1, plus the 31 existing, including `RejuvApplicationTests` and `MessagesIntegrationTest`, which prove the new beans wire up with no HTTP call at startup).
- Live check (not committed): `driver.sh eval` calling `getRanks()`, `getHeroes()`, `getItems()`, `getBenchmarks(1, 7)`, `getItemStats(1, 7)` returned 12 ranks, 65 heroes, 774 items, 29 metrics and 140 item stats; a second `getItems()` returned the same cached instance.
- Not checked live: `getMatchHistory` and `getPlayerRank`, because they need a real account id. They are covered by fixture tests only.
- `git diff --check`: clean. `pom.xml` and `application.yaml` diffs contain only the listed dependency and the appended block. Grep for token/key/secret/authorization in the new code and fixtures: none. Fixtures are LF. `.env` was never read.

## Deviations

- `item-stats` uses `hero_ids`, not the spec's candidate `hero_id`: both exist, but `hero_id` is deprecated in the OpenAPI document (Step 0 rule: use the verified one). The live call with `hero_ids` returned 200.
- Fixtures: the owner's evidence files are not in the repository. The assets, metrics and item-stats fixtures are trimmed from public live responses (no user data). The match history and rank fixtures are built from the OpenAPI schema with fake ids (account `1000001`, matches `900000001+`).
- The spec says the items asset has 764 entries (about 8.2 MB); the live call returned 774 entries (about 6.4 MB). No code impact.
- Small additions inside the specified tests: `itemStatsSendFiltersAndParseByItemId` also checks that the deprecated `hero_id` is not sent; `rateLimit429OutsideHistoryIsNotRetried` also checks `retryAfter()`. `DeadlockApiClientCachingTest` uses `@DirtiesContext` per method so each test starts with empty caches.

## Known Issues

- With Jackson 3 defaults, a `null` in a primitive DTO field may fail the parse and become `DeadlockApiException`. The OpenAPI document marks those fields as required and non-null.
- History 429 behavior is documented but was not observed live.
- Follow-ups for the owner (not part of this task): `docs/roadmap.md` still needs the Task 022 entry; `docs/mvp-spec.md` metrics must change (no net-worth-at-10/20-min benchmark; the item benchmark is `avg_buy_time_s`, not a p50).

## Merge Risks

- Edits `pom.xml` and `application.yaml`. No other unmerged task branch existed when this was written. Task 008 (Claude client) will likely touch the same files.
- Task 022 depends on `DeadlockApiConfig.restClientBuilder`, `Sleeper` and both exception types; keep them public and stable.
