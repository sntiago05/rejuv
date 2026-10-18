# Manual testing guide

How to run Rejuv locally and check, in the dev Discord server, what the merged tasks do. Updated for `main` after PR #71 (tasks 001-034, 036, 038 merged; 035 and 037 are not). The Discord steps themselves are **not yet verified end to end** except where marked.

## 1. Setup

| Need | Detail |
|---|---|
| Docker running | PostgreSQL 17 from `compose.yaml` (also needed by `./mvnw verify`) |
| `.env` (never commit it) | `DISCORD_TOKEN`, `DEV_GUILD_ID`, `POSTGRES_PASSWORD`, `ANTHROPIC_API_KEY`; copy the keys from `.env.example` |
| Discord Developer Portal | **Server Members Intent** enabled (Bot > Privileged Gateway Intents), needed by `/leaderboard server` |
| Optional env (see sections below) | `WEB_BASE_URL` (web report), `PREMIUM_ROLE_CHECK_ENABLED`, `PREMIUM_GUILD_ID`, `PREMIUM_ROLE_ID` (premium commands), `USAGE_LIMITS_ENABLED` |
| A linked account | run `/link` once with your Deadlock account id (SteamID64, profile URL or account id) |

Start (Windows: `.\mvnw.cmd` instead of `./mvnw`; see the `MAVEN_USER_HOME` note in `PROJECT_CONTEXT.md` if the wrapper fails):

```bash
docker compose up -d postgres
./mvnw spring-boot:run
```

Expected in the log: Flyway applies `V1`..`V4` on a fresh database, then the commands are registered in the dev guild. The first start also syncs about 315 hero and item emojis in the background (1-3 minutes); until it finishes, embeds have no icons, which is normal.

Restart the bot after any change to environment variables or code. Commands are re-registered on every start; if a command looks old, wait a few seconds and reload Discord (Ctrl+R).

### Handy database checks

```bash
docker exec dev-postgres-1 sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "select * from analysis_usage order by id" -c "select account_id, match_id, created_at from match_analysis order by created_at desc limit 5" -c "select version, description from flyway_schema_history order by installed_rank"'
```

## 2. Checks by feature

### `/analyze` and match selection (Tasks 009, 025, 027)
1. `/analyze` with no options. Expected: a deferred public embed with Key Strength, Biggest Leak, Next Game Focus and Key Numbers, hero thumbnail, footer with hero and rank.
2. `/analyze match:` then **pick an entry** from the dropdown (label like `17/10/2026 <icon> Calico - Victory - 13/4/18 - 26:08 - 118802457`). Expected: that exact match is analyzed.
3. `/analyze match:118802457` **typing the id by hand**. Expected: same match as step 2.
4. `/analyze match:` pasting the **whole label text**. Expected: ephemeral *"That is not a match id. Pick a match from the list or type only the numeric match id."* and nothing analyzed.
5. `/analyze hero:` (autocomplete) and `result:` Victory/Defeat. Expected: the latest match with that hero or result. `match` wins over both.
6. A valid id that is not among your last 200 matches. Expected: *"No match was found matching your selection..."*.
7. Repeat step 2 for an already analyzed match. Expected: same text, **no AI call** (check the log line `source=stored`).

### Build and chart in `/analyze` (Task 026, not yet checked in Discord)
1. `/analyze` on a **fresh** match. Expected, in addition to the text: a "build" field listing your final items (emoji when synced, name, buy time such as `12:34`) and **one image** with two panels: team soul lead (blue above zero, orange below) and your net worth versus your teammates average.
2. Reopen the same match. Expected: same build and chart, no AI call, no weekly use consumed.
3. If the match metadata is unavailable (the API may answer 503), the text appears with the degraded note and **without** build and chart. If only the image is missing, check the log for `ChartRenderer` WARN (fonts); the rest of the embed must still be there.

### Weekly usage limits (Task 019; verified in Discord on 2026-10-18)
Off by default. To test, set in `.env` and restart: `USAGE_LIMITS_ENABLED=true`, `USAGE_WEEKLY_FREE_ANALYSES=1`.
1. `/usage`. Expected (ephemeral): *"You have used 0 of 1 free AI analyses this week. Your limit resets <relative time>."*; with the flag off: *"Weekly analysis limits are currently disabled..."*.
2. `/analyze` on a match you never analyzed. Expected: works and one row in `analysis_usage`.
3. `/usage` again. Expected: `1 of 1`. The reset is next Monday 00:00 UTC.
4. `/analyze` on **another** never-analyzed match. Expected: *"You have reached your weekly limit of 1 free AI analyses..."*, no AI call, no new row.
5. Reopen an already analyzed match. Expected: works, no new row. Matches analyzed **before** you enabled the flag are not counted.

### Web report (Tasks 017, 018, not yet checked)
Set `WEB_BASE_URL=http://localhost:8080` in `.env` and restart (blank = no button).
1. `/analyze` on a fresh match. Expected: a **View full report** button under the embed. With `WEB_BASE_URL` blank there is no button.
2. Open the link (`/r/<publicId>`). Expected: analysis text, final build and the PNG chart; no JavaScript, `noindex` header. An unknown id returns 404.
3. Task 018: the page also shows the **soul curve** (SVG: your net worth solid line vs lobby average dashed line with a +/-1 std band, minutes on X, souls in thousands on Y), also served at `/r/<publicId>/soul-curve.svg`. If the metadata, lobby badge or performance curve is missing, the section is simply absent and the page still loads (log shows one WARN).
4. Dark mode (browser/OS) changes the curve colors. The two lines must stay distinguishable without color (solid vs dashed).
5. Optional HTTPS (Task 018): `DOMAIN=<your domain>` and `docker compose -f compose.prod.yaml --profile https up -d` starts Caddy on 80/443; see `docs/deployment.md`. Only testable on a server with DNS.

### Premium: `/items` and `/compare` (Tasks 020, 021, 038, not yet checked)
Premium is a Patreon role in the support server. To test: `PREMIUM_ROLE_CHECK_ENABLED=true`, `PREMIUM_GUILD_ID`, `PREMIUM_ROLE_ID`, bot in that guild. With the flag off and usage limits off the gate is open.
1. Without the role (and `USAGE_LIMITS_ENABLED=true`): `/items hero:<name>` and `/compare` reply ephemeral that the command requires Premium.
2. With the role: `/items hero:<name>` lists items costing you wins and items working for you, with win rate vs meta and buy time.
3. `/compare` (no options: hero of your latest match) and `/compare hero:<name>`. Expected: embed "Souls / min, Kills, Deaths, Assists, Last hits, Denies" as `you X · top median Y (+-Z%)` against tier 11, plus your rank median when you are ranked 1-10. Footer says last 30 days.
4. `/compare` on a hero with fewer than 5 matches of 10+ minutes. Expected: the "not enough games" message with the minimum. Unlinked user: ephemeral "not linked". Unknown hero: "Hero not recognized".
5. Role removal takes up to 2 minutes to apply (cache); if the Patreon lookup fails the gate closes (fail closed).

### Coach layers (Tasks 029, 031-034; not visible in Discord yet)
`PhasesCoachProvider` (`phases`), `DeathsCoachProvider` (`deaths`), `BuildCoachProvider` (`build`) and `FormCoachProvider` (`recent_form`) run only on the fresh-AI path and feed `AnalysisInput.coach`. The prompt does not use them until task 037, so **`/analyze` output should look the same as before**. What to check:
1. `/analyze` on a fresh match still succeeds and takes about as long as before (the providers add one performance-curve call and one build call, both cached).
2. The log has no new ERROR; a provider failure only logs one WARN with the exception class and `/analyze` continues.
3. A match using the raw metadata fallback also still works (death and farm data are missing there, so those layers return nothing).

### Concurrency and freshness (Tasks 040, 041)
1. `/analyze` on a fresh match. Expected: the Biggest Leak starts with a severity label (Major, Notable or Minor) and the log has no `invalid_output`. If it fails, Anthropic may reject the `enum` in the schema: report the log line.
2. While that `/analyze` runs, send `/ping` or `/help` from another account. Expected: immediate answer.
3. `/unlink`, then `/link` another account, then `/profile`. Expected: data of the new account at once.
4. The log shows no `deadlock-api gate timeout` under normal use.

### `/matches` (Task 025)
`/matches` lists the latest 10 matches and marks the analyzed ones; filters `hero` and `result` apply; `user` shows another linked member. Footer suggests `/analyze match:<id>`.

### `/mates` (Tasks 015, 028)
`/mates` (or `user:`). Expected: top 5 teammates of the last 90 days with at least 5 games together, each as `Name (#accountId)`; a teammate without a Steam profile shows `Player #accountId`. A user without a linked account gets the ephemeral "not linked" message.

### Other commands (earlier tasks, no new changes)
`/ping`, `/help` (lists every command, including `/usage`, `/items` and `/compare`), `/link`, `/unlink`, `/profile`, `/heroes`, `/meta`, `/build`, `/counter`, `/leaderboard server`. A quick pass is enough after the build.

## 3. Metadata fallback (Task 023)
Hard to trigger by hand. With a healthy API, no WARN `JSON match metadata failed` should appear. When the JSON metadata endpoint answers 503 or 429, expect that WARN once and the analysis still succeeding from the raw protobuf endpoint. The result is cached for 24 hours, so a second `/analyze` of the same match makes no new request.

## 4. What a failure looks like
| Symptom | Likely cause |
|---|---|
| Bot does not start, "Disallowed intents" | Server Members Intent off in the Developer Portal |
| Bot does not start, database error | Docker/postgres not running or wrong `POSTGRES_PASSWORD` |
| Command missing or old options | bot not restarted, or Discord client cache (Ctrl+R) |
| `/analyze` returns the latest match instead of the chosen one | the value was not a dropdown choice or a numeric id (fixed by Task 027: now shows the invalid-id message) |
| Generic error with an error id | check the log for that id; unexpected exception |
| "Data source busy" message | deadlock-api returned 503/429; retry in a minute |
| No icons in embeds | first emoji sync still running |
| `/items` or `/compare` always says Premium required | `PREMIUM_ROLE_CHECK_ENABLED`, guild/role ids or the Server Members Intent are wrong, or the bot is not in the premium guild |
| No View full report button | `WEB_BASE_URL` blank |
| Embed without image | chart rendering failed (fonts) or no metadata; see the log WARN |

## 5. Report back
When something fails, note: the exact command and options, the time, and the log lines around it (never paste tokens or `.env`). Findings go to a new task spec; do not fix them ad hoc on `main`.
