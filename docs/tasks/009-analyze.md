# Task 009: `/analyze` and `match_analysis` persistence

- **Branch**: `feature/009-analyze`
- **Implementer**: Antigravity
- **Related ADRs**: ADR-0011 (single-match analysis, one stored analysis per account and match, non-AI fallback), ADR-0003 (Claude Haiku 5.5), ADR-0010 and ADR-0004 (deadlock-api), ADR-0002 (PostgreSQL, Caffeine), ADR-0008 (English, i18n-ready)

## Goal
Add `/analyze`: a public slash command that gives the caller an AI coaching report on their latest match, compared with players of the same hero and rank tier. It wires the pieces that already exist (match history, `MatchMetadataSource`, benchmarks, `MetricsService`, `AnalysisClient`) and stores each AI report in a new `match_analysis` table so the same match is never paid for twice. If the match metadata is unavailable it analyzes with history data only; if the AI fails it shows the key numbers without AI text. `/unlink` also deletes the stored analyses (`docs/mvp-spec.md`, "Data stored").

## Context (verified in the repository when this spec was written, 2026-10-17)
Do not repeat this discovery. If a fact below is false in your checkout, stop and ask.

- Dependencies 005, 007, 008 and 022 are merged in `main` (`docs/PROJECT_CONTEXT.md`). Start the branch from the latest `origin/main`. The baseline `./mvnw clean verify` has 344 tests (2 skipped benchmark tests); measure it before your first change.
- **Parallel work.** Task 024 (rich presentation) and two other tasks (014 `/heroes`, 016 `/leaderboard server`) are being implemented at the same time. Therefore: **do not use or create** `EmbedStyle`, `IconCatalog`, `AssetImages`; use a plain `EmbedBuilder` like `ProfileCommand` does; do not touch `HelpCommand`, `help.body`, `HelpCommandTest`, `MessagesIntegrationTest`, `CacheConfig`, `DeadlockApiClient`, `JdaConfig`, `LinkedAccountRepository`. Task 024 (or a small follow-up) restyles this command afterwards.
- Existing pieces you will call (real signatures):
  - `DeadlockApiClient.getMatchHistory(long accountId)` (`List<MatchHistoryEntry>`, newest first, max 200, cached 2 min, empty = no data), `getPlayerRank(long)` (`PlayerRank(badge, rank, subrank)`, cached 10 min), `getBenchmarks(int heroId, int rankTier)` (`Map<String, MetricPercentiles>`, tier 0..11, cached 6 h), `getItemStats(int heroId, int rankTier)` (`Map<Long, ItemStat>`, cached 6 h), `getHeroes()`, `getItems()`, `getRanks()` (cached 24 h). All throw `DeadlockApiUnavailableException` (503/429/I/O/timeout) or `DeadlockApiException`. **`DeadlockApiUnavailableException` and `MatchMetadataNotFoundException` both extend `DeadlockApiException`.**
  - `clients/MatchMetadataSource.getMatch(long matchId)` -> `MatchTelemetry` (throws `MatchMetadataNotFoundException`, `DeadlockApiUnavailableException`, `DeadlockApiException`); the only bean is `JsonMatchMetadataSource` (cached 24 h).
  - `services/MetricsService` (pure, `@Service`): `latestMatch(List<MatchHistoryEntry>)` -> `Optional<MatchHistoryEntry>`; `benchmarkRank(MatchHistoryEntry, PlayerRank)` -> `Optional<MatchRank>`; `buildInput(MetricsRequest)` -> `AnalysisInput`. `MetricsRequest(long accountId, MatchHistoryEntry match, MatchTelemetry telemetry /*null = degraded*/, MatchRank rank /*null = no benchmarks*/, Map<String, MetricPercentiles> benchmarks, Map<Long, ItemStat> itemStats, List<ItemAsset> items, List<HeroAsset> heroes, List<RankAsset> ranks)`; when `rank` is null, `benchmarks` and `itemStats` must be empty. `buildInput` throws `IllegalArgumentException` if `matchDurationS <= 0`. `AnalysisInput(String hero, String rank /*nullable*/, String result /*"win"|"loss"*/, double durationMin, Map<String, MetricComparison> metrics /*insertion-ordered*/, List<ItemTiming> items, String language)`; `MetricComparison(double value, Double p25, Double p50, Double p75)`. Hero name falls back to `hero_<id>` when unknown.
  - `clients/AnalysisClient.analyze(AnalysisInput)` -> `AnalysisOutcome(AnalysisResult result /*null = fallback*/, String model, int attempts, int inputTokens, int outputTokens)`; **never throws** (blank API key returns a fallback outcome). `AnalysisResult(String strength, String biggestLeak, String nextGameAction, List<String> keyNumbers)` is a Jackson record (`biggest_leak`, `next_game_action`, `key_numbers`).
  - `services/ProfileService.findLinkedAccountId(long discordUserId)` -> `OptionalLong` (database only). Reuse it for the "is the caller linked" check; do not duplicate the repository lookup.
  - `services/LinkService.unlink(long discordUserId)` (returns whether a row was deleted) and `repositories/LinkedAccountRepository` (`JpaRepository<LinkedAccount, Long>`, key = Discord user id). The same account may be linked by several Discord users.
  - `commands/CommandSupport.handleUnexpectedError(hook, messages, log, title, callerId, context, ex)` and `CommandSupport.oneDecimal(double)`. `ProfileCommand` is the model for deferral and error mapping: check the link first (ephemeral reply, no deferral), then `deferReply()`, then own `try/catch` (the router cannot answer after a deferral): `NoMatchHistoryException`, `DeadlockApiUnavailableException` (catch before the broader types), `RuntimeException`.
  - Flyway: only `V1__create_linked_account.sql` exists. The next migration is **V2**. `application.yaml` has `ddl-auto: validate`. Repository tests use `@SpringBootTest(properties = {"bot.token=", "bot.dev-guild-id="})` and `@Import(TestcontainersConfiguration.class)` (see `LinkedAccountRepositoryTest`).
- Rules from `AGENTS.md`: sequential API calls (never parallel uncached analytics requests), no JDA types in `services/`, no business logic in `commands/`, all user-facing text in `messages.properties` (messages with arguments contain no single apostrophe), constructor injection, no Lombok, no new dependency, never log tokens/keys.

## Files to create or modify
| File | Change |
|---|---|
| `src/main/resources/db/migration/V2__create_match_analysis.sql` | **Create.** Table `match_analysis` (see Interfaces). |
| `src/main/java/sntiago05/rejuv/domain/MatchAnalysis.java` | **Create.** JPA entity. |
| `src/main/java/sntiago05/rejuv/domain/AnalysisReport.java` | **Create.** Record returned by the service. |
| `src/main/java/sntiago05/rejuv/repositories/MatchAnalysisRepository.java` | **Create.** |
| `src/main/java/sntiago05/rejuv/services/AnalyzeService.java` | **Create.** |
| `src/main/java/sntiago05/rejuv/services/LinkService.java` | **Modify.** `unlink` also removes the stored analyses (see Behavior). Constructor gains the new repository. |
| `src/main/java/sntiago05/rejuv/commands/AnalyzeCommand.java` | **Create.** |
| `src/main/resources/messages.properties` | **Modify.** Add the `analyze.*` block **right after the `unlink.not_linked` line**. Change nothing else. |
| `src/test/resources/fixtures/analysis/` | **Create only if needed** for a stored `result_json` sample. |
| `src/test/java/sntiago05/rejuv/repositories/MatchAnalysisRepositoryTest.java` | **Create.** |
| `src/test/java/sntiago05/rejuv/services/AnalyzeServiceTest.java` | **Create.** |
| `src/test/java/sntiago05/rejuv/services/LinkServiceTest.java` | **Modify.** Constructor change and the new unlink cases only. |
| `src/test/java/sntiago05/rejuv/commands/AnalyzeCommandTest.java` | **Create.** Also asserts the command name is `analyze` and every `analyze.*` key resolves (this replaces edits to `MessagesIntegrationTest`). |
| `docs/task-reports/009-analyze.md` | **Create.** Task report (format of `docs/task-reports/013-meta.md`). |

No `pom.xml`, `application.yaml`, `CacheConfig` or `DeadlockApiClient` change: nothing here needs a new API call or cache. If you think one is needed, stop and ask.

## Interfaces / signatures

### Migration `V2__create_match_analysis.sql`
```sql
CREATE TABLE match_analysis (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    account_id    BIGINT       NOT NULL,
    match_id      BIGINT       NOT NULL,
    hero_id       INTEGER      NOT NULL,
    result_json   TEXT         NOT NULL,
    model         VARCHAR(100) NOT NULL,
    input_tokens  INTEGER      NOT NULL,
    output_tokens INTEGER      NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_match_analysis_account_match UNIQUE (account_id, match_id)
);
```
Do not edit V1.

### `domain/MatchAnalysis` (new JPA entity, table `match_analysis`)
Fields `Long id` (`GenerationType.IDENTITY`), `long accountId`, `long matchId`, `int heroId`, `String resultJson`, `String model`, `int inputTokens`, `int outputTokens`, `Instant createdAt`; protected no-arg constructor for JPA, a public constructor with all fields except `id`, getters, no setters (same style as `LinkedAccount`).

### `repositories/MatchAnalysisRepository` (new)
```java
public interface MatchAnalysisRepository extends JpaRepository<MatchAnalysis, Long> {

    Optional<MatchAnalysis> findByAccountIdAndMatchId(long accountId, long matchId);

    /** Deletes the analyses of the account unless some linked_account row still references the account. Returns the deleted row count. */
    @Modifying
    @Query("delete from MatchAnalysis a where a.accountId = :accountId "
            + "and not exists (select 1 from LinkedAccount l where l.accountId = :accountId)")
    int deleteByAccountIdIfNotLinked(@Param("accountId") long accountId);
}
```
(Same signature; adjust only the annotation details if Spring Data requires it, and say so in the report.)

### `domain/AnalysisReport` (new record)
```java
public record AnalysisReport(String hero, String rank /*nullable*/, String result /*"win"|"loss"*/,
                             double durationMin, AnalysisResult analysis /*null = AI unavailable*/,
                             Map<String, AnalysisInput.MetricComparison> metrics /*non-empty only when analysis is null*/,
                             boolean degraded /*match metadata was unavailable*/) { }
```
`hero`, `rank`, `result`, `durationMin` come from an `AnalysisInput` (fresh or header-only, below). Copy `metrics` with `Map.copyOf`-like immutability but **keep insertion order** (use `Collections.unmodifiableMap(new LinkedHashMap<>(...))`).

### `services/AnalyzeService` (new `@Service`)
```java
public AnalyzeService(DeadlockApiClient deadlockApiClient, MatchMetadataSource matchMetadataSource,
                      MetricsService metricsService, AnalysisClient analysisClient,
                      MatchAnalysisRepository repository)
/**
 * @throws NoMatchHistoryException when the account has no match history
 * @throws sntiago05.rejuv.clients.DeadlockApiException (including DeadlockApiUnavailableException) when history,
 *         rank, benchmarks or assets cannot be read
 */
public AnalysisReport analyze(long accountId)
```
Use a private static `tools.jackson.databind.json.JsonMapper` for `AnalysisResult` <-> `result_json` (same library as `AnalysisClient`; do not add a bean).

## Behavior

### `AnalyzeService.analyze(accountId)` (all calls sequential, no `@Transactional` around the HTTP calls)
1. `history = getMatchHistory(accountId)`; empty -> `NoMatchHistoryException(accountId)`. `match = metricsService.latestMatch(history)` (present because the list is non-empty).
2. `currentRank = getPlayerRank(accountId)`; `rank = metricsService.benchmarkRank(match, currentRank)` (may be empty); `heroes = getHeroes()`, `ranks = getRanks()`.
3. **Stored analysis:** `repository.findByAccountIdAndMatchId(accountId, match.matchId())`. If present, **no AI call and no further API call**: build a header-only input with `metricsService.buildInput(new MetricsRequest(accountId, match, null, rank.orElse(null), Map.of(), Map.of(), List.of(), heroes, ranks))`, parse `result_json` into `AnalysisResult`, and return `AnalysisReport(input.hero(), input.rank(), input.result(), input.durationMin(), analysis, Map.of(), false)`. If `result_json` cannot be parsed, log `WARN` (account and match id only), delete that row, and continue as if it did not exist.
4. **Match metadata (degradable):** `telemetry = matchMetadataSource.getMatch(match.matchId())`. On any `DeadlockApiException` (this includes not found and unavailable) log `WARN` (account id, match id, exception class; no stack trace) and continue with `telemetry = null`, `degraded = true`.
5. **Benchmarks (required when a rank exists):** if `rank` is present, `benchmarks = getBenchmarks(match.heroId(), rank.tier())` (an exception propagates). If `telemetry != null` and rank is present, `itemStats = getItemStats(match.heroId(), rank.tier())` and `items = getItems()`; if that call fails with `DeadlockApiException`, log `WARN` and use empty `itemStats` (items are then sent without a benchmark). If `rank` is empty or `telemetry == null`, `itemStats` is empty and `items` is an empty list. If `rank` is empty, `benchmarks` is empty (the analysis then has no comparison; this is the unranked case).
6. `input = metricsService.buildInput(new MetricsRequest(...))`; `outcome = analysisClient.analyze(input)`.
7. If `outcome.isFallback()`: do **not** store; return `AnalysisReport(..., analysis = null, metrics = input.metrics(), degraded)`. (A later `/analyze` retries the AI.)
8. Otherwise store `new MatchAnalysis(accountId, match.matchId(), match.heroId(), json, outcome.model(), outcome.inputTokens(), outcome.outputTokens(), Instant.now())` with `repository.save`. If `save` throws `DataIntegrityViolationException` (the same user ran `/analyze` twice at once and the unique constraint fired), re-read the stored row and return that report as in step 3. Return `AnalysisReport(..., outcome.result(), Map.of(), degraded)`.
9. One `INFO` line per call: account id, match id, `source=stored|ai|fallback`, `degraded`, tokens (never the result text, never any key).

### `LinkService.unlink(discordUserId)`
Becomes `@Transactional`. If the user has no link, return `false` without touching anything else. Otherwise read the linked `accountId`, delete the link, then call `matchAnalysisRepository.deleteByAccountIdIfNotLinked(accountId)` (so another Discord user still linked to the same account keeps the stored analyses), and return `true`. `link(...)` is unchanged (re-linking to a different account leaves the old account's analyses; recorded as a known limitation, out of scope).

### `/analyze` (`AnalyzeCommand`, name `analyze`, no options, public)
1. `accountId = profileService.findLinkedAccountId(callerId)`; empty -> ephemeral `analyze.not_linked` (no deferral).
2. `event.deferReply().queue()`, then call the service inside its own `try/catch`: `NoMatchHistoryException` -> edit with `analyze.no_matches`; `DeadlockApiUnavailableException` -> `error.data_source_busy`; `RuntimeException` -> `CommandSupport.handleUnexpectedError(hook, messages, log, "Analyze", callerId, "accountId=" + accountId, ex)`.
3. Success embed (`EmbedBuilder`, no color/thumbnail): 
   - title `analyze.title` (hero, `analyze.result.win` or `analyze.result.loss`, `CommandSupport.oneDecimal(durationMin)`);
   - description (only when needed): `analyze.degraded_note` when `degraded`, `analyze.fallback_note` when `analysis == null`, joined by a newline if both;
   - AI report (`analysis != null`): fields `analyze.field.strength`, `analyze.field.leak`, `analyze.field.next_action`, `analyze.field.key_numbers`; the key numbers are `keyNumbers` joined by `\n` (skip the field when the list is empty);
   - fallback (`analysis == null`): only the `analyze.field.key_numbers` field, built from `metrics`: the first 4 entries (in map order) whose `p50` is non-null, or the first 4 entries if none has a median; each line `analyze.fallback_number` (`{0}` label from `analyze.metric.<key>`, `{1}` value, `{2}` median) or `analyze.fallback_number_no_median` when `p50` is null. Numbers print without a trailing `.0` for whole values, otherwise one decimal;
   - footer: `analyze.footer` (hero, rank) when `rank != null`, else `analyze.footer_no_rank`.
   - Every field value is cut to at most 1024 characters (a private helper in the command that cuts at a word boundary and appends `...`); AI text is untrusted length-wise. No command may produce an embed over 6000 characters (the cuts guarantee it).
4. Metric labels: `analyze.metric.<key>` for the 12 keys of `MetricsService` (`souls_per_min`, `kills`, `deaths`, `assists`, `deaths_per_10_min`, `last_hits`, `denies`, `player_damage_per_min`, `net_worth_at_10_min`, `net_worth_at_20_min`, `permanent_buffs`, `first_permanent_buff_min`). An unknown key falls back to the raw key.

### User-facing text (`messages.properties`, English; block after `unlink.not_linked`)
Keys (write natural English text; arguments use `{0}`..; no single apostrophe in messages with arguments): `analyze.description`, `analyze.not_linked` (explain `/link`), `analyze.no_matches` (data may not be tracked yet; mention the deadlock-api ingest tool, like `profile.no_matches`), `analyze.title` (`{0}` hero, `{1}` result, `{2}` minutes), `analyze.result.win`, `analyze.result.loss`, `analyze.field.strength`, `analyze.field.leak`, `analyze.field.next_action`, `analyze.field.key_numbers`, `analyze.footer` (`{0}` hero, `{1}` rank; ends with "last 30 days"), `analyze.footer_no_rank`, `analyze.degraded_note`, `analyze.fallback_note`, `analyze.fallback_number` (`{0}` `{1}` `{2}`), `analyze.fallback_number_no_median`, and the 12 `analyze.metric.*` labels. Reuse `error.data_source_busy` and `error.generic`.

## Tests
JUnit 5, AssertJ, Mockito; `MessagesFixture.english()` for commands; Testcontainers for the repository (needs Docker). Use a real `MetricsService` in the service tests (it is pure); mock the clients.

1. **`MatchAnalysisRepositoryTest`:** V2 applies (table and unique constraint exist: saving a second row with the same account and match throws `DataIntegrityViolationException`); save/read back all fields; `findByAccountIdAndMatchId` hit and miss; `deleteByAccountIdIfNotLinked` deletes when no `linked_account` references the account, and deletes nothing when one does; it only deletes the given account.
2. **`AnalyzeServiceTest`:** (a) fresh AI path: stores one row with model and tokens, returns the AI result, calls were made in order (history, rank, heroes, ranks, metadata, benchmarks, item stats, items, AI) - verify with `InOrder` and that `analysisClient.analyze` receives an input whose `hero`/`result` match the match; (b) stored path: no `matchMetadataSource`, no benchmark, no AI call, report built from the stored JSON; (c) corrupted stored JSON: row deleted, flow continues as fresh; (d) metadata `MatchMetadataNotFoundException`, `DeadlockApiUnavailableException` and `DeadlockApiException` each give `degraded = true`, no `getItemStats`/`getItems` call, and the analysis still runs; (e) item-stats failure keeps the run non-degraded and sends items without benchmark; (f) unranked player (no `benchmarkRank`): no `getBenchmarks`/`getItemStats` call, report `rank == null`; (g) AI fallback: nothing stored, `analysis == null`, `metrics` equals the input metrics in order; (h) `DataIntegrityViolationException` on save returns the concurrently stored report; (i) empty history -> `NoMatchHistoryException`, no other call; (j) `getBenchmarks`/`getPlayerRank` throwing `DeadlockApiUnavailableException` propagates and nothing is stored; (k) the stored `result_json` round-trips through `AnalysisResult`.
3. **`LinkServiceTest`:** unlink with a link calls `deleteByAccountIdIfNotLinked(accountId)` after the link delete; unlink without a link returns `false` and does not call it; existing link/unlink cases keep passing.
4. **`AnalyzeCommandTest`:** name is `analyze`, no options; not linked -> ephemeral `analyze.not_linked`, no deferral, no service call; success with AI -> deferred (public), embed with the title, four fields, footer; degraded -> description has `analyze.degraded_note`; fallback -> description has `analyze.fallback_note`, only the key-numbers field with the chosen lines (with and without medians); unranked -> `analyze.footer_no_rank`; a 3000-character AI field is cut to <= 1024; `NoMatchHistoryException` -> `analyze.no_matches`; `DeadlockApiUnavailableException` -> `error.data_source_busy`; unexpected `RuntimeException` -> generic message with an error id and no rethrow; every `analyze.*` key listed above resolves to a non-blank text.
5. **Existing suites** (`FlywayMigrationTest`, `MessagesIntegrationTest`, `ProfileServiceCachingTest`, ...) keep passing unmodified; if one fails only because of V2, report it and ask before touching it.

## Verification
1. `./mvnw clean verify` passes with Docker running (Windows: `.\mvnw.cmd clean verify`; see the `MAVEN_USER_HOME` note in `docs/PROJECT_CONTEXT.md` if the wrapper fails). Report the real test count against the baseline you measured.
2. `git diff --check`; the final diff contains only the files in the table; no secret, no real user data; `.env` never read or printed.
3. Manual check in the dev guild (needs `DISCORD_TOKEN`, `DEV_GUILD_ID`, PostgreSQL, `ANTHROPIC_API_KEY`; **ask the owner before the first live AI call**, it costs about 2 COP): `/analyze` not linked; `/link` then `/analyze` (AI report); `/analyze` again (stored, instant, no new `match_analysis` row); check the row in PostgreSQL; `/unlink` and confirm the row is gone. If you do not run this check, say so in the report.

## Documentation
- Task report `docs/task-reports/009-analyze.md`: status, summary, files, commands run with real results, deviations, known issues, manual check result.
- `docs/PROJECT_CONTEXT.md`: **do not edit in this branch** while other task branches are active. Mark the update "pending integration" in the report and list the facts to add (V2 `match_analysis`, `AnalyzeService`/`AnalyzeCommand`, degraded and fallback behavior, the stored-analysis rule, `/unlink` deletion rule).
- Do not edit ADRs, `docs/mvp-spec.md`, `docs/architecture.md`, `docs/implementation-plan.md` or other specs.

## Out of scope
- Usage limits, feature flag, premium, Patreon (Tasks 019-020); web report pages (017); choosing a match other than the latest; `/compare`.
- Prompt or system-prompt changes (Task 010); changes to `AnalysisClient`, `MetricsService`, `JsonMatchMetadataSource` or any client method; the raw protobuf fallback (Task 023).
- Presentation work (colors, icons, thumbnails): Task 024 or a follow-up restyles the command.
- Moving the blocking API calls off the JDA event thread (known limitation shared with `/profile`).
- Deleting analyses when a user re-links to a different account.
- `/help` text: `help.body` already lists `/analyze`.

## Acceptance criteria
- [ ] V2 migration applies; `match_analysis` has the columns and the unique `(account_id, match_id)` constraint
- [ ] `/analyze` for a linked user replies with a public embed (title, strength, biggest leak, next action, key numbers, footer)
- [ ] A second `/analyze` for the same match makes no AI call and no metadata/benchmark call, and creates no new row
- [ ] Metadata failure gives a degraded analysis with the degraded note; AI failure gives the key numbers without AI text and stores nothing
- [ ] Unranked player: the analysis runs without benchmarks and the footer says so
- [ ] Not linked, no matches, busy source and unexpected errors have distinct messages; the unexpected one logs an error id
- [ ] `/unlink` removes the stored analyses of the account unless another user is still linked to it
- [ ] Calls to the API are sequential; no new cache, endpoint or dependency
- [ ] All user-facing text is in `messages.properties`; no field value exceeds 1024 characters
- [ ] `./mvnw clean verify` passes; test count and baseline difference are in the report
- [ ] Task report created; `docs/PROJECT_CONTEXT.md` update marked pending integration; final diff has no unrelated files

## Decisions and open questions
Decisions taken in this spec:
1. AI fallback results are **not** stored, so a retry can still get a real analysis (ADR-0011 says "stored and reused" for analyses; a fallback is not an analysis).
2. Metadata failures degrade; benchmark and rank failures do not (an analysis without comparison would mislead). Item-stats failure degrades only the item comparison.
3. Stored analyses are rebuilt into a report from the current history entry, so only the AI result is persisted; the title and footer are recomputed.
4. `AnalyzeCommand` reuses `ProfileService.findLinkedAccountId` to avoid duplicating the lookup; extracting a shared lookup is a later cleanup.

Open for the owner (not blocking):
1. A match with `matchDurationS <= 0` makes `buildInput` throw; it surfaces as the generic error with an id. Say if you want a dedicated message later.
2. The command blocks the JDA event thread during up to eight sequential API calls (history, rank, assets, metadata, benchmarks, item stats, items, AI); consider an executor in a later task.
