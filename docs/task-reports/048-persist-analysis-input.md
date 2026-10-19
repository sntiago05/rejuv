# Task 048: Persist the analysis input and show it on the full report

## Status
Implemented on branch `feature/048-persist-analysis-input`. `.\mvnw.cmd verify` passes (1048 tests run, 0 failures, 14 skipped; +12 new unit tests over baseline 1036). Not pushed, not merged.

## Summary
Task 048 persists the exact `AnalysisInput` that Claude Haiku analyzed and presents detailed match metrics and coaching insights on the web report page (`/r/{publicId}`):
1. **Persistence & Flyway Migration V5 (`V5__match_analysis_input_json.sql`, `MatchAnalysis.java`, `AnalyzeService.java`)**:
   - Added Flyway migration V5: `ALTER TABLE match_analysis ADD COLUMN input_json TEXT;` (nullable, no default).
   - Added nullable `inputJson` field, constructor, and getter to `MatchAnalysis` JPA entity. Backward-compatible constructors preserved.
   - `AnalyzeService` serializes the exact `AnalysisInput` passed to `AnalysisClient.analyze()` and stores it in `MatchAnalysis.inputJson` within the same persistence flow.
   - Guarded by size cap: if serialized input exceeds `MAX_INPUT_JSON_CHARS = 20000`, stores `NULL` and logs one `WARN` with the length.
   - Degraded and fallback analyses that are not stored remain unstored.
2. **Report View Model & Tolerant Parsing (`ReportService.java`, `ReportView.java`)**:
   - `ReportService.find` reads `stored.getInputJson()` and parses it into a tolerant Jackson tree (`JsonNode`) using Jackson 3.x property traversal.
   - Unknown or missing fields never fail; corrupted JSON logs one `WARN` and renders the page with `hasDetailedSections = false` without throwing a 500 error.
   - Extracts and populates `hero`, `rank`, `result`, and `durationMin` into `ReportView`.
3. **Metrics Table & Peer Benchmarks (`ReportService.java`, `ReportView.MetricRow`)**:
   - Parses `metrics` object into `ReportView.MetricRow` with `key`, `labelKey` (`analyze.metric.*`), formatted `yourValue`, `p25`, `p50`, and plain-text `verdict`.
   - `ReportService.computeVerdict`: calculates `"below p25"`, `"between p25 and p50"`, or `"above p50"`. Handles lower-is-better metrics (`deaths`, `deaths_per_10_min`, `first_permanent_buff_min`, and timing metrics) where higher values mean worse performance (flipped peer standing). Returns `"-"` if benchmarks are missing.
   - Numbers formatted with `Locale.ROOT` without locale grouping surprises; percentages formatted with one decimal.
4. **Coach Sections & Other Signals (`ReportService.java`, `ReportView.CoachSection`, `ReportView.OtherSignal`)**:
   - Renders coach payloads in the fixed order: `phases, deaths, objectives, aim_mechanics, build, recent_form, matchup`.
   - Each coach block exposes its significance (`major`, `notable`, `minor`, `none`) and its fields as a definition list (`<dl><dt><dd>`).
   - Unknown future coach keys render under a generic "Other signals" card with scalar values.
5. **Legacy Rows & Notice**:
   - Existing analyses (`input_json = NULL`) render identically to before, accompanied by a one-line notice explaining that detailed sections are available for newer analyses.
6. **Web Presentation & Security (`report.html`, `report.css`, `messages.properties`)**:
   - Clean, responsive HTML5/CSS3 presentation without JavaScript.
   - Security headers and CSP preserved exactly: `default-src 'none'; img-src 'self'; style-src 'self'; base-uri 'none'; frame-ancestors 'none'`.
   - All user data and coach values rendered via Thymeleaf `th:text` with automatic HTML escaping; verified by tests that `<script>` and HTML tags in coach payloads cannot execute.
   - Added all headings, labels, and notices to `src/main/resources/messages.properties`.

## Files Created or Modified
| File | Changes |
|---|---|
| `src/main/resources/db/migration/V5__match_analysis_input_json.sql` | Added V5 migration adding nullable `input_json TEXT` column to `match_analysis`. |
| `src/main/java/sntiago05/rejuv/domain/MatchAnalysis.java` | Added `inputJson` field, getter, and overloaded constructors. |
| `src/main/java/sntiago05/rejuv/domain/ReportView.java` | Added `hasDetailedSections`, `metricsTable`, `coachSections`, `otherSignals` and supporting records `MetricRow`, `CoachField`, `CoachSection`, `OtherSignal`. |
| `src/main/java/sntiago05/rejuv/services/AnalyzeService.java` | Serialized `AnalysisInput` with `MAX_INPUT_JSON_CHARS = 20000` cap and stored in `MatchAnalysis`. |
| `src/main/java/sntiago05/rejuv/services/ReportService.java` | Parsed `input_json` into view model, implemented `computeVerdict`, `formatNumber`, and coach field extraction. |
| `src/main/resources/templates/report.html` | Rendered Match at a glance, Your numbers vs peers, coach sections, other signals, and older analysis notice. |
| `src/main/resources/static/report.css` | Styled glance items, metrics table, coach section cards, significance badges, and notice box. |
| `src/main/resources/messages.properties` | Added `report.glance.*`, `report.metrics.*`, `report.coach.*`, `report.significance.*`, and `report.older_analysis_note` keys. |
| `src/test/java/sntiago05/rejuv/FlywayMigrationTest.java` | Added tests verifying V5 migration applied successfully, nullable column, and insert operations. |
| `src/test/java/sntiago05/rejuv/repositories/MatchAnalysisRepositoryTest.java` | Added test saving and reading back entity with `input_json`. |
| `src/test/java/sntiago05/rejuv/services/AnalyzeServiceTest.java` | Asserted saved `input_json` equals serialized input, contains no secrets/tokens, and oversized inputs store null and log warning. |
| `src/test/java/sntiago05/rejuv/services/ReportServiceTest.java` | Added tests for `find` with `input_json`, corrupt JSON handling, and all `computeVerdict` higher/lower-is-better boundary cases. |
| `src/test/java/sntiago05/rejuv/web/ReportControllerTest.java` | Added tests verifying detailed sections rendering, older analysis notice, and HTML escaping of coach payloads. |
| `docs/task-reports/048-persist-analysis-input.md` | This task report. |

No other files were touched.

## Storage Growth Estimate
- Measured size of realistic `AnalysisInput` JSON:
  - A typical match input with 12 benchmarked metrics, 3 item timings, and 2-3 coach sections (e.g. phases and deaths) serializes to approximately 1.2 KB to 1.8 KB.
  - A full match input containing all coach sections (`phases`, `deaths`, `objectives`, `build`, `recent_form`, `matchup`) with multiple checkpoints and phase breakdowns serializes to approximately 2.5 KB to 3.5 KB.
  - Storage is capped at `MAX_INPUT_JSON_CHARS = 20000` (~20 KB), ensuring pathological payloads are discarded.
  - At an average of ~2.5 KB per analysis row, 1,000 analyses require approximately 2.5 MB of database storage (inline or TOAST-compressed in PostgreSQL), representing negligible overhead.

## Privacy Note
- `input_json` stores only match statistics, percentile benchmarks, item buy times, and derived coaching heuristics that were submitted to the AI model.
- It contains no authentication tokens, API keys, passwords, webhook URLs, user personal data, or private Discord messages.
- A unit test in `AnalyzeServiceTest` verifies that the serialized `input_json` does not contain any `"token"`, `"key"`, or `"secret"` substrings.

## Verification
- Targeted unit tests: `.\mvnw.cmd test "-Dtest=AnalyzeServiceTest,ReportServiceTest,ReportControllerTest,FlywayMigrationTest,MatchAnalysisRepositoryTest"`: BUILD SUCCESS (86 tests run, 0 failures, 0 skipped; 26.594 s).
- Full test suite: `.\mvnw.cmd verify`: BUILD SUCCESS (1048 tests run [+12 new unit tests], 0 failures, 14 skipped; 35.973 s).
- Git diff inspection: Exactly 13 files modified/added; only the files authorized by the task spec were touched.
- Security assertions: All CSP headers and "no `<script`" assertions pass.

## Deviations
None.

## Known Issues / Blockers
None.

## Pending Manual Checks
- Manual browser inspection of `/r/{publicId}` for visual verification of layout across mobile (360px) and desktop (>720px) viewport widths.

## Pending Integration
Per parallel worktree rules, `docs/PROJECT_CONTEXT.md` was not modified directly.
Facts to integrate upon merge:
- Database schema: Flyway migration `V5__match_analysis_input_json.sql` adds nullable `input_json TEXT` column to `match_analysis`.
- Persistence: `AnalyzeService` saves the exact `AnalysisInput` serialized to JSON in `match_analysis.input_json` (size capped at 20,000 characters; logs warning and stores null if exceeded; degraded analyses remain unstored).
- Web Report: `ReportService.find` tolerantly parses `input_json` into `ReportView`, populating:
  1. Match at a glance (hero, rank, result, duration).
  2. Your numbers vs peers table with benchmarks and `computeVerdict` helper (supports lower-is-better metrics where higher is worse).
  3. Coach sections in fixed order (`phases, deaths, objectives, aim_mechanics, build, recent_form, matchup`), each with significance badge and labeled definition list.
  4. Other signals card for unknown future coach keys.
- Backward compatibility: Old analyses (`input_json = NULL`) preserve existing layout and display a one-line notice that detailed sections are available on newer analyses.
- Precursor for Task 049: Persisted `input_json` and data sections provide the data model for the redesigned report layout and descriptive SVG charts in task 049.
