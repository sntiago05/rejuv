# Task 055: Match scoreboard, readable charts, readable analysis format and a Deadlock-style Tailwind report

## Status
Implemented on branch `feature/055-report-scoreboard-readability`. `env -u ANTHROPIC_BASE_URL -u ANTHROPIC_CUSTOM_HEADERS -u ANTHROPIC_API_KEY ./mvnw verify` passes: 1164 tests, 0 failures, 14 skipped. `scripts/build-css.sh --check` passes (run twice, same result). Not pushed, not merged. `docs/PROJECT_CONTEXT.md` and `docs/manual-testing.md` are **pending integration** (tasks 056 and 057 run in parallel); the manual steps are below.

## Summary
1. **Scoreboard** (web report): both teams, viewer's team first, rows sorted by damage. Each row: hero icon and name, player name (or `Player N`), `You` tag, K/D/A, KDA, creeps, damage with a bar, final items as icons. `ReportScoreboardBuilder` is pure (one pass); `ReportService` adds one cached Steam-name lookup (`getPlayerNames`, failure means no names) and never throws.
2. **Icons from our own origin**: `GET /r/{id}/img/hero/{heroId}.png` and `/img/item/{itemId}.png` (the CSP `img-src 'self'` is unchanged). Served only for ids that appear in that match (scope cached 10 min per report, so a page of about 150 icons does not hit the database 150 times). `ReportIconService` downloads through the existing `AssetImageClient` allow-list, keeps bytes in a bounded Caffeine cache (600 icons, 6 h), and only returns real PNG, JPEG, WebP or SVG (checked by the first bytes). Failures are 404, never cached.
3. **Charts redrawn for phones**: the old charts were 640 units wide with 12-unit text, so on a 328 px card the text was about 6 px. They are now 480 units wide with 17-unit text (about 11 to 12 px on a phone, bigger on desktop), thicker lines, at most 6 time ticks, `Xm` unit labels, no label on top of a line, no label running past the plot. Phase and death charts put name and verdict on one line above each bar; metrics put label and value above the track; the build timeline is a vertical list (names never overlap). The "your team ahead / enemy ahead" key moved under the axis. The long net-worth caption moved into the SVG `<desc>`.
4. **Web analysis format**: order is verdict (biggest leak, next game, strength), tips, scoreboard, key numbers, charts, details, final build. One `h1`, landmarks, skip link, visible focus ring, `prefers-reduced-motion`, 16 px body, 70-character reading width, `lang`, `color-scheme`, a visible one-sentence takeaway above each chart (`aria-hidden`, the image `alt` carries the same text), print styles kept. Result, severity, verdict and team outcome are always words, never only color.
5. **Look**: noir with brass accents, dark by default and light via `prefers-color-scheme`, defined as CSS variables in `src/main/tailwind/report.css`; `ReportThemeContrastTest` checks 4.5:1 for 18 text pairs in both themes. `@import "tailwindcss" source(none)` plus explicit `@source` makes the generated CSS deterministic (before, auto-detection also scanned docs and tests).
6. **Discord `/analyze` embed**: tips are a bold title with quoted `Evidence` and `Drill` lines (blank parts skipped); key numbers are short bullet lines `• **Metric:** yours (peers: ref)` instead of a wide code-block table (the table broke on phones). Field order, 1024 and 6000 limits and the donation field are unchanged. `EmbedStyle.formatKeyNumbersTable` was replaced by `EmbedStyle.parseKeyNumber`.

## Important files
- New: `services/ReportScoreboardBuilder.java`, `services/ReportIconService.java`, tests `ReportScoreboardBuilderTest`, `ReportIconServiceTest`, `ReportThemeContrastTest`, `docs/references/deadlock-style-notes.md`.
- Changed: `domain/ReportView.java` (new `scoreboard` component, `analyzedOn()`, compatible constructors), `services/ReportService.java`, `services/ReportChartsSvg.java`, `services/SoulCurveSvg.java`, `web/ReportController.java`, `templates/report.html`, `templates/report-not-found.html`, `tailwind/report.css`, `static/report.css` (generated, 23 KB), `commands/AnalyzeCommand.java`, `commands/EmbedStyle.java`, `messages.properties` (keys appended at the end; two `analyze.tip.*` and `analyze.key_number.*` keys replace `analyze.tip.line`).
- Tests changed: `ReportServiceTest`, `ReportControllerTest`, `ReportChartsSvgTest`, `SoulCurveSvgTest`, `AnalyzeCommandTest`.

## Deviations from the spec
- Scoreboard rows are a list of cards (`ul/li` with labeled stats), not a `<table>`. A table that turns into cards below 640 px loses its semantics or needs a duplicated header; the list reads the same on every width and every number has a visible label.
- Icon bytes use a private Caffeine cache inside `ReportIconService` instead of `CacheConfig`, so no shared cache registry or `DeadlockApiClientCachingTest` entry was touched (tasks 056 and 057 edit those).
- Chart text is 17 units in a 480-unit viewBox (about 11 to 12 px at 360 px width), not 14 px: a 14 px minimum at 328 px width would need a viewBox of 328 units or a second layout per screen size. The earlier claim that text was 12 px was wrong because the 640-unit viewBox scales down.
- The reference research failed (see below); the look is original, not copied from Deadlock or any tracker.

## Verification
- `./mvnw verify`: BUILD SUCCESS, 1164 tests, 0 failures, 14 skipped.
- `scripts/build-css.sh --check`: OK twice in a row.
- Looked at screenshots (headless Chrome, 360 px and 1280 px) of the report from the test-generated HTML, and of the SVGs at 328 px. Icons show as broken images there because the page was opened from a file, not served. Light mode was seen at 360 px; dark mode at 1280 px and 360 px.
- Not done: the report was **not opened from a running server**, the icon endpoints were **never called against the real CDN** (only against mocks and byte samples, so the image formats of the real assets are untested), the Discord embed was **not seen in Discord**, and no screen reader or print preview was tried.

## Reference research (honest note)
Two web searches returned only summaries and `WebFetch`/`curl` could not open `playdeadlock.com` or `statlocker.gg`. No color or font was read from any site. The only visual reference is the owner's two screenshots. Details in `docs/references/deadlock-style-notes.md`.

## Known issues and follow-up
- Icon formats and sizes from the real asset CDN must be checked once (`AssetImageClient` rejects files over 256 KB).
- Player names come from Steam personas and are shown on a public link page; this matches third-party trackers but the owner should confirm it is acceptable.
- The kill participation and letter grades of the screenshots are not shown (not available from our data; not invented).
- The old `analyze.tip.line` key was removed; nothing else used it.

## Manual testing steps (for `docs/manual-testing.md` when integrated)
1. Start the bot with `WEB_BASE_URL=http://localhost:8080`, run `/analyze` on a new match, open the report.
2. Scoreboard: two teams, your row marked `You`, hero and item icons load (check the browser network tab: only `/r/<id>/img/...`, `report.css` and the SVGs), no horizontal scroll at 360 px.
3. Charts: text readable at 360 px; no label over a line; all six charts open directly (`/soul-lead.svg`, `/soul-curve.svg`, `/phases.svg`, `/deaths.svg`, `/metrics.svg`, `/build.svg`).
4. Accessibility: Tab shows the skip link first and a visible focus ring; the page works at 200% zoom; dark and light OS themes; print preview.
5. Discord: `/analyze` embed shows tips as bold titles with quoted Evidence and Drill, key numbers as bullets, on desktop and phone.
6. `/r/<id>/img/hero/999.png` (a hero not in the match) and `/img/item/1.png` return 404.
