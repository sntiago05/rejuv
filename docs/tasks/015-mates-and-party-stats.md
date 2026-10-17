# Task 015: `/mates` and solo vs party stats in `/profile`

- **Branch**: `feature/015-mates`
- **Implementer**: Antigravity
- **Related ADRs**: ADR-0014 (free personal utilities, shared caches first), ADR-0010 and ADR-0004 (deadlock-api), ADR-0002 (Caffeine), ADR-0008 (English, i18n-ready), ADR-0016 (icons and `EmbedStyle`)

## Goal
1. `/mates [user]`: public embed with the player's best teammates (the accounts they win most with) over a recent window, with games together and win rate together. No AI call.
2. `/profile`: one new line comparing the win rate playing **solo** with the win rate playing in a **party**.
Both read two per-player endpoints that are not wrapped yet, cached, through `DeadlockApiClient`.

## Context (verified from the repository when this spec was written, 2026-10-17)
Do not repeat this discovery. If a fact below is false in your checkout, stop and ask.

- Plan: `docs/implementation-plan.md` task 015 depends on 005 and 006 (both merged). Start the branch from the latest `origin/main`. Baseline: measure `./mvnw clean verify` before your first change (498 tests, 2 skipped when this spec was written).
- **Parallel work.** Tasks 025 (`/matches`, `/analyze` selection), 019 (usage limits) and 017 (web report) are specified at the same time; none of them touches `ProfileService`, `ProfileCommand`, `ProfileSummary` or `LinkedAccountRepository`. Expected trivial conflicts (keep both sides): `CacheConfig`, `DeadlockApiClient`, `DeadlockApiClientCachingTest`, `messages.properties` (append at the end; `help.body` only one line for `/mates`).
- **The endpoint paths and fields are NOT verified.** `docs/features.md` lists `players/{id}/mate-stats` and `players/{id}/party-stats`, but Task 014 found that its catalog path for per-player hero stats was outdated (the real endpoint is the batch `GET /v1/players/hero-stats?account_ids=`). Treat both paths and every JSON field name below as **unverified until Step 0**.
- Real code you will call or extend: `DeadlockApiClient` (`RestClient` + `DeadlockApiRequests` for the 503/429/404 rules; `getMatchHistory`, `getPlayerRank`, `getHeroStats`, `getPlayerHeroStats(accountId, minUnixTimestamp)` is the closest model: cached, `sync = true`, key `accountId:ts`, failures not cached), `config/CacheConfig` (static Caffeine caches; `DeadlockApiClientCachingTest.cacheTtlsAndSizesMatchTheDesign` asserts the exact set of names, TTLs and sizes), `services/ServiceSupport` (package-private: `WINDOW` 30 days, `startOfUtcDayMinus(Clock, Duration)`, `top(List, Comparator, int)`), `services/ProfileService` (two-constructor `Clock` pattern; `getProfile(accountId)` cached in `profileSummary` 10 min/1000: history, rank, ranks asset, heroes asset, in sequence), `domain/ProfileSummary(accountId, rankTier, rankName, subrank, recentMatches, recentWins, winRatePercent, avgKills, avgDeaths, avgAssists, topHeroes)`, `commands/ProfileCommand` (embed fields `profile.field.*`), `commands/CommandSupport`, `commands/EmbedStyle`, `services/IconCatalog`.
- Rules from `AGENTS.md`: every client read cached; sequential API calls, never parallel uncached analytics requests; DTO records with `@JsonIgnoreProperties(ignoreUnknown = true)` and only the used fields; no JDA types in `services/`; no business logic in `commands/`; user-facing text in `messages.properties` (messages with arguments contain no single apostrophe); no new dependency.

## Step 0: verify (before writing code)
Do **not** read the whole OpenAPI file: fetch only the two operations you need (or ask the owner to paste them), then make **one** live GET per endpoint (respect rate limits; if you have no network access ask the owner for a shortened body).
1. Find the real path, query parameters (account id form, time filter such as `min_unix_timestamp`, minimum-matches filter) and response shape of the **mate stats** and **party stats** of a player. Record in the report: exact path and query used, the JSON field names that identify the teammate account, the matches played together, the wins together, and for party stats the party-size key and its per-size matches and wins; whether solo (party size 1) is a row; the response size; the behavior for an account without data (200 `[]` or other).
2. Check whether the mate rows carry anything displayable besides the account id (a name). Record it. If there is none, the embed shows the account id only (see Behavior).
3. If the structure differs materially from the Goal (for example no wins together, or party stats not split by party size), **stop and ask**; do not invent fields.
Keep the Java names below; map the real JSON names with `@JsonProperty` and report them.

## Files to create or modify
| File | Change |
|---|---|
| `src/main/java/sntiago05/rejuv/clients/dto/MateStat.java` | **Create.** DTO record. |
| `src/main/java/sntiago05/rejuv/clients/dto/PartyStat.java` | **Create.** DTO record. |
| `src/main/java/sntiago05/rejuv/clients/DeadlockApiClient.java` | **Modify.** Add only the imports, the path constants, the `BodyReader` constants and the two methods, placed right after the per-player hero-stats method. Change nothing else. |
| `src/main/java/sntiago05/rejuv/config/CacheConfig.java` | **Modify.** Add only `DEADLOCK_MATE_STATS` and `DEADLOCK_PARTY_STATS` and their `register(...)` lines, after the last existing cache. |
| `src/main/java/sntiago05/rejuv/domain/Teammate.java` | **Create.** Record. |
| `src/main/java/sntiago05/rejuv/domain/MatesSummary.java` | **Create.** Record. |
| `src/main/java/sntiago05/rejuv/domain/PartySplit.java` | **Create.** Record. |
| `src/main/java/sntiago05/rejuv/services/MatesService.java` | **Create.** |
| `src/main/java/sntiago05/rejuv/commands/MatesCommand.java` | **Create.** |
| `src/main/java/sntiago05/rejuv/domain/ProfileSummary.java` | **Modify.** Add a nullable `PartySplit party` component and keep an 11-argument constructor (party null). |
| `src/main/java/sntiago05/rejuv/services/ProfileService.java` | **Modify.** Add the party stats call at the end of `getProfile`, degradable. Nothing else. |
| `src/main/java/sntiago05/rejuv/commands/ProfileCommand.java` | **Modify.** One extra field when `party != null`. Nothing else. |
| `src/main/resources/messages.properties` | **Modify.** Add the `mates.*` block and the new `profile.*` keys at the **end of the file**, and the `/mates` line to `help.body` (only that line). |
| `src/test/resources/fixtures/deadlock-api/mate-stats.json` and `party-stats.json` | **Create.** Real shape from Step 0, **invented** numbers, with extra unknown fields. |
| `src/test/java/sntiago05/rejuv/clients/MateAndPartyStatsClientTest.java` | **Create.** Endpoint tests (copy the `MockRestServiceServer` setup of `PlayerHeroStatsClientTest`). |
| `src/test/java/sntiago05/rejuv/clients/DeadlockApiClientCachingTest.java` | **Modify.** Add both caches to the exact-set maps (at the **end**) and one caching test per method. |
| `src/test/java/sntiago05/rejuv/services/MatesServiceTest.java` | **Create.** |
| `src/test/java/sntiago05/rejuv/services/ProfileServiceTest.java` | **Modify.** Party split present, absent and degraded. |
| `src/test/java/sntiago05/rejuv/commands/MatesCommandTest.java` | **Create.** |
| `src/test/java/sntiago05/rejuv/commands/ProfileCommandTest.java` | **Modify.** The new field appears only with a party split. |
| `src/test/java/sntiago05/rejuv/commands/HelpCommandTest.java` | **Modify.** Only the `contains(...)` list: add `/mates`. |
| `docs/task-reports/015-mates.md` | **Create.** Task report (format of `docs/task-reports/014-heroes.md`). |

No `pom.xml`, `application.yaml`, migration, entity or repository change. If you think one is needed, stop and ask.

## Interfaces / signatures
Everything below that depends on the API shape is the **intent**; adjust only the JSON mapping after Step 0.

### DTOs (new)
```java
@JsonIgnoreProperties(ignoreUnknown = true)
public record MateStat(/* teammate account id */ long mateId, long matches, long wins) { }

@JsonIgnoreProperties(ignoreUnknown = true)
public record PartyStat(/* party size */ int partySize, long matches, long wins) { }
```

### `DeadlockApiClient` (new methods)
```java
/** Teammates of one player (matches that started at or after minUnixTimestamp). Empty list = no data. */
@Cacheable(cacheNames = CacheConfig.DEADLOCK_MATE_STATS, sync = true, key = "#accountId + ':' + #minUnixTimestamp")
public List<MateStat> getMateStats(long accountId, long minUnixTimestamp)

/** Solo and party results of one player. Empty list = no data. */
@Cacheable(cacheNames = CacheConfig.DEADLOCK_PARTY_STATS, sync = true, key = "#accountId + ':' + #minUnixTimestamp")
public List<PartyStat> getPartyStats(long accountId, long minUnixTimestamp)
```
Return `List.copyOf(...)`. Failures use the existing rules (`DeadlockApiUnavailableException` for 503/429/I/O/timeout, `DeadlockApiException` for other statuses and unreadable bodies; failures are not cached). Use the real time-filter parameter from Step 0; if an endpoint has no time filter, drop the timestamp from the method and the key and say so in the report.

### `CacheConfig`
`DEADLOCK_MATE_STATS = "deadlockMateStats"` (10 min, max 200) and `DEADLOCK_PARTY_STATS = "deadlockPartyStats"` (10 min, max 200).

### Domain (new)
```java
/** wins and matches are together with the player; winRatePercent is wins * 100.0 / matches. */
public record Teammate(long accountId, int matches, int wins, double winRatePercent) { }
/** teammates is empty when no teammate reaches the minimum; at most 5 entries. */
public record MatesSummary(long accountId, List<Teammate> teammates) { }
/** soloMatches/partyMatches are 0 when absent; win rates are whole percent, 0 when there are no matches. */
public record PartySplit(int soloMatches, int soloWinRatePercent, int partyMatches, int partyWinRatePercent) { }
```
Lists are immutable copies.

### `services/MatesService` (new `@Service`)
```java
static final int TOP_MATES = 5;
static final int MIN_MATCHES_TOGETHER = 5;
static final Duration WINDOW = Duration.ofDays(90);
@Autowired public MatesService(DeadlockApiClient deadlockApiClient)
MatesService(DeadlockApiClient deadlockApiClient, Clock clock)   // tests
/** Lets DeadlockApiUnavailableException / DeadlockApiException propagate. */
public MatesSummary getMates(long accountId)
```
Rules: ignore the player themself and rows with `matches < MIN_MATCHES_TOGETHER`; order by win rate desc, then matches desc, then account id asc; keep 5; one pass over the list (use `ServiceSupport.top` if it fits; it is package-private in `services`, so no new helper is needed).

### `ProfileService` and `ProfileSummary`
`ProfileSummary` gets a last component `PartySplit party` (null = unavailable) and keeps its current constructor delegating with `null`. In `getProfile`, **after** the existing four calls, call `getPartyStats(accountId, startOfUtcDayMinus(clock, 90 days))` and build the split (party size 1 = solo, sizes 2 and above = party, sum matches and wins); on **any** `DeadlockApiException` (including unavailable) log WARN with the account id and the exception class name only, and set `party` to null: the profile must never fail because of party stats. An empty list also gives null.

## Behavior

### `/mates [user]` (`MatesCommand`, name `mates`, public)
1. Option `user` (not required), default the caller, like `/profile`; not linked: ephemeral `mates.not_linked_self` / `mates.not_linked_other` before deferring; then public `deferReply()`.
2. Service call inside its own `try/catch`: empty teammates -> plain text `mates.no_mates` (`{0}` the minimum games); `DeadlockApiUnavailableException` -> `error.data_source_busy`; other `RuntimeException` -> `CommandSupport.handleUnexpectedError(hook, messages, log, "Mates", callerId, "accountId=" + accountId, ex)`.
3. Embed (`EmbedStyle.COLOR`): title `mates.title` (`{0}` the target name); one field `mates.field.top` with one line per teammate from `mates.entry` (`{0}` position, `{1}` the teammate label, `{2}` matches together, `{3}` win rate as a whole number), through `EmbedStyle.fitField`; footer `mates.footer` (window of 90 days and the minimum of 5 games together). The teammate label is the name from the API when Step 0 found one, otherwise `mates.teammate_label` (`{0}` account id). **Never** resolve or show a Discord user for a teammate in this task.

### `/profile` party line
When `party != null` and (`soloMatches + partyMatches > 0`), add a field `profile.field.party` with `profile.party_value` (`{0}` solo matches, `{1}` solo win rate, `{2}` party matches, `{3}` party win rate); omit the field otherwise. No other `/profile` text changes.

### User-facing text (English)
`mates.description`, `mates.option.user`, `mates.not_linked_self`, `mates.not_linked_other`, `mates.no_mates`, `mates.title`, `mates.field.top`, `mates.entry`, `mates.teammate_label`, `mates.footer`, `profile.field.party`, `profile.party_value`. Reuse `error.data_source_busy`, `error.generic`.

## Tests
JUnit 5, AssertJ, Mockito; `MockRestServiceServer` for HTTP; `MessagesFixture.english()`; a fixed `Clock`.
1. **`MateAndPartyStatsClientTest`:** exact path and query; fixtures parse (unknown fields ignored); empty array -> empty list; 503 with `Retry-After` retried once then `DeadlockApiUnavailableException`; 500 -> `DeadlockApiException`; malformed body -> `DeadlockApiException`.
2. **Caching:** same key makes one HTTP call; another key makes a second call; failures are not cached; the exact-set test includes both new caches with 10 min / 200.
3. **`MatesServiceTest`:** the player themself ignored; below 5 games ignored; ordering and ties; top 5 truncation; empty input; window from the injected `Clock`; exceptions propagate.
4. **`ProfileServiceTest`:** solo and party split sums (sizes 2 and above merged); only solo; only party; empty list gives null; any `DeadlockApiException` gives null and the profile is still returned; the existing profile tests keep passing unchanged.
5. **`MatesCommandTest`:** name and optional user option; not linked self/other -> ephemeral, no deferral, no service call; success embed (title, lines, footer, icons not required); no mates; busy source; unexpected error (generic message with an error id, no rethrow); all `mates.*` keys resolve.
6. **`ProfileCommandTest`:** the party field appears with a split and with the right text, and is absent when null or when both counts are 0.

## Verification
1. Step 0 recorded in the report.
2. `./mvnw clean verify` passes with Docker running (Windows: `.\mvnw.cmd clean verify`; see the `MAVEN_USER_HOME` note in `docs/PROJECT_CONTEXT.md`). Report the real test count against the baseline.
3. `git diff --check`; the diff contains only the files in the table; `.env` never read or printed.
4. Manual check in the dev guild (needs the usual credentials and PostgreSQL): `/mates` not linked; `/link` then `/mates`; `/mates user:<another linked user>`; `/profile` shows the solo vs party line. If you do not run it, say so in the report.

## Documentation
- Task report `docs/task-reports/015-mates.md`: status, Step 0 findings (real paths and fields), summary, files, commands run with real results, deviations, known issues.
- `docs/PROJECT_CONTEXT.md`: **do not edit in this branch** while other task branches are active; mark it "pending integration" and list the facts (the real endpoints, the two caches, `MatesService`, `/mates`, `PartySplit` in `/profile`).
- Do not edit `docs/features.md` (it names unverified paths; report the corrections instead), ADRs, `docs/mvp-spec.md`, `docs/implementation-plan.md` or other specs.

## Out of scope
- Showing Discord users or mentions for teammates, links to their profiles, comparing two players, per-hero duo stats, AI summaries, premium gating.
- Changing existing client methods, `/heroes`, `/leaderboard`, `/analyze`.

## Acceptance criteria
- [ ] Step 0 recorded the real endpoints and field names; no field was invented
- [ ] `/mates` lists up to 5 teammates (at least 5 games together) with games and win rate together, over 90 days
- [ ] `/profile` shows solo vs party only when the data exists and never fails because of it
- [ ] Both new reads are cached (10 min, max 200 each) and covered by the exact-set cache test
- [ ] Not linked, no mates, busy source and unexpected errors have distinct messages; the unexpected one logs an error id
- [ ] No parallel or uncached repeated calls; no new dependency, migration or edit outside the table
- [ ] All user-facing text is in `messages.properties`; no field value exceeds 1024 characters
- [ ] `./mvnw clean verify` passes; test count and baseline difference are in the report
- [ ] Task report created; `docs/PROJECT_CONTEXT.md` update marked pending integration; final diff has no unrelated files

## Decisions and open questions
Decisions taken in this spec: 90-day window and a minimum of 5 games together (thresholds can change in review); party stats are merged into solo (size 1) versus party (2 and above); a party-stats failure degrades the profile silently (the profile is cached 10 minutes without the line in that case).

Open for the owner (not blocking): whether to show teammates who are also linked in Rejuv as Discord mentions (privacy and consent); whether to add a per-hero duo view later.
