# Task 017: Public web report page and "View full report" button

## Status

Implemented on `feature/017-web-report`. `.\mvnw.cmd clean verify` passes with **698 tests** (0 failures, 0 errors, 2 skipped benchmark tests). Not pushed, not merged.

## Summary

- **Web Server & Dependencies**: Added `spring-boot-starter-webmvc` and `spring-boot-starter-thymeleaf` to `pom.xml`. Removed `spring.main.web-application-type: none` from `application.yaml` and configured `server.port: ${WEB_PORT:8080}`, `server.address: ${WEB_BIND_ADDRESS:127.0.0.1}`, `server.error.include-stacktrace: never`, `spring.thymeleaf.cache: true`, and `rejuv.web.base-url: ${WEB_BASE_URL:}`. Added corresponding environment variables to `.env.example`.
- **Database Migration (V4)**: Created Flyway migration `src/main/resources/db/migration/V4__add_match_analysis_public_id.sql` adding `public_id UUID NOT NULL DEFAULT gen_random_uuid()` and unique constraint `uq_match_analysis_public_id` on `match_analysis`. Existing rows are backfilled automatically with non-guessable random UUIDs.
- **Domain & Repository**:
  - Added `public_id` field to `domain/MatchAnalysis` (initialized via `UUID.randomUUID()` in the public constructor; JPA reflection for existing entities) and getter `getPublicId()`.
  - Added `findByPublicId(UUID publicId)` to `repositories/MatchAnalysisRepository`.
  - Added `UUID publicId` component to `domain/AnalysisReport`, retaining backward-compatible 8-arg and 9-arg constructors.
- **Analyze Service**: Updated `services/AnalyzeService` to populate `publicId` across all construction sites: stored row, fresh save, and concurrent duplicate save (falling back to `entity.getPublicId()`). The fallback outcome (when AI fails and no analysis is stored) sets `publicId = null`.
- **Web Properties**: Created `config/WebProperties` (`@ConfigurationProperties("rejuv.web")`) with `enabled()` helper returning true when `baseUrl` is non-blank.
- **Report Domain & Service**:
  - Created `domain/ReportView` containing match display metadata and coaching analysis without exposing any Steam account ID or Discord user ID.
  - Created `services/ReportService` providing `find(UUID publicId)` and `chartPng(UUID publicId)`. Handles corrupted JSON gracefully (logs WARN with public ID only, returns empty). Fetches cached match telemetry via `matchMetadataSource.getMatch(matchId)`; on `RuntimeException`, logs one WARN with the exception class and gracefully degrades (omits duration, build, and chart) without throwing. Makes zero AI calls, records no usage, and does not invoke usage checks.
- **Web Controller & Security**:
  - Created `web/ReportController` exposing `GET /r/{publicId}` and `GET /r/{publicId}/chart.png`.
  - Implemented required HTTP security headers on both routes:
    - `X-Robots-Tag: noindex, nofollow`
    - `Content-Security-Policy: default-src 'none'; img-src 'self'; style-src 'self'; base-uri 'none'; frame-ancestors 'none'`
    - `Referrer-Policy: no-referrer`
    - `X-Content-Type-Options: nosniff`
    - `Cache-Control: private, max-age=300`
  - Returns HTTP 404 with view `report-not-found` for invalid UUID strings or missing reports, without echoing user input or leaking stack traces. Returns HTTP 404 with empty body for missing chart images.
- **Templates & Styling**:
  - Created `src/main/resources/templates/report.html` and `src/main/resources/templates/report-not-found.html` using Thymeleaf.
  - Strict security adherence: all dynamic text rendered via `th:text` or `#{...}`; no `th:utext`; `<meta name="robots" content="noindex,nofollow">` and mobile viewport included; no external scripts, CDNs, or fonts.
  - Created hand-written CSS in `src/main/resources/static/report.css` providing a clean, single-column, dark-mode responsive layout.
- **Discord Button Integration**: Modified `commands/AnalyzeCommand` to inject `WebProperties`. When web links are enabled (`baseUrl` non-blank) and the report has a non-null `publicId`, appends a link button (`Button.link(url, messages.get("analyze.report_button"))`) to the reply embed. When web links are disabled or `publicId` is null, the embed is unchanged.
- **Unlinking Behavior**: Unlinking an account (`/unlink`) already deletes all stored analyses for that account from `match_analysis`. As a result, public web report pages (`GET /r/{publicId}`) for unlinked accounts automatically return HTTP 404 without requiring additional unlinking code.
- **ADR-0018**: Created `docs/adr/0018-web-report-stack.md` documenting the architecture decision for Spring MVC + Thymeleaf in the same application.

## Important Files

- `pom.xml` (modified: added `spring-boot-starter-webmvc` and `spring-boot-starter-thymeleaf`)
- `src/main/resources/application.yaml` (modified: server port, address, thymeleaf cache, web base-url)
- `.env.example` (modified: added `WEB_PORT=`, `WEB_BIND_ADDRESS=`, `WEB_BASE_URL=`)
- `src/main/resources/db/migration/V4__add_match_analysis_public_id.sql` (created: migration for `public_id`)
- `src/main/java/sntiago05/rejuv/domain/MatchAnalysis.java` (modified: added `publicId` column and getter)
- `src/main/java/sntiago05/rejuv/repositories/MatchAnalysisRepository.java` (modified: added `findByPublicId`)
- `src/main/java/sntiago05/rejuv/domain/AnalysisReport.java` (modified: added `publicId` component)
- `src/main/java/sntiago05/rejuv/services/AnalyzeService.java` (modified: filled `publicId` at construction sites)
- `src/main/java/sntiago05/rejuv/config/WebProperties.java` (created: configuration properties record)
- `src/main/java/sntiago05/rejuv/domain/ReportView.java` (created: public report presentation record)
- `src/main/java/sntiago05/rejuv/services/ReportService.java` (created: report view and chart retrieval service)
- `src/main/java/sntiago05/rejuv/web/ReportController.java` (created: MVC web controller)
- `src/main/java/sntiago05/rejuv/commands/AnalyzeCommand.java` (modified: added link button)
- `src/main/resources/messages.properties` (modified: added report keys and analyze button label)
- `src/main/resources/templates/report.html` (created: Thymeleaf report template)
- `src/main/resources/templates/report-not-found.html` (created: Thymeleaf 404 template)
- `src/main/resources/static/report.css` (created: responsive dark theme CSS)
- `docs/adr/0018-web-report-stack.md` (created: architecture decision record)
- `src/test/java/sntiago05/rejuv/services/ReportServiceTest.java` (created: 8 tests)
- `src/test/java/sntiago05/rejuv/web/ReportControllerTest.java` (created: 6 tests)
- `src/test/java/sntiago05/rejuv/commands/AnalyzeCommandTest.java` (modified: +3 tests for button logic)
- `src/test/java/sntiago05/rejuv/FlywayMigrationTest.java` (modified: +2 assertions for V4 migration)
- `src/test/java/sntiago05/rejuv/repositories/MatchAnalysisRepositoryTest.java` (modified: +3 tests for public_id column and query)
- `src/test/java/sntiago05/rejuv/services/AnalyzeServiceTest.java` (modified: +1 test for publicId populating)
- `docs/task-reports/017-web-report.md` (created: task report)

## Decisions

- **In-process Spring MVC + Thymeleaf**: As documented in ADR-0018, rendering web pages within the same Spring Boot JVM avoids managing an external frontend stack (Node, Next.js, etc.) or extra build tooling, while sharing existing cache infrastructure (`GameAssets`, `MatchMetadataSource`, `ChartRenderer`).
- **Privacy & Safety**: `ReportView` explicitly omits Discord user IDs and Steam account IDs. Log statements in `ReportService` and `ReportController` never include account or Discord identifiers. Thymeleaf uses HTML escaping on all dynamic fields (`th:text`), preventing XSS vulnerabilities. Strict security headers (CSP, noindex, no-referrer, nosniff) protect against indexing and framing.
- **Non-guessable Public IDs**: Stored records use PostgreSQL `gen_random_uuid()` (122 random bits), preventing sequential enumeration attacks.
- **Fail-safe Degradation**: If match metadata is unavailable when rendering a web report, `ReportService` gracefully degrades (omitting build, duration, and chart) rather than failing the entire request.
- **Spring Boot Context MockMvc Testing**: Due to `spring-boot-starter-test` package organization in Spring Boot 4.1.1, `ReportControllerTest` uses `@SpringBootTest` with Testcontainers PostgreSQL and `@MockitoBean ReportService` configured via `MockMvcBuilders.webAppContextSetup(context).build()`, testing the full web context, filters, headers, and view resolution without requiring extra test starter dependencies.

## Verification

- **Baseline before changes**: 675 tests passed on `main`.
- **Targeted Test Suites**:
  - `.\mvnw.cmd test "-Dtest=ReportServiceTest"`: 8 tests, 0 failures.
  - `.\mvnw.cmd test "-Dtest=ReportControllerTest"`: 6 tests, 0 failures.
  - `.\mvnw.cmd test "-Dtest=AnalyzeCommandTest"`: 43 tests, 0 failures.
  - `.\mvnw.cmd test "-Dtest=AnalyzeServiceTest"`: 25 tests, 0 failures.
  - `.\mvnw.cmd test "-Dtest=FlywayMigrationTest"`: 4 tests, 0 failures.
  - `.\mvnw.cmd test "-Dtest=MatchAnalysisRepositoryTest"`: 7 tests, 0 failures.
  - `.\mvnw.cmd test "-Dtest=MessagesIntegrationTest"`: 5 tests, 0 failures.
  - `.\mvnw.cmd test "-Dtest=RejuvApplicationTests"`: 1 test, 0 failures.
- **Full Build Verification**:
  - Command: `.\mvnw.cmd clean verify`
  - Result: **BUILD SUCCESS**
  - Total tests run: **698 tests** (0 failures, 0 errors, 2 skipped benchmark tests). (+23 tests over 675 baseline).
- **Manual Verification Record**:
  - Live Discord bot interaction (/analyze button click on Discord) was not run because no Discord application bot token is configured in the environment.
  - Simulated controller invocations via `MockMvc` thoroughly verified HTTP 200 with dynamic HTML escaping, HTTP 404 for malformed UUIDs, HTTP 404 for unknown UUIDs, chart PNG streaming, and all required security headers (`X-Robots-Tag`, `Content-Security-Policy`, `Referrer-Policy`, `X-Content-Type-Options`, `Cache-Control`).

## Deviations

None. All interfaces, routes, security headers, database migration, and templates follow the specification.

## Known Issues

- Spring Boot startup displays an informative log warning: `spring.jpa.open-in-view is enabled by default`. This default behavior does not break functionality, but can be explicitly set in `application.yaml` (`spring.jpa.open-in-view: false`) in a future configuration cleanup if desired.

## Merge Risks

- `AnalyzeService.java` was modified to populate `publicId`. If Task 029 is merged to `main` concurrently, review `AnalyzeService.java` to ensure constructor calls pass `publicId`.
- Merge conflicts in `messages.properties` should be resolved by keeping keys from both branches.

## PROJECT_CONTEXT update pending integration

The following facts are ready to be integrated into `docs/PROJECT_CONTEXT.md` once merged:
- **Web Server**: Spring Boot now runs an embedded web server (Tomcat via `spring-boot-starter-webmvc`) bound to `127.0.0.1:8080` by default (configurable via `WEB_PORT`, `WEB_BIND_ADDRESS`, `WEB_BASE_URL`).
- **Web Reports**: Route `GET /r/{publicId}` renders a server-side Thymeleaf report page for an analysis using its UUID; route `GET /r/{publicId}/chart.png` serves the match chart image. Strict security headers (`CSP`, `X-Robots-Tag`, `Referrer-Policy`, `X-Content-Type-Options`, `Cache-Control`) are enforced on both routes.
- **V4 Migration**: Adds `public_id UUID NOT NULL DEFAULT gen_random_uuid()` and unique constraint `uq_match_analysis_public_id` to `match_analysis`.
- **Button in `/analyze`**: When `WEB_BASE_URL` is configured, `/analyze` embeds feature a "View full report" link button pointing to the web report URL.
- **Unlinking**: Unlinking an account deletes its stored analyses, automatically causing `/r/{publicId}` to return HTTP 404.
