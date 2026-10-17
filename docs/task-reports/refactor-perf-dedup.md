# Report: performance and deduplication refactor (no task number)

**Status:** Done on branch `refactor/perf-dedup` (not merged). Requested directly by the owner on 2026-10-17; there is no task spec. Behavior of every command is unchanged except the edge case listed under Deviations.

## Summary
- **Shared deadlock-api request rules.** New package-private `clients/DeadlockApiRequests` holds the Task 004 GET rules (one 503 retry within the `Retry-After` cap, 429/I/O/timeout → `DeadlockApiUnavailableException`, other failures → `DeadlockApiException`, optional 404 mapping, optional "429 with body is success"). `DeadlockApiClient` and `JsonMatchMetadataSource` use it; both public constructors are unchanged. Resolves follow-up (b) of Task 022 and is the helper Task 023 should reuse.
- **Match history is read as a prefix.** `getMatchHistory` streams the JSON array and stops after `MAX_HISTORY_ENTRIES` (200) elements instead of deserializing the whole body (up to about 5 MB / 6,842 entries observed live) and discarding the rest. The reader uses the mapper of Spring's default `JacksonJsonHttpMessageConverter`, so DTOs are read with the same configuration. Empty, `null`, non-array and truncated bodies keep their previous outcomes.
- **`DeadlockApiClient` filters.** Four near-identical query builders and six `if (rankTier != null) requireRankTier(...)` blocks collapsed into `badgeRange(...)` and `requireRankTierIfPresent(...)`. Query parameters are unchanged.
- **`services/GameAssets`.** One place for hero/item lookup maps (`heroNames`, `namedHeroes`, `itemsById`, `itemNames`), rebuilt only when the cached asset list instance changes. Replaces the map building that `BuildService`, `CounterService`, `MetaService`, `ProfileService` and `HeroResolver` each repeated on every command.
- **Services.** `MetaService` now uses `ServiceSupport` (rank validation, 30-day UTC window) like `/build` and `/counter`. New `ServiceSupport.top(list, comparator, limit)` replaces four sort-and-copy blocks. `BuildService` groups columns in a `TreeMap` (no separate key sort). `ProfileService` computes wins and K/D/A averages in one pass.
- **Commands.** `CommandSupport.handleUnexpectedError` takes a free-form context string, so `/meta` and `/profile` reuse it (log lines keep the same text). `formatPercent` renamed `oneDecimal` and reused by `/meta` and `/profile`. `MetaCommand` reuses `rankOption`, `getRankTier`, `rankLabel` and groups heroes by tier in one pass (`EnumMap`) instead of one scan per tier. `CounterCommand` footer reads the thresholds from `CounterService` instead of duplicated string constants.

## Important files
- New: `clients/DeadlockApiRequests.java`, `services/GameAssets.java`, `test/.../services/GameAssetsTest.java`, `test/.../PerformanceBenchmarkTest.java` (disabled unless `-Dbenchmark=true`).
- Modified: `clients/DeadlockApiClient.java`, `clients/JsonMatchMetadataSource.java`, `commands/{CommandSupport,BuildCommand,CounterCommand,MetaCommand,ProfileCommand}.java`, `services/{BuildService,CounterService,MetaService,ProfileService,HeroResolver,ServiceSupport}.java`, tests that construct those services, `DeadlockApiClientTest` (+6 history tests), `MetaServiceTest` (+1).

## Verification (actually run)
- `./mvnw -B verify`: BUILD SUCCESS, 344 tests, 0 failures, 0 errors, 2 skipped (the benchmark). `main` had 332.
- `./mvnw -B -q test -Dtest=PerformanceBenchmarkTest -Dbenchmark=true` (median of 40 runs after 15 warm-up runs, in-memory mock server, this machine):
  - Match history, 6,842 synthetic entries (6.4 MiB): before 24.2-25.0 ms, after 0.9-1.1 ms of parsing (about 96% less).
  - Item names index, 764 items: before 0.13 ms per command, after under 0.001 ms (cached).
- Not measured: network time. The prefix read stops consuming the body after about 3% of a 6,842-entry history, so the real saving on a cold `/link` or `/profile` for a large account should be larger than the parse saving, but it was not measured against the live API.

## Deviations
- `MetaService` used `Collectors.toMap` over hero names and threw `NullPointerException` (→ generic error reply) if a hero asset had a null name. With `GameAssets` that hero is listed with the "unknown hero" label, as `/counter` and `/profile` already did. Test `heroWithoutNameInAssetsHasNullName` covers it.
- Invalid JSON after the 200th history entry is no longer detected (it is never read).
- The 404 / retry / debug log lines now come from logger `DeadlockApiRequests` instead of the two client classes; the debug line shows the path template, not the match id.

## Known issues and follow-ups
- Worktree `../rejuv-wt/024` was created before this refactor and changes `CounterCommand`; it should rebase on this branch after merge (`handleUnexpectedError` signature, `oneDecimal`, `CounterService.MIN_*` constants, `GameAssets`).
- The commands still call the API synchronously on the JDA event thread (unchanged, see `PROJECT_CONTEXT.md`).
- Cold assets (`/v1/assets/items`, about 8 MB) are still downloaded on the first `/build` or `/counter` after each 24 h expiry; warming the cache at startup would remove that wait but changes startup behavior, so it needs an owner decision.
