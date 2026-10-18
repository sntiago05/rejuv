# Task 028: Steam player names in `/mates`

- **Branch**: `feature/028-mates-names`
- **Implementer**: Antigravity
- **Related ADRs**: ADR-0004 and ADR-0010 (deadlock-api, hand-written client), ADR-0002 (Caffeine), ADR-0008 (English, i18n-ready), ADR-0014 (free social utilities)

## Goal
`/mates` shows each teammate as `Name (#accountId)` instead of only `Player #accountId`, using the Steam persona name from deadlock-api. If a name cannot be obtained, the line falls back to the current `Player #accountId` text and the command still works.

## Context (verified, see `docs/evidence/026-028-details.md` section A; 2026-10-18)
Do not repeat this discovery. If a fact below is false in your checkout, stop and ask.

- Depends on 015 (`/mates`, merged). Independent of 026 and 027. Start from the latest `origin/main`.
- Endpoint: `GET /v1/players/steam?account_ids=<comma separated>` (OpenAPI `operationId: steam`, tag Steam). `account_ids` is required, 1 to 1000 ids. Response: JSON array of `SteamProfile`; the field we use is `personaname` (string) with `account_id` (int32). Other fields (`avatar*`, `profileurl`, `friends`, `realname`, `countrycode`, ...) are **not** used and must not be mapped.
- **Never send `refresh`.** `refresh=true` forces a live Steam sync with a strict quota (3 req/min and 15 req/h per IP). Without it the endpoint reads the backend database (100 req/s per IP).
- Verified live (2 calls): `account_ids=139205933` gives HTTP 200 with `personaname` `stroodl`, cacheable (`cache-control: public, max-age=60`); `account_ids=139205933,999999999` gives HTTP 200 with **only the existing account** (unknown ids are silently omitted).
- **Not verified:** the response when **none** of the ids exist (OpenAPI says 404 "No Steam profile found.", it may also be `[]`), and whether private profiles omit `personaname`. The design below handles both without depending on the answer.
- 503 and 429 handling is already centralized in `clients/DeadlockApiRequests` (one 503 retry when `Retry-After` <= `max-retry-after`; 429, I/O and timeout become `DeadlockApiUnavailableException`; other statuses become `DeadlockApiException`; `DeadlockApiUnavailableException extends DeadlockApiException`).
- Real code to extend, following the pattern of `getPlayerRanks`: `DeadlockApiClient.getPlayerRanks(List<Long> accountIds)` (`@Cacheable(DEADLOCK_PLAYER_RANKS, sync = true, key = "#accountIds")`, validates the list before any HTTP call, builds the `account_ids` query with `Collectors.joining(",")`, returns an immutable map keyed by id with absent accounts missing). `CacheConfig` constants and `register(...)` calls. `clients/dto/AccountRank` as the DTO style. `MatesService.getMates(long accountId)` returns `MatesSummary(long accountId, List<Teammate> teammates)` with `Teammate(long accountId, int matches, int wins, double winRatePercent)`. `MatesCommand.embed` builds the label with `messages.get("mates.teammate_label", String.valueOf(id))` and the line with `mates.entry` (`{0}. {1} - {2} matches, {3}% win`).
- Rules from `AGENTS.md`: external DTOs are records with `@JsonIgnoreProperties(ignoreUnknown = true)` and only the fields we use; every client read is cached; sequential calls; no JDA types in `services/`; user-facing text in `messages.properties` (messages with arguments contain no single apostrophe); no new dependency.

## Files to create or modify
| File | Change |
|---|---|
| `src/main/java/sntiago05/rejuv/clients/dto/SteamProfile.java` | **Create.** DTO record. |
| `src/main/java/sntiago05/rejuv/clients/DeadlockApiClient.java` | **Modify.** Add `getPlayerNames`, the path constant and the reader. Nothing else. |
| `src/main/java/sntiago05/rejuv/config/CacheConfig.java` | **Modify.** Add the `DEADLOCK_PLAYER_NAMES` constant and its `register(...)` line only. |
| `src/main/java/sntiago05/rejuv/domain/MatesSummary.java` | **Modify.** Add the `names` component. |
| `src/main/java/sntiago05/rejuv/services/MatesService.java` | **Modify.** Fetch names for the selected teammates, tolerate failure. |
| `src/main/java/sntiago05/rejuv/commands/MatesCommand.java` | **Modify.** Use the names in the label. |
| `src/main/resources/messages.properties` | **Modify.** Add `mates.teammate_named` at the **end of the file**. |
| `src/test/resources/fixtures/deadlock-api/players-steam.json` | **Create.** Fixture with 2 profiles (use fake data, no real friends lists). |
| `src/test/java/sntiago05/rejuv/clients/DeadlockApiClientTest.java` | **Modify.** Add the `getPlayerNames` cases (use the file that already holds the `getPlayerRanks` tests; if they live elsewhere, modify that file instead and say so in the report). |
| `src/test/java/sntiago05/rejuv/clients/DeadlockApiClientCachingTest.java` | **Modify.** Add the cache case (same note as above). |
| `src/test/java/sntiago05/rejuv/services/MatesServiceTest.java` | **Modify.** Names cases. |
| `src/test/java/sntiago05/rejuv/commands/MatesCommandTest.java` | **Modify.** Label cases. |
| `docs/task-reports/028-mates-names.md` | **Create.** Task report (format of `docs/task-reports/014-heroes.md`). |

No `pom.xml` change, no migration. If you think another file is needed, stop and ask.

## Interfaces / signatures

```java
// clients/dto/SteamProfile.java
@JsonIgnoreProperties(ignoreUnknown = true)
public record SteamProfile(@JsonProperty("account_id") long accountId,
                           @JsonProperty("personaname") String personaName) { }

// DeadlockApiClient
private static final String PLAYER_STEAM_PATH = "/v1/players/steam";
/**
 * Steam persona names of many accounts in one request, keyed by account id. Accounts the API does not return, or
 * returns without a usable name, are absent from the map. Never sends the refresh parameter. Callers pass a sorted,
 * distinct, immutable list so the cache key is stable.
 *
 * @throws IllegalArgumentException if the list is empty, has more than 1000 ids, or contains a negative id (before any HTTP call)
 */
@Cacheable(cacheNames = CacheConfig.DEADLOCK_PLAYER_NAMES, sync = true, key = "#accountIds")
public Map<Long, String> getPlayerNames(List<Long> accountIds)

// CacheConfig
public static final String DEADLOCK_PLAYER_NAMES = "deadlockPlayerNames"; // 1 h, max 500
// register(cacheManager, DEADLOCK_PLAYER_NAMES, Duration.ofHours(1), 500);

// domain/MatesSummary
public record MatesSummary(long accountId, List<Teammate> teammates, Map<Long, String> names)
// compact constructor: teammates = List.copyOf(teammates); names = Map.copyOf(names) (null becomes Map.of())
```
The `Teammate` record and `MatesService.getMates(long)` signature are unchanged.

## Behavior

### `DeadlockApiClient.getPlayerNames`
1. Validate the list exactly like `getPlayerRanks` (message text may differ), before any HTTP call.
2. One GET `/v1/players/steam` with only the `account_ids` query parameter (comma separated, no spaces) and the existing request rules. Parse a JSON array of `SteamProfile`.
3. Build the map from entries whose `personaName` is non-null and not blank (after trim); duplicate ids keep the first. Return an unmodifiable map.
4. Failures follow the shared rules: a non-2xx status becomes `DeadlockApiException`, 503/429/I/O/timeout `DeadlockApiUnavailableException`. Failures are not cached (default Spring behavior for exceptions).

### `MatesService.getMates`
1. Unchanged selection of the top teammates (`TOP_MATES`, `MIN_MATCHES_TOGETHER`, 90-day window, comparator).
2. If the top list is empty, do **not** call `getPlayerNames`; return `names = Map.of()`.
3. Otherwise call `deadlockApiClient.getPlayerNames` once with the **sorted, distinct** teammate ids (an immutable list, ascending). Do not include the caller's own id.
4. If that call throws `DeadlockApiException` (this includes `DeadlockApiUnavailableException`, and a 404 for "no profile found"), log one WARN line with the exception class and message (no stack trace, no ids beyond the count), and return the summary with `names = Map.of()`. Names are optional: the mates statistics must not fail because of them. `IllegalArgumentException` and other runtime exceptions are not swallowed.
5. The mates call and the names call run **sequentially**.

### `MatesCommand`
1. For each teammate, `name = summary.names().get(accountId)`.
2. Name present: label = `messages.get("mates.teammate_named", sanitizedName, String.valueOf(accountId))`. Name absent: label = existing `mates.teammate_label` (unchanged text and behavior).
3. `sanitizedName`: trim; collapse line breaks and tabs to single spaces; truncate to 32 characters (cut by code point, not in the middle of a surrogate pair); escape Discord markdown with JDA's `MarkdownSanitizer.escape` (a `commands/` concern, since it is formatting) so a name such as `**x**` or one containing `@everyone` cannot restyle or ping. If the result is blank, treat the name as absent.
4. Everything else (title, ordering, win rate rounding, `EmbedStyle.fitField`, error replies, `deferReply`) is unchanged.

### User-facing text (English)
`mates.teammate_named={0} (#{1})`: the name followed by the account id. Do not change `mates.entry` or `mates.teammate_label`. Message arguments are passed as strings (no number grouping of the account id).

## Tests
1. **Client (`MockRestServiceServer` + `players-steam.json`):** request path and query are exactly `/v1/players/steam?account_ids=1,2` and **contain no `refresh`**; two profiles map to two names; an id missing from the response is absent; a profile with a null or blank `personaname` is absent; an empty array gives an empty map; 500 gives `DeadlockApiException`; 503 once then 200 succeeds (shared retry rule) and 429 gives `DeadlockApiUnavailableException`; an empty list, a list of 1001 ids and a negative id throw `IllegalArgumentException` with **no HTTP call**.
2. **Cache:** two calls with equal lists make one HTTP call; a failure is not cached (second call retries).
3. **`MatesServiceTest`:** names are fetched once for the sorted distinct teammate ids; no names call when there are no teammates; `DeadlockApiUnavailableException` and `DeadlockApiException` from the names call give a summary with the same teammates and empty names; the mates ordering is unaffected by names; the caller's id is never requested.
4. **`MatesCommandTest`:** a teammate with a name renders `Name (#id)` through the catalog; one without renders `Player #id`; markdown characters are escaped (`**x**`), a name longer than 32 characters is truncated, a name with a line break becomes one line, a blank name falls back; all catalog keys used resolve.
5. Existing `getPlayerRanks`, `MatesService` and `MatesCommand` tests keep passing after the `MatesSummary` constructor change (adapt only the construction sites).

## Verification
1. `./mvnw clean verify` passes with Docker running (Windows: `.\mvnw.cmd clean verify`; see the `MAVEN_USER_HOME` note in `docs/PROJECT_CONTEXT.md`). Report the real test count and the baseline difference.
2. `git diff --check`; the diff contains only the files in the table; `.env` never read or printed.
3. Live check (at most 2 sequential GET calls, no `refresh`): `GET /v1/players/steam?account_ids=<one known id>` returns the expected `personaname`. If you can, also record what the endpoint returns when **no** id exists (404 or `[]`) and add it to the report; the code must work either way. If you do not run it, say so.
4. Manual check (needs the usual credentials): `/mates` shows `Name (#id)` lines; with the network blocked for the names call (or an unknown id) the line shows `Player #id` and the command still answers. If you do not run it, say so in the report.

## Documentation
- Task report `docs/task-reports/028-mates-names.md`: status, summary, files, commands run with real results, deviations, known issues.
- `docs/PROJECT_CONTEXT.md`: **do not edit in this branch** while other task branches are active; mark it "pending integration" and list the facts (new endpoint, cache name and TTL, `MatesSummary.names`, failure tolerance).
- Do not edit ADRs, `docs/mvp-spec.md`, `docs/implementation-plan.md` or other specs.

## Out of scope
- Names, avatars or profile links in `/profile`, `/heroes`, `/leaderboard` or `/matches` (a later task can reuse `getPlayerNames`).
- The `refresh` parameter, friends lists, country codes, real names.
- Persisting names in the database.
- Any change to the mates query, thresholds or ordering.

## Acceptance criteria
- [ ] `/mates` shows `Name (#id)` when a name is known and `Player #id` otherwise
- [ ] The names request never contains `refresh`, is one batch call, and is cached
- [ ] A failure of the names call does not fail `/mates`
- [ ] Names are sanitized (markdown, length, line breaks) before display
- [ ] All user-facing text is in `messages.properties`
- [ ] Compiles, all tests pass (`./mvnw clean verify`)
- [ ] Follows AGENTS.md conventions; no new dependency
- [ ] Task report created; `docs/PROJECT_CONTEXT.md` update marked pending integration; final diff has no unrelated files

## Decisions and open questions
Decisions taken in this spec (architect, owner can veto): show both name and id as `Name (#id)` (owner request: "the name in addition to the id"); cache names 1 hour (names change rarely and the endpoint is cheap); names are best effort and never block the command. Open (not blocking): whether to show the avatar later.
