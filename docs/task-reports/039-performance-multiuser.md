# Task 039: performance and multi-user readiness

## Status
Implemented on `feature/039-performance-multiuser` (7 commits including this report, on top of `main` 55d8cba). `./mvnw -B verify` passes: 978 tests, 0 failures, 0 errors, 14 skipped (baseline on the same base: 958 tests, 2 skipped). Not pushed, not merged. The spec was given in the prompt (no `docs/tasks/039-*.md` file).

Observable behavior is unchanged: same Discord replies (same message keys and text), same chart PNG bytes, same web pages, same stored data. No existing test assertion was changed.

## Summary
- **Chart rendering** (`/analyze` embed and web report `chart.png`): the PNG is now written to an in-memory `ImageOutputStream` instead of `ImageIO.write(image, "png", OutputStream)`, which buffers through a temp file while the ImageIO disk cache is on (the default; verified `ImageIO.getUseCache() == true`). The image is drawn into `TYPE_3BYTE_BGR` (the layout the PNG writer encodes from) and fonts, strokes and fill colours are static constants. The PNG bytes are identical (SHA-256 prefix `d0a3741011389b84` before and after).
- **`spring.jpa.open-in-view: false`**: a web report request kept its pooled DB connection from the first query until the response was written, including the deadlock-api metadata wait. Now the connection is returned after each repository call.
- **Duplicated command code** moved into existing helpers (`CommandSupport`, `EmbedStyle`).
- **Measurement harnesses** (skipped unless `-Dbenchmark=true`, same pattern as `PerformanceBenchmarkTest`): CPU and memory micro-benchmarks, HTTP calls per command (cold/warm), and the raw metadata fallback decode.

## Optimizations (measured)

| Optimization | Where (file) | Before (measured) | After (measured) | Saving | Risk |
|---|---|---|---|---|---|
| In-memory PNG stream, 3-byte image, reused AWT constants | `services/ChartRenderer.java` | Full chart render, 12 points: median 19.7 / 20.0 / 20.0 ms (3 runs); one temp file written and deleted per chart | 15.1 / 16.0 / 17.0 / 17.2 / 15.4 ms (5 runs); no temp file | median of runs 19.9 → 16.0 ms: about 3.9 ms (about 20%) CPU per chart, range 2.5-4.9 ms; one disk write per chart removed; same bytes | Low: byte-identical output checked by hash |
| PNG encoding alone (part of the row above) | same | 17.5 / 19.1 / 17.6 ms | 15.1 / 17.1 / 15.4 ms | 11-13% | — |
| Open-in-view off | `src/main/resources/application.yaml` | 1 pooled connection held during the metadata call of a report request (`ReportConnectionUsageTest` failed: expected 0, was 1); Spring's open-in-view WARN printed 3 times in the verify log | 0 connections held; 0 WARN lines | A report view no longer occupies one of Hikari's 10 default connections for up to 10 s read timeout + one Retry-After wait (max 10 s) | Low: entities have no lazy associations; the template reads `ReportView` records |
| Shared command helpers | `commands/CommandSupport.java`, `EmbedStyle.java` + 6 commands | 15 duplicated blocks in 6 commands | 4 helpers | 6 commands −130 / +24 lines, helpers +51: net −55 lines in 8 files | Low: all 206 command tests pass unchanged |

Measured and **not** changed (no worthwhile gain):

| Candidate | Where | Measurement | Decision |
|---|---|---|---|
| Single pass + precompiled regex in `HeroResolver.resolve` | `services/HeroResolver.java` | 1,000 resolves (500 exact + 500 prefix): 1.11-1.18 ms before, 1.21-1.38 ms after (the single pass lowercases names the exact pass skipped) | Reverted |
| Precompiled whitespace regex alone | same | 0.30-0.47 µs → 0.18-0.34 µs per call | Not worth a change on its own (under 0.3 µs per command) |
| Soul curve SVG `String.format` calls | `services/SoulCurveSvg.java` | 0.31-0.36 ms per render, 4,888 chars | Left as is |
| Raw metadata fallback (Zstandard + protobuf), 3 MB payload | `clients/RawMatchMetadataSource.java`, `RawMatchMetadataParser.java` | full `getMatch` (mock HTTP read + zstd + parse) 5.9 ms; parse alone 0.45-0.8 ms | Left as is: runs at most once per match per 24 h (metadata cache) and only when the JSON endpoint fails |
| PNG compression level 0.75 instead of the default 0.5 | `ChartRenderer` | encode 9.5 ms instead of 13.8 ms, but 44,337 bytes instead of 36,107 and different bytes | Not done (changes the reply file; listed for the owner) |

## Total effect per command
Upstream HTTP calls measured with `UpstreamCallCountBenchmarkTest`: real `CacheConfig` caches, clients and services, `MockRestServiceServer` answering every path from the fixtures (200-entry history, coach metadata fixture, an upgrade item added so the build coach layer runs as in a real match). "Warm" = the same command again. The command's own asset calls (thumbnail, rank badge) are included. This task does not change any HTTP count (cache settings are out of scope); the counts are the reference for the owner decisions below.

| Command | Cold HTTP calls | Warm HTTP calls | CPU-bound wall time (this machine) |
|---|---|---|---|
| `/analyze` (fresh) | 12 deadlock-api + 1 Anthropic. deadlock-api calls: history, rank, heroes, ranks, metadata, metrics, item-stats, items, performance curve, item-flow, ability-order, hero-counter-stats (6 of them `/v1/analytics/*`) | 0 (stored analysis) | Service + 5 coach layers with warm caches and mocked AI: 1.46-1.52 ms. Chart: 19.7-20.0 → 15.1-17.2 ms. Total about 21.4 → 17.5 ms (about −18%) |
| `/analyze` (stored) | (part of the warm column) | 0 | Chart rendered again on every call: same saving (about 3.9 ms) |
| `/compare` | 5: history, heroes, metrics ×2 (tier 11 + own tier), rank | 0 | Not measured separately (single pass over at most 200 entries) |
| `/items` | 5: heroes, rank, item-stats ×2 (player + meta), items | 0 | Not measured separately |
| `/matches` | 2: history, heroes (+1 DB query) | 0 | Not measured separately |
| `/profile` | 4: history, rank, ranks, heroes | 0 (profile cache) | Not measured separately |
| Web report view (`/r/{id}` + `chart.png` + `soul-curve.svg`) | metadata, rank (only without lobby badge), performance curve, ranks, heroes; all shared with `/analyze` caches | 0 | Chart PNG re-rendered on every view: about 3.9 ms less per view; DB connection no longer held during upstream calls |

Allocations were not measured with a profiler; by reading the code, the chart change removes 7 `BasicStroke`, 3 `Font` and one `Color` per filled lead segment from every render, plus the temp file.

## Duplicated code removed
- `CommandSupport.winFilter(OptionMapping)`: win/loss parsing copied 3 times (`AnalyzeCommand` handle and match autocomplete, `MatchesCommand`).
- `CommandSupport.heroChoices(Collection<String>, String)`: the identical hero autocomplete of `/analyze` and `/matches`.
- `CommandSupport.minutesSeconds(int)`: `m:ss` formatting of `/matches` and the `/analyze` build field (`AnalyzeCommand.formatBuyTime` and `MatchesCommand.formatDuration` removed).
- `EmbedStyle.addFittedField(EmbedBuilder, String, List<String>)`: "fit the lines, add the field only if something fits" repeated 8 times (`/items` ×2, `/counter` ×3, `/compare`, `/meta`, `/analyze` build field).
- Files: `AnalyzeCommand`, `MatchesCommand`, `ItemsCommand`, `CounterCommand`, `CompareCommand`, `MetaCommand` (−130 / +24 lines), `CommandSupport`, `EmbedStyle` (+51 lines incl. Javadoc). New tests: `CommandSupportTest` (5), 2 cases in `EmbedStyleTest`.
- Remaining duplication (not changed): one-decimal rounding exists in `MetricsService`, `ItemsService`, `ReportService` and two coach providers; the providers are a reserved area, and the two non-reserved copies alone are not worth a helper.

## Multi-user readiness

### Fixed
- Open-in-view off (above): report views no longer pin DB connections while waiting on deadlock-api.

### Verified facts
- **All commands run on one thread.** `JdaConfig` sets no event pool. In JDA 5.6.1, `EventManagerProxy.handle` runs the listener on the calling thread when no executor is set (checked in the bytecode with `javap`); per JDA's documented default, that calling thread is the gateway read thread (not checked in the bytecode). So every slash command and autocomplete of every guild is handled one at a time, including the upstream and AI work that runs after `deferReply()`.
- **Blocking work before `deferReply`** (counts against Discord's 3 s acknowledgement window): `HeroResolver` in `/analyze` and `/matches` (heroes asset, an HTTP call when that cache is cold); `PremiumFeatureGate` in `/items` and `/compare` (Discord member lookup with a 5 s timeout when limits and the Patreon check are on and the 2 min premium cache is cold); `/usage` never defers and calls the same premium check; every command's `findLinkedAccountId` is a DB call (Hikari `connectionTimeout` default 30 s if the pool were exhausted).
- **Shared state is thread-safe:** the only mutable fields in singletons are `GameAssets` (volatile immutable indexes, benign rebuild race), `IconCatalog` (volatile immutable map) and Caffeine caches. No `synchronized`, `ThreadLocal`, parallel streams or executors besides the emoji-sync thread. All 20 `@Cacheable` methods use `sync = true`, so identical in-flight loads coalesce across threads (web threads already run concurrently with the JDA thread).
- Pools: Hikari default (10 connections, not configured); Tomcat default platform threads (`spring.threads.virtual.enabled` is not set, although ADR-0001 lists virtual threads); deadlock-api and Anthropic use one JDK `HttpClient` each (connection reuse; connect 3 s, read 10 s / 30 s).
- Memory per cached value (heap delta over many copies after GC; synthetic data, so approximate): 200-entry match history 9-18 KiB, match telemetry (12 players, 13 snapshots, 30 items each) about 41 KiB, 1,800-row hero counter list about 77 KiB. At the current maximum sizes: history 1,000 × 18 KiB ≈ 18 MB, metadata 300 × 41 KiB ≈ 12 MB, counters 50 × 77 KiB ≈ 4 MB, well below the about 700 MB heap (`MaxRAMPercentage=70`, `mem_limit: 1g`).
- Database: measured in a throwaway `postgres:17` container with the V1-V4 schema. `/leaderboard` candidates query (`ORDER BY linked_at DESC LIMIT 500`, no index): 1.1-1.3 ms at 10,000 linked accounts, 11.8 ms at 100,000; with an index on `linked_at` 0.10-0.20 ms. `/unlink` (`NOT EXISTS` on `linked_account.account_id`, no index, link already deleted in the transaction): 0.5 ms at 10,000, 5 ms at 100,000; with an index under 0.1 ms. Other queries use existing unique indexes. **No migration added**: at any realistic size for this bot these costs are small next to one deadlock-api round trip.

### Capacity reasoning (assumptions marked)
- **Today: effectively one command in flight.** A `/analyze` keeps the only event thread busy for its whole duration: cold, 12 sequential deadlock-api calls plus one Haiku call. *Assumption:* 150-600 ms per deadlock-api call and 3-10 s for Haiku give about 5-17 s per cold analysis. Any interaction that arrives during that time is not even deferred until it ends, so it misses Discord's 3 s window and fails ("The application did not respond"). With two users sending commands at once, the second one very likely fails. Bot-wide `/analyze` throughput is about 3-12 per minute (assumption above).
- **deadlock-api limit** (200 requests/min per IP): a fully cold `/analyze` uses 12 requests → about 16/min. With hero/rank data warm (6 h caches), only history, rank and metadata are per user → about 66/min (100/min if the rank call is skipped, see owner decisions). Today the single thread is the bottleneck long before this limit.
- **After the concurrency change** (owner decision 1, not done): the limit becomes the upstream rate limit and Haiku latency, not the event thread. With a deadlock-api concurrency gate of 1-2 requests, about 66 warm-hero analyses per minute is the upper bound.

## Candidates in reserved areas (not changed)
Each is under about 0.2 ms per fresh `/analyze`, whose service + coach part is 1.5 ms in total, so all are low priority.
- `BuildCoachProvider.provide` builds a `HashMap` over all ~764 item assets on every fresh `/analyze` (`GameAssets.itemsById()` already holds it); a map of that size took 0.13-0.15 ms in `PerformanceBenchmarkTest`.
- `MatchupCoachProvider.buildHeroNamesMap`, `BuildCoachProvider.resolveHeroName`, `DeathsCoachProvider.resolveHeroName`: rebuild or scan the hero list instead of `GameAssets.heroNames()` (about 40 heroes, microseconds).
- `FormCoachProvider`: about 6 stream passes over at most 200 history entries.
- One-decimal rounding duplicated in `BuildCoachProvider` and `PhasesCoachProvider`.
- `MetricsService.items` (builds the AI input; not on the reserved list, left alone so the AI-input path is untouched while the AI-quality work runs) rebuilds an upgrade map over all items per analysis.

## Cache findings for the cache audit (not changed)
- The rendered chart PNG is not cached: every web report view and every stored `/analyze` re-renders it (about 15-17 ms). Caching the bytes per analysis would remove that.
- The items asset (about 8 MB) expires after 24 h; the first command after expiry downloads it synchronously (startup emoji sync warms it only once). A refresh-ahead policy would hide that wait.
- A fresh `/analyze` reads the match history twice (selection and coach request); both hit the 2 min cache except when the entry expires between the two reads.

## Needs owner decision
1. **Concurrent event handling (most important for multi-user).** Options:
   - A. JDA event pool on virtual threads (`JDABuilder.setEventPool(Executors.newVirtualThreadPerTaskExecutor(), true)`) plus a global concurrency gate for deadlock-api requests in `DeadlockApiRequests` (one place for all adapters, for example a fair `Semaphore` with 1-2 permits) so the "never fire parallel uncached analytics requests" rule still holds across users. Pros: no head-of-line blocking, every interaction is deferred in time, AI calls overlap. Cons: thread-model change; per-user event order is no longer guaranteed (`/link` then `/analyze` sent within milliseconds); more simultaneous DB use (10 connections are still plenty for short queries).
   - B. Bounded platform executor (for example 4 threads, bounded queue, "busy" reply when full). Pros: fixed resource ceiling. Cons: queueing again under bursts; one more tuning knob.
   - C. Keep validation on the gateway thread and hand only the work after `deferReply()` to an executor. Pros: validation stays ordered. Cons: two execution paths per command.
   - Recommendation: **A**, with the gate in `DeadlockApiRequests` and a load test (N parallel `/analyze` against `MockRestServiceServer` with delays) in the same task. The thread-safety audit above found nothing that blocks it.
2. **Skip `getPlayerRank` in `AnalyzeService` when the match has a ranked badge.** `MetricsService.benchmarkRank` only reads the current rank when the match badge is missing, so the call is wasted for ranked matches (80% of one account's recent 200 matches, `PROJECT_CONTEXT.md`). Saves 1 of 12 cold calls per `/analyze` (about 8%). It changes the Task 009 call order and the failure behavior (a `/rank` outage would no longer fail a ranked `/analyze`), and two `AnalyzeServiceTest` assertions (`freshAiPathStoresRowAndReturnsAiResultWithSequentialCalls` order check, `getPlayerRankThrowingUnavailablePropagatesAndSavesNothing`). Recommendation: do it, with a lazy overload `benchmarkRank(match, Supplier<PlayerRank>)`.
3. **Blocking work before `deferReply`.** Deferring earlier would make today's ephemeral refusals ("not linked", "premium required") public or make the success reply ephemeral, which are visible changes. Recommendation: after (1) this only affects the caller's own 3 s window; also lower the premium member lookup timeout from 5 s to under 3 s, or defer `/usage` ephemerally (its reply is already ephemeral, so `deferReply(true)` would not change what the user sees).
4. **`spring.threads.virtual.enabled: true`** for Tomcat (web report), as ADR-0001 intends. Low risk; no measured need today.
5. **Indexes** `linked_account(linked_at)` and `linked_account(account_id)` (one V5 migration): numbers above; worthwhile only past roughly 50,000 linked accounts.
6. **PNG compression level** 0.75: 30% faster encoding but 23% larger attachment and different bytes; not recommended while uploads are small.

## Important files
- Modified: `services/ChartRenderer.java`, `src/main/resources/application.yaml`, `commands/{CommandSupport,EmbedStyle,AnalyzeCommand,MatchesCommand,ItemsCommand,CounterCommand,CompareCommand,MetaCommand}.java`, `test/.../commands/EmbedStyleTest.java` (2 tests added).
- New tests: `test/.../web/ReportConnectionUsageTest.java` (runs in `verify`), `test/.../commands/CommandSupportTest.java`, benchmarks `test/.../CpuHotPathBenchmarkTest.java` (6), `test/.../UpstreamCallCountBenchmarkTest.java` (5), `test/.../clients/RawMetadataBenchmarkTest.java` (1), all skipped unless `-Dbenchmark=true`.

## Verification (actually run, Windows, Git Bash `./mvnw`)
- Baseline before any change: `./mvnw -B verify` → BUILD SUCCESS, 958 tests, 0 failures, 0 errors, 2 skipped (35.9 s).
- Final: `./mvnw -B verify` → BUILD SUCCESS, 978 tests, 0 failures, 0 errors, 14 skipped (37.5 s). +20 tests: 8 that run (`CommandSupportTest` 5, `EmbedStyleTest` +2, `ReportConnectionUsageTest` 1) and 12 benchmarks that are skipped by default.
- `./mvnw -B test -Dtest="*Benchmark*" -Dbenchmark=true` → 14 tests, 0 failures; numbers in the tables above (medians after warm-up; the chart before/after numbers come from three alternating runs against the original and the new `ChartRenderer`).
- `ReportConnectionUsageTest` against the original `application.yaml` → failed with `expected: 0 but was: 1`; after the change → passes.
- `EXPLAIN (ANALYZE)` in a throwaway `postgres:17` container with the V1-V4 schema at 10,000 and 100,000 linked accounts (script kept outside the repo).

## Deviations
- None from the prompt spec. No cache, prompt, `AnalysisClient`, `AnalysisInput`/`AnalysisResult` or coach provider file was touched; no dependency, ADR or migration added.

## Known limitations
- All timings come from the Windows development machine, not the Oracle ARM server; absolute numbers will differ, the relative changes should hold.
- Network latency of deadlock-api and Anthropic was not measured; capacity figures that use it are marked as assumptions.
- Memory figures come from heap deltas after `System.gc()` on synthetic data and vary between runs (history 9-18 KiB).
- Call counts use fixture data; branches that depend on data (for example the lane part of the matchup layer) may differ in real matches.

## Pending integration into PROJECT_CONTEXT
(Parallel worktrees are active, so `docs/PROJECT_CONTEXT.md` was not edited.)
- 039 done: chart PNG encoded in memory (about 20% less CPU per chart, same bytes); `spring.jpa.open-in-view: false` (report views no longer hold DB connections during upstream calls); shared command helpers `CommandSupport.winFilter/heroChoices/minutesSeconds` and `EmbedStyle.addFittedField`.
- Known limitations: replace "`spring.jpa.open-in-view` is on" with "off since 039". Keep "commands run on the JDA gateway thread", now verified in JDA 5.6.1 (no event pool configured), and add: a second interaction arriving during a running `/analyze` misses the 3 s acknowledgement window; fix pending owner decision (report 039, "Needs owner decision" 1).
- Reference numbers: cold `/analyze` = 12 deadlock-api calls (6 analytics) + 1 Anthropic; `/compare` 5, `/items` 5, `/profile` 4, `/matches` 2; all warm = 0.
- Benchmarks: `CpuHotPathBenchmarkTest`, `UpstreamCallCountBenchmarkTest`, `RawMetadataBenchmarkTest` (run with `-Dbenchmark=true`); `verify` now reports 14 skipped tests.
