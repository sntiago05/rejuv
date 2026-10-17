# Task 025: `/matches` and match selection in `/analyze`

- **Branch**: `feature/025-matches`
- **Implementer**: Antigravity
- **Related ADRs**: ADR-0003 and ADR-0011 (AI only for interpretation, token discipline), ADR-0014 (free personal utilities), ADR-0008 (English, i18n-ready), ADR-0016 (icons and `EmbedStyle`)

## Goal
Let a player choose **which** match `/analyze` interprets, and find their matches without spending any AI token:
1. `/matches [hero] [result] [user]`: public embed listing the latest 10 matches (optionally filtered by hero and win/loss), marking the ones already analyzed.
2. `/analyze [match] [hero] [result]`: `match` has autocomplete (latest 10 matches, no API cost beyond the cached history), `hero` has autocomplete (hero names), `result` is a win/loss choice. With no option it analyzes the latest match, exactly as today.
3. Measure tokens: every fresh AI analysis logs an estimated cost, and a test proves one match is never paid twice whatever way it was selected.

## Context (verified from the repository when this spec was written, 2026-10-17)
Do not repeat this discovery. If a fact below is false in your checkout, stop and ask.

- Plan: new task, depends on 009, 014, 016 and 024 (all merged in `main`). Start the branch from the latest `origin/main`. Measure the `./mvnw clean verify` baseline (498 tests, 2 skipped) before your first change.
- **Conflicts with other specs.** Tasks 015 (`/mates`), 019 (usage limits) and 017 (web report) are specified at the same time. This task changes the signature of `AnalyzeService.analyze`, so **merge 025 before 019 and 017**. 015 does not touch these files. Trivial expected conflicts (keep both sides): `messages.properties`, `HelpCommand` text only through `help.body`.
- Real code you will use: `DeadlockApiClient.getMatchHistory(long accountId)` -> `List<MatchHistoryEntry>` (max 200, newest first, cached `deadlockMatchHistory` 2 min/1000, empty list = no data). `MatchHistoryEntry(matchId, heroId, heroLevel, startTime, matchDurationS, matchMode, matchResult, playerTeam, playerMatchOutcome, playerKills, playerDeaths, playerAssists, netWorth, lastHits, denies, rankedDisplayBadge)`; a win is `matchResult == playerTeam` (the rule used by `ProfileService`; `playerMatchOutcome` is 0 for old matches, do not use it). `services/MetricsService.latestMatch(history)` (newest by `startTime`). `services/AnalyzeService.analyze(long accountId)` returns `AnalysisReport(heroId, hero, rank, result, durationMin, analysis, metrics, degraded)`; it already stores one row per `(account_id, match_id)` in `match_analysis` and returns it without an AI call; `repositories/MatchAnalysisRepository.findByAccountIdAndMatchId`. `services/HeroResolver` (exact name ignoring case, then unique prefix), `services/GameAssets.heroNames()`, `services/IconCatalog`, `commands/EmbedStyle` (`COLOR`, `withIcon`, `fitField`), `commands/CommandSupport` (`handleUnexpectedError`, `oneDecimal`), `services/ProfileService.findLinkedAccountId(long)`, `commands/SlashCommand` (`name`, `data`, `handle`), `commands/CommandRouter` (a `ListenerAdapter` registering commands in the dev guild).
- `AnalysisClient` already sends only the compact metric summary (never raw API JSON), effort low, `max_tokens` 1500, and already logs input/output tokens per analysis. **This task does not change the prompt, the schema or `max_tokens`** (prompt tuning belongs to Task 010).
- Rules from `AGENTS.md`: sequential API calls, every client read cached, no JDA types in `services/`, no business logic in `commands/`, all user-facing text in `messages.properties` (messages with arguments contain no single apostrophe), no new dependency.

## Step 0: verify (before writing code)
1. Discord limits: autocomplete answers within 3 seconds with at most 25 choices, each choice name at most 100 characters; an `INTEGER` option accepts a value up to 2^53 (match ids are far smaller). Confirm with the JDA 5.6.1 API (`CommandAutoCompleteInteractionEvent`, `Command.Choice`, `OptionData.setAutoComplete`) using `javap` or the sources; record the signatures in the report.
2. Measure and record how long `getMatchHistory` takes cold and warm for a long history (the body is about 5 MB). If the cold call can exceed about 2 seconds, keep the design below (empty choices on any failure; the first keystroke warms the cache) and report the measurement. If you cannot measure (no network access), say so.

## Files to create or modify
| File | Change |
|---|---|
| `src/main/java/sntiago05/rejuv/domain/MatchFilter.java` | **Create.** Record. |
| `src/main/java/sntiago05/rejuv/domain/MatchSummary.java` | **Create.** Record. |
| `src/main/java/sntiago05/rejuv/services/MatchSearchService.java` | **Create.** |
| `src/main/java/sntiago05/rejuv/services/MatchNotFoundException.java` | **Create.** Runtime exception (no data class beyond a message). |
| `src/main/java/sntiago05/rejuv/services/AnalyzeService.java` | **Modify.** `analyze(long accountId)` becomes `analyze(long accountId, MatchFilter filter)`; match choice delegated to `MatchSearchService`; cost log. Nothing else. |
| `src/main/java/sntiago05/rejuv/repositories/MatchAnalysisRepository.java` | **Modify.** Add one query (see signatures). |
| `src/main/java/sntiago05/rejuv/config/AnthropicPricingProperties.java` | **Create.** Separate properties record (see signatures); `AnthropicProperties` is **not** changed because its canonical constructor is used by existing tests. |
| `src/main/java/sntiago05/rejuv/config/AnthropicConfig.java` | **Modify.** Only add `AnthropicPricingProperties.class` to `@EnableConfigurationProperties`. |
| `src/main/resources/application.yaml` | **Modify.** Add `anthropic.pricing.*` keys with default `0`. Nothing else. |
| `src/main/java/sntiago05/rejuv/commands/SlashCommand.java` | **Modify.** Add a default method `autocomplete`. |
| `src/main/java/sntiago05/rejuv/commands/CommandRouter.java` | **Modify.** Route autocomplete events. |
| `src/main/java/sntiago05/rejuv/commands/AnalyzeCommand.java` | **Modify.** New options, autocomplete, filter handling, `match_not_found` reply. |
| `src/main/java/sntiago05/rejuv/commands/MatchesCommand.java` | **Create.** |
| `src/main/resources/messages.properties` | **Modify.** Add the `matches.*` block and the new `analyze.*` keys at the **end of the file**, and add the two `/matches` and `/analyze` lines to `help.body` (only those lines). |
| `src/test/java/sntiago05/rejuv/services/MatchSearchServiceTest.java` | **Create.** |
| `src/test/java/sntiago05/rejuv/services/AnalyzeServiceTest.java` | **Modify.** Adapt to the new signature; add selection and no-double-payment tests. |
| `src/test/java/sntiago05/rejuv/repositories/MatchAnalysisRepositoryTest.java` | **Modify.** Add a test for the new query. |
| `src/test/java/sntiago05/rejuv/commands/AnalyzeCommandTest.java` | **Modify.** New options, autocomplete, not-found. |
| `src/test/java/sntiago05/rejuv/commands/MatchesCommandTest.java` | **Create.** |
| `src/test/java/sntiago05/rejuv/commands/CommandRouterTest.java` | **Modify.** Autocomplete routing. |
| `src/test/java/sntiago05/rejuv/commands/HelpCommandTest.java` | **Modify.** Only the `contains(...)` list: add `/matches`. |
| `docs/task-reports/025-matches.md` | **Create.** Task report (format of `docs/task-reports/014-heroes.md`). |

No `pom.xml` change, no migration, no new entity. If you think one is needed, stop and ask.

## Interfaces / signatures

### Domain (new)
```java
/** All fields optional; null = no filter. win: true = victories only, false = defeats only. */
public record MatchFilter(Long matchId, Integer heroId, Boolean win) {
    public static final MatchFilter LATEST = new MatchFilter(null, null, null);
}

/** One match of the player's cached history. heroName is null when the hero id is not in the heroes asset. */
public record MatchSummary(long matchId, int heroId, String heroName, long startTimeEpochS, int durationS,
                           boolean win, int kills, int deaths, int assists) { }
```

### `services/MatchSearchService` (new `@Service`)
```java
static final int DEFAULT_LIMIT = 10;
public MatchSearchService(DeadlockApiClient deadlockApiClient, GameAssets gameAssets)
/** Newest first, at most limit entries. Empty history gives an empty list. Lets DeadlockApi exceptions propagate. */
public List<MatchSummary> find(long accountId, MatchFilter filter, int limit)
/** The newest match that passes the filter (a matchId filter picks exactly that match). */
public Optional<MatchHistoryEntry> select(long accountId, MatchFilter filter)
```
One pass over the history, no nested loops; hero names from `gameAssets.heroNames()`.

### `services/AnalyzeService`
```java
/** @throws NoMatchHistoryException when the history is empty; MatchNotFoundException when nothing passes the filter */
public AnalysisReport analyze(long accountId, MatchFilter filter)
```
Everything else in `analyze` is unchanged (stored analysis first, degraded mode, fallback not stored, one row per account and match). After a fresh AI analysis is stored, the existing `source=ai` INFO line gains `estimatedCostUsd=<value>` when pricing is configured, and `estimatedCostUsd=unknown` when it is not.

### `MatchAnalysisRepository` (new query)
```java
/** Ids among matchIds that already have a stored analysis for the account (one query). */
@Query("select m.matchId from MatchAnalysis m where m.accountId = :accountId and m.matchId in :matchIds")
List<Long> findAnalyzedMatchIds(@Param("accountId") long accountId, @Param("matchIds") Collection<Long> matchIds);
```
(Use the real property names of the entity; the snippet shows the intent.)

### `AnthropicPricingProperties` (new)
```java
@ConfigurationProperties("anthropic.pricing")
public record AnthropicPricingProperties(double inputUsdPerMillion, double outputUsdPerMillion) { }
```
Keys `anthropic.pricing.input-usd-per-million` and `anthropic.pricing.output-usd-per-million`, default `0` in `application.yaml`; `AnalyzeService` receives it through its constructor (adapt `AnalyzeServiceTest`). **Do not invent prices**: the owner fills them from the official Anthropic pricing page. When either is `0` the cost is logged as `unknown`. Cost = `inputTokens / 1e6 * input + outputTokens / 1e6 * output`.

### `SlashCommand` and `CommandRouter`
```java
default void autocomplete(CommandAutoCompleteInteractionEvent event) { }
```
`CommandRouter.onCommandAutoCompleteInteraction` finds the command by `event.getName()` and calls `autocomplete`; a `RuntimeException` is logged at WARN and answered with `event.replyChoices(List.of()).queue()` (never the generic error message, never rethrown). Unknown command: empty choices. Autocomplete events are **not** counted as the one INFO line per command.

## Behavior

### `/analyze [match] [hero] [result]`
- Options (all optional): `match` (`INTEGER`, autocomplete, description `analyze.option.match`), `hero` (`STRING`, autocomplete, `analyze.option.hero`), `result` (`STRING` with two fixed choices, names `analyze.result.choice.win` / `analyze.result.choice.loss`, values `win` / `loss`).
- Filter: if `match` is present it wins and `hero` and `result` are ignored (documented in `analyze.option.match`). Otherwise `hero` is resolved with `HeroResolver` (not found: ephemeral `analyze.hero_not_found` before any deferral, same wording as `build.hero_not_found`; `/build` and `/counter` reply after deferring, this command replies before because the check is local) and combined with `result`; with no option the filter is `MatchFilter.LATEST`.
- Same flow as today after that: not linked check before deferring, public `deferReply()`, service call in its own `try/catch`. New: `MatchNotFoundException` -> plain text `analyze.match_not_found`. The match id you pass may not belong to the player's history (typed by hand): the service answers `MatchNotFoundException`; **never** analyze a match that is not in the caller's own history.
- Autocomplete: `match` focused -> `MatchSearchService.find(accountId, filter built from the hero/result options already typed, 10)` -> choices named `analyze.autocomplete.match` (`{0}` hero name or `#<heroId>`, `{1}` Win/Loss, `{2}` date `yyyy-MM-dd` UTC, `{3}` `K/D/A`) with value = match id; at most 100 characters (truncate the hero name if needed). `hero` focused -> up to 25 hero names that contain the typed text (case-insensitive), from `gameAssets.heroNames()`. Not linked, empty history, or **any** failure -> empty choices (no ephemeral message, no exception). No AI call and no uncached request other than `getMatchHistory`.

### `/matches [hero] [result] [user]` (`MatchesCommand`, name `matches`, public)
- Options: `hero` (`STRING`, autocomplete), `result` (same two choices as above, keys `matches.result.choice.*`), `user` (`USER`, default the caller). Not linked: ephemeral `matches.not_linked_self` / `matches.not_linked_other` before deferring. Then public `deferReply()`.
- Calls `MatchSearchService.find(accountId, filter, 10)` and `MatchAnalysisRepository.findAnalyzedMatchIds` once. Empty history -> `matches.no_history`; filters with no result -> `matches.no_results`.
- Embed (`EmbedStyle.COLOR`, hero icon through `IconCatalog` when present, `EmbedStyle.fitField`): title `matches.title` (`{0}` target name); one line per match from `matches.entry` (`{0}` date as a Discord timestamp `<t:epoch:d>`, `{1}` hero (with icon) , `{2}` Victory/Defeat, `{3}` K/D/A, `{4}` duration mm:ss, `{5}` match id); a `matches.analyzed_marker` text appended to analyzed matches; footer `matches.footer` explains that `/analyze match:` takes the match, that analyzed matches are marked and cost nothing to open again. Busy source -> `error.data_source_busy`; unexpected -> `CommandSupport.handleUnexpectedError(..., "Matches", ...)`.
- Only the caller's own analyzed marks are meaningful: when `user` is another player the marker still reflects that player's stored analyses (it is public data already).

### User-facing text (English)
`matches.description`, `matches.option.hero`, `matches.option.result`, `matches.option.user`, `matches.result.choice.win`, `matches.result.choice.loss`, `matches.not_linked_self`, `matches.not_linked_other`, `matches.no_history`, `matches.no_results`, `matches.title`, `matches.entry`, `matches.analyzed_marker`, `matches.footer`; `analyze.option.match`, `analyze.option.hero`, `analyze.option.result`, `analyze.result.choice.win`, `analyze.result.choice.loss`, `analyze.hero_not_found`, `analyze.match_not_found`, `analyze.autocomplete.match`. Update `analyze.description` only if the current text no longer fits. Reuse `error.data_source_busy`, `error.generic`.

## Tests
JUnit 5, AssertJ, Mockito; `MessagesFixture.english()`; fixed `Clock` where time matters.
1. **`MatchSearchServiceTest`:** win rule (`matchResult == playerTeam`), filters by hero, by result, by both, by exact match id; newest first and the limit; exact match id not in history -> empty; hero name null for an unknown hero; empty history; exceptions propagate; a 200-entry history is processed once (no repeated scans).
2. **`AnalyzeServiceTest`:** latest (default) keeps today's behavior; hero/result filter picks the right match; `matchId` filter analyzes exactly that match; nothing passes -> `MatchNotFoundException`; **the same match selected three ways (default, by id, by filter) makes exactly one `AnalysisClient.analyze` call and one stored row**; `estimatedCostUsd` is `unknown` with zero pricing and the exact value with configured pricing.
3. **`MatchAnalysisRepositoryTest`:** `findAnalyzedMatchIds` returns only the account's own analyzed ids among the given ones.
4. **`AnalyzeCommandTest`:** options and choices exist; `match` wins over `hero`/`result`; unknown hero -> ephemeral before deferring; `MatchNotFoundException` -> `analyze.match_not_found`; autocomplete: match choices (names, values, length at most 100, at most 10), hero choices (at most 25), not linked -> empty, service failure -> empty, no service call that reaches the AI.
5. **`MatchesCommandTest`:** name and options; not linked self/other -> ephemeral, no deferral; success embed with entries, icon when present, analyzed marker only on analyzed ids; empty history and no results; busy source and unexpected error; every new `matches.*` and `analyze.*` key resolves to non-blank text.
6. **`CommandRouterTest`:** autocomplete routed to the right command; a throwing `autocomplete` answers empty choices and is not rethrown.

## Verification
1. Step 0 recorded in the report.
2. `./mvnw clean verify` passes with Docker running (Windows: `.\mvnw.cmd clean verify`; see the `MAVEN_USER_HOME` note in `docs/PROJECT_CONTEXT.md` if the wrapper fails). Report the real test count against the baseline.
3. `git diff --check`; the diff contains only the files in the table; `.env` never read or printed.
4. Manual check in the dev guild (needs `DISCORD_TOKEN`, `DEV_GUILD_ID`, PostgreSQL, `ANTHROPIC_API_KEY`): `/matches`, `/matches hero:<x> result:loss`, `/analyze` (latest), `/analyze match:` using the autocomplete, repeating the same match (instant, no second AI call), `/analyze hero:<x> result:loss`. If you do not run it, say so in the report.

## Documentation
- Task report `docs/task-reports/025-matches.md`: status, Step 0 findings, summary, files, commands run with real results, deviations, known issues.
- `docs/PROJECT_CONTEXT.md`: **do not edit in this branch** while other task branches are active; mark it "pending integration" in the report and list the facts to add (commands, `MatchSearchService`, the `analyze` signature change, autocomplete routing, the pricing keys).
- Do not edit `docs/features.md`, ADRs, `docs/mvp-spec.md`, `docs/implementation-plan.md` or other specs.

## Out of scope
- Any change to the prompt, the AI schema, `max_tokens`, `AnalysisClient`, `MetricsService` (token trimming and prompt tuning belong to Task 010).
- Usage limits and premium (Task 019, 020), the web report (Task 017), a persisted match history (the 200-entry cached history is the only source), analyzing matches older than that history.
- Autocomplete for other commands, pagination of `/matches` beyond 10, per-match detail pages.

## Acceptance criteria
- [ ] `/analyze` with no option behaves exactly as before; `match`, `hero` and `result` select the match as specified
- [ ] `/analyze` can never analyze a match outside the caller's own cached history
- [ ] Autocomplete never reaches the AI, answers at most 10 match or 25 hero choices, each name at most 100 characters, and answers empty choices on any problem
- [ ] `/matches` lists up to 10 matches, filters work, analyzed matches are marked, no AI call is made
- [ ] One match is analyzed by the AI at most once however it was selected (test proves it)
- [ ] Each fresh AI analysis logs `estimatedCostUsd` (`unknown` until the owner sets pricing); no invented prices
- [ ] Not linked, unknown hero, no match, busy source and unexpected errors have distinct messages
- [ ] No parallel or uncached repeated calls; no new dependency, migration or edit outside the table
- [ ] All user-facing text is in `messages.properties`; field values are at most 1024 characters
- [ ] `./mvnw clean verify` passes; test count and baseline difference are in the report
- [ ] Task report created; `docs/PROJECT_CONTEXT.md` update marked pending integration; final diff has no unrelated files

## Decisions and open questions
Decisions taken in this spec (owner, 2026-10-17):
1. The command to find matches is `/matches`; `/analyze` gets `match` (autocomplete), `hero` and `result`; `match` wins over the filters; 10 results everywhere.
2. Token work in this task is measuring and reuse only: no prompt or output changes.
3. Autocomplete uses only the cached history and never a database query, so it stays fast.

Open for the owner (not blocking): the Anthropic prices for the cost log (fill `anthropic.pricing.*` from the official pricing page); whether the web report (Task 017) should also link from `/matches`.
