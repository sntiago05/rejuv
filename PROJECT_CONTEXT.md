# Project context: Rejuv, an AI coach for Deadlock

Compact summary for the Claude Project knowledge base. Full detail lives in `docs/` in the repo.

## Who
Backend student (Spring Boot, Riwi Colombia, paused systems engineering degree), intermediate level, 15-30 h/week, budget under 100,000 COP. Plays Deadlock and LoL. Goals: earn online income, build a strong portfolio, learn AI-assisted development. Explain important decisions; chat explanations in Spanish; everything produced (code, docs, commits, bot UI) in English.

## Product
Rejuv: English-language Discord bot that coaches Deadlock players with AI analysis of their matches. Differentiator vs stats sites: specific, personal, actionable AI advice + social server features. Built i18n-ready (Spanish can be added later).
- MVP commands: `/link`, `/unlink`, `/profile`, `/analyze`, `/help` (full spec in `docs/mvp-spec.md`). `/analyze` = latest match vs p25/p50/p75 of same hero + rank tier, Haiku returns strict JSON (strength, biggest leak, next-game action, key numbers). Free limit 3/week starts in week 2, behind a feature flag.
- Premium (~2 USD/month/user, week 2, behind a feature flag): unlimited analyses, progress tracking, builds, server rankings.
- Feature catalog in `docs/features.md` (ADR-0014): free hero utilities without linking (`/build`, `/counter`, `/meta`), social (`/heroes`, `/mates`, `/leaderboard server`); premium personal insight (`/compare`, `/items`, soul curve, later heatmap/trends/alerts). AI only for interpretation.
- Surfaces: Discord bot first; week 2 adds public web report pages linked from each analysis (ADR-0012). Premium `/compare` vs top-rank players on same hero (ADR-0013).
- Monetization: Patreon assigns `Premium` role in official support server; bot checks role by user ID. Launch free first month to validate.

## Stack (ADRs 0001-0005)
Java 21, Spring Boot 4.1 (no web server initially), JDA 5, PostgreSQL + Spring Data JPA, Caffeine cache, deadlock-api.com (community, unofficial), Claude Haiku 5.5 `claude-haiku-5-5` (low effort, capped max_tokens, only metric summaries sent), Docker Compose on Oracle Cloud Always Free ARM.

## Architecture
Packages: `commands` (no logic) → `services` → `clients` (cached external APIs) / `repositories`; plus `domain`, `config`.
`/analyze`: deferReply → usage check → fetch matches (cached) → compute metrics → Haiku → store → English embed.

## Costs
Initial ~20,000 COP (5 USD API credits). ~2 COP per analysis. Patreon fee assumed 12%. Spend limit set in Claude Console.

## Workflow (ADR-0009)
Claude Project = architect: designs, writes task specs in `docs/tasks/`, reviews diffs. Antigravity = main implementer, follows task specs. Claude Code = reserved for hard tasks (complex bugs, AI analysis service). One agent per branch, git worktrees, human review before merge. `AGENTS.md` single source of rules, `CLAUDE.md` imports it, skills mirrored in `.claude/skills/` and `.agents/skills`. Docs and ADRs in English, updated with code. herdr postponed.

## Current status
Fast track: MVP built and launched in week 1 (day-by-day plan in `docs/roadmap.md`), growth features + web reports + premium built in week 2 (premium behind a feature flag until ~30 weekly active users). Plan: tasks 001-010 in `docs/implementation-plan.md`. AGENTS.md, Antigravity rule, Claude Code settings and the `new-discord-command` skill are done. Next: Antigravity implements task 001 (skeleton + `/ping`). Risks: deadlock-api data coverage gaps, many competing tracker sites. ADRs 0001-0015. Main competitor bot: Deadlock Assistant (600+ servers, stats/meta/counters/builds, no AI coaching) → our edge is coaching. Tasks 001-021. Budget: near zero, passion project; only spend is capped Claude API credits (+ domain in week 2).
