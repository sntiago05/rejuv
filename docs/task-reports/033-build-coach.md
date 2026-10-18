# Task 033: Coach layer "build" (final build against popular build of hero and rank)

## Status
Implemented on `feature/033-build-coach`. `.\mvnw.cmd clean verify` passes (807 tests, 0 failures, 2 skipped). Not pushed, not merged.

## Summary
Task 033 implements `BuildCoachProvider`, a new `CoachInsightProvider` under key `"build"`:
1. **Kept Upgrades**: Evaluates player item purchases with `soldAtS == null` where the corresponding `ItemAsset` has `type == "upgrade"`, collapsing duplicated item IDs.
2. **Popular Flow Integration**: Invokes `BuildService.getBuild(heroName, rankTier)` once, de-duplicating popular items across all build phases by item ID while preserving the highest pick rate.
3. **Missing Popular Items**: Identifies popular items with `pickRatePercent >= 35.0` that were never bought by the player during the match (`allBoughtItemIds`), sorted by pick rate descending then item ID ascending (capped at 3).
4. **Off-Meta Items**: Identifies kept upgrade items not present in the popular list, sorted by asset cost descending (null cost counted as 0) then item ID ascending (capped at 3). Items with unknown names are skipped.
5. **Match Rates**: Computes `matching_popular`, `kept_count`, `popular_count`, and `match_rate_pct = matching_popular / min(kept_count, popular_count) * 100` with divide-by-zero guards for zero counts.
6. **Significance**: Assesses significance as `"major"` when `missing_popular` has an item with `pick_rate >= 55.0` and `win_rate >= 52.0`; `"notable"` when `missing_popular` is non-empty or `off_meta.size() >= 3`; otherwise `"none"`.
7. **Failure Isolation & Missing Data**: Gracefully returns `Optional.empty()` on null telemetry/player, unranked/null/0 rank tier, missing upgrade purchases, unresolvable hero name, empty build phases, or when `BuildService` throws `HeroNotFoundException`, `IllegalArgumentException`, or `DeadlockApiException` (logged once at WARN with exception class name).

## Important Files
- **Created**:
  - `src/main/java/sntiago05/rejuv/services/BuildCoachProvider.java`: Provider implementation with Jackson 3 `@JsonProperty` records for `BuildCoachPayload`, `MissingItem`, and `OffMetaItem`.
  - `src/test/java/sntiago05/rejuv/services/BuildCoachProviderTest.java`: 17 unit tests verifying full happy path, thresholds, cap and tie-breaking, significance boundaries, off-meta ordering, missing data, exceptions, determinism, and payload size.
  - `docs/task-reports/033-build-coach.md`: This task report.
- **Modified**:
  - None.

## Decisions
- **Hero & Item Name Resolution**: Hero name resolves from `request.heroes()` via `name()` falling back to `className()`. Popular item names resolve from `BuildItem.itemName()` falling back to `ItemAsset.name()`. Items with null or blank names are skipped.
- **Upgrade Definition**: Filtered strictly on `ItemAsset.type.equalsIgnoreCase("upgrade")`. If the player made no upgrade purchases in the match, the provider returns `Optional.empty()`.
- **Zero-Count Guarding**: Guarded `min(kept_count, popular_count) <= 0` to yield `0.0` match rate when all upgrades were sold before match end (`kept_count == 0`), preventing NaN or division by zero.
- **Payload Size & Structure**: Designed `BuildCoachPayload` records with `@JsonInclude(NON_NULL)`. Tested with full multi-phase matches; serialized JSON size is ~350-450 characters, well under the 1,200 character ceiling.

## Verification
- Baseline `.\mvnw.cmd clean verify`: BUILD SUCCESS, 790 tests run, 0 failures, 2 skipped (39.459 s).
- Final `.\mvnw.cmd clean verify`: BUILD SUCCESS, 807 tests run (+17 tests), 0 failures, 2 skipped (54.813 s).
- Targeted test run:
  - `.\mvnw.cmd test -Dtest=BuildCoachProviderTest`: 17 tests passed (exit code 0).
  - `.\mvnw.cmd test -Dtest=CoachContextServiceTest`: 9 tests passed (exit code 0).
- `git diff --check`: Clean, no formatting or whitespace issues.
- Scope: Only `BuildCoachProvider.java`, `BuildCoachProviderTest.java`, and `033-build-coach.md` created.

## Deviations
None.

## Known Issues
None.

## Merge Risks
None. The provider is a standalone `@Component` discovered automatically via Spring component scan. No existing signatures or schemas modified.

PROJECT_CONTEXT update pending integration (parallel task branches `018`, `031`, `032`, `034`, `035`, `038` are active). Facts to add upon integration:
- `BuildCoachProvider` registers coaching insight under key `"build"`, comparing kept upgrades with popular build items from `BuildService`.
- Payload reports `significance`, `kept_count`, `popular_count`, `matching_popular`, `match_rate_pct`, `missing_popular`, and `off_meta`.
