# Task 017: Web server, public report page `/r/{publicId}` and "View full report" button

- **Branch**: `feature/017-web-report`
- **Implementer**: Claude Code (the plan assigns it; it changes the application type and adds dependencies)
- **Related ADRs**: ADR-0012 (bot first, server-rendered report pages at `/r/{publicId}`), ADR-0001 (Spring Boot), ADR-0005 (Docker Compose deployment), ADR-0008 (English, i18n-ready). **This task needs a new ADR** (see Documentation).

## Goal
Every stored AI analysis gets a public, non-guessable report page served by the same Spring Boot app, and the `/analyze` embed gets a link button "View full report" that opens it. The page is read-only, shows the same coaching content as the embed in a shareable layout, and exposes no Discord or account identifier.

## Context (verified from the repository when this spec was written, 2026-10-17)
Do not repeat this discovery. If a fact below is false in your checkout, stop and ask.

- Plan: `docs/implementation-plan.md` task 017 depends on 009 (merged). **Merge order matters:** Task 025 changes `AnalyzeService.analyze` and Task 019 adds Flyway `V3`; start this branch from the latest `origin/main` **after 025 and 019 are merged**, and use `V4`. If they are not merged, stop and ask. Baseline: measure `./mvnw clean verify` before your first change.
- The app is a non-web application today: `src/main/resources/application.yaml` has `spring.main.web-application-type: none`; `pom.xml` has no web or template starter (starters present: `spring-boot-starter`, data-jpa, flyway, validation, cache, restclient; Boot 4.1, Java 21).
- `match_analysis` (V2): `id, account_id, match_id, hero_id, result_json, model, input_tokens, output_tokens, created_at`, unique `(account_id, match_id)`; entity `domain/MatchAnalysis`, `repositories/MatchAnalysisRepository`. `AnalysisResult` (strength, biggestLeak, nextGameAction, keyNumbers) is stored as JSON in `result_json`. `AnalysisReport(heroId, hero, rank, result, durationMin, analysis, metrics, degraded)` is what the command renders; only fresh, non-fallback AI analyses are stored. `LinkService.unlink` already deletes the stored analyses of an account that is no longer linked by anyone (so the page of an unlinked account disappears: keep that behavior).
- `AnalyzeCommand` replies with `hook.editOriginalEmbeds(embed)`; no components are used anywhere yet. `commands/EmbedStyle` has the brand color `0xE5A93C`.
- Rules from `AGENTS.md`: no JDA types in `services/`, no business logic in `commands/`, user-facing text in `messages.properties` (page texts too, see below), Flyway only for schema changes, no secrets in git. **New dependencies and an architecture change are authorized only by this spec.**

## Step 0: verify (before writing code)
1. Dependencies: with Boot 4.1 the web and template starter artifact ids may differ from Boot 3 (`spring-boot-starter-web` / `-thymeleaf`). Confirm the correct artifact ids in the Spring Boot 4.1 BOM or documentation, add them **without a version**, and record the ids and the resolved versions in the report. Use the **servlet** stack (Spring MVC) with Thymeleaf. If the template starter is not available for Boot 4.1, stop and ask (the alternative is plain HTML built in Java).
2. JDA: confirm in JDA 5.6.1 (`javap`/sources) the API for a link button and for attaching it to an edit: `Button.link(String url, String label)`, `ActionRow.of(...)`, `WebhookMessageEditAction.setComponents(...)`/`setActionRow(...)`. Record the signatures in the report.
3. Startup: after the change, `./mvnw clean verify` must still pass (every `@SpringBootTest` context now starts a web environment) and the bot must still start. Record memory use at idle if you can measure it (ADR-0012 asks to re-check limits).

## Files to create or modify
| File | Change |
|---|---|
| `docs/adr/0017-web-report-pages.md` | **Create.** New ADR (authorized exception to "never edit ADRs": new file only). Use `docs/adr/0000-template.md`. |
| `pom.xml` | **Modify.** Only the two starters from Step 0 (and nothing else). |
| `src/main/resources/application.yaml` | **Modify.** `spring.main.web-application-type: servlet`, `server.port: ${PORT:8080}`, and a `rejuv.web` block (see signatures). Nothing else. |
| `.env.example` | **Modify.** Add `REPORT_BASE_URL=` and `PORT=8080` (no real values). |
| `src/main/resources/db/migration/V4__match_analysis_public_report.sql` | **Create.** |
| `src/main/java/sntiago05/rejuv/domain/MatchAnalysis.java` | **Modify.** New fields and accessors for the new columns (see signatures). |
| `src/main/java/sntiago05/rejuv/repositories/MatchAnalysisRepository.java` | **Modify.** Add `findByPublicId`. |
| `src/main/java/sntiago05/rejuv/domain/AnalysisReport.java` | **Modify.** Add `String publicId` (null when the analysis is not stored). |
| `src/main/java/sntiago05/rejuv/services/AnalyzeService.java` | **Modify.** Create the public id and the display columns when saving; return `publicId` in every `AnalysisReport`. Nothing else. |
| `src/main/java/sntiago05/rejuv/config/WebProperties.java` | **Create.** Properties record. |
| `src/main/java/sntiago05/rejuv/config/WebConfig.java` | **Create.** Registers `WebProperties`; security headers filter. |
| `src/main/java/sntiago05/rejuv/services/ReportService.java` | **Create.** |
| `src/main/java/sntiago05/rejuv/domain/ReportView.java` | **Create.** Record. |
| `src/main/java/sntiago05/rejuv/web/ReportController.java` | **Create.** New package `web/` for HTTP endpoints (**architecture change, recorded in the ADR**; update nothing else in `docs/architecture.md`). |
| `src/main/resources/templates/report.html` | **Create.** |
| `src/main/resources/templates/report-not-found.html` | **Create.** |
| `src/main/java/sntiago05/rejuv/commands/AnalyzeCommand.java` | **Modify.** Add the link button when a public id and a base URL exist. |
| `src/main/resources/messages.properties` | **Modify.** Add the `report.*` block at the **end of the file** (page texts and button label). |
| `src/test/java/sntiago05/rejuv/services/ReportServiceTest.java` | **Create.** |
| `src/test/java/sntiago05/rejuv/web/ReportControllerTest.java` | **Create.** |
| `src/test/java/sntiago05/rejuv/repositories/MatchAnalysisRepositoryTest.java` | **Modify.** Public id uniqueness and lookup. |
| `src/test/java/sntiago05/rejuv/services/AnalyzeServiceTest.java` | **Modify.** `publicId` behavior. |
| `src/test/java/sntiago05/rejuv/commands/AnalyzeCommandTest.java` | **Modify.** Button present or absent. |
| `docs/task-reports/017-web-report.md` | **Create.** Task report (format of `docs/task-reports/014-heroes.md`). |

No Caddy, domain, TLS or Docker Compose change (Task 018). If you think another file is needed, stop and ask.

## Interfaces / signatures

### Migration `V4__match_analysis_public_report.sql`
Add to `match_analysis`: `public_id varchar(32)`, `result varchar(8)`, `duration_min double precision`, `rank_label varchar(64)`. Backfill `public_id` for existing rows with a random 32-hex value (PostgreSQL 17: `replace(gen_random_uuid()::text, '-', '')`), then `alter ... set not null` and add a unique constraint `uq_match_analysis_public_id`. `result`, `duration_min` and `rank_label` stay nullable (old rows have none; the page omits what is missing). Never edit it after it is applied.

### Public id
128 bits from `java.security.SecureRandom`, rendered as 32 lowercase hex characters, generated in `AnalyzeService` when it saves a new row (a collision is practically impossible; the unique constraint is the safety net). The id never appears in logs at INFO next to an account or Discord id.

### Properties
```java
@ConfigurationProperties("rejuv.web")
public record WebProperties(String baseUrl) { }   // rejuv.web.base-url: ${REPORT_BASE_URL:}
```
Blank base URL = feature off for the button (the page still works). A non-blank value must be an `https` URL (or `http://localhost...` for development) and is used without a trailing slash; reject anything else at startup with a clear message.

### `services/ReportService` (new) and `domain/ReportView` (new)
```java
public record ReportView(String heroName, String result, Double durationMin, String rankLabel,
                         Instant createdAt, AnalysisResult analysis) { }
public Optional<ReportView> findByPublicId(String publicId)
```
`publicId` that is not exactly 32 lowercase hex characters returns empty **without touching the database**. Hero name comes from `GameAssets.heroNames()` (null-safe, falls back to `#<heroId>` text from the catalog). Unparseable `result_json` returns empty and logs WARN with the row id only.

### `web/ReportController`
`GET /r/{publicId}` -> HTML (`report.html`) with status 200, or `report-not-found.html` with status 404. No other route, no listing, no JSON API, no write endpoint. Responses carry `X-Robots-Tag: noindex, nofollow`, `Referrer-Policy: no-referrer`, `X-Content-Type-Options: nosniff` and a `Content-Security-Policy` that allows only same-origin and inline styles used by the template (no external scripts: the soul curve chart and any CDN script belong to Task 018).

## Behavior
- Page: hero, result, duration, rank (when present), date (UTC), then `Key Strength`, `Biggest Leak`, `Next Game Action` and the key numbers, plus a footer stating it was generated by Rejuv from deadlock-api data. All text comes from `messages.properties` (`report.*`), all dynamic values are escaped by Thymeleaf (`th:text`, never `th:utext`). **No Discord id, account id, match id or username is rendered.** Responsive and readable on a phone, dark and light friendly, no external fonts or scripts.
- `/analyze`: when the reply has a `publicId` **and** `WebProperties.baseUrl()` is not blank, attach one link button (label `report.button`, URL `<baseUrl>/r/<publicId>`) with `ActionRow`. Otherwise no components. Stored analyses reopened later get the same button (same public id). Degraded or fallback replies are not stored, so they have no button.
- Unlinking deletes the stored analyses (existing behavior), so the page answers 404 afterwards. Add a test for that through the repository delete path or document in the report why it is covered.
- Memory and startup: the app still starts the bot; the web server is the only new listener (`PORT`, default 8080); no public exposure is configured by this task.

### User-facing text (English)
`report.button`, `report.title`, `report.heading`, `report.field.strength`, `report.field.leak`, `report.field.next_action`, `report.field.key_numbers`, `report.meta`, `report.footer`, `report.not_found.title`, `report.not_found.body`. Templates read them through a `Messages`-backed model attribute or Spring's message source (use the existing message source, no second one).

## Tests
JUnit 5, AssertJ, Mockito; `@WebMvcTest` or `MockMvc` standalone for the controller.
1. **`ReportServiceTest`:** valid id found; malformed id (wrong length, uppercase, non-hex, SQL characters, path traversal text) returns empty and **never** queries the repository; missing id empty; corrupt JSON empty; hero name fallback; nullable header fields.
2. **`ReportControllerTest`:** 200 with the expected content for a known id; 404 page for unknown or malformed id; response headers (`X-Robots-Tag`, `Referrer-Policy`, `X-Content-Type-Options`, CSP); HTML escaping (a result text containing `<script>` is rendered inert); no account id, match id or Discord id appears in the body.
3. **`MatchAnalysisRepositoryTest`:** `findByPublicId`; the unique constraint rejects a duplicate public id; a row without a public id is rejected (not null). The backfill of old rows is checked manually (see Verification) because the test database starts already migrated.
4. **`AnalyzeServiceTest`:** a new analysis is saved with a 32-hex public id and the display columns; the same id is returned when the analysis is reopened; fallback and degraded replies have `publicId == null`.
5. **`AnalyzeCommandTest`:** a button with the right URL when a public id and a base URL exist; none without the base URL; none without a public id.
6. Context test: the application context starts in web mode (`@SpringBootTest` with the existing Testcontainers configuration) and the controller bean exists.

## Verification
1. Step 0 recorded in the report.
2. `./mvnw clean verify` passes with Docker running (Windows: `.\mvnw.cmd clean verify`; see the `MAVEN_USER_HOME` note in `docs/PROJECT_CONTEXT.md`). Report the real test count against the baseline and that Flyway applied V4.
3. `git diff --check`; the diff contains only the files in the table; `.env` never read or printed.
4. Backfill check: against a local database already at V3 with at least one stored analysis (or one inserted by hand), start the app and confirm in `psql` that existing rows got distinct 32-hex `public_id` values.
5. Manual check (needs the usual credentials and `REPORT_BASE_URL=http://localhost:8080`): `/analyze` shows the button, the link opens the page, an unknown id gives the 404 page, `/unlink` makes the page 404. If you do not run it, say so in the report.

## Documentation
- ADR `docs/adr/0017-web-report-pages.md`: decision (same app, servlet stack, Thymeleaf, `/r/{publicId}` with 128-bit ids, no listing, noindex), alternatives (separate frontend, Caddy-only static export), consequences (memory, public attack surface, privacy on unlink).
- Task report `docs/task-reports/017-web-report.md`: status, Step 0 findings, summary, files, commands run with real results, deviations, known issues.
- `docs/PROJECT_CONTEXT.md`: **do not edit in this branch** while other task branches are active; mark it "pending integration" and list the facts (V4, web mode, port, `rejuv.web.base-url`, public id rules, `web/` package, ADR-0017).
- Do not edit other ADRs, `docs/mvp-spec.md`, `docs/implementation-plan.md` or other specs.

## Out of scope
- Domain, Caddy, TLS and compose changes, the soul curve chart and any external script (Task 018); premium-only content; a public index of reports; user accounts, login or deletion from the page; sharing metadata images; Open Graph previews; rate limiting (note the risk in the report).
- Changing the prompt, the AI client or what the analysis contains.

## Acceptance criteria
- [ ] The two starters are added without versions and the app still starts the bot and passes the whole suite
- [ ] `/r/{publicId}` serves a stored analysis and returns a 404 page for unknown or malformed ids without a database query for malformed ones
- [ ] The page exposes no Discord id, account id, match id or username, escapes every dynamic value, sends the security headers and works without any external script
- [ ] The button appears only when a public id and a valid `REPORT_BASE_URL` exist
- [ ] Unlinking removes the reports (404)
- [ ] V4 applies, backfills public ids and never edits V1 to V3
- [ ] ADR-0017 created; no other ADR edited
- [ ] All user-facing text (page and button) is in `messages.properties`
- [ ] `./mvnw clean verify` passes; test count and baseline difference are in the report
- [ ] Task report created; `docs/PROJECT_CONTEXT.md` update marked pending integration; final diff has no unrelated files

## Decisions and open questions
Decisions taken in this spec: ids are 128-bit random hex; the page shows only what is stored (strength, leak, action, key numbers and the header fields), not the full metric table; the web server runs on `PORT` (default 8080) and exposure is left to Task 018.

Open for the owner (not blocking): whether stored reports should expire after N days (privacy and storage); whether to persist the metric table so the page can show more than the embed; the domain name (Task 018).
