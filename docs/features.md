# Feature catalog

Everything the bot can offer based on deadlock-api data. Endpoints must be confirmed against `https://api.deadlock-api.com/openapi.json` when each task is specified.

## Competitive landscape
- **Deadlock Assistant** (Discord bot, 600+ communities) already offers stats, match history, meta dashboards, counters, build snapshots, synergy, rivals, leaderboards and patch tracking.
- Many websites (deadlock-api.com, Statlocker, Mobalytics, Tracker.gg...) cover stats and meta.
- **Implication:** our free hero utilities (`/build`, `/counter`, `/meta`, `/synergy`, leaderboards) are commodity. They are worth having only as lightweight growth hooks; the product is AI coaching (`/analyze`, `/compare`, trends). When time is short, invest in coaching quality, not in more utility commands.

## Principles (ADR-0014)
- **Free = utility that spreads the bot.** Useful to anyone in a server, often without linking an account (builds, counters, meta). Every use is visible advertising.
- **Premium = deep personal insight.** Things about *you* that keep getting better the more you play.
- **AI only where interpretation adds value.** Most features are pure data (zero AI cost). AI is reserved for coaching and summaries.
- **Shared caches first.** Data keyed by hero/rank is cached once for all users (analytics: 200 req/min per IP).

## Catalog

### Personal coaching (needs `/link`)
| Feature | Command | Data source | AI | Tier | When |
|---|---|---|---|---|---|
| Latest match coaching report | `/analyze` | `players/{id}/match-history`, match metadata, `analytics/player-stats/metrics` (hero + badge range, incl. `include_buff_metrics`) | Yes | Free (limited later) | Week 1 |
| Profile + rank | `/profile` | match history, `players/{id}/mmr-history` | No | Free | Week 1 |
| My heroes: win rate, KDA per hero vs hero average at my rank, pool suggestion | `/heroes` | `players/{id}/hero-stats`, `analytics/hero-stats` (bucket `avg_badge`) | No | Free | Week 2 |
| Best teammates (who I win most with) | `/mates` | `players/{id}/mate-stats` | No | Free | Week 2 |
| Solo vs party win rate | inside `/profile` | `players/{id}/party-stats` | No | Free | Week 2 |
| My items vs meta: items that underperform for me | `/items` | `players/{id}/item-stats`, `analytics/item-stats` | Optional | Premium | Week 2 |
| Compare with top-rank players on my hero | `/compare` | `analytics/player-stats/metrics`, `analytics/item-stats` with top badge range | Yes | Premium | Week 2 |
| Soul curve vs average (net worth over time) | web report chart | `analytics/player-performance-curve` | No | Premium | Week 2 |
| Where I die (death heatmap) | web report | `analytics/kill-death-stats` with `account_ids` | No | Premium | Later |
| Nemesis: opponents I lose to most | `/nemesis` | `players/{id}/enemy-stats` | No | Free (fun) | Later |
| Multi-match trends (last 10-20 games) | `/trends` | match history + metrics | Yes | Premium | Later |

### Hero knowledge (no link needed, great for growth)
| Feature | Command | Data source | AI | Tier | When |
|---|---|---|---|---|---|
| Meta build for a hero at a rank: item order by phase, ability order | `/build hero [rank]` | `analytics/item-flow-stats`, `analytics/ability-order-stats`, `analytics/hero-build-stats` | No | Free | Week 2 |
| How to beat a hero: worst matchups + items that win against it | `/counter hero` | `analytics/hero-counter-stats`, `analytics/item-stats` with `enemy_hero_ids` | Optional short summary | Free | Week 2 |
| Tier list by rank and patch | `/meta [rank]` | `analytics/hero-stats` | No | Free | Week 2 |
| Best hero pairs (synergies) | `/synergy hero` | `analytics/hero-synergy-stats` | No | Free | Later |
| Lane matchup duo vs duo | `/lane` | `analytics/lane-matchup-stats`, `lane-soul-curve` (marked "subject to change") | No | Free | Later, endpoint unstable |
| Draft helper: predicted win rate for a 6v6 | `/draft` | hero comb / synergy / counter stats | Optional | Premium | Later |

### Server and community
| Feature | Command | Data source | AI | Tier | When |
|---|---|---|---|---|---|
| Server leaderboard of linked members by rank | `/leaderboard server` | `players/mmr` (batch) | No | Free | Week 2 |
| Global / regional / hero leaderboard | `/leaderboard global` | `v1/leaderboard` | No | Free | Later |
| Patch digest auto-posted to a channel | scheduled | patches endpoint | Yes (summary) | Free | Later |
| "Match finished" highlight alerts for linked members | scheduled | match history polling | No | Premium (server) | Later, mind rate limits |
| Daily guessing minigame | `/daily` | assets + analytics | No | Free | Later |

## Notes
- deadlock-api.com also offers Deadlockdle, a team builder and a stream kit; we should not clone them, only use data that powers personal coaching or quick in-Discord answers.
- Lane endpoints are explicitly unstable; do not build on them before they stabilize.
- Buff (power-up) pickup timings exist only for matches since build 6712 (2026-09-29).
