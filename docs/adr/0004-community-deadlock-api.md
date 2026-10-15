# ADR-0004: Use the community deadlock-api.com

- **Status**: Accepted
- **Date**: 2026-10-15

## Context
Valve offers no official Deadlock API. deadlock-api.com provides match history, player stats and static assets (heroes, items).

## Decision
Use deadlock-api.com through a single `DeadlockApiClient`.

## Consequences
- It is unofficial and may change or rate-limit: cache aggressively, handle errors gracefully, isolate it behind the client interface.
- Consider supporting the project if the bot grows.
- The client abstraction makes porting to other games (e.g. Riot API for LoL) easier.
