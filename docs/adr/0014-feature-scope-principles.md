# ADR-0014: Feature scope principles (free utility, premium insight)

- **Status**: Accepted
- **Date**: 2026-10-15

## Context
deadlock-api exposes 40+ endpoints (player, match, analytics, leaderboard, patches). We could build dozens of features; we need rules for what to build, what to charge for and what to skip.

## Decision
- Free features are hero/meta utilities usable without linking (`/build`, `/counter`, `/meta`) plus light personal stats; they drive growth.
- Premium features are deep personal insights (`/compare`, item analysis, curves, trends, alerts).
- AI only for interpretation (coaching, summaries). Pure data features never call the model.
- Prefer endpoints whose results can be cached per hero/rank and shared by all users.
- Do not build on endpoints marked unstable; do not clone deadlock-api.com's own tools.

## Consequences
- Most of the feature set costs nothing to run.
- `features.md` is the single catalog; new ideas are added there with tier and data source before any task is written.
