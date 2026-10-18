# Task 026: Items and charts in the `/analyze` result

- **Branch**: `feature/026-analyze-items-charts`
- **Implementer**: Antigravity
- **Related ADRs**: ADR-0003 and ADR-0011 (AI only for interpretation, token discipline), ADR-0014 (free personal utilities), ADR-0016 (icons and `EmbedStyle`), ADR-0008 (English, i18n-ready)

## Goal
Make the `/analyze` result richer without spending any extra AI token: add the player's **final build** (purchased items with icon, name and buy time) and **one chart image** drawn on the server from the match telemetry (team soul lead over time, and the player's net worth against their teammates). Everything is data the bot already has; the AI prompt does not change.

## Context (verified from the repository and `docs/evidence/026-028-details.md`, 2026-10-18)
Do not repeat this discovery. If a fact below is false in your checkout, stop and ask.

- Depends on 009, 019, 022, 023, 025 (all merged in `main`). Start from the latest `origin/main`. **Merge Task 027 first**: both tasks edit `AnalyzeCommand.java` and `messages.properties`. Trivial expected conflicts (keep both sides).
- Real code you will use:
  - `AnalyzeService.analyze(long discordUserId, long accountId, MatchFilter filter)` returns `AnalysisReport(int heroId, String hero, String rank, String result, double durationMin, AnalysisResult analysis, Map<String, AnalysisInput.MetricComparison> metrics, boolean degraded)`, built at five `new AnalysisReport(...)` sites (stored analysis, AI fallback outcome, fresh AI result, concurrent-duplicate result, unparsable concurrent result). The stored path returns **before** any metadata call; the fresh path calls `matchMetadataSource.getMatch(match.matchId())` (`MatchMetadataSource`, now the cached `FallbackMatchMetadataSource`, cache `deadlockMatchMetadata` 24 h/300) and sets `telemetry = null` and `degraded = true` on `DeadlockApiException`.
  - Telemetry model (`domain/`): `MatchTelemetry(long matchId, int durationS, List<PlayerTelemetry>)` (all players of the match), `PlayerTelemetry(long accountId, int team, int heroId, List<ItemPurchase> items, List<StatSnapshot> snapshots, List<PowerUpBuff> powerUpBuffs)`, `ItemPurchase(long itemId, int boughtAtS, Integer soldAtS)` (`soldAtS == null` = never sold; sorted ascending by `boughtAtS`), `StatSnapshot(int timeS, long netWorth, long playerDamage, long playerHealing)` (sorted ascending by `timeS`; observed every 180 s early and ending with a final snapshot, not verified for every match).
  - Names and icons: `GameAssets.itemsById()` (`Map<Long, ItemAsset>`; `ItemAsset(id, className, name, type, itemTier, itemSlotType, cost, image)`; buyable items have `type` `"upgrade"`), `GameAssets.itemNames()`, `services/IconCatalog.item(long itemId)` (`Optional<String>` Discord emoji markup for item ids that were synced as bot emojis, `i_<id>`; absent when the emoji is not synced), `commands/EmbedStyle` (`COLOR`, `withIcon(Optional<String>, String)`, `fitField(List<String>)`, 1024-character field limit).
  - `commands/AnalyzeCommand.buildEmbed(AnalysisReport)` builds the embed (hero thumbnail, strength / leak / next action / key numbers fields, footer) and `answer(...)` sends it with `hook.editOriginalEmbeds(...)`. No file attachment and no image rendering exist anywhere in the code today.
  - There is no `Dockerfile` in the repository yet (production packaging is not defined), so the font situation of the future runtime image is unknown.
- **Not verified (the implementation must not depend on them):** that each match has exactly 6 players per team; that all players have snapshots at the same `timeS`; that the final snapshot time equals `durationS`; that raw-protobuf matches contain only buyable items; that every item has a synced emoji.
- Rules from `AGENTS.md`: no JDA types in `services/` (the byte array is produced in a service, attached in the command), no business logic in `commands/`, sequential API calls, every client read cached, user-facing text in `messages.properties` (messages with arguments contain no single apostrophe), **no new dependency**.

## Decisions taken in this spec (architect, owner can veto before implementation)
1. **Charts are drawn with the JDK (`java.awt` / Java2D, `BufferedImage`, `ImageIO` PNG)**, no charting library and no external chart service. Reason: zero dependency, and match data does not leave the bot. Alternatives considered and rejected: a charting library (new dependency and owner approval), a hosted chart URL (sends match data to a third party), waiting for the web report (Tasks 017/018, not built).
2. **One PNG with two stacked panels** (a Discord embed shows one image): panel 1 team soul lead, panel 2 the player's net worth against the average of their teammates.
3. **Items come from the telemetry already fetched** and are shown as the final build (items not sold), using the synced emoji when available and the name always.
4. **Stored analyses also get the details**: opening a stored analysis reads the cached telemetry (no AI call, no usage row). If the telemetry is unavailable the stored text is shown without items and chart.

## Files to create or modify
| File | Change |
|---|---|
| `src/main/java/sntiago05/rejuv/domain/MatchVisuals.java` | **Create.** Records for the extra data. |
| `src/main/java/sntiago05/rejuv/domain/AnalysisReport.java` | **Modify.** Add a `visuals` component and keep the old 8-argument constructor. |
| `src/main/java/sntiago05/rejuv/services/MatchVisualsService.java` | **Create.** Builds `MatchVisuals` from telemetry. |
| `src/main/java/sntiago05/rejuv/services/ChartRenderer.java` | **Create.** Java2D PNG renderer. |
| `src/main/java/sntiago05/rejuv/services/AnalyzeService.java` | **Modify.** Attach visuals at the five report sites; read cached telemetry on the stored path. Nothing else. |
| `src/main/java/sntiago05/rejuv/commands/AnalyzeCommand.java` | **Modify.** Build lines with icons for the items field; render and attach the chart. |
| `src/main/resources/messages.properties` | **Modify.** Add the new keys at the **end of the file**. |
| `src/test/java/sntiago05/rejuv/services/MatchVisualsServiceTest.java` | **Create.** |
| `src/test/java/sntiago05/rejuv/services/ChartRendererTest.java` | **Create.** |
| `src/test/java/sntiago05/rejuv/services/AnalyzeServiceTest.java` | **Modify.** Constructor wiring and the visuals cases. |
| `src/test/java/sntiago05/rejuv/commands/AnalyzeCommandTest.java` | **Modify.** Items field and attachment cases. |
| `docs/task-reports/026-analyze-items-charts.md` | **Create.** Task report (format of `docs/task-reports/014-heroes.md`). |

No `pom.xml` change, no migration, no new cache, no change to the prompt, the AI client, `MetricsService`, `UsageService` or any DTO. If you think another file is needed, stop and ask.

## Interfaces / signatures

```java
// domain/MatchVisuals.java
public record MatchVisuals(List<PurchasedItem> items, ChartData chart) {
    public MatchVisuals { items = List.copyOf(items); }   // chart may be null

    /** itemId and name always set; items without a known name are dropped by the service, never shown as "Item #id". */
    public record PurchasedItem(long itemId, String name, int boughtAtS) { }

    /**
     * Parallel lists, same length n >= 2, ascending timeS. soulLead = own team total net worth minus enemy team total
     * net worth; playerNetWorth = the player; teammatesAvgNetWorth = average of the other players of the player's team
     * (empty list when the player has no teammate in the telemetry).
     */
    public record ChartData(List<Integer> timesS, List<Long> soulLead, List<Long> playerNetWorth,
                            List<Long> teammatesAvgNetWorth) { }
}

// domain/AnalysisReport.java: new canonical component at the END, plus the compatibility constructor
public record AnalysisReport(int heroId, String hero, String rank, String result, double durationMin,
                             AnalysisResult analysis, Map<String, AnalysisInput.MetricComparison> metrics,
                             boolean degraded, MatchVisuals visuals) {
    public AnalysisReport(int heroId, String hero, String rank, String result, double durationMin,
                          AnalysisResult analysis, Map<String, AnalysisInput.MetricComparison> metrics, boolean degraded) {
        this(heroId, hero, rank, result, durationMin, analysis, metrics, degraded, null);
    }
}
// keep the existing compact constructor behavior for metrics

// services/MatchVisualsService.java  (@Service)
public MatchVisualsService(GameAssets gameAssets)
/** Empty when telemetry is null or the player is not in it. Never throws for missing data. */
public Optional<MatchVisuals> build(long accountId, MatchTelemetry telemetry)

// services/ChartRenderer.java  (@Component)
/** PNG bytes, 900 x 640 px; empty when chart data is unusable or rendering fails (logged at WARN). */
public Optional<byte[]> render(MatchVisuals.ChartData chart)
```
`AnalyzeService` gains one constructor parameter, `MatchVisualsService visualsService`, appended last (update the test wiring only).

## Behavior

### `MatchVisualsService.build`
1. Find the player's `PlayerTelemetry` by `accountId`; none -> `Optional.empty()`.
2. **Items:** take the player's items with `soldAtS == null`, ordered by `boughtAtS` ascending (keep the telemetry order, already sorted). Map each id through `gameAssets.itemsById()`; keep it only when found, `type` equals `"upgrade"` and `name` is non-blank; drop everything else (abilities, unknown ids). Cap at **12** items (the last 12 bought if more).
3. **Chart:** use only the `timeS` values present in the snapshot lists of **every** player in the telemetry (set intersection, ascending); this makes the sums consistent. If fewer than 2 common times remain, or the team of the player has no one else, `chart = null` (the items may still be returned). For each common time: own team sum minus the other teams' sum of `netWorth` (`soulLead`), the player's `netWorth`, and the average `netWorth` of the player's teammates excluding the player (integer division; empty list if none). Use hash lookups by `timeS` per player (build one `Map<Integer, Long>` per player, single pass); no nested scans.
4. If both the item list and the chart are empty, return `Optional.empty()`.

### `ChartRenderer.render`
1. Set `System.setProperty("java.awt.headless", "true")` once (static initializer) before using AWT.
2. Draw a 900 x 640 PNG: dark background, two panels of equal height, a title text above each panel (use fixed English strings **read from `messages.properties` through `Messages`**, so inject `Messages`: `analyze.chart.lead_title`, `analyze.chart.networth_title`), axes with minute labels on X and thousands of souls (`12.5k`) on Y, light grid lines, anti-aliasing on.
   - Panel 1: line (and a translucent fill to the zero line) of `soulLead` against minutes, a visible zero line, positive area in one colour and negative in another (colour-blind safe pair such as blue and orange).
   - Panel 2: two lines, the player (strong colour) and the teammates average (neutral colour), with a small legend using `analyze.chart.legend_you` and `analyze.chart.legend_team`.
   - Handle a flat series (all zero) and large values without division by zero or an inverted axis.
3. Encode with `ImageIO.write(image, "png", out)`. Return `Optional.empty()` for null chart or fewer than 2 points. Catch `RuntimeException`, `java.awt.AWTError` and `LinkageError` (missing fonts or native libs on a bare runtime image), log one WARN with the class and message (no stack trace), and return empty. Do not catch `Throwable`.
4. Fonts: use the logical font family (`Font.SANS_SERIF`); do not bundle a font file.

### `AnalyzeService`
1. **Fresh path:** reuse the `telemetry` already fetched; at each of the four report sites after that point call `visualsService.build(accountId, telemetry)` (null telemetry -> empty) and pass `orElse(null)`.
2. **Stored path (line of the first `new AnalysisReport(...)`):** after the stored text is parsed and before returning, try `matchMetadataSource.getMatch(match.matchId())`; on `DeadlockApiException` (any subtype) log one WARN and use no visuals. This call is cached (24 h) and is **not** an AI call: it records no usage. The weekly limit is still checked only for fresh analyses, exactly as today (a stored analysis never triggers `checkAllowance`).
3. The order of the existing calls, the usage check, saving, the cost log and the fallback behavior do not change.

### `AnalyzeCommand`
1. Items field (only when `report.visuals() != null` and items are present): title `analyze.field.build`, one line per item built with `messages.get("analyze.build.item", iconOrEmpty + name, minuteSecond)` where `iconOrEmpty` is `iconCatalog.item(itemId)` followed by a space when present, and `minuteSecond` is `m:ss` from `boughtAtS`. Join with newline through `EmbedStyle.fitField`. `IconCatalog` is already used by other commands; inject it the same way.
2. Chart: if `report.visuals() != null && report.visuals().chart() != null`, call `chartRenderer.render(chart)`; when bytes are present, call `builder.setImage("attachment://match.png")` and send with `hook.editOriginalEmbeds(embed).setFiles(FileUpload.fromData(bytes, "match.png")).queue()`. When rendering returned empty, send the embed without image (no error to the user). The existing error handling in `answer(...)` is unchanged.
3. Missing visuals or no items: the embed is exactly what it is today.
4. The embed must stay within Discord limits (6000 characters total, 25 fields); the new field counts against them, so keep the existing `truncate` behavior and `fitField`.

### User-facing text (English)
`analyze.field.build`, `analyze.build.item` (`{0}` icon and name, `{1}` buy time), `analyze.chart.lead_title`, `analyze.chart.networth_title`, `analyze.chart.legend_you`, `analyze.chart.legend_team`. No apostrophes in messages with arguments.

## Tests
1. **`MatchVisualsServiceTest`** (build telemetry in memory): items exclude sold items and unknown or non-`upgrade` ids and are capped at 12; player absent -> empty; soul lead equals own-team sum minus enemy sum on the common times; a player with a missing snapshot time removes that time for everyone; fewer than 2 common times -> `chart == null` but items kept; no teammates -> empty `teammatesAvgNetWorth`; both empty -> `Optional.empty()`; teams of uneven size (5 v 6) still work.
2. **`ChartRendererTest`:** valid data returns bytes that start with the PNG signature and decode with `ImageIO.read` to 900 x 640; null chart, 1 point and all-zero series do not throw (all-zero returns an image, null and 1 point return empty); a rendering failure (inject a failing path, for example by passing lists of different lengths) returns empty and logs, no exception escapes.
3. **`AnalyzeServiceTest`:** fresh path attaches visuals built from the fetched telemetry; degraded (null telemetry) gives `visuals == null`; the stored path calls `getMatch` once, attaches visuals, and still makes no AI call and records no usage; `DeadlockApiUnavailableException` on the stored path gives the stored text with `visuals == null`; an over-limit user is still blocked before any metadata call on the fresh path (existing test kept passing).
4. **`AnalyzeCommandTest`:** with visuals the embed has the `analyze.field.build` field, an item with a synced emoji shows the emoji and one without shows the name only, the image URL is `attachment://match.png` and one file named `match.png` is attached; renderer returns empty -> no image and no file; no visuals -> embed identical to the current expected one; all new catalog keys resolve.
5. Existing tests keep passing; adapt only the places that construct `AnalyzeService`, `AnalyzeCommand` or `AnalysisReport` through the changed constructors.

## Verification
1. `./mvnw clean verify` passes with Docker running (Windows: `.\mvnw.cmd clean verify`; see the `MAVEN_USER_HOME` note in `docs/PROJECT_CONTEXT.md`). Report the real test count and the baseline difference.
2. `git diff --check`; the diff contains only the files in the table; `.env` never read or printed.
3. Headless check: run the renderer test with `-Djava.awt.headless=true` (it must pass) and record whether it also passes on the machine without a display. State clearly that the font situation of the future production image is **not verified** (there is no Dockerfile yet).
4. Manual check (needs the usual credentials): `/analyze` on a fresh match shows the build field and one chart image (soul lead above, your net worth versus teammates below); reopening the same match (stored) shows the same details and does not use a weekly analysis; a match whose metadata is unavailable shows the text without items and chart. Save one rendered PNG to the scratchpad or a temp folder (not the repository) and describe what it shows. If you do not run it, say so in the report.

## Documentation
- Task report `docs/task-reports/026-analyze-items-charts.md`: status, summary, files, commands run with real results, deviations, known issues.
- `docs/PROJECT_CONTEXT.md`: **do not edit in this branch** while other task branches are active; mark it "pending integration" and list the facts (new `visuals` component, `MatchVisualsService`, `ChartRenderer`, the headless/font risk, the stored-path telemetry read).
- Do not edit ADRs, `docs/mvp-spec.md`, `docs/implementation-plan.md` or other specs.

## Out of scope
- Any change to the AI prompt, schema or input; adding items or charts to the AI request.
- Damage or healing charts, minion (CS) charts, item timing against benchmarks, ability order, the other players' builds.
- Persisting telemetry, charts or images; new caches; new emojis (use only what `IconCatalog` already has).
- A web report or a charting dependency (Tasks 017 and 018).
- A Dockerfile or font installation for production (follow-up once packaging exists).

## Acceptance criteria
- [ ] The result shows the final build with names (and emoji when synced) and buy times
- [ ] One PNG chart (soul lead, net worth against teammates) is attached and referenced by the embed
- [ ] No extra AI token is spent; the AI input is unchanged; stored reopenings record no usage
- [ ] Missing telemetry, missing player, unsynced emoji or a rendering failure degrade silently to today's embed
- [ ] No new dependency; charts use the JDK only
- [ ] All user-facing text is in `messages.properties`
- [ ] Compiles, all tests pass (`./mvnw clean verify`)
- [ ] Follows AGENTS.md conventions; no JDA types in `services/`
- [ ] Task report created; `docs/PROJECT_CONTEXT.md` update marked pending integration; final diff has no unrelated files

## Decisions and open questions
Decisions are listed above (JDK rendering, one image with two panels, final build only, stored analyses also enriched). Open for the owner (not blocking): damage and healing curves as a second image; the production font setup when a Dockerfile exists; whether to show items from the other players.
