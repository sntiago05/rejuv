# Task 049: Full report redesign and descriptive web charts

- **Branch**: `feature/049-report-redesign-charts`
- **Implementer**: Antigravity
- **Related ADRs**: ADR-0018 (zero JS, no external fonts or CDN, CSP, `th:text` only). ADR-0012 proposed client-side charts with a CDN library; ADR-0018 supersedes that in practice, so charts here are server-generated SVG.
- **Depends on**: 048 merged (persisted `input_json` and the content sections) and 047 merged (shared chart wording and colors). Must read both task reports first.

## Goal
Redesign the full report page so it is easy to understand at a glance and richer than the Discord embed, and add server-rendered SVG charts that explain themselves (what the line means, when it mattered, how you compare), without adding JavaScript.

## Why (verified)
- Today's page (`report.html`, `report.css`) is a header, four cards, a build list, one PNG and one SVG soul curve. After 048 it also has data sections, but plain and unvisualized.
- `SoulCurveSvg` (640x360, light/dark through `prefers-color-scheme` inside the SVG, empty under 3 points) is served from `GET /r/{publicId}/soul-curve.svg`; the PNG from `/chart.png`. Both are `<img>` sources, which is why the CSP `img-src 'self'` and `style-src 'self'` suffice. Palette: accent `#e5a93c`, strength `#4c9be8`, leak `#f28c28` (`report.css`).
- The Discord PNG and the web SVG use different golds; task 047 sets the shared meaning (gold = you, grey = teammates, blue/orange = sides).
- Constraints that must not move: the exact CSP string, `X-Robots-Tag`, `Referrer-Policy`, `Cache-Control`, no `<script`, no inline `style=` attributes, `th:text` only, UUID validation of `publicId` (36 chars), 404 template without echoing input.

## Files to create or modify
Read the 048 report and the current `ReportController`, `ReportService`, `report.html`, `report.css`, `SoulCurveSvg` first and confirm names.
| File | Change |
|---|---|
| `src/main/resources/templates/report.html` | New page structure (below). |
| `src/main/resources/static/report.css` | New styles: responsive layout, light and dark through `prefers-color-scheme`, print stylesheet. No external fonts; system font stack. |
| `src/main/java/sntiago05/rejuv/web/ReportController.java` | New image endpoints under `/r/{publicId}/` (names below), same validation, headers and 404 handling as the existing ones. |
| `src/main/java/sntiago05/rejuv/services/ReportChartsSvg.java` (new) | Renders the new SVGs from the 048 view model; one small private builder shared by all charts (no copy-paste of axis/colors code). May reuse helpers from `SoulCurveSvg` by extracting them into a package-private class instead of duplicating. |
| `src/main/java/sntiago05/rejuv/services/SoulCurveSvg.java` | Only to extract shared helpers or add the descriptive annotations below. |
| `src/main/java/sntiago05/rejuv/services/ReportService.java`, view record | Expose which charts are available (booleans like `hasSoulCurve`). |
| `src/main/resources/messages.properties` | New `report.*` keys. |
| Tests | `ReportChartsSvgTest` (new), `SoulCurveSvgTest`, `ReportControllerTest`, `ReportServiceTest`. |
| `docs/task-reports/049-report-redesign-charts.md` | New: task report. |

No other file. Do not edit migrations, the embed, `ChartRenderer`, the prompt, providers, security headers or the CSP string.

## Behavior

### Page structure (top to bottom)
1. **Hero banner**: hero name, result (Victory/Defeat), duration, rank, match id, analysis date.
2. **Verdict strip**: the biggest leak with its severity as a large, color-coded card next to the next-game action; the strength below. Same texts as today.
3. **Key numbers** as a compact comparison list with a small bar per metric (pure CSS widths from `class` names, not inline styles: use a fixed set of 10 width classes `w0..w100` mapped from a percentage rounded to the nearest 10) showing your value against p25/p50.
4. **Charts block** (below), each with a title, a one-sentence "how to read this" caption from `messages.properties`, and `alt` text that states the main takeaway computed in code.
5. **Detail sections** from 048 (metrics table, coach sections), restyled as collapsible `<details>` elements (native, no JS; first section open).
6. **Final build** with icons or names and buy minutes, as today.
7. Footer with analysis date and brand.
Mobile first: single column under 720 px, two columns above where useful; readable at 360 px width; `print` hides decoration and keeps all text.

### Charts (all SVG, served as `<img>`, each with light and dark styling inside the SVG, empty/404 when data is insufficient so the page omits the block)
1. **Soul lead over time** (existing curve extended): shaded areas for "your team ahead" / "enemy ahead", the largest swing annotated ("Biggest swing: -4.1k between 22 and 25 min"), phase bands (same boundaries as 047), and death markers when `input_json` provides deaths.
2. **You vs lobby average** (existing soul curve): keep, add direct end labels and the verdict ("above the average by 6%" computed from the last point).
3. **Phase scorecard** (`/r/{publicId}/phases.svg`): three horizontal bars (Laning, Mid, Late) of the player's net-worth z-change from the `phases` coach payload, centered at zero, labeled with plain words ("Better than average" / "Worse than average"); omitted without that payload.
4. **Deaths timeline** (`/r/{publicId}/deaths.svg`): a strip along the match minutes with one marker per death, sized or colored by respawn seconds, with the longest respawn labeled, phase bands behind; derives from the `deaths` coach payload fields available in 048 (`by_phase`, totals) and, when per-death times are not persisted, shows the three phase counts as bars instead. State which in the report; do not invent per-death times that are not stored.
5. **Metrics vs peers** (`/r/{publicId}/metrics.svg`): for each metric a range bar from p25 to p50 with your value as a dot, color by verdict. Metric names from existing `analyze.metric.*` keys.
Chart palette: gold = you, grey = reference/teammates, blue/orange = team sides, red/green only for worse/better verdicts, always with a text label so color is never the only signal (accessibility). Shared constants in one place.

### Security and performance
- All new endpoints validate `publicId` like the existing ones, return the same headers, `Cache-Control: private, max-age=300`, and 404 with no input echo.
- Escape every string put into an SVG (names, labels) and test it.
- SVG generation is CPU-only, no extra HTTP calls beyond what `ReportService.find` already does; each chart renders in O(points). Add a test that all five charts for a 40-minute match render under a generous time bound (for example 200 ms total on the test machine, asserted loosely) or note the measurement in the report instead of a flaky assertion.

## Out of scope
- JavaScript, external fonts, CDN charts, interactivity beyond `<details>`, the Discord embed and PNG (047), new stored data (per-death times are not persisted by 048; if wanted, that is a separate task), sharing/social cards, premium gating, localization of the page.

## Tests
- `ReportChartsSvgTest`: each chart returns well-formed SVG (parse with the JDK XML parser), contains the expected labels, handles empty/insufficient data by returning empty, escapes `<`, `&` and quotes in labels, light/dark rules present, no `<script`.
- `ReportControllerTest`: every new endpoint returns 200 with `image/svg+xml` and the exact existing security headers; 404 for invalid UUID and unknown id with no echo; the page contains no `<script`, no `style=` attribute and passes the existing CSP assertion unchanged; blocks for unavailable charts are omitted.
- `ReportServiceTest`: availability flags; old analyses (NULL `input_json`) keep the 048 behavior.
- CSS width classes mapping tested (percentage to class).
- Whole suite: `.\mvnw.cmd verify` (Docker Desktop running); report counts. Save a rendered page and SVGs under `target/` for the reviewer and describe them in the report (not committed).

## Documentation
Task report `docs/task-reports/049-report-redesign-charts.md` (status, files, commands, deviations, what is not stored (per-death times), manual checks not done: desktop, 360 px mobile, dark mode, print). Do not edit `docs/PROJECT_CONTEXT.md`; list under "Pending integration": page structure, new endpoints, palette, the `w0..w100` class convention.

## Acceptance criteria
- [ ] Compiles, all tests pass (`.\mvnw.cmd verify`)
- [ ] New structure and the five charts with captions and alt text; blocks omitted when data is missing
- [ ] Exact CSP and headers unchanged; no JavaScript, no inline `style=`
- [ ] Palette and meanings consistent with task 047
- [ ] Only the listed files changed; no new dependency or migration
