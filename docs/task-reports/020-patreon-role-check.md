# Task 020: Patreon Premium role check

## Status

Implemented and verified locally. `.\mvnw.cmd clean verify` passes with 699 tests (675 baseline + 24 new, 0 failures, 2 skipped benchmark tests). Not pushed, not merged.

## Summary

- Created configuration properties record `config/PremiumProperties` (`@ConfigurationProperties("rejuv.premium")`: `enabled`, `guildId`, `roleId`, `cacheTtl`) with compact constructor validating positive IDs when enabled and defaulting `cacheTtl` to 2 minutes.
- Created port interface `services/PremiumRoleGateway` with `boolean hasRole(long guildId, long roleId, long userId)`.
- Created unchecked runtime exception `services/PremiumLookupException`.
- Created implementation `services/PatreonRolePremiumChecker` implementing `PremiumChecker`, annotated with `@Component`, `@Primary`, `@ConditionalOnProperty(prefix = "rejuv.premium", name = "enabled", havingValue = "true")`, and `@EnableConfigurationProperties(PremiumProperties.class)`. Uses Caffeine cache bounded at 10,000 entries with write expiration matching `cacheTtl`, caching both true and false results, atomically querying the gateway via `cache.get(userId, loader)`, logging lookup failures at WARN without leaking internal tokens or stack traces, failing closed (`false`), and never throwing from `isPremium`.
- Created JDA adapter `config/JdaPremiumRoleGateway` implementing `PremiumRoleGateway`, annotated with `@Component` and `@ConditionalOnProperty(prefix = "rejuv.premium", name = "enabled", havingValue = "true")`. Queries `guild.retrieveMemberById(userId).submit().get(5, TimeUnit.SECONDS)`. Handles `UNKNOWN_MEMBER` and `UNKNOWN_USER` as `false`, and converts missing JDA/guild, other Discord API errors, timeouts, and interruptions (restoring thread interrupt status) into `PremiumLookupException`.
- Added `rejuv.premium` configuration block in `src/main/resources/application.yaml` (`enabled`, `guild-id`, `role-id`).
- Added environment variable template entries in `.env.example`: `PREMIUM_ROLE_CHECK_ENABLED=false`, `PREMIUM_GUILD_ID=`, and `PREMIUM_ROLE_ID=`.
- Added comprehensive unit and context tests: `PremiumPropertiesTest` (6 tests), `JdaPremiumRoleGatewayTest` (10 tests), and `PatreonRolePremiumCheckerTest` (8 tests).

## Important Files

- `src/main/java/sntiago05/rejuv/config/PremiumProperties.java` (create)
- `src/main/java/sntiago05/rejuv/services/PremiumRoleGateway.java` (create)
- `src/main/java/sntiago05/rejuv/services/PremiumLookupException.java` (create)
- `src/main/java/sntiago05/rejuv/services/PatreonRolePremiumChecker.java` (create)
- `src/main/java/sntiago05/rejuv/config/JdaPremiumRoleGateway.java` (create)
- `src/main/resources/application.yaml` (modify)
- `.env.example` (modify)
- `src/test/java/sntiago05/rejuv/config/PremiumPropertiesTest.java` (create)
- `src/test/java/sntiago05/rejuv/config/JdaPremiumRoleGatewayTest.java` (create)
- `src/test/java/sntiago05/rejuv/services/PatreonRolePremiumCheckerTest.java` (create)
- `docs/task-reports/020-patreon-role-check.md` (create)

## Decisions

- **Transparent primary replacement**: Annotated `PatreonRolePremiumChecker` with `@Primary` and `@ConditionalOnProperty(prefix = "rejuv.premium", name = "enabled", havingValue = "true")`, allowing it to cleanly take precedence over `UsageConfig`'s default bean without modifying `UsageConfig`, `UsageService`, `AnalyzeCommand`, or `UsageCommand`.
- **Caffeine caching**: In-memory cache with bounded 10,000 maximum entries and write expiration of `cacheTtl` (default 2 minutes). Atomic loading via `cache.get(userId, loader)` deduplicates concurrent checks. Failed lookups throw unchecked exceptions during computation which prevents Caffeine from caching the failure, allowing subsequent retries.
- **Fail closed on error**: Any lookup failure logs user id and exception class name at WARN without Discord internal stack traces or credentials, does not cache the failure, and returns `false` (never grants free unlimited access on errors). `isPremium` never throws.
- **JDA REST lookup**: `retrieveMemberById(userId).submit().get(5, TimeUnit.SECONDS)` with bounded 5-second wait. Maps `UNKNOWN_MEMBER` and `UNKNOWN_USER` to `false` (user not present in support server), while wrapping timeouts, interruptions, missing guilds/JDA, or API errors in `PremiumLookupException`.

## Verification

- **Baseline before changes**: `.\mvnw.cmd verify` passed with **675 tests**, 0 failures, 2 skipped benchmark tests.
- **Narrow test runs**:
  - `.\mvnw.cmd test "-Dtest=PremiumPropertiesTest,JdaPremiumRoleGatewayTest,PatreonRolePremiumCheckerTest"`: 24 tests, 0 failures.
- **Full build**: `.\mvnw.cmd clean verify` passed with **699 tests**, 0 failures, 0 errors, 2 skipped (+24 new tests over baseline).
- **Manual Discord check**: Not run (requires live bot token and Discord support server with configured Premium role).
- **Diff check**: `git diff --check` clean; no secrets, tokens, or personal identifiers committed; only authorized files created or modified.

## Acceptance criteria

| Criterion | Evidence |
|---|---|
| Compiles, all tests pass, counts recorded in the report | Full build passed: 699 tests run (baseline 675 + 24 new tests), 0 failures, 2 skipped |
| Flag off changes nothing; flag on makes users with the role premium within `cacheTtl` | `PremiumPropertiesTest.flagOffUsesDefaultLambdaBean`, `PremiumPropertiesTest.flagOnWithStubGatewayInjectsPatreonRolePremiumCheckerIntoUsageService`, `PatreonRolePremiumCheckerTest.trueAndFalseAnswersReturned`, `PatreonRolePremiumCheckerTest.afterTtlCallsGatewayAgain` |
| Lookup failures fail closed, are not cached and never throw | `PatreonRolePremiumCheckerTest.failureReturnsFalseAndLaterSuccessIsNotBlockedByCachedFailure`, `PatreonRolePremiumCheckerTest.runtimeExceptionThatIsNotPremiumLookupExceptionAlsoReturnsFalse`, `PatreonRolePremiumCheckerTest.isPremiumNeverThrows`, `JdaPremiumRoleGatewayTest.otherDiscordErrorThrowsPremiumLookupException` |
| No new dependency, no user-facing text, no file outside the list changed | `pom.xml`, `messages.properties`, and existing command/service classes untouched; verified by `git status --short` |
| Task report created; `docs/PROJECT_CONTEXT.md` update marked pending integration | `docs/task-reports/020-patreon-role-check.md` created; section below |
| Follows AGENTS.md conventions | Record properties, port/adapter architecture, constructor injection, SLF4J logging, no Lombok |

## Deviations

None. All files, interfaces, and behaviors matched the specification exactly.

## Known Issues

None.

## Merge Risks

None.

## PROJECT_CONTEXT update pending integration

Parallel unmerged feature branches (`feature/017-web-report`, `feature/021-items-premium`, `feature/029-coach-foundation`, `feature/030-analysis-text-cleanup`, `feature/036-deployment-ci`) are active in the repository. Per project rules, `docs/PROJECT_CONTEXT.md` is not modified directly in this branch. The following verified facts are pending integration:
- **Configuration**: Added `rejuv.premium` block (`rejuv.premium.enabled`, `rejuv.premium.guild-id`, `rejuv.premium.role-id`, `rejuv.premium.cache-ttl`) mapped to `PREMIUM_ROLE_CHECK_ENABLED`, `PREMIUM_GUILD_ID`, and `PREMIUM_ROLE_ID`.
- **Premium role check**: Introduced `PatreonRolePremiumChecker` (implementing `PremiumChecker`) and `JdaPremiumRoleGateway` (implementing `PremiumRoleGateway`). When `rejuv.premium.enabled=true`, checks support server role membership via JDA REST lookup with 2-minute Caffeine caching per user id, failing closed on Discord errors or missing users.
