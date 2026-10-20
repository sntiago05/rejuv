# Task 056: `/draft` (predicted win rate and last-pick suggestions) and `/synergy`

- **Branch**: `feature/056-draft-and-synergy`
- **Implementer**: Antigravity
- **Related ADRs**: ADR-0014 (feature scope: pure data, zero AI cost), ADR-0019 (free model), ADR-0004 (deadlock-api)
- **Runs in parallel with**: 055 (web report and `/analyze` format) and 057 (`/trends`, `/nemesis`, rank percentile). See "Parallel work".

## Goal
Two free, no-AI commands built on shared analytics caches:
1. `/draft`: the user types the heroes of the allied team and of the enemy team; the bot estimates the allied win chance and suggests the best remaining picks.
2. `/synergy`: for one hero, lists the allied heroes with the best and worst win rate together.

## Verified facts (do not invent others)
- deadlock-api `GET /v1/analytics/hero-synergy-stats` returns an array of rows `{hero_id1, hero_id2, matches_played, wins, kills1, kills2, deaths1, deaths2, assists1, assists2, networth1, networth2, creeps1, creeps2, last_hits1, last_hits2, denies1, denies2, obj_damage1, obj_damage2}`. Useful query parameters (all optional): `min_unix_timestamp`, `min_average_badge`, `max_average_badge`, `min_matches`, `same_lane_filter`, `match_mode`, `game_mode`. Whether a pair appears once or twice (both orders) is **not verified**: check it against a real response saved as a fixture before relying on it (step 1 of Behavior).
- `GET /v1/analytics/hero-comb-stats` returns `{hero_ids: [int], wins, losses, matches}` (params include `comb_size`, `include_hero_ids`, `include_enemy_hero_ids`, `min_matches`). Not required by this task; do not use it unless the model below needs it.
- Already in the repo: `DeadlockApiClient.getHeroCounterStats(Integer rankTier, long minUnixTimestamp)` returning `List<HeroCounterStat(heroId, enemyHeroId, wins, matchesPlayed)>`, cache `DEADLOCK_HERO_COUNTERS`; `HeroResolver` (hero name to id), `GameAssets.heroNames()`, `CounterService` and `CounterCommand` (read them first and copy the conventions for rank tier, time window, caching and errors); `CommandSupport`, `ServiceSupport`.
- AGENTS.md rules: every client read is cached; honor 503 `Retry-After` once; never fire parallel uncached analytics requests; user text only in `messages.properties` through `Messages`; commands that can take over 3 s call `deferReply()` first.

## Files to create or modify
| File | Change |
|---|---|
| `src/main/java/sntiago05/rejuv/clients/dto/HeroSynergyStat.java` | New record, `@JsonIgnoreProperties(ignoreUnknown = true)`, only the fields used: `hero_id1`, `hero_id2`, `matches_played`, `wins`. |
| `src/main/java/sntiago05/rejuv/clients/DeadlockApiClient.java` | New method `List<HeroSynergyStat> getHeroSynergyStats(Integer rankTier, long minUnixTimestamp)` with its own cache name, built the same way as `getHeroCounterStats`. Do not change other methods. |
| `src/main/java/sntiago05/rejuv/config/CacheConfig.java` | New cache name constant and registration for the synergy cache (same TTL and size policy as the counters cache). |
| `src/test/java/sntiago05/rejuv/clients/DeadlockApiClientCachingTest.java` | Add the new cache to the exact-set assertion (`cacheTtlsAndSizesMatchTheDesign`). Keep every existing entry. |
| `src/main/java/sntiago05/rejuv/services/DraftService.java` | New. Pure logic, no JDA types. Public API in "Interfaces". |
| `src/main/java/sntiago05/rejuv/services/SynergyService.java` | New. Best and worst partners for one hero. |
| `src/main/java/sntiago05/rejuv/commands/DraftCommand.java`, `SynergyCommand.java` | New slash commands. Validate input, call the service, build the embed. No business logic. |
| `src/main/java/sntiago05/rejuv/commands/HelpCommand.java` and `messages.properties` | List the two commands in `/help` (only if `/help` builds its list from code; if it is a text key, edit the key). |
| `src/main/resources/messages.properties` | Append keys at the **end** of the file (other tasks append too; keep both sides on conflict): `draft.*`, `synergy.*`. |
| tests | `DraftServiceTest`, `SynergyServiceTest`, `DraftCommandTest`, `SynergyCommandTest`, client test with JSON fixture `src/test/resources/fixtures/hero-synergy-stats.json` (a small real-shaped sample), cache test update above. |
| `docs/task-reports/056-draft-and-synergy.md` | New task report (AGENTS.md "After implementation"). Mark `docs/PROJECT_CONTEXT.md` as pending integration; do **not** edit it. |

No other file. If you need one, stop and ask.

## Interfaces / signatures
- `record DraftRequest(List<Integer> allyHeroIds, List<Integer> enemyHeroIds, Integer rankTier)`.
- `record DraftResult(double winProbability, int allyCount, int enemyCount, List<PickSuggestion> suggestions, boolean lowConfidence)` and `record PickSuggestion(int heroId, double winProbability, long sampleMatches)`.
- `DraftResult DraftService.estimate(DraftRequest request)`; throws `DeadlockApiUnavailableException` as the other services do.
- `SynergyResult SynergyService.forHero(int heroId, Integer rankTier)` with `record SynergyResult(int heroId, List<Partner> best, List<Partner> worst)` and `record Partner(int heroId, double winRate, long matches)`.
- Slash commands:
  - `/draft allies:<text> enemies:<text> [rank:<choice>]`. `allies`: up to 5 hero names separated by commas (the user's own hero is one of the 6 allies; accept 0 to 5 names). `enemies`: up to 6 names. Resolve every name with `HeroResolver`; an unknown or ambiguous name gets the existing hero-not-found message. Duplicate names in one team are rejected.
  - `/synergy hero:<text> [rank:<choice>]`.
  - Use the same `rank` option style and default as `/counter`.

## Behavior
1. **Fixture first.** Make one real call to `hero-synergy-stats` (no key needed) with the same parameters the client will use, save a trimmed sample as the fixture, and record in the task report whether a pair appears in one or both orders. The service must work correctly in both cases (normalize each pair to an unordered key).
2. **Model (keep it simple and documented in the report).** Work in log-odds. Start at 0 (50%). For each ally pair with `matches >= MIN_PAIR_MATCHES` add the shrunk synergy delta `logit((wins + k*0.5)/(matches + k)) - logit(0.5)` with a shrinkage constant `k` (suggest 50). For each ally-versus-enemy pair from `getHeroCounterStats` add the counter delta computed the same way. Average the pair deltas within each group (so team size does not inflate the result), sum the two group means, convert back with the logistic function and clamp to 5% to 95%. Pairs below the minimum sample are ignored. All constants are named `private static final` fields. This is an estimate, not a forecast.
3. **Suggestions.** When the allied team has fewer than 6 heroes, evaluate every hero in `GameAssets.namedHeroes()` not already picked by either team as the next ally, using the same model, and return the top 3 by probability. Fewer than 6 allies and at least 1 enemy is the normal use; with 0 enemies still work (synergy only). Use one pass over the stats with hash maps keyed by hero pair; do not rescan the list for each candidate hero.
4. **Low confidence.** `lowConfidence` is true when fewer than 3 pairs had enough data. The embed then says the estimate is weak.
5. **Output.** Ephemeral = false, one embed: allied win chance with a plain-text bar, the two teams with hero names, the top 3 suggestions, and a short disclaimer line. All text via `messages.properties`. Hero names only (no emojis required; reuse the emoji helper only if `CounterCommand` already does).
6. **`/synergy`.** From the synergy rows, for each partner hero compute win rate with shrinkage as above; list the best 5 and worst 5 partners with at least `MIN_PAIR_MATCHES` games, each with win rate and matches.
7. **Errors.** deadlock-api unavailable: the usual "data source busy" reply. No data for the rank: a clear message. Never throw to the user; log one INFO line per command as the other commands do.
8. **Cache and calls.** One synergy call and one counter call per (rank, window); both cached. Do not call them in parallel.

## Out of scope
`hero-comb-stats`, `lane-matchup-stats`, saved drafts, buttons or select menus, any AI call, the web report, `/analyze`, anything of tasks 055 and 057 (`ReportService`, `AnalyzeCommand`, `ProfileService`, match history, `enemy-stats`, rank distribution), `docs/PROJECT_CONTEXT.md`, ADRs, `pom.xml`, migrations.

## Parallel work
055 edits the report web layer and the `/analyze` embed; 057 adds `/trends`, `/nemesis` and edits `/profile`. Shared files: `messages.properties` (append at the end only), `DeadlockApiClient` and `CacheConfig` (you add one method and one cache; 057 adds others; on conflict keep all entries), `DeadlockApiClientCachingTest` (keep all entries), `HelpCommand`. Do not reformat or reorder existing code.

## Acceptance criteria
- [ ] `./mvnw verify` passes (use `env -u ANTHROPIC_BASE_URL -u ANTHROPIC_CUSTOM_HEADERS` if the shell sets them).
- [ ] Unit tests: model math (known inputs, both pair orders, shrinkage, clamp), minimum sample, suggestion ranking and exclusion of picked heroes, hero resolution errors and duplicates, 503 mapping, command reply shape, client mapping with `MockRestServiceServer` and the fixture.
- [ ] `/draft` with 5 allies and 6 enemies gives a probability and no suggestions; with 3 allies and 3 enemies gives top 3 suggestions.
- [ ] No hardcoded user text; new cache is in the caching test; no duplicated helper (reused `HeroResolver`, `CommandSupport`, `ServiceSupport`).
- [ ] Task report created with the pair-order finding, model constants and the real commands run.
