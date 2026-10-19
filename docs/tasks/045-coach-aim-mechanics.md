# Task 045: Coach layer `aim_mechanics` (shot accuracy for gun-dominant heroes) and prompt lines

- **Branch**: `feature/045-coach-aim-mechanics`
- **Implementer**: Antigravity
- **Related ADRs**: ADR-0017 (metadata sources)
- **Depends on**: 040, 043, 044 and 046 merged (done). Must read `docs/coach-provider-rules.md` first; this spec overrides it only where it says so (it authorizes the prompt and `FOCUS_AREAS` edits that the rules file normally forbids for provider tasks, because 046 left no later prompt task).
- **Evidence** (local, gitignored): `docs/evidence/metadata-calibration/` (`00-summary.md`, `04-spec-inputs.md`, `verification/00-summary.md`; 43 matches, 516 players). Owner decisions for this spec: **aim only** (`mitigation` is deferred: it has no calibration, only 10 matches); **Silver (80) is included**; **`major` uses accuracy only**.

## Goal
Add a coach layer with key `aim_mechanics` that tells the model how accurate the player's shots were, only for gun-dominant heroes, using the final stat snapshot already downloaded in `GET /v1/matches/{id}/metadata`. Zero new HTTP calls. Explain the key in the prompt and add it to `focus_area`.

## Why (verified)
- Gun-dominant heroes (median weapon damage share >= 50% and median shots >= 700 in the calibration): Haze 13, Warden 25, Vyper 58, Silver 80, Rat King 84. **Silver has only N=4 matches (verification marked it inconclusive); the owner chose to include it anyway.** Say so in a code comment next to the set.
- Final-snapshot accuracy for gun-carry players (N=64): p10 40.9, p25 48.5, p50 55.6, p75 61.2, p90 66.8. The original rule (`acc < 28`, `crit < 5`) fired 0.0%. The "OR" major rule fired 21.9% (Vyper 58%), so `major` here uses accuracy only.
- Crit rate (`hero_bullets_hit_crit / hero_bullets_hit`, bullets >= 250, N=58): p10 10.0, p50 13.9, p90 21.1. It is reported but does **not** drive significance.
- Already parsed: `shots_hit`, `shots_missed` (`SnapshotDetail.shotsHit/shotsMissed`, mapped with `zeroIfNull`). Missing: `hero_bullets_hit` and `hero_bullets_hit_crit` (raw JSON name is `hero_bullets_hit_crit`, not `crits`).
- Open and unverified: whether pellet/burst weapons count one shot or several, and whether post-death hits count. Do not claim either in code or prompt.

## Files to create or modify
| File | Change |
|---|---|
| `src/main/java/sntiago05/rejuv/clients/dto/MatchMetadataResponse.java` | Add `@JsonProperty("hero_bullets_hit") Long heroBulletsHit` and `@JsonProperty("hero_bullets_hit_crit") Long heroBulletsHitCrit` to `Stat` (nullable). |
| `src/main/java/sntiago05/rejuv/domain/SnapshotDetail.java` | Add components `Long heroBulletsHit` and `Long heroBulletsHitCrit` (nullable = field absent). Keep the current constructor as an overload delegating with `null, null`. |
| `src/main/java/sntiago05/rejuv/clients/JsonMatchMetadataSource.java` | Map the two fields into `SnapshotDetail` **without** `zeroIfNull` (absent stays `null`). |
| `src/main/java/sntiago05/rejuv/services/AimMechanicsCoachProvider.java` | New provider (below). |
| `src/main/java/sntiago05/rejuv/domain/AnalysisResult.java` | Add `"aim_mechanics"` to `FOCUS_AREAS`. Nothing else. |
| `src/main/resources/prompts/analysis-system.txt` | Edits below. |
| `src/test/java/sntiago05/rejuv/services/AimMechanicsCoachProviderTest.java` | New. |
| `src/test/java/sntiago05/rejuv/clients/JsonMatchMetadataSourceTest.java` | Add a mapping test. |
| `src/test/resources/fixtures/deadlock-api/match-metadata-aim.json` | New small fixture (2 players, final snapshot with shots and crit fields; one snapshot without the crit fields). |
| `src/test/java/sntiago05/rejuv/clients/AnalysisPromptTest.java` | Raise the cap to `5500`; update the pinned tie-break string; add assertions (below). |
| `src/test/java/sntiago05/rejuv/domain/AnalysisResultTest.java` | Add a test that `aim_mechanics` is accepted as `focus_area`. |
| `docs/task-reports/045-coach-aim-mechanics.md` | New: task report. |

No other file. Do **not** edit `RawMatchMetadataParser` (fallback leaves `detail` null, so the provider returns empty), `PlayerTelemetry`, `MatchTelemetry`, `AnalysisInput`, `AnalyzeService`, `CoachContextService` (providers are picked up by scanning), `MessagesRequest` (the schema derives from `FOCUS_AREAS`), `messages.properties`, `CacheConfig`, `application.yaml`, migrations, `docs/PROJECT_CONTEXT.md`. Existing tests must keep compiling and passing without edits other than those listed.

## Interfaces / signatures

### AimMechanicsCoachProvider
`@Component public class AimMechanicsCoachProvider implements CoachInsightProvider`; `public static final String KEY = "aim_mechanics"`. Constants (`static final`, each with a short comment citing the calibration):
```
Set<Integer> GUN_HEROES = Set.of(13, 25, 58, 80, 84)   // Haze, Warden, Vyper, Silver (N=4, inconclusive), Rat King
int MIN_SHOTS_NOTABLE = 500        // below this the sample is too small to judge
int MIN_SHOTS_MAJOR = 1000         // major needs a larger sample
double NOTABLE_ACCURACY_PCT = 48.0 // about the p25 of gun carries
double MAJOR_ACCURACY_PCT = 32.0   // far below the p10 (40.9)
long MIN_BULLETS_FOR_CRIT = 250    // crit rate is only meaningful above this
```
Payload: nested `public record AimMechanicsInsight` annotated like the other providers (`@JsonInclude(NON_NULL)`, `@JsonProperty` snake_case, `@JsonPropertyOrder`), fields in this order:
```
String significance      // "none" | "notable" | "major"
double accuracy_pct      // shots_hit / (shots_hit + shots_missed) * 100, one decimal
int shots                // shots_hit + shots_missed of the final snapshot
Double crit_pct          // hero_bullets_hit_crit / hero_bullets_hit * 100, one decimal; omitted unless hero_bullets_hit >= MIN_BULLETS_FOR_CRIT and both fields are non-null
```
Reuse the existing rounding helper of the other providers (search first; do not copy a new one).

### Rules
- Inputs: the player's final snapshot (greatest `timeS`) and its `detail`.
- Degradation (`Optional.empty()`): `request`/`telemetry`/`player` null; hero not in `GUN_HEROES`; no snapshots or final `detail == null` (raw fallback); `shots < MIN_SHOTS_NOTABLE`.
- Significance: `major` if `accuracy_pct < MAJOR_ACCURACY_PCT` and `shots >= MIN_SHOTS_MAJOR`; `notable` if not major and `accuracy_pct < NOTABLE_ACCURACY_PCT`; otherwise `none`. Crit rate never changes it.
- Deterministic; payload at or below 1,200 characters; no logging beyond one `DEBUG` line without payload.

### Prompt edits (`analysis-system.txt`; English; keep structure and numbering)
1. Add `aim_mechanics` to the coach key list in section 1.
2. Add one meaning line after `objectives`: `aim_mechanics: significance, accuracy_pct (shots hit over shots fired), shots, crit_pct (share of hero bullets that were crits, only present with enough bullets); only for gun-heroes, crit_pct is context and never the leak by itself.`
3. Tie-break order becomes `deaths, phases, objectives, aim_mechanics, build, matchup, recent_form`.
4. Add `"aim_mechanics"` to the `focus_area` list.
5. Nothing else; never explain what a shot is or what causes low accuracy. The prompt must stay under `5500` characters (currently 4,823).

## Behavior
- Same input gives the same output. The key is registered by component scanning and dropped by `CoachContextService` when `none`.
- Release note for the report: until a live `/analyze` is checked, it is not verified that Anthropic accepts the longer enum or that the model uses the key correctly.

## Out of scope
- `mitigation` (needs calibration first), `damage_matrix`, weapon-vs-ability splits, pellet counting, benchmarks fetched from the API, strength output for good aim, the raw fallback, display of `focus_area`.

## Tests
- `AimMechanicsCoachProviderTest`: happy path asserting the exact payload; every hero in `GUN_HEROES` accepted and a non-gun hero returns empty; boundaries (47.9 / 48.0, 31.9 / 32.0 with 999 / 1000 shots, 499 / 500 shots); crit omitted at 249 bullets and present at 250, omitted when a crit field is null; `detail == null` and no snapshots return empty; final snapshot is used, not the first; crit never changes significance; payload size; determinism; `key()` is `aim_mechanics`.
- `JsonMatchMetadataSourceTest`: the new fixture maps both fields; an absent crit field stays `null`.
- `AnalysisPromptTest`: cap 5500; contains `aim_mechanics`, `accuracy_pct`, `crit_pct`; the `focus_area` line contains `"aim_mechanics"`; the tie-break string equals the new order; existing assertions stay green.
- `AnalysisResultTest`: `aim_mechanics` accepted; unknown still `null`. `AnalysisClientTest` unchanged and green.
- Whole suite: `.\mvnw.cmd verify` (Docker Desktop running); report baseline and final counts.

## Documentation
Task report `docs/task-reports/045-coach-aim-mechanics.md` (status, files, commands run, final prompt length, deviations, the Silver caveat, the unverified shot-counting semantics, pending live `/analyze` check, and that `mitigation` is deferred). Do not edit `docs/PROJECT_CONTEXT.md`; list under "Pending integration": key `aim_mechanics`, constants and rules, new `SnapshotDetail` fields, prompt and enum changes, new tie-break order.

## Acceptance criteria
- [ ] Compiles, all tests pass (`.\mvnw.cmd verify`)
- [ ] Unit tests as listed, including every boundary
- [ ] Only the listed files changed; no new HTTP call, dependency, migration or message key
- [ ] `aim_mechanics` is in `FOCUS_AREAS` and in the prompt; prompt under 5,500 characters
- [ ] Report states the Silver caveat and that `mitigation` is deferred
