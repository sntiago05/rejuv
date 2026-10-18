# Task 037: Prompt v2 with coach insights, severity in the result, and soul curve rank fallback

## Status
Implemented on `feature/037-prompt-v2-priorities`. `.\mvnw.cmd verify` passes (958 tests run, 0 failures, 2 skipped). Not pushed, not merged.

## Summary

### Part A: Prompt v2 & Leak Priorities
- **System Prompt Rewrite (`analysis-system.txt`)**:
  - Rewrote the LLM system prompt in English under 4,000 characters (2,878 characters), structuring it in 7 explicit sections.
  - Section 1 explains all 5 coach keys (`phases`, `deaths`, `build`, `recent_form`, `matchup`) and instructs the model to trust the code-computed `significance` (`major`, `notable`, `none`).
  - Section 2 defines the metrics and z-scores (`*_z` standard deviations, higher deaths is worse, correlation vs causation for matchup win rates, `recent_form` habit vs one-off).
  - Section 3 establishes strict priority ordering: highest significance first (`major` before `notable`), deterministic tie-breaking order (`deaths` > `phases` > `build` > `matchup` > `recent_form`), and metrics fallback (below p25 or worse than p50) when no coach insight is notable or major. Dominated match guard forbids naming a metric leak if positive `net_worth_z` across all checkpoints in a win. Sets `focus_area` and maps `severity`.
  - Sections 4-7 cover `strength` (never same topic as leak), `next_game_action` (concrete action tied to focus area), strict JSON output schema (`strength`, `biggest_leak`, `next_game_action`, `key_numbers`, `focus_area`, `severity`), and style/grounding/language rules.
- **Domain Record (`AnalysisResult`)**:
  - Added nullable components `@JsonProperty("focus_area") String focusArea` and `@JsonProperty("severity") String severity` with `@JsonInclude(Include.NON_NULL)`.
  - Preserved the 4-argument constructor delegating with `null, null` for deserialization and backward compatibility with old database rows.
  - Added constants `FOCUS_AREAS = Set.of("phases", "deaths", "build", "recent_form", "matchup", "metrics")` and `SEVERITIES = Set.of("minor", "notable", "major")`.
  - Compact constructor normalizes both strings by trimming and lowercasing, resetting invalid values to `null`.
- **Client Parsing (`AnalysisClient`)**:
  - Passes through `focus_area` and `severity` to `trimmedResult`. Unrecognized or missing values are normalized to `null` and do not cause parsing failure (`invalid_output`).
- **Presentation (`AnalyzeCommand` & `report.html`)**:
  - `AnalyzeCommand`: When `analysis.severity()` is present, prefixes the "Biggest Leak" field value with `messages.get("analyze.severity." + severity) + " "`, subject to the existing field character limit truncation. When `severity` is null, output formatting remains identical to existing behavior.
  - `report.html`: Conditionally renders the severity prefix before the leak text using `#{'analyze.severity.' + report.analysis.severity}` via Thymeleaf (no hardcoded strings, no scripts).
  - Appended message keys in `messages.properties`: `analyze.severity.major`, `analyze.severity.notable`, `analyze.severity.minor`.

### Part B: Soul Curve Own-Rank Fallback
- **Service Fallback (`ReportService`)**:
  - When `telemetry.averageBadge() == null`, `computeSoulCurveData` calls `deadlockApiClient.getPlayerRank(stored.getAccountId())`.
  - If the call throws a `RuntimeException`, logs one WARN with the exception class and returns `Optional.empty()`.
  - If `playerRank` is null, `badge() <= 0`, or `rank() < 1`, returns `Optional.empty()` (unranked player: no curve).
  - Otherwise, derives `tier = Math.clamp(playerRank.rank(), 1, 11)` and sets `ownRank = true`.
  - When `averageBadge()` is present, `getPlayerRank` is never called and `ownRank = false`.
- **SVG Caption Overload (`SoulCurveSvg`)**:
  - Added 7-argument overload taking `boolean ownRank`.
  - Uses `report.curve.caption_own_rank` when `ownRank` is true, otherwise `report.curve.caption`.
  - Maintained backward compatibility via 6-argument overload delegating to `render(..., false, ...)`.
  - Updated neutral wording for `report.curve.alt` and `report.curve.aria`, and appended `report.curve.caption_own_rank` in `messages.properties`.

## Files Created or Modified
- `src/main/resources/prompts/analysis-system.txt`: Rewritten prompt v2 (2,878 chars).
- `src/main/java/sntiago05/rejuv/domain/AnalysisResult.java`: Added `focusArea` and `severity` components, constants, normalization, and 4-arg constructor delegation.
- `src/main/java/sntiago05/rejuv/clients/AnalysisClient.java`: Passed through `focus_area` and `severity`.
- `src/main/java/sntiago05/rejuv/commands/AnalyzeCommand.java`: Added severity prefixing for Discord embed leak field.
- `src/main/resources/templates/report.html`: Added conditional severity label in leak card.
- `src/main/java/sntiago05/rejuv/services/ReportService.java`: Added own-rank fallback in `computeSoulCurveData` and passed `ownRank` flag to `soulCurveSvg.render`.
- `src/main/java/sntiago05/rejuv/services/SoulCurveSvg.java`: Added 7-arg `render` overload selecting `caption_own_rank`.
- `src/main/resources/messages.properties`: Updated `report.curve.alt` and `report.curve.aria` to neutral phrasing; appended `analyze.severity.*` and `report.curve.caption_own_rank`.
- `src/test/java/sntiago05/rejuv/clients/AnalysisPromptTest.java`: New unit test asserting prompt size (< 4000 chars), coach keys, severities, and required keywords.
- `src/test/java/sntiago05/rejuv/domain/AnalysisResultTest.java`: Tested 4-arg constructor, serialization omitting nulls, trimming/lowercasing normalization, and invalid value reset.
- `src/test/resources/fixtures/anthropic/messages-with-priorities.json`: Added Anthropic API response fixture with `focus_area` and `severity`.
- `src/test/java/sntiago05/rejuv/clients/AnalysisClientTest.java`: Tested responses with valid new fields, missing new fields, and invalid severity normalization.
- `src/test/java/sntiago05/rejuv/commands/AnalyzeCommandTest.java`: Tested severity prefixing, field limit truncation, and null severity output identity.
- `src/test/java/sntiago05/rejuv/services/SoulCurveSvgTest.java`: Tested own-rank caption formatting, 6-arg overload delegation, and SVG escaping.
- `src/test/java/sntiago05/rejuv/services/ReportServiceTest.java`: Tested lobby badge present (never calls `getPlayerRank`, `ownRank=false`), badge null + ranked player (`hasSoulCurve=true`, `ownRank=true`), unranked player (`badge() == 0`), `getPlayerRank` throwing exception, and rank 12 tier clamping.
- `docs/task-reports/037-prompt-v2-priorities.md`: This task report.

## Verification
- **Baseline Test Run**:
  - `.\mvnw.cmd verify`: 938 tests run, 0 failures, 2 skipped.
- **Targeted Test Runs**:
  - `AnalysisPromptTest`: 1 test run, 0 failures.
  - `AnalysisResultTest`: 20 tests run, 0 failures.
  - `AnalysisClientTest`: 18 tests run, 0 failures.
  - `AnalyzeCommandTest`: 46 tests run, 0 failures.
  - `SoulCurveSvgTest`: 11 tests run, 0 failures.
  - `ReportServiceTest`: 19 tests run, 0 failures.
  - `ReportControllerTest`: 10 tests run, 0 failures.
- **Final Verification**:
  - `.\mvnw.cmd verify`: BUILD SUCCESS.
  - Tests run: **958**, Failures: **0**, Errors: **0**, Skipped: **2** (+20 tests).
- **Manual Check**:
  - Real Discord interaction and live Anthropic API calls were not run manually in this automated environment (token not configured for real API/bot in test worktree).
  - Unit tests comprehensively simulate:
    - `/analyze` embed formatting with and without severity labels.
    - Web report rendering of leak card with and without severity labels.
    - SVG generation with lobby rank and own-rank caption.
    - Deserialization and display of legacy stored analyses without `focus_area`/`severity`.

## Deviations
None.

## Known Issues
None.

## Merge Risks
None. Schema was not modified (stored JSON text is backward-compatible with 4-arg constructor and `@JsonIgnoreProperties`), and no existing coach providers or metadata sources were altered.

## Project Context Facts (to add at integration)
The following facts should be added to `docs/PROJECT_CONTEXT.md` upon merge into `main`:
- **Prompt v2**: `analysis-system.txt` incorporates code-computed coach insights (`phases`, `deaths`, `build`, `recent_form`, `matchup`), trusts code-assigned `significance`, and prioritizes leaks by significance and deterministic tie-breaking.
- **AnalysisResult Components**: Added nullable `@JsonProperty("focus_area") String focusArea` and `@JsonProperty("severity") String severity` (`major`, `notable`, `minor`). Old rows deserialize safely with `null`.
- **Severity Embed & Report Display**: Discord `/analyze` embed prefixes the Biggest Leak field with severity label (`🔴 Major: `, `🟠 Notable: `, `⚪ Minor: `). Report HTML leak card displays the same tag.
- **Soul Curve Fallback**: When match lobby has no average badge (`telemetry.averageBadge() == null`), `ReportService` queries `DeadlockApiClient.getPlayerRank(...)` to build soul curve against the player's current rank tier, using caption `report.curve.caption_own_rank`. Unranked players (`badge <= 0` or `rank < 1`) continue to yield no curve.
