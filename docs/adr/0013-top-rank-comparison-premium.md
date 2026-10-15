# ADR-0013: Top-rank comparison as a premium feature

- **Status**: Accepted (week 2, behind a feature flag)
- **Date**: 2026-10-15

## Context
Players want to know how the best players use their hero. deadlock-api supports filtering analytics by badge range, including the highest tiers. Pro tournament data (e.g. Deadlock Night Shift) is attractive but access is uncertain.

## Decision
Add `/compare` (premium): the player's latest match or recent average vs top-tier players on the same hero (item timings, souls/min, damage, deaths). Uses the same benchmark pipeline with a top badge range. Tournament/pro data stays in the parking lot until data access is verified.

## Consequences
- Reuses `MetricsService` and benchmark caching; low extra cost.
- Strong, easy-to-explain premium value ("see how top players do it").
