# Task 038: `/compare` (premium): your recent games against top-rank players on the same hero

- **Branch**: `feature/038-compare-premium`
- **Implementer**: Antigravity
- **Related ADRs**: ADR-0013 (top-rank comparison, premium), ADR-0016 (presentation); presentation convention in `docs/implementation-plan.md`
- **Depends on**: 021 (merged: `PremiumFeatureGate`), 007 (merged). Second half of the original task 021.

## Goal
`/compare [hero:<name>]` shows how the caller's last games on one hero compare with the benchmarks of top-rank players (tier 11) and, for reference, with the caller's own rank tier. It is a premium command gated by `PremiumFeatureGate`. No AI call and no new client method.

Deviation from ADR-0013, deliberate: item timings and damage per minute are **not** included, because they need match telemetry per game (one metadata request per game, with a strict Steam quota). They are a follow-up that can reuse this command's service.

## Files to create or modify
| File | Change |
|---|---|
| `services/CompareService.java` | **New.** |
| `domain/CompareReport.java`, `domain/MetricGap.java` | **New** records. |
| `services/NotEnoughGamesException.java` | **New** (`RuntimeException`). |
| `commands/CompareCommand.java` | **New** slash command. |
| `src/main/resources/messages.properties` | Add keys at the end; add `/compare` to `help.body`. |
| Tests | **New**: `CompareServiceTest`, `CompareCommandTest`. Modify: `HelpCommandTest`. |
| `docs/task-reports/038-compare-premium.md` | **New.** |

Do not edit `DeadlockApiClient`, `CacheConfig`, `MetricsService`, `PremiumFeatureGate` or any other file. `docs/PROJECT_CONTEXT.md`: pending integration.

## Interfaces / signatures
```java
// services/CompareService.java   @Service, two-constructor Clock pattern is not needed (no time logic)
public CompareReport compare(long accountId, String heroInput)   // heroInput null/blank = hero of the latest match in the history
// @throws HeroNotFoundException, NoMatchHistoryException, NotEnoughGamesException (new, RuntimeException, in services/)

// domain/MetricGap.java
public record MetricGap(String metricKey, double playerValue, double topP50, Double rankP50, double gapPercent, boolean playerBetter) {}
// domain/CompareReport.java
public record CompareReport(int heroId, String heroName, int games, int topTier, Integer playerRankTier, List<MetricGap> gaps) {}
```
Existing API to reuse: `DeadlockApiClient.getMatchHistory(accountId)` (cached), `getPlayerRank(accountId)`, `getBenchmarks(heroId, tier)` (`Map<String, MetricPercentiles>`, keys `net_worth_per_min`, `kills`, `deaths`, `assists`, `last_hits`, `denies`; `MetricPercentiles(avg, p25, p50, p75)`), `HeroResolver`, `GameAssets`, `CommandSupport.heroOption`, `PremiumFeatureGate`, the `MetricsService.BENCHMARK_*` constants (package-private, same package).

## Behavior
1. **Command.** `compare` with optional `hero` (`CommandSupport.heroOption`). Linked account of the caller only. Before `deferReply()`: not linked -> ephemeral `compare.not_linked`; `PremiumFeatureGate.allowed(callerId)` false -> ephemeral `compare.premium_required`. Then public `deferReply()`.
2. **Hero.** `heroInput` resolved with `HeroResolver` (unknown -> `HeroNotFoundException`); blank -> hero of the newest history entry (empty history -> `NoMatchHistoryException`).
3. **Sample.** History entries of that hero with `matchDurationS >= MIN_DURATION_S` (600), newest first, at most `MAX_GAMES` (20). Fewer than `MIN_GAMES` (5) -> `NotEnoughGamesException` -> `compare.not_enough_games` (with the minimum).
4. **Player values** (mean over the sample, one pass): `net_worth_per_min` = mean of `netWorth / (durationS / 60.0)`, `kills`, `deaths`, `assists`, `last_hits`, `denies` as per-game means.
5. **Benchmarks.** `topTier = 11` (constant). `getBenchmarks(heroId, 11)` for the top tier (required: if empty, report `compare.no_data`). `playerRankTier` = current tier from `getPlayerRank` when `badge > 0` and the tier is in 1..10; for the reference column call `getBenchmarks(heroId, thatTier)` only then (otherwise `rankP50` is null). Calls are sequential and cached.
6. **Gap.** For each metric present in the top-tier map: `gapPercent = (playerValue - topP50) / topP50 * 100` (guard `topP50 <= 0`: skip the metric). `playerBetter` = value above the top median, except `deaths` where lower is better. Order of `gaps` is fixed: souls per minute, kills, deaths, assists, last hits, denies. Round to one decimal when formatting only.
7. **Embed** (presentation convention: `EmbedStyle.COLOR`, hero card thumbnail via `AssetImages`, every field through `EmbedStyle.fitField`, hero icon through `IconCatalog.hero` + `EmbedStyle.withIcon`; must work text-only): title with hero and games count; one field "You vs top players" with a line per metric `Metric: you X · top median Y (+Z%)`, plus `· your rank median W` when available; a short footer naming the tier label (`CommandSupport.rankLabel`) and the 30-day benchmark window. No prose advice.
8. **Errors:** `HeroNotFoundException` -> `compare.hero_not_found`; `NoMatchHistoryException` -> `compare.no_matches`; `NotEnoughGamesException` -> `compare.not_enough_games`; `DeadlockApiUnavailableException` -> `error.data_source_busy`; other `RuntimeException` -> `CommandSupport.handleUnexpectedError`. One INFO log per command (user id, command, duration, outcome).

## Messages (English, at the end of `messages.properties`)
`compare.description`, `compare.option.hero`, `compare.not_linked`, `compare.premium_required`, `compare.hero_not_found`, `compare.no_matches`, `compare.not_enough_games`, `compare.no_data`, `compare.title`, `compare.field.metrics`, `compare.metric.<key>` for the six metric labels, `compare.line`, `compare.line_rank` (rank reference suffix), `compare.footer`. `/help` gains one line for `/compare` marked premium.

## Out of scope
Item timings and damage per minute (needs telemetry); AI text; comparing against another user; pro or tournament data; changes to `PremiumFeatureGate`, the client, caches or migrations; new dependencies.

## Tests
- `CompareServiceTest` (mocks): sample selection (hero filter, 600 s cut, cap 20, newest first), 4 vs 5 games, hand-computed means and gaps, `deaths` direction, metric missing in the benchmark map skipped, `topP50 <= 0` skipped, empty top benchmarks, rank reference present / absent (unranked, tier 11, tier 0), blank hero uses the newest entry, unknown hero, empty history.
- `CompareCommandTest`: not-linked and not-premium replies are ephemeral and trigger no service call and no deferral; happy path embed with fields, thumbnail and icons; text-only embed with an empty icon catalog (mandatory); every error branch; every `compare.*` key resolves; `help.body` mentions `/compare`.

## Verification
`./mvnw clean verify` (on this machine `.\mvnw.cmd clean verify`, Docker running); counts in the report. Manual Discord check if a token is available, otherwise "not run".

## Acceptance criteria
- [ ] Compiles, all tests pass, counts recorded
- [ ] Non-premium and not-linked users get ephemeral refusals and cause no API calls
- [ ] Output follows the presentation convention and works without emojis
- [ ] No new client method, DTO, cache, dependency or migration; no file outside the list changed
- [ ] Task report created; `docs/PROJECT_CONTEXT.md` update marked pending integration
- [ ] Follows AGENTS.md conventions
