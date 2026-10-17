# Task 006: `/profile`

- **Branch**: `feature/006-profile`
- **Implementer**: Antigravity
- **Related ADRs**: ADR-0002 (Caffeine), ADR-0004 and ADR-0010 (deadlock-api), ADR-0008 (English, i18n-ready)

## Goal
Add `/profile [user]`: a public embed with the linked player's current rank, a summary of their last 10 matches (wins, win rate, average K/D/A) and their top 3 heroes by games in the last 30 days. No AI call. The summary is cached 10 minutes per account (`docs/mvp-spec.md`, `/profile`).

## Context (verified in the repository when this spec was written)
Do not repeat this discovery. If a fact below is false in your checkout, stop and ask.

- Plan entry: `docs/implementation-plan.md`, task 006, depends on 005, implementer Antigravity. Task 015 later adds party stats to `/profile`; do not prepare for it.
- Tasks 004, 005 and 022 are merged in `main` (PR #25, #27, #28). Start the branch from the latest `origin/main`.
- **Task 008 is planned to run in parallel** in another worktree. It does not touch any file of this task. Do not touch `application.yaml`, `.env.example`, `pom.xml` or anything under `clients/`.
- Existing code you will use (do not change it):
  - `repositories/LinkedAccountRepository extends JpaRepository<LinkedAccount, Long>` (id = Discord user id). `domain/LinkedAccount` exposes `getDiscordUserId()`, `getAccountId()`, `getLinkedAt()`.
  - `clients/DeadlockApiClient` (all methods cached, all throw `DeadlockApiUnavailableException` or `DeadlockApiException`):
    - `List<MatchHistoryEntry> getMatchHistory(long accountId)`: newest first, max 200 entries, empty list = no data.
    - `PlayerRank getPlayerRank(long accountId)`: `badge`, `rank` (= tier, 0-11), `subrank`. An unranked player returns zeros.
    - `List<RankAsset> getRanks()`: `tier`, `name`. `List<HeroAsset> getHeroes()`: `id`, `className`, `name`.
  - `clients/dto/MatchHistoryEntry` fields used here: `matchId`, `heroId`, `startTime` (epoch seconds), `matchResult`, `playerTeam`, `playerKills`, `playerDeaths`, `playerAssists`.
  - `services/NoMatchHistoryException(long accountId)` (Task 005), reused here.
  - `config/CacheConfig`: static Caffeine caches; unknown cache names return `null`, so a new cache must be registered there.
  - `commands/LinkCommand` is the reference for a deferred command that handles its own errors (`CommandRouter` cannot reply after a deferral). `CommandRouter` already writes the one INFO line per command.
  - `help.body` already lists `/profile [user]`; do not change it.
- Win rule (verified in real responses, `docs/PROJECT_CONTEXT.md`): a match is a win when `matchResult == playerTeam`. Do not use `playerMatchOutcome` (0 for matches before about 2026-07-30).
- Rules from `AGENTS.md`: no business logic in `commands/`; services use no JDA types; all user-facing text in `messages.properties`; `deferReply()` first for commands that may take more than 3 s; constructor injection, `final` fields, records, no Lombok.

## Files to create or modify
| File | Change |
|---|---|
| `src/main/java/sntiago05/rejuv/domain/ProfileSummary.java` | **Create.** Record (see Interfaces). |
| `src/main/java/sntiago05/rejuv/domain/HeroUsage.java` | **Create.** Record. |
| `src/main/java/sntiago05/rejuv/services/ProfileService.java` | **Create.** |
| `src/main/java/sntiago05/rejuv/commands/ProfileCommand.java` | **Create.** |
| `src/main/java/sntiago05/rejuv/config/CacheConfig.java` | **Modify.** Only add the `PROFILE_SUMMARY` constant and its `register(...)` line. |
| `src/main/resources/messages.properties` | **Modify.** Append the keys in "User-facing text". Change no existing key. |
| `src/test/java/sntiago05/rejuv/services/ProfileServiceTest.java` | **Create.** |
| `src/test/java/sntiago05/rejuv/services/ProfileServiceCachingTest.java` | **Create.** |
| `src/test/java/sntiago05/rejuv/commands/ProfileCommandTest.java` | **Create.** |
| `src/test/java/sntiago05/rejuv/services/MessagesIntegrationTest.java` | **Modify.** Only: add the new keys to `requiredKeys`, and add `"profile"` to the command-name assertion. Change nothing else. |
| `docs/task-reports/006-profile.md` | **Create.** Task report (format of the Task 005 report). |

No `pom.xml`, `application.yaml`, migration, entity, repository, client or DTO change is needed. If you think one is needed, stop and ask.

## Interfaces / signatures

### Records (`sntiago05.rejuv.domain`, new)
```java
/** heroName is null when the hero id is not in the heroes asset. */
public record HeroUsage(int heroId, String heroName, int matches) { }

/**
 * rankTier is null when the player is unranked (badge 0). rankName is null when unranked
 * or when the tier is missing from the ranks asset. subrank is 0 when unknown.
 * topHeroes is immutable, at most 3 entries, possibly empty.
 */
public record ProfileSummary(long accountId,
                             Integer rankTier, String rankName, int subrank,
                             int recentMatches, int recentWins, int winRatePercent,
                             double avgKills, double avgDeaths, double avgAssists,
                             List<HeroUsage> topHeroes) { }
```

### `ProfileService` (`sntiago05.rejuv.services`, `@Service`, new)
```java
static final int RECENT_MATCHES = 10;
static final int TOP_HEROES = 3;
static final Duration TOP_HEROES_WINDOW = Duration.ofDays(30);

@Autowired
public ProfileService(LinkedAccountRepository repository, DeadlockApiClient deadlockApiClient)   // uses Clock.systemUTC()
ProfileService(LinkedAccountRepository repository, DeadlockApiClient deadlockApiClient, Clock clock) // package-private, for tests

/** Linked Deadlock account of the Discord user, or empty. Database only, no API call. */
public OptionalLong findLinkedAccountId(long discordUserId);

/**
 * Builds the profile summary. Cached in CacheConfig.PROFILE_SUMMARY, key = accountId, sync = true.
 * @throws NoMatchHistoryException when the match history is empty (not cached)
 * Lets DeadlockApiUnavailableException / DeadlockApiException propagate (not cached).
 */
@Cacheable(cacheNames = CacheConfig.PROFILE_SUMMARY, sync = true)
public ProfileSummary getProfile(long accountId);
```
Do not add a `Clock` bean (that would touch shared config). The two-constructor pattern above is the decision of this spec.

### `ProfileCommand` (`sntiago05.rejuv.commands`, `@Component implements SlashCommand`, new)
- `ProfileCommand(ProfileService profileService, Messages messages)`.
- `name()` = `profile`; `data()` = `Commands.slash("profile", messages.get("profile.description"))` with one option: type `USER`, name `user`, description `messages.get("profile.option.user")`, **not** required.

### `CacheConfig` (modify)
`public static final String PROFILE_SUMMARY = "profileSummary"; // 10 min, max 1000` and `register(cacheManager, PROFILE_SUMMARY, Duration.ofMinutes(10), 1000);`

## Behavior

### `ProfileService.getProfile(accountId)`
Calls in this order (sequential, never in parallel): `getMatchHistory`, then `getPlayerRank`, `getRanks`, `getHeroes`. If the history is empty, throw `NoMatchHistoryException(accountId)` before any other call.

1. **Rank**: `badge == 0` → `rankTier = null`, `rankName = null`, `subrank = 0`. Otherwise `rankTier = rank.rank()`, `subrank = rank.subrank()`, `rankName` = name of the `RankAsset` whose `tier == rankTier`, or `null` if none.
2. **Recent matches**: the first `min(10, history.size())` entries (the list is newest first). `recentMatches` = that count; `recentWins` = entries with `matchResult == playerTeam`; `winRatePercent = Math.round(recentWins * 100.0 / recentMatches)`; `avgKills/avgDeaths/avgAssists` = arithmetic mean over those entries, unrounded (the command formats them).
3. **Top heroes**: entries of the whole fetched history (up to 200) with `startTime >= now - 30 days` (epoch seconds, `now` from the clock). Group by `heroId`, count, sort by count descending, then by `heroId` ascending; keep the first 3. `heroName` = `HeroAsset.name` for that id, or `null`. If no entry is in the window, `topHeroes` is empty.

### `/profile [user]`
1. Target = the `user` option if present, else the caller. Read the target's Discord id.
2. `profileService.findLinkedAccountId(targetId)` **before** deferring (database only).
   - Empty and target is the caller → ephemeral reply `profile.not_linked_self`; stop.
   - Empty and target is another user → ephemeral reply `profile.not_linked_other` with `{0}` = the target's mention (`User.getAsMention()`); stop.
3. `event.deferReply().queue()` (public; API calls can exceed 3 s).
4. Call `getProfile(accountId)` synchronously and answer through the hook:
   - success → `editOriginalEmbeds(embed).queue()` (embed below);
   - `NoMatchHistoryException` → `editOriginal(profile.no_matches)`;
   - `DeadlockApiUnavailableException` → `editOriginal(error.data_source_busy)`;
   - any other `RuntimeException` → error id `UUID.randomUUID()`, log `ERROR` with id, caller id, target account id and the exception, `editOriginal(error.generic)` with the id. Do not rethrow.
5. A bot user as target is not special-cased (it is simply not linked).

### Embed
Built with JDA `EmbedBuilder`; every text comes from `Messages`. Numbers are pre-formatted Strings: averages with `String.format(Locale.ROOT, "%.1f", value)`.
- Title: `profile.title` with `{0}` = target's effective name (`User.getEffectiveName()`).
- Field (not inline) `profile.field.rank` → value:
  - unranked → `profile.rank_unranked`;
  - `rankName != null` and `subrank > 0` → `profile.rank_value` (`{0}` name, `{1}` subrank);
  - `rankName != null` and `subrank == 0` → `rankName` alone;
  - `rankName == null` and `rankTier != null` → `profile.rank_unknown_tier` (`{0}` tier).
- Field (not inline) `profile.field.recent` with `{0}` = `recentMatches` → value `profile.recent_value` (`{0}` wins, `{1}` matches, `{2}` win rate percent, `{3}`/`{4}`/`{5}` average kills/deaths/assists).
- Field (not inline) `profile.field.top_heroes` → one line per hero, joined with `\n`: `profile.hero_line` (`{0}` hero name or `profile.hero_unknown` with `{0}` = id, `{1}` matches). Empty list → `profile.no_recent_heroes`.
- Footer: `profile.footer` with `{0}` = account id.

## User-facing text (`messages.properties`, append, English)
Messages with arguments must not contain a single apostrophe (write `''` if unavoidable). Command and option descriptions: 1-100 chars. Embed title max 256 chars, field name max 256.

| Key | Intent |
|---|---|
| `profile.description` | Command description: show rank, recent matches and top heroes |
| `profile.option.user` | Option description: the Discord user to look up (default: you) |
| `profile.not_linked_self` | You have no linked account; run `/link` with your account (mention `/link`) |
| `profile.not_linked_other` | `{0}` = mention. That user has not linked a Deadlock account |
| `profile.no_matches` | No matches found for the linked account; data may not be tracked yet; text must contain `ingest tool` |
| `profile.title` | `{0}` = display name. Example intent: "Deadlock profile: {0}" |
| `profile.field.rank` | "Rank" |
| `profile.rank_value` | `{0}` rank name, `{1}` subrank, e.g. "Archon 3" |
| `profile.rank_unranked` | "Unranked" |
| `profile.rank_unknown_tier` | `{0}` tier, e.g. "Rank tier 7" |
| `profile.field.recent` | `{0}` = count, e.g. "Last 10 matches" |
| `profile.recent_value` | `{0}` wins, `{1}` matches, `{2}` win rate %, `{3}`-`{5}` average K/D/A; two lines |
| `profile.field.top_heroes` | "Top heroes (last 30 days)" |
| `profile.hero_line` | `{0}` hero, `{1}` matches, e.g. "Infernus - 12 matches" |
| `profile.hero_unknown` | `{0}` id, e.g. "Hero #{0}" |
| `profile.no_recent_heroes` | No matches in the last 30 days |
| `profile.footer` | `{0}` account id; says the data comes from deadlock-api |

## Tests
JUnit 5, AssertJ, Mockito. Use `MessagesFixture.english()`. Use the existing JSON fixtures in `src/test/resources/fixtures/deadlock-api/` only if convenient; building `MatchHistoryEntry` records in the test is preferred for exact control. No new fixture is required.

### `ProfileServiceTest` (mocks, fixed `Clock`)
1. `findLinkedAccountIdReturnsAccountWhenLinked` / `...EmptyWhenNotLinked`.
2. `emptyHistoryThrowsNoMatchHistoryAndCallsNothingElse`: rank and assets never called.
3. `usesOnlyTheTenNewestMatchesForRecentStats`: 12 entries → `recentMatches` 10; wins, win rate and averages computed from the first 10 only.
4. `fewerThanTenMatchesUsesAll`: 3 entries → `recentMatches` 3, correct averages.
5. `winIsMatchResultEqualsPlayerTeam`: include entries with `playerMatchOutcome` 0 that are wins by the rule.
6. `winRateIsRounded`: 2 wins of 3 → 67.
7. `topHeroesCountsOnlyLast30DaysSortedByCountThenId`: entries just inside and just outside 30 days; ties broken by lower hero id; max 3.
8. `topHeroesEmptyWhenNoMatchInWindow`.
9. `unknownHeroIdHasNullName`.
10. `unrankedPlayerHasNullTierAndName`: badge 0.
11. `rankNameResolvedFromAssets` and `missingTierInAssetsGivesNullName`.
12. `propagatesUnavailableException`.

### `ProfileServiceCachingTest`
Small Spring context (`@SpringJUnitConfig`, style of `DeadlockApiClientCachingTest`) importing `CacheConfig` and `ProfileService`, with mocked `LinkedAccountRepository` and `DeadlockApiClient` beans.
1. `secondCallForSameAccountHitsCache`: history called once for two `getProfile(1000001)` calls.
2. `differentAccountsAreCachedSeparately`.
3. `failureIsNotCached`: first call throws `DeadlockApiUnavailableException`, second succeeds and calls the client again.
4. `profileCacheIsRegisteredWithTenMinuteTtl`: the `profileSummary` cache exists in the `CacheManager` (and, if the existing caching test checks the policy, check 10 min the same way).

### `ProfileCommandTest`
Style of `LinkCommandTest`.
1. `nameIsProfileWithOptionalUserOption`: type `USER`, not required, descriptions equal the catalog text, 1-100 chars.
2. `callerNotLinkedRepliesEphemerallyWithoutDeferring`.
3. `otherUserNotLinkedRepliesWithMention`: text contains the target's mention; no deferral; `getProfile` never called.
4. `optionUserIsUsedAsTarget`: service is asked for the option user's id, not the caller's.
5. `successDefersPubliclyAndEditsWithEmbed`: `deferReply()` without ephemeral; embed title, three fields (names and values) and footer match the catalog, e.g. `Archon 3`, `7 wins of 10`-style text built from the catalog, averages with one decimal.
6. `embedShowsUnrankedUnknownTierUnknownHeroAndNoRecentHeroes` (one or more tests).
7. `noMatchesEditsWithNoMatchesMessage`, `unavailableEditsWithBusyMessage`.
8. `unexpectedErrorEditsWithGenericMessageAndDoesNotRethrow`.
9. `catalogTextsHaveRequiredContent`: `profile.no_matches` contains `ingest tool`; `profile.not_linked_self` contains `/link`.

### `MessagesIntegrationTest` (existing, minimal edit)
`requiredKeys` includes every new key; the command-name assertion includes `profile`. Needs Docker.

## Verification
1. `./mvnw clean verify` passes with Docker running (on Windows `.\mvnw.cmd clean verify`; see the `MAVEN_USER_HOME` note in `docs/PROJECT_CONTEXT.md` if the wrapper fails). Report the real test count against the baseline of 135 from the Task 022 report.
2. `git diff --check` and a review of the final diff: only the files in the table changed; no secret, token, real account id or real user data; `.env` never read or printed.
3. Optional manual check in the dev guild (needs `DISCORD_TOKEN`, `DEV_GUILD_ID`, PostgreSQL): `/profile` linked and not linked, `/profile user:@other` linked and not linked. If you do not run it, say so in the report.

## Documentation
- Task report `docs/task-reports/006-profile.md`: status, summary, files, commands run with real results, deviations, known issues.
- `docs/PROJECT_CONTEXT.md`: **do not edit in this branch.** Task 008 runs in parallel; mark the context update as "pending integration" in the report (`AGENTS.md`, Parallel tasks), listing the facts to add after both branches merge.
- No ADR. Do not edit `docs/mvp-spec.md`, `docs/architecture.md`, `docs/features.md`, other specs or `docs/roadmap.md`.

## Out of scope
- Party stats, solo vs party win rate, `mmr-history`, per-hero win rates, more than 3 heroes (Tasks 014, 015).
- Any change to `DeadlockApiClient`, DTOs, `LinkService`, `CommandRouter`, `HelpCommand`, `help.body`, `Messages`, `application.yaml`, `pom.xml`.
- Cache eviction on `/link` or `/unlink` (a re-link can show the old profile up to 10 min; accepted, note it in the report).
- Fetching more than the 200 history entries the client keeps (top heroes counts only those; note it in the report).
- Async execution or a `Clock` bean.

## Acceptance criteria
- [ ] `./mvnw clean verify` passes; test count and baseline difference are in the report
- [ ] `/profile` without option shows the caller's profile; with `user` shows that user's
- [ ] Not-linked targets get an ephemeral reply before any deferral and no API call
- [ ] Linked targets get a public deferred embed with rank, last 10 summary and top 3 heroes (last 30 days), all text from `messages.properties`
- [ ] Win = `matchResult == playerTeam`; recent stats use at most the 10 newest matches; ties in top heroes broken by hero id
- [ ] `getProfile` is cached 10 min per account; failures are not cached; API calls are sequential
- [ ] Empty history, API unavailable and unexpected errors each give their own message; unexpected errors log an error id and do not rethrow
- [ ] Commands contain no business logic; `ProfileService` uses no JDA types
- [ ] Only the files in the table changed; no new dependency, migration or secret
- [ ] Task report created with `docs/PROJECT_CONTEXT.md` update marked pending integration; final diff has no unrelated files

## Decisions and open questions
Decisions taken in this spec:
1. **Not-linked check before deferring**, so the not-linked reply is ephemeral and cheap, while the profile itself is public (`docs/mvp-spec.md`).
2. **Cache the computed summary**, not the raw calls only: the history cache is 2 min, the spec asks 10 min for the profile.
3. **Top heroes come from the cached history (max 200 entries)**, not from a new endpoint. Very active players may have more than 200 matches in 30 days; accepted for the MVP.
4. **Win rule `matchResult == playerTeam`**, because `playerMatchOutcome` is 0 for older matches.

Open for the owner (not blocking):
1. Whether `/profile user:@other` should be allowed for every user (it is here, matching `docs/mvp-spec.md`). Revisit with premium/privacy rules.
