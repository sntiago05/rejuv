# Task 021: `/items` (premium): your items against the meta

- **Branch**: `feature/021-items-premium`
- **Implementer**: Antigravity
- **Related ADRs**: ADR-0014 (feature scope), ADR-0006 (premium), ADR-0016 (presentation); presentation convention in `docs/implementation-plan.md`
- **Depends on**: 007, 019 (merged). First half of the original task 021 (`/compare` becomes task 038 and comes later). Independent of 029, 017, 020.

## Goal
`/items hero:<name> [rank:<tier>]` shows, for the caller's **linked account** and one hero, which shop items they buy with a better or worse win rate than other players of the same hero and rank, plus how their buy times differ from the meta. It is a premium command, gated by a small reusable gate that Task 038 will reuse. No AI call.

## Step 0: verify the player item endpoint (mandatory, before coding)
The endpoint `GET /v1/players/{account_id}/item-stats` is listed in `docs/features.md` but **no repository evidence of its parameters or response exists** (the analytics `item-stats` shape is known; the player one is not). Before writing any code:
1. Make **one** sequential live read with the owner's linked account (get the id from the owner; do not read `.env`): `GET https://api.deadlock-api.com/v1/players/{account_id}/item-stats?hero_ids={heroId}&min_unix_timestamp={now-90d}`. If the API documents other parameter names (check `https://api.deadlock-api.com/openapi.json` for that single path only), use the documented ones.
2. Record in the report: HTTP status, parameter names that worked, the keys of one response row, and the row count.
3. The spec below assumes an array of rows with at least `item_id`, `matches` and `wins` (and optionally `avg_buy_time_s`). If the live response lacks `item_id`, `matches` or `wins`, or the endpoint does not accept a hero filter, **stop and report** to the owner; do not guess a different design.
Keep the (anonymized: no account ids) response shape as the basis of the test fixture `player-item-stats.json`.

## Files to create or modify
| File | Change |
|---|---|
| `clients/dto/PlayerItemStat.java` | **New** DTO (`@JsonIgnoreProperties(ignoreUnknown = true)`), only fields used. |
| `clients/DeadlockApiClient.java` | Modify: add `getPlayerItemStats`, its path constant and reader. Nothing else. |
| `config/CacheConfig.java` | Modify: add `DEADLOCK_PLAYER_ITEM_STATS = "deadlockPlayerItemStats"` (10 min, max 200). |
| `services/PremiumFeatureGate.java` | **New.** Reusable premium gate. |
| `services/ItemsService.java` | **New.** |
| `domain/ItemsReport.java`, `domain/ItemComparison.java` | **New** records. |
| `services/NoRankException.java` | **New** (`RuntimeException`). |
| `commands/ItemsCommand.java` | **New** slash command. |
| `src/main/resources/messages.properties` | Add keys at the end. |
| `src/main/resources/messages.properties` `help.body` | Add `/items` to the list (and keep every other line). |
| Tests | **New**: `ItemsServiceTest`, `ItemsCommandTest`, `PremiumFeatureGateTest`, `PlayerItemStatsClientTest`. Modify: `DeadlockApiClientCachingTest` (new cache and its TTL/size in `cacheTtlsAndSizesMatchTheDesign`), `HelpCommandTest`, `MessagesIntegrationTest` only if needed. |
| `src/test/resources/fixtures/deadlock-api/player-item-stats.json` | **New** fixture from Step 0, invented ids. |
| `docs/task-reports/021-items-premium.md` | **New.** Task report. |

Do not edit `UsageService`, `UsageConfig` or any other file. `docs/PROJECT_CONTEXT.md`: mark the update as pending integration.

## Interfaces / signatures

```java
// clients/DeadlockApiClient.java  (cache key "#accountId + ':' + #heroId + ':' + #minUnixTimestamp")
@Cacheable(cacheNames = CacheConfig.DEADLOCK_PLAYER_ITEM_STATS, sync = true, key = "...")
public List<PlayerItemStat> getPlayerItemStats(long accountId, int heroId, long minUnixTimestamp)
// PlayerItemStat: item_id (long), matches (long), wins (long); avg_buy_time_s (double) only if Step 0 confirms it, else omit.

// services/PremiumFeatureGate.java   @Service, constructor(UsageProperties, PremiumChecker)
public boolean allowed(long discordUserId)
// true when usage limits are disabled (rejuv.usage.enabled=false: premium features are open during the free launch period)
// or when PremiumChecker.isPremium(discordUserId). Never throws.

// services/ItemsService.java   @Service, two-constructor Clock pattern as ProfileService/HeroPoolService
public ItemsReport compare(long accountId, String heroInput, Integer rankOverride)
// @throws HeroNotFoundException, NoRankException (rank unknown and no override), NoMatchHistoryException is NOT used here

// domain/ItemComparison.java
public record ItemComparison(long itemId, String itemName, long playerMatches, double playerWinRatePercent,
        long metaMatches, double metaWinRatePercent, double deltaPoints, Double playerBuyMin, Double metaBuyMin) {}
// domain/ItemsReport.java
public record ItemsReport(int heroId, String heroName, int rankTier, List<ItemComparison> underperforming,
        List<ItemComparison> outperforming, int itemsConsidered) {}
```

## Behavior
1. **Command.** Slash command `items`: required `hero` (use `CommandSupport.heroOption`, with the same autocomplete as `/build` if it exists there) and optional `rank` (`CommandSupport.rankOption`). Works only for the **caller's own linked account** (no `user` option: premium data about other people is out of scope).
2. **Order of checks, before `deferReply()`:** (a) not linked: ephemeral `items.not_linked`; (b) `PremiumFeatureGate.allowed(callerId)` false: ephemeral `items.premium_required`. Then public `deferReply()`.
3. **Rank tier.** `rankOverride` if given; else the current tier from `DeadlockApiClient.getPlayerRank` (`badge > 0` means ranked, tier = `rank`); else `NoRankException` -> `items.no_rank`. Tier must be 1..11 (`ServiceSupport.validateRankTier`).
4. **Data (sequential, all cached):** resolve the hero with `HeroResolver`; `getPlayerItemStats(accountId, heroId, startOf90DayWindow)` (90-day UTC window like `HeroPoolService.PLAYER_WINDOW`); `getItemStats(heroId, tier)` for the meta (30-day, rank-tier win rates; `ItemStat.matches/wins`); assets via `GameAssets` for names; only items of type `upgrade`.
5. **Computation (single pass with hash maps, no nested loops):** for each player item with `playerMatches >= MIN_PLAYER_MATCHES` (constant, 8) and a meta entry with `metaMatches >= MIN_META_MATCHES` (constant, 100): `delta = playerWinRate - metaWinRate` in percentage points. `underperforming`: `delta <= -MIN_DELTA` (constant, 5.0 points), worst first, ties by more player matches then item id; `outperforming`: `delta >= MIN_DELTA`, best first, same ties. Each list capped at 5. `itemsConsidered` = number of items that passed the match thresholds. Buy times (minutes, one decimal) only when both sides provide them; null otherwise. Player win rate is `wins / matches * 100`; matches `0` entries are skipped.
6. **Embed** (follow the presentation convention: `EmbedStyle.COLOR`, hero card thumbnail via `AssetImages`, item icons via `IconCatalog.item` + `EmbedStyle.withIcon`, every field through `EmbedStyle.fitField`, must work text-only): title `items.title` (hero, rank label), fields "Costing you wins" (`underperforming`) and "Working for you" (`outperforming`), each line `Name: your win rate over N matches vs meta win rate (+/-X.X pts)` with the buy-time difference when available; footer explains the thresholds. If both lists are empty: ephemeral-style plain reply `items.no_data` (include `itemsConsidered` in the log, not the text).
7. **Errors:** `HeroNotFoundException` -> `items.hero_not_found`; `DeadlockApiUnavailableException` -> `error.data_source_busy`; any other `RuntimeException` -> `CommandSupport.handleUnexpectedError(...)`. One INFO log per command (user id, command, duration, outcome) as in the other commands.
8. Caveat in the footer: win rate with an item is correlational (it depends on how the game went); say so briefly (`items.footer`).

## Messages (English, at the end of `messages.properties`)
`items.description`, `items.option.hero`, `items.option.rank` (or reuse the shared option text if `CommandSupport` already has one), `items.not_linked`, `items.premium_required`, `items.no_rank`, `items.hero_not_found`, `items.no_data`, `items.title` (`{0}` hero, `{1}` rank), `items.field.underperforming`, `items.field.outperforming`, `items.entry` (item, your win rate, your matches, meta win rate, signed delta), `items.entry_buy_time` (appended buy-time suffix), `items.footer` (`{0}` min player matches, `{1}` min meta matches, correlation note). `/help` gains one line for `/items` marked premium.

## Out of scope
`/compare` (task 038); AI summaries; `user` option; ability or build suggestions; changing `UsageService` or limits; Patreon link or button; paginated output; web report; new dependencies; migrations.

## Tests
- `PlayerItemStatsClientTest` (pattern of `PlayerRanksClientTest`): exact path and query from Step 0, parsing of the fixture, empty array, 503 retry rule, unknown fields ignored. `DeadlockApiClientCachingTest`: cache hit on the second call and the new TTL/size row.
- `PremiumFeatureGateTest`: limits off -> allowed for anyone; limits on and premium -> allowed; limits on and not premium -> denied; a throwing `PremiumChecker` -> denied (never throws).
- `ItemsServiceTest` (mocks, fixed `Clock`): thresholds (7 vs 8 player matches, 99 vs 100 meta matches, delta 4.9 vs 5.0), ordering and tie-breaks, cap of 5, item without meta entry skipped, non-upgrade items skipped, buy time null when absent, rank override wins over the current rank, unranked without override throws `NoRankException`, invalid override rejected, unknown hero throws `HeroNotFoundException`, hash-map approach (no repeated scans of the stats lists).
- `ItemsCommandTest`: not linked and not premium replies are ephemeral and trigger no service/API call and no deferral; happy path embed with fields, thumbnail and icons; text-only embed when the icon catalog is empty (mandatory); empty result message; every error branch; every `items.*` key resolves; `help.body` mentions `/items`.

## Verification
`./mvnw clean verify` (on this machine `.\mvnw.cmd clean verify`, Docker running). Manual Discord check if a bot token is available (otherwise "not run"): `/items` with limits disabled (works), with `USAGE_LIMITS_ENABLED=true` and a non-premium user (premium message).

## Acceptance criteria
- [ ] Step 0 results recorded in the report (endpoint, parameters, response keys) before the code
- [ ] Compiles, all tests pass, counts recorded in the report
- [ ] Non-premium and not-linked users get ephemeral refusals and cause no API calls
- [ ] Output follows the presentation convention and works without emojis
- [ ] `PremiumFeatureGate` is reusable (no `/items` specific logic inside it)
- [ ] No new dependency, no file outside the list changed
- [ ] Task report created; `docs/PROJECT_CONTEXT.md` update marked pending integration
- [ ] Follows AGENTS.md conventions
