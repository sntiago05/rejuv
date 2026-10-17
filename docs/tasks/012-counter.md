# Task 012: `/counter hero [rank]`

- **Branch**: `feature/012-counter`
- **Implementer**: Antigravity
- **Related ADRs**: ADR-0014 (free hero utilities, shared caches first), ADR-0010 and ADR-0004 (deadlock-api), ADR-0002 (Caffeine), ADR-0008 (English, i18n-ready)

## Goal
Add `/counter hero [rank]`: a public embed that tells a player how to beat a hero, for all ranks or one rank tier: the heroes that win most against it, the heroes it beats most often, and the items with the best win rate against it. No account link and no AI call. It adds two analytics reads (`hero-counter-stats`, and `item-stats` filtered by enemy hero) to `DeadlockApiClient`, cached and shared by all users.

## Context (verified in the repository when this spec was written)
Do not repeat this discovery. If a fact below is false in your checkout, stop and ask.

- Plan entry: `docs/implementation-plan.md`, task 012, implementer Antigravity. The plan lists dependency 004 only, but this spec also **requires Task 011 merged into `main`**: it reuses `services/HeroResolver` and `services/HeroNotFoundException`, which Task 011 creates (`docs/tasks/011-build.md`). If `HeroResolver` does not exist in your checkout, stop and ask; **do not create or copy it**.
- Tasks 004-008, 013 and 022 are merged in `main` (243 tests per `docs/PROJECT_CONTEXT.md`, more once 011 is merged). Start the branch from the latest `origin/main`.
- Reference implementations: Task 013 (`docs/tasks/013-meta.md`) and Task 011. Mirror them: `DeadlockApiClient.getHeroStats` and its private `heroStatsFilter(UriBuilder, Integer rankTier, long minUnixTimestamp)`/`requireRankTier(int)`, `MetaService` (two-constructor `Clock` pattern, window = start of the current UTC day minus 30 days, tier validation 1..11), `MetaCommand` (public deferral, optional `INTEGER` `rank` option with 11 choices labeled `meta.rank.<tier>`, own error handling after `deferReply()`), `BuildService`/`BuildCommand` (hero option and `HeroNotFoundException` handling).
- Existing code you will use: `DeadlockApiClient.get(path, uriFunction, typeRef, false)`, the existing private `ITEM_STATS_PATH = "/v1/analytics/item-stats"` and `ITEM_STATS_TYPE` (`List<ItemStat>`), `getHeroes()` and `getItems()` (both cached in `deadlockAssets`, 24 h). `clients/dto/ItemStat(long itemId, long matches, long wins, long losses, double avgBuyTimeS, double avgBuyTimeRelative)` already exists and is reused as is. `ItemAsset(long id, String className, String name, String type, Integer itemTier, String itemSlotType, Integer cost)`: `type` is `upgrade`, `ability` or `weapon`.
- Catalog keys reused: `meta.rank.1` ... `meta.rank.11`, `meta.all_ranks`, `meta.option.rank`, `error.data_source_busy`, `error.generic`.
- Facts from `https://api.deadlock-api.com/openapi.json` (read 2026-10-17 by the architect; response bodies were **not** called live):
  - `GET /v1/analytics/hero-counter-stats`: query `min_unix_timestamp` (default 30 days ago), `min_average_badge`/`max_average_badge` (0..116), `same_lane_filter` (boolean, default **true**), `min_matches` (default 20), `game_mode` (default `normal`), `match_mode` (default `ranked,unranked`). **There is no hero filter**: the response is an array with one row per ordered pair: `hero_id`, `enemy_hero_id`, `wins` ("times `hero_id` won when facing `enemy_hero_id`"), `matches_played`, plus many `kills`/`enemy_kills`-style totals we do not use. Cached 6 h by the server.
  - `GET /v1/analytics/item-stats`: `enemy_hero_ids` (comma-separated string; "what beats hero X"), `min_unix_timestamp`, `min_average_badge`/`max_average_badge`, `min_matches` (default 20), `bucket` (enum including `no_bucket`), `same_lane_filter` (default false, ignored without `enemy_hero_ids`), `hero_ids` (string, optional). Response is an array of `ItemStats` with `item_id`, `wins`, `losses`, `matches`, ... (already mapped by `ItemStat`).
  - Analytics rate limit: 200 req/min per IP, shared by all analytics endpoints.
- **Not verified yet** (Step 0): the real `hero-counter-stats` row count and that both directions of a pair appear; that `item-stats?enemy_hero_ids=<id>` without `hero_ids` and without `bucket` returns one row per item.
- Rules from `AGENTS.md`: DTOs are records with `@JsonIgnoreProperties(ignoreUnknown = true)` and only the fields used; every client read is cached; never fire parallel uncached analytics requests; no business logic in commands; services use no JDA types; all user-facing text in `messages.properties`.

## Step 0: verify the endpoints (before writing code)
This needs network access. If you have none, ask the owner to run the calls and paste shortened bodies. Use a real hero id from `getHeroes()` and the badge range of one tier (e.g. 70..79). At most one request per endpoint; respect the rate limit. Confirm and record in the report:
1. `GET /v1/analytics/hero-counter-stats?min_unix_timestamp=<ts>`: array of pair rows; approximate row count; for the chosen hero, rows exist with `hero_id == id` and with `enemy_hero_id == id`; whether `hero_id == enemy_hero_id` rows exist (mirror matches).
2. `GET /v1/analytics/item-stats?enemy_hero_ids=<id>&min_unix_timestamp=<ts>`: one row per item (no duplicated `item_id`), rows include `matches` and `wins`.

If a field only has a different JSON name, use the real name in `@JsonProperty`, keep the Java names below and report it. If the structure differs (no per-pair rows, no `matches_played`/`wins`, several rows per item), **stop and ask**.

## Files to create or modify
| File | Change |
|---|---|
| `src/main/java/sntiago05/rejuv/clients/dto/HeroCounterStat.java` | **Create.** DTO record. |
| `src/main/java/sntiago05/rejuv/clients/DeadlockApiClient.java` | **Modify.** Only add the counter path constant and type reference, `getHeroCounterStats`, `getItemStatsAgainst` and, if needed, one private helper. Reuse `heroStatsFilter` if it fits; do not change existing methods' behavior. |
| `src/main/java/sntiago05/rejuv/config/CacheConfig.java` | **Modify.** Only add `DEADLOCK_HERO_COUNTERS`, `DEADLOCK_COUNTER_ITEMS` and their `register(...)` lines. |
| `src/main/java/sntiago05/rejuv/domain/CounterMatchup.java` | **Create.** Record. |
| `src/main/java/sntiago05/rejuv/domain/CounterItem.java` | **Create.** Record. |
| `src/main/java/sntiago05/rejuv/domain/CounterGuide.java` | **Create.** Record. |
| `src/main/java/sntiago05/rejuv/services/CounterService.java` | **Create.** |
| `src/main/java/sntiago05/rejuv/commands/CounterCommand.java` | **Create.** |
| `src/main/resources/messages.properties` | **Modify.** Append the `counter.*` keys; add one `/counter hero [rank]` line to `help.body` after the `/build hero [rank]` line (after `/meta [rank]` if Task 011 is not merged yet, which should not happen). Change nothing else. |
| `src/test/resources/fixtures/deadlock-api/hero-counter-stats.json` | **Create.** Real shape from Step 0, **invented** numbers, 8-10 pair rows among 4 heroes (both directions, one mirror row), with unknown fields. |
| `src/test/java/sntiago05/rejuv/clients/DeadlockApiClientTest.java` | **Modify.** Add the new endpoint tests only. |
| `src/test/java/sntiago05/rejuv/clients/DeadlockApiClientCachingTest.java` | **Modify.** Add the two new caches to the exact-set maps and one caching test per method. |
| `src/test/java/sntiago05/rejuv/services/CounterServiceTest.java` | **Create.** |
| `src/test/java/sntiago05/rejuv/commands/CounterCommandTest.java` | **Create.** |
| `src/test/java/sntiago05/rejuv/commands/HelpCommandTest.java` | **Modify.** Only add `"/counter"` to the asserted command list. |
| `src/test/java/sntiago05/rejuv/services/MessagesIntegrationTest.java` | **Modify.** Only add the new keys to `requiredKeys` and `"counter"` to the command-name assertion. |
| `docs/task-reports/012-counter.md` | **Create.** Task report (format of the Task 013 report). |

No new item-stats fixture: the client test for `getItemStatsAgainst` reuses the existing `fixtures/deadlock-api/item-stats.json`. No `pom.xml`, `application.yaml`, migration, entity or repository change is needed. If you think one is needed, stop and ask.

## Interfaces / signatures

### `HeroCounterStat` (`sntiago05.rejuv.clients.dto`, new)
```java
@JsonIgnoreProperties(ignoreUnknown = true)
public record HeroCounterStat(@JsonProperty("hero_id") int heroId,
                              @JsonProperty("enemy_hero_id") int enemyHeroId,
                              @JsonProperty("wins") long wins,
                              @JsonProperty("matches_played") long matchesPlayed) { }
```

### `DeadlockApiClient` (add)
```java
private static final String HERO_COUNTERS_PATH = "/v1/analytics/hero-counter-stats";

/**
 * Every hero-versus-hero pair since minUnixTimestamp. rankTier null = all ranks (no badge filter).
 * Callers must not call the analytics methods in parallel for different uncached keys.
 * @throws IllegalArgumentException if rankTier is non-null and not in 0..11 (before any HTTP call)
 */
@Cacheable(cacheNames = CacheConfig.DEADLOCK_HERO_COUNTERS, sync = true, key = "#rankTier + ':' + #minUnixTimestamp")
public List<HeroCounterStat> getHeroCounterStats(Integer rankTier, long minUnixTimestamp);

/**
 * Win and match counts per item when the enemy team has enemyHeroId. Same rank, window and error rules.
 * One row per item. Not hero-specific: it covers the items bought by any hero facing enemyHeroId.
 */
@Cacheable(cacheNames = CacheConfig.DEADLOCK_COUNTER_ITEMS, sync = true,
        key = "#enemyHeroId + ':' + #rankTier + ':' + #minUnixTimestamp")
public List<ItemStat> getItemStatsAgainst(int enemyHeroId, Integer rankTier, long minUnixTimestamp);
```
Requests (no other parameter, in particular no `same_lane_filter`, `bucket`, `hero_ids` or `min_matches`):
- `getHeroCounterStats`: `min_unix_timestamp=<minUnixTimestamp>`, and when `rankTier` is non-null `min_average_badge=rankTier*10`, `max_average_badge=rankTier*10+9`.
- `getItemStatsAgainst`: `enemy_hero_ids=<enemyHeroId>` plus the same timestamp and badge parameters.
Same error mapping and 503 retry as the other analytics methods (through `get`, with `false` as the last argument). Both return `List.copyOf(...)`.

### `CacheConfig` (add)
```java
public static final String DEADLOCK_HERO_COUNTERS = "deadlockHeroCounters";   // 6 h, max 50
public static final String DEADLOCK_COUNTER_ITEMS = "deadlockCounterItems";   // 6 h, max 200
```
with `register(cacheManager, DEADLOCK_HERO_COUNTERS, Duration.ofHours(6), 50);` and `register(cacheManager, DEADLOCK_COUNTER_ITEMS, Duration.ofHours(6), 200);`. The pair payload is global per rank (about 1,400 rows), so 50 entries is enough.

### Domain (`sntiago05.rejuv.domain`, new)
```java
/** heroName is null when the hero id is not in the heroes asset. winRatePercent is unrounded. */
public record CounterMatchup(int heroId, String heroName, long matches, double winRatePercent) { }

/** itemName is never null: items without a name in the items asset are dropped. winRatePercent is unrounded. */
public record CounterItem(long itemId, String itemName, long matches, double winRatePercent) { }

/**
 * rankTier null = all ranks. All lists immutable and possibly empty.
 * counters: heroes that win most against the target hero (their win rate against it), best first.
 * favorable: heroes the target hero beats most often (the target hero's win rate against them), best first.
 * items: items with the best win rate when the enemy team has the target hero, best first.
 */
public record CounterGuide(int heroId, String heroName, Integer rankTier, List<CounterMatchup> counters,
                           List<CounterMatchup> favorable, List<CounterItem> items) { }
```

### `CounterService` (`sntiago05.rejuv.services`, `@Service`, new)
```java
static final int MIN_MATCHUP_MATCHES = 50;
static final int MIN_ITEM_MATCHES = 100;
static final int MAX_MATCHUPS = 5;
static final int MAX_ITEMS = 5;
static final String UPGRADE_TYPE = "upgrade";
static final Duration WINDOW = Duration.ofDays(30);
static final int MIN_RANK_TIER = 1;
static final int MAX_RANK_TIER = 11;

@Autowired
public CounterService(DeadlockApiClient deadlockApiClient, HeroResolver heroResolver)          // Clock.systemUTC()
CounterService(DeadlockApiClient deadlockApiClient, HeroResolver heroResolver, Clock clock)    // package-private, for tests

/**
 * @param heroInput text typed by the user
 * @param rankTier null = all ranks, else 1..11
 * @throws IllegalArgumentException for a tier outside 1..11 (before any call)
 * @throws HeroNotFoundException if the input does not resolve to a hero
 * Lets DeadlockApiUnavailableException / DeadlockApiException propagate.
 */
public CounterGuide getCounterGuide(String heroInput, Integer rankTier);
```

### `CounterCommand` (`sntiago05.rejuv.commands`, `@Component implements SlashCommand`, new)
- `CounterCommand(CounterService counterService, Messages messages)`.
- `name()` = `counter`; `data()` = `Commands.slash("counter", messages.get("counter.description"))` with two options, in this order: (1) `STRING` `hero`, description `messages.get("counter.option.hero")`, **required**, max length 50; (2) `INTEGER` `rank`, description `messages.get("meta.option.rank")`, **not** required, 11 choices with value `1..11` and name `messages.get("meta.rank." + tier)` (same as `MetaCommand`).

## Behavior

### `CounterService.getCounterGuide(heroInput, rankTier)`
1. Validate `rankTier` (null or 1..11) before any call.
2. `hero = heroResolver.resolve(heroInput)`; empty → `HeroNotFoundException(heroInput)`. No stats call is made.
3. `minUnixTimestamp` = start of the current UTC day (from the clock) minus 30 days, in epoch seconds.
4. Calls in this order, sequentially: `getHeroCounterStats(rankTier, ts)`, `getItemStatsAgainst(hero.id(), rankTier, ts)`, `getHeroes()`, `getItems()`.
5. **Counters.** From the pair rows with `enemyHeroId == hero.id()`, `heroId != hero.id()` and `matchesPlayed >= MIN_MATCHUP_MATCHES`: `winRatePercent = wins * 100.0 / matchesPlayed` (the opponent's win rate against the target hero). Duplicate `heroId`: keep the first. Sort by `winRatePercent` desc, then `matches` desc, then `heroId` asc; keep `MAX_MATCHUPS`.
6. **Favorable.** From the rows with `heroId == hero.id()`, `enemyHeroId != hero.id()` and `matchesPlayed >= MIN_MATCHUP_MATCHES`: `CounterMatchup.heroId` is the **opponent** (the row's `enemyHeroId`) and `winRatePercent = wins * 100.0 / matchesPlayed` (the target hero's win rate against it). Same de-duplication, sorting and cap.
7. **Items.** From `ItemStat` rows with `matches >= MIN_ITEM_MATCHES` whose id resolves in `getItems()` to an asset with `type` equal to `UPGRADE_TYPE` (ignoring case) and a non-blank `name`: `winRatePercent = wins * 100.0 / matches`. Unknown ids and other types are dropped. Duplicate `itemId`: keep the first. Sort by `winRatePercent` desc, then `matches` desc, then `itemId` asc; keep `MAX_ITEMS`.
8. `heroName` of matchups from `HeroAsset.name` or `null`. Return `CounterGuide(hero.id(), hero.name(), rankTier, counters, favorable, items)`.

### `/counter hero [rank]`
1. `event.deferReply().queue()` (public). No link check.
2. Read `hero` (String) and optional `rank` (`Integer`, null when absent). Call `getCounterGuide` synchronously and answer through the hook:
   - `counters`, `favorable` and `items` all empty → `editOriginal(counter.no_data).queue()`;
   - otherwise → `editOriginalEmbeds(embed).queue()`;
   - `HeroNotFoundException` → `editOriginal(counter.hero_not_found).queue()` (do **not** echo the user input);
   - `DeadlockApiUnavailableException` → `editOriginal(error.data_source_busy).queue()`;
   - any other `RuntimeException` → error id `UUID.randomUUID()`, log `ERROR` with id, caller id, hero input, rank tier and the exception, `editOriginal(error.generic)` with the id. Do not rethrow.

### Embed
Built with JDA `EmbedBuilder`; every text from `Messages`; numbers pre-formatted with `String.format(Locale.ROOT, "%.1f", value)`.
- Title: `counter.title` with `{0}` = hero name, `{1}` = `meta.rank.<tier>` or `meta.all_ranks` when the tier is null.
- Fields (not inline), each shown **only if its list is non-empty**, in this order:
  1. `counter.field.counters`: one `counter.matchup_entry` per line (`{0}` = hero name or `counter.hero_unknown` with `{0}` = id, `{1}` = win rate), joined with `\n`.
  2. `counter.field.items`: one `counter.item_entry` per line (`{0}` = item name, `{1}` = win rate).
  3. `counter.field.favorable`: same line format as counters.
- Footer: `counter.footer` with `{0}` = `MIN_MATCHUP_MATCHES`, `{1}` = `MIN_ITEM_MATCHES`.

## User-facing text (`messages.properties`, append, English)
Messages with arguments must not contain a single apostrophe. Command and option texts: 1-100 chars.

| Key | Intent |
|---|---|
| `counter.description` | Command description: how to beat a hero (best counters and items) |
| `counter.option.hero` | Option description: hero to counter, e.g. Haze |
| `counter.title` | `{0}` hero, `{1}` rank label, e.g. "How to beat {0}: {1}" |
| `counter.field.counters` | Field name, e.g. "Heroes that beat it" |
| `counter.field.items` | Field name, e.g. "Items that win against it" |
| `counter.field.favorable` | Field name, e.g. "Heroes it beats" |
| `counter.matchup_entry` | `{0}` hero, `{1}` win rate, e.g. "{0} - {1}%" |
| `counter.item_entry` | `{0}` item, `{1}` win rate, e.g. "{0} - {1}%" |
| `counter.hero_unknown` | `{0}` id, e.g. "Hero #{0}" |
| `counter.footer` | `{0}` min matchup matches, `{1}` min item matches: last 30 days, matchups with at least {0} matches, items with at least {1}; item win rates are not adjusted for item cost or timing |
| `counter.hero_not_found` | Hero not recognized: check the spelling and try again |
| `counter.no_data` | Not enough recent matches for this hero and rank yet; try another rank or all ranks |
| `help.body` (one added line) | `` `/counter hero [rank]` - See which heroes and items beat a hero.`` after the `/build hero [rank]` line |

## Tests
JUnit 5, AssertJ, Mockito; `MockRestServiceServer` and the fixtures for the client; `MessagesFixture.english()` for commands.

### `DeadlockApiClientTest` (add)
1. `heroCountersSendBadgeRangeAndTimestamp`: `getHeroCounterStats(7, 1789000000L)` sends `min_average_badge=70`, `max_average_badge=79`, `min_unix_timestamp=1789000000` and nothing else; parses the fixture.
2. `heroCountersWithoutRankSendNoBadgeFilter`; `heroCountersRejectTierOutOfRange` (12 and -1, no HTTP call).
3. `itemStatsAgainstSendsEnemyHeroBadgeRangeAndTimestamp`: `getItemStatsAgainst(61, 7, 1789000000L)` sends `enemy_hero_ids=61`, the badge range and `min_unix_timestamp`, and nothing else (no `hero_ids`); parses `item-stats.json` into `List<ItemStat>`; `itemStatsAgainstWithoutRankSendsNoBadgeFilter`; `itemStatsAgainstRejectsTierOutOfRange`.
4. `heroCountersServiceUnavailableIsMapped` and `itemStatsAgainstServiceUnavailableIsMapped` (503 with a large `Retry-After` → `DeadlockApiUnavailableException`), following the existing analytics tests.

### `DeadlockApiClientCachingTest` (modify)
Add both caches to the exact-set maps (`deadlockHeroCounters` 6 h / 50, `deadlockCounterItems` 6 h / 200). Add `heroCountersAreCachedPerTierAndTimestamp` and `itemStatsAgainstAreCachedPerHeroTierAndTimestamp`: same arguments → one HTTP call; a different hero, tier or timestamp → a new call. Check that `getItemStats(heroId, rankTier)` (existing, other cache) is not affected.

### `CounterServiceTest` (mocks, fixed `Clock`)
1. `timestampIsStartOfUtcDayMinus30Days` (clock at 2026-10-17T15:30Z → 2026-09-17T00:00Z); `allRanksPassesNullTier`; `invalidTierIsRejectedWithoutCalls` (0 and 12).
2. `unknownHeroThrowsAndMakesNoStatsCalls`.
3. `callsCountersThenItemsThenAssetsSequentially` (`InOrder`).
4. `countersAreRowsWhereTargetIsTheEnemy`: win rate is the row's `wins / matchesPlayed` (the attacker's), sorted desc, ties by matches desc then id asc, capped at 5.
5. `favorableAreRowsWhereTargetIsTheHero`: the matchup id is the opponent, win rate is the target's.
6. `pairsBelowMinMatchesAreDropped` (49 dropped, 50 kept); `mirrorRowIsIgnored`; `duplicateOpponentKeepsFirst`; `unknownOpponentHasNullName`.
7. `itemsFilterToUpgradesWithAKnownName`: non-upgrade types, unknown ids and blank names dropped; `itemsBelowMinMatchesAreDropped` (99 dropped, 100 kept); `itemsSortedByWinRateThenMatchesThenIdAndCappedAtFive`; `duplicateItemKeepsFirst`.
8. `emptyDataGivesEmptyListsNotNull`; `propagatesUnavailableException`.

### `CounterCommandTest` (style of `MetaCommandTest`)
1. `nameIsCounterWithRequiredHeroAndOptionalRankChoices`: options in order `hero` (`STRING`, required, max length 50) then `rank` (`INTEGER`, not required, 11 choices 1..11 with `meta.rank.*` labels); descriptions 1-100 chars.
2. `defersPubliclyAndEditsWithEmbed`: no ephemeral deferral; title, the three fields in order with entry lines, footer from the catalog; edit queued.
3. `titleUsesAllRanksWithoutOption`, `heroAndRankAreSentToService`.
4. `emptyListsAreOmittedFromTheEmbed` (only the non-empty fields appear); `unknownOpponentUsesHeroUnknown`.
5. `allEmptyEditsWithNoData`, `heroNotFoundEditsWithoutEchoingInput`, `unavailableEditsWithBusyMessage`, `unexpectedErrorEditsWithGenericMessageAndDoesNotRethrow`; each verifies the edit is queued.

### `HelpCommandTest`, `MessagesIntegrationTest` (minimal edits)
`help.body` contains `/counter`; every new key resolves; command names include `counter`. `MessagesIntegrationTest` needs Docker.

## Verification
1. Task 011 is merged and Step 0 is done and recorded in the report.
2. `./mvnw clean verify` passes with Docker running (on Windows `.\mvnw.cmd clean verify`; see the `MAVEN_USER_HOME` note in `docs/PROJECT_CONTEXT.md` if the wrapper fails). Report the real test count against the baseline you measure on `origin/main` before your first change.
3. `git diff --check` and a review of the final diff: only the files in the table changed; the fixture has invented numbers; no secret, token or real user data; `.env` never read or printed.
4. Optional manual check in the dev guild (needs `DISCORD_TOKEN`, `DEV_GUILD_ID`, PostgreSQL): `/counter hero:Haze`, `/counter hero:haz rank:Archon`, `/counter hero:zzz`. If you do not run it, say so in the report.

## Documentation
- Task report `docs/task-reports/012-counter.md`: status, Step 0 findings, summary, files, commands run with real results, deviations, known issues.
- `docs/PROJECT_CONTEXT.md`: **do not edit in this branch.** Mark the update as "pending integration" in the report (`AGENTS.md`, Parallel tasks), listing the facts to add (Step 0 facts, two new caches, `/counter` done); the owner integrates it.
- No ADR. Do not edit `docs/mvp-spec.md`, `docs/architecture.md`, `docs/features.md`, other specs or `docs/roadmap.md`.

## Out of scope
- The optional AI summary mentioned in `docs/features.md`, `same_lane_filter` or any other query parameter, per-lane matchups, synergies (`/synergy`), hero-specific item win rates (`hero_ids` with `enemy_hero_ids`), baseline-adjusted item win rates, `hero_build_id`.
- Hero autocomplete, aliases, fuzzy matching, tier 0 as a choice.
- Changing `HeroResolver`, `HeroNotFoundException`, existing `DeadlockApiClient` methods, other DTOs, `MetaCommand`, `BuildCommand`, `CommandRouter`, `Messages`, `application.yaml`, `pom.xml`.
- A service-level cache.

## Acceptance criteria
- [ ] Task 011 is merged; `HeroResolver` is reused, not copied
- [ ] Step 0 findings are in the report; the DTO matches the real response shape
- [ ] `./mvnw clean verify` passes; test count and baseline difference are in the report
- [ ] `getHeroCounterStats` and `getItemStatsAgainst` send exactly the listed parameters, validate the tier before any HTTP call and are cached 6 h (max 50 and 200)
- [ ] `/counter` works without a linked account; it defers publicly and shows only the non-empty fields (counters, items, favorable) with at most 5 entries each; all text from `messages.properties`
- [ ] Matchups below 50 matches and items below 100 matches are excluded; only `upgrade` items with a name are shown; mirror rows are ignored
- [ ] Unknown hero, empty data, API unavailable and unexpected errors each give their own message; the unknown-hero reply does not echo the input; unexpected errors log an error id and do not rethrow
- [ ] API calls are sequential; commands contain no business logic; services use no JDA types
- [ ] `help.body` lists `/counter`
- [ ] Only the files in the table changed; no new dependency, migration or secret
- [ ] Task report created with the `docs/PROJECT_CONTEXT.md` update marked pending integration; final diff has no unrelated files

## Decisions and open questions
Decisions taken in this spec:
1. **Counters from the pair table**, because the endpoint has no hero filter: the service filters the cached global table (one call per rank/day shared by every hero and user).
2. **Three lists** (counters, items, favorable): the plan says "worst matchups + items that win against it"; the favorable list costs nothing extra and answers "what should I play into this hero".
3. **Raw item win rate against the hero**, no baseline: a second call per item would multiply analytics requests. The footer states the limitation; only `upgrade` items are shown so abilities and weapons do not pollute the list.
4. **Default `same_lane_filter`** (true for pairs, false for items) is left to the API, to avoid inventing a meaning; Task 010 or the owner can change it.
5. **Thresholds** 50 matches per matchup and 100 per item hide noisy high-rank samples; if nothing passes, `counter.no_data`.
6. **Starts after Task 011** because of `HeroResolver`; both tasks otherwise only append to shared files.

Open for the owner (not blocking):
1. Whether to add a short AI summary later (`docs/features.md` marks it optional). Out of scope here.
