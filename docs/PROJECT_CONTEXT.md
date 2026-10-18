# Project context: Rejuv, an AI coach for Deadlock

Single source of truth for project state. Compact snapshot, not a log: per-task detail lives in `docs/task-reports/NNN-*.md`, the specs in `docs/tasks/`, decisions in `docs/adr/`. The same content is uploaded to the Claude Project knowledge base. Update it with verified facts only, and only after a batch of branches is merged (AGENTS.md).

Legend: **Done** = merged and verified in the repo. **Planned** = decided or specified, not built.

## Who
Backend student (Spring Boot, Riwi Colombia), intermediate level, 15-30 h/week, budget under 100,000 COP. Plays Deadlock and LoL. Goals: online income, portfolio, learn AI-assisted development. Explain important decisions; chat explanations in Spanish; everything produced (code, docs, commits, bot UI) in English.

## Product
Rejuv: English-language Discord bot that coaches Deadlock players with AI analysis of their matches. Differentiator vs stats sites (Deadlock Assistant has 600+ servers but no AI coaching): specific, personal, actionable advice plus social server features. Built i18n-ready.
- Commands implemented: `/ping` (dev guild), `/help`, `/link`, `/unlink`, `/profile`, `/analyze`, `/matches`, `/usage`, `/meta`, `/build`, `/counter`, `/heroes`, `/mates`, `/leaderboard server`, `/items` and `/compare` (both premium). Behavior spec: `docs/mvp-spec.md`; feature catalog: `docs/features.md` (ADR-0014).
- `/analyze` = a match vs p25/p50/p75 of the same hero and rank tier; Haiku returns strict JSON (strength, biggest leak, next-game action, key numbers). Free limit 3/week behind a flag, premium (~2 USD/month via a Patreon role in the support server) planned behind the same flag. Launch free first month.
- Surfaces: Discord bot first; public web report per analysis (ADR-0012, ADR-0018).
- Costs: initial ~20,000 COP; ~2 COP per analysis; details in `docs/costs.md`.

## Stack and architecture
Java 21, Spring Boot 4.1, JDA 5.6, Maven, PostgreSQL 17 + Spring Data JPA + Flyway, Caffeine, Spring `RestClient`, Spring MVC + Thymeleaf (web report), Claude Haiku 5.5 `claude-haiku-5-5`, Docker Compose (Oracle Cloud ARM64). ADRs 0001-0018.
Packages (`sntiago05.rejuv`): `commands` (no logic) -> `services` -> `clients` (cached external APIs) / `repositories`; plus `domain`, `config`, `web`.
`/analyze`: deferReply -> select match (history, cached) -> stored analysis? return it -> usage check -> metadata + benchmarks + item stats -> `CoachContextService` providers -> Haiku -> store -> embed with build field, PNG chart and optional "View full report" button.

## Workflow (ADR-0009)
Claude Project/Claude Code = architect: writes specs in `docs/tasks/`, reviews diffs. Antigravity (`agy`) = main implementer, one worktree per task under `../rejuv-wt/NNN`, branch `feature/NNN-slug`, report in `docs/task-reports/`. Human reviews and merges; agents never push or merge unasked. Skills: `create-task-spec`, `execute-task`, `task-worktree`, `review-task-diff`, `git-commit`, `new-discord-command`, `run-rejuv` (mirrored in `.claude/skills/` and `.agents/skills`). Operational merge/launch plan: `docs/plan-de-implementacion-017-029.md`.

## Current status (2026-10-18)
`main` contains tasks 001-009, 011-034, 036, 038 (see table; 010, 035 and 037 are not merged). Last full build: `./mvnw verify` passed on the 038 branch after merging `main` (exit 0, 816 tests on its own base; the 018 branch reported 814, 034 reported 820; each task was built before the others merged). `main` itself has not been rebuilt after the wave 2 merges (PRs #66-#71): run `./mvnw verify` on `main` for the real count. CI (`.github/workflows/ci.yml`) now runs `verify` and the image build on PRs.
The bot has **not** been run end to end in public or deployed; no manual Discord check exists for most features.

| Task | What | Report | PR |
|---|---|---|---|
| 001-003 | Skeleton + `/ping`; i18n `Messages` + `/help`; Postgres/Flyway `linked_account` (V1) | 001-003 | |
| 004 | `DeadlockApiClient` (history, rank, benchmarks, item stats, assets), 503/429 rules, caches | 004 | #25 |
| 005, 006 | `/link`, `/unlink`; `/profile` | 005, 006 | #27, #31 |
| 007, 008 | `MetricsService` (pure); `AnalysisClient` (Haiku, retry, fallback) | 007, 008 | #33, #32 |
| 009 | `/analyze` + `match_analysis` (V2), degraded mode, stored reuse | 009 | #41 |
| 011-014, 016 | `/build`, `/counter`, `/meta`, `/heroes`, `/leaderboard server` | 011-014, 016 | #36-#43 |
| 015, 025 | `/mates`; `/matches` + match selection/autocomplete | 015, 025 | #50, #51 |
| 019 | Usage limits (V3 `analysis_usage`), `/usage`, `PremiumChecker` port | 019 | #53 |
| 022, 023 | Match metadata port + JSON adapter; raw protobuf fallback (ADR-0017) | 022, 023 | #28, #52 |
| 024 | Rich presentation: emojis, thumbnails, `EmbedStyle` (ADR-0016) | 024 | #40 |
| 026-028 | Items + PNG chart in `/analyze`; typed match id; Steam names in `/mates` | 026-028 | #54-#56 |
| 029 | Coach foundation: richer telemetry, performance curve client, `CoachInsightProvider` | 029 | #60 |
| 030 | Strip stray `}` `,` from AI text (in `AnalysisResult` constructor) | 030 | #59 |
| 017 | Web report `/r/{publicId}` + link button (V4 `public_id`, ADR-0018) | 017 | #63 |
| 020 | Patreon role check (`PatreonRolePremiumChecker`, flag `rejuv.premium.enabled`) | 020 | #61 |
| 021 | `/items` premium + `PremiumFeatureGate` | 021 | #62 |
| 018 | Soul curve SVG on the web report (`SoulCurveSvg`, `/r/{id}/soul-curve.svg`) + optional Caddy HTTPS (`--profile https`) | 018 | #66 |
| 031-034 | Coach layers (`CoachInsightProvider`): `phases` (z-scores vs rank curve), `deaths`, `build` (vs popular build), `recent_form` | 031-034 | #67-#70 |
| 038 | `/compare` premium: last games on a hero vs tier 11 medians | 038 | #71 |
| 036 | Dockerfile, `compose.prod.yaml`, CI, `docs/deployment.md` | 036 | #64 |
| refactor | `DeadlockApiRequests`, `GameAssets`, perf/dedup | refactor-perf-dedup | #39 |

### Key facts per area (what future tasks must know)
- **Telemetry model:** `MatchTelemetry`/`PlayerTelemetry` (own model, not API DTOs). Since 029 players carry `playerSlot`, `assignedLane`, `deaths` (`DeathEvent`: time, killer slot, respawn seconds) and each `StatSnapshot` has an optional `SnapshotDetail` (kills/deaths/assists, creep kills, possible creeps, shots hit/missed, denies, damage taken, lane/neutral gold, gold lost on death). **Only the JSON adapter fills them** (it also fills `MatchTelemetry.averageBadge`, the mean of the non-zero team average badges, since 018); the raw fallback leaves `detail`, slot and lane `null` and no deaths, so consumers must treat that as "no data".
- **Coach extension point (029):** implement `CoachInsightProvider` (`key()`, `provide(CoachRequest)`) as a `@Component`; `CoachContextService` runs providers sequentially in key order, isolates failures, and the result goes to `AnalysisInput.coach` (omitted from the JSON when empty). Runs only on the fresh-AI path. Registered providers (keys): `phases` (031: z-scores at 6/12/20/30 min, phase changes), `deaths` (032), `build` (033: calls `BuildService` once, needs a rank tier), `recent_form` (034: only match and history, works in degraded mode). Each payload is under 1,200 characters with `significance` none/notable/major; shared rules in `docs/coach-provider-rules.md`. The prompt does not mention `coach` yet (task 037), so none of this changes the visible `/analyze` output.
- **Performance curve:** `DeadlockApiClient.getPerformanceCurve(heroId, rankTier)` -> `PerformanceCurvePoint` list (time in seconds from `resolution=0`; `_avg` and `_std` of net worth, kills, deaths, assists, player damage, denies, creep kills), cache `deadlockPerformanceCurve` 6 h/500. Only mean and std exist (no percentiles).
- **Player item stats (021):** the endpoint is `GET /v1/analytics/item-stats` with `account_id`, `hero_ids`, `min_unix_timestamp` (there is no `/v1/players/{id}/item-stats`). Cache `deadlockPlayerItemStats` 10 min/200.
- **Premium gate:** `PremiumFeatureGate.allowed(userId)` = limits disabled OR `PremiumChecker.isPremium`. `PremiumChecker` default says nobody; with `rejuv.premium.enabled=true` the Patreon role checker (JDA REST lookup in `PREMIUM_GUILD_ID`/`PREMIUM_ROLE_ID`, cached 2 min, fail closed) is `@Primary`.
- **Usage limits:** off by default (`USAGE_LIMITS_ENABLED=false`); a usage row is written only after a fresh, stored, non-fallback AI analysis; stored/fallback/duplicate results never count. To test: `USAGE_LIMITS_ENABLED=true USAGE_WEEKLY_FREE_ANALYSES=1`.
- **Web (017):** `rejuv.web.base-url` (`WEB_BASE_URL`, blank = no button), server binds `127.0.0.1:${WEB_PORT:8080}` by default, page headers set `noindex` and a strict CSP, no JavaScript. Page shows analysis text, final build and the PNG chart; `rank`/`result` are not stored. Since 018 the page also shows the soul curve as a server-side SVG (absent when metadata, lobby badge or curve are missing). HTTPS: optional Caddy service under compose profile `https` (`Caddyfile`, `DOMAIN` env, bot binds `0.0.0.0` in `compose.prod.yaml`); not run on a real server or domain yet.
- **Metadata fallback (023):** `FallbackMatchMetadataSource` (`@Primary`) tries JSON then raw (Zstandard + hand-written protobuf reader) on 503/429/I-O/invalid body; 404 never falls back. Real payload is Zstandard although the OpenAPI text says bz2. Needs a glibc base image for `zstd-jni`.
- **Presentation convention (every embed command):** `EmbedStyle.COLOR`, hero/item names through `IconCatalog` + `EmbedStyle.withIcon`, thumbnail via `AssetImages`, every field through `EmbedStyle.fitField`, must work text-only (mandatory test).
- **`/compare` (038):** `CompareService` (sample: hero filter, matches of 10+ min, newest 20, minimum 5), benchmarks at fixed tier 11 plus the player's own rank median (tier 1-10 only); deaths is lower-is-better; gated by `PremiumFeatureGate`. No telemetry (skips items and damage per minute on purpose).
- **Caches:** `CacheConfig` registers 18 Caffeine caches (including `deadlockPlayerNames`, `deadlockPlayerItemStats`, `deadlockPerformanceCurve`). Any new cache must be added to `DeadlockApiClientCachingTest.cacheTtlsAndSizesMatchTheDesign` (exact set of names, TTLs, sizes); parallel tasks conflict there, resolve by keeping all entries.
- **Flyway:** V1 `linked_account`, V2 `match_analysis`, V3 `analysis_usage`, V4 `match_analysis.public_id`. Next is V5; check open branches for a clashing number.

## Deadlock API facts (observed in real responses)
- Host `https://api.deadlock-api.com`. Analytics limit 200 req/min per IP; 503 with `Retry-After` under load; never fire parallel uncached analytics calls.
- `match-history`: array newest first, 200 entries kept; `[]` for accounts without data; `player_match_outcome` 0 before ~2026-07-30; win = `match_result == player_team` (inference); `ranked_display_badge` non-null only for ranked, 80% of one account's recent 200 matches.
- `player-stats/metrics`: filters `hero_ids`, `min/max_average_badge`; keys `percentile25/50/75`; no benchmark for net worth at 10/20 min or item timing (use the performance curve and `item-stats` `avg_buy_time_s`).
- `matches/{id}/metadata`: JSON in `match_info`, 12 players; per player `hero_id`, `team`, `player_slot`, `assigned_lane` (values 1, 4, 6 seen, 2 players per team per lane), `death_details[]` (`game_time_s`, `killer_player_slot`, `time_to_kill_s`, `death_duration_s`, positions), `items`, `stats[]` snapshots at 180, 360, 540, 720, 900, 1200, 1500, 1800, 2100 s plus final (net worth, damage, healing, creep kills, possible creeps, shots, damage taken, gold sources), `power_up_buffs` (since 2026-09-29). Also `damage_matrix`, `objectives`, `mid_boss` (shape not inspected). May return 503 "Steam is busy"; a non-existent match returns 503, not 404. `item_id` is unsigned 32-bit (use `long`). `player-performance-curve` and `hero-counter-stats` (42 rows for hero 31) confirmed in `docs/evidence/029-coaching-analytics/` (local only, not committed: contains real account data).
- `players/{id}/rank`: zeros for unranked. `players/steam?account_ids=` returns 200 `[]` for unknown ids. Ranks tiers 0-11; `/v1/assets/items` is ~8 MB.

## Constraints
- English-only user text in `messages.properties` via `Messages` (ADR-0008). No hardcoded text, including Thymeleaf templates (`#{...}`).
- Secrets only in `.env`/environment (never read or commit `.env`). The deny rule in Claude Code settings also blocks `.env.example` for the assistant; edit it by hand if needed.
- Needs Docker for `verify` (Testcontainers) and a reachable PostgreSQL at startup. Server Members Intent must be enabled in the Discord Developer Portal.
- On this Windows machine use `.\mvnw.cmd`; it may need `MAVEN_USER_HOME` pointed at a junction. `.claude/skills/run-rejuv/driver.sh list/cmd/eval` fail on Windows (`;` classpath issue).
- Claude API key only from `ANTHROPIC_API_KEY`, never logged. `agy` launched from scripts needs a `.ps1` launcher (quoting); one worktree per task.

## Known limitations and risks
- Commands call the API synchronously on the JDA event thread after a deferral (`/analyze` makes many sequential calls).
- `/analyze` quality (audit of 4 real analyses): numbers are grounded, but reasoning errors (compares a value with the wrong median), "leaks" in dominant games (0 denies) and no priority by impact. Coach upgrade planned: tasks 031-035 (data layers), 037 (prompt/schema v2), 010 (tuning). `max_tokens` is 1500 (raise in 037 if needed).
- `/analyze` with duration <= 0 throws `IllegalArgumentException` (generic error). Re-linking to another account keeps the old account's stored analyses. `/profile` has no cache eviction on link (10 min stale). `/leaderboard server` considers only the 500 most recently linked accounts.
- Web report: `rank` and `result` not stored; the page needs the cached metadata (24 h) for duration/build/chart and degrades without it; `spring.jpa.open-in-view` is on (default) and could be turned off.
- Fonts for the chart are installed in the Docker image (task 036) but a real container render check on the server is still pending; a render failure only drops the image.
- First emoji sync uploads ~315 application emojis (1-3 min); `MarkdownSanitizer` does not neutralize `@everyone` (embeds do not ping); private profiles without `personaname` unverified.
- Data coverage gaps in deadlock-api; match metadata can be unavailable (degraded mode: history metrics only). Death positions have no map-zone data (do not claim "died in enemy territory").

## Next
1. Wave 2 is merged except 035 (matchup coach; branch `feature/035-matchup-coach` exists locally, not pushed). Then 037 (prompt/schema v2 with priorities and severity, embed, web; spec still to write, depends on 031-035) and 010 (tuning with testers).
2. Manual Discord checks pending (steps in `docs/manual-testing.md`): `/analyze` (chart, typed id, report button, soul curve), `/items`, `/compare`, `/mates` names, `/heroes`, Patreon role, emoji sync, and that coach providers do not slow or break `/analyze`.
3. Deployment to Oracle (guide in `docs/deployment.md`); HTTPS/domain not done.

## References
- Specs: `docs/mvp-spec.md`, `docs/features.md`, `docs/architecture.md`, `docs/product.md`, `docs/costs.md`, `docs/deployment.md`
- Process: `docs/implementation-plan.md`, `docs/plan-de-implementacion-017-029.md`, `docs/roadmap.md` (not updated for 022+), `docs/agent-workflow.md`
- ADRs: `docs/adr/0001`-`0018` (most relevant: 0001, 0002, 0005, 0006, 0008, 0009, 0010, 0011, 0012, 0016, 0017, 0018)
- Task specs and reports: `docs/tasks/NNN-*.md`, `docs/task-reports/NNN-*.md`
