# ADR-0005: Docker Compose on Oracle Cloud Always Free

- **Status**: Accepted
- **Date**: 2026-10-15

## Context
The JVM needs more RAM than typical free tiers give. Budget is under 100,000 COP.

## Options considered
| Option | Pros | Cons |
|---|---|---|
| Oracle Cloud Always Free (ARM) | Permanently free, plenty of RAM | Card verification, regional capacity issues |
| Railway / Render free | Easy deploys | Too little memory or sleeping instances |
| Local PC | Free | Not suitable for production |

## Decision
Bot + PostgreSQL in Docker Compose, deployed to an Oracle Cloud ARM instance.

## Consequences
- Same setup locally and in production.
- Docker images must support ARM64.
