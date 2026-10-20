# ADR-0019: Free with donations

- **Status**: Accepted
- **Date**: 2026-10-19

## Context
ADR-0006 planned freemium with Patreon and a Discord role check to fund operations. The owner decided to run Rejuv free for all players rather than gating features behind a paid subscription.

## Options considered
| Option | Pros | Cons |
|---|---|---|
| Freemium with Patreon (ADR-0006) | Recurring subscription revenue | Complex setup, gated community, Patreon payout friction in Colombia |
| Free with optional donations | Accessible to all players, zero payment code, simpler user experience | AI cost is borne entirely by the owner, dependent on voluntary support |

## Decision
Run Rejuv completely free for everyone with no usage limits and no premium tier. Project support comes from an optional configurable donation link (task 050). The `/items` and `/compare` commands are open to all linked users, and all premium gating and Patreon role-checking code is removed (task 051).

## Consequences
- AI coaching and analysis costs are paid by the owner and bounded only by deadlock-api and Anthropic rate limits.
- There is currently no per-user cooldown (a known limitation noted in `PROJECT_CONTEXT.md`); introducing a future cooldown or budget cap is deferred to a separate task.
- ADR-0006 (freemium with Patreon) and ADR-0013 (top-rank comparison premium) are superseded in practice.
- The `analysis_usage` table and weekly tracking are eliminated, simplifying the schema and command set (`/usage` removed).
