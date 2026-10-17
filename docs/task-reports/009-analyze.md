# Task 009: `/analyze` and `match_analysis` persistence

## Status

Implemented and verified locally. `.\mvnw.cmd clean verify` passes with 376 tests (344 baseline + 32 new, 2 skipped benchmark tests). Not pushed, not merged.

## Summary

- Created Flyway migration `V2__create_match_analysis.sql` creating table `match_analysis` with columns `id`, `account_id`, `match_id`, `hero_id`, `result_json`, `model`, `input_tokens`, `output_tokens`, `created_at`, and unique constraint `uq_match_analysis_account_match (account_id, match_id)`.
- Created domain JPA entity `domain/MatchAnalysis` and record `domain/AnalysisReport`.
- Created Spring Data repository `repositories/MatchAnalysisRepository` with `findByAccountIdAndMatchId` and `@Modifying @Query deleteByAccountIdIfNotLinked`.
- Created `services/AnalyzeService`:
  - Sequential API calls without `@Transactional` around external HTTP calls: match history -> latest match -> player rank -> benchmark rank -> heroes/ranks assets.
  - Stored analysis cache check: if a stored row exists in `match_analysis` and `result_json` parses, returns stored report immediately with no AI or benchmark call; if corrupted, deletes row and continues as fresh.
  - Degradable match metadata: attempts `MatchMetadataSource.getMatch`; on any `DeadlockApiException`, logs WARN and continues in degraded mode (`telemetry = null`, `degraded = true`).
  - Benchmarks: if rank exists, fetches benchmarks (propagating unavailable/failure); if metadata is present, fetches item stats (falling back to empty item stats on failure) and items.
  - AI analysis: calls `AnalysisClient.analyze(input)`; if fallback outcome, does not store and returns report with input metrics; otherwise serializes `AnalysisResult` to JSON and saves `MatchAnalysis` (handling concurrent save unique constraint conflicts).
  - SLF4J INFO log per analysis with account id, match id, source (stored/ai/fallback), degraded, and tokens.
- Modified `services/LinkService`:
  - Injected `MatchAnalysisRepository`.
  - Annotated `unlink(long discordUserId)` with `@Transactional`. When unlinking, retrieves `accountId`, deletes link from `linked_account`, and calls `matchAnalysisRepository.deleteByAccountIdIfNotLinked(accountId)` so that other Discord users linked to the same account retain stored analyses.
- Created `commands/AnalyzeCommand`:
  - Slash command `/analyze` without options.
  - Ephemeral reply with `analyze.not_linked` if caller has no linked Deadlock account (without deferring).
  - Public `deferReply()`, error mapping for `NoMatchHistoryException` (`analyze.no_matches`), `DeadlockApiUnavailableException` (`error.data_source_busy`), and unexpected `RuntimeException` (via `CommandSupport.handleUnexpectedError`).
  - Formats embed with title, optional description note (`analyze.degraded_note`, `analyze.fallback_note`, or both), AI coaching fields (strength, leak, next action, key numbers) or non-AI fallback key numbers (up to 4 metrics formatted with or without medians), and footer (with or without rank).
  - Cuts field values at word boundaries to at most 1024 characters.
- Modified `messages.properties`:
  - Added `analyze.*` message block right after `unlink.not_linked` with natural English text, without unescaped single quotes.

## Important Files

- `src/main/resources/db/migration/V2__create_match_analysis.sql` (create)
- `src/main/java/sntiago05/rejuv/domain/MatchAnalysis.java` (create)
- `src/main/java/sntiago05/rejuv/domain/AnalysisReport.java` (create)
- `src/main/java/sntiago05/rejuv/repositories/MatchAnalysisRepository.java` (create)
- `src/main/java/sntiago05/rejuv/services/AnalyzeService.java` (create)
- `src/main/java/sntiago05/rejuv/services/LinkService.java` (modify)
- `src/main/java/sntiago05/rejuv/commands/AnalyzeCommand.java` (create)
- `src/main/resources/messages.properties` (modify)
- `src/test/java/sntiago05/rejuv/repositories/MatchAnalysisRepositoryTest.java` (create: 6 tests)
- `src/test/java/sntiago05/rejuv/services/AnalyzeServiceTest.java` (create: 14 tests)
- `src/test/java/sntiago05/rejuv/services/LinkServiceTest.java` (modify: 7 tests)
- `src/test/java/sntiago05/rejuv/commands/AnalyzeCommandTest.java` (create: 12 tests)
- `docs/task-reports/009-analyze.md` (create)

## Decisions

- Fallback results from `AnalysisClient` are never persisted to `match_analysis`, ensuring that future executions can retry and obtain a genuine AI analysis.
- Stored analyses re-read current history and asset headers to construct `AnalysisReport`, avoiding redundant storage of display metadata while persisting the AI coaching result.
- Added `@Transactional` on `MatchAnalysisRepository.deleteByAccountIdIfNotLinked` interface method in addition to `LinkService.unlink`, ensuring standalone executions or tests outside explicit service transactions succeed without `TransactionRequiredException`.
- Used `Collections.unmodifiableMap(new LinkedHashMap<>(metrics))` in `AnalysisReport` to maintain deterministic insertion ordering for fallback metrics rendering.

## Verification

- **Baseline before changes**: `.\mvnw.cmd clean verify` exited 0 with **344 tests**, 0 failures, 2 skipped (benchmark tests).
- **Narrow test runs**:
  - `.\mvnw.cmd test "-Dtest=LinkServiceTest,MatchAnalysisRepositoryTest"`: 13 tests, 0 failures (V2 Flyway migration and repository queries verified in Testcontainers PostgreSQL).
  - `.\mvnw.cmd test -Dtest=AnalyzeServiceTest`: 14 tests, 0 failures.
  - `.\mvnw.cmd test -Dtest=AnalyzeCommandTest`: 12 tests, 0 failures.
- **Full build**: `.\mvnw.cmd clean verify` exited 0 with **376 tests**, 0 failures, 0 errors, 2 skipped (+32 new tests).
- **Manual Discord check**: Not executed by the implementer (requires live bot credentials, dev guild, and owner approval for Anthropic API credits).
- **Diff check**: `git diff --check` clean; no secrets, tokens or real user IDs committed; `.env` was never read.

## Acceptance Criteria

| Criterion | Evidence |
|---|---|
| V2 migration applies; `match_analysis` has columns and unique `(account_id, match_id)` | `MatchAnalysisRepositoryTest.savingDuplicateAccountAndMatchThrowsDataIntegrityViolationException`, Flyway applied V2 |
| `/analyze` for a linked user replies with public embed (title, strength, leak, action, key numbers, footer) | `AnalyzeCommandTest.successWithAiRepliesWithPublicEmbed` |
| Second `/analyze` for same match makes no AI or benchmark call, no new row | `AnalyzeServiceTest.storedPathReturnsStoredAnalysisWithoutMetadataOrBenchmarksOrAiCall` |
| Metadata failure gives degraded analysis; AI failure gives key numbers without AI text and stores nothing | `AnalyzeServiceTest.metadataExceptionsDegradeRunAndSkipItemStatsAndItems`, `AnalyzeServiceTest.aiFallbackStoresNothingAndReturnsInputMetrics`, `AnalyzeCommandTest.degradedAnalysisShowsDegradedNoteInDescription`, `AnalyzeCommandTest.fallbackAnalysisShowsFallbackNoteAndKeyNumbersWithAndWithoutMedians` |
| Unranked player runs without benchmarks and footer indicates so | `AnalyzeServiceTest.unrankedPlayerSkipsBenchmarksAndItemStatsAndReportsNullRank`, `AnalyzeCommandTest.unrankedFooterWhenRankIsNull` |
| Not linked, no matches, busy source and unexpected errors have distinct messages; unexpected logs error id | `AnalyzeCommandTest.callerNotLinkedRepliesEphemerallyWithoutDeferring`, `noMatchHistoryExceptionEditsOriginalWithNoMatchesMessage`, `deadlockApiUnavailableExceptionEditsOriginalWithDataSourceBusy`, `unexpectedRuntimeExceptionEditsOriginalWithGenericErrorAndDoesNotRethrow` |
| `/unlink` removes stored analyses of account unless another user is linked | `MatchAnalysisRepositoryTest.deleteByAccountIdIfNotLinkedDeletesWhenNoLinkedAccountReferencesTheAccount`, `deleteByAccountIdIfNotLinkedDeletesNothingWhenLinkedAccountReferencesTheAccount`, `LinkServiceTest.unlinkDeletesExistingLinkAndRemovesAnalysesIfNotLinked` |
| Sequential API calls; no new cache, endpoint or dependency | InOrder verification in `AnalyzeServiceTest`, no changes to `pom.xml`, `application.yaml`, or `CacheConfig` |
| All user-facing text in `messages.properties`; no field value exceeds 1024 characters | `AnalyzeCommandTest.resolvesEveryAnalyzeKey`, `AnalyzeCommandTest.longAiFieldIsCutToAtMost1024Characters` |
| `./mvnw clean verify` passes; test count and baseline in report | 376 tests (344 baseline + 32 new, 2 skipped), exit code 0 |
| Task report created; `PROJECT_CONTEXT.md` update marked pending integration; clean diff | This report; `PROJECT_CONTEXT.md` deferred below; `git diff --check` clean |

## Deviations

None. Implemented exactly according to `docs/tasks/009-analyze.md`.

## Known Issues

- Command execution blocks the JDA event thread during sequential API calls (history, rank, assets, metadata, benchmarks, item stats, items, AI).
- If a match has duration <= 0, `MetricsService.buildInput` throws `IllegalArgumentException`, which surfaces as the generic unexpected error with an error ID.
- Re-linking to a different account currently leaves the old account's stored analyses (noted as out of scope in the task spec).

## PROJECT_CONTEXT update (pending integration)

Active unmerged task branch exists (`feature/024-rich-presentation`). As instructed by `AGENTS.md` and the task spec, `docs/PROJECT_CONTEXT.md` was not modified in this branch. The following verified facts should be added during integration:
- **Task 009** (`/analyze` and `match_analysis` persistence) done:
  - Table `match_analysis` (Flyway migration `V2__create_match_analysis.sql`) with unique constraint on `(account_id, match_id)`.
  - `services/AnalyzeService`: sequential match analysis pipeline with caching/persistence of AI coaching reports, degradable metadata handling, non-AI fallback metrics, and duplicate prevention.
  - `commands/AnalyzeCommand`: public `/analyze` slash command with formatted embeds, field truncation at 1024 chars, and distinct error messages.
  - `services/LinkService`: `@Transactional` unlink cleans up stored analyses via `deleteByAccountIdIfNotLinked`.
  - Test suite test count: 376 tests (344 baseline + 32 new).

## Merge Risks

- `src/main/resources/messages.properties` has new keys appended under the `unlink.not_linked` line. Other branches adding message keys should not conflict if placed in separate blocks.
