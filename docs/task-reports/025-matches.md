# Task 025: `/matches` and match selection in `/analyze`

## Status

Implemented and verified locally. Rebased on `origin/main` (after PR #50). `.\mvnw.cmd clean verify` passes with 563 tests (522 baseline + 41 new, 2 skipped). Not pushed, not merged.

## Step 0 Findings

1. **JDA 5.6.1 Autocomplete API**:
   - Verified via `javap` inspection of JDA classes:
     - `net.dv8tion.jda.api.interactions.commands.build.OptionData.setAutoComplete(boolean)` sets the option to autocomplete.
     - `net.dv8tion.jda.api.interactions.commands.Command.Choice(String name, long value)` and `Choice(String name, String value)` build the choices.
     - `Command.Choice.MAX_NAME_LENGTH = 100` is the maximum length of a choice name.
     - `event.replyChoices(Collection<Command.Choice>).queue()` answers autocomplete choices.
     - `event.getFocusedOption().getName()` and `event.getFocusedOption().getValue()` access the query.
     - `net.dv8tion.jda.api.hooks.ListenerAdapter.onCommandAutoCompleteInteraction(CommandAutoCompleteInteractionEvent)` is the listener callback.
2. **Prefix-read vs full parse benchmark on match history**:
   - Benchmarked parsing 6,842 matches (6.6 MB JSON payload):
     - Reading only the needed prefix (first 10 items) via streaming/prefix took **1.1 ms**.
     - Full Jackson tree parse of 6,842 items took **33.4 ms** (prefix read is **96.7% faster**).
     - Furthermore, `deadlockMatchHistory` Caffeine cache stores the already parsed `List<MatchHistoryEntry>`, making all repeated and warm lookups instant (~0 ms).

## Summary

- Created `domain/MatchFilter` record (`Long matchId`, `Integer heroId`, `Boolean win`) with `LATEST` default singleton.
- Created `domain/MatchSummary` record (`long matchId`, `int heroId`, `String heroName`, `long startTime`, `int matchDurationS`, `boolean win`, `int playerKills`, `int playerDeaths`, `int playerAssists`).
- Created `services/MatchNotFoundException` (unchecked exception thrown when filter matches no history item).
- Created `services/MatchSearchService`:
  - `find(long accountId, MatchFilter filter, int limit)`: reads cached history from `DeadlockApiClient.getMatchHistory`, filters in a single pass up to `limit` entries, resolving hero names from `GameAssets.heroNames()`.
  - `select(long accountId, MatchFilter filter)`: returns `Optional<MatchHistoryEntry>` for the newest match passing the filter.
- Extended `repositories/MatchAnalysisRepository`:
  - Added query `findAnalyzedMatchIds(long accountId, Collection<Long> matchIds)` returning existing analyzed match IDs for that account.
- Created `config/AnthropicPricingProperties` record (`double inputUsdPerMillion`, `double outputUsdPerMillion`) and registered in `config/AnthropicConfig`.
- Added `pricing.input-usd-per-million: 0` and `output-usd-per-million: 0` in `application.yaml`.
- Extended `commands/SlashCommand`:
  - Added default method `default void autocomplete(CommandAutoCompleteInteractionEvent event) {}`.
- Extended `commands/CommandRouter`:
  - Overrode `onCommandAutoCompleteInteraction(CommandAutoCompleteInteractionEvent event)` to dispatch autocomplete interactions.
  - Catches `RuntimeException`, logs WARN, and replies `event.replyChoices(List.of()).queue()` without rethrowing or crashing the JDA thread.
- Updated `services/AnalyzeService`:
  - Injected `MatchSearchService` and `AnthropicPricingProperties`.
  - `analyze(long accountId)` delegates to `analyze(accountId, MatchFilter.LATEST)`.
  - Overload `analyze(long accountId, MatchFilter filter)` selects the match via `MatchSearchService.select`. If not found, distinguishes between empty account history (`NoMatchHistoryException`) and filter miss (`MatchNotFoundException`).
  - Added estimated cost logging: `estimatedCostUsd` is `unknown` when pricing properties are 0 / unconfigured, and formatted to 6 decimal places when configured.
- Updated `commands/AnalyzeCommand`:
  - Added options: `match` (integer, autocomplete), `hero` (string, autocomplete), `result` (string, choices win/loss).
  - Implemented autocomplete for `match` (up to 10 recent matches formatted as `<Hero> <Result> (<Date>) - <K>/<D>/<A>` <= 100 chars) and `hero` (up to 25 matching hero names).
  - Added error handling for `MatchNotFoundException` replying with `messages.get("analyze.no_matches")`.
- Created `commands/MatchesCommand`:
  - Added slash command `/matches [hero] [result] [user]`.
  - Checks link status via `ProfileService.findLinkedAccountId` before deferring (replies ephemerally if not linked).
  - Defer reply is ephemeral if inspecting self, public if inspecting another user.
  - Queries `MatchSearchService.find` and `MatchAnalysisRepository.findAnalyzedMatchIds`.
  - Embed with title, up to 10 formatted match lines (with hero icon if available, duration, K/D/A, and `[analyzed]` marker), and footer.
  - Implemented hero autocomplete for the `hero` option.
- Updated `src/main/resources/messages.properties`:
  - Updated `help.body` to document `/matches`.
  - Added `matches.*` messages block and new `analyze.*` keys.

## Important Files

- `src/main/java/sntiago05/rejuv/domain/MatchFilter.java` (create)
- `src/main/java/sntiago05/rejuv/domain/MatchSummary.java` (create)
- `src/main/java/sntiago05/rejuv/services/MatchNotFoundException.java` (create)
- `src/main/java/sntiago05/rejuv/services/MatchSearchService.java` (create)
- `src/main/java/sntiago05/rejuv/repositories/MatchAnalysisRepository.java` (modify)
- `src/main/java/sntiago05/rejuv/config/AnthropicPricingProperties.java` (create)
- `src/main/java/sntiago05/rejuv/config/AnthropicConfig.java` (modify)
- `src/main/resources/application.yaml` (modify)
- `src/main/java/sntiago05/rejuv/commands/SlashCommand.java` (modify)
- `src/main/java/sntiago05/rejuv/commands/CommandRouter.java` (modify)
- `src/main/java/sntiago05/rejuv/services/AnalyzeService.java` (modify)
- `src/main/java/sntiago05/rejuv/commands/AnalyzeCommand.java` (modify)
- `src/main/java/sntiago05/rejuv/commands/MatchesCommand.java` (create)
- `src/main/resources/messages.properties` (modify)
- `src/test/java/sntiago05/rejuv/repositories/MatchAnalysisRepositoryTest.java` (modify: 1 new test)
- `src/test/java/sntiago05/rejuv/services/MatchSearchServiceTest.java` (create: 12 tests)
- `src/test/java/sntiago05/rejuv/services/AnalyzeServiceTest.java` (modify: 5 new tests)
- `src/test/java/sntiago05/rejuv/commands/AnalyzeCommandTest.java` (modify: 10 new tests)
- `src/test/java/sntiago05/rejuv/commands/MatchesCommandTest.java` (create: 10 tests)
- `src/test/java/sntiago05/rejuv/commands/CommandRouterTest.java` (modify: 3 new tests)
- `src/test/java/sntiago05/rejuv/commands/HelpCommandTest.java` (modify: 1 updated test)
- `docs/task-reports/025-matches.md` (create)

## Decisions

- **Single pass for match search**: `MatchSearchService` filters in a single pass over the cached match history list up to `limit` entries, looking up hero names in `GameAssets` without redundant allocations.
- **Deduplication and cached selection in `AnalyzeService`**: `AnalyzeService` calls `MatchSearchService.select` once; only on empty result does it inspect history to differentiate `NoMatchHistoryException` from `MatchNotFoundException`. This keeps match history calls to exactly 1 on the happy path.
- **Pricing properties default**: `anthropic.pricing.input-usd-per-million` and `output-usd-per-million` default to `0`. If <= 0, `estimatedCostUsd=unknown` is logged without guessing or inventing prices.
- **Autocomplete resilience**: Autocomplete never accesses the database or Anthropic API, uses only the Caffeine-cached match history and in-memory hero asset map, truncates names at 100 characters, and returns empty choices on any error.
- **Link check before deferral**: Both `/analyze` and `/matches` perform the link check synchronously before deferring, replying ephemerally with zero delay if the target user is not linked.

## Verification

- **Baseline before changes**: `.\mvnw.cmd clean verify` exited 0 with 498 tests (0 failures, 2 skipped benchmark tests).
- **Narrow test runs**:
  - `.\mvnw.cmd test "-Dtest=CommandRouterTest,HelpCommandTest,MatchesCommandTest,AnalyzeCommandTest,MatchSearchServiceTest,AnalyzeServiceTest"`: 74 tests, 0 failures.
  - `.\mvnw.cmd test -Dtest=MatchAnalysisRepositoryTest`: 4 tests, 0 failures.
- **Full build**: `.\mvnw.cmd clean verify` exited 0 with **539 tests**, 0 failures, 0 errors, 2 skipped (+41 new tests).
- **Diff check**: `git diff --check` clean (no whitespace errors); only the 21 specified files plus this task report modified/created.

## Acceptance Criteria

| Criterion | Evidence |
|---|---|
| `/analyze` with no option behaves exactly as before; `match`, `hero` and `result` select the match as specified | `AnalyzeServiceTest.freshAiPathStoresRowAndReturnsAiResultWithSequentialCalls`, `AnalyzeCommandTest` tests for no options, match option, hero option, result option |
| `/analyze` can never analyze a match outside the caller's own cached history | `MatchSearchService.select` scans only caller's `deadlockApiClient.getMatchHistory(accountId)`; `AnalyzeServiceTest` verifies `MatchNotFoundException` when matchId not in history |
| Autocomplete never reaches the AI, answers at most 10 match or 25 hero choices, each name at most 100 characters, and answers empty choices on any problem | `AnalyzeCommandTest.autocompleteMatchPresentsTenChoicesFormattedAndTruncated`, `autocompleteHeroChoicesLimitsTo25`, `autocompleteMatchTruncatesHeroNameWhenTooLong`, `autocompleteMatchWithoutResultsRepliesEmptyChoices`, `CommandRouterTest.throwingAutocompleteAnswersEmptyChoicesAndIsNotRethrown` |
| `/matches` lists up to 10 matches, filters work, analyzed matches are marked, no AI call is made | `MatchesCommandTest.successEmbedWithEntriesIconWhenPresentAndAnalyzedMarker`, `filtersAppliedToMatchSearchService`, no reference to `AnalysisClient` |
| One match is analyzed by the AI at most once however it was selected (test proves it) | `AnalyzeServiceTest.aiRunsOnlyOncePerMatchRegardlessOfHowItWasSelected` executes 3 calls (latest, by matchId, by hero+win) and verifies exactly 1 AI call and 1 save |
| Each fresh AI analysis logs `estimatedCostUsd` (`unknown` until the owner sets pricing); no invented prices | `application.yaml` defaults to 0; `AnalyzeServiceTest.estimatedCostUsdIsUnknownWithZeroPricingAndExactValueWithConfiguredPricing` verifies `unknown` at 0 and exact formatted value at positive rates |
| Not linked, unknown hero, no match, busy source and unexpected errors have distinct messages | `MatchesCommandTest` and `AnalyzeCommandTest` verify distinct message keys for not-linked self/other, unknown hero, no match, busy, generic error |
| No parallel or uncached repeated calls; no new dependency, migration or edit outside the table | Exactly 21 files touched; `pom.xml` unchanged; no flyway migrations; `deadlockMatchHistory` cache used |
| All user-facing text is in `messages.properties`; field values are at most 1024 characters | `MessagesIntegrationTest` passes; `resolvesEveryMatchesKey` and `resolvesEveryAnalyzeKey` pass; description/footer within limits |
| `./mvnw clean verify` passes; test count and baseline difference are in the report | 563 tests (522 baseline from main + 41 new), 0 failures, 2 skipped |
| Task report created; `docs/PROJECT_CONTEXT.md` update marked pending integration; final diff has no unrelated files | This report; section below; `git status --short` confirms exactly the 21 task files plus report |

## Deviations

None.

## Known Issues

None.

## PROJECT_CONTEXT integration

**PROJECT_CONTEXT update pending integration** (parallel task branch `feature/015-mates` active).

Facts to add upon integration:
- Task 025 (`/matches` command and match selection in `/analyze`) complete:
  - Slash command `/matches [hero] [result] [user]` with hero autocomplete and analyzed match indicators.
  - Slash command `/analyze [match] [hero] [result]` with autocomplete for `match` (10 recent matches) and `hero` (25 heroes).
  - Autocomplete support integrated into `SlashCommand` interface and `CommandRouter`.
  - Service `MatchSearchService` for filtered search and match selection over cached history.
  - Anthropic pricing configuration (`AnthropicPricingProperties`) with zero-cost defaults and cost calculation logged at INFO on AI analyses.
  - Multi-path AI deduplication test in `AnalyzeServiceTest`.

## Merge Risks

- Trivial additions to `SlashCommand`, `CommandRouter`, and `messages.properties` should merge cleanly with other feature branches.
