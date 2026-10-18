# Task 040: Cache Freshness, Degraded AI Analysis Handling, and Leaner AI Input

## Status
Implemented on `feature/040-cache-freshness-and-ai-input`.
- Baseline tests: 978 passed, 0 failures, 14 skipped.
- Final verification (`.\mvnw.cmd verify`): 982 passed, 0 failures, 14 skipped (+4 tests).
- Not pushed, not merged.

## Summary
Completed all parts (A through F) specified in `docs/tasks/040-cache-freshness-and-ai-input.md`:
1. **Part A (Schema)**: Extended Anthropic `RESULT_SCHEMA` in `MessagesRequest.java` to define and require `"focus_area"` (enum: `phases`, `deaths`, `build`, `recent_form`, `matchup`, `metrics`) and `"severity"` (enum: `minor`, `notable`, `major`).
2. **Part B (Degraded Analysis Handling)**: In `AnalyzeService.java`, when telemetry is unavailable (`degraded == true`) and AI returned a result, the analysis is returned to the user with `publicId == null` (no "View full report" button) without calling `repository.save` or `usageService.recordAnalysis`. A single INFO log is emitted with `source=ai degraded=true stored=false`. Subsequent requests can retry analysis when telemetry becomes available.
3. **Part C (Metadata Cache Splitting)**: Removed `@Cacheable` from `FallbackMatchMetadataSource`. Added 24-hour `@Cacheable(CacheConfig.DEADLOCK_MATCH_METADATA, sync = true)` to `JsonMatchMetadataSource`. Added 2-minute `@Cacheable(CacheConfig.DEADLOCK_MATCH_METADATA_RAW, sync = true)` to `RawMatchMetadataSource`. Failed calls and 404s are not cached.
4. **Part D (Cache Lifetimes & Sizes)**: Updated `CacheConfig.java` to configure exactly 19 Caffeine caches, tuned TTLs and maximum sizes according to spec, and added `.recordStats()`. Enabled CGLIB class proxies via `@EnableCaching(proxyTargetClass = true)` to support Spring caching on concrete beans.
5. **Part E (Cache Eviction on Link/Unlink)**: Injected `CacheManager` into `LinkService.java`. On successful `link` and `unlink`, evicts `PROFILE_SUMMARY`, `DEADLOCK_MATCH_HISTORY`, and `DEADLOCK_PLAYER_RANK` for the target account. On `link`, also evicts for the previously linked account if one existed and differed.
6. **Part F (Leaner AI Input)**: Removed `p75` from `AnalysisInput.MetricComparison` record and updated `MetricsService.compare()`. Updated `CoachContextService.java` to filter out insights with `significance: "none"` using a static `JsonMapper`. Adjusted `prompts/analysis-system.txt` wording to reference `p25/p50` and clarify that `none` coach entries are omitted.

## Cache Lifetimes and Sizes (Before / After)

| Cache Name | Previous TTL | Previous Max Size | New TTL | New Max Size |
|---|---|---|---|---|
| `deadlockAssets` | 24 h | 10 | 2 h | 10 |
| `deadlockBenchmarks` | 6 h | 500 | 3 h | 500 |
| `deadlockItemStats` | 6 h | 500 | 3 h | 500 |
| `deadlockMatchHistory` | 2 min | 1000 | 45 s | 1000 |
| `deadlockPlayerRank` | 10 min | 1000 | 5 min | 1000 |
| `deadlockPlayerRanks` | 10 min | 100 | 5 min | 200 |
| `deadlockPlayerNames` | 1 h | 500 | 1 h | 1000 |
| `deadlockMatchMetadata` | 24 h | 300 | 24 h | 1000 |
| `deadlockMatchMetadataRaw` *(new)* | — | — | 2 min | 100 |
| `profileSummary` | 10 min | 1000 | 5 min | 1000 |
| `deadlockHeroStats` | 6 h | 50 | 3 h | 50 |
| `deadlockItemFlow` | 6 h | 200 | 3 h | 200 |
| `deadlockAbilityOrder` | 6 h | 200 | 3 h | 200 |
| `deadlockHeroCounters` | 6 h | 50 | 3 h | 50 |
| `deadlockCounterItems` | 6 h | 200 | 3 h | 200 |
| `deadlockPlayerHeroStats` | 10 min | 200 | 5 min | 500 |
| `deadlockMateStats` | 10 min | 200 | 5 min | 500 |
| `deadlockPlayerItemStats` | 10 min | 200 | 5 min | 500 |
| `deadlockPerformanceCurve` | 6 h | 500 | 3 h | 500 |

*Note*: The Patreon role cache (`patreonRoleCheck`, 2 min) remains untouched. Total registered application caches: 19.

## AI Input Token Difference Analysis

Local samples in `docs/evidence/ai-exploration/05-raw-samples/` were not present in the workspace. Character and token estimations were computed based on reconstructed payloads:
1. **Omission of `p75` in `AnalysisInput.MetricComparison`**:
   - `PlayerMetrics` contains 10 benchmark comparisons (`souls_per_min`, `kills`, `deaths`, `assists`, `deaths_per_10_min`, `last_hits`, `denies`, `player_damage_per_min`, `net_worth_at_10_min`, `net_worth_at_20_min`).
   - Removing `,"p75":<float>` removes approximately 14–17 characters per metric (~150 characters total across the 10 metrics).
   - Character savings: ~150 chars (~35–40 prompt tokens).
2. **Omission of Coach Entries with `significance: "none"`**:
   - In matches where 2 to 4 coach modules detect nothing notable, each module previously output an entry such as `{"summary":"...","significance":"none",...}` (~120–250 characters each).
   - Character savings: ~250–800+ chars (~60–200 prompt tokens).
3. **System Prompt Tuning (`analysis-system.txt`)**:
   - Updated from 3,962 characters to 3,943 characters (saving 19 characters, under the 4,000 character limit while preserving all required keyword assertions).
4. **Total Reduction**:
   - Average request payload reduction: ~400–1,000 characters (~100–250 input tokens per `/analyze` call), speeding up generation latency and lowering API costs without loss of actionable advice.

## Important Files Changed

### Production Code
- `src/main/java/sntiago05/rejuv/clients/dto/MessagesRequest.java`: Extended `RESULT_SCHEMA` with required `focus_area` and `severity` enum properties.
- `src/main/java/sntiago05/rejuv/services/AnalyzeService.java`: Skipped persistence and usage quota consumption for degraded AI analyses (`publicId == null`).
- `src/main/java/sntiago05/rejuv/clients/FallbackMatchMetadataSource.java`: Removed `@Cacheable`.
- `src/main/java/sntiago05/rejuv/clients/JsonMatchMetadataSource.java`: Added `@Cacheable(DEADLOCK_MATCH_METADATA)` (24 h).
- `src/main/java/sntiago05/rejuv/clients/RawMatchMetadataSource.java`: Added `@Cacheable(DEADLOCK_MATCH_METADATA_RAW)` (2 min).
- `src/main/java/sntiago05/rejuv/config/CacheConfig.java`: Registered new raw cache constant, updated TTLs and sizes for 19 caches, added `.recordStats()`, and enabled `proxyTargetClass = true`.
- `src/main/java/sntiago05/rejuv/services/LinkService.java`: Injected `CacheManager` and added eviction of profile summary, match history, and player rank on link, unlink, and re-link.
- `src/main/java/sntiago05/rejuv/domain/AnalysisInput.java`: Removed `p75` component from `MetricComparison`.
- `src/main/java/sntiago05/rejuv/services/MetricsService.java`: Stopped populating `p75` in `compare()`.
- `src/main/java/sntiago05/rejuv/services/CoachContextService.java`: Filtered out insights with `significance: "none"` using static `JsonMapper`.
- `src/main/resources/prompts/analysis-system.txt`: Updated system prompt to `p25/p50` and clarified omitted `none` coach entries.

### Test Code
- `src/test/java/sntiago05/rejuv/clients/DeadlockApiClientCachingTest.java`: Verified all 19 cache names, TTLs, and sizes.
- `src/test/java/sntiago05/rejuv/clients/FallbackMatchMetadataSourceCachingTest.java`: Verified caching split between JSON (24h) and Raw (2m), retry behavior, and non-caching of errors/404s.
- `src/test/java/sntiago05/rejuv/clients/AnalysisClientTest.java`: Tested request schema contains `focus_area` and `severity` enums and required fields.
- `src/test/java/sntiago05/rejuv/clients/AnalysisPromptTest.java`: Verified prompt length (<4,000 chars), absence of `p75`, and presence of key phrases.
- `src/test/java/sntiago05/rejuv/services/AnalyzeServiceTest.java`: Added assertions that degraded AI runs do not persist, do not record usage, set `publicId == null`, and retry AI on repeat calls.
- `src/test/java/sntiago05/rejuv/services/LinkServiceTest.java`: Tested cache eviction on link, unlink, and re-link across `PROFILE_SUMMARY`, `DEADLOCK_MATCH_HISTORY`, and `DEADLOCK_PLAYER_RANK`.
- `src/test/java/sntiago05/rejuv/services/CoachContextServiceTest.java`: Tested dropping `significance: "none"` entries while preserving `notable`, `major`, and untagged payloads.
- `src/test/java/sntiago05/rejuv/services/MetricsServiceTest.java`: Updated comparison assertions for 3-argument `MetricComparison`.
- `src/test/java/sntiago05/rejuv/commands/AnalyzeCommandTest.java`: Updated constructor references.
- `src/test/java/sntiago05/rejuv/domain/AnalysisInputJsonTest.java`: Verified JSON serialization without `p75`.
- `src/test/java/sntiago05/rejuv/services/ProfileServiceCachingTest.java`: Updated TTL assertion from 10m to 5m to align with the new `CacheConfig.PROFILE_SUMMARY` TTL (confirmed with user).

## Live Check Status
- **Live Discord/Bot Check**: **Not done** in this environment. Running the live `/analyze` command against the Anthropic API requires live credentials (`DISCORD_TOKEN`, `ANTHROPIC_API_KEY`) and active Steam/Deadlock match data, which are not configured in this test worktree.
- Recommendation: When deploying to staging/dev guild, run `/analyze` on a newly played ranked match and verify in the embed that the biggest leak begins with a severity label (`[Major]`, `[Notable]`, or `[Minor]`) and that no `invalid_output` error is logged by `AnalysisClient`.

## Known Limitations & Out of Scope
- Analyses built from raw fallback telemetry (reduced detail when JSON fails but `telemetry != null`) continue to be stored and charged to usage quota as specified.
- Prompt versioning and database migrations for existing stored analyses remain out of scope (`AnalysisResult` tolerates null `focus_area`/`severity` for legacy rows).
- Cache stats are recorded via `.recordStats()` but no metrics endpoint or periodic log was added in this task.

## Facts to Add to `docs/PROJECT_CONTEXT.md` (Pending Integration)
Do not edit `docs/PROJECT_CONTEXT.md` while other worktrees are active. Upon merging `feature/040-cache-freshness-and-ai-input`, incorporate the following:
- Registered 19 Caffeine caches in `CacheConfig`: added `deadlockMatchMetadataRaw` (2 min TTL, max 100).
- Cache lifetimes updated: `deadlockMatchHistory` (45 s), `deadlockPlayerRank` / `deadlockPlayerRanks` / `profileSummary` / player hero/mate/item stats (5 min), `deadlockAssets` (2 h), `deadlockBenchmarks` / static hero/item/curve stats (3 h), `deadlockMatchMetadata` (24 h). All caches record stats.
- `@EnableCaching(proxyTargetClass = true)` is active for CGLIB proxying of concrete beans.
- Metadata caching split: `JsonMatchMetadataSource` is cached for 24 hours under `deadlockMatchMetadata`; `RawMatchMetadataSource` is cached for 2 minutes under `deadlockMatchMetadataRaw`; `FallbackMatchMetadataSource` delegates without its own cache.
- Degraded analyses (`telemetry == null`) that succeed via AI are returned with `publicId == null` and are neither saved to `MatchAnalysisRepository` nor charged to `UsageService`.
- Anthropic Claude tool schema for `AnalysisResult` now requires `focus_area` and `severity`.
- Coach insights with `significance: "none"` are filtered out in `CoachContextService` before sending to the model; `AnalysisInput.MetricComparison` omits `p75`.
- `LinkService` evicts `profileSummary`, `deadlockMatchHistory`, and `deadlockPlayerRank` on account link/unlink.
