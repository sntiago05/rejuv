# Task 044: Coach layer `deaths` upgrade (time-to-kill summary)

- **Branch**: `feature/044-deaths-time-to-kill`
- **Implementer**: Antigravity
- **Related ADRs**: ADR-0017 (metadata sources)
- **Depends on**: 032 (the `deaths` provider) and 043 merged (done). Must read `docs/coach-provider-rules.md` first; this spec overrides it only where it says so.
- **Evidence** (local, gitignored): `docs/evidence/metadata-coach/00-summary.md`, `02-provider-designs.md`, `03-validation-on-samples.md`, raw samples `docs/evidence/metadata-coach/raw/match-*-metadata.json` (10 matches).

## Goal
Extend the existing `deaths` coach layer (key `deaths`, **not** a new key) with a short summary of `time_to_kill_s`, using data Rejuv already downloads and maps. Zero new HTTP calls, no new telemetry fields.

## Why (verified)
- `match_info.players[].death_details[].time_to_kill_s` is already parsed: `MatchMetadataResponse.DeathDetail.timeToKillS` (`Double`), mapped by `JsonMatchMetadataSource` into `DeathEvent.timeToKillS` (`Double`, nullable). `DeathsCoachProvider` never reads it.
- Measured on the 10 raw samples (744 deaths): no nulls; median 13.1 s, p10 5.3 s, p90 28.8 s; 15 values are exactly `-1.0` (probably "unknown", **not confirmed**); 13 values are above 60 s (max 180.0); only 1.2% of valid values are below 2.5 s. Winners and losers look alike (median 13.6 s vs 12.9 s).
- Consequence: the data gives **no basis for an "ambush death" threshold** (1.8, 2.0, 2.2 and 2.5 s all appear in the evidence without support). This task adds no such threshold and does not change `significance`.
- **Unconfirmed semantics:** the `DeathEvent` Javadoc says "time the killer spent dealing damage"; the evidence says "seconds under fire before dying". The implementer must not claim either meaning in code comments, the payload or the report: describe the field only as "`time_to_kill_s` as reported by deadlock-api".
- The raw protobuf fallback produces no deaths, so the provider already returns empty there. Unchanged.

## Files to create or modify
| File | Change |
|---|---|
| `src/main/java/sntiago05/rejuv/services/DeathsCoachProvider.java` | Compute the time-to-kill summary and add three optional payload fields (below). |
| `src/test/java/sntiago05/rejuv/services/DeathsCoachProviderTest.java` | Add tests; existing tests must pass unchanged. |
| `docs/task-reports/044-deaths-time-to-kill.md` | New: task report. |

No other file. Do **not** edit `DeathEvent`, `MatchMetadataResponse`, `JsonMatchMetadataSource`, `RawMatchMetadataParser`, `PlayerTelemetry`, `MatchTelemetry`, the prompt, `AnalysisResult`, `messages.properties`, `CacheConfig`, `DeadlockApiClient`, or `docs/PROJECT_CONTEXT.md`. The prompt and the `focus_area` enum belong to a later task.

## Interfaces / signatures

### New constants in `DeathsCoachProvider` (`static final`, each with a short comment)
- `TTK_MAX_VALID_S = 60.0`: values above it are treated as "no data" (excluded, not clamped); comment that it is a conservative cut of observed outliers up to 180 s, not a game rule.
- `MIN_TTK_SAMPLES = 3`: minimum valid values required to emit the summary.

### Valid value rule
A `DeathEvent.timeToKillS` is valid when it is not null, `> 0.0`, `<= TTK_MAX_VALID_S`, and finite. This excludes `-1.0` and null (treated as "no data"), zero and the outliers.

### Payload (`DeathsPayload`)
Append three components **at the end** of the record and of `@JsonPropertyOrder`, all `@JsonInclude(NON_NULL)`:
```
@JsonProperty("ttk_median_s")  Double ttkMedianS   // median of valid values, one decimal
@JsonProperty("ttk_p90_s")     Double ttkP90S      // p90 of valid values, one decimal
@JsonProperty("ttk_counted")   Integer ttkCounted  // number of valid values used
```
All three are `null` (omitted) when fewer than `MIN_TTK_SAMPLES` valid values exist. `deaths_counted` keeps its current meaning; do not reuse it.

### Statistics
- Median: middle value of the sorted valid values; for an even count, the mean of the two middle values.
- p90: nearest rank, `sorted[ceil(0.9 * n) - 1]`.
- Round with the existing private `round` helper of `DeathsCoachProvider` (do not copy a new one). Sort once (`double[]` copy), single pass to collect valid values; no nested scans.

## Behavior
- Same input gives the same output. `significance`, all existing fields and all existing thresholds are unchanged, including when the summary is present.
- No per-death list is added (payload size). The serialized payload for a realistic full match stays at or below 1,200 characters.
- No logging added.
- Release note for the report: until the prompt task (a later one) explains the new fields, the model sees unexplained `ttk_*` keys; the branch should not be deployed to users before that task. Say so in the report.

## Out of scope
- Any ambush or "burst death" threshold, `death_pos`/`killer_pos`, map zones, per-death output, mitigation or aim mechanics, prompt, schema, display, raw fallback.
- Any change to other providers or to the existing `deaths` thresholds.

## Tests (`DeathsCoachProviderTest`)
- Happy path: a set of deaths with known values asserting exact `ttkMedianS`, `ttkP90S`, `ttkCounted` (odd count and even count median; p90 nearest-rank on 10 and on 7 values).
- `-1.0`, null, `0.0`, `NaN` and values above 60.0 are excluded; exactly `60.0` is included; `60.1` is not.
- Fewer than `MIN_TTK_SAMPLES` valid values (2 vs 3) omits all three fields; all values invalid omits them.
- `significance` and the other fields are identical with and without valid TTK values (same deaths, TTK replaced by null).
- Serialized payload of a realistic full match with the new fields is at or below 1,200 characters, and omitted fields do not appear in the JSON (plain Jackson 3 `JsonMapper`).
- Determinism; existing tests unchanged.
- Whole suite: `.\mvnw.cmd verify` passes; report baseline and final test counts.

## Documentation
Task report `docs/task-reports/044-deaths-time-to-kill.md` (status, files, commands actually run, deviations, the release note above, the unconfirmed-semantics note, and that no threshold was added because the 10-sample measurement does not support one; a larger sample is needed first). Do not edit `docs/PROJECT_CONTEXT.md`; list under "Pending integration": new optional `deaths` fields `ttk_median_s`, `ttk_p90_s`, `ttk_counted`, the validity rule and constants.

## Acceptance criteria
- [ ] Compiles, all tests pass (`.\mvnw.cmd verify`)
- [ ] Unit tests as listed, including each boundary (`MIN_TTK_SAMPLES`, `TTK_MAX_VALID_S`)
- [ ] Follows AGENTS.md and `docs/coach-provider-rules.md`; only the listed files changed
- [ ] No new HTTP call, dependency, telemetry field, threshold or user-facing text
- [ ] `significance` behavior unchanged
- [ ] Report includes the not-yet-deployable-until-prompt-task note and the unconfirmed-semantics note
