# Task 037: Prompt v2 with coach insights, severity in the result, and soul curve rank fallback

- **Branch**: `feature/037-prompt-v2-priorities`
- **Implementer**: Claude Code
- **Related ADRs**: ADR-0012, ADR-0018 (web report)
- **Depends on**: 029, 031, 032, 033, 034 and 035 merged into `main` (providers keys `phases`, `deaths`, `build`, `recent_form`, `matchup`), 018 merged (soul curve). Must read `docs/coach-provider-rules.md` first. If 035 is not merged when this starts, stop and ask: the prompt must describe all five keys.

## Goal
Part A: make the model use the coach insights computed in code (`AnalysisInput.coach`, today ignored by the prompt) and pick the biggest leak by `significance`, not by one value versus one median; return and show a `focus_area` and a `severity`. Part B: make the web soul curve appear for non-ranked matches by using the player's own rank when the lobby has no average badge.

## Why (verified)
- `src/main/resources/prompts/analysis-system.txt` mentions only `metrics` and `items`; the `coach` object is sent but never explained, and the audit of 4 real analyses found wrong comparisons and "leaks" in dominant games.
- `GET /r/{id}/soul-curve.svg` answers 404 for a non-ranked match: `average_badge_team0/1` are `0`, so `MatchTelemetry.averageBadge()` is `null` and `ReportService.computeSoulCurveData` returns empty (real case: match 119271423).

## Files to create or modify
| File | Change |
|---|---|
| `src/main/resources/prompts/analysis-system.txt` | Rewrite as prompt v2 (Part A, "Prompt rules"). |
| `src/main/java/sntiago05/rejuv/domain/AnalysisResult.java` | Add `focusArea` and `severity` components; keep the 4-argument constructor and `clean`. |
| `src/main/java/sntiago05/rejuv/clients/AnalysisClient.java` | Parse the two new fields and normalize them (never fail the analysis because of them); keep every existing validation. |
| `src/main/java/sntiago05/rejuv/commands/AnalyzeCommand.java` | Prefix the "Biggest Leak" value with the severity label when `severity` is not null. |
| `src/main/resources/templates/report.html` | Show the same severity label in the leak card. |
| `src/main/java/sntiago05/rejuv/services/ReportService.java` | Part B: own-rank fallback in `computeSoulCurveData`. |
| `src/main/java/sntiago05/rejuv/services/SoulCurveSvg.java` | Part B: overload `render(...)` with an `ownRank` flag selecting the caption key. |
| `src/main/resources/messages.properties` | New keys (below) and the neutral wording of two existing `report.curve.*` keys. |
| `src/test/java/sntiago05/rejuv/domain/AnalysisResultTest.java` | Modify (new components). |
| `src/test/java/sntiago05/rejuv/clients/AnalysisClientTest.java` | Modify; add fixture JSON under `src/test/resources/fixtures/anthropic/` if the existing ones do not fit. |
| `src/test/java/sntiago05/rejuv/commands/AnalyzeCommandTest.java` | Modify. |
| `src/test/java/sntiago05/rejuv/services/ReportServiceTest.java` | Modify (Part B). |
| `src/test/java/sntiago05/rejuv/services/SoulCurveSvgTest.java` | Modify (Part B). |
| `src/test/java/sntiago05/rejuv/web/ReportControllerTest.java` | Modify only if the template change breaks it. |
| `src/test/java/sntiago05/rejuv/clients/AnalysisPromptTest.java` | New: checks the prompt resource (below). |
| `docs/task-reports/037-prompt-v2-priorities.md` | New: task report. |

No other file. Do **not** edit the coach providers, `AnalysisInput`, `CoachContextService`, `AnalyzeService`, `CacheConfig`, `DeadlockApiClient`, `application.yaml` (`max-tokens` stays 1500: the output grows by two short fields; if a test or real run shows `max_tokens` stops, report it instead of changing it) or any Flyway migration (the result is stored as JSON text; no schema change).

## Interfaces / signatures

### AnalysisResult
```java
public record AnalysisResult(String strength,
        @JsonProperty("biggest_leak") String biggestLeak,
        @JsonProperty("next_game_action") String nextGameAction,
        @JsonProperty("key_numbers") List<String> keyNumbers,
        @JsonProperty("focus_area") String focusArea,    // nullable
        @JsonProperty("severity") String severity)        // nullable
```
- Keep `@JsonIgnoreProperties(ignoreUnknown = true)`. Keep a 4-argument constructor delegating with `null, null` so existing callers and every analysis already stored in `match_analysis.result_json` still deserialize.
- Constants: `public static final Set<String> FOCUS_AREAS = Set.of("phases","deaths","build","recent_form","matchup","metrics")` and `SEVERITIES = Set.of("minor","notable","major")`.
- The compact constructor normalizes: `focusArea`/`severity` are trimmed and lowercased; a value outside its set becomes `null` (never an exception). `clean(...)` still applies to the four text fields only.
- Serialization must omit the two fields when `null` (`@JsonInclude(NON_NULL)` on them) so old stored rows do not change shape when re-serialized.

### AnalysisClient
After the existing validation, build the trimmed result with the two new fields passed through (the record normalizes them). A missing or invalid `focus_area`/`severity` is **not** an `invalid_output`.

### SoulCurveSvg
Keep the current `render(String heroName, String rankLabel, List<Integer>, List<Long>, List<Double>, List<Double>)` delegating to a new overload that takes `boolean ownRank` after `rankLabel`; when `ownRank` is true the caption uses `report.curve.caption_own_rank`, otherwise `report.curve.caption` (unchanged).

## Behavior

### Part A: prompt v2 (`analysis-system.txt`)
Rewrite in English, keeping rule 1 (grounding) and rule 4 (language). The prompt must say, in this order:
1. Input: besides `hero`, `rank`, `result`, `duration_min`, `metrics`, `items`, `language`, an optional `coach` object whose keys are `phases`, `deaths`, `build`, `recent_form`, `matchup`; any key may be absent (absent means no data, never infer it). Each coach object has `significance` of `none`, `notable` or `major`, computed in code: trust it, do not recompute it or contradict it.
2. Meaning of the main fields in one line each (the implementer reads each provider class and `docs/tasks/031..035` for the field names; do not invent fields): `*_z` values are standard deviations from the average player of the same hero and rank (positive = above average, but for deaths and `deaths_z` higher is worse); `win_rate_pct` of matchups are global averages and correlational, never present them as cause; `recent_form` tells whether the match is a habit or a one-off.
3. Choosing `biggest_leak`: pick from the `coach` entry with the highest `significance` (`major` before `notable`; ties broken by this fixed order: `deaths`, `phases`, `build`, `matchup`, `recent_form`); if no coach entry is `notable` or `major`, fall back to the old metrics rule (below p25 or clearly worse than p50). Never name a leak from a metric in a match the player dominated (positive `net_worth_z` at every checkpoint and a win): say that no real leak was found, then give the smallest improvement. `focus_area` is the key used (`metrics` for the fallback) and `severity` is that entry's `significance` mapped `major`->`major`, `notable`->`notable`, `none`/metrics fallback->`minor`.
4. Choosing `strength`: from the same data, the best positive deviation; never the same topic as the leak.
5. `next_game_action`: one concrete action tied to the same `focus_area`; no vague advice ("play better").
6. Output: strict JSON only with keys `strength`, `biggest_leak`, `next_game_action`, `key_numbers`, `focus_area`, `severity`; 3-4 `key_numbers` in the form `"<metric>: <yours> / <reference>"` where every number comes from the input.
7. Style: the existing length limits (1-2 sentences), no markdown, no emojis, no stray braces or commas at the end of a value.

The prompt must not add examples containing real account data. Keep the whole file under 4,000 characters.

### Part A: presentation
- `AnalyzeCommand`: when `analysis.severity()` is non-null, the "Biggest Leak" field value is `messages.get("analyze.severity.<severity>") + " " + truncate(...)`, still passed through the existing `truncate`/field limits (the label counts toward the limit). When `null`, output is exactly what it is today.
- `report.html`: same label in the leak card, via `#{analyze.severity.<severity>}` computed in Thymeleaf only when `severity` is not null (no hardcoded text, no JavaScript).
- `focus_area` is stored and serialized but not shown in this task.

### Part B: soul curve own-rank fallback
In `ReportService.computeSoulCurveData`:
1. Today: `telemetry.averageBadge() == null` returns empty. New: when `averageBadge()` is `null`, call `deadlockApiClient.getPlayerRank(stored.getAccountId())` (cached). If the call throws `RuntimeException`, log one WARN with the exception class (existing style) and return empty.
2. Use the result only when `badge() > 0` and `rank() >= 1`; `tier = Math.clamp(rank(), 1, 11)`. Otherwise return empty (unranked player: still no curve).
3. Mark the data as `ownRank = true` and pass it to `SoulCurveSvg.render`. When `averageBadge()` is present the behavior is exactly as today (`ownRank = false`).
4. Everything after the tier is unchanged (curve fetch, grid alignment, at least 3 points, `std > 0`).
5. The own rank is the player's **current** rank, not necessarily the rank at match time. The caption says so.
6. Both `find` (which sets `hasSoulCurve`) and `soulCurveSvg` go through the same method, so the page and the SVG route stay consistent. Do not call `getPlayerRank` when `averageBadge()` is present.

### New and changed messages (`messages.properties`, append at the end)
```
analyze.severity.major=🔴 Major:
analyze.severity.notable=🟠 Notable:
analyze.severity.minor=⚪ Minor:
report.curve.caption_own_rank={0} net worth vs {1} average at your current rank
```
Also change the values of the existing `report.curve.alt` to `Player net worth vs rank average over match time` and `report.curve.aria` to `Net worth curve comparing player to rank average` (they must be true for both lobby and own-rank curves). Check that `MessagesTest`/completeness tests still pass.

## Out of scope
- New coach providers or changes to any provider payload; `AnalysisInput` shape; a v2 JSON schema with lists of priorities (single leak stays); showing `focus_area` anywhere.
- Changing `/analyze` selection, usage limits, or stored-analysis reuse. Old stored analyses are not regenerated.
- Prompt tuning with real testers (task 010).
- Lobby average for non-ranked matches by any other means (for example `ranked_display_badge` from history); only the own-rank fallback above.
- Raising `max-tokens`, new caches, new endpoints, new migrations.

## Tests
- `AnalysisResultTest`: 4-argument constructor gives `null`/`null`; old JSON without the new keys parses; `focus_area` `" Deaths "` normalizes to `deaths`; `severity` `"critical"` becomes `null`; serializing a result with both `null` omits the keys; the stray-character cleanup still works.
- `AnalysisClientTest` (existing `MockRestServiceServer` style): a response with valid new fields is returned with them; a response without them still succeeds with `null`; an invalid `severity` still succeeds with `null`; a response missing a required old field is still `invalid_output`.
- `AnalysisPromptTest`: loads `/prompts/analysis-system.txt` and asserts it is under 4,000 characters, contains each of the five coach keys, the three severity words, `focus_area`, `severity`, and the words `significance` and `language`.
- `AnalyzeCommandTest`: `severity=major` prefixes the leak field with the `analyze.severity.major` text and stays within the field limit; `severity=null` is identical to the current output (existing assertions untouched).
- `ReportServiceTest` (Part B): lobby badge present -> `getPlayerRank` never called (`verify(..., never())`) and `ownRank=false`; badge `null` + ranked player -> curve built with the player's tier, `hasSoulCurve` true; badge `null` + `badge()==0` -> no curve; badge `null` + `getPlayerRank` throws -> no curve and no exception; `tier` clamped for `rank()` 12.
- `SoulCurveSvgTest`: `ownRank=true` caption contains the own-rank wording; the old 6-argument overload still produces the old caption; output stays escaped and script-free.
- A report page test (existing `ReportControllerTest` style) or `ReportServiceTest` assertion that a stored analysis without the new fields renders (old rows).

## Verification
Run the project's verification (`./mvnw verify`; on this Windows machine `.\mvnw.cmd verify`) and report baseline and final test counts. Manual check (state in the report that it was or was not run): `/analyze` on a fresh match shows the severity label and a leak consistent with the most significant coach entry; the report page of a non-ranked match now shows the curve with the own-rank caption; an older analysis still opens.

## Documentation
Create the task report `docs/task-reports/037-prompt-v2-priorities.md`. Do not edit `docs/PROJECT_CONTEXT.md` while other worktrees are active; list the facts to add at integration (prompt v2 in effect, `focus_area`/`severity` fields, own-rank fallback caption wording, current-rank caveat).

## Acceptance criteria
- [ ] Compiles, all tests pass (`./mvnw verify`)
- [ ] Unit tests for the service logic (Part B) and the result/client parsing (Part A)
- [ ] Stored analyses without `focus_area`/`severity` still deserialize and display unchanged
- [ ] No user-facing text hardcoded; keys added through `messages.properties`
- [ ] The prompt explains all five coach keys and `significance`, and stays under 4,000 characters
- [ ] `GET /r/{id}/soul-curve.svg` returns the SVG for a non-ranked match whose player has a rank, and still 404 for an unranked player
- [ ] No file outside the list above changed; follows AGENTS.md conventions
