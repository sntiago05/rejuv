# Task 026: Items and charts in the `/analyze` result

## Status
Implemented on `feature/026-analyze-items-charts`. `./mvnw clean verify` passes. Not pushed, not merged.

## Summary
`/analyze` now shows the player's final build (name, synced emoji when available, buy time) and one 900 x 640 PNG
(team soul lead above, player net worth against teammates average below), drawn with Java2D from telemetry the bot
already fetches. No AI prompt, input, usage or cache change. Stored analyses are also enriched through the cached
metadata read (no AI call, no usage row, no allowance check). Missing telemetry, player, emoji or a render failure
degrade to the previous embed.

## Important Files
- `domain/MatchVisuals.java` (new), `domain/AnalysisReport.java` (new `visuals` component, 8-arg constructor kept)
- `services/MatchVisualsService.java` (new), `services/ChartRenderer.java` (new)
- `services/AnalyzeService.java` (visuals at the five report sites, stored-path telemetry read)
- `commands/AnalyzeCommand.java` (build field, chart attachment), `messages.properties` (6 keys at the end)
- Tests: `MatchVisualsServiceTest`, `ChartRendererTest`, `AnalyzeServiceTest`, `AnalyzeCommandTest`

## Decisions
- Chart common times are the intersection over all players; per-time sums iterate players (about 12) per point, which is provably small.
- Soul lead uses own team minus all other teams; if the player has no teammate in telemetry the chart is null.
- `setFiles(List.of(FileUpload))` is used (Collection overload) so the attachment is testable with Mockito.
- `AnalyzeService.storedVisuals` catches `DeadlockApiException` only; it runs inside the stored-JSON try block, so the service must never throw otherwise (`MatchVisualsService.build` does not).

## Verification
- Baseline (`.\mvnw.cmd -q verify` before changes): green, 636 tests (summed from surefire reports).
- `.\mvnw.cmd clean verify` after changes: BUILD SUCCESS, 659 tests, 0 failures, 0 errors, 2 skipped (skipped tests pre-exist). +23 tests.
- Renderer tests also run explicitly with `-Djava.awt.headless=true`: pass. The renderer sets headless itself. Windows dev machine, JDK fonts available.
- `git diff --check`: clean (only the CRLF working-copy warnings that exist for every file on this machine).
- Rendered sample saved outside the repo (`%TEMP%/rejuv-chart-sample.png`): dark background, panel 1 blue area above zero from 0 to ~20 min then orange below zero to 30 min, panel 2 yellow "You" line above grey "Teammates avg" line, minute labels on X and `k` souls on Y, legend top-left of panel 2.
- Manual Discord check (`/analyze` live, stored reopen, unavailable metadata): NOT run (no credentials/Discord here).

## Deviations
None. Existing stored-path test renamed (`...WithoutBenchmarksOrAiCall`) and now expects one `getMatch` call, as the spec requires.

## Known Issues
- Font situation of the future production image is NOT verified (no Dockerfile). The renderer returns empty and logs a WARN on AWT/font failure, so the embed degrades to no image.
- Y axis ticks are padded values (for example `-1.4k`, `5.9k`), not round numbers.
- Stored reopening now makes a cached metadata call (24 h cache); a cache miss costs one deadlock-api request.

## Merge Risks
- Task 028 branch may touch `AnalyzeCommand.java` and `messages.properties` (keep both sides).
- PROJECT_CONTEXT update pending integration. Facts to add: `AnalysisReport.visuals`, `MatchVisualsService`, `ChartRenderer` (JDK only, headless, 900 x 640 PNG), stored path reads cached telemetry, font/headless risk until a Dockerfile exists.
