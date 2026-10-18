# Task 033: Coach layer "build" (final build against the popular build of the hero and rank)

- **Branch**: `feature/033-build-coach`
- **Implementer**: Antigravity
- **Related ADRs**: none
- **Depends on**: 029 (merged), 011 (`BuildService`, merged). Must read `docs/coach-provider-rules.md` first.

## Goal
Compare what the player actually kept in the build with what players of the same hero and rank usually buy, so the model can say "you skipped X, which 62% of players buy and which wins 55%" or "you bought Y that almost nobody builds". The existing AI input only has the first three purchases and the first tier-3 item.

## Files to create or modify
| File | Change |
|---|---|
| `services/BuildCoachProvider.java` | **New.** |
| `src/test/java/sntiago05/rejuv/services/BuildCoachProviderTest.java` | **New.** |
| `docs/task-reports/033-build-coach.md` | **New.** |

Nothing else. `docs/PROJECT_CONTEXT.md`: pending integration.

## Interfaces / signatures
```java
@Component
public class BuildCoachProvider implements CoachInsightProvider {
    public static final String KEY = "build";
    public BuildCoachProvider(BuildService buildService)
    @Override public String key()
    @Override public Optional<CoachInsight> provide(CoachRequest request)
}
```
Existing API to reuse (do not reimplement flow parsing): `BuildService.getBuild(String heroInput, Integer rankTier)` returns `domain/HeroBuild` (`phases` -> `BuildPhase.items` -> `BuildItem(itemId, itemName, matches, pickRatePercent, winRatePercent)`, items already filtered to at least `BuildService.MIN_ITEM_MATCHES` matches, at most 4 items per phase). It accepts `rankTier` null or 1..11 and throws `HeroNotFoundException`, `IllegalArgumentException` (tier outside 1..11) or `DeadlockApiException`.
Player data: `request.player().items()` (`ItemPurchase(itemId, boughtAtS, soldAtS)`), `request.items()` (assets: `type`, `itemTier`, `name`), `request.heroes()`.

## Behavior
1. Return `Optional.empty()` when `telemetry`/`player` is null, `rankTier` is null or `0`, the player has no purchases of upgrade items, or the hero name cannot be resolved from `request.heroes()` (use its `name`, falling back to `className`).
2. **Kept items.** Purchases with `soldAtS == null` whose asset has `type == "upgrade"`; collapse repeated ids. This is the set `kept`. (Items sold during the match are not part of the final build.)
3. Call `buildService.getBuild(heroName, rankTier)` once. `HeroNotFoundException`, `IllegalArgumentException` and `DeadlockApiException` are expected conditions: catch them, log one WARN with the exception class, and return empty. Empty `phases` returns empty.
4. **Popular items** = all `BuildItem`s of all phases, de-duplicated by item id keeping the highest pick rate.
5. `missing_popular`: popular items with `pickRatePercent >= MISSING_MIN_PICK_PERCENT` (35.0) that are not in `kept` (and were not bought at all); sorted by pick rate desc then item id; cap 3. Fields: `item`, `pick_rate`, `win_rate`.
6. `off_meta`: items in `kept` that are **not** among the popular items; cap 3 sorted by `ItemAsset.cost` descending (null cost counts as 0), then item id ascending. Fields: `item` only (the flow data has no statistics for unpopular items; do not invent them).
7. `matching_popular`: number of `kept` items that are among the popular ones, and `kept_count` (kept upgrades). `match_rate_pct` = `matching_popular / min(kept_count, popular_count) * 100`, `popular_count` = popular items count.
8. **Significance:** `major` when `missing_popular` has an entry with `pick_rate >= 55.0` and `win_rate >= 52.0`; `notable` when `missing_popular` is non-empty or `off_meta.size() >= 3`; else `none`. Constants named and commented. This is correlational (popular does not mean correct): the payload carries no judgement words.
9. Payload:
```json
{"significance":"notable","kept_count":12,"popular_count":16,"matching_popular":8,"match_rate_pct":66.7,
 "missing_popular":[{"item":"Soul Rebirth","pick_rate":48.2,"win_rate":53.1}],
 "off_meta":[{"item":"Mystic Burst"}]}
```
Item names come from the popular list (`itemName`) or the asset; skip an item whose name is unknown.

## Out of scope
Ability order; item buy times (already in `AnalysisInput.items`); changes to `BuildService`, `DeadlockApiClient`, DTOs or caches; flow analysis per phase; recommending a replacement item.

## Tests (plus the required cases in the rules file)
- Mocked `BuildService`: full happy path with 3 phases; kept items sold earlier are excluded; non-upgrade items ignored; duplicated ids collapsed.
- `missing_popular` threshold at 34.9 / 35.0 and cap 3 with tie-breaking by item id; `major` boundary at pick 54.9 / 55.0 and win 51.9 / 52.0.
- `off_meta` listing, cap 3, deterministic order; item without name skipped.
- `rankTier` null and `0` return empty and never call `buildService`.
- `HeroNotFoundException`, `IllegalArgumentException`, `DeadlockApiUnavailableException` from `getBuild` return empty without propagating.
- Empty `HeroBuild.phases` returns empty; `match_rate_pct` when `kept_count` or `popular_count` is 0 (guard divide by zero).
- Exactly one `getBuild` call per `provide`.

## Verification
`./mvnw clean verify` (on this machine `.\mvnw.cmd clean verify`, Docker running); counts in the report.

## Acceptance criteria
- [ ] Compiles, all tests pass, counts recorded
- [ ] Provider follows every rule in `docs/coach-provider-rules.md`; payload at most 1,200 characters
- [ ] Reuses `BuildService`; no new client method, DTO or cache
- [ ] Only the three files above created; nothing else changed
- [ ] Task report created; `docs/PROJECT_CONTEXT.md` update marked pending integration
- [ ] Follows AGENTS.md conventions
