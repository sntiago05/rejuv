# ADR-0020: Tailwind CSS 4 build-time CLI for web reports

- **Status**: Accepted
- **Date**: 2026-10-20

## Context
ADR-0018 established that web coaching reports (`/r/{publicId}`) are rendered on the server with Spring MVC and Thymeleaf, zero JavaScript, strict CSP (`default-src 'none'; img-src 'self'; style-src 'self'`), and a hand-written stylesheet (`report.css`). As the report expanded in Task 049 with multi-column responsive cards, collapsible sections, and data visualizations, maintaining hand-written CSS became repetitive and error-prone across light/dark themes and mobile viewports.

We want modern utility-first CSS via Tailwind CSS v4 without introducing a Node.js runtime dependency, without breaking `./mvnw verify`, without complicating Docker builds on Oracle Cloud ARM64, and without violating ADR-0018's strict Content Security Policy.

## Options considered
| Option | Pros | Cons |
|---|---|---|
| **Tailwind CSS v4 Standalone CLI (Chosen)** | Single native binary; zero Node/npm/npx dependencies; fast compilation (~50ms); supports Linux x64 and ARM64; generated `static/report.css` is committed so `./mvnw verify` and Dockerfile require no extra tools; verified via SHA-256 | Requires committing generated CSS asset and adding a CI verification script to check that committed CSS matches source |
| **Maven Plugin with Node/Tailwind (frontend-maven-plugin)** | Integrates directly into `mvn compile`; guaranteed regeneration on every build | Downloads Node.js and npm during build; drastically slows `./mvnw verify`; brittle on ARM64 and offline/restricted network environments in Docker; adds heavy moving parts |
| **CDN Tailwind (Play CDN / script tag)** | Zero build steps; instant prototyping | Strictly prohibited by ADR-0018 CSP (`default-src 'none'`); requires client-side JS runtime; unminified in production; introduces external third-party network dependency |
| **Separate React/Vue SPA + REST API** | Rich client-side interactivity; decoupled frontend | Requires Node/npm build chain, separate hosting or reverse proxy routing, CORS configuration, duplicate API DTOs, and violates ADR-0012 / ADR-0018 |

## Decision
Adopt Tailwind CSS v4 via its official standalone CLI binary (pinned version `v4.3.3`, verified with SHA-256 for both x64 and arm64 Linux architectures).

The CSS source lives in `src/main/tailwind/report.css`. A developer or CI script (`scripts/build-css.sh`) compiles and minifies this into `src/main/resources/static/report.css`, which is committed to git. CI verifies that the committed CSS is strictly up to date with `scripts/build-css.sh --check`.

Charts remain standalone SVG endpoints served as `<img>` tags by `ReportController`. Inline SVGs remain rejected because they would require Thymeleaf `th:utext` (strictly banned by ADR-0018) and a relaxed CSP. Inline SVG may be revisited in the future if safe HTML-sanitized SVG rendering is introduced.

## Consequences
- **Build and operations**: Zero new Maven dependencies or build plugins in `pom.xml`. `./mvnw verify` and Docker packaging remain completely unchanged, fast, and self-contained.
- **Security and CSP**: The Content Security Policy remains byte-identical (`default-src 'none'; img-src 'self'; style-src 'self'; base-uri 'none'; frame-ancestors 'none'`). Zero client-side scripts, zero inline `style=` attributes, and zero third-party font or CDN requests.
- **Developer workflow**: Changes to `src/main/tailwind/report.css` or template classes require running `scripts/build-css.sh` before committing. CI fails immediately if the committed `report.css` is stale.
- **Portability**: The standalone CLI supports both the Linux x64 development machines and Oracle Cloud ARM64 environments with deterministic SHA-256 checksum verification.
