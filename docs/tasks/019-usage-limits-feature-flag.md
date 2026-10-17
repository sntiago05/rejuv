# Task 019: Feature flag and weekly usage limits for `/analyze`

- **Branch**: `feature/019-usage-limits`
- **Implementer**: Antigravity
- **Related ADRs**: ADR-0006 (freemium, 3 free AI analyses per week, launch free first), ADR-0003 (cost control), ADR-0008 (English, i18n-ready)

## Goal
Add a weekly limit of fresh AI analyses per Discord user, **off by default** behind a feature flag, so the owner can launch free and switch limits on later. Only a fresh AI call counts: reopening a stored analysis, a degraded or fallback result (no AI text) and `/matches` never count. Add `/usage` so a user can see what is left. Premium detection (Task 020) is a port with a default "nobody is premium" implementation here.

## Context (verified from the repository when this spec was written, 2026-10-17)
Do not repeat this discovery. If a fact below is false in your checkout, stop and ask.

- Plan: `docs/implementation-plan.md` task 019 depends on 009 (merged). **This spec also needs Task 025 merged first** (it changes `AnalyzeService.analyze` to `analyze(long accountId, MatchFilter filter)`). Start the branch from the latest `origin/main` after 025 is merged; if 025 is not merged, stop and ask. Recommended merge order: 025, 019, 017. Baseline: measure `./mvnw clean verify` before your first change.
- Flyway currently has `V1__create_linked_account.sql` and `V2__create_match_analysis.sql`; **this task uses `V3`**. Task 017 uses `V4` and must merge after this one.
- `AnalyzeService` (real behavior): history -> latest/selected match -> rank -> assets -> stored analysis (returned with no AI call) -> metadata (degradable) -> benchmarks -> `AnalysisClient.analyze` -> a fallback outcome is **not stored** -> otherwise saves `MatchAnalysis` (unique `(account_id, match_id)`) and handles a concurrent duplicate. `AnalyzeCommand` checks the linked account before deferring, then public `deferReply()`. `ProfileService.findLinkedAccountId(long)` is the only link lookup.
- Configuration pattern: `@ConfigurationProperties` records registered with `@EnableConfigurationProperties` in a config class (`BotProperties` in `JdaConfig`, `AnthropicProperties` in `AnthropicConfig`). Environment variables feed `application.yaml` (`${NAME:default}`), documented in `.env.example` (never read or edit `.env`).
- Rules from `AGENTS.md`: no JDA types in `services/`, no business logic in `commands/`, user-facing text in `messages.properties`, Flyway only for schema changes, constructor injection, no new dependency.

## Files to create or modify
| File | Change |
|---|---|
| `src/main/resources/db/migration/V3__create_analysis_usage.sql` | **Create.** |
| `src/main/java/sntiago05/rejuv/domain/AnalysisUsage.java` | **Create.** JPA entity. |
| `src/main/java/sntiago05/rejuv/repositories/AnalysisUsageRepository.java` | **Create.** |
| `src/main/java/sntiago05/rejuv/config/UsageProperties.java` | **Create.** Properties record. |
| `src/main/java/sntiago05/rejuv/config/UsageConfig.java` | **Create.** Registers `UsageProperties` and the default `PremiumChecker` bean. |
| `src/main/java/sntiago05/rejuv/services/PremiumChecker.java` | **Create.** Interface (port for Task 020). |
| `src/main/java/sntiago05/rejuv/services/UsageService.java` | **Create.** |
| `src/main/java/sntiago05/rejuv/services/UsageLimitExceededException.java` | **Create.** |
| `src/main/java/sntiago05/rejuv/domain/UsageStatus.java` | **Create.** Record. |
| `src/main/java/sntiago05/rejuv/services/AnalyzeService.java` | **Modify.** New signature with the Discord user id, limit check before the AI call, usage recorded after a fresh AI analysis is stored. Nothing else. |
| `src/main/java/sntiago05/rejuv/commands/AnalyzeCommand.java` | **Modify.** Pass the caller id, handle `UsageLimitExceededException`. |
| `src/main/java/sntiago05/rejuv/commands/UsageCommand.java` | **Create.** |
| `src/main/resources/application.yaml` | **Modify.** Add the `rejuv.usage` block only. |
| `.env.example` | **Modify.** Add `USAGE_LIMITS_ENABLED=false` and `USAGE_WEEKLY_FREE_ANALYSES=3` (no real values, no secrets). |
| `src/main/resources/messages.properties` | **Modify.** Add the `usage.*` block and `analyze.limit_reached` at the **end of the file**, and the `/usage` line to `help.body` (only that line). |
| `src/test/java/sntiago05/rejuv/services/UsageServiceTest.java` | **Create.** |
| `src/test/java/sntiago05/rejuv/repositories/AnalysisUsageRepositoryTest.java` | **Create.** (Testcontainers, like `MatchAnalysisRepositoryTest`.) |
| `src/test/java/sntiago05/rejuv/services/AnalyzeServiceTest.java` | **Modify.** New signature and limit scenarios. |
| `src/test/java/sntiago05/rejuv/commands/AnalyzeCommandTest.java` | **Modify.** New signature and the limit reply. |
| `src/test/java/sntiago05/rejuv/commands/UsageCommandTest.java` | **Create.** |
| `src/test/java/sntiago05/rejuv/commands/HelpCommandTest.java` | **Modify.** Only the `contains(...)` list: add `/usage`. |
| `docs/task-reports/019-usage-limits.md` | **Create.** Task report (format of `docs/task-reports/014-heroes.md`). |

No `pom.xml` change. If you think one is needed, stop and ask.

## Interfaces / signatures

### Migration `V3__create_analysis_usage.sql`
Table `analysis_usage`: `id bigserial primary key`, `discord_user_id bigint not null`, `match_id bigint not null`, `created_at timestamptz not null`; index `idx_analysis_usage_user_created` on `(discord_user_id, created_at)`. Never edit it after it is applied.

### Properties
```java
@ConfigurationProperties("rejuv.usage")
public record UsageProperties(boolean enabled, int weeklyFreeAnalyses) { }
```
`application.yaml`: `rejuv.usage.enabled: ${USAGE_LIMITS_ENABLED:false}`, `rejuv.usage.weekly-free-analyses: ${USAGE_WEEKLY_FREE_ANALYSES:3}`. Reject a non-positive `weeklyFreeAnalyses` at startup with a clear message.

### Premium port
```java
public interface PremiumChecker {
    /** True when the Discord user has unlimited analyses. Task 020 provides the Patreon role implementation. */
    boolean isPremium(long discordUserId);
}
```
`UsageConfig` registers a default bean (`@Bean @ConditionalOnMissingBean(PremiumChecker.class)`) that returns `false` for everyone, so Task 020 only has to add its own bean.

### `services/UsageService` (new `@Service`)
```java
@Autowired public UsageService(UsageProperties properties, AnalysisUsageRepository repository, PremiumChecker premiumChecker)
UsageService(UsageProperties properties, AnalysisUsageRepository repository, PremiumChecker premiumChecker, Clock clock) // tests
/** @throws UsageLimitExceededException when limits are enabled, the user is not premium and used >= limit */
public void checkAllowance(long discordUserId)
/** Stores one usage row (call only after a fresh AI analysis was saved). No-op when limits are disabled. */
public void recordAnalysis(long discordUserId, long matchId)
public UsageStatus status(long discordUserId)
```
```java
/** limit and used are for the current UTC week; resetsAt is the next Monday 00:00 UTC. When !enabled or premium, nothing is limited. */
public record UsageStatus(boolean limitsEnabled, boolean premium, int used, int limit, Instant resetsAt) {
    public boolean unlimited() { return !limitsEnabled || premium; }
}
public final class UsageLimitExceededException extends RuntimeException {
    public UsageLimitExceededException(Instant resetsAt, int limit) { ... }
    public Instant resetsAt();
    public int limit();
}
```
The window is the **calendar week in UTC, Monday 00:00 to the next Monday 00:00** (owner can change in review). Counting is one `count` query by `(discordUserId, createdAt >= weekStart)`.

### `AnalyzeService`
```java
/** @throws UsageLimitExceededException when the free weekly limit is used up and a fresh AI call would be needed */
public AnalysisReport analyze(long discordUserId, long accountId, MatchFilter filter)
```
Order inside: everything up to the stored-analysis check is unchanged, so **a stored analysis is returned with no limit check and no usage row**. `usageService.checkAllowance(discordUserId)` runs **after** the stored check and **before** the metadata, benchmark and AI calls (do not spend API calls for a user who is over the limit). `usageService.recordAnalysis(discordUserId, matchId)` runs only after `repository.save(entity)` succeeded for a non-fallback outcome. A fallback outcome and a concurrent-duplicate path record nothing.

## Behavior
- Flag off (default): behavior identical to today; no usage row is written; `/usage` says limits are off.
- Flag on, not premium, used < limit: the analysis runs and one row is recorded. used >= limit: no AI call; `analyze` replies (public text, after the deferral) with `analyze.limit_reached` (`{0}` the limit, `{1}` the reset as a Discord timestamp `<t:epoch:R>`). The reply never mentions Patreon or payment (that comes with Task 020).
- Premium (`PremiumChecker.isPremium`): never limited; rows are still recorded (useful for stats) **only when limits are enabled**.
- `/usage` (name `usage`, **ephemeral**, no options, no deferral needed): flag off -> `usage.disabled`; unlimited (premium) -> `usage.premium`; otherwise `usage.status` (`{0}` used, `{1}` limit, `{2}` reset timestamp `<t:epoch:R>`). It never calls an external API.
- The reset instant and the week start are computed from the injected `Clock` in UTC.

### User-facing text (English)
`usage.description`, `usage.disabled`, `usage.premium`, `usage.status`, `analyze.limit_reached`. Reuse `error.generic`.

## Tests
1. **`UsageServiceTest`:** flag off never throws and never records; limit reached throws with the right `resetsAt` and `limit`; premium never throws; week boundary (Sunday 23:59:59 UTC counts in the old week, Monday 00:00:00 starts a new one) with a fixed `Clock`; `status` values; non-positive limit rejected.
2. **`AnalysisUsageRepositoryTest`:** the count query counts only that user and only rows in the window.
3. **`AnalyzeServiceTest`:** stored analysis never checks the limit nor records usage; fresh AI analysis records exactly one row; fallback outcome records nothing; over the limit: no metadata, benchmark or AI call is made and `UsageLimitExceededException` propagates; concurrent duplicate path records nothing.
4. **`AnalyzeCommandTest`:** `UsageLimitExceededException` -> `analyze.limit_reached` with the reset timestamp; the caller id is passed to the service.
5. **`UsageCommandTest`:** name, ephemeral reply, the three states; all `usage.*` keys and `analyze.limit_reached` resolve.

## Verification
1. `./mvnw clean verify` passes with Docker running (Windows: `.\mvnw.cmd clean verify`; see the `MAVEN_USER_HOME` note in `docs/PROJECT_CONTEXT.md`). Report the real test count against the baseline; Flyway applies V3 on the Testcontainers database.
2. `git diff --check`; the diff contains only the files in the table; `.env` never read or printed.
3. Manual check (needs the usual credentials): with `USAGE_LIMITS_ENABLED=true USAGE_WEEKLY_FREE_ANALYSES=1`: first fresh `/analyze` works, `/usage` shows 1 of 1, a second different match is refused with the reset time, reopening the first match still works. If you do not run it, say so in the report.

## Documentation
- Task report `docs/task-reports/019-usage-limits.md`: status, summary, files, commands run with real results, deviations, known issues.
- `docs/PROJECT_CONTEXT.md`: **do not edit in this branch** while other task branches are active; mark it "pending integration" and list the facts (V3, the flag keys, `PremiumChecker` default, `/usage`, the `analyze` signature).
- Do not edit ADRs, `docs/mvp-spec.md`, `docs/implementation-plan.md` or other specs.

## Out of scope
- Patreon, the Premium role check and any payment text (Task 020); per-guild limits; limits for other commands; an admin command to reset usage; caching `UsageService.status`.
- Any change to the prompt, the AI client or token trimming.

## Acceptance criteria
- [ ] With the flag off nothing changes and nothing is written
- [ ] With the flag on, only fresh AI analyses count; stored reopenings, fallbacks and degraded-without-AI results never do
- [ ] An over-limit user triggers no metadata, benchmark or AI call
- [ ] The weekly window is Monday 00:00 UTC to Monday 00:00 UTC and the reset time shown is correct
- [ ] `PremiumChecker` exists with a default "nobody" bean that Task 020 can replace without editing `UsageService`
- [ ] `/usage` is ephemeral and makes no external call
- [ ] V3 migration applies; no existing migration edited; no new dependency
- [ ] All user-facing text is in `messages.properties`
- [ ] `./mvnw clean verify` passes; test count and baseline difference are in the report
- [ ] Task report created; `docs/PROJECT_CONTEXT.md` update marked pending integration; final diff has no unrelated files

## Decisions and open questions
Decisions taken in this spec (owner, 2026-10-17): the free limit is 3 analyses per week (ADR-0006); limits start disabled; the window is the UTC calendar week; only fresh AI analyses count.

Open for the owner (not blocking): rolling 7 days instead of a calendar week; whether premium usage should always be recorded for statistics.
