# Task 016: `/leaderboard server`

## Status

Implemented and verified locally. `.\mvnw.cmd clean verify` passes with 380 tests (344 baseline + 36 new, 2 skipped benchmark tests). Not pushed, not merged.

## Step 0 Findings

1. **JDA API verification (JDA 5.0.0)**:
   - `GatewayIntent.GUILD_MEMBERS` exists.
   - `JDABuilder.createLight(String, Collection<GatewayIntent>)` exists.
   - `Guild#retrieveMembersByIds(Collection<Long>)` (and `long...` varargs) exists, returning `Task<List<Member>>` with `onSuccess` and `onError` handlers.
   - **Request chunking**: Checked `net.dv8tion.jda.internal.entities.GuildImpl` bytecode; JDA explicitly checks `Checks.check(ids.length <= 100, "You can only request 100 members at once")` and throws `IllegalArgumentException` when more than 100 IDs are passed. JDA does *not* split requests automatically. The command partitions candidates into chunks of at most 100 IDs and queries them sequentially.
   - `net.dv8tion.jda.api.utils.MarkdownSanitizer.escape(String)` exists.

2. **Live GET verification (`/v1/players/rank`)**:
   - Live query executed against `https://api.deadlock-api.com/v1/players/rank?account_ids=139205933,1` returned HTTP 200 OK with `[{"account_id":139205933,"badge":116,"rank":11,"subrank":6,"last_match":{...}},{"account_id":1,"badge":0,"rank":0,"subrank":0,"last_match":null}]`.
   - Also tested with encoded commas `%2C`; both returned HTTP 200 OK.
   - In Spring's `UriBuilder`, `uri.path(PLAYER_RANKS_PATH).queryParam("account_ids", joined).build()` generates raw commas (`,`), which the API accepts cleanly.
   - Response field names match `AccountRank` (`account_id`, `badge`, `rank`, `subrank`); `last_match` is ignored.

## Summary

- Created `clients/dto/AccountRank` record (`accountId`, `badge`, `rank`, `subrank`) with `@JsonIgnoreProperties(ignoreUnknown = true)`.
- Extended `clients/DeadlockApiClient`:
  - Added import `AccountRank` right after `AbilityOrderStat`.
  - Added path constant `PLAYER_RANKS_PATH = "/v1/players/rank"` right after `PLAYER_RANK_PATH`.
  - Added `PLAYER_RANKS_READER` body reader right after `RANK_READER`.
  - Added `@Cacheable(cacheNames = CacheConfig.DEADLOCK_PLAYER_RANKS, sync = true, key = "#accountIds") public Map<Long, AccountRank> getPlayerRanks(List<Long> accountIds)` right after `getPlayerRank`. Validates non-empty, max 1000 IDs, and non-negative values before making HTTP requests.
- Extended `config/CacheConfig`:
  - Added constant `DEADLOCK_PLAYER_RANKS = "deadlockPlayerRanks"`.
  - Registered cache with 10-minute TTL and max size 100 right after `DEADLOCK_PLAYER_RANK`.
- Updated `config/JdaConfig`:
  - Configured `JDABuilder.createLight(botProperties.token(), List.of(GatewayIntent.GUILD_MEMBERS))` to enable the privileged guild members intent.
- Created domain records:
  - `domain/LinkedMember(long discordUserId, long accountId)`.
  - `domain/LeaderboardEntry(long discordUserId, long accountId, int badge, int tier, int subrank, String rankName)`.
  - `domain/ServerLeaderboard(List<LeaderboardEntry> entries, int rankedCount, int unrankedCount)` with defensive immutable copy.
- Created `services/LeaderboardService`:
  - `findCandidates()` fetches up to 500 newest links (`MAX_CANDIDATES`) ordered by `linkedAt` desc from `LinkedAccountRepository`.
  - `buildLeaderboard(List<LinkedMember> membersInServer)` performs a single batch `getPlayerRanks` call for distinct, sorted account IDs and resolves tier names via `getRanks()`.
  - Sorts entries by `badge` descending, then `discordUserId` ascending. Caps returned entries to top 10 (`LEADERBOARD_SIZE`) while returning total `rankedCount` and `unrankedCount`.
- Created `commands/LeaderboardCommand`:
  - Registered `/leaderboard server` with `setGuildOnly(true)`.
  - Checks guild presence (defensive ephemeral `leaderboard.guild_only`) and subcommand name (ephemeral `error.unknown_command`).
  - Public `deferReply()`, fetches candidate linked accounts, chunks into batches of 100, and resolves guild membership with `guild.retrieveMembersByIds`.
  - Package-private `respond(...)` builds leaderboard, formats plain Markdown embed (no colors/thumbnails) with escaped member names, rank text (`leaderboard.rank_value`, bare `rankName` when subrank 0, or `leaderboard.rank_unknown_tier`), and footer counts.
  - Handles empty results (`leaderboard.empty`), busy source (`error.data_source_busy`), and unexpected errors (`error.generic` with UUID error ID).
- Updated `src/main/resources/messages.properties`:
  - Added `leaderboard.*` keys right after `link.no_matches`.
- Tests added:
  - `src/test/resources/fixtures/deadlock-api/player-ranks.json` with 4 mock accounts.
  - `src/test/java/sntiago05/rejuv/clients/PlayerRanksClientTest.java` (15 endpoint tests).
  - `src/test/java/sntiago05/rejuv/clients/DeadlockApiClientCachingTest.java` (exact-set map update + 1 caching test).
  - `src/test/java/sntiago05/rejuv/services/LeaderboardServiceTest.java` (9 unit tests).
  - `src/test/java/sntiago05/rejuv/commands/LeaderboardCommandTest.java` (11 unit tests covering routing, embeds, errors, message resolution, and length limits).

## Important Files

- `src/main/java/sntiago05/rejuv/clients/dto/AccountRank.java` (create)
- `src/main/java/sntiago05/rejuv/clients/DeadlockApiClient.java` (modify)
- `src/main/java/sntiago05/rejuv/config/CacheConfig.java` (modify)
- `src/main/java/sntiago05/rejuv/config/JdaConfig.java` (modify)
- `src/main/java/sntiago05/rejuv/domain/LinkedMember.java` (create)
- `src/main/java/sntiago05/rejuv/domain/LeaderboardEntry.java` (create)
- `src/main/java/sntiago05/rejuv/domain/ServerLeaderboard.java` (create)
- `src/main/java/sntiago05/rejuv/services/LeaderboardService.java` (create)
- `src/main/java/sntiago05/rejuv/commands/LeaderboardCommand.java` (create)
- `src/main/resources/messages.properties` (modify)
- `src/test/resources/fixtures/deadlock-api/player-ranks.json` (create)
- `src/test/java/sntiago05/rejuv/clients/PlayerRanksClientTest.java` (create)
- `src/test/java/sntiago05/rejuv/clients/DeadlockApiClientCachingTest.java` (modify)
- `src/test/java/sntiago05/rejuv/services/LeaderboardServiceTest.java` (create)
- `src/test/java/sntiago05/rejuv/commands/LeaderboardCommandTest.java` (create)
- `docs/task-reports/016-leaderboard-server.md` (create)

## Decisions

1. **`GUILD_MEMBERS` intent enabled**: Enables `retrieveMembersByIds` so the bot can discover server membership for candidate linked accounts without requiring a database migration or per-user REST lookups.
2. **Sequential chunking of member requests**: Since JDA limits `retrieveMembersByIds` to 100 IDs, candidates are partitioned into 100-ID chunks and processed sequentially before building the final embed response.
3. **Single batch API call for ranks**: Deadlock API batch rank endpoint `/v1/players/rank` is called once per distinct sorted list of account IDs, avoiding N individual rank calls and remaining within rate limits (20 req/min/IP).
4. **No member mentions**: Embed description uses sanitized effective names (`MarkdownSanitizer.escape`) and avoids `<@id>` mentions so `/leaderboard server` never pings users.
5. **Separation of glue method**: `LeaderboardCommand.respond(...)` is package-private and accepts raw maps, enabling comprehensive unit testing without requiring Discord gateway connections.

## Verification

- **Baseline before changes**: `.\mvnw.cmd clean verify` passed with 344 tests (2 skipped benchmark tests), 0 failures.
- **Narrow test runs**:
  - `.\mvnw.cmd test -Dtest=PlayerRanksClientTest`: 15 tests, 0 failures.
  - `.\mvnw.cmd test -Dtest=DeadlockApiClientCachingTest`: 12 tests, 0 failures.
  - `.\mvnw.cmd test -Dtest=LeaderboardServiceTest`: 9 tests, 0 failures.
  - `.\mvnw.cmd test -Dtest=LeaderboardCommandTest`: 11 tests, 0 failures.
- **Full build**: `.\mvnw.cmd clean verify` passed with **380 tests** (2 skipped benchmark tests), 0 failures (+36 new tests).
- **Diff check**: `git diff --check` clean; only files listed in the specification table were touched; `.env` was never read or printed.
- **Manual Discord check**: Not executed by implementer. Requires live Discord credentials (`DISCORD_TOKEN`, `DEV_GUILD_ID`) and the owner prerequisite below (Server Members Intent toggle enabled in Discord Developer Portal). The intent behavior and `retrieveMembersByIds` cannot be mocked without live Discord.

## Acceptance Criteria

| Criterion | Evidence |
|---|---|
| `getPlayerRanks` is cached (`deadlockPlayerRanks`, 10 min, max 100), makes one HTTP request per distinct id list, and is covered by the exact-set cache test | `DeadlockApiClient.java` line 102, `CacheConfig.java`, `DeadlockApiClientCachingTest.cacheTtlsAndSizesMatchTheDesign`, and `playerRanksAreCachedBySortedIdListAndFailuresAreNotCached` |
| `/leaderboard server` lists at most 10 linked members of the current server by rank, with names, rank text and counts in the footer | `LeaderboardServiceTest.limitsEntriesToTop10WhileReportingTotalRankedCount`, `LeaderboardCommandTest.respondWithRankedMembersBuildsEmbedWithSanitizedNamesAndCounts` |
| Linked users who are not in the server are not listed; unranked or missing accounts are counted, not listed | `LeaderboardServiceTest.absentAccountsAndBadgeZeroAreCountedAsUnrankedAndNotListed`, `LeaderboardCommandTest.respondWithRankedMembersBuildsEmbedWithSanitizedNamesAndCounts` |
| Member names cannot inject Markdown or mentions; nobody is pinged | `MarkdownSanitizer.escape` in `LeaderboardCommand.buildEmbed`, tested in `LeaderboardCommandTest.respondWithRankedMembersBuildsEmbedWithSanitizedNamesAndCounts` |
| No linked members, no ranked members, busy source and unexpected errors have distinct messages; the unexpected one logs an error id | `LeaderboardCommandTest.noCandidatesRepliesEmpty`, `respondWithZeroRankedMembersRepliesEmpty`, `respondWhenServiceThrowsUnavailableRepliesDataSourceBusy`, `candidatesDatabaseFailureHandlesUnexpectedError`, `respondWhenServiceThrowsUnexpectedHandlesErrorWithoutRethrow` |
| `JdaConfig` enables only `GUILD_MEMBERS` (no cache policy or other intent); no new dependency or migration; the report states whether the owner confirmed the portal toggle | `JdaConfig.java` passes `List.of(GatewayIntent.GUILD_MEMBERS)`; `pom.xml` untouched; no migration files created; owner toggle confirmation noted below |
| All user-facing text is in `messages.properties` | `messages.properties` lines 47-56; verified by `LeaderboardCommandTest.allLeaderboardMessagesResolveToNonBlankText` |
| `./mvnw clean verify` passes; test count and baseline difference are in the report | Full build passed: 380 tests (344 baseline + 36 new, 2 skipped benchmark tests), exit code 0 |
| Task report created; `docs/PROJECT_CONTEXT.md` update marked pending integration; final diff has no unrelated files | This report; `PROJECT_CONTEXT.md` deferred per parallel worktrees rule; `git status --short` shows exact table match |

## Deviations

None. All implementation files, signatures, cache definitions, and messages match the specification.

## Known Issues & Owner Prerequisites

- **Owner prerequisite**: Before running the bot with Discord or deploying, the **Server Members Intent** must be enabled in the Discord Developer Portal (Bot > Privileged Gateway Intents). Without it, Discord closes the gateway connection ("Disallowed intents") and the bot fails to start.
- **500 candidate cap**: `LeaderboardService.findCandidates()` selects the 500 most recently linked accounts. For large deployments, this should be superseded by a per-guild member streaming approach.
- **Bot verification above 100 guilds**: Discord requires bot verification and approval for privileged gateway intents if the bot joins more than 100 guilds.

## PROJECT_CONTEXT integration

Marked **pending integration** because parallel task worktrees and branches are currently active (`feature/024-rich-presentation`, `feature/009-analyze`, `feature/014-heroes`, etc.).

Facts to integrate into `docs/PROJECT_CONTEXT.md` upon merge:
- Task 016 (`/leaderboard server`) done: public embed ranking members of the current server with linked Deadlock accounts by rank (top 10).
- `DeadlockApiClient.getPlayerRanks(List<Long> accountIds)` batch rank endpoint (`/v1/players/rank`) cached in `deadlockPlayerRanks` (10 min, max 100).
- `JdaConfig` enables `GatewayIntent.GUILD_MEMBERS` (requires Server Members Intent toggle in Discord Developer Portal).
- `LeaderboardService` and `LeaderboardCommand` added (capped at 500 candidates).
- `/help` does not list `/leaderboard` yet (follow-up).

## Merge Risks

- `DeadlockApiClient.java`, `CacheConfig.java`, `JdaConfig.java`, `messages.properties`, and `DeadlockApiClientCachingTest.java` are shared with parallel tasks. All additions are modular and placed at the designated insertion points to minimize merge conflicts.
