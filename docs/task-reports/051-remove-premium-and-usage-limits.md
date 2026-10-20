# Task 051: Remove premium, Patreon and weekly usage limits

## Status
Implemented on branch `feature/051-remove-premium-usage-limits`. `env -u ANTHROPIC_BASE_URL -u ANTHROPIC_CUSTOM_HEADERS ./mvnw verify` passes (1038 tests run, 0 failures, 14 skipped). Not pushed, not merged.

## Summary
Task 051 establishes Rejuv as free for everyone by completely removing weekly usage limits, premium tier checks, Patreon role gating, and the `/usage` slash command.

1. **Freed `/items` and `/compare` commands**:
   - Removed `PremiumFeatureGate` from `ItemsCommand` and `CompareCommand`.
   - Any linked user can run `/items` and `/compare` without role checks or usage limit enforcement.

2. **Cleaned `/analyze` pipeline**:
   - Removed `UsageService` from `AnalyzeService`. Analyses never check allowance or record usage rows.
   - Removed `UsageLimitExceededException` handling from `AnalyzeCommand`.

3. **Removed `/usage` command & cleanup**:
   - Deleted `UsageCommand.java` and its unit tests.
   - The bot registers one command less. Dev guild slash command updates will automatically drop `/usage` on next startup. Note: global commands need no cleanup because `/usage` was never registered globally.
   - Updated `help.body` in `src/main/resources/messages.properties`: removed `/usage` line, and changed `💎 (Premium)` to `🎒` for `/items` and `🆚` for `/compare`.

4. **Dropped `analysis_usage` table (Flyway V6)**:
   - Added migration `V6__drop_analysis_usage.sql`: `DROP TABLE IF EXISTS analysis_usage;`.
   - **This deletes stored usage counts for good.** The table only stored weekly counter rows for limits; with limits eliminated, nothing reads or writes this table.
   - Existing migration `V3__create_analysis_usage.sql` remains completely untouched.
   - `FlywayMigrationTest` was extended to verify `V6` execution and assert that `analysis_usage` does not exist while `linked_account` and `match_analysis` remain intact.

5. **Deleted obsolete premium and usage classes**:
   - Main: `commands/UsageCommand.java`, `config/JdaPremiumRoleGateway.java`, `config/PremiumProperties.java`, `config/UsageConfig.java`, `config/UsageProperties.java`, `domain/AnalysisUsage.java`, `domain/UsageStatus.java`, `repositories/AnalysisUsageRepository.java`, `services/PatreonRolePremiumChecker.java`, `services/PremiumChecker.java`, `services/PremiumFeatureGate.java`, `services/PremiumLookupException.java`, `services/PremiumRoleGateway.java`, `services/UsageLimitExceededException.java`, `services/UsageService.java`.
   - Tests: `commands/UsageCommandTest.java`, `config/JdaPremiumRoleGatewayTest.java`, `config/PremiumPropertiesTest.java`, `repositories/AnalysisUsageRepositoryTest.java`, `services/PatreonRolePremiumCheckerTest.java`, `services/PremiumFeatureGateTest.java`, `services/UsageServiceTest.java`.

6. **Configuration & Documentation**:
   - `application.yaml`: removed `rejuv.usage` and `rejuv.premium` blocks (kept `rejuv.web`; did not touch donation config from task 050).
   - `.env.example`: removed `USAGE_LIMITS_ENABLED`, `USAGE_WEEKLY_FREE_ANALYSES`, `PREMIUM_ROLE_CHECK_ENABLED`, `PREMIUM_GUILD_ID`, `PREMIUM_ROLE_ID`.
   - `docs/manual-testing.md`: removed weekly limit sections, `/usage` instructions, Patreon/Premium setup, and `analysis_usage` SQL queries.
   - `docs/adr/0019-free-with-donations.md`: written following template.

## Files Created, Modified, or Deleted

| File | Change |
|---|---|
| `src/main/java/sntiago05/rejuv/services/AnalyzeService.java` | Removed `UsageService` parameter/field, calls, and exception javadoc. |
| `src/main/java/sntiago05/rejuv/commands/AnalyzeCommand.java` | Removed `UsageLimitExceededException` import and catch block. |
| `src/main/java/sntiago05/rejuv/commands/ItemsCommand.java` | Removed `PremiumFeatureGate` parameter/field, import, and gate check. |
| `src/main/java/sntiago05/rejuv/commands/CompareCommand.java` | Removed `PremiumFeatureGate` parameter/field, import, and gate check. |
| `src/main/resources/application.yaml` | Removed `rejuv.usage` and `rejuv.premium` sections. |
| `src/main/resources/messages.properties` | Removed usage/limit/premium keys; updated `help.body`. |
| `src/main/resources/db/migration/V6__drop_analysis_usage.sql` | New Flyway migration dropping `analysis_usage`. |
| `.env.example` | Removed usage and premium environment variables. |
| `docs/adr/0019-free-with-donations.md` | New ADR-0019 for free model with donations. |
| `docs/manual-testing.md` | Removed weekly usage limits, `/usage`, and premium testing sections. |
| `src/test/java/sntiago05/rejuv/FlywayMigrationTest.java` | Added assertions for `V6` and verification that `analysis_usage` is dropped. |
| `src/test/java/sntiago05/rejuv/commands/AnalyzeCommandTest.java` | Removed usage limit test and `analyze.limit_reached` key check. |
| `src/test/java/sntiago05/rejuv/commands/ItemsCommandTest.java` | Removed premium gate mocks and tests. |
| `src/test/java/sntiago05/rejuv/commands/CompareCommandTest.java` | Removed premium gate mocks and tests. |
| `src/test/java/sntiago05/rejuv/services/AnalyzeServiceTest.java` | Removed usage service mocks, verifications, and limit tests. |
| `src/test/java/sntiago05/rejuv/UpstreamCallCountBenchmarkTest.java` | Removed `UsageService` mock bean. |
| `src/test/java/sntiago05/rejuv/commands/HelpCommandTest.java` | Removed `"/usage"` assertion on `help.body` (approved deviation). |
| 22 deleted source/test files | Removed all 15 main and 7 test usage/premium classes. |
| `docs/task-reports/051-remove-premium-and-usage-limits.md` | This report. |

No other file was touched. Existing migrations, `pom.xml`, `JdaConfig`, and `docs/PROJECT_CONTEXT.md` were not edited.

## Final Grep Search
Executed command:
```bash
grep -rniE "premium|patreon|UsageService|UsageProperties|analysis_usage|/usage" src docs/manual-testing.md compose*.yaml
```
Output:
```
src/test/java/sntiago05/rejuv/FlywayMigrationTest.java:205:                  AND table_name IN ('analysis_usage', 'linked_account', 'match_analysis')
src/main/resources/db/migration/V3__create_analysis_usage.sql:1:CREATE TABLE analysis_usage (
src/main/resources/db/migration/V3__create_analysis_usage.sql:8:CREATE INDEX idx_analysis_usage_user_created ON analysis_usage (discord_user_id, created_at);
src/main/resources/db/migration/V6__drop_analysis_usage.sql:1:DROP TABLE IF EXISTS analysis_usage;
```
Note: As mandated by database conventions and task instructions, historical applied migration `V3__create_analysis_usage.sql` was preserved untouched. Only `V6__drop_analysis_usage.sql` and its verification in `FlywayMigrationTest` reference `analysis_usage`.

## Verification Commands & Results
- Migration tests:
  `env -u ANTHROPIC_BASE_URL -u ANTHROPIC_CUSTOM_HEADERS ./mvnw test -Dtest=FlywayMigrationTest`
  Result: 13 tests run, 0 failures, 0 errors, 0 skipped.
- Messages & Help command tests:
  `env -u ANTHROPIC_BASE_URL -u ANTHROPIC_CUSTOM_HEADERS ./mvnw test -Dtest=MessagesIntegrationTest,HelpCommandTest`
  Result: 10 tests run, 0 failures, 0 errors, 0 skipped.
- Full test suite:
  `env -u ANTHROPIC_BASE_URL -u ANTHROPIC_CUSTOM_HEADERS ./mvnw verify`
  Result: BUILD SUCCESS (1038 tests run, 0 failures, 0 errors, 14 skipped; total time 56.729 s).
  *Explanation of test count change:* Test count decreased from 1074 to 1038 (-36 net) due to deleting 38 tests across 7 deleted test classes (`UsageCommandTest`, `JdaPremiumRoleGatewayTest`, `PremiumPropertiesTest`, `AnalysisUsageRepositoryTest`, `PatreonRolePremiumCheckerTest`, `PremiumFeatureGateTest`, `UsageServiceTest`) and removed premium/usage test cases in `AnalyzeCommandTest`, `ItemsCommandTest`, `CompareCommandTest`, `AnalyzeServiceTest`, while adding 2 new tests in `FlywayMigrationTest`.

## Deviations
- `HelpCommandTest.java`: The spec noted `CommandRouterTest (only if it lists command names): remove usage`. In reality, `CommandRouterTest` does not list command names, but `HelpCommandTest.helpBodyCoversMvpTopics` explicitly asserted that `help.body` contains `"/usage"`. Per owner confirmation, line 78 of `HelpCommandTest.java` was edited to remove `"/usage"`.

## Canonical Docs Mentioning Premium or Patreon
The following documentation files still mention premium, Patreon, or usage limits and should be updated by the owner during documentation review:
- `docs/mvp-spec.md`
- `docs/features.md`
- `docs/product.md`
- `docs/costs.md`
- `docs/roadmap.md`
- `docs/architecture.md`
- `docs/implementation-plan.md`
- `docs/deployment.md`
- `docs/adr/0006-freemium-patreon-role.md` (superseded by ADR-0019)
- `docs/adr/0013-top-rank-comparison-premium.md` (superseded in practice by ADR-0019)
- Historical task reports: `docs/task-reports/019-usage-limits.md`, `docs/task-reports/020-patreon-role-check.md`, `docs/task-reports/021-items-premium.md`, `docs/task-reports/038-compare-premium.md`

## Pending Integration (for `docs/PROJECT_CONTEXT.md`)
Per worktree isolation rules, `docs/PROJECT_CONTEXT.md` was not modified directly. The following facts are pending integration:
- **Free model**: Rejuv runs fully free with no weekly limits and no premium tier; support model relies on optional donation link (task 050).
- **Commands**: `/usage` command deleted (no global cleanup needed); `/items` and `/compare` are open to all linked accounts.
- **Database**: Flyway `V6` drops table `analysis_usage`.
- **Decisions**: ADR-0019 (`docs/adr/0019-free-with-donations.md`) accepted; ADR-0006 and ADR-0013 superseded in practice.
