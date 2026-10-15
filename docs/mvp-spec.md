# MVP functional specification

Status: Draft for review. Defines exactly what the bot does in Phase 1. Anything not listed here is out of scope.

## Goal
A player links their account once, and `/analyze` gives them a short, specific coaching report on their latest match, benchmarked against players of the same hero and rank. Success = testers say "I didn't notice that" and run it again after their next match.

## Commands

| Command | Purpose | Visibility |
|---|---|---|
| `/link account:<text>` | Link a Deadlock (Steam) account to the Discord user | Ephemeral |
| `/unlink` | Remove the link and stored data | Ephemeral |
| `/profile [user]` | Rank, last 10 matches summary, top heroes | Public |
| `/analyze` | AI coaching report on the latest match | Public |
| `/help` | What the bot does, how to link, data coverage tip | Ephemeral |

`/ping` exists only in the dev test server (health check), not registered globally.

### `/link account:<text>`
Accepted inputs:
- SteamID64 (17 digits, starts with `7656119`)
- Profile URL `https://steamcommunity.com/profiles/<SteamID64>`
- Deadlock account ID (SteamID3 number, as shown on deadlock-api / Statlocker)

Conversion: `account_id = steamId64 - 76561197960265728`.
Not supported in MVP: vanity URLs (`/id/<name>`), they need the Steam Web API. Reply explains how to find the numeric ID.

Behavior:
1. Parse input. Invalid → ephemeral error with examples.
2. Verify the account has Deadlock match history via the API. None → error with data coverage tip.
3. Upsert `linked_account(discord_user_id, account_id, linked_at)`.
4. Reply: confirmation + suggestion to run `/analyze`.

### `/profile [user]`
- Default user = caller. If not linked → prompt to `/link`.
- Embed: current rank (badge name + subtier), win rate last 10, average K/D/A last 10, top 3 heroes by games (last 30 days).
- No AI call. Cached 10 minutes per account.

### `/analyze`
1. Caller must be linked.
2. `deferReply()` (AI + API calls can exceed 3 s).
3. Fetch the latest match from match history.
4. If an analysis for `(account_id, match_id)` exists in DB → return it (no AI cost).
5. Build `MatchMetrics` for the player in that match.
6. Fetch benchmarks: same hero, same rank tier (badge range `tier*10` to `tier*10+9`), last 30 days, percentiles p25/p50/p75 for each metric. Cached 6 h per (hero, tier).
7. Build compact `AnalysisInput` (see below) and call Claude Haiku 5.5.
8. Store result, reply with embed.

Embed layout:
- Title: `<Hero> · <Win/Loss> · <duration>`
- Field "Strength": 1-2 sentences
- Field "Biggest leak": 1-2 sentences with numbers vs benchmark
- Field "Do this next game": one concrete action
- Field "Key numbers": 3-4 metrics as `yours / median at your rank`
- Footer: `Compared with <hero> players at <rank> · last 30 days`

### `/help`
Static text from `messages.properties`, including: matches may be missing if the player's data isn't tracked yet; the deadlock-api ingest tool helps fill gaps.

## Metrics (MVP set)
Per player, latest match:
- Souls per minute (net worth / duration)
- Kills, deaths, assists; deaths per 10 min
- Player damage per minute
- Last hits and denies (if available)
- Net worth at 10 and 20 min (if the match is long enough)
- First 3 item purchase times and the time of the first tier-3 item
- First permanent power-up (buff) pickup time and buffs per minute (matches since build 6712)

Benchmarks: p25, p50, p75 of the same metrics for same hero + rank tier.
Rule for findings: only flag a metric as a leak if it is below p25 or clearly worse than p50 with a meaningful gap. Never invent metrics not present in the input.

## AI contract
Input (JSON, target under 1,500 tokens):
```json
{
  "hero": "Haze",
  "rank": "Archon 3",
  "result": "loss",
  "duration_min": 34.5,
  "metrics": { "souls_per_min": {"value": 812, "p25": 790, "p50": 905, "p75": 1010}, "...": {} },
  "items": [{"name": "...", "bought_at_min": 6.2, "p50_bought_at_min": 5.1}],
  "language": "en"
}
```
Output: strict JSON only:
```json
{ "strength": "...", "biggest_leak": "...", "next_game_action": "...", "key_numbers": ["..."] }
```
Model settings: `claude-haiku-5-5`, effort low, `max_tokens` 600. If JSON parsing fails → retry once, then fall back to a non-AI summary of the key numbers.

## Error handling (user-facing, English, from `messages.properties`)
| Situation | Message intent |
|---|---|
| Not linked | Explain `/link` with an example |
| No recent matches found | Data may not be tracked yet; mention ingest tool |
| Deadlock API 503 / timeout | "Data source is busy, try again in a minute" (respect `Retry-After`) |
| Claude API error | Show key numbers without AI text |
| Unexpected error | Generic apology + error id logged |

## Data stored
- `linked_account`: discord_user_id (PK), account_id, linked_at
- `match_analysis`: id, account_id, match_id, hero_id, result_json, model, input_tokens, output_tokens, created_at; unique (account_id, match_id)
- `/unlink` deletes both for that user.

## Out of scope for MVP
Usage limits, premium, Patreon, server rankings, alerts, multi-match trends, vanity URL resolution, Spanish, LoL.

## Known risks
- **Data coverage**: deadlock-api only has matches it has fetched; some players' recent matches may be missing. Mitigation: clear message + ingest tool tip; measure how often it happens during testing.
- **Competition**: many tracker sites exist (Statlocker, Mobalytics, Tracker.gg, Blitz...). The coaching report quality is the product; stats alone are not.
- **API stability**: unofficial, rate limited (analytics: 200 req/min per IP), load shedding with 503. Cache everything, never fire many uncached requests in parallel.

## Open questions
- Analyze only the latest match, or let the user pick one of the last 5? (MVP: latest only)
- Should `/analyze` be public or ephemeral by default? (MVP: public, it advertises the bot)
- Include ability order analysis? (Later)
