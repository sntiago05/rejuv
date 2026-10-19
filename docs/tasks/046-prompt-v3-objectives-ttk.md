# Task 046: Prompt v3 (explain `objectives` and the `deaths` time-to-kill fields, add `objectives` to `focus_area`)

- **Branch**: `feature/046-prompt-v3`
- **Implementer**: Antigravity
- **Related ADRs**: none (prompt and schema wording only)
- **Depends on**: 037, 040, 043 and 044 merged (done). Must read `docs/coach-provider-rules.md` first.
- **Not covered**: the keys planned for task 045 (`mitigation`, `aim_mechanics`) do not exist yet. Task 045 (or a later prompt task) must add its own prompt lines and enum values.

## Goal
Make the coach data of tasks 043 and 044 usable: the system prompt explains the `objectives` key and the new `deaths` fields, and `objectives` becomes a valid `focus_area`. Until this task is merged, the model sees unexplained keys and cannot return `focus_area: objectives`, so tasks 043 and 044 must not be deployed to users before it.

## Why (verified)
- `src/main/resources/prompts/analysis-system.txt` (3,943 bytes) lists the coach keys in line 5 as `phases, deaths, build, recent_form, matchup`, describes each key's fields in lines 8-12, orders `biggest_leak` ties in line 16 (`deaths, phases, build, matchup, recent_form`), and lists the `focus_area` values in line 30. `objectives` and the `ttk_*` fields appear nowhere in it.
- `AnalysisResult.FOCUS_AREAS` (`Set.of("phases","deaths","build","recent_form","matchup","metrics")`) feeds `RESULT_SCHEMA` in `MessagesRequest` (sorted), so adding a value updates the schema; `AnalysisResult.normalize()` maps unknown values to `null` rather than failing.
- `focus_area` is stored but not displayed anywhere (no `messages.properties` key, no DB column or constraint; `result_json` is TEXT), so no Flyway migration or message key is needed.
- `AnalysisPromptTest` asserts the prompt is shorter than 4,000 characters. The prompt has about 57 characters of headroom, which is not enough for this change.
- Prompts are not versioned and stored analyses are never regenerated (040 listed that as out of scope). Old rows keep their old text.
- Payload field names: `objectives` = `significance, mid_boss_team, mid_boss_enemy, rejuvenator_stolen_by_enemy, rejuvenator_stolen_by_team, player_boss_damage, boss_damage_share_pct, first_mid_boss_min`; new `deaths` fields = `ttk_median_s, ttk_p90_s, ttk_counted`.

## Files to create or modify
| File | Change |
|---|---|
| `src/main/resources/prompts/analysis-system.txt` | Edit as described under Behavior. |
| `src/main/java/sntiago05/rejuv/domain/AnalysisResult.java` | Add `"objectives"` to `FOCUS_AREAS`. Nothing else. |
| `src/test/java/sntiago05/rejuv/clients/AnalysisPromptTest.java` | Raise the length cap to `5000` and add assertions (below). |
| `src/test/java/sntiago05/rejuv/domain/AnalysisResultTest.java` | Add a test that `objectives` is accepted as `focus_area`. |
| `docs/task-reports/046-prompt-v3-objectives-ttk.md` | New: task report. |

No other file. Do **not** edit `MessagesRequest` (the schema derives from `AnalysisResult.FOCUS_AREAS`), `AnalysisClient`, `AnalyzeCommand`, `messages.properties`, any provider, `CoachContextService`, `application.yaml` (`max-tokens` stays 1500), Flyway migrations, `docs/PROJECT_CONTEXT.md`, or other specs. Existing `AnalysisClientTest` must pass unchanged (it compares the schema enum with `FOCUS_AREAS`).

## Behavior

### Prompt edits (keep the existing structure and numbering; English)
1. Line 5: add `objectives` to the list of coach keys.
2. After the `deaths` line (line 9) append the three new fields to it: `ttk_median_s, ttk_p90_s, ttk_counted`; and add a short `objectives` line after `matchup` with the eight fields above and one sentence of meaning: Mid Boss claims by each team, Rejuvenator steals in each direction (`rejuvenator_stolen_by_enemy` means our team killed the Mid Boss and the enemy claimed the Rejuvenator), the player's share of their team's boss damage, and the minute of the first Mid Boss event. Do not write field names the payload does not have.
3. In the General line, or on the `deaths` line, state in plain words that `ttk_*` values are seconds as reported by the data source and are context only: never name them as the leak by themselves, never call a death an ambush, and never explain what they measure (the meaning is unconfirmed). Also state that the `objectives` fields are team events: do not blame the player for a Mid Boss claim unless `player_boss_damage` or `boss_damage_share_pct` supports it.
4. Line 16 tie-break order becomes: `deaths, phases, objectives, build, matchup, recent_form`.
5. Line 30: add `"objectives"` to the `focus_area` list.
6. Do not change the `strength`, `key_numbers`, style or output rules, and do not add examples. Every number the model may quote still comes from the input.

The edited prompt must stay under `5000` characters (aim for about 4,500).

### Code
`AnalysisResult.FOCUS_AREAS` gains `"objectives"`; `RESULT_SCHEMA` picks it up automatically. No other behavior change.

## Out of scope
- Prompt rules for task 045 keys, prompt versioning or regeneration of stored analyses, displaying `focus_area`, new message keys, schema restructuring, any provider or threshold change.
- A live Anthropic call. Whether the API accepts the longer enum is not verified in this repository; list it in the report as a manual check pending.

## Tests
- `AnalysisPromptTest`: cap raised from 4000 to 5000; the prompt contains `objectives`, `ttk_median_s`, `ttk_p90_s`, `ttk_counted`, `rejuvenator_stolen_by_enemy`, `boss_damage_share_pct`; the `focus_area` line contains `"objectives"`; the tie-break order text contains `deaths, phases, objectives, build, matchup, recent_form`; existing assertions (including `doesNotContain("p75")`) still pass.
- `AnalysisResultTest`: a result with `focus_area` `objectives` is kept (not normalized to null); an unknown value still becomes null.
- `AnalysisClientTest` unchanged and green (the schema enum equals `FOCUS_AREAS`, which now includes `objectives`).
- Whole suite: `.\mvnw.cmd verify` passes (Docker Desktop must be running for the Testcontainers tests); report baseline and final counts.

## Documentation
Task report `docs/task-reports/046-prompt-v3-objectives-ttk.md` (status, files, commands actually run, final prompt length, deviations, and the pending manual checks: live `/analyze` with a real match to confirm Anthropic accepts the enum and returns `focus_area: objectives` when `objectives` is major; confirm the model does not describe `ttk_*` as an ambush). Do not edit `docs/PROJECT_CONTEXT.md`; list under "Pending integration": prompt v3 explains `objectives` and `ttk_*`, new `focus_area` value `objectives`, new tie-break order, tasks 043 and 044 become deployable, and task 045 must extend the prompt itself.

## Acceptance criteria
- [ ] Compiles, all tests pass (`.\mvnw.cmd verify`)
- [ ] Prompt edits 1-6 applied; prompt under 5,000 characters
- [ ] `objectives` is a valid `focus_area` in `AnalysisResult.FOCUS_AREAS` and in the generated schema
- [ ] Only the listed files changed; no new dependency, migration, message key or HTTP call
- [ ] Report lists the pending live checks and the "task 045 must extend the prompt" note
