# Task 047: `/analyze` embed v2 and a clearer Discord chart

- **Branch**: `feature/047-analyze-embed-chart-v2`
- **Implementer**: Antigravity
- **Related ADRs**: ADR-0016 (emoji icons), ADR-0011 (analysis design: strict JSON, numbers computed in the backend)
- **Depends on**: 037, 040 and 046 merged (done). Independent of 045 and 048 (small, mergeable conflict in `messages.properties` only).
- **Evidence**: inventory of the current embed and chart (see "Why"); `docs/manual-testing.md` items for the embed and chart.

## Goal
Make the `/analyze` reply easy to scan in a few seconds and make the chart explain itself without legends or prior knowledge: the reader should know who is ahead, when the match turned, and when they died. The AI output schema, the prompt and the stored result do not change.

## Why (verified in the current code)
- `AnalyzeCommand.buildEmbed` shows fields in this order: Key Strength, Biggest Leak (with a severity prefix), Next Game Focus, Key Numbers, Final Build, then the chart image and a footer. Key Numbers are plain lines `"<metric>: <yours> / <reference>"`. Only field values are truncated (1,024 chars); title, description and footer are not capped.
- `ChartRenderer` (Java2D, 900x640, dark) draws two stacked panels: team soul lead (blue above zero, orange below) and your net worth vs teammates average (gold vs grey), with a legend and minute ticks. There are no phase markers, no death markers, no end-of-line labels, no plain-language reading hint. Input is `MatchVisuals.ChartData(timesS, soulLead, playerNetWorth, teammatesAvgNetWorth)`.
- The web SVG uses a different gold than the PNG (`0xE5A93C`/`0xD97706` vs `0xFFC83D`).
- Death times exist in telemetry (`PlayerTelemetry.deaths`, `DeathEvent.gameTimeS`); phase boundaries exist as constants in `DeathsCoachProvider` (`LANING_PHASE_END_S = 720`, `MID_GAME_END_S = 1200`).

## Files to create or modify
Read `AnalyzeCommand`, `EmbedStyle`, `ChartRenderer`, `MatchVisuals` and `MatchVisualsService` before editing, and confirm the names below.
| File | Change |
|---|---|
| `src/main/java/sntiago05/rejuv/commands/AnalyzeCommand.java` | New embed layout (below). |
| `src/main/java/sntiago05/rejuv/commands/EmbedStyle.java` | Only if a shared helper is needed (monospace table); reuse before adding. |
| `src/main/java/sntiago05/rejuv/domain/MatchVisuals.java` | Add `List<Integer> playerDeathTimesS` to `ChartData` (copy defensively; keep the existing constructor as an overload delegating with an empty list). |
| `src/main/java/sntiago05/rejuv/services/MatchVisualsService.java` | Fill `playerDeathTimesS` from the player's `DeathEvent`s (empty when unknown). |
| `src/main/java/sntiago05/rejuv/services/ChartRenderer.java` | Chart redesign (below). |
| `src/main/resources/messages.properties` | New keys (below). |
| `src/test/java/sntiago05/rejuv/commands/AnalyzeCommandTest.java`, `.../services/ChartRendererTest.java`, `.../services/MatchVisualsServiceTest.java` | Update and add tests. |
| `docs/task-reports/047-analyze-embed-chart-v2.md` | New: task report. |

No other file. Do **not** edit the prompt, `AnalysisResult`, `AnalysisClient`, `AnalyzeService`, the web report, `SoulCurveSvg`, entities, migrations or `application.yaml`. `CpuHotPathBenchmarkTest` prints a PNG hash; if the new chart changes it, say so in the report, do not fix the benchmark otherwise.

## Behavior

### Embed (all text through `messages.properties`; no hardcoded user-facing text)
1. Field order: **Biggest Leak** (keeps the severity prefix), **Next Game Focus**, **Key Strength**, **Key Numbers**, **Final Build**, then chart and footer. The first thing read is the problem and the action.
2. Field names get a leading emoji from keys (for example a warning sign for the leak, a target for the action, a muscle for the strength, a chart for numbers, a shield or backpack for the build). Choose plain Unicode emoji, not application emoji, so the text fallback of ADR-0016 is not needed; keep each name short.
3. **Key Numbers** render as a single monospace code block with aligned columns `metric | yours | reference`, built by splitting each string `"<metric>: <yours> / <reference>"` once on `": "` and on the last `" / "`. A string that does not match that shape is kept verbatim as its own line (never dropped). The block respects the 1,024-char limit (drop whole lines from the end, never cut inside a line; reuse the existing fitting helper where it fits).
4. Do not change what the model returns: text fields are shown as they are. Severity labels, the degraded and fallback notes, the footer, the report button and the empty/error replies keep their behavior.
5. Title, description and footer must not exceed Discord limits (256 / 4,096 / 2,048); add a defensive cap and a test.

### Chart (`ChartRenderer`; still PNG, no new dependency, fonts as today; dark theme)
1. Same canvas width; height may grow up to 720.
2. **Plain-language reading hint** under each panel title from new keys, for example "Above the line: your team has more souls" and "Gold line above grey: you are ahead of your teammates".
3. **Phase bands** (Laning, Mid, Late) as subtle alternating background bands with the phase name at the top, using the same boundaries as `DeathsCoachProvider` (720 s and 1200 s); copy the two numbers into named constants with a comment (do not make `DeathsCoachProvider` fields public in this task).
4. **Death markers**: a small marker at each `playerDeathTimesS` on the net-worth panel (on the player's line), with a one-line legend entry from a key ("Your deaths"). Nothing is drawn when the list is empty.
5. **Direct labels** instead of a legend: the last point of each line gets its name ("You", "Teammates") next to it; the lead panel labels the two sides ("Your team ahead" / "Enemy ahead") at the zero line edge.
6. **Final value** annotation on the lead panel (for example "+3.4k at 28 min"), computed from the last point, one decimal.
7. Keep the accessible color pair already used (blue/orange for sides, gold/grey for lines) and make sure no two meanings share a color; text contrast on the dark background must be readable at small size (minimum 12 px font equivalent at 900 px width).
8. Failure behavior unchanged: `render()` returns empty on any rendering failure; one-point or mismatched series still return empty.

### New message keys (names are fixed, English text is yours but short)
`analyze.field.leak`, `.next_action`, `.strength`, `.key_numbers`, `.build` keep their keys and get emoji prefixes; new: `analyze.chart.lead_hint`, `analyze.chart.networth_hint`, `analyze.chart.phase.laning`, `.phase.mid`, `.phase.late`, `analyze.chart.deaths_label`, `analyze.chart.side_you`, `analyze.chart.side_enemy`, `analyze.chart.final_lead` (placeholders `{0}` signed thousands, `{1}` minute). Escape apostrophes for `MessageFormat`; format numbers without locale grouping.

## Out of scope
- Prompt or schema changes, showing `focus_area`, new buttons, the web report and its SVG (task 049), persistence (task 048), light theme, animated or multi-image replies, premium gating.

## Tests
- Embed: field order; emoji prefixes present; Key Numbers block aligns columns, keeps a malformed line verbatim, stays under 1,024 chars by dropping whole lines; fallback (null analysis) path unchanged; title/description/footer caps; report button unchanged.
- Chart: PNG decodes and has the expected width; renders with and without deaths; death markers do not throw with an empty list or times outside the series; phase bands with a match shorter than 720 s and longer than 1200 s; mismatched/one-point series return empty; a golden-pixel or color-sample check proving a marker color appears at the expected x for a known death time (a check, not a full image hash).
- `MatchVisualsServiceTest`: death times are filled from telemetry and empty when absent.
- Whole suite: `.\mvnw.cmd verify` (Docker Desktop running); report counts. Save one rendered PNG from a test fixture under the scratchpad or `target/` (never committed) and describe it in the report.

## Documentation
Task report `docs/task-reports/047-analyze-embed-chart-v2.md` (status, files, commands run, deviations, the benchmark hash note, manual checks not done: view the embed and chart in real Discord on desktop and mobile). Do not edit `docs/PROJECT_CONTEXT.md`; list under "Pending integration": embed field order, Key Numbers table format, chart elements, new message keys, `ChartData.playerDeathTimesS`.

## Acceptance criteria
- [ ] Compiles, all tests pass (`.\mvnw.cmd verify`)
- [ ] Embed order and Key Numbers table as described; no hardcoded user-facing text
- [ ] Chart shows reading hints, phase bands, death markers, direct labels and a final lead annotation
- [ ] Only the listed files changed; no new dependency, migration or HTTP call
- [ ] Report lists the manual Discord checks as not done
