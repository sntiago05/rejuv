# Task 040: Cache freshness, no stored degraded analyses, schema fix and leaner AI input

- **Branch**: `feature/040-cache-freshness-and-ai-input`
- **Implementer**: Antigravity
- **Related ADRs**: none
- **Depends on**: 037 merged (done). **Merge after task 039 (performance, branch `feature/039-performance-multiuser`)**: 039 was forbidden to touch `CacheConfig`, `AnalysisClient`, the prompt and the coach providers, so the two should not conflict; update with `main` before merging and re-run `verify`.
- **Evidence** (local only, gitignored, contains real account data): `docs/evidence/cache-audit/` (03-recommendations.md, 02-staleness.md) and `docs/evidence/ai-exploration/` (00-summary.md, 03-output-quality.md). Read them for the reasons; this spec is the contract.

## Goal
Fix the verified defects that show stale or degraded data to users and the one that hides the severity badges, and cut tokens that carry no information, without changing what `/analyze` is for. Six parts: A schema fix, B no stored degraded analyses, C metadata cache split, D cache lifetimes, E cache eviction on link/unlink, F leaner AI input.

## Why (verified)
- **A.** `MessagesRequest.RESULT_SCHEMA` lists only the four old fields with `additionalProperties:false`, so structured outputs forbid the model from writing `focus_area` and `severity`; analyses 6-8 of the audit used prompt v2 and still came back without them (task 037 did not list this file).
- **B.** `AnalyzeService` saves the degraded analysis (metadata unavailable) to `match_analysis` and records usage; later requests reuse the stored row forever (`findByAccountIdAndMatchId`).
- **C.** `FallbackMatchMetadataSource.getMatch` is `@Cacheable(DEADLOCK_MATCH_METADATA)` at the composite level, so when JSON fails and the raw protobuf source answers, that reduced telemetry (no lanes, no snapshot detail, no deaths, no average badge) is cached 24 h.
- **D.** The API sets `Cache-Control` of 10 min for analytics, 5 min for rank and history, 1 h for assets (measured); Rejuv caches 6 h, 10 min and 24 h. Match history cached 2 min hides a match finished seconds ago.
- **E.** `LinkService.link/unlink` write the database but evict nothing.
- **F.** `p75` is never referenced by the prompt (58 tokens per analysis); coach entries with `significance` `none` are sent in full but the prompt forbids using them for the leak (149-759 tokens).

## Files to create or modify
| File | Change |
|---|---|
| `src/main/java/sntiago05/rejuv/clients/dto/MessagesRequest.java` | A: extend `RESULT_SCHEMA`. |
| `src/main/java/sntiago05/rejuv/services/AnalyzeService.java` | B: skip save and usage for degraded results. |
| `src/main/java/sntiago05/rejuv/clients/FallbackMatchMetadataSource.java` | C: remove `@Cacheable`. |
| `src/main/java/sntiago05/rejuv/clients/JsonMatchMetadataSource.java` | C: add `@Cacheable` (24 h cache). |
| `src/main/java/sntiago05/rejuv/clients/RawMatchMetadataSource.java` | C: add `@Cacheable` on the new short cache. |
| `src/main/java/sntiago05/rejuv/config/CacheConfig.java` | C, D: new cache constant, new TTLs and sizes, `recordStats()`. |
| `src/main/java/sntiago05/rejuv/services/LinkService.java` | E: evict caches. |
| `src/main/java/sntiago05/rejuv/services/CoachContextService.java` | F: drop `none` entries. |
| `src/main/java/sntiago05/rejuv/domain/AnalysisInput.java` | F: remove `p75` from `MetricComparison`. |
| `src/main/java/sntiago05/rejuv/services/MetricsService.java` | F: stop filling `p75`. |
| `src/main/java/sntiago05/rejuv/commands/AnalyzeCommand.java`, `domain/AnalysisReport.java` | F: only if they construct `MetricComparison` with four arguments (compile fix, no behavior change). |
| `src/main/resources/prompts/analysis-system.txt` | F: wording (below). |
| `src/test/java/sntiago05/rejuv/clients/DeadlockApiClientCachingTest.java` | D: exact cache names, TTLs and sizes. |
| `src/test/java/sntiago05/rejuv/clients/FallbackMatchMetadataSourceCachingTest.java`, `FallbackMatchMetadataSourceTest.java` | C: update to the new caching location. |
| `src/test/java/sntiago05/rejuv/clients/AnalysisClientTest.java`, `AnalysisPromptTest.java` | A, F. |
| `src/test/java/sntiago05/rejuv/services/AnalyzeServiceTest.java`, `LinkServiceTest.java`, `CoachContextServiceTest.java`, `MetricsServiceTest.java` | B, E, F. |
| `src/test/java/sntiago05/rejuv/commands/AnalyzeCommandTest.java`, `domain/AnalysisInputJsonTest.java` | F: compile fixes for the record change. |
| `docs/task-reports/040-cache-freshness-and-ai-input.md` | New: task report. |

No other file. No Flyway migration, no new dependency, no change to `effort`, `max-tokens`, prompt caching, the provider classes, or `AnalysisResult`.

## Interfaces / signatures

### A. Schema (`MessagesRequest.RESULT_SCHEMA`)
Add to `properties`: `"focus_area"` = `{"type":"string","enum":["phases","deaths","build","recent_form","matchup","metrics"]}` and `"severity"` = `{"type":"string","enum":["minor","notable","major"]}`. Add both to `required` (the model must always choose them; `AnalysisResult` still tolerates `null` for old rows). Keep `additionalProperties:false`. Derive the enum values from `AnalysisResult.FOCUS_AREAS`/`SEVERITIES` if that keeps the ordering deterministic (sort them), otherwise list them literally.
If the Anthropic API rejects `enum` inside this schema, the existing fallback path would silently hide it: state in the report that the live check below was done or was not.

### B. `AnalyzeService.analyze`
When `degraded` is true (telemetry unavailable) and the model returned a result (`!outcome.isFallback()`): do **not** call `repository.save` and do **not** call `usageService.recordAnalysis`; return `AnalysisReport(..., outcome.result(), Map.of(), true, visuals(...), null)` (public id `null`, so no "View full report" button, same as the existing fallback path). Log one INFO line with `source=ai degraded=true stored=false`. The non-degraded path is unchanged. Rationale: the user retries later and gets the full analysis; the weekly allowance is not consumed by a degraded result.
Out of scope but to be reported as a known limitation: analyses built from the raw fallback telemetry (reduced detail but `telemetry != null`) are still stored.

### C. Metadata caches
- `JsonMatchMetadataSource.getMatch`: `@Cacheable(cacheNames = CacheConfig.DEADLOCK_MATCH_METADATA, sync = true)` (24 h, finished matches are immutable upstream).
- `RawMatchMetadataSource.getMatch`: `@Cacheable(cacheNames = CacheConfig.DEADLOCK_MATCH_METADATA_RAW, sync = true)` with new constant `DEADLOCK_MATCH_METADATA_RAW = "deadlockMatchMetadataRaw"`, **2 min, max 100**, so a Steam outage does not cause a protobuf decode per request, but JSON is retried soon after.
- `FallbackMatchMetadataSource.getMatch`: no cache annotation; behavior (404 never falls back, suppressed exceptions) unchanged. Remove the unused import.
- Failures are never cached (Spring does not cache exceptions); keep it.

### D. `CacheConfig` lifetimes and sizes (exact; update the constant comments)
| Cache | TTL | Max size |
|---|---|---|
| `deadlockAssets` | 2 h | 10 |
| `deadlockBenchmarks`, `deadlockItemStats`, `deadlockHeroStats`, `deadlockItemFlow`, `deadlockAbilityOrder`, `deadlockHeroCounters`, `deadlockCounterItems`, `deadlockPerformanceCurve` | 3 h | unchanged (500, 500, 50, 200, 200, 50, 200, 500) |
| `deadlockMatchHistory` | 45 s | 1000 |
| `deadlockPlayerRank`, `profileSummary` | 5 min | 1000 |
| `deadlockPlayerRanks` | 5 min | 200 |
| `deadlockPlayerHeroStats`, `deadlockMateStats`, `deadlockPlayerItemStats` | 5 min | 500 |
| `deadlockPlayerNames` | 1 h | 1000 |
| `deadlockMatchMetadata` | 24 h | 1000 |
| `deadlockMatchMetadataRaw` (new) | 2 min | 100 |
Add `.recordStats()` to the Caffeine builder (no endpoint or log in this task). The Patreon role cache (2 min) is untouched.

### E. `LinkService`
Inject `CacheManager`. After a successful `link` and after `unlink` returns true, evict `CacheConfig.PROFILE_SUMMARY`, `CacheConfig.DEADLOCK_MATCH_HISTORY` and `CacheConfig.DEADLOCK_PLAYER_RANK` for the account id (the cache key is the `long` argument boxed). Null-safe if a cache is missing. On `link`, also evict for the previously linked account of that Discord user if one existed and differs.

### F. Leaner AI input
- `AnalysisInput.MetricComparison(double value, Double p25, Double p50)`; update `MetricsService.compare` and every construction site. `MetricPercentiles.p75` (API DTO) stays.
- `CoachContextService.build`: skip an insight whose payload serializes with `significance` equal to `none`. Determine it by converting the payload with a shared `JsonMapper` (`convertValue` to a tree) and reading `significance`; a payload without that field is kept. Log nothing per skipped entry.
- Prompt wording only (keep it under 4,000 characters and keep every keyword `AnalysisPromptTest` checks): "p25/p50/p75" becomes "p25/p50"; replace "Any key may be absent (absent means no data, never infer it)" with: coach entries whose significance is none are omitted; an absent key means no data or nothing notable, never infer it; if the coach object is empty or missing, use the metrics fallback rule for `biggest_leak` with `focus_area` `metrics` and `severity` `minor`. Section 3's remaining rules stay.

## Behavior (observable)
- `/analyze` on a fresh match: embed shows the severity label in the leak field (needs a real run, see Verification).
- `/analyze` right after a match finished: the new match appears within 45 s of the last history fetch, not 2 min.
- Metadata outage: the first request after a JSON failure that used the raw source does not freeze the match as reduced for 24 h; a second request after 2 min retries JSON.
- Degraded analysis: shown to the user, not stored, no usage consumed, no report button; the next `/analyze` of the same match runs a new analysis.
- `/link` then `/profile`/`/matches`: shows the new account immediately.

## Out of scope
- `effort`, `max-tokens`, prompt caching, thinking budget, further token pruning (`p25`, unreferenced coach fields): wait for the quality test.
- Prompt versioning of stored analyses and any Flyway migration; re-analysis of old matches.
- Storing flags for raw-fallback telemetry.
- Rank-tier staleness at analysis time, refresh-ahead of assets, actuator endpoints, thread-model or performance changes (task 039).
- Provider payload shapes and thresholds.

## Tests
- `DeadlockApiClientCachingTest`: exact set of names (now 19), TTLs and sizes from the table; the history entry expires after 45 s, not 2 min (use the existing technique for TTL assertions).
- Metadata caching tests: JSON success cached (one HTTP call for two reads); JSON failure + raw success is **not** in `deadlockMatchMetadata` and a second call retries JSON; raw result cached in `deadlockMatchMetadataRaw` (second call within TTL does not re-fetch raw); 404 still never falls back and nothing is cached.
- `AnalyzeServiceTest`: degraded + AI result -> no `repository.save`, no `recordAnalysis`, report has `publicId == null` and non-null analysis; non-degraded path unchanged (saved and recorded); a second call for the same match after a degraded run calls the AI again.
- `LinkServiceTest`: link and unlink evict the three caches for the account; unlink of a non-linked user evicts nothing; re-link to a different account evicts the old one as well.
- `CoachContextServiceTest`: an insight with `significance` `none` is skipped; `notable`/`major` and payloads without the field are kept; ordering and failure isolation unchanged.
- `MetricsServiceTest`, `AnalysisInputJsonTest`: no `p75` in the JSON; `p25`/`p50` values unchanged.
- `AnalysisClientTest`: request body schema contains `focus_area` and `severity` with their enums and both are in `required`; a response with both parses into the result.
- `AnalysisPromptTest`: still under 4,000 characters; does not contain `p75`; contains the omitted-key sentence's key words (`omitted`, `nothing notable`).

## Verification
`.\mvnw.cmd verify` (Docker running); report baseline and final test counts. Live check, to be stated in the report as done or not done: restart the bot from this branch and run `/analyze` on a ranked match you never analyzed; confirm the leak field starts with a severity label and the log has no `invalid_output`. If `invalid_output` appears right after the schema change, stop and report the response (the `enum` constraint may be unsupported).

## Documentation
Task report `docs/task-reports/040-cache-freshness-and-ai-input.md` with before/after of every lifetime and the measured token difference of one reconstructed input (use `docs/evidence/ai-exploration/05-raw-samples/` if available locally; otherwise count characters of the removed fields). Do not edit `docs/PROJECT_CONTEXT.md` while other worktrees are active; list the facts to add: new cache `deadlockMatchMetadataRaw`, 19 caches, lifetimes table, degraded analyses are not stored, schema contains `focus_area`/`severity`, coach `none` entries omitted.

## Acceptance criteria
- [ ] Compiles, all tests pass (`.\mvnw.cmd verify`)
- [ ] Unit tests for each part A-F as listed
- [ ] No user-facing text added or changed
- [ ] Only the files listed above changed; follows AGENTS.md conventions
- [ ] Report states whether the live `/analyze` check with the new schema was run
