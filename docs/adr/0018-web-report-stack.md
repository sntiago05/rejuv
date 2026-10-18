# ADR-0018: Web report stack: Spring MVC and Thymeleaf in the same application

- **Status**: Accepted
- **Date**: 2026-10-18

## Context
As decided in ADR-0012, each stored `/analyze` match result should be accessible via a public web page (`/r/{publicId}`) so users can share coaching reports outside Discord. The application currently runs as a headless Discord bot on Spring Boot 4.1 on an Oracle Cloud Always Free instance (ARM architecture, limited RAM, strict budget).

We need to choose how to serve these web reports without adding significant deployment overhead, complex frontend toolchains, or excessive memory pressure.

## Options considered
| Option | Pros | Cons |
|---|---|---|
| Spring MVC + Thymeleaf (monolith) | Zero frontend toolchain (no npm/vite/node); reuses existing services, models, caches and i18n messages; single deployable jar; minimal latency | Adds embedded Tomcat memory overhead (~30–50 MB); requires opening a web port; server-side rendering only |
| Separate SPA frontend (React/Svelte/Vue) + REST API | Highly interactive client-side rendering; separated concerns | Requires Node build step, separate deployment or static hosting, CORS setup, and duplicate API DTOs |
| Static HTML generation to object storage (S3/Cloudflare R2) | Zero web server overhead on the bot VM; infinitely scalable | Requires external storage credentials and bucket; complicated lifecycle management when accounts are unlinked |

## Decision
Serve web report pages directly from the existing Spring Boot application using Spring MVC and Thymeleaf (`spring-boot-starter-webmvc` and `spring-boot-starter-thymeleaf`). Pages are server-side rendered with pure HTML and a small hand-written CSS stylesheet (`report.css`), with zero JavaScript, external fonts, or CDNs.

The web server binds by default to loopback (`127.0.0.1:8080`), intended to sit behind a reverse proxy (Caddy / Task 036) for automatic HTTPS in production.

## Consequences
- **Build and operations**: No frontend build step or node runtime needed; `./mvnw verify` builds the complete application including templates and static assets.
- **Memory footprint**: Embedded Tomcat and Thymeleaf template cache add modest memory usage, remaining well within the VM memory budget.
- **Port and networking**: Application now requires an HTTP port (`WEB_PORT:8080`, bound to `127.0.0.1` by default). The bot continues to run identically even if the web server is idle.
- **Security & privacy**:
  - Each report is keyed by a cryptographically random, non-guessable UUID (`public_id`, 122 random bits).
  - All dynamic data is escaped via Thymeleaf `th:text`; `th:utext` is strictly avoided.
  - Strict security headers (`Content-Security-Policy`, `X-Robots-Tag: noindex, nofollow`, `Referrer-Policy: no-referrer`, `X-Content-Type-Options: nosniff`, `Cache-Control: private`) are enforced on all web responses.
  - No Discord IDs or Steam account IDs are exposed in the URL, page content, or logs.
  - Unlinking an account deletes its stored analyses, making its report pages return 404 immediately.
- **Future work**: Client-side interactive soul curve visualization (Task 018) and containerized deployment with Caddy reverse proxy (Task 036) will build upon this foundation.
