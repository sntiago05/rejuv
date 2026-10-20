# Roadmap (fast track: 2 weeks to launch everything)

Passion project, near-zero budget, AI-assisted development with high weekly dedication. Coding is compressed into days; what cannot be compressed is real user feedback, so features are built early but some are switched on only when the data says so.

## Week 1: build and launch the MVP

| Day | Work | Tasks |
|---|---|---|
| 1 | Setup (`setup-checklist.md`), AGENTS.md, skeleton, i18n, DB | 001, 002, 003 |
| 2 | Deadlock client, `/link`, `/unlink`, `/profile` | 004, 005, 006 |
| 3 | Metrics, AI client, `/analyze` | 007, 008, 009 |
| 4 | Testing with 3-4 players, prompt tuning, deploy to Oracle Cloud | 010, deploy |
| 5 | Public launch: support server, global commands, invite link, Reddit/Discord posts | launch |

**Gate before day 5 launch:** at least 3 testers say the report taught them something. If not, spend day 5 improving the analysis instead of launching.

## Week 2: growth features, web reports and premium (premium removed by 051)

| Day | Work | Tasks |
|---|---|---|
| 6 | Hero utilities, no link needed: `/build`, `/counter`, `/meta` | 011, 012, 013 |
| 7 | Social and personal: `/heroes`, `/mates`, party stats in `/profile`, `/leaderboard server` | 014, 015, 016 |
| 8-9 | Web report pages + "View full report", soul curve chart, domain + HTTPS (ADR-0012) | 017, 018 |
| 10 | Premium pack behind a feature flag: `/compare`, `/items`, usage limits, Patreon role check (built, then removed by 051 under ADR-0019 free model; `/compare` and `/items` remain free) | 019, 020, 021 |

*Note: The planned premium gate and tiers were dropped in task 051 in favor of the free model (ADR-0019).*

Full feature catalog with tiers and data sources: `features.md`.

## What to measure from day 5
- Linked users, `/analyze` calls per day
- % of `/analyze` calls that found the latest match (data coverage)
- Users who come back after 7 days
- Feedback in the support server

## Risks of the fast track
- Claude Pro usage limits: heavy days will hit them; keep implementation in Antigravity (ADR-0009) and planning chats short.
- Quality over speed: a weak first impression on launch day is hard to undo. Respect the day-4 gate.
- Riwi workload: if a day slips, slip the plan, not the gates.

## Later (parking lot)
See the "Later" rows in `features.md`, plus:
- Multi-match trends and weekly progress DMs
- Spanish via i18n
- Esports / Night Shift pro builds (research data access first)
- League of Legends version
- Discord native app subscriptions (check availability in Colombia)
- GraalVM native image
