# Task 038: `/compare` (premium): your recent games against top-rank players on the same hero

## Status
Implemented on `feature/038-compare-premium`. `.\mvnw.cmd verify` passes (816 tests, 0 failures, 2 skipped). Not pushed, not merged.

## Summary
Added the `/compare [hero:<name>]` slash command for a player's linked account to compare their recent games (up to 20 matches of 10+ minutes, minimum 5) on a hero against the top-rank (tier 11) benchmarks and, for reference, against their own rank tier median (tiers 1..10). The command is gated by `PremiumFeatureGate`, rejecting non-linked and non-premium callers with ephemeral messages without invoking external APIs or deferring the interaction.

The embed follows the presentation convention: `EmbedStyle.COLOR`, hero card thumbnail via `AssetImages`, hero icon through `IconCatalog.hero` + `EmbedStyle.withIcon`, field limits through `EmbedStyle.fitField`, and degrades cleanly to text-only mode when emojis are absent.

## Important Files
- `domain/MetricGap.java` (new): record representing a single metric gap comparison (`metricKey`, `playerValue`, `topP50`, `rankP50`, `gapPercent`, `playerBetter`).
- `domain/CompareReport.java` (new): record holding the comparison report data (`heroId`, `heroName`, `games`, `topTier`, `playerRankTier`, `gaps`).
- `services/NotEnoughGamesException.java` (new): runtime exception thrown when fewer than 5 eligible games are found in history for the selected hero.
- `services/CompareService.java` (new): business logic for hero resolution (or latest match fallback), history filtering, per-game means in a single pass, sequential benchmark fetching, and gap calculation.
- `commands/CompareCommand.java` (new): slash command `/compare` with optional `hero` option, premium gating, error handling, and rich embed formatting.
- `src/main/resources/messages.properties`: added `compare.*` keys and added `/compare` to `help.body`.
- Tests:
  - `CompareServiceTest` (new, 14 tests): sample selection (hero filter, 600s cut, cap 20, newest first), 4 vs 5 games, hand-computed means and gaps, deaths direction, missing benchmark metrics skipped, `topP50 <= 0` skipped, empty top benchmarks, rank reference present/absent (unranked, tier 11, tier 0), blank hero uses newest entry, unknown hero, and empty history.
  - `CompareCommandTest` (new, 12 tests): command data structure, ephemeral refusals for unlinked and non-premium callers, happy path embed with thumbnail/icons, text-only embed fallback, empty results, all error branches, and message catalog key resolution.
  - `HelpCommandTest` (modified): verified `/compare` presence in `help.body`.
- `docs/task-reports/038-compare-premium.md` (new): this task report.

## Decisions
- Fixed metric order: `souls_per_min`, `kills`, `deaths`, `assists`, `last_hits`, `denies`.
- Filter criteria: `matchDurationS >= 600`, at most 20 newest matches (`MAX_GAMES`), minimum 5 matches (`MIN_GAMES`).
- Player values computed in a single pass over the sample, deriving `net_worth_per_min` as mean of `netWorth / (durationS / 60.0)` and per-game averages for the other metrics.
- Top tier benchmark: fixed at tier 11 (`TOP_TIER = 11`). If top benchmarks are empty or contain no metrics passing guards, returns an empty gaps list which maps to `compare.no_data`.
- Player rank reference: queried from `getPlayerRank(accountId)` and included only when `badge > 0` and the tier is between 1 and 10; omitted (null) for unranked, tier 0, or tier 11.
- Gap percentage: `(playerValue - topP50) / topP50 * 100`, guarding `topP50 <= 0` by skipping the metric.
- Deaths direction: lower is better (`playerBetter = playerValue < topP50`), while all other metrics are higher is better (`playerValue > topP50`).
- Presentation: Embed thumbnail from `AssetImages.heroCardUrl(heroId)`, hero icon via `IconCatalog.hero(heroId)` prepended to the field title, metric lines formatted as `{0}: you {1} · top median {2} ({3})` with optional rank reference suffix ` · your rank median {0}`, and footer with tier label and 30-day benchmark window note.
- Deliberate deviation from ADR-0013: match telemetry items and damage per minute are omitted as planned by the task specification to respect Steam API request limits.

## Verification
- Baseline build (`.\mvnw.cmd verify`): 790 tests run, 0 failures, 2 skipped.
- Full verification (`.\mvnw.cmd verify`): 816 tests run, 0 failures, 2 skipped (+26 tests).
- `git diff --check`: clean (no whitespace issues or trailing empty lines).
- Scope check: `git status --short` shows only files authorized by the task spec and this closeout report.
- Privacy check: verified no tokens, API keys, or real player credentials were committed.
- Manual Discord check: Not run (no credentials/token available in this environment).

## Deviations
None. Implemented exactly according to `docs/tasks/038-compare-premium.md`.

## Known Issues
None.

## Merge Risks
- Parallel active branches: `feature/018-report-soul-curve`, `feature/031-phases-coach`, `feature/032-deaths-coach`, `feature/033-build-coach`, `feature/034-form-coach`, `feature/035-matchup-coach`.
- `docs/PROJECT_CONTEXT.md` update marked pending integration per `AGENTS.md` parallel worktree rules.
