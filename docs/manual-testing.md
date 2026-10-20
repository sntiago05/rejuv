# Manual testing guide

How to run Rejuv locally and check, in the dev Discord server, what the merged tasks do. Updated for `main` with tasks 001-051 merged (049 report redesign, 050 donations and 051 free model are the newest; sections 2.0 to 2.2 below cover them). The Discord steps themselves are **not yet verified end to end** except where marked.

## 1. Setup

| Need | Detail |
|---|---|
| Docker running | PostgreSQL 17 from `compose.yaml` (also needed by `./mvnw verify`) |
| `.env` (never commit it) | `DISCORD_TOKEN`, `DEV_GUILD_ID`, `POSTGRES_PASSWORD`, `ANTHROPIC_API_KEY`; copy the keys from `.env.example` |
| Discord Developer Portal | **Server Members Intent** enabled (Bot > Privileged Gateway Intents), needed by `/leaderboard server` |
| Optional env (see sections below) | `WEB_BASE_URL` (web report), `DONATION_URL` and `DONATION_LABEL` (donation link) |
| A linked account | run `/link` once with your Deadlock account id (SteamID64, profile URL or account id) |

Start (Windows: `.\mvnw.cmd` instead of `./mvnw`; see the `MAVEN_USER_HOME` note in `PROJECT_CONTEXT.md` if the wrapper fails):

```bash
docker compose up -d postgres
./mvnw spring-boot:run
```

Expected in the log: Flyway applies `V1`..`V6` on a fresh database (on an existing one, only the missing versions, for example `V6 drop analysis usage`), then the commands are registered in the dev guild. The first start also syncs about 315 hero and item emojis in the background (1-3 minutes); until it finishes, embeds have no icons, which is normal.

Always build with `clean` after switching branches or merging (`./mvnw clean spring-boot:run`): old compiled classes of deleted code (for example `AnalysisUsage.class`) stay in `target/` and make the start fail with "missing table analysis_usage".

Restart the bot after any change to environment variables or code. Commands are re-registered on every start; if a command looks old, wait a few seconds and reload Discord (Ctrl+R).

### Handy database checks

```bash
docker exec dev-postgres-1 sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "select account_id, match_id, created_at from match_analysis order by created_at desc limit 5" -c "select version, description from flyway_schema_history order by installed_rank"'
```

## 2. Checks by feature

### 2.0 Free model and migration V6 (Task 051, not yet checked)
Needs a database that still has the old `analysis_usage` table (any database created before this task). A fresh database also works.
1. Start the bot. Expected in the log: Flyway applies `V6__drop_analysis_usage` and there is no error. **The old usage counts are deleted for good.**
2. Run the database check from "Handy database checks". Expected: `V6 drop analysis usage` with success, and `\dt` shows only `linked_account`, `match_analysis` and Flyway's own table, with no `analysis_usage`.
3. In Discord, type `/`. Expected: `/usage` is gone from the list (the dev guild updates at the start; Ctrl+R if it still shows).
4. `/help`. Expected: no `/usage` line; `/items` has a backpack icon and `/compare` a VS icon; neither says "(Premium)".
5. `/items hero:<name>` and `/compare` with a normal account that has **no special role**. Expected: normal replies. There is no "requires Premium" message.
6. `/analyze` on three different fresh matches in a row. Expected: all three work, with no "weekly limit" message and no database error in the log.
7. `/leaderboard server` in a server with linked players. Expected: same output as before (the Server Members Intent is still used).

### 2.1 Donation link (Task 050, not yet checked)
Without `DONATION_URL` (the default):
1. `/donate`. Expected (ephemeral, text only): *"Donations are not set up yet. Thanks for your interest!"*, no button.
2. `/help`, a fresh `/analyze` and an open report page. Expected: no donation text or link anywhere.

With a link: set in `.env` `DONATION_URL=https://example.com/donate` and `DONATION_LABEL=Support Us`, then restart.
3. `/donate`. Expected (ephemeral): embed "💛 Support Rejuv" with the text "Rejuv is free to use...", and a link button "Support Us" that opens the URL.
4. `/help`. Expected: the last line is *"💛 Support Rejuv: Support Us"* with a working link.
5. `/analyze` on a fresh match. Expected: the **last** embed field is "💛 Support Rejuv" with the link, and the analysis text is unchanged. (The field is skipped if the embed would be too long: that is correct.)
6. Open the report page (`/r/<publicId>`). Expected: in the footer, the text "Rejuv is free. If it helped you, you can support it:" and the link "Support Us". Check the link has `rel="noopener noreferrer nofollow"` (view source).
7. Leave `DONATION_LABEL` empty and restart. Expected: the label becomes "Support Rejuv".
8. Set `DONATION_URL=ftp://example.com` (or `javascript:alert(1)`) and restart. Expected: one WARN about an invalid donation URL in the log, and the behavior of steps 1 and 2 (no link). The bot still starts.

### 2.2 Report page redesign and charts (Task 049, not yet checked)
Set `WEB_BASE_URL=http://localhost:8080`, restart, run `/analyze` on a fresh match (the match needs stored input, so use one analyzed after Task 048) and open the report. Check in a desktop browser **and** at 360 px width (browser developer tools):
1. Layout order: hero banner, verdict strip, key numbers (bars), charts, collapsible sections, final build, footer. No JavaScript runs and no horizontal scroll at 360 px.
2. Charts block: soul lead, net worth vs lobby average, phase scorecard, deaths timeline, metrics vs peers. Each chart has a title, a caption and readable text. The deaths chart shows the three phase counts only.
3. Direct chart files: `/r/<publicId>/soul-lead.svg` (also `/lead.svg`), `/phases.svg`, `/deaths.svg`, `/metrics.svg` and `/soul-curve.svg` return an SVG. Response headers include `Content-Security-Policy` and `X-Robots-Tag: noindex, nofollow`. An unknown id returns 404.
4. Collapsible sections: "Detailed Numbers vs Peers" is open first; coach sections and "Additional Match Signals" open and close.
5. Dark mode (OS or browser) and print preview (Ctrl+P): the page stays readable; in print, details are expanded and the page is black on white.
6. An **older** analysis (made before Task 048) opens without error and shows the note that detailed metrics exist only for newer analyses.

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
2. Reopen the same match. Expected: same build and chart, no AI call.
3. If the match metadata is unavailable (the API may answer 503), the text appears with the degraded note and **without** build and chart. If only the image is missing, check the log for `ChartRenderer` WARN (fonts); the rest of the embed must still be there.

### Web report (Tasks 017, 018, not yet checked)
Set `WEB_BASE_URL=http://localhost:8080` in `.env` and restart (blank = no button).
1. `/analyze` on a fresh match. Expected: a **View full report** button under the embed. With `WEB_BASE_URL` blank there is no button.
2. Open the link (`/r/<publicId>`). Expected: analysis text, final build and the charts (see section 2.2 for the redesigned page); no JavaScript, `noindex` header. An unknown id returns 404.
3. Task 018: the page also shows the **soul curve** (SVG: your net worth solid line vs lobby average dashed line with a +/-1 std band, minutes on X, souls in thousands on Y), also served at `/r/<publicId>/soul-curve.svg`. If the metadata, lobby badge or performance curve is missing, the section is simply absent and the page still loads (log shows one WARN).
4. Dark mode (browser/OS) changes the curve colors. The two lines must stay distinguishable without color (solid vs dashed).
5. Optional HTTPS (Task 018): `DOMAIN=<your domain>` and `docker compose -f compose.prod.yaml --profile https up -d` starts Caddy on 80/443; see `docs/deployment.md`. Only testable on a server with DNS.

### `/items` and `/compare` (Tasks 021, 038; open to everyone since 051; not yet checked)
1. `/items hero:<name>` lists items costing you wins and items working for you, with win rate vs meta and buy time. Unlinked user: ephemeral "not linked".
2. `/compare` (no options: hero of your latest match) and `/compare hero:<name>`. Expected: embed "Souls / min, Kills, Deaths, Assists, Last hits, Denies" as `you X · top median Y (+-Z%)` against tier 11, plus your rank median when you are ranked 1-10. Footer says last 30 days.
3. `/compare` on a hero with fewer than 5 matches of 10+ minutes. Expected: the "not enough games" message with the minimum. Unlinked user: ephemeral "not linked". Unknown hero: "Hero not recognized".

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
`/ping`, `/help` (lists every command, including `/items` and `/compare`; `/usage` no longer exists), `/donate` (section 2.1), `/link`, `/unlink`, `/profile`, `/heroes`, `/meta`, `/build`, `/counter`, `/leaderboard server`. A quick pass is enough after the build.

## 2.2 Coach tips and spend guard (Task 052)
1. Run `/analyze` on a match that has no stored analysis. The embed shows a "🧭 Top Tips" field right after "Next Game Focus", with up to 3 numbered tips, each with Evidence and Drill. The log line `Analysis completed` shows tokens, and `Analyze ... source=ai` shows a number in `estimatedCostUsd` (not `unknown`).
2. Run `/analyze` again at once on another match: you get the cooldown message (wait about 60 s). Run it again on the same match: it returns the stored result for free, with no cooldown message.
3. To test the daily caps without waiting, start with `ANALYSIS_COOLDOWN=0s ANALYSIS_USER_DAILY=2 ANALYSIS_GLOBAL_DAILY=3` and run three different matches: the third gives the daily-limit message. State resets on restart.
4. Analyses made before task 052 show no tips field; nothing else changes.
5. Confirm that Anthropic accepts `effort: medium` and `max_tokens: 4000` (a successful new analysis proves it).

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
| No View full report button | `WEB_BASE_URL` blank |
| Embed without image | chart rendering failed (fonts) or no metadata; see the log WARN |
| Start fails with "missing table analysis_usage" | stale classes in `target/`; run `./mvnw clean ...` |
| No donation link anywhere | `DONATION_URL` blank or invalid (look for the WARN `Invalid donation URL`), or the bot was not restarted |
| "Please wait N seconds" or "daily limit" on `/analyze` | spend guard (task 052); stored analyses are never limited; raise `ANALYSIS_*` values or restart |
| Report page has no charts | the analysis was made before Task 048 (no stored input) or the cached metadata expired |

## 5. Report back
When something fails, note: the exact command and options, the time, and the log lines around it (never paste tokens or `.env`). Findings go to a new task spec; do not fix them ad hoc on `main`.
