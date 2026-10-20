# Task 019: Feature flag and weekly usage limits for `/analyze`

> Superseded: the premium tier, Patreon check and usage limits were removed by task 051 (ADR-0019). This report is kept as history.

## Status

Implemented and verified locally. `.\mvnw.cmd clean verify` passes with 580 tests (563 baseline + 17 new, 0 failures, 2 skipped benchmark tests). Not pushed, not merged.

## Summary

- Created Flyway migration `src/main/resources/db/migration/V3__create_analysis_usage.sql` creating table `analysis_usage` (`id BIGSERIAL PRIMARY KEY`, `discord_user_id BIGINT NOT NULL`, `match_id BIGINT NOT NULL`, `created_at TIMESTAMPTZ NOT NULL DEFAULT now()`) and index `idx_analysis_usage_user_created` on `(discord_user_id, created_at)`.
- Created domain entity `domain/AnalysisUsage` with JPA mappings.
- Created repository `repositories/AnalysisUsageRepository` with derived count query method `countByDiscordUserIdAndCreatedAtGreaterThanEqual(long discordUserId, Instant createdAt)`.
- Created configuration properties record `config/UsageProperties` (`rejuv.usage`: `enabled`, `weeklyFreeAnalyses`) validating non-positive limits at construction.
- Created port interface `services/PremiumChecker` with method `isPremium(long discordUserId)`.
- Created configuration class `config/UsageConfig` enabling `UsageProperties` and registering a default `@ConditionalOnMissingBean(PremiumChecker.class)` bean returning `false` for everyone.
- Created record `domain/UsageStatus` (`limitsEnabled`, `premium`, `used`, `limit`, `resetsAt`) with `unlimited()`.
- Created exception `services/UsageLimitExceededException` carrying `resetsAt` and `limit`.
- Created service `services/UsageService` handling allowance verification (`checkAllowance`), recording analyses (`recordAnalysis`), and computing status (`status`). Calendar week window is Monday 00:00 UTC to next Monday 00:00 UTC using injected `Clock`.
- Modified `services/AnalyzeService`:
  - Updated signature to `analyze(long discordUserId, long accountId, MatchFilter filter)`.
  - Added `usageService.checkAllowance(discordUserId)` after stored-analysis check and before metadata, benchmark, and AI calls.
  - Added `usageService.recordAnalysis(discordUserId, match.matchId())` after successful AI outcome is saved to repository.
- Modified `commands/AnalyzeCommand` to pass `callerId` to `analyzeService.analyze` and catch `UsageLimitExceededException`, replying with `analyze.limit_reached` and the reset timestamp `<t:epoch:R>`.
- Created slash command `commands/UsageCommand` (`/usage`, ephemeral, no options) handling flag off (`usage.disabled`), premium (`usage.premium`), and active limit status (`usage.status`).
- Added configuration in `application.yaml` (`rejuv.usage` block with `${USAGE_LIMITS_ENABLED:false}` and `${USAGE_WEEKLY_FREE_ANALYSES:3}`) and `.env.example`.
- Added user-facing messages in `messages.properties` (`usage.description`, `usage.disabled`, `usage.premium`, `usage.status`, `analyze.limit_reached`) and added `/usage` to `help.body`.
- Added and updated comprehensive tests: `UsageServiceTest`, `UsageCommandTest`, `AnalysisUsageRepositoryTest`, `AnalyzeServiceTest`, `AnalyzeCommandTest`, and `HelpCommandTest`.

## Important Files

- `src/main/resources/db/migration/V3__create_analysis_usage.sql` (create)
- `src/main/java/sntiago05/rejuv/domain/AnalysisUsage.java` (create)
- `src/main/java/sntiago05/rejuv/repositories/AnalysisUsageRepository.java` (create)
- `src/main/java/sntiago05/rejuv/config/UsageProperties.java` (create)
- `src/main/java/sntiago05/rejuv/config/UsageConfig.java` (create)
- `src/main/java/sntiago05/rejuv/services/PremiumChecker.java` (create)
- `src/main/java/sntiago05/rejuv/domain/UsageStatus.java` (create)
- `src/main/java/sntiago05/rejuv/services/UsageLimitExceededException.java` (create)
- `src/main/java/sntiago05/rejuv/services/UsageService.java` (create)
- `src/main/java/sntiago05/rejuv/commands/UsageCommand.java` (create)
- `src/main/java/sntiago05/rejuv/services/AnalyzeService.java` (modify)
- `src/main/java/sntiago05/rejuv/commands/AnalyzeCommand.java` (modify)
- `src/main/resources/application.yaml` (modify)
- `.env.example` (modify)
- `src/main/resources/messages.properties` (modify)
- `src/test/java/sntiago05/rejuv/services/UsageServiceTest.java` (create: 7 tests)
- `src/test/java/sntiago05/rejuv/commands/UsageCommandTest.java` (create: 6 tests)
- `src/test/java/sntiago05/rejuv/repositories/AnalysisUsageRepositoryTest.java` (create: 2 tests)
- `src/test/java/sntiago05/rejuv/services/AnalyzeServiceTest.java` (modify: 1 new test, updated signature)
- `src/test/java/sntiago05/rejuv/commands/AnalyzeCommandTest.java` (modify: 1 new test, updated signature)
- `src/test/java/sntiago05/rejuv/commands/HelpCommandTest.java` (modify)
- `docs/task-reports/019-usage-limits.md` (create)

## Decisions

- **Weekly calendar window in UTC**: Monday 00:00:00 UTC to next Monday 00:00:00 UTC calculated via `TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)` and `plusWeeks(1)`. Sunday 23:59:59 UTC belongs to the previous week and resets 1 second later; Monday 00:00:00 UTC starts the fresh week and resets 7 days later.
- **Limit check placement**: Stored analyses return immediately without calling `checkAllowance`. `checkAllowance` runs before metadata, benchmarks, and AI calls so no third-party API quotas are consumed when a user is over the limit.
- **Usage recording**: Usage rows are recorded only after `repository.save` succeeds on fresh non-fallback AI analyses. Stored analyses, fallbacks, and concurrent duplicate saves record nothing. Premium users still have usage recorded when limits are enabled (for statistics). When limits are disabled (`enabled=false`), recording is a no-op.
- **Fail-fast properties validation**: `UsageProperties` compact constructor validates that `weeklyFreeAnalyses > 0`, failing startup immediately if configured with a non-positive integer.
- **PremiumChecker port**: Default bean in `UsageConfig` returns `false` using `@ConditionalOnMissingBean(PremiumChecker.class)` so Task 020 can provide the Patreon role check bean without modifying `UsageService`.

## Verification

- **Baseline before changes**: `.\mvnw.cmd verify` passed with **563 tests**, 0 failures, 2 skipped benchmark tests.
- **Narrow test runs**:
  - `.\mvnw.cmd test "-Dtest=UsageServiceTest,UsageCommandTest,HelpCommandTest"`: 18 tests, 0 failures.
  - `.\mvnw.cmd test "-Dtest=AnalyzeCommandTest,AnalyzeServiceTest"`: 43 tests, 0 failures.
  - `.\mvnw.cmd test "-Dtest=AnalysisUsageRepositoryTest"`: 2 tests, 0 failures (Testcontainers PostgreSQL 17, Flyway applied V1, V2, V3).
  - `.\mvnw.cmd test "-Dtest=MessagesIntegrationTest"`: 5 tests, 0 failures (catalog keys and command registrations validated).
- **Full build**: `.\mvnw.cmd clean verify` passed with **580 tests**, 0 failures, 0 errors, 2 skipped (+17 new tests over baseline).
- **Manual Discord check**: Not executed by the implementer (requires live bot token and Discord server setup).
- **Diff check**: `git diff --check` clean; `.env` was never read or committed; no secrets or tokens committed.

## Acceptance criteria

| Criterion | Evidence |
|---|---|
| With the flag off nothing changes and nothing is written | `UsageServiceTest.flagOffNeverThrowsAndNeverRecords`, `UsageCommandTest.flagOffRepliesWithDisabledMessageEphemerally` |
| With the flag on, only fresh AI analyses count; stored reopenings, fallbacks and degraded-without-AI results never do | `AnalyzeServiceTest.storedPathReturnsStoredAnalysisWithoutMetadataOrBenchmarksOrAiCall`, `AnalyzeServiceTest.aiFallbackStoresNothingAndReturnsInputMetrics`, `AnalyzeServiceTest.dataIntegrityViolationExceptionOnSaveReturnsConcurrentlyStoredReport`, `AnalyzeServiceTest.freshAiPathStoresRowAndReturnsAiResultWithSequentialCalls` |
| An over-limit user triggers no metadata, benchmark or AI call | `AnalyzeServiceTest.overLimitUserTriggersNoMetadataBenchmarkOrAiCallAndThrows` |
| The weekly window is Monday 00:00 UTC to Monday 00:00 UTC and the reset time shown is correct | `UsageServiceTest.weekBoundarySunday235959CountsInOldWeekAndMonday000000StartsNewOne`, `AnalysisUsageRepositoryTest.countCountsOnlyThatUserAndOnlyRowsInWindow` |
| `PremiumChecker` exists with a default "nobody" bean that Task 020 can replace without editing `UsageService` | `UsageConfig.defaultPremiumChecker`, `UsageServiceTest.premiumNeverThrowsAndRecordsWhenLimitsEnabled`, Spring context tests |
| `/usage` is ephemeral and makes no external call | `UsageCommandTest`, `UsageCommand` |
| V3 migration applies; no existing migration edited; no new dependency | `AnalysisUsageRepositoryTest` applied V3 migration on Postgres 17; `pom.xml` unchanged |
| All user-facing text is in `messages.properties` | `MessagesIntegrationTest.catalogFileIsWellFormed`, `UsageCommandTest.resolvesAllUsageKeysAndAnalyzeLimitReached`, `AnalyzeCommandTest.resolvesEveryAnalyzeKey` |
| `./mvnw clean verify` passes; test count and baseline difference are in the report | 580 tests run (baseline 563, +17 new tests), 0 failures, 2 skipped |
| Task report created; `docs/PROJECT_CONTEXT.md` update marked pending integration; final diff has no unrelated files | This report; section below; `git status --short` verified |

## Deviations

None. All files and interfaces matched the specification exactly.

## Known Issues

None.

## Merge Risks

Task 017 uses Flyway migration `V4` and must be merged after Task 019 (`V3`).

## PROJECT_CONTEXT update pending integration

Parallel unmerged feature branch `feature/023-raw-metadata-fallback` is active in the repository. Per project rules, `docs/PROJECT_CONTEXT.md` is not modified directly in this branch. The following verified facts are pending integration:
- **Migration V3**: `V3__create_analysis_usage.sql` added table `analysis_usage` and index `idx_analysis_usage_user_created`. Flyway schema is now at V3.
- **Configuration**: `rejuv.usage.enabled` (`USAGE_LIMITS_ENABLED`, default `false`) and `rejuv.usage.weekly-free-analyses` (`USAGE_WEEKLY_FREE_ANALYSES`, default `3`) added.
- **Usage Limits and /usage**: `UsageService` tracks fresh AI analyses per Discord user per calendar week (Monday 00:00 UTC to Monday 00:00 UTC). Slash command `/usage` displays remaining allowance and reset timestamp.
- **AnalyzeService signature**: `AnalyzeService.analyze` signature updated to `analyze(long discordUserId, long accountId, MatchFilter filter)`.
- **PremiumChecker port**: `PremiumChecker` interface introduced with default `@ConditionalOnMissingBean` returning `false` for all users, ready for Task 020 Patreon integration.
