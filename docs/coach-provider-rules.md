# Coach provider rules (tasks 031-035)

Shared contract for every coaching layer that plugs into `/analyze` through `CoachInsightProvider` (introduced by task 029). Each task spec says which of these rules it overrides; if a spec contradicts this file, the spec wins for that task only and must say so.

## Why this exists
The audit of real analyses (`docs/PROJECT_CONTEXT.md`, "Known limitations") showed the model picks "leaks" by comparing one value with one median and cannot judge impact. The fix is a **pre-analysis in code**: each provider computes facts and a machine-readable significance from verified data, and the model only narrates them (prompt v2 is task 037). Providers never write prose.

## Contract (every provider)
1. **Shape.** A `@Component` in package `sntiago05.rejuv.services`, named `<Topic>CoachProvider`, implementing `CoachInsightProvider`. `key()` returns a constant lowercase snake_case string; `provide(CoachRequest)` returns `Optional<CoachInsight>` whose `key` equals `key()`. Constructor injection, `final` fields, no Lombok.
2. **Payload.** A `public record` (nested in the provider class) serialized by Jackson 3 with `@JsonProperty("snake_case")` names and `@JsonInclude(NON_NULL)`: only numbers, booleans, strings and lists of such records. No `null`s, no nested maps with free keys, no prose sentences. Labels are short identifiers (`"laning"`, `"late"`) or names taken from assets (hero or item names).
3. **Numbers.** Rounded to one decimal (half up, like `MetricsService.round`); percentages as 0-100; times in whole minutes when shown to the model as `minute`, in seconds otherwise (say which in the field name: `_s`, `_min`).
4. **Significance.** The top-level payload record has a `String significance` with exactly one of `"none"`, `"notable"`, `"major"`, computed in code from named constants (thresholds are `static final` fields with a comment on why that value). The model is told later to prioritize `major`, so thresholds must be conservative: `major` only for clearly abnormal values.
5. **Size.** The serialized payload must stay at or below **1,200 characters** for a realistic full match (the test builds one and asserts it). Cap list lengths (state the cap in the spec).
6. **Missing data is normal.** `request.telemetry()`, `request.player()`, `request.rankTier()` can be `null`; snapshots can lack `detail` (raw fallback source), `playerSlot` and `assignedLane` can be `null`, lists can be empty, matches can be short. In every such case return `Optional.empty()` (or a reduced payload if the spec says so). Never throw for missing data; never divide by zero (guard `std <= 0`, `duration <= 0`, empty lists).
7. **Purity and cost.** No AI call, no database access, no Discord/JDA type, no `Instant.now()` (inject a `Clock` with the two-constructor pattern used by `ProfileService` if time is needed). Only the cached `DeadlockApiClient` methods listed in the spec; call them **sequentially**; do not add caches, endpoints or DTOs unless the spec lists them. Same input must give the same output (sort ties explicitly: by value, then id).
8. **Efficiency.** Single pass over snapshots, deaths and history; hash maps for lookups (`GameAssets`, player by slot). No nested scans of the 200-entry history or the telemetry players beyond the 12 players.
9. **Failures.** `CoachContextService` already catches `RuntimeException` from a provider and skips it; still do not rely on that for expected conditions. Do not catch broad exceptions inside the provider except around an optional extra API call that the spec marks as best effort (log one WARN with the exception class).
10. **Logging.** At most one `DEBUG` line. No INFO line per provider (the command already logs one per analysis). Never log payloads or account ids.

## Files a provider task may touch
Its own provider class, its own tests (JUnit 5 + AssertJ, hand-built `MatchTelemetry`/`PlayerTelemetry`/`StatSnapshot`/`SnapshotDetail`/`DeathEvent`/`MatchHistoryEntry`, no network), its own task report, and only the shared files its spec lists. A provider task must **not** edit `AnalysisInput`, `AnalyzeService`, `CoachContextService`, `MetricsService`, the prompt, `messages.properties` (no user-facing text exists in a provider), `CacheConfig`, `DeadlockApiClient` or existing DTOs. The new provider is picked up by Spring component scanning, so no wiring file changes. `docs/PROJECT_CONTEXT.md` is not edited (parallel worktrees): mark the update pending integration in the report.

## Required test cases (every provider)
- Happy path with a realistic full match and the exact payload fields.
- Each "missing data" case from rule 6 that applies, returning empty (or the reduced payload).
- Threshold boundaries for every constant used by `significance` (just below, at, just above).
- Determinism: two calls give equal payloads; the request objects are not mutated.
- Payload size at or below 1,200 characters (serialize with a plain Jackson 3 `JsonMapper`).
- `key()` equals the insight key and is lowercase snake_case.
- `./mvnw clean verify` passes; the report states baseline and final test counts.

## Reading material for the implementer
`docs/tasks/029-coach-foundation.md` and its report (the contract), `services/CoachRequest.java`, `services/CoachInsightProvider.java`, `domain/PlayerTelemetry.java`, `domain/StatSnapshot.java`, `domain/SnapshotDetail.java`, `domain/DeathEvent.java`, `clients/dto/PerformanceCurvePoint.java`, `services/MetricsService.java` (rounding and metric naming), and `docs/PROJECT_CONTEXT.md`.
