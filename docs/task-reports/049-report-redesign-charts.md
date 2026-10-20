# Task 049: Full report redesign and descriptive web charts

## Status
Implemented on branch `feature/049-report-redesign-charts`. Verification with `env -u ANTHROPIC_BASE_URL -u ANTHROPIC_CUSTOM_HEADERS ./mvnw verify` passes (1105 tests run, 0 failures, 14 skipped; +22 new unit tests over baseline 1083). Not pushed, not merged.

## Review fixes
Following code review feedback, the following refinements were implemented:
1. **[Blocker] Elimination of hardcoded user-facing strings**:
   - Replaced all raw English strings in `ReportChartsSvg.java` and `SoulCurveSvg.java` (SVG titles, biggest swing annotation, deaths breakdown label, phase labels, verdict badges, and alt texts) with `report.chart.*` and `report.curve.*` keys in `src/main/resources/messages.properties`.
   - Converted `computeSoulLeadAlt`, `computePhasesAlt`, `computeDeathsAlt`, `computeMetricsAlt`, and `computeSoulCurveAlt` to instance methods backed by `Messages`.
   - Formatted numbers as strings prior to passing into `Messages.get(...)` to prevent `MessageFormat` from applying unexpected digit groupings or locale-specific comma formatting.
   - Verified that `report.chart.phases.better`, `report.chart.phases.worse`, and `report.chart.phases.average` are actively utilized in both SVG rendering and alt text generation.
2. **[Should fix] JSON parsing de-duplication in `ReportService`**:
   - Extracted `parseCoachPhases(JsonNode coachNode)` returning `Optional<List<PhaseScore>>` and `parseCoachDeaths(JsonNode coachNode)` returning `Optional<CoachDeathsData>`.
   - Reused these private helpers across `find()`, `phasesSvg()`, and `deathsSvg()`, completely removing duplicate Jackson traversal code.
3. **[Should fix] Constructor cleanup**:
   - Removed the legacy 7-argument `ReportService` constructor that passed `reportChartsSvg = null`, removed `@Autowired` on the single canonical constructor, and removed obsolete `null` checks on `reportChartsSvg`.
   - Removed legacy 11-, 12-, and 16-argument constructors from `ReportView` and the 6-argument constructor from `ReportView.MetricRow`.
   - Added test factory helpers in `ReportControllerTest.java` (`createReportView`, `createBasicReport`, `createCurveReport`, `createDetailedReport`) to keep tests clean and maintainable with canonical record construction.

## Summary
Task 049 completely redesigns the web match analysis report (`/r/{publicId}`) and adds server-rendered, self-explanatory SVG charts without adding any client-side JavaScript or external dependencies, adhering strictly to ADR-0018:

1. **Page Structure & Responsive Layout (`report.html`, `report.css`)**:
   - **Hero Banner**: Displays hero name, match outcome badge (Victory / Defeat), duration in minutes, player rank badge, match ID, and analysis timestamp.
   - **Verdict Strip**: Side-by-side cards highlighting the Biggest Leak (with severity badge) and Next Game Focus, with Key Strength positioned directly beneath.
   - **Key Numbers**: A compact benchmark comparison list featuring small horizontal metric bars using pure CSS width classes (`w0..w100` rounded to nearest 10% from `(value / (2 * p50)) * 100`). Zero inline `style=` attributes are used, maintaining full CSP compliance.
   - **Charts Block**: Up to 5 SVG charts rendered as standard `<img>` tags with localized captions and code-computed descriptive `alt` texts summarizing key takeaways. If underlying telemetry or benchmark data is missing, the corresponding chart card is cleanly omitted.
   - **Detail Sections**: Restyled as native HTML5 collapsible `<details>` elements (zero JavaScript). The "Your Numbers vs Peers" metrics table is first and open by default (`open`), followed by individual Coach sections (`phases`, `deaths`, `objectives`, `aim_mechanics`, `build`, `recent_form`, `matchup`) with significance badges, and an "Other Signals" card.
   - **Final Build**: Purchased items list with minute timings and soul costs.
   - **Footer**: Analysis date and Rejuv brand.
   - **Mobile First & Print**: Single-column below 720px, fully readable down to 360px width. Print stylesheet hides background decorations, navigation chrome, and preserves high-contrast text.

2. **Server-Rendered SVG Charts (`ReportChartsSvg.java`, `SoulCurveSvg.java`)**:
   - Built via a dedicated `SvgBuilder` ensuring well-formed XML parsable by standard XML parsers (`DocumentBuilderFactory`), proper XML entity escaping, and system font typography.
   - Includes embedded CSS with dark mode defaults and `@media (prefers-color-scheme: light)` rules so charts seamlessly switch themes when embedded via `<img>`.
   - **Palette consistency with Task 047**:
     - Gold (`#ffc83d`): Player / You.
     - Neutral grey (`#9aa0a6` / `#4e5058`): Reference, benchmarks, teammates.
     - Blue (`#4c9be8`) / Orange (`#f28c28`): Team sides (ahead vs behind).
     - Green (`#57f287` / `#16a34a`) / Red (`#ed4245` / `#dc2626`): Better vs worse verdicts, always paired with explicit text labels.
   - Five distinct charts:
     1. **Soul lead over time** (`/r/{publicId}/soul-lead.svg`, alias `/r/{publicId}/lead.svg`): Shaded area plots for team lead/deficit, zero-axis guideline, phase background bands (laning 0-12m, mid 12-20m, late 20m+), player death markers, and annotation of the single biggest swing.
     2. **Net worth vs lobby average** (`/r/{publicId}/soul-curve.svg`): Direct end labels ("You", "Average"), standard deviation band, and a verdict annotation stating final net worth difference ("above the average by X%").
     3. **Phase scorecard** (`/r/{publicId}/phases.svg`): Centered zero-line horizontal bars depicting net worth momentum z-change across Laning, Mid, and Late game, labeled with plain words ("Better than average", "Worse than average", "Average").
     4. **Deaths breakdown** (`/r/{publicId}/deaths.svg`): Horizontal bars showing deaths across laning, mid, and late game, total deaths, and annotated longest respawn time.
     5. **Metrics vs peers** (`/r/{publicId}/metrics.svg`): Horizontal range tracks with p25 to p50 median bands and player standing dots color-coded by verdict.

3. **Endpoints & Security (`ReportController.java`, `ReportService.java`)**:
   - Added endpoints:
     - `GET /r/{publicId}/soul-lead.svg` (and alias `GET /r/{publicId}/lead.svg`)
     - `GET /r/{publicId}/phases.svg`
     - `GET /r/{publicId}/deaths.svg`
     - `GET /r/{publicId}/metrics.svg`
   - All endpoints enforce UUID format validation, return exact security headers (`Content-Security-Policy: default-src 'none'; img-src 'self'; style-src 'self'; base-uri 'none'; frame-ancestors 'none'`, `X-Robots-Tag: noindex, nofollow`, `Referrer-Policy: no-referrer`, `X-Content-Type-Options: nosniff`, `Cache-Control: private, max-age=300`), and return 404 with an empty body without echoing invalid UUID inputs.
   - Pure CPU-only generation: Chart rendering takes ~0.06 ms per chart (~0.3 ms for all charts combined), well within the 200 ms performance bound.

## Files Created or Modified
| File | Changes |
|---|---|
| `src/main/java/sntiago05/rejuv/services/ReportChartsSvg.java` | New class: renders Soul Lead, Phase scorecard, Deaths breakdown, and Metrics vs peers SVG charts with dark/light themes, XML escaping, localized user-facing text, and takeaway alt text computation. |
| `src/main/java/sntiago05/rejuv/services/SoulCurveSvg.java` | Updated to unify palette with task 047 (`#ffc83d`), added direct end labels ("You", "Average"), verdict annotation, localized strings, and `computeSoulCurveAlt`. |
| `src/main/java/sntiago05/rejuv/domain/ReportView.java` | Added chart availability booleans (`hasSoulLead`, `hasPhasesChart`, `hasDeathsChart`, `hasMetricsChart`), `*Alt` text fields, and `MetricRow.widthClass`. Removed obsolete legacy constructors. |
| `src/main/java/sntiago05/rejuv/services/ReportService.java` | Single constructor injecting `ReportChartsSvg` without `@Autowired`, de-duplicated coach phases/deaths JSON parsing into private helpers, exposed SVG rendering methods, added `mapWidthClass`, and computed chart booleans/alt texts in `find()`. |
| `src/main/java/sntiago05/rejuv/web/ReportController.java` | Added SVG chart endpoints (`/soul-lead.svg`, `/lead.svg`, `/phases.svg`, `/deaths.svg`, `/metrics.svg`) with security headers and UUID validation. |
| `src/main/resources/messages.properties` | Added chart titles, captions, phase verdicts, swing labels, deaths breakdown labels, metrics comparison labels, and alt texts (`report.chart.*`, `report.curve.*`). |
| `src/main/resources/templates/report.html` | Redesigned layout with hero banner, verdict strip, key numbers with CSS bar width classes, SVG charts block, collapsible `<details>` detail sections, final build, and footer. |
| `src/main/resources/static/report.css` | Comprehensive redesign: responsive CSS grid/flexbox, system typography, width classes `w0..w100`, dark/light theme tokens, and print stylesheet. |
| `src/test/java/sntiago05/rejuv/services/ReportChartsSvgTest.java` | New test class: well-formed XML parsing, label escaping, empty data handling, localized alt text verification, CPU performance bound (<200 ms), and sample SVG export. |
| `src/test/java/sntiago05/rejuv/services/SoulCurveSvgTest.java` | Added tests for direct labels, verdict annotation, gold stroke palette, and `computeSoulCurveAlt`. |
| `src/test/java/sntiago05/rejuv/services/ReportServiceTest.java` | Added tests for `mapWidthClass` rounding, chart SVG delegation, and chart view model properties. |
| `src/test/java/sntiago05/rejuv/web/ReportControllerTest.java` | Added tests for all SVG endpoints (200, 404, security headers, no input echo), verified zero inline styles and zero scripts on report page, exported sample `report.html`, and updated with test factory helpers. |
| `docs/task-reports/049-report-redesign-charts.md` | This task report with review fixes section. |

No other files were touched.

## Generated Review Artifacts
Sample rendered report and SVGs have been saved to `target/samples/` (git-ignored) for visual review:
- `target/samples/report.html`: Full rendered report page with hero banner, verdict strip, key numbers, chart images, collapsible details, and footer.
- `target/samples/soul-lead.svg`: Lead over time chart with phase bands, death markers, and swing annotations.
- `target/samples/soul-curve.svg`: Soul curve with direct labels, standard deviation band, and verdict.
- `target/samples/phases.svg`: Phase scorecard with horizontal bars centered at zero.
- `target/samples/deaths.svg`: Deaths breakdown by phase with longest respawn annotation.
- `target/samples/metrics.svg`: Metrics vs peers with p25/p50 bands and color-coded dots.

## Verification
- Targeted unit tests:
  - `ReportChartsSvgTest`: 12 tests, 0 failures.
  - `SoulCurveSvgTest`: 13 tests, 0 failures.
  - `ReportServiceTest`: 25 tests, 0 failures.
  - `ReportControllerTest`: 19 tests, 0 failures.
- Full verification suite:
  - Command: `env -u ANTHROPIC_BASE_URL -u ANTHROPIC_CUSTOM_HEADERS ./mvnw verify`
  - Result: `BUILD SUCCESS` (1104 tests run, 0 failures, 14 skipped; 26.943 s).
- Security & CSP assertions:
  - Exact CSP header string maintained: `default-src 'none'; img-src 'self'; style-src 'self'; base-uri 'none'; frame-ancestors 'none'`.
  - Asserted zero `<script` occurrences and zero `style=` inline attributes across rendered HTML.
  - All SVG endpoints return identical security headers and 404 with empty body without echoing malformed input.

## Deviations
None.

## What is Not Stored / Known Limitations
- Exact per-death timestamps are not stored in `input_json` by Task 048. As specified in the task description, the deaths chart renders the three phase counts (`by_phase`) as bars along with total deaths and longest respawn time; per-death timestamps were not invented.
- Manual browser checks not completed in this headless environment:
  - Desktop layout check in browser.
  - 360 px mobile viewport inspection.
  - System dark mode switching (`prefers-color-scheme`).
  - Print preview layout inspection.

## Pending Integration
Per task spec instructions, `docs/PROJECT_CONTEXT.md` was not modified directly. Facts to integrate upon merge:
- **Page structure**: Redesigned report layout at `/r/{publicId}` featuring Hero banner, Verdict strip, Key numbers with pure CSS width classes (`w0..w100`), Charts block, collapsible `<details>` detail sections (metrics table first & open, coach sections, other signals), Final build, and Footer.
- **New endpoints**:
  - `GET /r/{publicId}/soul-lead.svg` (and alias `GET /r/{publicId}/lead.svg`)
  - `GET /r/{publicId}/phases.svg`
  - `GET /r/{publicId}/deaths.svg`
  - `GET /r/{publicId}/metrics.svg`
- **Palette consistency**: Standardized palette aligned with Task 047 (gold `#ffc83d` for you/player, grey `#9aa0a6` for benchmarks/reference, blue `#4c9be8` / orange `#f28c28` for teams, green `#57f287` / red `#ed4245` for verdicts).
- **CSS width convention**: Pure CSS bar widths via discrete classes `w0..w100` mapped from percentage rounded to the nearest 10, completely avoiding inline styles.
