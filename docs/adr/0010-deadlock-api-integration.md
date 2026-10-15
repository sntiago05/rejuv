# ADR-0010: Hand-written client for deadlock-api with analytics benchmarks

- **Status**: Accepted
- **Date**: 2026-10-15

## Context
deadlock-api publishes an OpenAPI spec and auto-generated clients for many languages. The API is large (analytics, matches, players, assets). We need a handful of endpoints: match history, match metadata, rank/hero assets and `player-stats/metrics` for percentile benchmarks by hero and rank. Analytics endpoints share a 200 req/min per IP limit; busy queries return 503 with `Retry-After`.

## Options considered
| Option | Pros | Cons |
|---|---|---|
| Generated Java client | Complete, fast to start | Large dependency, exposes everything, less learning |
| Hand-written `RestClient` + minimal DTOs | Small, explicit, easy to test and cache | Must track API changes ourselves |

## Decision
Hand-written `DeadlockApiClient` with Spring `RestClient`, DTOs only for used fields (`@JsonIgnoreProperties(ignoreUnknown = true)`). Benchmarks come from `/v1/analytics/player-stats/metrics` filtered by `hero_ids` and badge range. Exact endpoint paths are confirmed against `https://api.deadlock-api.com/openapi.json` in task 004.

## Consequences
- Caffeine caches: assets 24 h, benchmarks 6 h, match history 2 min, profiles 10 min.
- On 503 honor `Retry-After` once, then fail gracefully.
- Never send parallel uncached analytics requests from one command.
- Consider an API key / supporting the project if traffic grows.
