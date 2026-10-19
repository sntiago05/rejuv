# Task 044: Coach layer `deaths` upgrade (time-to-kill summary)

## Status
Implemented on branch `feature/044-deaths-time-to-kill`. `.\mvnw.cmd verify` passes (1034 tests run, 0 failures, 14 skipped; +7 new unit tests over baseline 1027). Not pushed, not merged.

## Summary
Task 044 upgrades the existing `deaths` coach layer (`DeathsCoachProvider`, key `"deaths"`, not a new key) by adding a concise summary of `time_to_kill_s` from data already downloaded and parsed by Rejuv from deadlock-api (`DeathEvent.timeToKillS`):
1. **Zero New HTTP Calls / No New Telemetry Fields**:
   Reuses existing `DeathEvent.timeToKillS` mapped from `MatchMetadataResponse.DeathDetail.timeToKillS`.
2. **Valid Value Rule & Constants**:
   - `TTK_MAX_VALID_S = 60.0`: Values above 60.0 are treated as "no data" (excluded, not clamped); a conservative cut of observed outliers up to 180 s, not a game rule.
   - `MIN_TTK_SAMPLES = 3`: Minimum valid values required to emit the summary.
   - A `DeathEvent.timeToKillS` is valid when it is not null, `> 0.0`, `<= TTK_MAX_VALID_S`, and finite (`Double.isFinite`). Excludes `-1.0`, null, `0.0`, `NaN`, and outliers above `60.0`.
3. **Statistical Calculations**:
   - Single pass over deaths into a primitive array buffer, copied and sorted once (`double[]`).
   - Median: middle value of sorted valid values for odd count; mean of the two middle values for even count.
   - p90: nearest rank using `sorted[(int) Math.ceil(0.9 * n) - 1]`.
   - Rounded to one decimal place using the existing private `round(double)` helper.
4. **Payload Extension (`DeathsPayload`)**:
   - Three optional components appended at the end of `DeathsPayload` and `@JsonPropertyOrder`:
     - `@JsonProperty("ttk_median_s") Double ttkMedianS`
     - `@JsonProperty("ttk_p90_s") Double ttkP90S`
     - `@JsonProperty("ttk_counted") Integer ttkCounted`
   - All three are `null` and omitted from JSON serialization (`@JsonInclude(NON_NULL)` at record level) when fewer than `MIN_TTK_SAMPLES` valid values exist.
   - `deaths_counted` retains its existing meaning and was not reused.
5. **No Threshold Added & Significance Unchanged**:
   Empirical measurements across 10 raw match samples (744 deaths) show no statistical basis for an "ambush death" or "burst death" threshold (winner vs loser median 13.6 s vs 12.9 s; thresholds 1.8–2.5 s found in evidence lack empirical support). No threshold was added and `significance` computation remains completely unchanged. A larger sample calibration is needed first before any threshold could be justified.
6. **Unconfirmed Semantics Note**:
   The semantic meaning of `time_to_kill_s` is unconfirmed: `DeathEvent` Javadoc describes it as "time the killer spent dealing damage", while evidence analysis suggests "seconds under fire before dying". In accordance with the specification, neither meaning is claimed in code comments, payload, or documentation; the field is treated strictly as "`time_to_kill_s` as reported by deadlock-api".
7. **Release Note**:
   Until the prompt task (a later task) explains the new `ttk_*` fields to Haiku and updates prompt/schema guidelines, the model will see unexplained `ttk_*` keys in its coach context. This branch should not be deployed to public users before that task.

## Files Modified
| File | Changes |
|---|---|
| `src/main/java/sntiago05/rejuv/services/DeathsCoachProvider.java` | Added `TTK_MAX_VALID_S` (60.0) and `MIN_TTK_SAMPLES` (3) constants, `isValidTtk` validation predicate, single-pass sorted TTK median and p90 computation, and appended `ttk_median_s`, `ttk_p90_s`, `ttk_counted` to `DeathsPayload` and `@JsonPropertyOrder`. Removed unauthorized 13-argument constructor and redundant per-component `@JsonInclude` annotations. |
| `src/test/java/sntiago05/rejuv/services/DeathsCoachProviderTest.java` | Added 7 comprehensive unit tests for odd/even count median, p90 nearest-rank (on 10 and 7 values), exclusions (-1.0, null, 0.0, NaN, >60.0), boundary conditions (60.0 vs 60.1, 2 vs 3 samples), significance invariance, payload serialization size (<= 1,200 chars) on a realistic full 12-player match with resolved killers and `deaths_while_behind`, null-omission in JSON, and determinism. Existing tests pass unchanged. |
| `docs/task-reports/044-deaths-time-to-kill.md` | This task report. |

No other files were touched.

## Decisions and Algorithm Quality
- **Efficient Single-Pass Collection**: Pre-allocated a primitive `double[] buffer` sized to `deaths.size()`, collected valid values in a single pass without boxing, then sorted only the valid sub-array via `Arrays.copyOf` and `Arrays.sort`.
- **Exact Nearest-Rank p90**: Implemented `sorted[(int) Math.ceil(0.9 * n) - 1]` matching the exact spec definition and verified boundaries on 3, 7, and 10 values.
- **Reuse of Rounding Helper**: Reused the existing `DeathsCoachProvider.round` helper using `BigDecimal.valueOf(val).setScale(1, RoundingMode.HALF_UP).doubleValue()`.
- **Review Findings Fixes**:
  - In `DeathsCoachProvider.DeathsPayload`, removed the extra 13-argument constructor (unused and not authorized by the spec) so only the canonical 16-argument record constructor is present.
  - Removed redundant per-component `@JsonInclude(NON_NULL)` annotations from the three `ttk_*` record components since `DeathsPayload` already declares `@JsonInclude(JsonInclude.Include.NON_NULL)` at the record level.
  - Strengthened `timeToKillSerializationSizeAndOmission` in `DeathsCoachProviderTest` to construct a realistic full match (12 players, 9 deaths with resolved killers, `top_killer` "Haze", `deaths_while_behind` 5 of 8, and valid TTK values) and asserted the serialized payload is <= 1200 characters and contains the TTK fields, while preserving the omission assertions.

## Verification
- Baseline `.\mvnw.cmd verify`: BUILD SUCCESS (1027 tests run, 0 failures, 14 skipped; 46.356 s).
- Targeted test run: `.\mvnw.cmd test -Dtest=DeathsCoachProviderTest`: BUILD SUCCESS (20 tests run, 0 failures, 0 skipped; 11.253 s).
- Full suite `.\mvnw.cmd verify`: BUILD SUCCESS (1034 tests run [+7 new tests], 0 failures, 14 skipped; 38.247 s).
- Whitespace check: `git diff --check` exited 0 (clean, no whitespace issues).
- Scope check: Exactly the 2 implementation files and 1 task report modified/created.

## Deviations
None.

## Known Issues / Blockers
None.

## Pending Integration
Per parallel worktree rules, `docs/PROJECT_CONTEXT.md` was not modified directly.
Facts to integrate upon merge:
- Coach layer `deaths` upgrade: optional payload fields `ttk_median_s` (Double), `ttk_p90_s` (Double), and `ttk_counted` (Integer).
- Valid value rule: not null, finite, `> 0.0`, `<= 60.0` (`TTK_MAX_VALID_S`).
- Sample threshold: requires at least 3 valid values (`MIN_TTK_SAMPLES = 3`); omitted otherwise.
- Semantic definition: `time_to_kill_s` as reported by deadlock-api (unconfirmed semantics).
- Deployment blocker: awaiting the prompt task before public user deployment.
