# Task 051: Remove premium, Patreon and weekly usage limits

- **Branch**: `feature/051-remove-premium-usage-limits`
- **Implementer**: Antigravity
- **Related ADRs**: ADR-0006 (freemium with Patreon) is replaced by a new ADR-0019 written in this task. ADR-0013 (top-rank comparison premium) only needs a note in the report. Do not edit any existing ADR.
- **Depends on**: 049 and 050 merged (they also touch `messages.properties`, `application.yaml`, `.env.example` and `AnalyzeCommandTest`; 050 adds the donation link that replaces premium as the support model).

## Goal
Rejuv is free for everyone. Delete the premium role check, the Patreon code, the weekly usage limits and the `/usage` command. `/items` and `/compare` become open to all users. Drop the unused `analysis_usage` table with a new migration.

## Why (verified)
- `PremiumFeatureGate.allowed(long)` is called in `ItemsCommand` (line 73) and `CompareCommand` (line 74). Both reply `*.premium_required` when it returns false.
- `AnalyzeService` calls `usageService.checkAllowance(discordUserId)` (line 120) and `usageService.recordAnalysis(...)` (line 204). `AnalyzeCommand` catches `UsageLimitExceededException` (line 278).
- Limits and the role check are off by default (`USAGE_LIMITS_ENABLED=false`, `PREMIUM_ROLE_CHECK_ENABLED=false`), so deleting them changes no behavior for the current default setup.
- `V3__create_analysis_usage.sql` created `analysis_usage` and an index. Applied migrations must never be edited, so the table is removed by a new migration.
- The `GUILD_MEMBERS` gateway intent in `JdaConfig` is also needed by `LeaderboardCommand` (`retrieveMembersByIds`). **Keep it.**

## Files to create or modify
Delete (main):
`commands/UsageCommand.java`, `config/JdaPremiumRoleGateway.java`, `config/PremiumProperties.java`, `config/UsageConfig.java`, `config/UsageProperties.java`, `domain/AnalysisUsage.java`, `domain/UsageStatus.java`, `repositories/AnalysisUsageRepository.java`, `services/PatreonRolePremiumChecker.java`, `services/PremiumChecker.java`, `services/PremiumFeatureGate.java`, `services/PremiumLookupException.java`, `services/PremiumRoleGateway.java`, `services/UsageLimitExceededException.java`, `services/UsageService.java` (all under `src/main/java/sntiago05/rejuv/`).

Delete (tests, under `src/test/java/sntiago05/rejuv/`):
`commands/UsageCommandTest.java`, `config/JdaPremiumRoleGatewayTest.java`, `config/PremiumPropertiesTest.java`, `repositories/AnalysisUsageRepositoryTest.java`, `services/PatreonRolePremiumCheckerTest.java`, `services/PremiumFeatureGateTest.java`, `services/UsageServiceTest.java`.

| File | Change |
|---|---|
| `src/main/java/sntiago05/rejuv/services/AnalyzeService.java` | Remove the `UsageService` constructor parameter and field, the `checkAllowance` and `recordAnalysis` calls, and the `@throws UsageLimitExceededException` javadoc. |
| `src/main/java/sntiago05/rejuv/commands/AnalyzeCommand.java` | Remove the `UsageLimitExceededException` import and its `catch` block. |
| `src/main/java/sntiago05/rejuv/commands/ItemsCommand.java` | Remove the `PremiumFeatureGate` parameter, field, import and the `allowed` check. |
| `src/main/java/sntiago05/rejuv/commands/CompareCommand.java` | Same as `ItemsCommand`. |
| `src/main/resources/application.yaml` | Remove the `rejuv.usage` and `rejuv.premium` blocks. Keep `rejuv.web` and the `rejuv.donation` block from task 050. |
| `src/main/resources/messages.properties` | Remove keys below; edit `help.body`. |
| `src/main/resources/db/migration/V6__drop_analysis_usage.sql` (new) | `DROP TABLE IF EXISTS analysis_usage;` (the index goes with the table). |
| `.env.example` | Remove `USAGE_LIMITS_ENABLED`, `USAGE_WEEKLY_FREE_ANALYSES`, `PREMIUM_ROLE_CHECK_ENABLED`, `PREMIUM_GUILD_ID`, `PREMIUM_ROLE_ID` if present. Do not touch any other line. |
| `docs/adr/0019-free-with-donations.md` (new) | New ADR (below). |
| `docs/manual-testing.md` | Remove only the sections and lines about weekly usage limits, `/usage`, Premium, Patreon and `analysis_usage`. |
| Tests | Edit `AnalyzeCommandTest`, `ItemsCommandTest`, `CompareCommandTest`, `AnalyzeServiceTest`, `UpstreamCallCountBenchmarkTest` (remove the `UsageService` mock bean and every premium/usage case); extend `FlywayMigrationTest`. |
| `docs/task-reports/051-remove-premium-and-usage-limits.md` | New: task report. |

No other file. Do not edit `pom.xml`, `JdaConfig`, existing migrations, existing ADRs, `AGENTS.md`, `docs/mvp-spec.md`, `docs/features.md`, `docs/product.md`, `docs/costs.md`, `docs/roadmap.md` or `docs/PROJECT_CONTEXT.md`.

## Interfaces / signatures
- `AnalyzeService`: constructor loses `UsageService usageService`. Keep the other parameters and their order.
- `ItemsCommand` and `CompareCommand`: constructors lose `PremiumFeatureGate premiumFeatureGate`. Keep the other parameters and their order.
- Before deleting a class, search for every remaining reference (`grep -rn`) and make sure nothing is left that does not compile.

## Behavior
1. `/items` and `/compare` run for any linked user, with no role lookup and no usage check. Their other behavior (unlinked user, empty results, errors) does not change.
2. `/analyze` never refuses because of a weekly limit and never writes usage rows.
3. `/usage` no longer exists. The bot registers one command less. Discord drops the old command at the next registration on the dev guild; note in the report that global commands (if registered later) need no cleanup because `/usage` was never global.
4. Flyway `V6` drops `analysis_usage`. **This deletes the stored usage counts for good.** The table only stored counts used for the limits; with limits gone, nothing reads it. The report must state this.
5. `help.body`: remove the `/usage` line; remove the `💎` icon and the `(Premium)` text from the `/items` and `/compare` lines (use a normal icon, for example `🎒` for `/items` and `🆚` for `/compare`). Keep the rest of the text and its escapes (`\n\`) exactly as they are. If task 050 added `help.donate`, leave it.

### Message keys to remove
`usage.description`, `usage.disabled`, `usage.premium`, `usage.status`, `items.premium_required`, `compare.premium_required`, `analyze.limit_reached`, and any other key that only the deleted code used (check with a search before removing; keep any key that other code still uses).

### New ADR-0019 (`docs/adr/0019-free-with-donations.md`)
Follow `docs/adr/0000-template.md`. Status: Accepted. Date: use today's date. Short content:
- **Context**: ADR-0006 planned freemium with Patreon. The owner decided to run Rejuv free.
- **Decision**: no limits and no premium tier; support comes from an optional configurable donation link (task 050); `/items` and `/compare` are open; premium code removed (task 051).
- **Consequences**: AI cost is paid by the owner and is only bounded by deadlock-api and Anthropic rate limits; there is no per-user cooldown (known issue in `PROJECT_CONTEXT.md`); a future cooldown or budget cap is a separate task; ADR-0006 and ADR-0013 are superseded in practice.
Do not edit ADR-0006 or ADR-0013.

## Out of scope
- A per-user cooldown, a daily budget or any new rate limit (separate task).
- Removing the `GUILD_MEMBERS` intent.
- Updating `mvp-spec.md`, `features.md`, `product.md`, `costs.md`, `roadmap.md` or `PROJECT_CONTEXT.md`.
- Changing what `/items` or `/compare` compute.

## Tests
- `FlywayMigrationTest`: after all migrations, `analysis_usage` does not exist and the other tables (`linked_account`, `match_analysis`) still exist.
- `ItemsCommandTest` and `CompareCommandTest`: a linked user gets the normal reply; the premium cases are removed.
- `AnalyzeServiceTest` and `AnalyzeCommandTest`: usage and limit cases removed; the normal and error paths still pass.
- `CommandRouterTest` (only if it lists command names): remove `usage`.
- Whole suite: `env -u ANTHROPIC_BASE_URL -u ANTHROPIC_CUSTOM_HEADERS ./mvnw verify`. Report the real test counts and explain why the count went down (deleted tests).
- Final search: `grep -rniE "premium|patreon|UsageService|UsageProperties|analysis_usage|/usage" src docs/manual-testing.md compose*.yaml` must return only the new `V6` file and its test. Put the output in the report.

## Documentation
Task report `docs/task-reports/051-remove-premium-and-usage-limits.md`, with a list of the docs that still mention premium or Patreon (`mvp-spec`, `features`, `product`, `costs`, `roadmap`, `architecture`, `implementation-plan`, `deployment`, ADR-0006, ADR-0013, task reports 019/020/021) so the owner can update them. Do not edit `docs/PROJECT_CONTEXT.md`; under "Pending integration" list: free model, `/usage` removed, `V6` migration, new ADR-0019.

## Acceptance criteria
- [ ] Compiles, all tests pass
- [ ] No reference to premium, Patreon or usage limits remains in `src` (final search clean)
- [ ] `/items` and `/compare` work for any linked user; `/usage` is gone
- [ ] `V6` migration added; `V3` untouched; `FlywayMigrationTest` checks the table is gone
- [ ] `GUILD_MEMBERS` intent and `/leaderboard server` still work
- [ ] ADR-0019 written; no existing ADR edited
- [ ] Follows AGENTS.md conventions; only the listed files changed; no new dependency
