# Task 011: `/build hero [rank]`

- **Branch**: `feature/011-build`
- **Implementer**: Antigravity
- **Related ADRs**: ADR-0014 (free hero utilities, shared caches first), ADR-0010 and ADR-0004 (deadlock-api), ADR-0002 (Caffeine), ADR-0008 (English, i18n-ready)

## Goal
Add `/build hero [rank]`: a public embed with the meta build of a hero for all ranks or one rank tier: what players buy in each phase of the game (item flow) and the most played ability order. No account link and no AI call. It adds two analytics endpoints (`item-flow-stats`, `ability-order-stats`) to `DeadlockApiClient`, cached and shared by all users, and creates `HeroResolver`, which Task 012 reuses.

## Context (verified in the repository when this spec was written)
Do not repeat this discovery. If a fact below is false in your checkout, stop and ask.

- Plan entry: `docs/implementation-plan.md`, task 011, depends on 004, implementer Antigravity. The plan requires this spec to authorize extending `DeadlockApiClient`; it does. Tasks 004-008, 013 and 022 are merged in `main` (243 tests on `main` per `docs/PROJECT_CONTEXT.md`). Start the branch from the latest `origin/main`.
- **Task 012 (`/counter`) is specified in parallel** and depends on this task: it reuses `HeroResolver` and `HeroNotFoundException` created here. Both tasks append to the same shared files (see "Parallel work" below).
- Reference implementation: Task 013 (`docs/tasks/013-meta.md`, `docs/task-reports/013-meta.md`), merged. Mirror it: `clients/DeadlockApiClient.getHeroStats` (+ private `heroStatsFilter(UriBuilder, Integer rankTier, long minUnixTimestamp)` and `requireRankTier(int)`), `services/MetaService` (two-constructor `Clock` pattern, window = start of the current UTC day minus 30 days, tier validation 1..11), `commands/MetaCommand` (public deferral, optional `INTEGER` `rank` option with 11 choices labeled `meta.rank.<tier>`, own error handling after `deferReply()`), and their tests.
- Existing code you will use: `DeadlockApiClient.get(path, uriFunction, typeRef, false)` (503/`Retry-After` rule), `getHeroes()` (`List<HeroAsset(int id, String className, String name)>`, cached 24 h) and `getItems()` (`List<ItemAsset(long id, String className, String name, String type, Integer itemTier, String itemSlotType, Integer cost)>`, cached 24 h). Failures: `DeadlockApiUnavailableException` (extends `DeadlockApiException`).
- Catalog keys reused: `meta.rank.1` ... `meta.rank.11`, `meta.all_ranks`, `error.data_source_busy`, `error.generic` (takes the error id). `help.body` lists the commands; `HelpCommandTest` asserts the command names it contains.
- Facts from `https://api.deadlock-api.com/openapi.json` (read 2026-10-17 by the architect; response bodies were **not** called live):
  - `GET /v1/analytics/item-flow-stats`: query `hero_ids` (nullable **string**; a single id is the expected use but the spec does not say so), `min_unix_timestamp` (int64, default 30 days ago), `min_average_badge`/`max_average_badge` (int32, 0..116), `min_matches` (default 20), `game_mode` (default `normal`), `match_mode` (default `ranked,unranked`). `phase_interval_s` is deprecated/unused; `phase_count` applies to `street_brawl` only. Cached 6 h by the server. Response is **one object** `ItemFlowStats` with `nodes[]` (`column` 0-based phase, `item_id`, `wins`, `losses`, `matches`, `players`, `total_kills`, `total_deaths`, `total_assists`, `adjusted_win_rate`, `avg_net_worth_at_buy`), `edges[]`, `summary`, `baseline`, `reached_per_column[]` (int64: distinct baseline games buying any upgrade in each column), `reached_wins_per_column[]`, `leave_one_out[]`. The number of columns for `normal` mode is not documented.
  - `GET /v1/analytics/ability-order-stats`: query `hero_id` (int32, **required**), `min_unix_timestamp`, `min_average_badge`/`max_average_badge`, `min_matches` (default 10), `game_mode`, `match_mode`. Response is an array of `AnalyticsAbilityOrderStats`: `abilities[]` (int32 ids, in purchase order; the OpenAPI description wrongly says "hero ids"), `avg_upgrade_times_s[]` (index-aligned), `wins`, `losses`, `matches`, `players`, `total_kills/deaths/assists`.
  - Analytics rate limit: 200 req/min per IP, shared by all analytics endpoints.
- **Not verified yet** (Step 0): that `hero_ids=<single id>` works on item-flow-stats; how many columns the response has; that ability ids resolve in `getItems()` (`ItemAsset.type` is `ability`, per `docs/PROJECT_CONTEXT.md` the items asset has types upgrade/ability/weapon).
- Rules from `AGENTS.md`: DTOs are records with `@JsonIgnoreProperties(ignoreUnknown = true)` and only the fields used; every client read is cached; never fire parallel uncached analytics requests; no business logic in commands; services use no JDA types; all user-facing text in `messages.properties`.

## Step 0: verify the endpoints (before writing code)
This needs network access. If you have none, ask the owner to run the two calls and paste the (shortened) bodies. Use any real hero id from `getHeroes()` and the badge range of one tier (e.g. `min_average_badge=70`, `max_average_badge=79`). Confirm and record in the report:
1. `GET /v1/analytics/item-flow-stats?hero_ids=<id>&min_unix_timestamp=<ts>`: returns one `ItemFlowStats` object for that hero only; how many distinct `column` values it has; whether `reached_per_column` has one entry per column.
2. `GET /v1/analytics/ability-order-stats?hero_id=<id>&min_unix_timestamp=<ts>`: returns an array; `abilities` length (expected up to 16); the ability ids exist in `GET /v1/assets/items` with `type` `ability` (check three).

Keep at most one request per endpoint and respect the rate limit. If a field only has a different JSON name, use the real name in `@JsonProperty`, keep the Java names below and report it. If the structure differs (not one object, no `column`/`matches`, `hero_ids` ignored so the result is not hero-specific, ability ids do not resolve), **stop and ask**.

## Files to create or modify
| File | Change |
|---|---|
| `src/main/java/sntiago05/rejuv/clients/dto/ItemFlowStats.java` | **Create.** DTO record. |
| `src/main/java/sntiago05/rejuv/clients/dto/ItemFlowNode.java` | **Create.** DTO record. |
| `src/main/java/sntiago05/rejuv/clients/dto/AbilityOrderStat.java` | **Create.** DTO record. |
| `src/main/java/sntiago05/rejuv/clients/DeadlockApiClient.java` | **Modify.** Only add the two path constants, two type references, `getItemFlowStats`, `getAbilityOrderStats` and, if needed, one private helper. Reuse `heroStatsFilter` if it fits; do not change existing methods' behavior. |
| `src/main/java/sntiago05/rejuv/config/CacheConfig.java` | **Modify.** Only add `DEADLOCK_ITEM_FLOW`, `DEADLOCK_ABILITY_ORDER` and their `register(...)` lines. |
| `src/main/java/sntiago05/rejuv/domain/BuildItem.java` | **Create.** Record. |
| `src/main/java/sntiago05/rejuv/domain/BuildPhase.java` | **Create.** Record. |
| `src/main/java/sntiago05/rejuv/domain/HeroBuild.java` | **Create.** Record. |
| `src/main/java/sntiago05/rejuv/services/HeroNotFoundException.java` | **Create.** Unchecked exception (`RuntimeException`). |
| `src/main/java/sntiago05/rejuv/services/HeroResolver.java` | **Create.** `@Component`. |
| `src/main/java/sntiago05/rejuv/services/BuildService.java` | **Create.** |
| `src/main/java/sntiago05/rejuv/commands/BuildCommand.java` | **Create.** |
| `src/main/resources/messages.properties` | **Modify.** Append the `build.*` keys; add one `/build hero [rank]` line to `help.body` after the `/meta [rank]` line. Change nothing else. |
| `src/test/resources/fixtures/deadlock-api/item-flow-stats.json` | **Create.** Real shape from Step 0, **invented** numbers, 2 columns, 3-4 nodes per column, with unknown fields (`edges`, `summary`, ...) to prove `ignoreUnknown`. |
| `src/test/resources/fixtures/deadlock-api/ability-order-stats.json` | **Create.** Real shape from Step 0, **invented** numbers, 3 orderings of 4-6 abilities, with unknown fields. |
| `src/test/java/sntiago05/rejuv/clients/DeadlockApiClientTest.java` | **Modify.** Add the new endpoint tests only. |
| `src/test/java/sntiago05/rejuv/clients/DeadlockApiClientCachingTest.java` | **Modify.** Add the two new caches to the exact-set maps and one caching test per method. |
| `src/test/java/sntiago05/rejuv/services/HeroResolverTest.java` | **Create.** |
| `src/test/java/sntiago05/rejuv/services/BuildServiceTest.java` | **Create.** |
| `src/test/java/sntiago05/rejuv/commands/BuildCommandTest.java` | **Create.** |
| `src/test/java/sntiago05/rejuv/commands/HelpCommandTest.java` | **Modify.** Only add `"/build"` to the asserted command list. |
| `src/test/java/sntiago05/rejuv/services/MessagesIntegrationTest.java` | **Modify.** Only add the new keys to `requiredKeys` and `"build"` to the command-name assertion. |
| `docs/task-reports/011-build.md` | **Create.** Task report (format of the Task 013 report). |

No `pom.xml`, `application.yaml`, migration, entity or repository change is needed. If you think one is needed, stop and ask.

## Interfaces / signatures

### DTOs (`sntiago05.rejuv.clients.dto`, new)
```java
@JsonIgnoreProperties(ignoreUnknown = true)
public record ItemFlowStats(@JsonProperty("nodes") List<ItemFlowNode> nodes,
                            @JsonProperty("reached_per_column") List<Long> reachedPerColumn) { }

@JsonIgnoreProperties(ignoreUnknown = true)
public record ItemFlowNode(@JsonProperty("column") int column,
                           @JsonProperty("item_id") long itemId,
                           @JsonProperty("matches") long matches,
                           @JsonProperty("wins") long wins) { }

@JsonIgnoreProperties(ignoreUnknown = true)
public record AbilityOrderStat(@JsonProperty("abilities") List<Long> abilities,
                               @JsonProperty("matches") long matches,
                               @JsonProperty("wins") long wins) { }
```
A missing or null list is treated as empty by the client (the returned records must hold immutable lists, never `null`).

### `DeadlockApiClient` (add)
```java
private static final String ITEM_FLOW_PATH = "/v1/analytics/item-flow-stats";
private static final String ABILITY_ORDER_PATH = "/v1/analytics/ability-order-stats";

/**
 * Item build flow of one hero since minUnixTimestamp. rankTier null = all ranks (no badge filter).
 * Callers must not call the analytics methods in parallel for different uncached keys.
 * @throws IllegalArgumentException if rankTier is non-null and not in 0..11 (before any HTTP call)
 */
@Cacheable(cacheNames = CacheConfig.DEADLOCK_ITEM_FLOW, sync = true,
        key = "#heroId + ':' + #rankTier + ':' + #minUnixTimestamp")
public ItemFlowStats getItemFlowStats(int heroId, Integer rankTier, long minUnixTimestamp);

/** Ability orders of one hero (one entry per distinct order). Same rank, window and error rules. */
@Cacheable(cacheNames = CacheConfig.DEADLOCK_ABILITY_ORDER, sync = true,
        key = "#heroId + ':' + #rankTier + ':' + #minUnixTimestamp")
public List<AbilityOrderStat> getAbilityOrderStats(int heroId, Integer rankTier, long minUnixTimestamp);
```
Requests (no other parameter):
- `getItemFlowStats`: `hero_ids=<heroId>`, `min_unix_timestamp=<minUnixTimestamp>`, and when `rankTier` is non-null `min_average_badge=rankTier*10`, `max_average_badge=rankTier*10+9`.
- `getAbilityOrderStats`: `hero_id=<heroId>` plus the same timestamp and badge parameters.
Same error mapping and 503 retry as the other analytics methods (through `get`, with `false` as the last argument). `getAbilityOrderStats` returns `List.copyOf(...)`.

### `CacheConfig` (add)
```java
public static final String DEADLOCK_ITEM_FLOW = "deadlockItemFlow";           // 6 h, max 200
public static final String DEADLOCK_ABILITY_ORDER = "deadlockAbilityOrder";   // 6 h, max 200
```
with `register(cacheManager, DEADLOCK_ITEM_FLOW, Duration.ofHours(6), 200);` and the same for `DEADLOCK_ABILITY_ORDER`. The maximum is 200 (not 500) because an ability-order response can hold many orderings per hero; evictions only cost an extra call.

### Domain (`sntiago05.rejuv.domain`, new)
```java
/** itemName is null when the item id is not in the items asset. pick and win rate are unrounded percents. */
public record BuildItem(long itemId, String itemName, long matches, double pickRatePercent, double winRatePercent) { }

/** column is 0-based. items immutable, sorted by matches desc, then itemId asc; never empty. */
public record BuildPhase(int column, List<BuildItem> items) { }

/**
 * rankTier null = all ranks. phases immutable, ascending column, only non-empty phases.
 * abilityOrder: ability names in purchase order ("#<id>" for unknown ids, see Behavior), empty when no data.
 * abilityOrderMatches: matches of the chosen order, 0 when abilityOrder is empty.
 */
public record HeroBuild(int heroId, String heroName, Integer rankTier, List<BuildPhase> phases,
                        List<String> abilityOrder, long abilityOrderMatches) { }
```

### `HeroResolver` (`sntiago05.rejuv.services`, `@Component`, new; reused by Task 012)
```java
public HeroResolver(DeadlockApiClient deadlockApiClient)

/**
 * Resolves a hero typed by a user. Uses the cached heroes asset (getHeroes()), no other call.
 * Order: (1) exact name, ignoring case, after trimming and collapsing inner whitespace to one space;
 * (2) otherwise the single hero whose name starts with the input (ignoring case);
 * (3) otherwise empty (also for blank input and for an ambiguous prefix).
 * Heroes whose name is null or blank are ignored.
 * Lets DeadlockApiUnavailableException / DeadlockApiException propagate.
 */
public Optional<HeroAsset> resolve(String input);
```
`HeroNotFoundException extends RuntimeException` has a constructor `HeroNotFoundException(String input)`; it is thrown by the services, not by `HeroResolver`.

### `BuildService` (`sntiago05.rejuv.services`, `@Service`, new)
```java
static final int MIN_ITEM_MATCHES = 20;
static final int MAX_ITEMS_PER_PHASE = 4;
static final int MAX_PHASES = 6;
static final Duration WINDOW = Duration.ofDays(30);
static final int MIN_RANK_TIER = 1;
static final int MAX_RANK_TIER = 11;

@Autowired
public BuildService(DeadlockApiClient deadlockApiClient, HeroResolver heroResolver)           // Clock.systemUTC()
BuildService(DeadlockApiClient deadlockApiClient, HeroResolver heroResolver, Clock clock)     // package-private, for tests

/**
 * @param heroInput text typed by the user
 * @param rankTier null = all ranks, else 1..11
 * @throws IllegalArgumentException for a tier outside 1..11 (before any call)
 * @throws HeroNotFoundException if the input does not resolve to a hero
 * Lets DeadlockApiUnavailableException / DeadlockApiException propagate.
 */
public HeroBuild getBuild(String heroInput, Integer rankTier);
```

### `BuildCommand` (`sntiago05.rejuv.commands`, `@Component implements SlashCommand`, new)
- `BuildCommand(BuildService buildService, Messages messages)`.
- `name()` = `build`; `data()` = `Commands.slash("build", messages.get("build.description"))` with two options, in this order: (1) `STRING` `hero`, description `messages.get("build.option.hero")`, **required**, max length 50; (2) `INTEGER` `rank`, description `messages.get("meta.option.rank")`, **not** required, 11 choices with value `1..11` and name `messages.get("meta.rank." + tier)` (same as `MetaCommand`).

## Behavior

### `BuildService.getBuild(heroInput, rankTier)`
1. Validate `rankTier` (null or 1..11) before any call.
2. `hero = heroResolver.resolve(heroInput)`; empty → `HeroNotFoundException(heroInput)`. No stats call is made.
3. `minUnixTimestamp` = start of the current UTC day (from the clock) minus 30 days, in epoch seconds (stable cache key for a day).
4. Calls in this order, sequentially: `getItemFlowStats(hero.id(), rankTier, ts)`, `getAbilityOrderStats(hero.id(), rankTier, ts)`, `getItems()`.
5. **Phases.** Group `nodes` by `column`. Per column, drop nodes with `matches < MIN_ITEM_MATCHES`; for duplicate `(column, itemId)` keep the first. `pickRatePercent = matches * 100.0 / reachedPerColumn[column]` when that entry exists and is > 0, else `0.0` (never divide by zero; a missing entry does not drop the item). `winRatePercent = wins * 100.0 / matches`. `itemName` = `ItemAsset.name` for that id or `null`. Sort items by `matches` desc, then `itemId` asc; keep the first `MAX_ITEMS_PER_PHASE`. Drop empty columns, order by column ascending, keep the first `MAX_PHASES`.
6. **Ability order.** Take the entry with the most `matches` (ties: more `wins`, then the lexicographically smaller `abilities` list). Entries with an empty `abilities` list are ignored. `abilityOrder` = for each id its `ItemAsset.name`, or `"#" + id` when unknown or blank (this service-level fallback is the only hard-coded text allowed, it is an identifier, not prose). `abilityOrderMatches` = its `matches`. No entry → empty list and `0`.
7. Return `HeroBuild(hero.id(), hero.name(), rankTier, phases, abilityOrder, abilityOrderMatches)`.

### `/build hero [rank]`
1. `event.deferReply().queue()` (public; it advertises the bot). No link check.
2. Read `hero` (String) and optional `rank` (`Integer`, null when absent). Call `getBuild` synchronously and answer through the hook:
   - `phases` and `abilityOrder` both empty → `editOriginal(build.no_data).queue()`;
   - otherwise → `editOriginalEmbeds(embed).queue()`;
   - `HeroNotFoundException` → `editOriginal(build.hero_not_found).queue()` (do **not** echo the user input);
   - `DeadlockApiUnavailableException` → `editOriginal(error.data_source_busy).queue()`;
   - any other `RuntimeException` → error id `UUID.randomUUID()`, log `ERROR` with id, caller id, hero input, rank tier and the exception, `editOriginal(error.generic)` with the id. Do not rethrow.

### Embed
Built with JDA `EmbedBuilder`; every text from `Messages`; numbers pre-formatted with `String.format(Locale.ROOT, "%.1f", value)`.
- Title: `build.title` with `{0}` = hero name, `{1}` = `meta.rank.<tier>` or `meta.all_ranks` when the tier is null.
- One field (not inline) per phase: name `build.phase` with `{0}` = `column + 1`; value = one `build.item_entry` per line (`{0}` = item name or `build.item_unknown` with `{0}` = id, `{1}` = pick rate, `{2}` = win rate) joined with `\n`.
- If `abilityOrder` is not empty, one field (not inline): name `build.abilities`, value = the names joined with `build.ability_separator`, then `\n` and `build.ability_matches` (`{0}` = `abilityOrderMatches`). Truncate the joined names to Discord's 1,024-char field limit if needed (16 names normally fit).
- Footer: `build.footer` with `{0}` = `MIN_ITEM_MATCHES`.

## User-facing text (`messages.properties`, append, English)
Messages with arguments must not contain a single apostrophe. Command and option texts: 1-100 chars.

| Key | Intent |
|---|---|
| `build.description` | Command description: popular item and ability build for a hero |
| `build.option.hero` | Option description: hero name, e.g. Haze |
| `build.title` | `{0}` hero, `{1}` rank label, e.g. "{0} build: {1}" |
| `build.phase` | `{0}` phase number, e.g. "Phase {0}" |
| `build.item_entry` | `{0}` item, `{1}` pick rate, `{2}` win rate, e.g. "{0} - {1}% pick, {2}% win" |
| `build.item_unknown` | `{0}` id, e.g. "Item #{0}" |
| `build.abilities` | Field name, e.g. "Ability order" |
| `build.ability_separator` | e.g. " > " |
| `build.ability_matches` | `{0}` matches, e.g. "Most played order ({0} matches)" |
| `build.footer` | `{0}` min matches: last 30 days, items bought in at least {0} matches, data from deadlock-api |
| `build.hero_not_found` | Hero not recognized: check the spelling and try again |
| `build.no_data` | Not enough recent matches for this hero and rank yet; try another rank or all ranks |
| `help.body` (one added line) | `` `/build hero [rank]` - See the popular item and ability build for a hero.`` after the `/meta [rank]` line |

## Tests
JUnit 5, AssertJ, Mockito; `MockRestServiceServer` and the new fixtures for the client; `MessagesFixture.english()` for commands.

### `DeadlockApiClientTest` (add)
1. `itemFlowSendsHeroBadgeRangeAndTimestamp`: `getItemFlowStats(61, 7, 1789000000L)` sends `hero_ids=61`, `min_average_badge=70`, `max_average_badge=79`, `min_unix_timestamp=1789000000` and nothing else; parses the fixture (nodes, `reachedPerColumn`).
2. `itemFlowWithoutRankSendsNoBadgeFilter`; `itemFlowRejectsTierOutOfRange` (12 and -1, no HTTP call).
3. `abilityOrderSendsHeroIdBadgeRangeAndTimestamp` (`hero_id`, not `hero_ids`) and parses the fixture; `abilityOrderWithoutRankSendsNoBadgeFilter`; `abilityOrderRejectsTierOutOfRange`.
4. `itemFlowServiceUnavailableIsMapped` and `abilityOrderServiceUnavailableIsMapped` (503 with a large `Retry-After` → `DeadlockApiUnavailableException`), following the existing analytics tests.
5. `itemFlowWithMissingListsIsEmpty`: a body without `nodes`/`reached_per_column` gives empty lists, not `null`.

### `DeadlockApiClientCachingTest` (modify)
Add both caches to the exact-set maps (6 h, 200). Add `itemFlowIsCachedPerHeroTierAndTimestamp` and `abilityOrderIsCachedPerHeroTierAndTimestamp`: same arguments → one HTTP call; a different hero, tier or timestamp → a new call.

### `HeroResolverTest` (mock client)
`exactNameIgnoringCase`, `trimsAndCollapsesWhitespace`, `uniquePrefixResolves`, `ambiguousPrefixIsEmpty`, `exactNameBeatsLongerPrefixMatch` (a name that is also the prefix of another name), `unknownIsEmpty`, `blankOrNullIsEmpty`, `heroesWithoutNameAreIgnored`, `propagatesUnavailableException`.

### `BuildServiceTest` (mocks, fixed `Clock`)
1. `timestampIsStartOfUtcDayMinus30Days` (clock at 2026-10-17T15:30Z → 2026-09-17T00:00Z); `allRanksPassesNullTier`; `invalidTierIsRejectedWithoutCalls` (0 and 12).
2. `unknownHeroThrowsAndMakesNoStatsCalls`.
3. `callsFlowThenAbilitiesThenItemsSequentially` (`InOrder`).
4. `phasesGroupByColumnAndAreOrdered`; `itemsBelowMinMatchesAreDropped` (19 dropped, 20 kept); `itemsSortedByMatchesThenIdAndCappedAtFour`; `phasesCappedAtSix`; `emptyColumnsAreDropped`.
5. `pickRateUsesReachedPerColumn`, `missingReachedEntryGivesZeroPickRateAndKeepsItem`, `zeroReachedGivesZeroPickRate` (no `ArithmeticException`/`NaN`); `winRateIsWinsOverMatches`; `unknownItemHasNullName`; `duplicateItemInColumnKeepsFirst`.
6. `mostPlayedAbilityOrderIsChosen`, `abilityOrderTieBreaksOnWinsThenLexicographic`, `emptyAbilityEntriesIgnored`, `unknownAbilityIdUsesHashFallback`, `noAbilityDataGivesEmptyOrder`.
7. `propagatesUnavailableException`.

### `BuildCommandTest` (style of `MetaCommandTest`)
1. `nameIsBuildWithRequiredHeroAndOptionalRankChoices`: options in order `hero` (`STRING`, required, max length 50) then `rank` (`INTEGER`, not required, 11 choices with values 1..11 and `meta.rank.*` labels); descriptions 1-100 chars.
2. `defersPubliclyAndEditsWithEmbed`: no ephemeral deferral; title, one field per phase in order, entry lines, ability field with separator, footer from the catalog; edit queued.
3. `titleUsesAllRanksWithoutOption`, `heroAndRankAreSentToService`.
4. `omitsAbilityFieldWhenEmpty`; `unknownItemUsesItemUnknown`.
5. `emptyBuildEditsWithNoData`, `heroNotFoundEditsWithoutEchoingInput`, `unavailableEditsWithBusyMessage`, `unexpectedErrorEditsWithGenericMessageAndDoesNotRethrow`; each verifies the edit is queued.

### `HelpCommandTest`, `MessagesIntegrationTest` (minimal edits)
`help.body` contains `/build`; every new key resolves; command names include `build`. `MessagesIntegrationTest` needs Docker.

## Verification
1. Step 0 done and recorded in the report.
2. `./mvnw clean verify` passes with Docker running (on Windows `.\mvnw.cmd clean verify`; see the `MAVEN_USER_HOME` note in `docs/PROJECT_CONTEXT.md` if the wrapper fails). Report the real test count against the baseline you measure on `origin/main` before your first change.
3. `git diff --check` and a review of the final diff: only the files in the table changed; fixtures have invented numbers; no secret, token or real user data; `.env` never read or printed.
4. Optional manual check in the dev guild (needs `DISCORD_TOKEN`, `DEV_GUILD_ID`, PostgreSQL): `/build hero:Haze`, `/build hero:haz rank:Archon`, `/build hero:zzz`. If you do not run it, say so in the report.

## Parallel work
Task 012 is specified together with this one and will touch the same shared files with append-only changes: `DeadlockApiClient`, `CacheConfig`, `DeadlockApiClientTest`, `DeadlockApiClientCachingTest`, `messages.properties` (incl. `help.body`), `HelpCommandTest`, `MessagesIntegrationTest`. If both branches are open, expect trivial merge conflicts in those files; resolve by keeping both sides' additions and re-running `verify`. Do not touch Task 012's files.

## Documentation
- Task report `docs/task-reports/011-build.md`: status, Step 0 findings, summary, files, commands run with real results, deviations, known issues.
- `docs/PROJECT_CONTEXT.md`: **do not edit in this branch.** Task 012 runs in parallel; mark the context update as "pending integration" in the report (`AGENTS.md`, Parallel tasks), listing the facts to add after the branches merge (Step 0 facts, two new caches, `HeroResolver`, `/build` done).
- No ADR. Do not edit `docs/mvp-spec.md`, `docs/architecture.md`, `docs/features.md`, other specs or `docs/roadmap.md`.

## Out of scope
- `hero-build-stats` (in-game published builds), item flow edges and transitions, `adjusted_win_rate`, ability upgrade times, per-patch filtering, ability priorities by level, build recommendations written by AI.
- Hero autocomplete (the router has no autocomplete handling), hero aliases or fuzzy matching beyond `HeroResolver` rules, tier 0 (`Obscurus`) as a choice, `game_mode` and `match_mode` parameters.
- Changing existing `DeadlockApiClient` methods, other DTOs, `MetaCommand`, `MetaService`, `CommandRouter`, `Messages`, `application.yaml`, `pom.xml`.
- A service-level cache (the computation is cheap; the client cache is shared).

## Acceptance criteria
- [ ] Step 0 findings are in the report; the DTOs match the real response shapes
- [ ] `./mvnw clean verify` passes; test count and baseline difference are in the report
- [ ] `getItemFlowStats` and `getAbilityOrderStats` send exactly the listed parameters, validate the tier before any HTTP call and are cached 6 h (max 200) per hero, tier and timestamp
- [ ] `HeroResolver` follows the exact/prefix rules and is reusable by Task 012 without changes
- [ ] `/build` works without a linked account; it defers publicly and shows phases in column order with at most 4 items each, plus the most played ability order; all text from `messages.properties`
- [ ] Items with fewer than 20 matches are excluded; pick rate never divides by zero
- [ ] Unknown hero, empty data, API unavailable and unexpected errors each give their own message; the unknown-hero reply does not echo the input; unexpected errors log an error id and do not rethrow
- [ ] API calls are sequential; commands contain no business logic; services use no JDA types
- [ ] `help.body` lists `/build`
- [ ] Only the files in the table changed; no new dependency, migration or secret
- [ ] Task report created with `docs/PROJECT_CONTEXT.md` update marked pending integration; final diff has no unrelated files

## Decisions and open questions
Decisions taken in this spec:
1. **Item flow + ability order only**, as in the plan; `hero-build-stats` (published in-game builds) is left out to keep one session of work.
2. **Free-text `hero` option resolved from the cached heroes asset**, because ~38 heroes exceed Discord's 25-choice limit and the router has no autocomplete. Registration must not depend on the API being up.
3. **Most played full ability order** (not a per-slot majority): simplest faithful reading of the endpoint, one coherent order a player can follow.
4. **Reuse `meta.rank.*` and `meta.option.rank` keys** instead of duplicating 12 keys.
5. **`HeroResolver` lives here**, so Task 012 should start after this task is merged (or copy nothing and wait); see Task 012.
6. **Caches 6 h / 200**, matching the server-side 6 h cache.

Open for the owner (not blocking):
1. If Step 0 shows the normal-mode flow has many columns (more than 6), whether to show all of them. Default here: first six.
