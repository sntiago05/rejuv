# ADR-0002: PostgreSQL for persistence, Caffeine for caching

- **Status**: Accepted
- **Date**: 2026-10-15

## Context
We need to store linked accounts, usage counts and analyses, and avoid hammering a community API.

## Options considered
| Option | Pros | Cons |
|---|---|---|
| PostgreSQL | Industry standard, great with Spring Data JPA | One more service to run |
| SQLite / H2 | Zero setup | Limits growth, weaker portfolio signal |
| Caffeine | Very fast, free, Spring Cache integration | Lost on restart |
| Redis | Persistent shared cache | Unneeded for a single instance |

## Decision
PostgreSQL + Caffeine.

## Consequences
- Revisit Redis only if we run multiple instances.
