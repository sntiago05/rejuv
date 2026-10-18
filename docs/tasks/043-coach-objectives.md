# Task 043: Coach layer `objectives` (Mid Boss claims, Rejuvenator steals, boss damage share)

- **Branch**: `feature/043-coach-objectives`
- **Implementer**: Antigravity
- **Related ADRs**: ADR-0017 (metadata sources)
- **Depends on**: 029 and 040 merged (done). Must read `docs/coach-provider-rules.md` first; this spec overrides it only where it says so.
- **Evidence** (local, gitignored): `docs/evidence/metadata-coach/02-provider-designs.md` section 2, `03-validation-on-samples.md`, raw samples `docs/evidence/metadata-coach/raw/match-*-metadata.json` (10 matches). Note: the thresholds were validated on only 10 matches, so this spec is deliberately more conservative than the evidence design.

## Goal
Add a new coach layer with key `objectives` that tells the model, from data already downloaded in `GET /v1/matches/{id}/metadata`, which team took the Mid Boss, whether a Rejuvenator was stolen, and how much of the team's boss damage the player did. Zero new HTTP calls.

## Why (verified)
- `match_info.mid_boss[]` has entries `{team_killed, team_claimed, destroyed_time_s}`; `team_killed != team_claimed` means the Rejuvenator crystal was stolen. Example from match 119330211: `[{"team_killed":0,"team_claimed":0,"destroyed_time_s":1715},{"team_killed":0,"team_claimed":0,"destroyed_time_s":2301}]`. An empty or absent list means the Mid Boss was not killed (or not reported).
- Each `players[].stats[]` snapshot has cumulative `boss_damage` (int64; 0 or a positive number in the samples).
- Rejuv maps none of these today (`MatchMetadataResponse` has no `mid_boss` or `boss_damage`; the raw protobuf fallback also does not carry them).
- The `objectives[]` list (guardians/walkers/patron) is **not** used here: its `team_objective_id` mapping is not verified.

## Files to create or modify
| File | Change |
|---|---|
| `src/main/java/sntiago05/rejuv/clients/dto/MatchMetadataResponse.java` | Add `@JsonProperty("mid_boss") List<MidBoss> midBoss` to `MatchInfo`; new record `MidBoss(team_killed Integer, team_claimed Integer, destroyed_time_s Integer)`; add `@JsonProperty("boss_damage") Long bossDamage` to `Stat`. |
| `src/main/java/sntiago05/rejuv/domain/MidBossEvent.java` | New record `MidBossEvent(int timeS, int teamKilled, int teamClaimed)` with `boolean stolen()` (`teamKilled != teamClaimed`). |
| `src/main/java/sntiago05/rejuv/domain/MatchTelemetry.java` | Add component `List<MidBossEvent> midBoss` (nullable: `null` means "unknown", empty means "no Mid Boss kill"). Keep the current 4-argument and 3-argument constructors as overloads delegating with `null`. The compact constructor copies the list sorted by `timeS` when not null. |
| `src/main/java/sntiago05/rejuv/domain/PlayerTelemetry.java` | Add component `Long bossDamage` (final cumulative boss damage, nullable). Keep the current 9-argument and 6-argument constructors as overloads delegating with `null`. |
| `src/main/java/sntiago05/rejuv/clients/JsonMatchMetadataSource.java` | Map `mid_boss` (skip entries with a null field; empty list when absent but the response is valid) and the `boss_damage` of the snapshot with the greatest `time_stamp_s`. |
| `src/main/java/sntiago05/rejuv/services/ObjectivesCoachProvider.java` | New provider (below). |
| `src/test/java/sntiago05/rejuv/services/ObjectivesCoachProviderTest.java` | New. |
| `src/test/java/sntiago05/rejuv/clients/JsonMatchMetadataSourceTest.java` | Add a test for the new mapping. |
| `src/test/resources/fixtures/deadlock-api/match-metadata-objectives.json` | New small fixture (2 players per team is enough, with `mid_boss` and `boss_damage`). |
| `docs/task-reports/043-coach-objectives.md` | New: task report. |

No other file. Do **not** edit `RawMatchMetadataParser` (the fallback leaves both new values `null`), `AnalysisInput`, `AnalyzeService`, `CoachContextService`, `MetricsService`, the prompt, `MessagesRequest`, `AnalysisResult`, `messages.properties`, `CacheConfig`, or `DeadlockApiClient`. The prompt and the `focus_area` enum are handled by a later task (do not add them here). Existing tests must keep compiling without edits other than those listed.

## Interfaces / signatures

### ObjectivesCoachProvider
`@Component public class ObjectivesCoachProvider implements CoachInsightProvider`; `public static final String KEY = "objectives"`. Payload: nested `public record ObjectivesInsight` annotated like the other providers (`@JsonInclude(NON_NULL)`, `@JsonProperty` snake_case, `@JsonPropertyOrder`), fields in this order:
```
String significance                    // "none" | "notable" | "major"
int mid_boss_team                      // Mid Boss claims by the player's team
int mid_boss_enemy                     // Mid Boss claims by the enemy team
int rejuvenator_stolen_by_enemy        // our team killed it, enemy claimed
int rejuvenator_stolen_by_team         // enemy killed it, our team claimed
Long player_boss_damage                // omitted when null
Double boss_damage_share_pct           // player share of own team's boss damage, 0-100, one decimal; omitted when team total <= 0 or damage unknown
Double first_mid_boss_min              // minute of the first event, one decimal; omitted when there are no events
```
Reuse the rounding helper used by the other providers (search `DeathsCoachProvider`/`MetricsService` first; do not copy a new one).

### Inputs and counting rules
- Player team = `request.player().team()`; win = `request.match().matchResult() == request.match().playerTeam()` (same inference as the rest of the code).
- `mid_boss_team` = events with `teamClaimed == playerTeam`; `mid_boss_enemy` = events with `teamClaimed != playerTeam`.
- `rejuvenator_stolen_by_enemy` = events with `teamKilled == playerTeam && teamClaimed != playerTeam`; `rejuvenator_stolen_by_team` = events with `teamKilled != playerTeam && teamClaimed == playerTeam`.
- Boss damage share = player's `bossDamage` divided by the sum of `bossDamage` of all players with the same team (treat null as unknown; if the player's value is null, omit the damage fields; if a teammate's is null, count it as 0 and still compute).

### Degradation (return `Optional.empty()`)
`telemetry == null`, `player == null`, `match == null`, `telemetry.midBoss() == null` (raw fallback), or match duration below `MIN_DURATION_S = 720` (conservative: there is nothing to say before the boss can matter; comment that the value is a conservative choice, not an observed spawn time). If `midBoss` is empty and the significance would be `none`, still return the insight with `none` (the context service drops it).

### Significance (constants `static final`, each with a short comment)
- `major`: `mid_boss_enemy >= 2`, or `rejuvenator_stolen_by_enemy >= 1`.
- `notable`: not major and any of: loss and `mid_boss_enemy >= 1`; `rejuvenator_stolen_by_team >= 1`; `mid_boss_team >= 1` and `boss_damage_share_pct >= 25.0` (`STRENGTH_SHARE_PCT`, strength signal).
- `none` otherwise (including a win where the enemy took one boss, and both teams not contesting).
The evidence proposed a third `major` rule (loss over 28 min with the enemy boss and a share under 5%); it is **dropped** because the 10 samples disagree with it (a 34 min loss with 2.1% share was classified `notable`). Do not add it.

## Behavior
- Registered automatically by component scanning; `CoachContextService` runs it with the others and drops it when `none`.
- The payload stays at or below 1,200 characters (it is about 250).
- Same input gives the same output. No logging beyond one `DEBUG` line without payload.
- Release note for the report: until the prompt task (a later one) explains this key and extends the `focus_area` enum, the model sees an unexplained `objectives` object and cannot return `focus_area: objectives`. This is harmless for tests but the branch should not be deployed to users before that task; say so in the report.

## Out of scope
- `objectives[]` structures, base guardian timing, objective damage; `damage_matrix`; the raw-protobuf fallback; prompt, schema enum, `AnalysisResult.FOCUS_AREAS`; embed/web display.
- Any change to the thresholds of other providers.

## Tests
- `ObjectivesCoachProviderTest`: happy path with a realistic match asserting the exact payload; each rule (enemy 2 claims `major`; steal by enemy `major`; loss with one enemy claim `notable`; win with one enemy claim `none`; steal by team `notable`; share 25.0 vs 24.9 boundary; no events `none`; both teams equal); degradation cases (null telemetry, null player, null match, `midBoss == null`, duration 719 vs 720, player team 0 and team 1 symmetric); share omitted when team total is 0 or the player's damage is null; boundary of every constant; determinism; payload size at or below 1,200 characters; `key()` is `objectives`.
- `JsonMatchMetadataSourceTest`: a new test with the new fixture proving `midBoss` events and `bossDamage` are mapped (the final snapshot wins), and that a response without `mid_boss` yields an empty list, not `null`. Existing tests unchanged.
- A mapping test or an assertion that `RawMatchMetadataParser` output has `midBoss() == null`.
- Whole suite: `.\mvnw.cmd verify` passes; report baseline and final counts.

## Documentation
Task report `docs/task-reports/043-coach-objectives.md` (status, files, commands run, deviations, the release note above). Do not edit `docs/PROJECT_CONTEXT.md`; list under "Pending integration": new key `objectives`, new telemetry fields `midBoss` and `bossDamage` (JSON adapter only), rules and thresholds.

## Acceptance criteria
- [ ] Compiles, all tests pass (`.\mvnw.cmd verify`)
- [ ] Unit tests as listed, including every threshold boundary
- [ ] Follows AGENTS.md and `docs/coach-provider-rules.md`; only the listed files changed
- [ ] No new HTTP call, no new dependency, no user-facing text
- [ ] Report mentions the not-yet-deployable-until-prompt-task note
