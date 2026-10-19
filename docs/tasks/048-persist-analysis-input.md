# Task 048: Persist the analysis input and show it on the full report

- **Branch**: `feature/048-persist-analysis-input`
- **Implementer**: Antigravity
- **Related ADRs**: ADR-0018 (web report stack: Thymeleaf, zero JS, security headers), ADR-0011
- **Depends on**: 040, 043, 044, 045 and 046 merged (045 is not required, but its coach key will simply appear when present). Task 049 depends on this one.
- **Flyway**: this task adds migration `V5__match_analysis_input_json.sql`. Before starting, check open branches for a clashing V5 and stop and ask if one exists.

## Goal
Today the full report only shows the four AI texts, the final build and two charts, because only the `AnalysisResult` JSON is stored. Persist the data the model saw so the page can show **much more than the Discord embed**: the metrics against their benchmarks and every coach section (phases, deaths, objectives, aim, build, recent form, matchup). This task adds the storage and the content sections with plain HTML/CSS. The visual redesign and new charts are task 049.

## Why (verified)
- `match_analysis` stores `result_json` (TEXT) and `public_id` (V4). `AnalyzeService` persists only the serialized `AnalysisResult` (strength, biggest_leak, next_game_action, key_numbers, focus_area, severity).
- Not stored: `AnalysisInput` (metrics with p25/p50, items, coach map, language), rank, result, duration. `ReportService.find` therefore passes `rank = null` and `result = null` to the view and re-fetches metadata live for the build and charts.
- The coach payloads exist only at analysis time (`AnalysisInput.coach`, built by `CoachContextService`, which drops `none` entries).
- `ReportController` sets CSP `default-src 'none'; img-src 'self'; style-src 'self'; base-uri 'none'; frame-ancestors 'none'`; tests assert it exactly, and that the page contains no `<script`. Templates use `th:text` only.

## Files to create or modify
Read `AnalyzeService`, `AnalysisInput`, `MatchAnalysis`, `MatchAnalysisRepository`, `ReportService`, `ReportController`, `report.html` and `report.css` first and confirm names.
| File | Change |
|---|---|
| `src/main/resources/db/migration/V5__match_analysis_input_json.sql` | `ALTER TABLE match_analysis ADD COLUMN input_json TEXT;` (nullable, no default). |
| `src/main/java/sntiago05/rejuv/domain/MatchAnalysis.java` | New nullable `inputJson` property (constructor-compatible with current callers). |
| `src/main/java/sntiago05/rejuv/services/AnalyzeService.java` | Serialize the `AnalysisInput` that was sent to the model and store it with the result (same transaction/save as today). |
| `src/main/java/sntiago05/rejuv/services/ReportService.java` | Parse `input_json` when present into a view model (below); stay unchanged when absent. |
| `src/main/java/sntiago05/rejuv/domain/ReportView.java` (or the existing view record) | Add the optional sections. |
| `src/main/resources/templates/report.html`, `src/main/resources/static/report.css` | Render the new sections. |
| `src/main/resources/messages.properties` | New `report.*` keys for headings and labels. |
| Tests | `AnalyzeServiceTest` (stores the input), `ReportServiceTest`, `ReportControllerTest`, a repository/Flyway test that runs V5 on Testcontainers. |
| `docs/task-reports/048-persist-analysis-input.md` | New: task report. |

No other file. Do not edit earlier migrations, the prompt, providers, `ChartRenderer`, `SoulCurveSvg`, the embed, security headers or the CSP.

## Behavior

### Storage
- On a successful AI analysis, store `input_json` = the JSON of the exact `AnalysisInput` sent to the model. The input must contain no secrets (it holds hero, rank, result, metrics, items, language, coach); assert this in a test by checking the serialized string has no `token`/`key`/`secret` substring.
- Also store, inside `input_json`, nothing the model did not see. Degraded or fallback analyses that are not stored today stay unstored.
- Existing rows keep `input_json = NULL`; reuse by `findByAccountIdAndMatchId` is unchanged. Maximum stored size: if the JSON exceeds `MAX_INPUT_JSON_CHARS = 20000`, store NULL and log one WARN line with the length (no payload).

### Report view model
- When `input_json` is present, parse it with the existing Jackson mapper into a tolerant tree (unknown or missing fields never fail; a parse error logs one WARN and renders the page as if `input_json` were null).
- Sections shown, each only when its data exists, in this order after the existing summary cards:
  1. **Match at a glance**: hero, rank, result, duration (now available instead of the current nulls).
  2. **Your numbers vs peers**: a table of every metric with your value, p25, p50, and a plain-text verdict ("below p25", "between p25 and p50", "above p50") computed in code by one helper with unit tests; lower-is-better metrics are handled the same way the AI prompt describes (deaths and timings: higher is worse).
  3. **Coach sections**, one block per present coach key in the fixed order `phases, deaths, objectives, aim_mechanics, build, recent_form, matchup`, each with its significance label and its fields as a labeled definition list (label keys in `messages.properties`, values via `th:text`). Unknown future coach keys render under a generic "Other signals" block with key and scalar values only.
- Old analyses (NULL) show exactly what the page shows today; a one-line note says that detailed sections are only available for newer analyses.
- Numbers are formatted without locale grouping surprises; percentages show one decimal.

### Security
- Keep every header and the exact CSP. No JavaScript, no inline `style=`, no new image source. All values reach the page through `th:text`; add a test with `<script>` and HTML in a coach string proving it is escaped.

## Out of scope
- New charts or SVG endpoints, layout redesign, the embed, the AI prompt, deleting or backfilling old rows, premium gating, public listing of reports.

## Tests
- Migration V5 applies on an empty and on a populated database (Testcontainers); nullable column.
- `AnalyzeServiceTest`: the stored `input_json` equals the serialized input; the over-size case stores NULL; degraded analyses are not stored.
- `ReportServiceTest`/`ReportControllerTest`: page with `input_json` shows each section and the verdict helper cases (below p25, equal to p25, between, equal to p50, above, lower-is-better flipped); page without it renders as before; corrupted JSON renders without 500; escaping test; the exact CSP and "no `<script`" assertions still pass.
- Whole suite: `.\mvnw.cmd verify` (Docker Desktop running); report counts.

## Documentation
Task report `docs/task-reports/048-persist-analysis-input.md` (status, files, commands, deviations, storage growth estimate per analysis in KB measured on a realistic input, privacy note, manual checks not done). Do not edit `docs/PROJECT_CONTEXT.md`; list under "Pending integration": V5 and `input_json`, sections and order, the verdict helper, size cap, old rows behavior.

## Acceptance criteria
- [ ] Compiles, all tests pass (`.\mvnw.cmd verify`)
- [ ] V5 migration added, no earlier migration edited
- [ ] Report shows metrics table and all present coach sections for new analyses and is unchanged for old ones
- [ ] CSP and security-header tests unchanged and green; no JavaScript
- [ ] Only the listed files changed; no new dependency
