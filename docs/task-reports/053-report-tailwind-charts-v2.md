# Task 053: Web report on Tailwind CSS 4, tips card and better charts

## Status
Implemented on branch `feature/053-report-tailwind-charts-v2`. Full verification with `env -u ANTHROPIC_BASE_URL -u ANTHROPIC_CUSTOM_HEADERS .claude/skills/run-rejuv/driver.sh verify` passes (1108 tests run, 0 failures, 14 skipped; +14 new unit tests over baseline 1094). `scripts/build-css.sh --check` passes. Not pushed, not merged.

## Summary
Task 053 rebuilds the presentation layer of the public match analysis web report (`/r/{publicId}`) using Tailwind CSS v4, introduces the new "Top Tips" card, refines the five existing server-rendered SVG charts, and adds a new Build Timeline chart:

1. **Tailwind CSS v4 Standalone CLI Build Tooling (ADR-0020)**:
   - Configured `src/main/tailwind/report.css` using CSS-first Tailwind 4 configuration (`@import "tailwindcss";`, `@source "../resources/templates";`, `@source "../java";`, `@theme`).
   - Created `scripts/build-css.sh` targeting the pinned standalone CLI binary (`v4.3.3`) with deterministic SHA-256 verification on Linux `x86_64` (`dc61b3ac6b8c9ca874c0cc4c57b2409791a64c5540404ca5f5367360babc313a`) and `aarch64` (`55fd0b241214eff3de1e8ee4f22796662f2d2e7a49bcfca7477cfd0bac398195`).
   - The CLI compiles and minifies `src/main/resources/static/report.css` (~22.0 KB minified, well under the 40 KB budget).
   - Added `scripts/build-css.sh --check` step to `.github/workflows/ci.yml` immediately following repository checkout.
   - Preserves ADR-0018: zero Node.js/npm dependencies, no frontend build step during `./mvnw verify` or Docker image builds.

2. **Template Redesign (`report.html`, `report-not-found.html`)**:
   - Modernized responsive single/two-column mobile-first layout (readable down to 360 px width).
   - Clean dark and light theme token integration matching `prefers-color-scheme`.
   - Replaced all string-concatenated class names with literal class lookups (`ReportService.severityBadgeClass`, `ReportService.leakBorderClass`, `ReportService.verdictClass`, `ReportService.resultBadgeClass`).
   - Preserved collapsible native `<details>` elements for metrics table and coaching sections (zero JavaScript).
   - Preserved donation link support and print styling via `print:` utility rules.
   - Enforced strict template safety: zero `th:utext`, zero `<script>`, zero inline `style=`, zero external resources (`http(s)://`).

3. **Top Tips Card**:
   - Added `ReportView.Tip(String tip, String evidence, String drill)` and `ReportView.tips()`.
   - `ReportService.find()` extracts the `tips` array from the stored `result_json` tree (written by parallel Task 052) independently of `AnalysisResult`.
   - Caps tips at 3 items; drops items with empty or blank `tip`; gracefully ignores missing or malformed JSON payloads.
   - The card is rendered only when tips exist and is omitted cleanly for older analyses.

4. **Chart Visual Improvements & New Build Timeline**:
   - Unified palette tokens (`COLOR_YOU_GOLD = "#e5a93c"`, `COLOR_REF_GREY = "#9aa0a6"`, `COLOR_SIDE_BLUE = "#4c9be8"`, `COLOR_SIDE_ORANGE = "#f28c28"`, `COLOR_BETTER_GREEN = "#57f287"`, `COLOR_WORSE_RED = "#ed4245"`) across `ReportChartsSvg` and `SoulCurveSvg`.
   - Raised all SVG text elements to at least 12 px (no font-size under 12 px at 360 px viewport).
   - Added unit labels to gridlines (`min`, `k`).
   - Added new Build Timeline chart (`GET /r/{publicId}/build.svg`, `ReportView.hasBuildTimeline`): horizontal time axis with purchased items alternating above and below the timeline (capped at 12 earliest items, omitted when < 3 items).

## Important Files
| File | Changes |
|---|---|
| `docs/adr/0020-tailwind-build-time-css.md` | New ADR documenting Tailwind CSS v4 standalone CLI choice, rejected alternatives, and consequences. |
| `src/main/tailwind/report.css` | Tailwind v4 source stylesheet with theme tokens, width utilities `w0..w100`, badge and verdict classes, and print styles. |
| `scripts/build-css.sh` | Shell script to download pinned Tailwind v4 CLI, verify SHA-256, build minified `report.css`, and support `--check`. |
| `.gitignore` | Added `.tools/` standalone binary directory. |
| `src/main/resources/static/report.css` | Generated minified CSS asset (22,066 bytes). |
| `.github/workflows/ci.yml` | Added `scripts/build-css.sh --check` step after checkout. |
| `src/main/resources/templates/report.html` | Redesigned with Tailwind CSS 4 utilities, Top Tips card, Build Timeline card, and zero JS. |
| `src/main/resources/templates/report-not-found.html` | Restyled 404 report page with Tailwind utilities. |
| `src/main/java/sntiago05/rejuv/domain/ReportView.java` | Added `tips`, `hasBuildTimeline`, and `buildAlt` fields and `Tip` record; maintained backwards-compatible constructor. |
| `src/main/java/sntiago05/rejuv/services/ReportService.java` | Extracted tips from `result_json`, added `buildTimeline` availability check, added `buildSvg()` endpoint delegate, and added literal class mapping helpers. |
| `src/main/java/sntiago05/rejuv/services/ReportChartsSvg.java` | Enforced font-size >= 12 px, updated theme tokens to `#e5a93c`, added `renderBuildTimeline()` and `computeBuildAlt()`, and shared `SvgBuilder`. |
| `src/main/java/sntiago05/rejuv/services/SoulCurveSvg.java` | Reused shared `SvgBuilder` and palette constants from `ReportChartsSvg`, updated font sizes >= 12 px, and added unit labels. |
| `src/main/java/sntiago05/rejuv/web/ReportController.java` | Added `GET /r/{publicId}/build.svg` endpoint using existing `serveSvg()` helper. Security headers untouched. |
| `src/main/resources/messages.properties` | Appended user-facing strings for tips and build timeline chart (`report.tips`, `report.tip.*`, `report.chart.build.*`). |
| `src/test/java/sntiago05/rejuv/web/ReportTemplateSafetyTest.java` | New test: enforces zero `th:utext`, zero `<script>`, zero inline `style=`, zero event handlers, and zero external resources. |
| `src/test/java/sntiago05/rejuv/web/ReportControllerTest.java` | Added tests for `/r/{publicId}/build.svg` (200, 404, security headers) and Top Tips card rendering/omission. |
| `src/test/java/sntiago05/rejuv/services/ReportServiceTest.java` | Added tests for tips parsing (3 tips, cap 5 to 3, drop blanks, missing, malformed), build timeline rules, and CSS class name presence. |
| `src/test/java/sntiago05/rejuv/services/ReportChartsSvgTest.java` | Added tests for build timeline SVG (XML validity, 12-item cap, alternating rows, XML escaping, empty < 3), font size >= 12 px, and sample generation. |
| `src/test/java/sntiago05/rejuv/services/SoulCurveSvgTest.java` | Updated gold stroke assertion to `COLOR_YOU_GOLD` and added font size >= 12 px assertion. |
| `docs/task-reports/053-report-tailwind-charts-v2.md` | This task report. |

## Chart Improvements (Before vs After)

1. **Soul Lead Over Time (`/r/{publicId}/soul-lead.svg`)**:
   - *Before*: 10-11 px font sizes in side indicators, annotations, and phase texts; raw numeric X ticks without explicit unit.
   - *After*: All text elements >= 12 px; explicit minute units on ticks (`0 min`, `2 min`...); unified gold stroke `#e5a93c`; safe margins avoiding label clip at 360 px width.
2. **Net Worth vs Lobby Average (`/r/{publicId}/soul-curve.svg`)**:
   - *Before*: 11 px font sizes across axis labels and direct labels; duplicated XML styling.
   - *After*: Reused shared `SvgBuilder` and palette constants; all text >= 12 px; minute unit labels on X axis; gold player line stroke `#e5a93c`.
3. **Phase Scorecard (`/r/{publicId}/phases.svg`)**:
   - *Before*: 11 px verdict and phase labels; cramped row height on mobile.
   - *After*: 12 px typography throughout; improved row spacing; explicit arrows and high-contrast verdict labels.
4. **Deaths Timeline (`/r/{publicId}/deaths.svg`)**:
   - *Before*: 11 px summary header and count values.
   - *After*: 12 px typography; distinct bars with pill corners; clear contrast in both dark and light modes.
5. **Metrics vs Peers (`/r/{publicId}/metrics.svg`)**:
   - *Before*: 11 px metric row labels and verdict texts.
   - *After*: 12 px metric row labels and value/verdict texts; explicit verbal verdict text next to each indicator (never color alone).
6. **Build Timeline (`/r/{publicId}/build.svg`) [NEW]**:
   - Horizontal match-minute axis showing purchase points with alternating vertical callouts (even items above, odd items below) avoiding overlap; capped at 12 earliest items; XML-escaped item names; clean 404 fallback when < 3 items.

## Verification
- Pinned CLI and checksum verification:
  - Binary: Tailwind CSS v4.3.3 standalone CLI.
  - x86_64 SHA-256: `dc61b3ac6b8c9ca874c0cc4c57b2409791a64c5540404ca5f5367360babc313a`.
  - aarch64 SHA-256: `55fd0b241214eff3de1e8ee4f22796662f2d2e7a49bcfca7477cfd0bac398195`.
  - Command: `scripts/build-css.sh --check`
  - Output: `OK: .../src/main/resources/static/report.css is up to date.`
- Full build and test suite:
  - Command: `env -u ANTHROPIC_BASE_URL -u ANTHROPIC_CUSTOM_HEADERS .claude/skills/run-rejuv/driver.sh verify`
  - Output: `[INFO] BUILD SUCCESS`, `Tests run: 1108, Failures: 0, Errors: 0, Skipped: 14`.
- Safety assertions:
  - `ReportTemplateSafetyTest`: Verified zero `th:utext`, zero `<script>`, zero `style=`, zero event handlers, zero static external resource links across all templates.
  - `ReportControllerTest`: Verified byte-identical CSP (`default-src 'none'; img-src 'self'; style-src 'self'; base-uri 'none'; frame-ancestors 'none'`) and security headers across page and all 6 SVG endpoints (`soul-lead.svg`, `lead.svg`, `phases.svg`, `deaths.svg`, `metrics.svg`, `build.svg`).
  - Class name test: Asserted all mapped class names from `ReportService` exist in the generated `report.css`.
- Generated review artifacts in `target/samples/`:
  - Full rendered HTML: `target/samples/report.html`
  - Rendered charts: `target/samples/soul-lead.svg`, `target/samples/soul-curve.svg`, `target/samples/phases.svg`, `target/samples/deaths.svg`, `target/samples/metrics.svg`, `target/samples/build.svg`.

## Decisions
- Used Tailwind CSS v4 standalone CLI with pinned version and SHA-256 validation. Generated minified CSS is committed to repository to keep `./mvnw verify` and Docker packaging toolchain-free.
- Kept SVG charts as standalone endpoints served as `<img>` tags to preserve strict CSP and eliminate the need for `th:utext`.
- Unified palette tokens with `ReportChartsSvg.COLOR_YOU_GOLD = "#e5a93c"` per ADR-0020 and Task 047/049.

## Deviations
None. All requirements in `docs/tasks/053-report-tailwind-charts-v2.md` and `AGENTS.md` were implemented exactly as specified.

## Known Issues
None.

## Merge Risks
None. Task 053 touches only the report web presentation and SVG charts. Parallel Task 052 touches `messages.properties` (append only, simple textual merge).

## Project Memory and Shared Context
Parallel task worktrees (`049`, `050`, `051`, `052`, `053`) are active simultaneously. Per `AGENTS.md` and `execute-task` guidelines:
**`docs/PROJECT_CONTEXT.md` update pending integration.**

## Manual Testing Steps (For Reviewer)
Do **not** edit `docs/manual-testing.md` (Task 052 appends there). Reviewers can verify runtime behavior locally using the driver:
1. Start PostgreSQL and boot application:
   ```bash
   .claude/skills/run-rejuv/driver.sh boot
   ```
2. Inspect rendered static artifacts:
   ```bash
   ls -la target/samples/
   ```
   Open `target/samples/report.html` in a web browser to verify layout at 360 px, 720 px, 1280 px, in light and dark mode, and browser print preview.
3. Test CSS check:
   ```bash
   scripts/build-css.sh --check
   ```
