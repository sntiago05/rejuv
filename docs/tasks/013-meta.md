# Task 013: `/meta [rank]`

- **Branch**: `feature/013-meta`
- **Implementer**: Antigravity
- **Related ADRs**: ADR-0014 (free hero utilities, shared caches first), ADR-0010 and ADR-0004 (deadlock-api), ADR-0002 (Caffeine), ADR-0008 (English, i18n-ready)

## Goal
Add `/meta [rank]`: a public embed with a hero tier list (S to D) by win rate over the last 30 days, for all ranks or for one rank tier. No account link and no AI call. It adds one analytics endpoint (`/v1/analytics/hero-stats`) to `DeadlockApiClient`, cached and shared by all users. Tasks 011, 012 and 014 can follow the same pattern later.

## Context (verified in the repository when this spec was written)
Do not repeat this discovery. If a fact below is false in your checkout, stop and ask.

- Plan entry: `docs/implementation-plan.md`, task 013, depends on 004, implementer Antigravity. The plan says tasks 011-013 must authorize extending `DeadlockApiClient`; this spec does. Tasks 004, 005, 006, 008 and 022 are merged in `main`. Start the branch from the latest `origin/main`.
- **Task 007 is planned to run in parallel** in another worktree. It only creates `domain/MatchRank`, `services/MetricsRequest`, `services/MetricsService`, its test and its report. Do not touch those.
- Existing code you will use or extend:
  - `clients/DeadlockApiClient`: path constants (`METRICS_PATH = "/v1/analytics/player-stats/metrics"`, ...), `ParameterizedTypeReference` constants, private `get(path, uriFunction, typeRef, boolean)` with the 503/`Retry-After` rule, private `requireRankTier(int)` (0..11, `IllegalArgumentException`) and private `heroAndBadgeFilter(UriBuilder, heroId, rankTier)` (badge range `tier*10` to `tier*10+9`). `List<HeroAsset> getHeroes()` is cached in `deadlockAssets`. Failures are `DeadlockApiUnavailableException` (503/429/I/O/timeout; extends `DeadlockApiException`) and `DeadlockApiException`.
  - `clients/dto/HeroAsset(int id, String className, String name)`.
  - `config/CacheConfig`: static Caffeine caches; an unregistered name returns `null`. `DeadlockApiClientCachingTest.cacheTtlsAndSizesMatchTheDesign` asserts the **exact** set of cache names with TTL and size, so a new cache must be added there too.
  - `services/ProfileService` is the reference for the two-constructor `Clock` pattern (public `@Autowired` with `Clock.systemUTC()`, package-private with a `Clock` for tests; no `Clock` bean).
  - `commands/ProfileCommand` is the reference for a deferred public command that handles its own errors after `deferReply()` (`CommandRouter` cannot reply after a deferral and already logs one INFO line per command). Its tests verify that each hook edit is queued.
  - Catalog keys reused: `error.data_source_busy`, `error.generic` (takes the error id).
  - `help.body` lists the commands; `HelpCommandTest` asserts the command names it contains.
- Facts from real responses (`docs/PROJECT_CONTEXT.md`): analytics filter by `min_average_badge`/`max_average_badge` (average of both teams); ranks tiers are 0-11 and the fixture `assets-ranks.json` has tier 0 `Obscurus`, 1 `Initiate`, 2 `Seeker`; analytics rate limit is 200 req/min per IP.
- **Not verified yet:** the query parameters and the response shape of `/v1/analytics/hero-stats`. Step 0 below verifies them.
- Rules from `AGENTS.md`: DTOs are records with `@JsonIgnoreProperties(ignoreUnknown = true)` and only the fields used; every client read is cached; never fire parallel uncached analytics requests; no business logic in commands; services use no JDA types; all user-facing text in `messages.properties`.

## Step 0: verify the endpoint (before writing code)
Check `GET /v1/analytics/hero-stats` in `https://api.deadlock-api.com/openapi.json`. This needs network access. If you have none, ask the owner to paste the relevant OpenAPI section.
Confirm and record in the report:
1. Query parameters `min_average_badge`, `max_average_badge` and `min_unix_timestamp` exist with these names and meanings, and what the default time window is when `min_unix_timestamp` is absent.
2. The response is a JSON array of per-hero objects with `hero_id`, `matches`, `wins`, `losses` (or their real names). Note any parameter that changes the grouping (for example `bucket`); do not send it.

If a field only has a different JSON name, use the real name in the DTO's `@JsonProperty`, keep the Java names below and report it. If the structure differs (not one row per hero, no win/match counts, no badge or timestamp filter), **stop and ask**.

## Files to create or modify
| File | Change |
|---|---|
| `src/main/java/sntiago05/rejuv/clients/dto/HeroStat.java` | **Create.** DTO record. |
| `src/main/java/sntiago05/rejuv/clients/DeadlockApiClient.java` | **Modify.** Only add `HERO_STATS_PATH`, its type reference, `getHeroStats` and, if needed, one private helper for the badge-only filter. Do not change existing methods' behavior. |
| `src/main/java/sntiago05/rejuv/config/CacheConfig.java` | **Modify.** Only add `DEADLOCK_HERO_STATS` and its `register(...)` line. |
| `src/main/java/sntiago05/rejuv/domain/HeroTier.java` | **Create.** Enum. |
| `src/main/java/sntiago05/rejuv/domain/MetaHero.java` | **Create.** Record. |
| `src/main/java/sntiago05/rejuv/domain/MetaTierList.java` | **Create.** Record. |
| `src/main/java/sntiago05/rejuv/services/MetaService.java` | **Create.** |
| `src/main/java/sntiago05/rejuv/commands/MetaCommand.java` | **Create.** |
| `src/main/resources/messages.properties` | **Modify.** Append the `meta.*` keys; add one `/meta [rank]` line to `help.body` after the `/analyze` line. Change nothing else. |
| `src/test/resources/fixtures/deadlock-api/hero-stats.json` | **Create.** Real shape from Step 0, **invented** numbers, 4-6 heroes. |
| `src/test/java/sntiago05/rejuv/clients/DeadlockApiClientTest.java` | **Modify.** Add the hero-stats tests only. |
| `src/test/java/sntiago05/rejuv/clients/DeadlockApiClientCachingTest.java` | **Modify.** Add `DEADLOCK_HERO_STATS` → 6 h / 50 to the exact-set maps and one caching test. |
| `src/test/java/sntiago05/rejuv/services/MetaServiceTest.java` | **Create.** |
| `src/test/java/sntiago05/rejuv/commands/MetaCommandTest.java` | **Create.** |
| `src/test/java/sntiago05/rejuv/commands/HelpCommandTest.java` | **Modify.** Only add `"/meta"` to the asserted command list. |
| `src/test/java/sntiago05/rejuv/services/MessagesIntegrationTest.java` | **Modify.** Only add the new keys to `requiredKeys` and `"meta"` to the command-name assertion. |
| `docs/task-reports/013-meta.md` | **Create.** Task report (format of the Task 006 report). |

No `pom.xml`, `application.yaml`, migration, entity or repository change is needed. If you think one is needed, stop and ask.

## Interfaces / signatures

### `HeroStat` (`sntiago05.rejuv.clients.dto`, new)
```java
@JsonIgnoreProperties(ignoreUnknown = true)
public record HeroStat(@JsonProperty("hero_id") int heroId,
                       @JsonProperty("matches") long matches,
                       @JsonProperty("wins") long wins,
                       @JsonProperty("losses") long losses) { }
```

### `DeadlockApiClient` (add)
```java
private static final String HERO_STATS_PATH = "/v1/analytics/hero-stats";

/**
 * Per-hero win and match counts since minUnixTimestamp. rankTier null = all ranks (no badge filter).
 * Callers must not call the analytics methods in parallel for different uncached keys.
 * @throws IllegalArgumentException if rankTier is non-null and not in 0..11 (before any HTTP call)
 */
@Cacheable(cacheNames = CacheConfig.DEADLOCK_HERO_STATS, sync = true, key = "#rankTier + ':' + #minUnixTimestamp")
public List<HeroStat> getHeroStats(Integer rankTier, long minUnixTimestamp);
```
Request: `min_unix_timestamp=<minUnixTimestamp>`; when `rankTier` is non-null also `min_average_badge=rankTier*10` and `max_average_badge=rankTier*10+9`. No other parameter. Returns `List.copyOf(...)`. Same error mapping and 503 retry as the other analytics methods (through `get`, with `false` as the last argument).

### `CacheConfig` (add)
`public static final String DEADLOCK_HERO_STATS = "deadlockHeroStats"; // 6 h, max 50` and `register(cacheManager, DEADLOCK_HERO_STATS, Duration.ofHours(6), 50);`

### Domain (`sntiago05.rejuv.domain`, new)
```java
/** Tier by win rate percent (unrounded): S >= 53, A >= 51, B >= 49, C >= 47, D below. */
public enum HeroTier {
    S, A, B, C, D;
    public static HeroTier of(double winRatePercent);
}

/** heroName is null when the hero id is not in the heroes asset. */
public record MetaHero(int heroId, String heroName, long matches, double winRatePercent, HeroTier tier) { }

/** rankTier null = all ranks. heroes immutable, sorted by winRatePercent desc, then heroId asc; may be empty. */
public record MetaTierList(Integer rankTier, int minMatches, List<MetaHero> heroes) { }
```

### `MetaService` (`sntiago05.rejuv.services`, `@Service`, new)
```java
static final int MIN_MATCHES = 100;
static final Duration WINDOW = Duration.ofDays(30);
static final int MIN_RANK_TIER = 1;
static final int MAX_RANK_TIER = 11;

@Autowired
public MetaService(DeadlockApiClient deadlockApiClient)                // Clock.systemUTC()
MetaService(DeadlockApiClient deadlockApiClient, Clock clock)          // package-private, for tests

/**
 * @param rankTier null = all ranks, else 1..11
 * @throws IllegalArgumentException for a tier outside 1..11 (before any call)
 * Lets DeadlockApiUnavailableException / DeadlockApiException propagate.
 */
public MetaTierList getTierList(Integer rankTier);
```

### `MetaCommand` (`sntiago05.rejuv.commands`, `@Component implements SlashCommand`, new)
- `MetaCommand(MetaService metaService, Messages messages)`.
- `name()` = `meta`; `data()` = `Commands.slash("meta", messages.get("meta.description"))` with one option: type `INTEGER`, name `rank`, description `messages.get("meta.option.rank")`, **not** required, 11 choices with value `1..11` and name `messages.get("meta.rank." + tier)`.

## Behavior

### `MetaService.getTierList(rankTier)`
1. Validate `rankTier` (null or 1..11).
2. `minUnixTimestamp` = start of the current UTC day (from the clock) minus 30 days, in epoch seconds. Truncating to the day keeps the cache key stable for a day.
3. Calls in this order, sequentially: `getHeroStats(rankTier, minUnixTimestamp)`, then `getHeroes()`.
4. For each `HeroStat` with `matches >= MIN_MATCHES`: `winRatePercent = wins * 100.0 / matches` (unrounded), `tier = HeroTier.of(winRatePercent)`, `heroName` = `HeroAsset.name` for that id or `null`. Rows below the minimum (including `matches == 0`) are dropped. Duplicate hero ids: keep the first.
5. Sort by `winRatePercent` descending, then `heroId` ascending. Return `MetaTierList(rankTier, MIN_MATCHES, heroes)`.

### `/meta [rank]`
1. `event.deferReply().queue()` (public; it advertises the bot). No link check.
2. Read the optional `rank` option (`Integer`, null when absent). Call `getTierList` synchronously and answer through the hook:
   - non-empty list → `editOriginalEmbeds(embed).queue()`;
   - empty list → `editOriginal(meta.no_data).queue()`;
   - `DeadlockApiUnavailableException` → `editOriginal(error.data_source_busy).queue()`;
   - any other `RuntimeException` → error id `UUID.randomUUID()`, log `ERROR` with id, caller id, rank tier and the exception, `editOriginal(error.generic)` with the id. Do not rethrow.

### Embed
Built with JDA `EmbedBuilder`; every text from `Messages`; numbers pre-formatted with `String.format(Locale.ROOT, "%.1f", winRatePercent)`.
- Title: `meta.title` with `{0}` = `meta.rank.<tier>` or `meta.all_ranks` when the tier is null.
- One field (not inline) per **non-empty** tier, in order S, A, B, C, D: name `meta.tier.<S|A|B|C|D>`, value = the tier's heroes in list order, one `meta.hero_entry` per line (`{0}` = hero name or `meta.hero_unknown` with `{0}` = id, `{1}` = win rate), joined with `\n`. Values stay under Discord's 1,024-char field limit for the ~40 heroes of the game; do not add per-hero match counts.
- Footer: `meta.footer` with `{0}` = `minMatches`.

## User-facing text (`messages.properties`, append, English)
Messages with arguments must not contain a single apostrophe. Command, option and choice texts: 1-100 chars.

| Key | Intent |
|---|---|
| `meta.description` | Command description: hero tier list by win rate, for all ranks or one rank |
| `meta.option.rank` | Option description: rank to filter by (default: all ranks) |
| `meta.rank.1` ... `meta.rank.11` | Rank names, used as choice labels and in the title: Initiate, Seeker, Alchemist, Arcanist, Ritualist, Emissary, Archon, Oracle, Phantom, Ascendant, Eternus (tiers 1-11). Tiers 1-2 match the `assets-ranks.json` fixture; if the real ranks asset you see differs, stop and ask |
| `meta.all_ranks` | "All ranks" |
| `meta.title` | `{0}` rank label, e.g. "Hero tier list: {0}" |
| `meta.tier.S` ... `meta.tier.D` | Field names, e.g. "S tier" |
| `meta.hero_entry` | `{0}` hero, `{1}` win rate, e.g. "{0} - {1}%" |
| `meta.hero_unknown` | `{0}` id, e.g. "Hero #{0}" |
| `meta.footer` | `{0}` min matches: win rate over the last 30 days, heroes with at least {0} matches, data from deadlock-api |
| `meta.no_data` | Not enough recent matches for this rank yet; try another rank or all ranks |
| `help.body` (one added line) | `` `/meta [rank]` - See the hero tier list by win rate.`` after the `/analyze` line |

## Tests
JUnit 5, AssertJ, Mockito; `MockRestServiceServer` and the new fixture for the client; `MessagesFixture.english()` for commands.

### `DeadlockApiClientTest` (add)
1. `heroStatsSendsBadgeRangeAndTimestamp`: `getHeroStats(7, 1789000000L)` sends `min_average_badge=70`, `max_average_badge=79`, `min_unix_timestamp=1789000000` and nothing else; parses the fixture.
2. `heroStatsWithoutRankSendsNoBadgeFilter`.
3. `heroStatsRejectsTierOutOfRange` (12 and -1, no HTTP call).
4. `heroStatsServiceUnavailableIsMapped` (503 with a large `Retry-After` → `DeadlockApiUnavailableException`), following the existing analytics tests.

### `DeadlockApiClientCachingTest` (modify)
Add the new cache to the exact-set maps (6 h, 50). Add `heroStatsAreCachedPerTierAndTimestamp`: same arguments → one HTTP call; a different tier or timestamp → a new call.

### `MetaServiceTest` (mocks, fixed `Clock`)
1. `timestampIsStartOfUtcDayMinus30Days` (clock at 2026-10-17T15:30Z → 2026-09-17T00:00Z).
2. `callsStatsThenHeroesSequentially` (`InOrder`), `allRanksPassesNullTier`.
3. `invalidTierIsRejectedWithoutCalls` (0 and 12).
4. `tierThresholdBoundaries`: 53.0 → S, 52.99 → A, 51.0 → A, 49.0 → B, 47.0 → C, 46.99 → D (test `HeroTier.of` directly too).
5. `heroesBelowMinMatchesAreDropped` (99 dropped, 100 kept, 0 dropped).
6. `sortedByWinRateThenHeroId`, `unknownHeroHasNullName`, `duplicateHeroIdKeepsFirst`.
7. `allBelowMinimumGivesEmptyList`, `propagatesUnavailableException`.

### `MetaCommandTest` (style of `ProfileCommandTest`)
1. `nameIsMetaWithOptionalRankChoices`: `INTEGER`, not required, 11 choices with values 1..11 and catalog labels, descriptions 1-100 chars.
2. `defersPubliclyAndEditsWithEmbed`: no ephemeral deferral; title, fields only for non-empty tiers in S..D order, entry lines, footer from the catalog; edit queued.
3. `titleUsesAllRanksWithoutOption` and `rankOptionIsPassedToService`.
4. `unknownHeroUsesHeroUnknown`.
5. `emptyListEditsWithNoData`, `unavailableEditsWithBusyMessage`, `unexpectedErrorEditsWithGenericMessageAndDoesNotRethrow`; each verifies the edit is queued.

### `HelpCommandTest`, `MessagesIntegrationTest` (minimal edits)
`help.body` contains `/meta`; every new key resolves; command names include `meta`. `MessagesIntegrationTest` needs Docker.

## Verification
1. Step 0 done and recorded in the report.
2. `./mvnw clean verify` passes with Docker running (on Windows `.\mvnw.cmd clean verify`; see the `MAVEN_USER_HOME` note in `docs/PROJECT_CONTEXT.md` if the wrapper fails). Report the real test count against the baseline you measure on `origin/main` before your first change.
3. `git diff --check` and a review of the final diff: only the files in the table changed; the fixture has invented numbers; no secret, token or real user data; `.env` never read or printed.
4. Optional manual check in the dev guild (needs `DISCORD_TOKEN`, `DEV_GUILD_ID`, PostgreSQL): `/meta` and `/meta rank:Archon`. If you do not run it, say so in the report.

## Documentation
- Task report `docs/task-reports/013-meta.md`: status, Step 0 findings, summary, files, commands run with real results, deviations, known issues.
- `docs/PROJECT_CONTEXT.md`: **do not edit in this branch.** Task 007 runs in parallel; mark the context update as "pending integration" in the report (`AGENTS.md`, Parallel tasks), listing the facts to add after both branches merge (endpoint facts from Step 0, new cache, `/meta` done).
- No ADR. Do not edit `docs/mvp-spec.md`, `docs/architecture.md`, `docs/features.md`, other specs or `docs/roadmap.md`.

## Out of scope
- Pick rate, ban rate, per-patch filtering, hero synergies or counters (`/synergy`, `/counter`, Task 012), builds (Task 011), personal hero stats (Task 014).
- Rank autocomplete, tier 0 (`Obscurus`) as a choice, sending `bucket` or any parameter not listed.
- Changing existing `DeadlockApiClient` methods, other DTOs, `CommandRouter`, `Messages`, `application.yaml`, `pom.xml`.
- A service-level cache (the computation is cheap; the client cache is shared).

## Acceptance criteria
- [ ] Step 0 findings are in the report; the DTO matches the real response shape
- [ ] `./mvnw clean verify` passes; test count and baseline difference are in the report
- [ ] `getHeroStats` sends exactly the listed parameters, validates the tier before any HTTP call and is cached 6 h (max 50) per tier and timestamp
- [ ] `/meta` works without a linked account; it defers publicly and shows non-empty tiers S..D with heroes sorted by win rate, all text from `messages.properties`
- [ ] Heroes with fewer than 100 matches are excluded; tier thresholds are exactly 53/51/49/47
- [ ] `/meta rank:<tier>` filters by that tier's badge range; the time window starts at the UTC day 30 days ago
- [ ] Empty result, API unavailable and unexpected errors each give their own message; unexpected errors log an error id and do not rethrow
- [ ] API calls are sequential; commands contain no business logic; `MetaService` uses no JDA types
- [ ] `help.body` lists `/meta`
- [ ] Only the files in the table changed; no new dependency, migration or secret
- [ ] Task report created with `docs/PROJECT_CONTEXT.md` update marked pending integration; final diff has no unrelated files

## Decisions and open questions
Decisions taken in this spec:
1. **Tier by fixed win-rate thresholds** (53/51/49/47), not by quantiles, so a tier means the same in every rank. Task 010 or the owner can tune them.
2. **Last 30 days, truncated to the UTC day**, instead of "current patch": there is no verified patch endpoint yet, and the day truncation keeps the shared cache effective.
3. **Fixed `INTEGER` choices with catalog labels** instead of autocomplete or an API call at registration. Registration must not depend on the API being up.
4. **Minimum 100 matches per hero** to hide noisy win rates in small high-rank samples; if a rank has no hero above it, `meta.no_data`.
5. **Public reply**: free utilities are growth hooks (ADR-0014).

Open for the owner (not blocking):
1. Whether `/meta` should later mark the patch date or show pick rate. Out of scope here.
