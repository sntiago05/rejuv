# Task 021: `/items` (premium): your items against the meta

> Superseded: the premium tier, Patreon check and usage limits were removed by task 051 (ADR-0019). This report is kept as history.

## Status
Implemented on `feature/021-items-premium`. `./mvnw clean verify` passes (708 tests, 0 failures, 2 skipped). Not pushed, not merged.

## Summary
Added the `/items hero:<name> [rank:<tier>]` slash command for a player's linked account to compare their shop item win rates and purchase timings on a hero against the rank meta (30-day rank average). The feature is gated by `PremiumFeatureGate`, which allows free access when `rejuv.usage.enabled=false` (free launch period) or checks `PremiumChecker.isPremium(callerId)` when enabled. The presentation follows `EmbedStyle.COLOR`, hero card thumbnail via `AssetImages`, item icons via `IconCatalog.item` + `EmbedStyle.withIcon`, field limits through `EmbedStyle.fitField`, and degrades cleanly to text-only mode when emojis are absent.

## Step 0 Results
- **Spec assumption**: `GET /v1/players/{account_id}/item-stats?hero_ids={heroId}&min_unix_timestamp={now-90d}`.
- **API check**: The endpoint `/v1/players/{account_id}/item-stats` does not exist in `https://api.deadlock-api.com/openapi.json` and returned HTTP 404 live.
- **Documented endpoint**: `openapi.json` documents `GET /v1/analytics/item-stats`, supporting `account_id` (and `account_ids`), `hero_ids`, and `min_unix_timestamp`.
- **Live check with owner's account**: `GET https://api.deadlock-api.com/v1/analytics/item-stats?account_id=490900659&hero_ids=31&min_unix_timestamp=1784497204`.
  - HTTP status: `200 OK`.
  - Parameters that worked: `account_id`, `hero_ids`, `min_unix_timestamp` (and `account_ids`).
  - Row count: 26 rows.
  - Response row keys: `item_id`, `bucket`, `wins`, `losses`, `matches`, `players`, `avg_buy_time_s`, `avg_sell_time_s`, `avg_buy_time_relative`, `avg_sell_time_relative`, `sold`.
  - Result: Confirmed required fields `item_id`, `matches`, `wins`, the `hero_ids` filter, and `avg_buy_time_s` (average buy time in seconds). Anonymized fixture `player-item-stats.json` was created with invented IDs.

## Important Files
- `clients/dto/PlayerItemStat.java` (new): DTO for player item stats (`item_id`, `matches`, `wins`, `avg_buy_time_s`).
- `config/CacheConfig.java`: added `DEADLOCK_PLAYER_ITEM_STATS = "deadlockPlayerItemStats"` (10 min, max 200).
- `clients/DeadlockApiClient.java`: added `getPlayerItemStats(accountId, heroId, minUnixTimestamp)`.
- `services/PremiumFeatureGate.java` (new): reusable gate checking `UsageProperties.enabled()` and `PremiumChecker.isPremium(discordUserId)`. Never throws.
- `services/NoRankException.java` (new): runtime exception for unranked players without a rank override.
- `domain/ItemComparison.java` (new): comparison record holding player/meta metrics, delta points, and buy times.
- `domain/ItemsReport.java` (new): report record holding hero details, rank tier, underperforming, outperforming, and items considered count.
- `services/ItemsService.java` (new): business logic comparing player item stats against meta stats in a single pass using hash maps.
- `commands/ItemsCommand.java` (new): slash command `/items` with `hero` and `rank` options.
- `src/main/resources/messages.properties`: added `items.*` message catalog keys and added `/items` to `help.body`.
- `src/test/resources/fixtures/deadlock-api/player-item-stats.json` (new): fixture with invented item IDs.
- Tests:
  - `PlayerItemStatsClientTest` (new, 6 tests): endpoint query verification, fixture parsing, empty response, 503 retry, 500 error, malformed JSON, and unknown field handling.
  - `DeadlockApiClientCachingTest` (modified, +2 tests): cache hit verification on `getPlayerItemStats`, failure caching exclusion, TTL (10 min) and maximum size (200) verification.
  - `PremiumFeatureGateTest` (new, 4 tests): disabled limits allow anyone without calling checker; enabled limits allow premium and deny non-premium; throwing checker is caught and denies without throwing.
  - `ItemsServiceTest` (new, 10 tests): match thresholds (7 vs 8 player, 99 vs 100 meta), delta boundaries (-4.9 vs -5.0, +4.9 vs +5.0), worst/best ordering and tie-breaks (player matches then item id), capping at 5, non-upgrade / missing meta filtering, buy time conversions, rank override vs player rank, unranked exception, invalid override rejection, and hero not found exception.
  - `ItemsCommandTest` (new, 11 tests): command options and choices, ephemeral not-linked and not-premium rejections without deferrals or API calls, embed generation with thumbnail/icons, text-only embed fallback, empty results, error branches, and message key resolution.
  - `HelpCommandTest` (modified): verified `/items` presence in `help.body`.

## Decisions
- Used `GET /v1/analytics/item-stats` as confirmed by Step 0 live verification and OpenAPI docs (path constant `PLAYER_ITEM_STATS_PATH = "/v1/analytics/item-stats"`).
- `PlayerItemStat` includes `avgBuyTimeS` as confirmed by Step 0.
- `ItemsService` performs single-pass evaluation over `playerStats` using hash maps (`itemsById`, `metaStatsMap`), avoiding nested loops over large asset collections.
- `PremiumFeatureGate` is completely generic and reusable across future premium commands (e.g. Task 038 `/compare`).

## Verification
- Baseline build (`.\mvnw.cmd verify`): passed with 675 tests, 0 failures, 2 skipped.
- Full clean verify (`.\mvnw.cmd clean verify`): passed with 708 tests, 0 failures, 2 skipped (+33 tests).
- `git diff --check`: clean.
- Scope check: `git status --short` shows only files listed in the task specification table and closeout report.
- Privacy check: verified no real user account IDs, match IDs, tokens, or keys exist in the repository diff or fixtures.
- Manual Discord check: Not run (no Discord credentials available in this worktree).

## Deviations
- Endpoint path: The specification anticipated `GET /v1/players/{account_id}/item-stats`, which returned 404 in deadlock-api. In accordance with Step 0 instruction ("If the API documents other parameter names (check https://api.deadlock-api.com/openapi.json for that single path only), use the documented ones"), the documented endpoint `GET /v1/analytics/item-stats?account_id={accountId}&hero_ids={heroId}&min_unix_timestamp={minUnixTimestamp}` was verified live and implemented.

## Known Issues
None.

## Merge Risks
- Parallel active branches: `feature/017-web-report`, `feature/020-patreon-role-check`, `feature/029-coach-foundation`, `feature/030-analysis-text-cleanup`, `feature/036-deployment-ci`.
- `docs/PROJECT_CONTEXT.md` update is marked pending integration due to parallel active branches.
- Task 020 provides the production Patreon role implementation of `PremiumChecker`, which `PremiumFeatureGate` delegates to when usage limits are enabled.
