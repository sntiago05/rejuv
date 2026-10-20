# Task 057: `/trends`, `/nemesis` and rank percentile in `/profile`

- **Branch**: `feature/057-trends-nemesis-percentile`
- **Implementer**: Antigravity
- **Related ADRs**: ADR-0014 (pure data, zero AI cost), ADR-0019 (free model)
- **Runs in parallel with**: 055 (web report and `/analyze` format) and 056 (`/draft`, `/synergy`). See "Parallel work".

## Goal
Three free, no-AI features for linked players:
1. `/trends`: how the last games are going (win rate, KDA, souls, best heroes, and whether the recent half is better or worse than the older half).
2. `/nemesis`: the opponents you lose to most often.
3. `/profile`: add the rank percentile ("top X% of ranked players").

## Verified facts (do not invent others)
- `DeadlockApiClient.getMatchHistory(long accountId)` returns `List<MatchHistoryEntry>` with `matchId, heroId, startTime, matchDurationS, matchMode, matchResult, playerTeam, playerMatchOutcome, playerKills, playerDeaths, playerAssists, netWorth, lastHits, denies, rankedDisplayBadge`. It is cached and holds up to about 200 matches. Read how `ProfileService` and `MatchesService`/`HeroPoolService` interpret `playerMatchOutcome`/`matchResult` (win or loss) and **reuse that helper**; do not guess the encoding.
- deadlock-api `GET /v1/players/{account_id}/enemy-stats` returns `[{enemy_id: int32, matches: [int64], matches_played: int64, wins: int64}]`. Parameters (all optional except the path id): `game_mode`, `min_unix_timestamp`, `max_unix_timestamp`, `min_duration_s`, `min_match_id`, `min_matches_played`, `max_matches_played`. It looks like `mate-stats` (`mate_id`), so `enemy_id` is probably an **account id**, but this is **not verified**: confirm with one real call (use your own linked account id from the dev database, never print it in the report) before coding. If `enemy_id` turns out to be a hero id, **stop and ask**: the command then changes meaning.
- deadlock-api `GET /v1/players/rank/distribution` returns `[{badge: int32, players: int64, rank: int32, subrank: int32}]` (optional filters `min_unix_timestamp`, `max_unix_timestamp`, `min_duration_s`, `is_high_skill_range_parties`, `is_low_pri_pool`, `is_new_player_pool`, `min_match_id`, `max_match_id`). `PlayerRank(badge, rank, subrank)` already exists. How `badge` orders ranks is **not verified**: check on the real response that sorting by `badge` ascending sorts the ranks from low to high before computing percentiles.
- Already in the repo: `MateStat`, `MatesService` + `MatesCommand` (copy their pattern for names: `Name (#accountId)`, best-effort Steam persona lookup, `Player #accountId` fallback, name sanitizing), `ProfileService.getProfile(long accountId)` and `ProfileCommand`, `CommandSupport`, `ServiceSupport`, `GameAssets`.
- AGENTS.md: every client read cached; honor 503 `Retry-After` once; never fire parallel uncached analytics requests; text only through `Messages`; `deferReply()` for slow commands; one INFO log line per command.

## Files to create or modify
| File | Change |
|---|---|
| `src/main/java/sntiago05/rejuv/clients/dto/EnemyStat.java` | New record (`@JsonIgnoreProperties(ignoreUnknown = true)`): `enemy_id`, `matches_played`, `wins`. Do not map the `matches` array (not needed). |
| `src/main/java/sntiago05/rejuv/clients/dto/RankDistributionEntry.java` | New record: `badge`, `players`. |
| `src/main/java/sntiago05/rejuv/clients/DeadlockApiClient.java` | Two new cached methods: `List<EnemyStat> getEnemyStats(long accountId, long minUnixTimestamp)` and `List<RankDistributionEntry> getRankDistribution()`, built like `getMateStats` and the analytics methods. Do not change other methods. |
| `src/main/java/sntiago05/rejuv/config/CacheConfig.java` and `src/test/.../DeadlockApiClientCachingTest.java` | New cache names (enemy stats: same TTL as mate stats; rank distribution: 6 hours, size 1). Add them to the exact-set assertion; keep every existing entry. |
| `src/main/java/sntiago05/rejuv/services/TrendsService.java` | New. Pure logic on `getMatchHistory`. |
| `src/main/java/sntiago05/rejuv/services/NemesisService.java` | New. Uses `getEnemyStats` and the persona lookup pattern of `MatesService`; share the lookup instead of copying it (extract to `ServiceSupport` or a small shared component only if `MatesService` already has it private; keep that refactor minimal and covered by the existing `MatesService` tests). |
| `src/main/java/sntiago05/rejuv/services/RankPercentileService.java` | New. Percentile from the distribution. |
| `src/main/java/sntiago05/rejuv/services/ProfileService.java` and its summary record | Add an optional percentile to the profile summary (empty when the rank is unknown or the distribution call fails). A failure must never break `/profile`. |
| `src/main/java/sntiago05/rejuv/commands/TrendsCommand.java`, `NemesisCommand.java` | New. `ProfileCommand.java` shows the percentile line only if present. |
| `src/main/java/sntiago05/rejuv/commands/HelpCommand.java` / `messages.properties` | List the two new commands in `/help`. |
| `src/main/resources/messages.properties` | Append keys at the **end** only: `trends.*`, `nemesis.*`, `profile.percentile`. |
| tests + fixtures | `TrendsServiceTest`, `NemesisServiceTest`, `RankPercentileServiceTest`, `ProfileServiceTest` additions, command tests, client tests with `MockRestServiceServer` and small real-shaped fixtures `enemy-stats.json`, `rank-distribution.json`. |
| `docs/task-reports/057-trends-nemesis-percentile.md` | New task report. Mark `docs/PROJECT_CONTEXT.md` as pending integration; do **not** edit it. |

No other file. If you need one, stop and ask.

## Interfaces / signatures
- `TrendsSummary TrendsService.summarize(long accountId, int games, HeroFilter filter)`; `record TrendsSummary(int games, double winRate, double avgKills, double avgDeaths, double avgAssists, double kdaRatio, long avgNetWorth, double winRateRecentHalf, double winRateOlderHalf, List<HeroLine> topHeroes)` and `record HeroLine(int heroId, int games, double winRate)`. Adjust the filter type to the existing one used by `/matches` if it fits (`MatchFilter` exists in `domain/`); otherwise omit the filter.
- `NemesisSummary NemesisService.nemeses(long accountId)`; `record NemesisLine(long accountId, String name, long matches, long wins, double winRate)`. Names best effort as in `MatesService`.
- `OptionalDouble RankPercentileService.percentile(PlayerRank rank)` returning the share (0 to 100) of players **below** the given badge.
- Commands: `/trends [games:<10|20|50>] [user:<member>]` (default 20) and `/nemesis [user:<member>]`, same `user` semantics as `/mates` (ephemeral "not linked" message otherwise).

## Behavior
1. **`/trends`.** Take the most recent `games` matches of the history (history is newest first; verify). Fewer than 5 games: friendly "not enough games" reply. Compute: win rate, average K/D/A, KDA ratio `(K+A)/max(1,D)`, average net worth, win rate of the newest half versus the oldest half (state the difference in points), top 3 heroes by games (minimum 2 games). Direction text: improving, steady (difference within plus or minus 5 points) or declining. Matches with unknown outcome are skipped and not counted. Embed with short lines, no table wider than mobile screens.
2. **`/nemesis`.** Only opponents with at least `MIN_NEMESIS_MATCHES` games (suggest 5). Sort by win rate ascending, then by matches descending; show the worst 5. One enemy-stats call (window: the last 90 days, like `/mates`). If the real call shows `enemy_id` is not an account id, stop and ask (see Verified facts).
3. **Percentile.** `percentile = players with badge below this badge / total players * 100`, plus half of the players in the same badge (mid-rank method), rounded to one decimal; display "Top X%" as `100 - percentile`. Unknown rank (badge 0 or missing): no line. Cache the distribution (6 h); the call happens at most once per profile view and failure is swallowed with one WARN.
4. **Errors.** Not linked: existing ephemeral message. API unavailable: the usual message (for `/profile` percentile: just omit). Empty data: clear message. No exception reaches the user.
5. **Efficiency.** Single pass over the history; hash maps for hero counts; sort only the small result sets. No repeated scans of the 200-match list.

## Out of scope
`/draft`, `/synergy`, `hero-synergy-stats` and `hero-comb-stats` (task 056); the web report, scoreboard and `/analyze` embed (task 055); charts; AI calls; match-level history in the database; `docs/PROJECT_CONTEXT.md`; ADRs; `pom.xml`; migrations.

## Parallel work
055 edits `ReportService`, templates, charts and `AnalyzeCommand`; 056 adds `/draft` and `/synergy` and also edits `DeadlockApiClient`, `CacheConfig`, `DeadlockApiClientCachingTest`, `HelpCommand`, `messages.properties`. On conflicts keep both sides (cache entries, help lines, appended keys). Do not reformat or reorder existing code.

## Acceptance criteria
- [ ] `./mvnw verify` passes (use `env -u ANTHROPIC_BASE_URL -u ANTHROPIC_CUSTOM_HEADERS` if the shell sets them).
- [ ] Unit tests: trends math with a hand-checked list (win rate, KDA, half comparison, direction thresholds, unknown outcomes, fewer than 5 games), nemesis ordering and minimum sample, percentile on a small hand-made distribution (first rank, middle, top, unknown), profile still renders when the distribution fails, client mapping and cache entries.
- [ ] The report records the real-call findings: `enemy_id` meaning, badge ordering, and the encoding of win/loss that was reused.
- [ ] No hardcoded user text; no duplicated persona-lookup code; existing `/mates` and `/profile` tests still pass unchanged (except the intended percentile addition).
- [ ] Task report created.
