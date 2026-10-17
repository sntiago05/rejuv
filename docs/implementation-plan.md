# Implementation plan (Phase 1 MVP)

## Approach
Vertical slices: each task delivers something runnable end to end, small enough for one agent session. Every task has a spec in `docs/tasks/` written in the Claude Project before implementation (ADR-0009).

## Task sequence
| # | Task | Depends on | Implementer |
|---|---|---|---|
| 001 | Project skeleton, config, `/ping` in dev server | - | Antigravity |
| 002 | i18n setup (`messages.properties`, `Messages` helper) + `/help` | 001 | Antigravity |
| 003 | PostgreSQL via Docker Compose, Flyway, `linked_account`; remove the TEMP auto-configuration exclusions from task 001 | 001 | Antigravity |
| 004 | `DeadlockApiClient`: match history (also the account check), benchmarks (`player-stats/metrics`), heroes/ranks/items assets, player rank, caching, 503 handling. Match metadata is **not** included (task 022) | 001 | Antigravity |
| 005 | `/link` and `/unlink` | 003, 004 | Antigravity |
| 006 | `/profile` | 005 | Antigravity |
| 007 | `MetricsService`: match metrics + benchmarks (pure logic, heavy unit tests with JSON fixtures; uses history, `MatchTelemetry`, benchmarks and the items catalog) | 004, 022 | Claude Code (core logic) |
| 008 | `AnalysisClient` for Claude Haiku 5.5 with JSON contract, retry, fallback | 001 | Claude Code |
| 009 | `/analyze` wiring + `match_analysis` persistence; degraded mode: if match metadata is unavailable, analyze with history data only | 005, 007, 008 | Antigravity |
| 010 | Prompt tuning with real matches from testers | 009 | Claude Project |
| 011 | `/build hero [rank]` (item flow + ability order) | 004 | Antigravity |
| 012 | `/counter hero` | 004 | Antigravity |
| 013 | `/meta [rank]` | 004 | Antigravity |
| 014 | `/heroes` | 005 | Antigravity |
| 015 | `/mates` + party stats in `/profile` | 005, 006 | Antigravity |
| 016 | `/leaderboard server` | 005 | Antigravity |
| 017 | Web server enablement, report page `/r/{publicId}`, embed button | 009 | Claude Code |
| 018 | Soul curve chart on report page, domain + Caddy HTTPS | 017 | Antigravity |
| 019 | Feature flag + usage limits | 009 | Antigravity |
| 020 | Patreon Premium role check | 019 | Antigravity |
| 021 | `/compare` and `/items` (premium) | 007, 019 | Claude Code |
| 022 | Match metadata: port `MatchMetadataSource`, own model `MatchTelemetry`, JSON adapter (`/v1/matches/{match_id}/metadata`) as the primary source, reusing the task 004 HTTP infrastructure | 004 | Antigravity |
| 023 | Match metadata fallback: raw adapter (`/metadata/raw`: bz2 + protobuf decode) and `FallbackMatchMetadataSource` (JSON first, raw on failure); needs an ADR for the new dependencies | 022 | Claude Code |

Fast track: week 1 = 001-010 plus 022 (before 007), week 2 = 011-021 (day by day in `roadmap.md`, to be updated for 022). Task 023 does not block 007 or 009: schedule it before the public launch only if the JSON metadata endpoint fails often during the tester week.

## Notes on the plan
- Task numbers 022-023 were appended to avoid renumbering existing references; execution order follows the "Depends on" column.
- Tasks 011-013 use other analytics endpoints (builds, counters, hero stats): each of their specs must authorize extending `DeadlockApiClient` with the endpoints it needs.
- Resolved (2026-10-16): item purchase times are compared with the `item-stats` average buy time (`avg_buy_time_s`, confirmed in a live call in Task 004; no percentiles). The AI input field is `avg_bought_at_min` (`docs/mvp-spec.md`).

## Engineering conventions
- Java 21, Spring Boot 4.1, JDA 5, Maven.
- `RestClient` for HTTP; hand-written DTOs only for fields we use (ADR-0010).
- Flyway for schema migrations.
- Config via `application.yaml` + environment variables; secrets only in `.env`.
- Tests: JUnit 5 + AssertJ; `MockRestServiceServer` for HTTP clients; JSON fixtures in `src/test/resources/fixtures/`.
- Logging: SLF4J, one log line per command with user id, command, duration, outcome.

## Definition of done (every task)
- [ ] Builds and all tests pass locally and in CI
- [ ] Follows AGENTS.md and the task spec; nothing out of scope changed
- [ ] User-facing text only in `messages.properties`
- [ ] Docs/ADRs updated if behavior or decisions changed
- [ ] Reviewed and understood by me before merging
