# Task 056: `/draft` (predicted win rate and last-pick suggestions) and `/synergy`

## Status

Implemented and verified locally. Baseline: 1137 passing tests (14 skipped). Final suite: 1175 passing tests (14 skipped), +38 new tests. `env -u ANTHROPIC_BASE_URL -u ANTHROPIC_CUSTOM_HEADERS .claude/skills/run-rejuv/driver.sh verify` passes cleanly with exit code 0. Interactive runtime verified with `driver.sh cmd synergy` and `driver.sh cmd draft`. Not pushed, not merged.

## Step 0 / Fixture First Findings

Live verification against `https://api.deadlock-api.com/v1/analytics/hero-synergy-stats` performed on 2026-10-20:
- Live call with `min_unix_timestamp` (30 days ago) returned 909 synergy pair rows.
- Analysis of pair ordering in the live response:
  - Total rows: 909
  - Unique pairs `(hero_id1, hero_id2)`: 909
  - Both directions count: 0
  - `hero_id1 <= hero_id2`: True for all rows
- **Pair order finding:** In the live deadlock-api response, each allied hero pair appears **only once** with `hero_id1 < hero_id2`.
- **Normalization:** In both `DraftService` and `SynergyService`, hero pairs are normalized to unordered keys (`PairKey.of(a, b)` using `Math.min(a, b)` and `Math.max(a, b)`), ensuring the service works identically whether pairs appear in one direction or both directions.
- Trimmed real-shaped fixture saved to `src/test/resources/fixtures/deadlock-api/hero-synergy-stats.json` with 6 pair rows across 4 heroes and unmapped extra fields to test `@JsonIgnoreProperties(ignoreUnknown = true)`.

## Model and Constants

The mathematical model works strictly in log-odds and uses empirical Bayesian shrinkage:
- **Baseline:** Starts at log-odds 0.0 (equivalent to 50% win probability, since $\text{logit}(0.5) = 0.0$).
- **Synergy Delta:** For each allied hero pair with $\text{matches} \ge \text{MIN\_PAIR\_MATCHES}$, the shrunk win rate is:
  $$p_{\text{synergy}} = \frac{\text{wins} + k \cdot 0.5}{\text{matches} + k}$$
  $$\Delta_{\text{synergy}} = \text{logit}(p_{\text{synergy}}) - \text{logit}(0.5) = \ln\left(\frac{p_{\text{synergy}}}{1 - p_{\text{synergy}}}\right)$$
- **Counter Delta:** For each ally-versus-enemy pair from `getHeroCounterStats` with $\text{matches} \ge \text{MIN\_PAIR\_MATCHES}$, computed identically with ally wins against enemy.
- **Group Means:** Deltas are averaged within the synergy group and within the counter group so that team size does not inflate odds. If a group has 0 qualifying pairs, its mean is 0.0:
  $$\text{totalLogOdds} = \mu_{\text{synergy}} + \mu_{\text{counter}}$$
- **Conversion and Clamping:** Probability is recovered using the standard logistic function:
  $$P = \frac{1}{1 + e^{-\text{totalLogOdds}}}$$
  clamped to $[\text{MIN\_PROBABILITY}, \text{MAX\_PROBABILITY}]$ ($0.05$ to $0.95$).
- **Named Constants:**
  - `DraftService.MIN_PAIR_MATCHES = 50`
  - `DraftService.SHRINKAGE_K = 50.0`
  - `DraftService.MIN_PROBABILITY = 0.05`
  - `DraftService.MAX_PROBABILITY = 0.95`
  - `DraftService.MIN_CONFIDENCE_PAIRS = 3`
  - `DraftService.MAX_SUGGESTIONS = 3`
  - `DraftService.MAX_ALLIES_FOR_SUGGESTION = 5`
  - `SynergyService.MIN_PAIR_MATCHES = 50`
  - `SynergyService.SHRINKAGE_K = 50.0`
  - `SynergyService.MAX_PARTNERS = 5`
- **Low Confidence:** `lowConfidence = true` when fewer than 3 pairs had enough data ($\text{qualifyingPairs} < 3$).
- **Candidate Suggestions:** When `allies.size() < 5`, every hero in `GameAssets.namedHeroes()` not picked by either team is evaluated as an additional ally using $O(1)$ lookups into pre-indexed maps (no rescanning of lists). Ranked by win probability descending, sample matches descending, hero ID ascending; top 3 returned.

## Summary of Changes

1. **DTO:**
   - Created `clients/dto/HeroSynergyStat`: Java record with `@JsonIgnoreProperties(ignoreUnknown = true)`, fields `hero_id1`, `hero_id2`, `wins`, `matches_played`.
2. **Cache:**
   - Modified `config/CacheConfig`: Added `DEADLOCK_HERO_SYNERGIES = "deadlockHeroSynergies"` (TTL 3 h, max 50), matching `DEADLOCK_HERO_COUNTERS`.
   - Updated `clients/DeadlockApiClientCachingTest`: Added cache definition and assertions to exact-set test `cacheTtlsAndSizesMatchTheDesign`, and added test `heroSynergiesAreCachedPerTierAndTimestamp`.
3. **DeadlockApiClient:**
   - Added `HERO_SYNERGY_PATH = "/v1/analytics/hero-synergy-stats"`, reader, and method `getHeroSynergyStats(Integer rankTier, long minUnixTimestamp)` annotated with `@Cacheable(DEADLOCK_HERO_SYNERGIES)`. Validates rank tier (0..11) before making requests.
   - Added tests in `DeadlockApiClientTest` for badge range, timestamp, null rank, out-of-range tier, and 503 retry-after mapping.
4. **Services:**
   - Created `services/SynergyService`: Computes top 5 best and worst partners for a given hero ID and rank tier. Normalizes pair orders, filters by $\text{matches} \ge 50$, computes shrunk win rate.
   - Created `services/DraftService`: Computes estimated win probability, low confidence flag, and top 3 pick suggestions using the log-odds model.
5. **Commands:**
   - Created `commands/SynergyCommand`: `/synergy hero:<text> [rank:<choice>]`. Public deferral, embeds hero thumbnail, displays best and worst partners with percentage and matches.
   - Created `commands/DraftCommand`: `/draft allies:<text> enemies:<text> [rank:<choice>]`. Validates allies ($\le 5$) and enemies ($\le 6$), rejects duplicates per team, displays win chance with plain-text bar (`[█████░░░░░]`), team rosters, and suggestions.
6. **I18n:**
   - Updated `messages.properties`: Added `/synergy` and `/draft` to `help.body`. Appended keys `draft.*` and `synergy.*` at the end of the file.

## Important Files

- `src/main/java/sntiago05/rejuv/clients/dto/HeroSynergyStat.java` (create)
- `src/main/java/sntiago05/rejuv/config/CacheConfig.java` (modify)
- `src/main/java/sntiago05/rejuv/clients/DeadlockApiClient.java` (modify)
- `src/main/java/sntiago05/rejuv/services/SynergyService.java` (create)
- `src/main/java/sntiago05/rejuv/services/DraftService.java` (create)
- `src/main/java/sntiago05/rejuv/commands/SynergyCommand.java` (create)
- `src/main/java/sntiago05/rejuv/commands/DraftCommand.java` (create)
- `src/main/resources/messages.properties` (modify)
- `src/test/resources/fixtures/deadlock-api/hero-synergy-stats.json` (create)
- `src/test/java/sntiago05/rejuv/clients/DeadlockApiClientTest.java` (modify)
- `src/test/java/sntiago05/rejuv/clients/DeadlockApiClientCachingTest.java` (modify)
- `src/test/java/sntiago05/rejuv/services/SynergyServiceTest.java` (create)
- `src/test/java/sntiago05/rejuv/services/DraftServiceTest.java` (create)
- `src/test/java/sntiago05/rejuv/commands/SynergyCommandTest.java` (create)
- `src/test/java/sntiago05/rejuv/commands/DraftCommandTest.java` (create)
- `docs/task-reports/056-draft-and-synergy.md` (this report)

## Decisions

1. **Unordered Pair Normalization:** Because the live API provides each pair once with `hero_id1 < hero_id2`, while fixtures or edge cases might have reverse or dual entries, both `SynergyService` and `DraftService` normalize pair lookups using `PairKey.of(Math.min(a, b), Math.max(a, b))` and deduplicate keeping the first entry.
2. **Suggestions Cutoff:** `DraftService` generates pick suggestions when `request.allyHeroIds().size() < 5`. With 5 allies provided (meaning 5 teammates entered, filling the 5 teammate slots), suggestions are omitted (`List.of()`), satisfying Acceptance Criterion 3 (`/draft with 5 allies and 6 enemies gives a probability and no suggestions; with 3 allies and 3 enemies gives top 3 suggestions`).
3. **No Domain Pollution:** To strictly abide by the spec's "Files to create or modify" table, all records (`DraftRequest`, `DraftResult`, `PickSuggestion`, `SynergyResult`, `Partner`) were declared as public static records inside their respective service classes without creating extra files in `domain/`.

## Verification Commands and Results

| Check / Requirement | Evidence / Command Run | Result |
|---|---|---|
| Full suite verification (`./mvnw verify` via `driver.sh`) | `env -u ANTHROPIC_BASE_URL -u ANTHROPIC_CUSTOM_HEADERS .claude/skills/run-rejuv/driver.sh verify` | 1175 tests run, 0 failures, 0 errors, 14 skipped (baseline 1137 + 38 new). Total time: 32.3 s |
| Client endpoint and caching tests | `env -u ANTHROPIC_BASE_URL -u ANTHROPIC_CUSTOM_HEADERS ./mvnw test -Dtest=DeadlockApiClientTest,DeadlockApiClientCachingTest` | 77 tests run, 0 failures |
| Synergy and Draft service tests | `env -u ANTHROPIC_BASE_URL -u ANTHROPIC_CUSTOM_HEADERS ./mvnw test -Dtest=SynergyServiceTest,DraftServiceTest` | 17 tests run, 0 failures |
| Command and I18n tests | `env -u ANTHROPIC_BASE_URL -u ANTHROPIC_CUSTOM_HEADERS ./mvnw test -Dtest=DraftCommandTest,SynergyCommandTest,HelpCommandTest,MessagesIntegrationTest` | 27 tests run, 0 failures |
| Command registration check | `SERVER_PORT=0 .claude/skills/run-rejuv/driver.sh list` | `/draft` and `/synergy` correctly listed with options |
| Manual `/synergy` execution | `SERVER_PORT=0 .claude/skills/run-rejuv/driver.sh cmd synergy hero=Infernus` | Returned embed with Infernus thumbnail, top best (Rat King 56.3%, Graves 55.9%, ...) and worst partners (Shiv 42.0%, Pocket 42.8%, ...) |
| Manual `/draft` (3 allies, 3 enemies) | `SERVER_PORT=0 .claude/skills/run-rejuv/driver.sh cmd draft allies="Infernus, Seven, Ivy" enemies="Haze, Wraith, Abrams"` | Returned win chance `54.8% [█████░░░░░]`, teams, and top 3 suggestions (Graves 58.3%, Rat King 58.3%, Paige 58.1%) |
| Manual `/draft` (5 allies, 6 enemies) | `SERVER_PORT=0 .claude/skills/run-rejuv/driver.sh cmd draft allies="Infernus, Seven, Ivy, Vindicta, Dynamo" enemies="Haze, Wraith, Abrams, Bebop, Kelvin, Yamato"` | Returned win chance `54.9% [█████░░░░░]`, teams, and NO suggestions field |
| Code diff and style check | `git diff --check`, `git status --short` | Clean diff, no trailing whitespace, only specified files touched |

## Deviations

None. Implemented exactly according to the spec, ADR-0014, ADR-0019, and AGENTS.md.

## Known Issues

None.

## PROJECT_CONTEXT Integration

PROJECT_CONTEXT update pending integration.
Parallel worktrees and branches are currently active:
- `feature/055-report-scoreboard-readability`
- `feature/056-draft-and-synergy` (this branch)
- `feature/057-trends-nemesis-percentile`
Per AGENTS.md and user instruction, `docs/PROJECT_CONTEXT.md` was not modified on this branch.

Facts to integrate into `docs/PROJECT_CONTEXT.md` upon merge:
- Task 056 complete: `/draft` and `/synergy` commands implemented.
- Cache added: `deadlockHeroSynergies` (`CacheConfig.DEADLOCK_HERO_SYNERGIES`, 3 h TTL, max 50).
- Deadlock API observed facts: `GET /v1/analytics/hero-synergy-stats` returns 909 rows, pairs appear only once with `hero_id1 < hero_id2`.

## Merge Risks

Minimal:
- `messages.properties` only modified by editing `/help` text and appending new keys at the end.
- `CacheConfig` and `DeadlockApiClient` only had additive changes (one new cache and one new method).
- All new commands and services are self-contained.
