# ADR-0011: Single-match analysis with structured AI output

- **Status**: Accepted
- **Date**: 2026-10-15

## Context
The coaching report is the product's differentiator. It must be specific, cheap and reliable.

## Decision
- MVP analyzes the latest match only, benchmarked against p25/p50/p75 for same hero and rank tier.
- Backend computes all numbers; the model only interprets them and must not invent metrics.
- Model returns strict JSON (`strength`, `biggest_leak`, `next_game_action`, `key_numbers`) mapped to a Discord embed.
- One analysis per (account, match), stored and reused.
- Fallback without AI if the model fails twice.

## Consequences
- Predictable cost and layout; easy to test the mapping.
- Multi-match trends become a premium feature later.
