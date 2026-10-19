# Task 046: Prompt v3 (explain `objectives` and the `deaths` time-to-kill fields, add `objectives` to `focus_area`)

## Status
Implemented on branch `feature/046-prompt-v3`. `.\mvnw.cmd verify` passes (1036 tests run, 0 failures, 14 skipped; +2 new unit tests over baseline 1034). Not pushed, not merged.

## Summary
Task 046 makes the coach data from tasks 043 (`objectives`) and 044 (`deaths` time-to-kill summary) usable by Claude Haiku in `/analyze`:
1. **System Prompt Updates (`src/main/resources/prompts/analysis-system.txt`)**:
   - **Line 5 (Input)**: Added `objectives` to the list of coach keys (`phases, deaths, build, recent_form, matchup, objectives`).
   - **Line 9 (`deaths`)**: Appended the three time-to-kill fields: `ttk_median_s, ttk_p90_s, ttk_counted`.
   - **Line 13 (`objectives`)**: Added a concise line after `matchup` defining the eight payload fields (`significance, mid_boss_team, mid_boss_enemy, rejuvenator_stolen_by_enemy, rejuvenator_stolen_by_team, player_boss_damage, boss_damage_share_pct, first_mid_boss_min`) and one sentence of meaning: Mid Boss claims by each team, Rejuvenator steals in each direction (`rejuvenator_stolen_by_enemy` means our team killed the Mid Boss and the enemy claimed the Rejuvenator), the player's share of their team's boss damage, and the minute of the first Mid Boss event.
   - **Line 14 (`General`)**: Added clear guidance that `ttk_*` values are seconds as reported by the data source and are context only (never name them as the leak by themselves, never call a death an ambush, and never explain what they measure as meaning is unconfirmed); and that `objectives` fields are team events (do not blame the player for a Mid Boss claim unless `player_boss_damage` or `boss_damage_share_pct` supports it).
   - **Line 17 (Tie-break order)**: Updated tie-break order for `biggest_leak` to: `deaths, phases, objectives, build, matchup, recent_form`.
   - **Line 31 (`focus_area`)**: Added `"objectives"` to the allowed `focus_area` list.
   - **Length**: Final prompt length is 4,823 characters (well below the 5,000 cap).
2. **Domain & Schema Update (`AnalysisResult.java`)**:
   - Added `"objectives"` to `AnalysisResult.FOCUS_AREAS`.
   - `MessagesRequest.RESULT_SCHEMA` automatically receives `"objectives"` in its sorted enum schema.
   - `AnalysisResult.normalize()` accepts `"objectives"` and preserves it.
3. **Tests**:
   - `AnalysisPromptTest`: Raised character limit cap from 4000 to 5000; verified presence of `objectives`, `ttk_median_s`, `ttk_p90_s`, `ttk_counted`, `rejuvenator_stolen_by_enemy`, `boss_damage_share_pct`; verified tie-break order text; verified focus_area line contains `"objectives"`; verified existing assertions still pass.
   - `AnalysisResultTest`: Added tests verifying `"objectives"` is accepted as a valid `focus_area` and whitespace/case are normalized.
   - `AnalysisClientTest`: Passes completely unchanged, confirming the schema enum correctly matches `FOCUS_AREAS`.

## Files Created or Modified
| File | Changes |
|---|---|
| `src/main/resources/prompts/analysis-system.txt` | Prompt v3 edits 1-6: added `objectives` key, appended `ttk_*` fields to `deaths`, added `objectives` field descriptions and meaning, added interpretation caveats in `General`, updated tie-break order, and added `"objectives"` to `focus_area`. |
| `src/main/java/sntiago05/rejuv/domain/AnalysisResult.java` | Added `"objectives"` to `FOCUS_AREAS`. |
| `src/test/java/sntiago05/rejuv/clients/AnalysisPromptTest.java` | Raised cap to 5000 and asserted prompt requirements for task 046. |
| `src/test/java/sntiago05/rejuv/domain/AnalysisResultTest.java` | Added tests verifying `objectives` is accepted and normalized in `focus_area`. |
| `docs/task-reports/046-prompt-v3-objectives-ttk.md` | This task report. |

No other files were touched.

## Decisions and Prompt Design
- **Consolidated Caveats in General**: Placed both `ttk_*` context rules and `objectives` team-event rules in the `- General:` section alongside existing `*_z` and matchup correlation caveats, keeping the interpretation guidelines unified and prominent.
- **Accurate Field Listing**: Included exactly the 8 fields emitted by `ObjectivesCoachProvider.ObjectivesInsight` without inventing or omitting field names.
- **Dynamic Schema Propagation**: In accordance with the spec, `MessagesRequest` was not touched; it derives its JSON schema enum directly from `AnalysisResult.FOCUS_AREAS`, keeping schema generation single-sourced.

## Verification
- Baseline `.\mvnw.cmd verify`: BUILD SUCCESS (1034 tests run, 0 failures, 14 skipped; 46.931 s).
- Targeted test run `.\mvnw.cmd test "-Dtest=AnalysisPromptTest,AnalysisResultTest,AnalysisClientTest"`: BUILD SUCCESS (41 tests run, 0 failures, 0 skipped; 14.129 s).
- Full suite `.\mvnw.cmd verify`: BUILD SUCCESS (1036 tests run [+2 new tests], 0 failures, 14 skipped; 39.306 s).
- Character count: 4,823 characters (verified via `Measure-Object -Character`).
- Git diff inspection: Exactly 4 files modified, 1 report created; no unrelated changes or touched files.

## Deviations
None.

## Known Issues / Blockers
None.

## Pending Manual Checks
- Live `/analyze` with a real match to confirm Anthropic API accepts the updated schema enum with `"objectives"` and returns `focus_area: objectives` when `objectives` has major significance.
- Confirm live Haiku responses treat `ttk_*` as contextual duration without describing deaths as an "ambush" or claiming unconfirmed measurement semantics.

## Pending Integration
Per parallel worktree rules, `docs/PROJECT_CONTEXT.md` was not modified directly.
Facts to integrate upon merge:
- Prompt v3 explains `objectives` and `ttk_*` fields in `src/main/resources/prompts/analysis-system.txt` (4,823 characters).
- New `focus_area` valid value `"objectives"` in `AnalysisResult.FOCUS_AREAS` and in the generated Anthropic JSON schema.
- New tie-break order for `biggest_leak`: `deaths, phases, objectives, build, matchup, recent_form`.
- Tasks 043 (`objectives`) and 044 (`deaths` TTK summary) become deployable to users now that prompt v3 explains their fields.
- **Note on task 045**: The keys planned for task 045 (`mitigation`, `aim_mechanics`) are not covered by task 046; task 045 (or a later prompt task) must extend the prompt and `FOCUS_AREAS` itself.
