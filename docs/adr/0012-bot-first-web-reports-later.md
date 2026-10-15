# ADR-0012: Discord bot first, web report pages in week 2

- **Status**: Accepted (implementation details Proposed, confirm in week 2)
- **Date**: 2026-10-15

## Context
The product could be a Discord bot or a website. Many free stats websites already exist (deadlock-api.com, Statlocker, Mobalytics, Tracker.gg); competing for search traffic is hard. Discord gives built-in distribution in communities and needs little frontend work. A website is easier to share and showcases full-stack skills.

## Options considered
| Option | Pros | Cons |
|---|---|---|
| Bot only | Fastest, distribution via servers, backend-focused | Reports trapped in Discord |
| Website only | Shareable, portfolio-friendly | Hard to get traffic, frontend effort, direct competition with stats sites |
| Bot first, web report pages later | Bot brings users, links spread the product | Two surfaces to maintain |

## Decision
Ship the bot first. In week 2 each analysis gets a public report page linked from the embed ("View full report").

Proposed implementation for week 2:
- Same Spring Boot app serves server-rendered pages (Thymeleaf) at `/r/{publicId}` (random, non-guessable id); no separate frontend deploy, no CORS.
- Caddy reverse proxy on the Oracle VM for automatic HTTPS; cheap domain bought in week 2.
- Charts rendered client-side with a small library from a CDN.

## Consequences
- Services must stay Discord-agnostic (already true by layering).
- Enabling the web server changes `spring.main.web-application-type` and adds memory; re-check limits.
- Every shared link is free marketing.
