# Task 052: Better coach output (tips) and an AI spend guard

## Status
Implemented on branch `feature/052-coach-quality-and-spend-guard`. `env -u ANTHROPIC_BASE_URL -u ANTHROPIC_API_KEY ./mvnw verify` (through `.claude/skills/run-rejuv/driver.sh verify`) passes: 1123 tests run, 0 failures, 14 skipped (baseline before the change: 1094 run, 14 skipped). Not pushed, not merged. No real Anthropic call was made.

## Summary
1. **Output budget and cost visibility**: `anthropic.max-tokens` 1500 to 4000, `effort` low to medium, pricing set to 0.10 / 0.50 USD per million tokens (values from `docs/costs.md`, to be confirmed in the Console). `estimatedCostUsd` in the `Analyze` log line is now a number.
2. **Prompt**: sections 3-8 of `analysis-system.txt` rewritten: priority by impact, compare a value only with the benchmark of that same metric, no leak in a dominated win, new `tips` section. The prompt is 5397 characters; `AnalysisPromptTest` caps it at 5500, so the new text had to be compact.
3. **Tips**: `AnalysisResult` has a new `tips` component (`Tip(tip, evidence, drill)`, max 3, blank tips dropped, stray characters cleaned, not serialized when empty). `MessagesRequest.RESULT_SCHEMA` requires `tips` (no `minItems`/`maxItems`). `AnalysisClient` passes tips through and stays lenient: a missing tips array is still a valid result and costs no retry. Old stored JSON without `tips` still deserializes.
4. **Embed**: a "🧭 Top Tips" field after "Next Game Focus". It is cut to 1024 characters (whole tips dropped from the end) and skipped when the embed would pass the Discord size limit, with room reserved for the fields that follow.
5. **Spend guard** (`AnalysisSpendGuard`, in memory, rolling 24 h): 60 s cooldown per user, 10 new analyses per user per day, 150 per day in total. Checked and recorded atomically (`synchronized`); a refused call records nothing. It is called in `AnalyzeService` right before `analysisClient.analyze`, after the stored-analysis branch, so stored results never count. A reserved call that then fails at the AI still counts (no refund). Refusal raises `AnalysisLimitExceededException(reason, retryAfter, limit)`; `AnalyzeCommand` replies with a message per reason (seconds, minutes or hours, rounded up). Config: `rejuv.analysis-limits.*` with env overrides `ANALYSIS_LIMITS_ENABLED`, `ANALYSIS_COOLDOWN`, `ANALYSIS_USER_DAILY`, `ANALYSIS_GLOBAL_DAILY`; a value <= 0 disables that limit.

## Important Files
- New: `config/AnalysisLimitsProperties.java`, `services/AnalysisSpendGuard.java`, `services/AnalysisLimitExceededException.java`, `AnalysisSpendGuardTest`, fixture `messages-with-tips.json`.
- Changed: `AnalysisResult`, `MessagesRequest`, `AnalysisClient`, `AnalyzeService`, `AnalyzeCommand`, `application.yaml`, `messages.properties`, `analysis-system.txt`, `docs/costs.md`, `docs/manual-testing.md`.
- Tests changed: `AnalysisClientTest`, `AnalysisResultTest`, `AnalyzeServiceTest`, `AnalyzeCommandTest`, `UpstreamCallCountBenchmarkTest`, `AnthropicPropertiesTest`.

## Decisions
- Guard state is a Caffeine cache (`expireAfterAccess` 24 h, max 10,000 users) of timestamp deques plus one global deque; each call prunes only the head, so no scan over all users.
- The global cap defaults to 150 per day: at the worst-case 0.003 USD per analysis that is about 0.45 USD per day.
- The tips field is sized with a reserve (1,400 characters plus the strength and key-numbers fields that follow) so a long tips block cannot make `EmbedBuilder.build()` fail.

## Verification
- `.claude/skills/run-rejuv/driver.sh verify` on the untouched branch with the default shell environment: 1 failure, `AnthropicPropertiesTest.bindsDefaultsFromApplicationYaml` (the base URL was `https://gateway.context-mode.com`). Cause: the `ANTHROPIC_BASE_URL` environment variable of this shell overrides `anthropic.base-url`; not a repository problem. With `env -u ANTHROPIC_BASE_URL -u ANTHROPIC_API_KEY` the baseline passed: 1094 tests, 14 skipped.
- First full run after my change failed once: `AnalysisPromptTest` (prompt 6166 characters, limit 5500). Fixed by shortening the new prompt text; second run passed: 1123 tests, 0 failures, 14 skipped.
- New tests: `AnalysisSpendGuardTest` (12 cases incl. a 16-thread concurrency case), tips cases in `AnalysisClientTest`, `AnalysisResultTest`, `AnalyzeServiceTest` (guard called once on the AI path, never on the stored path, refusal skips AI and storage), `AnalyzeCommandTest` (three reasons, tips field present, absent and within limits).

## Deviations
- `.env.example` was **not** updated. The file is blocked for my tools by a permission rule, so I could not read or append to it. The four `ANALYSIS_*` variables have defaults in `application.yaml`, so nothing breaks; the owner can add the four lines by hand (`ANALYSIS_LIMITS_ENABLED=true`, `ANALYSIS_COOLDOWN=60s`, `ANALYSIS_USER_DAILY=10`, `ANALYSIS_GLOBAL_DAILY=150`).
- `AnthropicPropertiesTest` was edited (expects `medium` and `4000`) and `UpstreamCallCountBenchmarkTest` got a mock `AnalysisSpendGuard` bean. Neither file is in the spec's test list; both were required by the yaml change and the new `AnalyzeService` constructor parameter.
- The new prompt had to fit `AnalysisPromptTest`'s 5,500-character cap, so it is shorter than the spec text sketched; all six required behaviors are in it. `AnalysisPromptTest` itself was not changed.

## Known Issues
- Guard state is in memory: a restart resets cooldowns and counters.
- `effort: medium` and `max_tokens: 4000` were never sent to the real API (no call was made, and this shell routes `ANTHROPIC_BASE_URL` to a gateway). The owner must run one `/analyze` to confirm the API accepts them.
- Pricing values come from `docs/costs.md` ("verify current pricing"); confirm them in the Anthropic Console.
- A reserved call whose AI request then fails still counts toward the quotas.
- Prompt quality (better reasoning, useful tips) is unjudged with real analyses.

## Merge Risks
- Task 053 (Tailwind report) runs in parallel and also appends keys to the end of `messages.properties`: expect a trivial conflict, keep both sides. The report page reads `tips` from the stored `result_json` (keys `tip`, `evidence`, `drill`), which this task writes.
- PROJECT_CONTEXT update pending integration (task 053 branch is active). Docs that still describe the old limits or costs (`mvp-spec`, `features`, `product`, `deployment`, ADR-0019's note that a cooldown is deferred) need an owner update.
