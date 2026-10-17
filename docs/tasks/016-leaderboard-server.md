# Task 016: `/leaderboard server`

- **Branch**: `feature/016-leaderboard-server`
- **Implementer**: Antigravity
- **Related ADRs**: ADR-0014 (free community feature, shared caches first), ADR-0010 and ADR-0004 (deadlock-api), ADR-0002 (Caffeine), ADR-0008 (English, i18n-ready), ADR-0001 (JDA)

## Goal
Add `/leaderboard server`: a public embed that ranks the members of the current Discord server who have linked a Deadlock account, by their current rank (top 10). No AI call and no database change. It adds one batch read (`players/rank?account_ids=...`) to `DeadlockApiClient`, cached, so a leaderboard costs one API request however many members are linked.

## Context (verified when this spec was written, 2026-10-17)
Do not repeat this discovery. If a fact below is false in your checkout, stop and ask.

- Plan entry: `docs/implementation-plan.md`, task 016, depends on 005 (merged). Start the branch from the latest `origin/main`. Baseline `./mvnw clean verify` = 344 tests (2 skipped benchmark tests); measure it before your first change.
- **Parallel work.** Task 024 (rich presentation, bumps JDA), Task 009 (`/analyze`) and Task 014 (`/heroes`) are implemented at the same time. Therefore: **do not use or create** `EmbedStyle`, `IconCatalog`, `AssetImages`; use a plain `EmbedBuilder`; do not touch `HelpCommand`, `help.body`, `HelpCommandTest`, `MessagesIntegrationTest`, `LinkedAccountRepository`, `LinkService`, `ProfileService`, `pom.xml` (the JDA version may differ between branches: use only the JDA API listed in Step 0). This task changes `JdaConfig` (one line, see below); Task 024 does not list it as modified, but if its `EmojiSyncStarter` branch also edits `JdaConfig`, keep both changes. A later follow-up adds `/leaderboard` to `/help` and restyles it. Expected, trivial merge conflicts (keep both sides): `CacheConfig`, `DeadlockApiClient`, `DeadlockApiClientCachingTest`, `messages.properties`. Follow the insertion points below to keep them rare. No migration in this task (Task 009 owns `V2`).
- **Endpoint: `GET /v1/players/rank`** ("Batch Rank"; the older `GET /v1/players/mmr` is deprecated, do not use it). Verified in `https://api.deadlock-api.com/openapi.json` and with live calls on 2026-10-17 (HTTP 200): query `account_ids` required, comma separated, 1..1000 ids; response is an array with one item per requested account: `{"account_id":139205933,"badge":116,"rank":11,"subrank":6,"last_match":{...}}`; an account without ranked data returns `{"account_id":1,"badge":0,"rank":0,"subrank":0,"last_match":null}` (not 404). `badge = rank * 10 + subrank`. Rate limit: **IP 20 req/min** (key 100/min, global 200/min). The spec contradicts itself about protected accounts ("every requested account is returned" vs "protected accounts are left out"), so treat an account missing from the response like an unranked one.
- Existing code you will call (real signatures): `DeadlockApiClient.getRanks()` -> `List<RankAsset(int tier, String name)>` (cached 24 h), `DeadlockApiClient.getPlayerRank(long)` (single account; unchanged and not used here), `repositories/LinkedAccountRepository` (`JpaRepository<LinkedAccount, Long>`; `LinkedAccount` has `getDiscordUserId()`, `getAccountId()`, `getLinkedAt()`), `commands/CommandSupport.handleUnexpectedError(...)`, `error.data_source_busy`, `error.generic`. `config/JdaConfig` builds the bot with `JDABuilder.createLight(botProperties.token(), Collections.emptyList())`: **no gateway intents**, no member cache. Owner decision (2026-10-17): this task enables the privileged `GUILD_MEMBERS` intent so the bot can look up which linked users are in a server.
- Reference implementations: `ProfileCommand` (deferral, own `try/catch` after it; catch `DeadlockApiUnavailableException` before the broader types, which extend `DeadlockApiException`) and `ProfileService`.
- Rules from `AGENTS.md`: every client read cached; sequential API calls; DTO records with `@JsonIgnoreProperties(ignoreUnknown = true)` and only used fields; no JDA types in `services/` (the command does the Discord part and passes plain ids and names); no business logic in `commands/`; all user-facing text in `messages.properties` (messages with arguments contain no single apostrophe); no new dependency.

## Owner prerequisite (before any manual run)
The **Server Members Intent** must be switched on for the bot in the Discord Developer Portal (Bot > Privileged Gateway Intents). Without it Discord closes the gateway connection ("Disallowed intents") and the **whole bot fails to log in**, not only this command. Free for bots in fewer than 100 servers (Discord verification is required beyond that). Ask the owner to confirm the toggle is on **before** you start the bot for the manual check, and say so in the report. Merging this task to `main` requires the same toggle wherever the bot runs (also production).

## Step 0: verify (before writing code)
1. **JDA API** in the JDA version of your checkout (record the version): `GatewayIntent.GUILD_MEMBERS`, `JDABuilder.createLight(String, Collection<GatewayIntent>)`, `Guild#retrieveMembersByIds(Collection<Long>)` (or the `long...` form) returning a `Task<List<Member>>` with `onSuccess`/`onError`, and whether JDA splits a request of more than 100 ids by itself (Discord limits one member request to 100 ids); `net.dv8tion.jda.api.utils.MarkdownSanitizer.escape(String)`. If any is missing, **stop and ask**.
2. **One live GET** through the format your client will use: `https://api.deadlock-api.com/v1/players/rank?account_ids=<id1>,<id2>` (two public account ids, or ask the owner). Confirm the commas are accepted when `UriBuilder` builds the query (check whether it encodes them as `%2C` and that the API still answers 200 with both accounts), and the item field names. If a name differs, use the real name in `@JsonProperty`, keep the Java names below and report it. If the structure differs materially, **stop and ask**.

## Files to create or modify
| File | Change |
|---|---|
| `src/main/java/sntiago05/rejuv/clients/dto/AccountRank.java` | **Create.** DTO record. |
| `src/main/java/sntiago05/rejuv/clients/DeadlockApiClient.java` | **Modify.** Add only: the import of `AccountRank` (right after the `AbilityOrderStat` import), a path constant right after `PLAYER_RANK_PATH`, a `BodyReader` constant right after `RANK_READER`, and the method `getPlayerRanks` right after `getPlayerRank`. Change nothing else. |
| `src/main/java/sntiago05/rejuv/config/CacheConfig.java` | **Modify.** Only add `DEADLOCK_PLAYER_RANKS` (right after `DEADLOCK_PLAYER_RANK`) and its `register(...)` line (right after the `DEADLOCK_PLAYER_RANK` one). |
| `src/main/java/sntiago05/rejuv/config/JdaConfig.java` | **Modify.** Only the `createLight` call: pass `List.of(GatewayIntent.GUILD_MEMBERS)` instead of `Collections.emptyList()` (fix the imports). No member cache policy, no chunking filter, no presence intent, nothing else. |
| `src/main/java/sntiago05/rejuv/domain/LinkedMember.java` | **Create.** Record. |
| `src/main/java/sntiago05/rejuv/domain/LeaderboardEntry.java` | **Create.** Record. |
| `src/main/java/sntiago05/rejuv/domain/ServerLeaderboard.java` | **Create.** Record. |
| `src/main/java/sntiago05/rejuv/services/LeaderboardService.java` | **Create.** |
| `src/main/java/sntiago05/rejuv/commands/LeaderboardCommand.java` | **Create.** |
| `src/main/resources/messages.properties` | **Modify.** Add the `leaderboard.*` block **right after the `link.no_matches` line**. Change nothing else. |
| `src/test/resources/fixtures/deadlock-api/player-ranks.json` | **Create.** Real shape, **invented** ids and numbers, 4 items (ranked, Eternus subrank, zeros with `last_match: null`, one with a full `last_match` object). |
| `src/test/java/sntiago05/rejuv/clients/PlayerRanksClientTest.java` | **Create.** Endpoint tests (new class to avoid editing the shared `DeadlockApiClientTest`; copy its `MockRestServiceServer` setup pattern). |
| `src/test/java/sntiago05/rejuv/clients/DeadlockApiClientCachingTest.java` | **Modify.** Add the new cache to the exact-set maps in `cacheTtlsAndSizesMatchTheDesign` (right after the `DEADLOCK_PLAYER_RANK` entries) and one caching test for `getPlayerRanks`. |
| `src/test/java/sntiago05/rejuv/services/LeaderboardServiceTest.java` | **Create.** |
| `src/test/java/sntiago05/rejuv/commands/LeaderboardCommandTest.java` | **Create.** Also asserts the command name is `leaderboard` and every `leaderboard.*` key resolves (this replaces edits to `MessagesIntegrationTest`). |
| `docs/task-reports/016-leaderboard-server.md` | **Create.** Task report (format of `docs/task-reports/013-meta.md`). |

No `pom.xml`, `application.yaml`, migration or repository change. If you think one is needed, stop and ask.

## Interfaces / signatures

### `clients/dto/AccountRank` (new)
```java
@JsonIgnoreProperties(ignoreUnknown = true)
public record AccountRank(@JsonProperty("account_id") long accountId,
                          @JsonProperty("badge") int badge,
                          @JsonProperty("rank") int rank,
                          @JsonProperty("subrank") int subrank) { }
```
(`last_match` is not mapped.)

### `DeadlockApiClient.getPlayerRanks` (new method)
```java
/**
 * Current rank of many accounts in one request, keyed by account id. Accounts the API does not return are absent
 * from the map; an account without ranked data has badge 0. Callers pass a sorted, distinct, immutable list so the
 * cache key is stable. Callers must not call the analytics-style methods in parallel for different uncached keys.
 *
 * @throws IllegalArgumentException if the list is empty, has more than 1000 ids, or contains a negative id (before any HTTP call)
 */
@Cacheable(cacheNames = CacheConfig.DEADLOCK_PLAYER_RANKS, sync = true, key = "#accountIds")
public Map<Long, AccountRank> getPlayerRanks(List<Long> accountIds)
```
Path `/v1/players/rank`, one query parameter `account_ids` = the ids joined with `,`. Return an unmodifiable map (first item wins for a duplicated id). Failures use the existing rules (`DeadlockApiUnavailableException` for 503/429/I/O/timeout, `DeadlockApiException` for other statuses and unreadable bodies; failures are not cached).

### `CacheConfig`
`public static final String DEADLOCK_PLAYER_RANKS = "deadlockPlayerRanks"; // 10 min, max 100` registered with `Duration.ofMinutes(10)` and 100 entries.

### Domain records (new)
```java
public record LinkedMember(long discordUserId, long accountId) { }

/** rankName is null when the tier is not in the ranks asset. */
public record LeaderboardEntry(long discordUserId, long accountId, int badge, int tier, int subrank, String rankName) { }

/** entries: ranked members only, best first, at most LeaderboardService.LEADERBOARD_SIZE. rankedCount counts all ranked members (not only those shown). */
public record ServerLeaderboard(List<LeaderboardEntry> entries, int rankedCount, int unrankedCount) { }
```
Lists are immutable copies.

### `services/LeaderboardService` (new `@Service`)
```java
public static final int LEADERBOARD_SIZE = 10;
static final int MAX_CANDIDATES = 500;

public LeaderboardService(LinkedAccountRepository repository, DeadlockApiClient deadlockApiClient)

/** The most recently linked accounts (at most MAX_CANDIDATES), database only. */
public List<LinkedMember> findCandidates()

/**
 * Ranks the given members (the linked members who are in the server). One batch API call. Lets
 * DeadlockApiUnavailableException / DeadlockApiException propagate.
 */
public ServerLeaderboard buildLeaderboard(List<LinkedMember> membersInServer)
```

## Behavior

### How membership is found
The bot keeps no member cache, so the command does not list the server. It takes the linked Discord user ids from the database (newest links first, at most `MAX_CANDIDATES` = 500) and asks Discord which of them are members of the current server with `Guild#retrieveMembersByIds` (needs the `GUILD_MEMBERS` intent). Users who are not members are simply absent from the result. If JDA does not split requests above 100 ids (Step 0), the command sends chunks of at most 100 ids one after another and merges the results; the final `respond(...)` call happens once, after the last chunk.

### `LeaderboardService`
- `findCandidates()`: `repository.findAll(PageRequest.of(0, MAX_CANDIDATES, Sort.by(Sort.Direction.DESC, "linkedAt")))`, mapped to `LinkedMember`. No new repository method.
- `buildLeaderboard(members)`: empty input -> `ServerLeaderboard(List.of(), 0, 0)` with **no API call**. Otherwise: distinct account ids, sorted ascending, as an immutable list -> **one** `getPlayerRanks(ids)` call, then `getRanks()` for names. Single pass over the members: an entry is *ranked* when the account is in the response with `badge > 0`; absent accounts and `badge == 0` count as unranked. Entries sort by `badge` desc, then `discordUserId` asc; `entries` = the first `LEADERBOARD_SIZE`; `tier = rank`, `subrank` from the API, `rankName` from the ranks asset by tier (hash map lookup, not a nested scan). Several Discord users linked to the same account each get their own entry.

### `/leaderboard server` (`LeaderboardCommand`, name `leaderboard`, public)
1. `data()`: `Commands.slash("leaderboard", ...)` with `setGuildOnly(true)` and one subcommand `server` (description from the catalog). Any other subcommand name -> ephemeral `error.unknown_command`. `event.getGuild() == null` (defensive) -> ephemeral `leaderboard.guild_only`.
2. `event.deferReply().queue()`. Then, inside a `try/catch` for the database part: `candidates = service.findCandidates()`; empty -> edit with `leaderboard.empty`.
3. `guild.retrieveMembersByIds(ids)` (chunked as above) with `onSuccess(members -> ...)` and `onError(failure -> CommandSupport.handleUnexpectedError(...))`. Collect `member.getIdLong() -> member.getEffectiveName()` for the returned members. No member returned -> edit with `leaderboard.empty`.
4. The glue after the lookups lives in a package-private method `void respond(InteractionHook hook, long callerId, String guildName, Map<Long, String> namesByUserId, Map<Long, Long> accountIdByUserId)` so it can be unit-tested without JDA tasks: it builds the `LinkedMember` list, calls `service.buildLeaderboard`, and edits the original message. Errors there: `DeadlockApiUnavailableException` -> `error.data_source_busy`; `RuntimeException` -> `CommandSupport.handleUnexpectedError(hook, messages, log, "Leaderboard", callerId, "guild=" + guildName, ex)`. No ranked member -> plain text `leaderboard.empty`.
5. Embed (`EmbedBuilder`, no color/thumbnail): title `leaderboard.title` (`{0}` guild name); description = one line per entry `leaderboard.entry` (`{0}` position starting at 1, `{1}` member name passed through `MarkdownSanitizer.escape`, `{2}` rank text: `leaderboard.rank_value` with name and subrank when `rankName != null` and `subrank > 0`, the bare `rankName` when `subrank == 0`, or `leaderboard.rank_unknown_tier` with the tier); footer `leaderboard.footer` (`{0}` rankedCount, `{1}` unrankedCount). The description is at most 4096 characters (10 short lines; a test with 10 members whose names are 32 characters proves it).
6. All user mentions are avoided on purpose (plain names, no `<@id>`), so the reply pings nobody.

### User-facing text (`leaderboard.*`, English)
`leaderboard.description`, `leaderboard.server.description`, `leaderboard.guild_only`, `leaderboard.empty` (explains that members must run `/link` and have a rank), `leaderboard.title`, `leaderboard.entry`, `leaderboard.rank_value`, `leaderboard.rank_unknown_tier`, `leaderboard.footer` (states the counts and that ranks come from deadlock-api and are cached for 10 minutes). Reuse `error.data_source_busy`, `error.generic`, `error.unknown_command`.

## Tests
JUnit 5, AssertJ, Mockito; `MockRestServiceServer` for HTTP; `MessagesFixture.english()` for commands.

1. **`PlayerRanksClientTest`:** exact URL and `account_ids` format for 1 and 3 ids; fixture parses (unknown fields and `last_match` ignored); the map is keyed by account id; empty array -> empty map; empty list, 1001 ids and a negative id -> `IllegalArgumentException` with **no** request; 503 with `Retry-After` retried once then `DeadlockApiUnavailableException`; 500 -> `DeadlockApiException`; malformed body -> `DeadlockApiException`.
2. **Caching test:** the same sorted list makes one HTTP call; a different list makes a second call; a failure is not cached; the exact-set test includes the new cache with 10 min / 100.
3. **`LeaderboardServiceTest`:** `findCandidates` requests page 0 of size 500 sorted by `linkedAt` desc and maps the rows; `buildLeaderboard` with an empty list makes no call; ids passed to the client are distinct, sorted and immutable (two users, same account -> one id, two entries); ordering by badge desc then Discord id asc; accounts absent from the response and `badge == 0` are counted as unranked and not listed; more than 10 ranked members -> 10 entries and the real `rankedCount`; rank names resolved (null name for an unknown tier); exceptions propagate.
4. **`LeaderboardCommandTest`:** `data()` has name `leaderboard`, guild-only, one subcommand `server`; unknown subcommand and missing guild -> ephemeral replies, no deferral; `respond(...)` with ranked members -> embed with title, ordered lines with sanitized names (a name such as `**bold** @everyone` is escaped), footer counts; zero ranked -> `leaderboard.empty`; `DeadlockApiUnavailableException` -> `error.data_source_busy`; unexpected error -> generic message with an error id and no rethrow; the event handler defers publicly and replies `leaderboard.empty` when there are no candidates; all `leaderboard.*` keys resolve to non-blank text. The `retrieveMembersByIds` lookup and the intent itself cannot be unit-tested without Discord and are verified by the manual check (state that in the report).

## Verification
1. Step 0 recorded in the report (JDA version, API presence, the live call and the comma encoding result).
2. `./mvnw clean verify` passes with Docker running (Windows: `.\mvnw.cmd clean verify`; see the `MAVEN_USER_HOME` note in `docs/PROJECT_CONTEXT.md` if the wrapper fails). Report the real test count against the baseline.
3. `git diff --check`; the diff contains only the files in the table; `.env` never read or printed.
4. Manual check in the dev guild (needs the owner prerequisite above, `DISCORD_TOKEN`, `DEV_GUILD_ID`, PostgreSQL and at least two linked users, one of them not in the dev guild if possible; first confirm the bot still logs in with the intent, and that `/ping` works): `/leaderboard server` with no linked members, then with linked members; confirm the order, that a linked user who is not in the server is not listed, and that nobody is pinged. If you do not run it, say so in the report.

## Documentation
- Task report `docs/task-reports/016-leaderboard-server.md`: status, Step 0 findings, summary, files, commands run with real results, deviations, known issues.
- `docs/PROJECT_CONTEXT.md`: **do not edit in this branch** while other task branches are active. Mark the update "pending integration" in the report and list the facts to add (endpoint `players/rank`, the new cache, `LeaderboardService`, `/leaderboard server`, the `GUILD_MEMBERS` intent (Developer Portal toggle required, also in production), the 500-candidate cap, that `/help` does not list it yet).
- Do not edit `docs/features.md`, ADRs, `docs/mvp-spec.md`, `docs/implementation-plan.md` or other specs.

## Out of scope
- `/leaderboard global`, hero leaderboards, the caller's own position when outside the top 10, pagination, buttons.
- Any gateway intent other than `GUILD_MEMBERS`, member cache policy, chunking, presence, a `guild_member` table or any migration.
- `/help` text and `HelpCommandTest` (follow-up), presentation (Task 024 or a follow-up).
- Changing any existing client method, `getPlayerRank`, `ProfileService`, `LinkService` or the repository.

## Acceptance criteria
- [ ] `getPlayerRanks` is cached (`deadlockPlayerRanks`, 10 min, max 100), makes one HTTP request per distinct id list, and is covered by the exact-set cache test
- [ ] `/leaderboard server` lists at most 10 linked members of the current server by rank, with names, rank text and counts in the footer
- [ ] Linked users who are not in the server are not listed; unranked or missing accounts are counted, not listed
- [ ] Member names cannot inject Markdown or mentions; nobody is pinged
- [ ] No linked members, no ranked members, busy source and unexpected errors have distinct messages; the unexpected one logs an error id
- [ ] `JdaConfig` enables only `GUILD_MEMBERS` (no cache policy or other intent); no new dependency or migration; the report states whether the owner confirmed the portal toggle
- [ ] All user-facing text is in `messages.properties`
- [ ] `./mvnw clean verify` passes; test count and baseline difference are in the report
- [ ] Task report created; `docs/PROJECT_CONTEXT.md` update marked pending integration; final diff has no unrelated files

## Decisions and open questions
Decisions taken in this spec:
1. **`GUILD_MEMBERS` intent enabled** (owner decision, 2026-10-17). Membership of the linked users is looked up with `retrieveMembersByIds` (one request per 100 ids instead of one REST call per user). Cost: a Developer Portal toggle that must be on wherever the bot runs, and Discord verification above 100 servers. Rejected: per-user REST lookups without the intent (no portal step, but one call per candidate) and a `guild_member` table (a migration that would collide with Task 009's `V2`).
2. One batch rank request per leaderboard instead of one request per member, because the single-player endpoint would multiply uncached calls; the batch endpoint allows 20 requests per minute per IP, which the 10-minute cache covers.
3. Ranks are the "current rank" reported by the API (`badge`, the rank at the end of the player's latest ranked match), not a computed value.

Open for the owner (not blocking):
1. The 500-candidate cap means that, once more than 500 users are linked, the oldest links are not considered for any server. Replace it with a per-guild approach (for example the guild's member list in chunks) when the bot grows.
2. Above 100 servers Discord requires verification and approval for the intent.
