# Task 014: `/heroes [user]`

- **Branch**: `feature/014-heroes`
- **Implementer**: Antigravity
- **Related ADRs**: ADR-0014 (free personal utility, shared caches first), ADR-0010 and ADR-0004 (deadlock-api), ADR-0002 (Caffeine), ADR-0008 (English, i18n-ready)

## Goal
Add `/heroes [user]`: a public embed with the hero pool of a linked player over the last 90 days: their most played heroes with matches, win rate, KDA, and both the win rate and the KDA compared with the average of that hero at the player's rank (last 30 days), plus two short pool tips (best and weakest pick relative to the field). No AI call. It adds one per-player read (`players/hero-stats`) to `DeadlockApiClient`, cached, and extends the existing `HeroStat` DTO with the kill, death and assist totals that `analytics/hero-stats` already returns (owner decision, 2026-10-17); `/meta` keeps its behavior.

## Context (verified when this spec was written, 2026-10-17)
Do not repeat this discovery. If a fact below is false in your checkout, stop and ask.

- Plan entry: `docs/implementation-plan.md`, task 014, depends on 005 (merged). Start the branch from the latest `origin/main`. Baseline `./mvnw clean verify` = 344 tests (2 skipped benchmark tests); measure it before your first change.
- **Parallel work.** Task 024 (rich presentation), Task 009 (`/analyze`) and Task 016 (`/leaderboard server`) are implemented at the same time. Therefore: **do not use or create** `EmbedStyle`, `IconCatalog`, `AssetImages`; use a plain `EmbedBuilder` like `ProfileCommand`; do not touch `HelpCommand`, `help.body`, `HelpCommandTest`, `MessagesIntegrationTest`, `JdaConfig`, `LinkedAccountRepository`, `LinkService`, `ProfileCommand`, `ProfileService`, `MetaCommand`, `MetaCommandTest` (Task 024 restyles the last two and `meta.*` messages). A later follow-up adds `/heroes` to `/help` and restyles it. Expected, trivial merge conflicts (keep both sides): `CacheConfig`, `DeadlockApiClient`, `DeadlockApiClientCachingTest`, `messages.properties`. Follow the insertion points given below to keep them rare.
- **The endpoint is `GET /v1/players/hero-stats`** (batch form, query `account_ids` required, comma separated, 1..1000 ids; there is **no** `/v1/players/{id}/hero-stats`, the catalog entry in `docs/features.md` is outdated). Verified in `https://api.deadlock-api.com/openapi.json` and with one live call on 2026-10-17 (HTTP 200, 41 hero items, 78 KB for a long history; an account without data returns 200 `[]`, not 404). Filters used here: `min_unix_timestamp` (int64, match start), `game_mode` default `normal`, `match_mode` description default `ranked,unranked`. There is **no** `min_matches` filter on this endpoint. Rate limit: IP 100 req/s. Under load any uncached request can return 503 with `Retry-After` (already handled by `DeadlockApiRequests`).
- Item shape (live, numbers real, trimmed to the fields used here): `{"account_id":139205933,"hero_id":1,"matches_played":108,"wins":54,"kills":786,"deaths":566,"assists":1167,"last_played":1790571726,"time_played":200445,"matches":[27052017,...], ...other fields}`. Verified live: `kills`, `deaths`, `assists` are **totals** over the filtered matches and `matches_played == matches.length`. The `matches` array is not needed (do not map it).
- Existing code you will call (real signatures): `DeadlockApiClient.getHeroStats(Integer rankTier, long minUnixTimestamp)` -> `List<HeroStat(int heroId, long matches, long wins, long losses)>` (this task adds three components, see below; null tier = all ranks, 1..11 = badge range, cached `deadlockHeroStats` 6 h/50; the same call `/meta` makes), `getPlayerRank(long accountId)` -> `PlayerRank(badge, rank, subrank)` (badge 0 = unranked), `services/GameAssets.heroNames()` (`Map<Integer, String>`), `services/ProfileService.findLinkedAccountId(long)` -> `OptionalLong` (database only; reuse it, do not duplicate the lookup), `services/ServiceSupport` (package-private: `WINDOW` = 30 days, `validateRankTier`, `startOfUtcDayMinus(Clock, Duration)`, `top(List, Comparator, int)`), `commands/CommandSupport.oneDecimal`, `CommandSupport.rankLabel(Messages, Integer)`, `CommandSupport.handleUnexpectedError(...)`.
- **`analytics/hero-stats` totals** (OpenAPI only, **no live sample yet**): each `AnalyticsHeroStats` row has required int64 fields `hero_id`, `matches`, `wins`, `losses`, `total_kills`, `total_deaths`, `total_assists` (and many other `total_*`). The names suggest sums over the row's `matches`, but the spec does not say so; Step 0 verifies it. The fixture `src/test/resources/fixtures/deadlock-api/hero-stats.json` has only `total_kills` today. `HeroStat` is built with four arguments in `DeadlockApiClientTest` (2 places) and `MetaServiceTest` (about 12 places); `MetaService` reads only `heroId`, `matches`, `wins`.
- Reference implementations to mirror: `ProfileService` (two-constructor `Clock` pattern, no `Clock` bean) and `ProfileCommand` (optional `USER` option, "not linked" check **before** deferring, public `deferReply()`, own `try/catch` after the deferral; catch `DeadlockApiUnavailableException` before the broader types, which extend `DeadlockApiException`).
- Rules from `AGENTS.md`: every client read cached; sequential API calls (never parallel uncached analytics requests); DTO records with `@JsonIgnoreProperties(ignoreUnknown = true)` and only the used fields; no JDA types in `services/`; no business logic in `commands/`; all user-facing text in `messages.properties` (messages with arguments contain no single apostrophe); no new dependency.

## Step 0: verify (before writing code)
One live GET (respect the rate limits; if you have no network access ask the owner to paste a shortened body): `GET https://api.deadlock-api.com/v1/players/hero-stats?account_ids=<a public account id>&min_unix_timestamp=<now minus 90 days>`. Confirm and record in the report: field names above; that a later `min_unix_timestamp` returns fewer or equal `matches_played` than without it; the size of the body. If a name differs, use the real name in `@JsonProperty`, keep the Java names below and report it. If the structure differs materially, **stop and ask**.

Second live GET: `GET https://api.deadlock-api.com/v1/analytics/hero-stats?min_unix_timestamp=<now minus 30 days>&min_average_badge=70&max_average_badge=79` (analytics rate limit 200 req/min; one request only). Confirm and record: one row per hero (no repeated `hero_id` without `bucket`); `total_kills`, `total_deaths`, `total_assists` are present; they are sums over the row's `matches` (per match: kills about 4 to 12, deaths about 4 to 9, assists about 5 to 20, and `total_deaths / matches` is the same order of magnitude as the player endpoint's deaths per match). If they are not sums per row, or a field is missing, **stop and ask**.

## Files to create or modify
| File | Change |
|---|---|
| `src/main/java/sntiago05/rejuv/clients/dto/PlayerHeroStat.java` | **Create.** DTO record. |
| `src/main/java/sntiago05/rejuv/clients/dto/HeroStat.java` | **Modify.** Add `totalKills`, `totalDeaths`, `totalAssists`; keep the old 4-argument constructor. |
| `src/main/java/sntiago05/rejuv/clients/DeadlockApiClient.java` | **Modify.** Add only: the import of `PlayerHeroStat` (between `MetricPercentiles` and `PlayerRank`), a path constant right after `HERO_STATS_PATH`, a `BodyReader` constant right after `HERO_STATS_READER`, and the method `getPlayerHeroStats` right after `getHeroStats`. Change nothing else. |
| `src/main/java/sntiago05/rejuv/config/CacheConfig.java` | **Modify.** Only add `DEADLOCK_PLAYER_HERO_STATS` (after `DEADLOCK_COUNTER_ITEMS`) and its `register(...)` line (after the `DEADLOCK_COUNTER_ITEMS` one). |
| `src/main/java/sntiago05/rejuv/domain/HeroPerformance.java` | **Create.** Record. |
| `src/main/java/sntiago05/rejuv/domain/HeroPoolSummary.java` | **Create.** Record. |
| `src/main/java/sntiago05/rejuv/services/HeroPoolService.java` | **Create.** |
| `src/main/java/sntiago05/rejuv/commands/HeroesCommand.java` | **Create.** |
| `src/main/resources/messages.properties` | **Modify.** Append the `heroes.*` block at the **end of the file**. Change nothing else. |
| `src/test/resources/fixtures/deadlock-api/hero-stats.json` | **Modify.** Add invented `total_deaths` and `total_assists` to every row (keep ids, `matches`, `wins`, `losses`, `total_kills` as they are). |
| `src/test/java/sntiago05/rejuv/clients/DeadlockApiClientTest.java` | **Modify.** Only the two `HeroStat` assertions of the hero-stats tests (about lines 253-254): compare with the 7-argument form, and add one test that a body **without** the three totals still parses (they become 0). |
| `src/test/java/sntiago05/rejuv/services/MetaServiceTest.java` | **Modify.** Add only one test proving `/meta` ignores the totals: tiers, ordering and win rates are identical for rows with and without non-zero totals. Existing tests keep using the 4-argument constructor unchanged. |
| `src/test/resources/fixtures/deadlock-api/player-hero-stats.json` | **Create.** Real shape, **invented** numbers, 4 hero items including unknown extra fields (`accuracy`, `matches` arrays, ...). |
| `src/test/java/sntiago05/rejuv/clients/PlayerHeroStatsClientTest.java` | **Create.** Endpoint tests (new class to avoid editing the shared `DeadlockApiClientTest`; copy its `MockRestServiceServer` setup pattern). |
| `src/test/java/sntiago05/rejuv/clients/DeadlockApiClientCachingTest.java` | **Modify.** Add the new cache to the exact-set maps in `cacheTtlsAndSizesMatchTheDesign` (at the **end** of both maps) and one caching test for `getPlayerHeroStats`. |
| `src/test/java/sntiago05/rejuv/services/HeroPoolServiceTest.java` | **Create.** |
| `src/test/java/sntiago05/rejuv/commands/HeroesCommandTest.java` | **Create.** Also asserts the command name is `heroes` and that every `heroes.*` key resolves (this replaces edits to `MessagesIntegrationTest`). |
| `docs/task-reports/014-heroes.md` | **Create.** Task report (format of `docs/task-reports/013-meta.md`). |

No `pom.xml`, `application.yaml`, migration, entity or repository change. If you think one is needed, stop and ask.

## Interfaces / signatures

### `clients/dto/HeroStat` (modified)
```java
@JsonIgnoreProperties(ignoreUnknown = true)
public record HeroStat(@JsonProperty("hero_id") int heroId,
                       @JsonProperty("matches") long matches,
                       @JsonProperty("wins") long wins,
                       @JsonProperty("losses") long losses,
                       @JsonProperty("total_kills") long totalKills,
                       @JsonProperty("total_deaths") long totalDeaths,
                       @JsonProperty("total_assists") long totalAssists) {

    /** Win and loss counts only; the kill, death and assist totals are 0. Keeps existing callers and tests compiling. */
    public HeroStat(int heroId, long matches, long wins, long losses) {
        this(heroId, matches, wins, losses, 0, 0, 0);
    }
}
```
A test must prove Jackson deserializes through the canonical constructor (totals present) and that a body without the totals gives 0. `getHeroStats` keeps its signature, cache name and key; `/meta` (`MetaService`, `MetaCommand`) is **not** changed and keeps its exact output: Task 024 restyles `MetaCommand` and the `meta.*` messages, so showing KDA in `/meta` is a follow-up after 024 is merged.

### `clients/dto/PlayerHeroStat` (new)
```java
@JsonIgnoreProperties(ignoreUnknown = true)
public record PlayerHeroStat(@JsonProperty("hero_id") int heroId,
                             @JsonProperty("matches_played") long matchesPlayed,
                             @JsonProperty("wins") long wins,
                             @JsonProperty("kills") long kills,
                             @JsonProperty("deaths") long deaths,
                             @JsonProperty("assists") long assists) { }
```

### `DeadlockApiClient.getPlayerHeroStats` (new method)
```java
/**
 * Per-hero totals of one player for matches that started at or after minUnixTimestamp (game mode normal,
 * API defaults otherwise). An empty list means no matches in the window.
 * Callers must not call the analytics-style methods in parallel for different uncached keys.
 */
@Cacheable(cacheNames = CacheConfig.DEADLOCK_PLAYER_HERO_STATS, sync = true, key = "#accountId + ':' + #minUnixTimestamp")
public List<PlayerHeroStat> getPlayerHeroStats(long accountId, long minUnixTimestamp)
```
Path `/v1/players/hero-stats`, query `account_ids=<accountId>` and `min_unix_timestamp=<ts>`. Return `List.copyOf(...)`. Failures use the existing rules (`DeadlockApiUnavailableException` for 503/429/I/O/timeout, `DeadlockApiException` for other statuses and unreadable bodies; failures are not cached).

### `CacheConfig`
`public static final String DEADLOCK_PLAYER_HERO_STATS = "deadlockPlayerHeroStats"; // 10 min, max 200` registered with `Duration.ofMinutes(10)` and 200 entries (bodies can be large; keep the maximum small).

### Domain records (new)
```java
/** heroName is null when the hero id is not in the heroes asset. The three benchmark values are null when no benchmark exists (heroAvgKda also when the benchmark row has no deaths). */
public record HeroPerformance(int heroId, String heroName, int matches, int wins, double winRatePercent,
                              double kda, Double heroAvgWinRatePercent, Double deltaPoints, Double heroAvgKda) { }

/** rankTier null = the player is unranked, so the benchmark is all ranks. topHeroes is empty when the player has no match in the window. best / weakest are null when not available. */
public record HeroPoolSummary(long accountId, Integer rankTier, List<HeroPerformance> topHeroes,
                              HeroPerformance best, HeroPerformance weakest) { }
```
Lists are immutable copies.

### `services/HeroPoolService` (new `@Service`)
```java
static final int TOP_HEROES = 5;
static final int MIN_MATCHES_FOR_TIP = 10;
public static final int MIN_BENCHMARK_MATCHES = 100;   // matches of the hero in the benchmark, as in /meta
static final Duration PLAYER_WINDOW = Duration.ofDays(90);

@Autowired public HeroPoolService(DeadlockApiClient deadlockApiClient, GameAssets gameAssets)
HeroPoolService(DeadlockApiClient deadlockApiClient, GameAssets gameAssets, Clock clock)   // tests
/** Lets DeadlockApiUnavailableException / DeadlockApiException propagate. */
public HeroPoolSummary getHeroPool(long accountId)
```

## Behavior

### `HeroPoolService.getHeroPool(accountId)` (sequential calls; one pass over each list; hash maps for lookups)
1. `playerStart = ServiceSupport.startOfUtcDayMinus(clock, PLAYER_WINDOW)`; `stats = getPlayerHeroStats(accountId, playerStart)`. Empty -> return a summary with empty `topHeroes`, `best`/`weakest` null, `rankTier` null, and **make no further call**.
2. `rank = getPlayerRank(accountId)`; `rankTier = rank.badge() != 0 && rank.rank() in 1..11 ? rank.rank() : null`.
3. `benchmarkStart = ServiceSupport.startOfUtcDayMinus(clock, ServiceSupport.WINDOW)`; `benchmark = getHeroStats(rankTier, benchmarkStart)`; index it by hero id (first row wins, like `MetaService`) keeping only rows with `matches >= MIN_BENCHMARK_MATCHES`. Heroes' average win rate = `wins * 100.0 / matches`; average KDA = `(totalKills + totalAssists) / (double) totalDeaths`, or null when `totalDeaths == 0` (also the case when the API omitted the totals).
4. For each `PlayerHeroStat` with `matchesPlayed > 0` (first row wins for a duplicated hero id): `winRatePercent = wins * 100.0 / matchesPlayed`; `kda = (kills + assists) / max(deaths, 1)` as a double; `heroAvgWinRatePercent`, `deltaPoints = winRatePercent - heroAvg` and `heroAvgKda` from the benchmark map or null; name from `gameAssets.heroNames()`.
5. `topHeroes` = top 5 by `matches` desc, then `heroId` asc (use `ServiceSupport.top`).
6. `best` = among **all** the player's heroes (not only the top 5) with `matches >= MIN_MATCHES_FOR_TIP` and non-null `deltaPoints > 0`, the highest `deltaPoints` (ties: more matches, then lower hero id). `weakest` = same pool with `deltaPoints < 0`, the lowest `deltaPoints` (same ties).

### `/heroes [user]` (`HeroesCommand`, name `heroes`, public)
1. Option `user` (`OptionType.USER`, not required), default the caller, like `/profile`. Not linked: ephemeral `heroes.not_linked_self` / `heroes.not_linked_other` (`{0}` = `target.getAsMention()`), **before** deferring.
2. `event.deferReply().queue()`, then call the service inside its own `try/catch`: `DeadlockApiUnavailableException` -> `error.data_source_busy`; `RuntimeException` -> `CommandSupport.handleUnexpectedError(hook, messages, log, "Heroes", callerId, "accountId=" + accountId, ex)`. Empty `topHeroes` -> plain text `heroes.no_matches`.
3. Embed (`EmbedBuilder`, no color/thumbnail): title `heroes.title` (`{0}` = `target.getEffectiveName()`); field `heroes.field.top` with one line per hero `heroes.entry` (`{0}` hero name or `heroes.hero_unknown`, `{1}` matches, `{2}` win rate as a whole number, `{3}` the comparison text, `{4}` the KDA text); the comparison text is `heroes.vs_average` (`{0}` signed one-decimal points, for example `+3.2` or `-1.4`, formatted with `Locale.ROOT`) or `heroes.vs_average_unknown` when `deltaPoints` is null; the KDA text is `heroes.kda_vs_average` (`{0}` own KDA, `{1}` hero average, both with `CommandSupport.oneDecimal`) or `heroes.kda` (`{0}` own KDA) when `heroAvgKda` is null; field `heroes.field.tips`: `heroes.tip_best` (`{0}` hero, `{1}` signed points) and/or `heroes.tip_weakest`, or `heroes.tip_none` when both are null; footer `heroes.footer` (`{0}` = `CommandSupport.rankLabel(messages, summary.rankTier())`, `{1}` = `String.valueOf(HeroPoolService.MIN_BENCHMARK_MATCHES)`, so make that constant `public`; the text states: your matches are from the last 90 days, the averages are the hero win rate and KDA of `{0}` players over the last 30 days with at least `{1}` matches).
4. Every field value is at most 1024 characters (5 lines of short text cannot exceed it; add a test with the longest realistic hero names to prove it).

### User-facing text (`heroes.*`, English)
`heroes.description`, `heroes.option.user`, `heroes.not_linked_self`, `heroes.not_linked_other`, `heroes.no_matches`, `heroes.title`, `heroes.field.top`, `heroes.field.tips`, `heroes.entry`, `heroes.vs_average`, `heroes.vs_average_unknown`, `heroes.kda`, `heroes.kda_vs_average`, `heroes.hero_unknown`, `heroes.tip_best`, `heroes.tip_weakest`, `heroes.tip_none`, `heroes.footer`. Reuse `error.data_source_busy`, `error.generic`.

## Tests
JUnit 5, AssertJ, Mockito; `MockRestServiceServer` for HTTP; `MessagesFixture.english()` for commands; a fixed `Clock`.

1. **`PlayerHeroStatsClientTest`:** URL and query (`account_ids`, `min_unix_timestamp`) are exact; fixture parses (unknown fields ignored); empty array -> empty list; 503 with `Retry-After` retried once then `DeadlockApiUnavailableException`; 500 -> `DeadlockApiException`; malformed body -> `DeadlockApiException`.
2. **Caching test:** same `(accountId, ts)` makes one HTTP call; another key makes a second call; a failure is not cached; the exact-set test includes the new cache with 10 min / 200.
3. **`HeroPoolServiceTest`:** empty player stats -> empty summary and `getPlayerRank`/`getHeroStats` never called; ranked player uses the rank tier for `getHeroStats`, unranked (badge 0) uses `null`; win rate, KDA (including `deaths == 0`), delta; benchmark KDA from the totals, null when the benchmark `totalDeaths` is 0 while the win rate benchmark is still present; benchmark row below 100 matches gives null average, delta and benchmark KDA; top 5 order (matches desc, hero id asc) and truncation; `best` and `weakest` selection from heroes outside the top 5, ignore heroes under 10 matches, ties, none available; hero absent from assets -> null name; duplicated hero rows in either list use the first row; windows computed from the injected `Clock` (90 days and 30 days); exceptions propagate.
4. **`HeroesCommandTest`:** name `heroes`, optional user option; not linked self/other -> ephemeral, no deferral, no service call; success -> public deferral and an embed with the expected title, fields, lines and footer; null delta line uses `heroes.vs_average_unknown`; KDA with and without a hero average (`heroes.kda_vs_average` / `heroes.kda`); tips variants; empty -> `heroes.no_matches`; busy -> `error.data_source_busy`; unexpected error -> generic message with an error id and no rethrow; all `heroes.*` keys resolve to non-blank text.

## Verification
1. Step 0 recorded in the report.
2. `./mvnw clean verify` passes with Docker running (Windows: `.\mvnw.cmd clean verify`; see the `MAVEN_USER_HOME` note in `docs/PROJECT_CONTEXT.md` if the wrapper fails). Report the real test count against the baseline.
3. `git diff --check`; the diff contains only the files in the table; `.env` never read or printed.
4. Manual check in the dev guild (needs `DISCORD_TOKEN`, `DEV_GUILD_ID`, PostgreSQL): `/heroes` not linked; `/link` then `/heroes`; `/heroes user:<another linked user>`. If you do not run it, say so in the report.

## Documentation
- Task report `docs/task-reports/014-heroes.md`: status, Step 0 findings, summary, files, commands run with real results, deviations, known issues.
- `docs/PROJECT_CONTEXT.md`: **do not edit in this branch** while other task branches are active. Mark the update "pending integration" in the report and list the facts to add (endpoint `players/hero-stats`, `HeroStat` now carries kill/death/assist totals, the new cache, `HeroPoolService`, `/heroes`, that `/help` does not list it yet).
- Do not edit `docs/features.md`, ADRs, `docs/mvp-spec.md`, `docs/implementation-plan.md` or other specs.

## Out of scope
- `/help` text and `HelpCommandTest` (follow-up), presentation (Task 024 or a follow-up), `/mates`, party stats in `/profile`.
- Other per-hero averages (only win rate and KDA are benchmarked; no other `total_*` field is mapped) and showing KDA in `/meta` (follow-up after Task 024).
- AI summaries, premium gating, per-match lists.
- Changing any existing client method's behavior, `MetaService`, `MetaCommand`, `HeroResolver`, `ProfileService`.

## Acceptance criteria
- [ ] `getPlayerHeroStats` is cached (`deadlockPlayerHeroStats`, 10 min, max 200) and covered by the exact-set cache test
- [ ] `/heroes` shows up to 5 heroes with matches, win rate, win rate comparison, KDA with the hero average, plus pool tips
- [ ] `HeroStat` has the three new totals, the old 4-argument constructor still works, and `/meta` output is unchanged (existing `MetaServiceTest` and `MetaCommandTest` pass unmodified, plus the new ignore-totals test)
- [ ] Unranked players are compared with all ranks; heroes with fewer than 100 benchmark matches show no comparison
- [ ] A player with no matches in 90 days gets a plain `heroes.no_matches` reply after a single API call
- [ ] Not linked, busy source and unexpected errors have distinct messages; the unexpected one logs an error id
- [ ] No parallel or uncached repeated calls; no new dependency, migration or shared-file edit outside the table
- [ ] All user-facing text is in `messages.properties`; no field value exceeds 1024 characters
- [ ] `./mvnw clean verify` passes; test count and baseline difference are in the report
- [ ] Task report created; `docs/PROJECT_CONTEXT.md` update marked pending integration; final diff has no unrelated files

## Decisions and open questions
Decisions taken in this spec:
1. The player window is 90 days (enough games for a pool view; the response is capped by the window) while the benchmark window is the 30-day window shared with `/meta`. The footer states both.
2. Owner decision (2026-10-17): extend `HeroStat` with `total_kills`/`total_deaths`/`total_assists` so `/heroes` can benchmark KDA too. The old constructor is kept so `/meta` and its tests are untouched; showing KDA in `/meta` waits for Task 024 because it restyles `MetaCommand` and `meta.*`.
3. `ProfileService.findLinkedAccountId` is reused for the link check; a shared lookup can be extracted later.

Open for the owner (not blocking): the 90-day window and the thresholds (10 matches for tips, 100 for the benchmark) can be changed in review; `docs/features.md` still names a non-existent `players/{id}/hero-stats` path and should be corrected when the docs are next edited (this task is not authorized to edit it).
