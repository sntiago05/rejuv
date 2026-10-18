# Task 043: Coach layer `objectives` (Mid Boss claims, Rejuvenator steals, boss damage share)

## Status
Implemented on `feature/043-coach-objectives`. `.\mvnw.cmd verify` passes (1017 tests run, 0 failures, 14 skipped). Not pushed, not merged.

## Summary
Added coach insight layer under key `objectives`, providing pre-analyzed objective facts (Mid Boss claims, Rejuvenator steals, boss damage contribution) from telemetry already fetched by `GET /v1/matches/{id}/metadata`:
1. **DTO & Domain Telemetry Mapping**:
   - `MatchMetadataResponse.MatchInfo` maps `@JsonProperty("mid_boss") List<MidBoss> midBoss` with `MidBoss(team_killed, team_claimed, destroyed_time_s)`.
   - `MatchMetadataResponse.Stat` maps `@JsonProperty("boss_damage") Long bossDamage`.
   - `MidBossEvent(int timeS, int teamKilled, int teamClaimed)` record with `boolean stolen()` helper (`teamKilled != teamClaimed`).
   - `MatchTelemetry` extended with component `List<MidBossEvent> midBoss` (nullable; compact constructor sorts non-null list by `timeS`; 4-arg and 3-arg constructors preserved).
   - `PlayerTelemetry` extended with component `Long bossDamage` (nullable; 9-arg and 6-arg constructors preserved).
   - `JsonMatchMetadataSource` maps `mid_boss` (skipping entries with null fields, returning empty list if absent) and sets player `bossDamage` from the stat snapshot with maximum `timeStampS`.
2. **Coach Provider (`ObjectivesCoachProvider`)**:
   - `@Component` implementing `CoachInsightProvider` under key `"objectives"`.
   - Serializes nested `ObjectivesInsight` with fields `significance`, `mid_boss_team`, `mid_boss_enemy`, `rejuvenator_stolen_by_enemy`, `rejuvenator_stolen_by_team`, `player_boss_damage`, `boss_damage_share_pct`, `first_mid_boss_min` (`@JsonInclude(NON_NULL)` and `@JsonPropertyOrder`).
   - Degradation: returns `Optional.empty()` if request/match/telemetry/player is null, telemetry `midBoss == null` (raw fallback indicator), or `durationS < MIN_DURATION_S` (720s). If `midBoss` is empty and duration >= 720s, returns insight with `significance = "none"` (context service drops it).
   - Significance evaluation:
     - `major`: `mid_boss_enemy >= 2` or `rejuvenator_stolen_by_enemy >= 1`.
     - `notable`: not major and any of: loss with `mid_boss_enemy >= 1`; `rejuvenator_stolen_by_team >= 1`; `mid_boss_team >= 1` and `boss_damage_share_pct >= 25.0` (`STRENGTH_SHARE_PCT`).
     - `none`: otherwise.
3. **Release Note**:
   Until the prompt task (a later task) explains the `objectives` key to Haiku and extends the `focus_area` enum, the model sees an unexplained `objectives` object in its input and cannot return `focus_area: objectives`. This is completely harmless for tests and existing pipelines, but the branch should not be deployed to public users before that task.

## Important Files
- Created:
  - `src/main/java/sntiago05/rejuv/domain/MidBossEvent.java`: Domain record representing a Mid Boss death and claim event with `stolen()` predicate.
  - `src/main/java/sntiago05/rejuv/services/ObjectivesCoachProvider.java`: Component implementing `CoachInsightProvider` for `"objectives"`.
  - `src/test/java/sntiago05/rejuv/services/ObjectivesCoachProviderTest.java`: 16 unit tests covering happy path, all significance rules, threshold boundaries, degradation, team symmetry, damage share omission edge cases, determinism, and payload size restriction (<= 1,200 chars).
  - `src/test/resources/fixtures/deadlock-api/match-metadata-objectives.json`: Test fixture with 4 players, 2 mid boss events (including a steal), and multiple snapshots per player.
  - `docs/task-reports/043-coach-objectives.md`: This task report.
- Modified:
  - `src/main/java/sntiago05/rejuv/clients/dto/MatchMetadataResponse.java`: Added `mid_boss` to `MatchInfo`, new `MidBoss` record, and `boss_damage` to `Stat`.
  - `src/main/java/sntiago05/rejuv/domain/MatchTelemetry.java`: Added `midBoss` component with sorting in compact constructor and backward-compatible overloads.
  - `src/main/java/sntiago05/rejuv/domain/PlayerTelemetry.java`: Added `bossDamage` component with backward-compatible overloads.
  - `src/main/java/sntiago05/rejuv/clients/JsonMatchMetadataSource.java`: Mapped `mid_boss` and greatest snapshot `boss_damage`.
  - `src/test/java/sntiago05/rejuv/clients/JsonMatchMetadataSourceTest.java`: Added tests for objectives fixture mapping, absent `mid_boss` list behavior, and raw fallback parser null assertions.

## Decisions
- **Zero New HTTP Calls**: Reused match metadata already downloaded by `JsonMatchMetadataSource` without contacting external endpoints.
- **Raw Protobuf Fallback Unchanged**: Per spec, `RawMatchMetadataParser` was not edited; its output retains `midBoss() == null` and `bossDamage() == null`, causing `ObjectivesCoachProvider` to gracefully degrade to `Optional.empty()`.
- **Rounding and Number Formatting**: Used `BigDecimal.valueOf(val).setScale(1, RoundingMode.HALF_UP).doubleValue()`, adhering to `docs/coach-provider-rules.md` and preventing floating-point rounding quirks on boundary checks (e.g. 24.9% vs 25.0%).

## Verification
- Baseline `.\mvnw.cmd verify`: BUILD SUCCESS, 998 tests run, 0 failures, 14 skipped (47.030 s).
- Targeted test runs:
  - `.\mvnw.cmd -q test -Dtest=JsonMatchMetadataSourceTest`: BUILD SUCCESS, 14 tests run.
  - `.\mvnw.cmd -q test -Dtest=ObjectivesCoachProviderTest`: BUILD SUCCESS, 16 tests run.
  - `.\mvnw.cmd -q test -Dtest=CoachContextServiceTest`: BUILD SUCCESS, 12 tests run.
  - `.\mvnw.cmd -q test -Dtest=AnalyzeServiceTest`: BUILD SUCCESS, 22 tests run.
- Final `.\mvnw.cmd verify`: BUILD SUCCESS, 1017 tests run (+19 new tests: 16 in `ObjectivesCoachProviderTest` and 3 in `JsonMatchMetadataSourceTest`), 0 failures, 14 skipped (40.990 s).
- `git diff --check`: Clean (no whitespace errors).
- Scope check: Exactly the files listed in the spec table plus this report; no other files modified.

## Deviations
None.

## Known Issues
None.

## Merge Risks
None. Auto-registered via Spring component scanning implementing `CoachInsightProvider`. Existing code and fallback parsers continue to operate unchanged.

## Pending Integration
`docs/PROJECT_CONTEXT.md` was not edited directly per parallel worktree policy (active worktrees `039`, `040`, `041`, `042`).
Facts to integrate upon merge:
- New coach layer `objectives` (`ObjectivesCoachProvider`, key `"objectives"`).
- Telemetry model updates: `MatchTelemetry.midBoss` (`List<MidBossEvent>`) and `PlayerTelemetry.bossDamage` (`Long`, final cumulative boss damage), mapped by `JsonMatchMetadataSource` (`RawMatchMetadataParser` leaves them `null`).
- Significance rules: `major` if enemy mid boss claims >= 2 or rejuvenator stolen by enemy >= 1; `notable` if loss and enemy claims >= 1, rejuvenator stolen by team >= 1, or team claims >= 1 with player boss damage share >= 25.0%; `none` otherwise.
