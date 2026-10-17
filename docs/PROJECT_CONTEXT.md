# Project context: Rejuv, an AI coach for Deadlock

Single source of truth for project state. Compact snapshot: product and decisions in the first half, verified progress in "Current status". Full detail lives in the other files in `docs/`. The same content is uploaded to the Claude Project knowledge base.

Legend used below: **Done** = implemented and verified in the repo. **Planned** = decided or specified, not built yet.

## Who
Backend student (Spring Boot, Riwi Colombia, paused systems engineering degree), intermediate level, 15-30 h/week, budget under 100,000 COP. Plays Deadlock and LoL. Goals: earn online income, build a strong portfolio, learn AI-assisted development. Explain important decisions; chat explanations in Spanish; everything produced (code, docs, commits, bot UI) in English.

## Product
Rejuv: English-language Discord bot that coaches Deadlock players with AI analysis of their matches. Differentiator vs stats sites: specific, personal, actionable AI advice + social server features. Built i18n-ready (Spanish can be added later).
- MVP commands: `/link`, `/unlink`, `/profile`, `/analyze`, `/help` (full spec in `docs/mvp-spec.md`). `/analyze` = latest match vs p25/p50/p75 of same hero + rank tier, Haiku returns strict JSON (strength, biggest leak, next-game action, key numbers). Free limit 3/week starts in week 2, behind a feature flag.
- Premium (~2 USD/month/user, week 2, behind a feature flag): unlimited analyses, progress tracking, builds, server rankings.
- Feature catalog in `docs/features.md` (ADR-0014): free hero utilities without linking (`/build`, `/counter`, `/meta`), social (`/heroes`, `/mates`, `/leaderboard server`); premium personal insight (`/compare`, `/items`, soul curve, later heatmap/trends/alerts). AI only for interpretation.
- Surfaces: Discord bot first; week 2 adds public web report pages linked from each analysis (ADR-0012). Premium `/compare` vs top-rank players on same hero (ADR-0013).
- Monetization: Patreon assigns `Premium` role in official support server; bot checks role by user ID. Launch free first month to validate.
- Competitor: Deadlock Assistant (600+ servers, stats/meta/counters/builds, no AI coaching). Our edge is coaching.

## Stack (ADRs 0001-0005)
Java 21, Spring Boot 4.1 (no web server initially), JDA 5, Maven, PostgreSQL + Spring Data JPA (+ Flyway), Caffeine cache, deadlock-api.com (community, unofficial), Claude Haiku 5.5 `claude-haiku-5-5` (low effort, capped max_tokens, only metric summaries sent), Docker Compose on Oracle Cloud Always Free ARM.

## Architecture
Packages: `commands` (no logic) → `services` → `clients` (cached external APIs) / `repositories`; plus `domain`, `config`. Base package `sntiago05.rejuv`.
`/analyze`: deferReply → usage check → fetch matches (cached) → compute metrics → Haiku → store → English embed.

## Costs
Initial ~20,000 COP (5 USD API credits). ~2 COP per analysis. Patreon fee assumed 12%. Spend limit set in Claude Console. Budget near zero, passion project; only spend is capped Claude API credits (+ domain in week 2). Details in `docs/costs.md`.

## Workflow (ADR-0009)
Claude Project = architect: designs, writes task specs in `docs/tasks/`, reviews diffs. Antigravity = main implementer, follows task specs and writes a report in `docs/task-reports/`. Claude Code = reserved for hard tasks (complex bugs, AI analysis service). One agent per branch, git worktrees, human review before merge. `AGENTS.md` single source of rules, `CLAUDE.md` imports it, skills mirrored in `.claude/skills/` and `.agents/skills`. Docs and ADRs in English, updated with code. herdr postponed.
Rule from `AGENTS.md`: update this file after each task with verified facts only; with several worktrees active, defer the update until branches are integrated.

## Plan
23 tasks in `docs/implementation-plan.md` (022-023 added for match metadata), ADRs 0001-0015 in `docs/adr/`. Fast track in `docs/roadmap.md`: week 1 = tasks 001-010 plus 022 before 007 (MVP, tester gate before public launch on day 5), week 2 = tasks 011-021 (hero utilities, social, web reports, premium behind a feature flag until ~30 weekly active users). `roadmap.md` has not been updated for 022 yet. Task 023 (raw protobuf fallback) is scheduled only if the JSON metadata endpoint fails often during testing.

## Current status
Last updated at the closeout of Task 022 on branch `feature/022-match-metadata` (verified on its branch, not merged yet). Task 005 is merged in `main` (PR #27).

### Done (verified)
- Setup: `AGENTS.md`, Antigravity rule, Claude Code settings and the `new-discord-command` skill. All planning docs and ADRs 0001-0015 written.
- **Task 001** (project skeleton and `/ping`), report in `docs/task-reports/001-project-skeleton.md`:
  - Java 21 / Spring Boot 4.1 / JDA 5 non-web bot skeleton. `.\mvnw.cmd clean verify` passes (4 tests, 0 failures).
  - `/ping` is the only implemented slash command, registered in the configured dev guild. Routing via `SlashCommand`, `CommandRouter`, `PingCommand`; config in `BotProperties` and `JdaConfig`; `CommandRouterTest` covers known, unknown and failing handlers.
  - Database dependencies are in the build, but database auto-configuration is disabled in `application.yaml` (TEMP exclusion, to be removed in task 003).
- **Task 002** (i18n setup and `/help`), report in `docs/task-reports/002-i18n-and-help.md`:
  - Message catalog `messages.properties` (UTF-8, English) with Discord-agnostic `Messages` helper (`sntiago05.rejuv.services`) over Spring `MessageSource`. Fail-fast on missing keys (`NoSuchMessageException`).
  - `/help` ephemeral slash command (`HelpCommand`) registered alongside `/ping` in the dev guild.
  - Sourced all user-facing text from the catalog in `CommandRouter` and `PingCommand` (`ping.description`, `ping.reply`, `error.unknown_command`, `error.generic`).
  - Verified with `.\mvnw.cmd verify` (22 tests, 0 failures), covering unit behavior, catalog validation, and Spring context integration.
- **Task 003** (PostgreSQL, Flyway, `linked_account`), report in `docs/task-reports/003-postgres-flyway-linked-account.md`:
  - PostgreSQL 17 in `compose.yaml` (localhost-only port, healthcheck, volume `postgres-data`).
  - Removed TEMP auto-configuration exclusions from `application.yaml`, configured datasource from `POSTGRES_*` variables with `ddl-auto: validate`.
  - Flyway migration `V1__create_linked_account.sql`, `LinkedAccount` entity, `LinkedAccountRepository`.
  - Testcontainers configuration with `postgres:17` and `@ServiceConnection`.
  - Verified: `./mvnw clean verify` (31 tests, 0 failures); manual check with `docker compose up -d postgres`, `spring-boot:run` (initial migration + idempotent second run), and `psql` schema check confirming table `linked_account`.
- **Task 004** (`DeadlockApiClient`), report in `docs/task-reports/004-deadlock-api-client.md`. Merged (PR #25):
  - `clients/DeadlockApiClient` on Spring `RestClient` (`spring-boot-starter-restclient`, Jackson 3 with `com.fasterxml.jackson.annotation` annotations): `getMatchHistory` (max 200 entries, newest first; empty list = no data, the account check for Task 005), `getPlayerRank`, `getBenchmarks(heroId, rankTier)` (`player-stats/metrics`, p25/p50/p75), `getItemStats(heroId, rankTier)` (average buy times), `getRanks`, `getHeroes`, `getItems`. No match metadata (Task 022).
  - Failures: `DeadlockApiException` (unexpected status, bad body) and `DeadlockApiUnavailableException` (503, 429, I/O, timeout; optional `retryAfter()`). One 503 retry when `Retry-After` <= 10 s (default 2 s); history 429 with an array body is a success.
  - `config/CacheConfig`: static Caffeine caches `deadlockAssets` 24 h/10, `deadlockBenchmarks` 6 h/500, `deadlockItemStats` 6 h/500, `deadlockMatchHistory` 2 min/1000, `deadlockPlayerRank` 10 min/1000. `config/DeadlockApiConfig.restClientBuilder` and `clients/Sleeper` are reused by Task 022.
  - Confirmed in OpenAPI: `item-stats` filters by `hero_ids` (`hero_id` is deprecated) and `min/max_average_badge`; metric keys are `percentile25/50/75`.
  - Verified: `driver.sh verify` (62 tests, 0 failures) and a live check of assets, benchmarks and item stats.
- **Task 005** (`/link`, `/unlink`), report in `docs/task-reports/005-link-unlink.md`. Merged (PR #27):
  - `services/AccountIdParser.parse` (pure static): SteamID64 (17 digits, prefix `7656119`), `steamcommunity.com/profiles/<SteamID64>` URL, or account ID 1..4294967295; `account_id = steamId64 - 76561197960265728`. Vanity URLs (`/id/<name>`) rejected with reason `VANITY_URL`, everything else `UNRECOGNIZED` (`InvalidAccountInputException`).
  - `services/LinkService`: `link(discordUserId, accountId)` requires non-empty cached match history (else `NoMatchHistoryException`), then upserts `linked_account`; `unlink(discordUserId)` returns whether a row was deleted. Re-linking replaces the account; the same account may be linked by several users.
  - `LinkCommand` parses before deferring, then `deferReply(true)` and handles its own errors (the router cannot reply after a deferral). `UnlinkCommand` replies directly. All replies ephemeral.
  - Shared catalog key `error.data_source_busy` for `DeadlockApiUnavailableException`; later commands reuse it.
  - Verified: `./mvnw clean verify` (102 tests, 0 failures). Manual Discord check by the owner in the dev guild (2026-10-16): `/link` with a real account id, `/link` again with the same id (one row, upsert), `/unlink` (`unlink.success`), `/unlink` again (`unlink.not_linked`), invalid input and vanity URL; all replies ephemeral.
- **Task 022** (match metadata port and JSON adapter), report in `docs/task-reports/022-match-metadata.md`. Verified on its branch, **not merged yet**:
  - Port `clients/MatchMetadataSource.getMatch(long matchId)` returns the own model `domain/MatchTelemetry` (`PlayerTelemetry`, `ItemPurchase`, `StatSnapshot`, `PowerUpBuff`; immutable lists). All players of the match are kept; items, snapshots and pickup times are sorted ascending; `sold_time_s` 0 becomes `null`; item ids are `long`. Only item purchases, three snapshot values (net worth, player damage, player healing) and power-up buffs are mapped.
  - Adapter `clients/JsonMatchMetadataSource` (only bean of the port) calls `GET /v1/matches/{match_id}/metadata` with no query parameters, with its own copy of the Task 004 request rules (one 503 retry when `Retry-After` <= 10 s). Errors: 404 → `MatchMetadataNotFoundException`; 503/429/I/O/timeout → `DeadlockApiUnavailableException`; other statuses and invalid bodies (missing `match_info`, players or a required field, other match id, malformed JSON) → `DeadlockApiException`.
  - Cache `deadlockMatchMetadata` 24 h/300 holds the mapped model; failures are not cached.
  - Verified: `./mvnw clean verify` (135 tests, 0 failures) after merging `main` with Task 005.

### In progress (not verified, not merged)
Task 022 waits for human review and merge (see above).

### Not started (planned only)
Tasks 006-021 and 023. Nothing beyond `/ping`, `/help`, `/link`, `/unlink`, the database baseline, the Deadlock API client and the match metadata port exists yet: no `/profile`, `/analyze`, metrics, AI client, web server, premium or Patreon check. The MVP has not been built, launched or deployed.

### Next
Eligible now: Tasks 006, 014 and 016 (depend on 005), Tasks 011-013 (depend on 004), Task 008 (depends on 001); Task 015 also needs 006. Task 007 needs 022 merged; Task 023 depends on 022. Task 009 needs 005, 007 and 008, and must extend `LinkService.unlink` to delete `match_analysis`.

### Deadlock API facts observed in real responses (2026-10-16, evidence collected by the owner)
- Host `https://api.deadlock-api.com`. `GET /v1/players/{account_id}/match-history` returns an array, newest first. An account without data returns 200 `[]` (not 404). The payload is large (about 5 MB for 6,842 matches), so a client must cap what it keeps.
- In the history, `player_match_outcome` is 0 (invalid) for matches before about 2026-07-30; win = outcome 1, and `match_result == player_team` matched every scored match (inference, not documented). `ranked_display_badge` is non-null only when `match_mode == 4` (inferred to be ranked); it is null for unranked matches.
- `GET /v1/analytics/player-stats/metrics` filters by `hero_ids`, `min_average_badge`, `max_average_badge` (average of both teams); it defaults to the last 30 days and to normal ranked/unranked matches. It returned 29 metrics (32 with `include_buff_metrics=true`), for example `net_worth_per_min`, `player_damage_per_min`, `last_hits`, `denies`, `deaths`, `kda`, `permanent_buffs`, `first_permanent_buff_time_s`. There is no benchmark for net worth at 10/20 min or for item purchase times.
- `GET /v1/players/{account_id}/rank` returns `badge`, `rank`, `subrank`, `last_match`; an unranked player returns 200 with zeros (no 404 observed).
- Assets: ranks have tiers 0-11; heroes expose `id`, `class_name`, `name`; `/v1/assets/items` has 764 entries (about 8 MB; types upgrade/ability/weapon; upgrades carry `item_tier` 1-5, `item_slot_type`, `cost`).
- `GET /v1/matches/{match_id}/metadata` returns JSON wrapped in `match_info` with 12 players: items (`game_time_s`, `sold_time_s`), stat snapshots at 180, 360, 540, 720, 900, 1200, 1500, 1800, 2100 s plus a final one (net worth, damage, healing), `power_up_buffs` with pickup times, and `player_rank_data.initial_display_rank`. `average_badge_team0/1` can be 0. Item ids resolve in `/v1/assets/items`. It once returned 503 "Steam is busy" (`Retry-After` 1265 s) and worked later for the same match; the Steam fallback is limited to 3 requests per hour per IP. `GET /v1/matches/{match_id}/metadata/raw` (bz2 protobuf) worked while the JSON endpoint failed. A non-existent match returned 503 ("recently failed to fetch from upstream"), not 404, so callers cannot tell "never exists" from "try later". `item_id` is unsigned 32-bit and needs a `long`.

## Constraints
- User-facing behavior is English-only (ADR-0008); all user-facing text lives in `messages.properties` and is accessed via `Messages`.
- Discord credentials come from environment variables (`.env`, see `.env.example`) and must never be committed. Manual Discord checks need `DISCORD_TOKEN` and `DEV_GUILD_ID`.
- After Task 003: the app needs a reachable PostgreSQL at startup (`POSTGRES_*` variables, no default password), Flyway is the only schema authority (`ddl-auto: validate`), and `verify` needs Docker running for Testcontainers.

## Known limitations and risks
- `/link` blocks the JDA event thread during the history call (up to the client's 10 s retry wait); a risk for Task 009, which makes more calls.
- `.claude/skills/run-rejuv/driver.sh` `list`/`cmd`/`eval` fail on Windows (`Driver.java` compile error, likely the classpath separator); `build` and `db-down` work.
- On this Windows machine the Maven wrapper may need `MAVEN_USER_HOME` pointed at a temporary junction to the existing Maven home (null-target issue in PowerShell).
- deadlock-api data coverage gaps; many competing tracker sites.
- Match metadata can be unavailable (503 while Steam is busy, strict Steam fallback quota). `/analyze` needs a degraded mode based on history data only (Task 009); a raw protobuf fallback is planned as Task 023.
- The item-timing benchmark is the `item-stats` average buy time (`avg_buy_time_s`, no percentiles), per the Task 004 spec; `docs/mvp-spec.md` still describes a p50 and needs an owner update. Net worth at 10 min has no exact snapshot (540 s and 720 s exist), so it must be interpolated; 20 min is exact (1200 s).
- Claude Pro usage limits on heavy days: keep implementation in Antigravity.

## References
- Specs: `docs/mvp-spec.md`, `docs/features.md`, `docs/architecture.md`, `docs/product.md`
- Process: `docs/implementation-plan.md`, `docs/roadmap.md`, `docs/agent-workflow.md`, `docs/getting-started.md`
- Task 001: `docs/tasks/001-project-skeleton.md`, `docs/task-reports/001-project-skeleton.md`
- Task 002: `docs/tasks/002-i18n-and-help.md`, `docs/task-reports/002-i18n-and-help.md`
- Task 003: `docs/tasks/003-postgres-flyway-linked-account.md`, `docs/task-reports/003-postgres-flyway-linked-account.md`
- Task 004: `docs/tasks/004-deadlock-api-client.md`, `docs/task-reports/004-deadlock-api-client.md`
- Task 005: `docs/tasks/005-link-unlink.md`, `docs/task-reports/005-link-unlink.md`
- Task 022: `docs/tasks/022-match-metadata.md`, `docs/task-reports/022-match-metadata.md`
- ADRs most relevant so far: `0001-java21-spring-boot-jda.md`, `0002-postgresql-and-caffeine.md`, `0005-oracle-cloud-docker-compose.md`, `0008-english-only-i18n-ready.md`, `0009-claude-plans-antigravity-implements.md`
