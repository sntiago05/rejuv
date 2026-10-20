# Task 052: Better coach output (tips) and an AI spend guard

- **Branch**: `feature/052-coach-quality-and-spend-guard`
- **Implementer**: Claude Code
- **Related ADRs**: ADR-0019 (free with donations; it defers "a future cooldown or budget cap to a separate task", this is that task). No new ADR: the guard is a small in-memory service, not an architecture change.
- **Depends on**: 051 merged (usage limits removed; `AnalyzeService.analyze(long discordUserId, ...)` still has the now unused `discordUserId` parameter, which this task starts using).

## Goal
Make the `/analyze` coaching richer and more useful (deeper prompt, a prioritized list of 3 tips with evidence and a drill, bigger output budget) while keeping the owner's Anthropic spend bounded. The owner has only 5 USD of API credit, so a configurable cooldown plus a per-user and a global daily cap on **new** AI calls ship in the same task. Stored analyses (cache hits) never count.

## Why (verified in the repo)
- `application.yaml`: `anthropic.max-tokens: 1500`, `effort: low`, `anthropic.pricing.*-usd-per-million: 0`. With price 0, `AnalyzeService.estimatedCostUsd` always logs `unknown`, so real cost is invisible.
- `PROJECT_CONTEXT.md` records an audit of real analyses: numbers are grounded, but there are reasoning errors (a value compared with the wrong median), "leaks" in dominant games, and no priority by impact.
- `AnalysisResult` has `strength`, `biggest_leak`, `next_game_action`, `key_numbers`, `focus_area`, `severity`. There is no list of tips.
- `AnalyzeService.analyze` returns a stored analysis before any AI call (`repository.findByAccountIdAndMatchId`), so the guard must sit after that branch.
- ADR-0019: no limits exist today; the only brakes are deadlock-api and Anthropic rate limits.
- `docs/costs.md` (not re-verified): Haiku 5.5 is 0.10 USD / 1M input and 0.50 USD / 1M output tokens. The owner must confirm current prices in the Anthropic Console; the values are config, not code.

Cost check (estimate, from `docs/costs.md` prices): ~4,000 input + ~2,000 output tokens is about 0.0014 USD per new analysis, so 5 USD covers about 3,500 analyses. With `max_tokens` 4000 as a hard ceiling (4,000 output + 5,000 input is 0.0029 USD) the worst case is about 1,700. A failed first attempt that is retried can double one analysis. The global daily cap below bounds the loss per day to about 150 x 0.003 = 0.45 USD.

## Files to create or modify
| File | Change |
|---|---|
| `src/main/resources/application.yaml` | `anthropic.max-tokens: 4000`, `anthropic.effort: medium`, `anthropic.pricing.input-usd-per-million: 0.10`, `output-usd-per-million: 0.50` (comment: values from `docs/costs.md`, verify in the Console). New block `rejuv.analysis-limits` (below). |
| `src/main/resources/prompts/analysis-system.txt` | Rewrite sections 3-7 as described in "Prompt". Keep sections 1-2 (field meanings) unchanged except for added text noted below. |
| `src/main/java/sntiago05/rejuv/clients/dto/MessagesRequest.java` | Add `tips` to `RESULT_SCHEMA`. |
| `src/main/java/sntiago05/rejuv/clients/AnalysisClient.java` | In `handleSuccessResponse`: sanitize tips and pass them into the trimmed result (below). No other change. |
| `src/main/java/sntiago05/rejuv/domain/AnalysisResult.java` | New component `tips` and nested record `Tip` (below). |
| `src/main/java/sntiago05/rejuv/config/AnalysisLimitsProperties.java` (new) | Limits properties record. |
| `src/main/java/sntiago05/rejuv/services/AnalysisSpendGuard.java` (new) | In-memory limiter. |
| `src/main/java/sntiago05/rejuv/services/AnalysisLimitExceededException.java` (new) | Unchecked exception with reason and wait time. |
| `src/main/java/sntiago05/rejuv/services/AnalyzeService.java` | Inject the guard; call `reserve` right before `analysisClient.analyze(input)`. |
| `src/main/java/sntiago05/rejuv/commands/AnalyzeCommand.java` | Catch `AnalysisLimitExceededException`; render the tips field. |
| `src/main/resources/messages.properties` | New keys (below). |
| `.env.example` | Add `ANALYSIS_LIMITS_ENABLED`, `ANALYSIS_COOLDOWN`, `ANALYSIS_USER_DAILY`, `ANALYSIS_GLOBAL_DAILY` with the defaults. Touch no other line. |
| `docs/costs.md` | Edit only the "Running costs" lines for tokens per analysis and cost per analysis, using the estimate above. |
| `docs/manual-testing.md` | Add a short section: tips in the embed, cooldown, daily cap, cached re-run is free. |
| Tests | `src/test/java/sntiago05/rejuv/services/AnalysisSpendGuardTest.java` (new); edit `AnalysisClientTest`, `AnalysisResultTest`, `AnalyzeServiceTest`, `AnalyzeCommandTest`; new fixture `src/test/resources/fixtures/anthropic/messages-with-tips.json`. |
| `docs/task-reports/052-coach-quality-and-spend-guard.md` | New: task report. |

No other file. Do not edit `pom.xml`, migrations (no schema change: tips live inside `result_json`), ADRs, `AGENTS.md`, `docs/PROJECT_CONTEXT.md` (mark the context update as pending integration if other worktrees are active), or other task specs. The web report page (`report.html`, `report.css`, `ReportService`, `ReportView`) is **not** touched here: task 053 (Tailwind redesign) runs in parallel, owns those files and shows tips by reading `tips` from the stored `result_json`. Stored JSON written by this task must therefore keep the field name `tips` with items `tip`, `evidence`, `drill` exactly as specified below.

## Interfaces / signatures

### `AnalysisResult` (domain, edit)
```java
public record AnalysisResult(String strength,
        @JsonProperty("biggest_leak") String biggestLeak,
        @JsonProperty("next_game_action") String nextGameAction,
        @JsonProperty("key_numbers") List<String> keyNumbers,
        @JsonProperty("focus_area") @JsonInclude(NON_NULL) String focusArea,
        @JsonProperty("severity") @JsonInclude(NON_NULL) String severity,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) List<Tip> tips) {

    public record Tip(String tip, String evidence, String drill) { }  // @JsonIgnoreProperties(ignoreUnknown = true)
}
```
- Keep the existing 4-argument and 6-argument constructors (they delegate with `tips = List.of()`), so current callers and tests keep compiling.
- Compact constructor: `tips == null` becomes `List.of()`; each tip text goes through the existing `clean(...)`; tips with a blank `tip` are dropped; at most 3 are kept; the list is immutable.
- Old stored JSON without `tips` must still deserialize (`tips` empty). Empty tips are not written (`NON_EMPTY`), so the stored JSON of analyses without tips is unchanged.

### `MessagesRequest.RESULT_SCHEMA` (edit)
Add property `tips`: `{"type":"array","items":{"type":"object","properties":{"tip":string,"evidence":string,"drill":string},"required":["tip","evidence","drill"],"additionalProperties":false}}`. Add `"tips"` to the top-level `required` list. Do not use `minItems`/`maxItems` (structured output may not support them); the count is enforced in code and in the prompt.

### `AnalysisClient.handleSuccessResponse` (edit)
Tips are **lenient**: a missing or empty `tips` array is still a valid result (never spend a retry on it). Pass `result.tips()` through to the trimmed `AnalysisResult`; the `AnalysisResult` constructor already drops blank tips and caps at 3. The validation of the existing six fields stays as is.

### `AnalysisLimitsProperties` (config, new)
```java
@ConfigurationProperties("rejuv.analysis-limits")
public record AnalysisLimitsProperties(@DefaultValue("true") boolean enabled,
                                       @DefaultValue("60s") Duration cooldown,
                                       @DefaultValue("10") int userDaily,
                                       @DefaultValue("150") int globalDaily) { }
```
A value `<= 0` (or a zero cooldown) turns that single limit off. `enabled=false` turns the whole guard off.

### `AnalysisLimitExceededException` (services, new)
```java
public class AnalysisLimitExceededException extends RuntimeException {
    public enum Reason { COOLDOWN, USER_DAILY, GLOBAL_DAILY }
    public AnalysisLimitExceededException(Reason reason, Duration retryAfter, int limit) { ... }
    public Reason reason();
    public Duration retryAfter();   // time until the request would be allowed
    public int limit();             // the limit value that was hit (0 for COOLDOWN)
}
```

### `AnalysisSpendGuard` (services, new)
```java
@Service
@EnableConfigurationProperties(AnalysisLimitsProperties.class)
public class AnalysisSpendGuard {
    public AnalysisSpendGuard(AnalysisLimitsProperties properties);             // Clock.systemUTC()
    AnalysisSpendGuard(AnalysisLimitsProperties properties, Clock clock);       // tests; follow MatchupCoachProvider's constructor pattern for Spring
    /** Checks all limits and, only if all pass, records one AI call for the user and globally. */
    public void reserve(long discordUserId);   // throws AnalysisLimitExceededException
}
```

## Behavior

### Spend guard
1. If `enabled` is false, `reserve` does nothing.
2. Check in this order, and record nothing unless all checks pass:
   1. **Cooldown**: last reserved time for this user plus `cooldown` is still in the future -> `COOLDOWN`, `retryAfter` = remaining time.
   2. **User daily**: the user already has `userDaily` reservations in the last 24 h (rolling window) -> `USER_DAILY`, `retryAfter` = time until the oldest one leaves the window.
   3. **Global daily**: same rule over all users with `globalDaily` -> `GLOBAL_DAILY`.
3. The check-and-record step is atomic (one lock for the whole method is fine; the work is a few list operations). Two simultaneous requests from one user must not both pass a cooldown.
4. Data structure: per user a deque of timestamps pruned from the head on each call; a single deque for the global window. Keep users in a Caffeine cache (`expireAfterAccess` 24 h, `maximumSize` 10,000) so memory stays bounded. No scan over all users on each call.
5. State is in memory and resets on restart. This is accepted and goes in the task report as a limitation.
6. A call that is rejected records nothing and logs one INFO line: `Analyze limited userId={} reason={} retryAfterSeconds={}`.

### `AnalyzeService`
- Call `spendGuard.reserve(discordUserId)` **after** the stored-analysis branch and **immediately before** `analysisClient.analyze(input)`. Cached results, `MatchNotFoundException` and similar early exits never consume quota.
- A reserved call counts even if the AI then fails (the cost may still have been paid). Do not refund.
- The exception propagates out of `analyze`; nothing is stored.
- `estimatedCostUsd` now logs a real value because pricing is set in `application.yaml`; no code change there.

### `AnalyzeCommand`
- Add `catch (AnalysisLimitExceededException ex)` before the generic `RuntimeException` catch. Reply with `hook.editOriginal(...)` using the key by reason. Format `retryAfter` with a small helper (reuse one in `CommandSupport` if it exists, else a private method): under 60 minutes -> `analyze.limit.wait_minutes`, otherwise `analyze.limit.wait_hours`, rounding up, minimum 1.
- Tips field: after `analyze.field.next_action` and before strength, when `analysis.tips()` is not empty, add one field named `analyze.field.tips` with one block per tip built from `analyze.tip.line`. Fit it to the 1024-character field limit (use `EmbedStyle.addFittedField` if its signature fits a list of lines, otherwise truncate with the existing `truncate`). If adding the field would push the embed over `MessageEmbed.EMBED_MAX_LENGTH_BOT`, skip the tips field (same guard style as `addSupportField`) rather than failing.

### Prompt (`analysis-system.txt`)
Keep the grounding and `significance` rules. Change or add:
1. **Priority by impact**: choose `biggest_leak` by expected effect on winning, not by the largest number; major significance still beats notable.
2. **Right benchmark**: compare a metric only with the p25/p50 given for that same metric and say which benchmark is used. Never compare a value with another metric's median. Show the player's number and the reference together.
3. **Dominant games**: in a win where net worth was above average at every checkpoint, say no real leak was found, give the smallest improvement and `severity` `minor`.
4. **Tips**: output `tips`: 3 items, ordered from highest to lowest expected impact, each tied to a different coach entry or metric when the data allows. `tip` = one concrete action (one sentence, starts with a verb). `evidence` = one sentence with numbers from the input only. `drill` = one sentence describing how to practice it next game with a measurable target built from input numbers (for example "finish laning with at most N deaths", where N comes from the benchmark). If the data supports fewer than 3 tips, output fewer, never invent.
5. **Depth within limits**: `strength` and `biggest_leak` may use up to 3 sentences; `next_game_action` stays 1 sentence and must be the same idea as `tips[0]`. No markdown, no emojis, same language as the `language` field.
6. Keep all prior bans: no inventing events, no causal claims from win rates, no explaining `ttk_*`, no blaming the player for team objective events without player data.

Add to section 6 (output) the `tips` key with the shape above. The prompt stays English; the output language follows `language`.

### `application.yaml` limits block
```yaml
rejuv:
  analysis-limits:
    enabled: ${ANALYSIS_LIMITS_ENABLED:true}
    cooldown: ${ANALYSIS_COOLDOWN:60s}
    user-daily: ${ANALYSIS_USER_DAILY:10}
    global-daily: ${ANALYSIS_GLOBAL_DAILY:150}
```
Add under the existing `rejuv:` key; keep `web` and `donation`.

### User-facing text (`messages.properties`, English)
```
analyze.field.tips=🧭 Top Tips
analyze.tip.line={0}. {1}\n   Evidence: {2}\n   Drill: {3}
analyze.limit.cooldown=You just ran an analysis. Please wait {0} seconds before the next one.
analyze.limit.user_daily=You reached the limit of {0} new analyses per day. Try again in about {1}. Analyses you already ran stay available for free.
analyze.limit.global_daily=Rejuv reached its daily AI budget. Try again in about {0}. Analyses that already exist stay available.
analyze.limit.wait_minutes={0} min
analyze.limit.wait_hours={0} h
```
Check the placeholder style of nearby keys (`{0}` style, `'` escaping) before writing; no user text in code.

## Tests
- `AnalysisSpendGuardTest` (fake `Clock` that tests can advance): passes under limits; cooldown blocks and passes after the time; user daily blocks at the limit and passes when the oldest entry leaves the 24 h window; global daily blocks across different users; rejected call records nothing (a later allowed call count is correct); disabled guard never throws; a zero value disables only that limit; `retryAfter` values are correct; concurrency: N threads calling `reserve` for one user with a cooldown, exactly one passes.
- `AnalysisClientTest`: `messages-with-tips.json` gives 3 tips; the existing success fixture (no tips) still succeeds with empty tips; a tip with a blank `tip` is dropped; more than 3 tips are cut to 3; the request body sent to the server contains `tips` in the schema and `max_tokens` from properties.
- `AnalysisResultTest`: old JSON without `tips` deserializes; empty tips are not serialized; null/blank handling; 4-arg and 6-arg constructors still work.
- `AnalyzeServiceTest`: guard is called once on the AI path and **not** called when a stored analysis exists; when the guard throws, `analysisClient.analyze` is never called and nothing is saved; exception type propagates. Update the three `new AnalyzeService(...)` calls (the guard is the new last parameter).
- `AnalyzeCommandTest`: each of the three reasons replies with its message and formatted wait; tips field present with 3 tips; absent when empty; embed stays within the Discord limits with long tips.

## Out of scope
- Persisting limit state in the database, new migrations, per-guild limits, an admin command, a monthly USD budget tracker.
- Prompt caching, batching, switching model, or changing the coach providers (031-035, 043-045).
- Any web report change (page, CSS, charts, tips card): task 053.
- Updating `mvp-spec`, `features`, `product`, `roadmap`, `architecture`, `implementation-plan`, `deployment` (owner follow-up; mention it in the report).

## Acceptance criteria
- [ ] `./mvnw verify` passes.
- [ ] New `/analyze` results contain up to 3 tips; the embed shows them; older stored analyses still render without errors (the web report shows tips in task 053).
- [ ] `max-tokens` is 4000, `effort` is `medium`, pricing is set, and the "Analyze ... estimatedCostUsd=" log line shows a number.
- [ ] A second new analysis within the cooldown is refused with the cooldown message; the (N+1)th new analysis in 24 h is refused; a stored match can be re-run any time without consuming quota.
- [ ] The global daily cap blocks new AI calls for all users and cached analyses still work.
- [ ] With `ANALYSIS_LIMITS_ENABLED=false` no limit applies.
- [ ] No Anthropic API key, token or `.env` content is logged or committed.
- [ ] Unit tests for the guard and the changed service logic; follows AGENTS.md conventions.
- [ ] The task report lists: the real `effort: medium` and `max_tokens: 4000` values accepted by the API **only if a real call was made** (otherwise state it is untested and the owner must run one `/analyze` to confirm), the in-memory reset limitation, and the doc follow-ups.
