# Task 011: `/build hero [rank]`

## Status

Implemented and verified locally. `.\mvnw.cmd clean verify` passes with 295 tests (243 baseline + 52 new). Not pushed, not merged.

## Step 0 Findings

Executed live queries to `https://api.deadlock-api.com` with hero id 1 (Infernus) and tier badge range 70..79:

1. **`GET /v1/analytics/item-flow-stats?hero_ids=1&min_average_badge=70&max_average_badge=79&min_unix_timestamp=<ts>`**:
   - Returns a single JSON object `ItemFlowStats` for that hero only.
   - Keys: `baseline`, `edges`, `leave_one_out`, `nodes`, `reached_per_column`, `reached_wins_per_column`, `summary`.
   - Has 4 distinct `column` values (0, 1, 2, 3).
   - `reached_per_column` has 4 entries (matching each column 0..3).
   - Each entry in `nodes` has `column`, `item_id`, `wins`, `losses`, `matches`, `players`, `total_kills`, `total_deaths`, `total_assists`, `adjusted_win_rate`, `avg_net_worth_at_buy`.
   - Matches designed DTO `ItemFlowStats(nodes, reachedPerColumn)` and `ItemFlowNode(column, itemId, matches, wins)`.

2. **`GET /v1/analytics/ability-order-stats?hero_id=1&min_average_badge=70&max_average_badge=79&min_unix_timestamp=<ts>`**:
   - Returns a JSON array of `AnalyticsAbilityOrderStats`.
   - Length of `abilities` array was 15 abilities in purchase order.
   - Verified three ability IDs in `GET /v1/assets/items` with `type` = `ability`:
     - 1593133799 -> Afterburn (`ability_afterburn`, `ability`)
     - 3516947824 -> Flame Dash (`ability_flame_dash`, `ability`)
     - 491391007 -> Napalm (`ability_incendiary_projectile`, `ability`)
   - Matches designed DTO `AbilityOrderStat(abilities, matches, wins)`.

## Summary

- Created DTO records in `sntiago05.rejuv.clients.dto`: `ItemFlowNode`, `ItemFlowStats` (with defensive copying and null handling), `AbilityOrderStat`.
- Created test fixtures with invented numbers in `src/test/resources/fixtures/deadlock-api/`: `item-flow-stats.json` and `ability-order-stats.json`.
- Extended `DeadlockApiClient`:
  - Added paths `ITEM_FLOW_PATH` and `ABILITY_ORDER_PATH`, and type references.
  - Added `@Cacheable` `getItemFlowStats(int heroId, Integer rankTier, long minUnixTimestamp)` cached in `DEADLOCK_ITEM_FLOW`.
  - Added `@Cacheable` `getAbilityOrderStats(int heroId, Integer rankTier, long minUnixTimestamp)` cached in `DEADLOCK_ABILITY_ORDER`.
  - Added query filter helpers (`hero_ids` for item-flow, `hero_id` for ability-order, with `min_unix_timestamp` and `rankTier*10`..`rankTier*10+9`).
- Extended `CacheConfig`:
  - Registered caches `deadlockItemFlow` (6 h TTL, max 200) and `deadlockAbilityOrder` (6 h TTL, max 200).
- Created domain records in `sntiago05.rejuv.domain`: `BuildItem`, `BuildPhase` (defensive copy, non-empty validation), `HeroBuild`.
- Created `HeroNotFoundException` runtime exception in `sntiago05.rejuv.services`.
- Created `HeroResolver` (`@Component`) in `sntiago05.rejuv.services`:
  - Resolves hero name against cached `getHeroes()`.
  - Resolution order: exact name ignoring case (after trimming and collapsing inner whitespace) -> unique prefix match ignoring case -> empty.
  - Discards null or blank hero names, and lets API exceptions propagate. Reusable by Task 012.
- Created `BuildService` (`@Service`) in `sntiago05.rejuv.services`:
  - Validates rank tier (null or 1..11) before any call.
  - Two-constructor `Clock` pattern (`Clock.systemUTC()` for production, fixed clock for unit tests).
  - Resolves hero via `HeroResolver`, throws `HeroNotFoundException` if empty.
  - Sequentially fetches `getItemFlowStats`, `getAbilityOrderStats`, `getItems`.
  - Phases: groups by column, filters `matches >= 20`, keeps first on duplicate `(column, itemId)`, computes `pickRatePercent` using `reachedPerColumn` without zero-division, computes `winRatePercent`, sorts by matches desc then itemId asc, caps at 4 items per phase and 6 phases.
  - Ability order: picks order with highest matches (tie-break on wins, then lexicographically smaller abilities), maps IDs to item names or `"#<id>"` fallback.
- Created `BuildCommand` (`@Component implements SlashCommand`) in `sntiago05.rejuv.commands`:
  - Registered `/build hero [rank]` with required String `hero` option (max length 50) and optional Integer `rank` option (choices 1..11 labeled from `meta.rank.*`).
  - Public `deferReply()`.
  - Embed with title, one non-inline field per phase in column order, ability order field with matches, and footer with min matches.
  - Error handling: empty data -> `build.no_data`, hero not found -> `build.hero_not_found` (without echoing user input), API busy -> `error.data_source_busy`, unexpected error -> `error.generic` with UUID error id logged without rethrow.
- Updated `messages.properties`:
  - Added `/build hero [rank]` line to `help.body` after `/meta [rank]`.
  - Appended `build.*` message keys (English, no single apostrophes in parameterized messages).

## Important Files

- `src/main/java/sntiago05/rejuv/clients/dto/ItemFlowNode.java` (create)
- `src/main/java/sntiago05/rejuv/clients/dto/ItemFlowStats.java` (create)
- `src/main/java/sntiago05/rejuv/clients/dto/AbilityOrderStat.java` (create)
- `src/main/java/sntiago05/rejuv/clients/DeadlockApiClient.java` (modify)
- `src/main/java/sntiago05/rejuv/config/CacheConfig.java` (modify)
- `src/main/java/sntiago05/rejuv/domain/BuildItem.java` (create)
- `src/main/java/sntiago05/rejuv/domain/BuildPhase.java` (create)
- `src/main/java/sntiago05/rejuv/domain/HeroBuild.java` (create)
- `src/main/java/sntiago05/rejuv/services/HeroNotFoundException.java` (create)
- `src/main/java/sntiago05/rejuv/services/HeroResolver.java` (create)
- `src/main/java/sntiago05/rejuv/services/BuildService.java` (create)
- `src/main/java/sntiago05/rejuv/commands/BuildCommand.java` (create)
- `src/main/resources/messages.properties` (modify)
- `src/test/resources/fixtures/deadlock-api/item-flow-stats.json` (create)
- `src/test/resources/fixtures/deadlock-api/ability-order-stats.json` (create)
- `src/test/java/sntiago05/rejuv/clients/DeadlockApiClientTest.java` (modify)
- `src/test/java/sntiago05/rejuv/clients/DeadlockApiClientCachingTest.java` (modify)
- `src/test/java/sntiago05/rejuv/services/HeroResolverTest.java` (create)
- `src/test/java/sntiago05/rejuv/services/BuildServiceTest.java` (create)
- `src/test/java/sntiago05/rejuv/commands/BuildCommandTest.java` (create)
- `src/test/java/sntiago05/rejuv/commands/HelpCommandTest.java` (modify)
- `src/test/java/sntiago05/rejuv/services/MessagesIntegrationTest.java` (modify)
- `docs/task-reports/011-build.md` (create)

## Decisions

- Made `BuildService.MIN_ITEM_MATCHES` public static final so `BuildCommand` can format the footer with the exact threshold constant without duplicating literal `20`.
- Ability order tie-breaking uses a comparator that prefers higher matches, then higher wins, then lexicographically smaller abilities.
- Free-text hero input resolves against the cached `getHeroes()` asset, satisfying Discord's 25-choice limit while supporting ~38 heroes.
- Truncated joined abilities list to fit Discord's 1024-character field limit if needed.

## Verification

- **Baseline before changes**: `.\mvnw.cmd verify` passed with 243 tests, 0 failures.
- **Narrow test runs**:
  - `.\mvnw.cmd test "-Dtest=DeadlockApiClientTest,DeadlockApiClientCachingTest"`: 46 tests, 0 failures.
  - `.\mvnw.cmd test "-Dtest=HeroResolverTest,BuildServiceTest"`: 31 tests, 0 failures.
  - `.\mvnw.cmd test "-Dtest=BuildCommandTest,HelpCommandTest"`: 15 tests, 0 failures.
  - `.\mvnw.cmd test -Dtest=MessagesIntegrationTest`: 5 tests, 0 failures.
- **Full build**: `.\mvnw.cmd clean verify` passed with **295 tests**, 0 failures, 0 errors, 0 skipped (+52 new tests).
- **Manual Discord check**: Not executed by the implementer (requires live bot credentials and dev guild).
- **Diff check**: `git diff --check` clean; no secrets, tokens or real player data committed; `.env` was never read.

## Acceptance criteria

| Criterion | Evidence |
|---|---|
| Step 0 findings in report, DTOs match real response shapes | Step 0 Findings section above; `ItemFlowStats`, `ItemFlowNode`, `AbilityOrderStat` |
| `./mvnw clean verify` passes, test count and baseline in report | 295 tests (243 baseline + 52 new), exit code 0 |
| `getItemFlowStats` and `getAbilityOrderStats` send listed params, validate tier, cached 6 h (max 200) | `itemFlowSendsHeroBadgeRangeAndTimestamp`, `abilityOrderSendsHeroIdBadgeRangeAndTimestamp`, caching tests |
| `HeroResolver` follows exact/prefix rules, reusable by Task 012 | `HeroResolverTest` (9 tests), clean `@Component` with `Optional<HeroAsset> resolve(String)` |
| `/build` works without linked account; public defer; phases in column order with max 4 items; ability order; text from catalog | `defersPubliclyAndEditsWithEmbed`, `BuildCommandTest` (10 tests) |
| Items < 20 matches excluded; pick rate never divides by zero | `itemsBelowMinMatchesAreDropped`, `zeroReachedGivesZeroPickRate` |
| Unknown hero, empty data, API unavailable, unexpected errors give own message without echoing input; error id logged | `heroNotFoundEditsWithoutEchoingInput`, `emptyBuildEditsWithNoData`, `unavailableEditsWithBusyMessage`, `unexpectedErrorEditsWithGenericMessageAndDoesNotRethrow` |
| Sequential API calls; no business logic in command; no JDA types in service | `callsFlowThenAbilitiesThenItemsSequentially`; `BuildService` has no JDA imports; `BuildCommand` formats |
| `help.body` lists `/build` | `helpBodyCoversMvpTopics` passes; `/build hero [rank]` present in `messages.properties` |
| Only table files changed; no new dependency, migration or secret | `git status --short`, `git diff --name-only origin/main...HEAD` |
| Task report created, `PROJECT_CONTEXT.md` update marked pending integration | This report; `docs/PROJECT_CONTEXT.md` left untouched on task branch |

## Deviations

None.

## Known Issues

- Command choice options for rank are 1 to 11 (tier 0 `Obscurus` is omitted as decided in the spec).
- If neither item flow nor ability orders have data for a hero and rank, `build.no_data` is returned.
- API calls happen synchronously on the JDA event thread after public deferral.

## PROJECT_CONTEXT integration

**PROJECT_CONTEXT update pending integration** because parallel task branches are active (`feature/012-counter` worktree).
Facts to add to `docs/PROJECT_CONTEXT.md` after integration:
- Task 011 (`/build hero [rank]`) done: public slash command displaying popular item flow by phase (column order, max 4 items/phase, min 20 matches) and most played ability order; free-text hero resolution against cached assets.
- `DeadlockApiClient`: added `getItemFlowStats(heroId, rankTier, minUnixTimestamp)` and `getAbilityOrderStats(heroId, rankTier, minUnixTimestamp)`.
- `CacheConfig`: added `deadlockItemFlow` (6 h, max 200) and `deadlockAbilityOrder` (6 h, max 200).
- `HeroResolver`: reusable component resolving hero input (exact match ignoring case -> unique prefix match -> empty).
- Step 0 facts: `item-flow-stats` returns a single object with 4 columns in normal mode and matching `reached_per_column`; `ability-order-stats` returns an array of orders with up to 15 abilities resolving to `ItemAsset.type == "ability"`.

## Merge Risks

- `messages.properties`, `CacheConfig`, `DeadlockApiClient`, `DeadlockApiClientTest`, `DeadlockApiClientCachingTest`, `HelpCommandTest`, and `MessagesIntegrationTest` are shared with parallel tasks (specifically Task 012 `/counter`). All changes are modular and append-only, making conflict resolution straightforward.
