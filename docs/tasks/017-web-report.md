# Task 017: Public web report page and "View full report" button

- **Branch**: `feature/017-web-report`
- **Implementer**: Antigravity
- **Related ADRs**: ADR-0012 (web reports in week 2), new ADR-0018 (created by this task)
- **Depends on**: 009, 019, 026 (merged). Merge **after task 029** if 029 is already on `main` (both edit `AnalyzeService`); otherwise merge `origin/main` into the branch before finishing.

## Goal
Every stored `/analyze` result gets a random, non-guessable public page `GET /r/{publicId}` rendered by the same Spring Boot app (Thymeleaf), and the `/analyze` embed gets a link button "View full report" that opens it. The page shows the same coaching text as the embed plus the final build and the existing soul chart. This task introduces the web server; HTTPS, the domain and the interactive soul curve are other tasks (018, 036).

## Files to create or modify
| File | Change |
|---|---|
| `pom.xml` | Add `spring-boot-starter-webmvc` and `spring-boot-starter-thymeleaf` (versions from the Boot BOM). **No other dependency.** |
| `src/main/resources/application.yaml` | Web config, see Behavior 1. |
| `.env.example` | Add `WEB_PORT=`, `WEB_BIND_ADDRESS=`, `WEB_BASE_URL=` (empty values, commented purpose). |
| `src/main/resources/db/migration/V4__add_match_analysis_public_id.sql` | **New** migration. |
| `domain/MatchAnalysis.java` | Modify: add `publicId` (`UUID`), getter. |
| `repositories/MatchAnalysisRepository.java` | Modify: add `findByPublicId(UUID)`. |
| `domain/AnalysisReport.java` | Modify: add last component `UUID publicId` (nullable); keep both existing constructors (publicId = `null`). |
| `services/AnalyzeService.java` | Modify: fill `publicId` in the report at every construction site (stored row, fresh save, concurrent duplicate); `null` on the fallback outcome. Nothing else. |
| `config/WebProperties.java` | **New.** `@ConfigurationProperties("rejuv.web")` record `WebProperties(String baseUrl)`; blank or null = web links disabled. |
| `services/ReportService.java` | **New.** Builds a `domain/ReportView`. |
| `domain/ReportView.java` | **New** record. |
| `web/ReportController.java` | **New** package `web`. |
| `commands/AnalyzeCommand.java` | Modify: add the link button (Behavior 5). |
| `src/main/resources/messages.properties` | Add keys at the end (Behavior 6). |
| `src/main/resources/templates/report.html`, `report-not-found.html` | **New** Thymeleaf templates. |
| `src/main/resources/static/report.css` | **New**, small hand-written stylesheet (no CDN, no JavaScript). |
| `docs/adr/0018-web-report-stack.md` | **New** ADR (template `0000-template.md`): Spring MVC + Thymeleaf in the same app, no frontend build, reverse proxy later; consequences (memory, port, public pages). |
| Tests | **New**: `ReportServiceTest`, `ReportControllerTest`. Modify: `MatchAnalysisRepositoryTest`, `FlywayMigrationTest` (V4 expectations), `AnalyzeServiceTest`, `AnalyzeCommandTest`, `MessagesIntegrationTest` only if its key list must grow. If enabling the web environment breaks other existing tests (for example `RejuvApplicationTests`), change the minimum lines needed in them and list each in the report. |
| `docs/task-reports/017-web-report.md` | **New.** Task report. |

Do not touch any other file. `docs/PROJECT_CONTEXT.md`: mark the update as pending integration.

## Interfaces / signatures

```java
// V4 (Flyway, never edit after merge). gen_random_uuid() is built in since PostgreSQL 13 (project uses 17).
ALTER TABLE match_analysis ADD COLUMN public_id UUID NOT NULL DEFAULT gen_random_uuid();
ALTER TABLE match_analysis ADD CONSTRAINT uq_match_analysis_public_id UNIQUE (public_id);

// domain/MatchAnalysis.java : @Column(name = "public_id", nullable = false, updatable = false) private UUID publicId;
// set to UUID.randomUUID() in the public constructor (existing 8-argument signature unchanged); the protected JPA constructor leaves it to Hibernate.
public UUID getPublicId()

// repositories/MatchAnalysisRepository.java
Optional<MatchAnalysis> findByPublicId(UUID publicId);

// config/WebProperties.java
@ConfigurationProperties("rejuv.web") public record WebProperties(String baseUrl) { public boolean enabled(); } // enabled = non-blank

// domain/ReportView.java  (no account id, no Discord id, ever)
public record ReportView(UUID publicId, long matchId, String hero, int heroId, String rank, String result,
        Double durationMin, AnalysisResult analysis, List<MatchVisuals.PurchasedItem> build, boolean hasChart,
        Instant analyzedAt) {}
// hero: hero asset name or "hero_<id>"; rank / result / durationMin: null when unknown (see Behavior 3)

// services/ReportService.java
public Optional<ReportView> find(UUID publicId)            // empty = no such report; never throws for API failures
public Optional<byte[]> chartPng(UUID publicId)            // empty = no report or no chart

// web/ReportController.java
@GetMapping("/r/{publicId}")            -> view "report" (HTTP 200) or "report-not-found" (HTTP 404)
@GetMapping("/r/{publicId}/chart.png")  -> image/png (200) or 404 with an empty body
```

## Behavior

1. **Web server.** `application.yaml`: remove `spring.main.web-application-type: none`; set `server.port: ${WEB_PORT:8080}`, `server.address: ${WEB_BIND_ADDRESS:127.0.0.1}` (loopback by default; a reverse proxy publishes it later), `rejuv.web.base-url: ${WEB_BASE_URL:}`, `server.error.include-stacktrace: never`, `spring.thymeleaf.cache: true`. The bot must still start and answer commands exactly as before; the web server is additive.
2. **Public id.** V4 backfills every existing row with a random UUID through the column default. New rows get `UUID.randomUUID()` from the entity (122 random bits, not enumerable). `/unlink` already deletes analyses of unlinked accounts, so their pages disappear automatically (state this in the report; add no code).
3. **`ReportService.find`.** Loads the row by `publicId`; parses `result_json` into `AnalysisResult` (a corrupted row = empty, WARN log with the public id only). Hero name from the cached assets (`GameAssets`/`DeadlockApiClient.getHeroes`, same helper style as the rest of the services). Then one cached read `matchMetadataSource.getMatch(matchId)`; any `RuntimeException` is caught (one WARN with the exception class) and the page degrades: `durationMin = null`, `build = []`, `hasChart = false`. With telemetry: `durationMin = telemetry.durationS() / 60.0` rounded to one decimal; `build` and `hasChart` from `MatchVisualsService.build(accountId, telemetry)`. `rank` and `result` are **not stored** and are not recomputed in this task: they are `null` and the template omits them. The service performs no AI call, records no usage and never calls the usage limit code.
4. **`ReportService.chartPng`** renders the chart with the existing `ChartRenderer.render(ChartData)`; a render failure is empty (404).
5. **Button in `/analyze`.** In `AnalyzeCommand.answer`, when `WebProperties.enabled()` and `report.publicId() != null`, attach a link button to the reply (both the branch with the chart attachment and the one without): URL = `baseUrl` without trailing `/` + `/r/` + publicId, label `analyze.report_button`. When web links are disabled, the reply is exactly what it is today. No other change to the embed.
6. **Controller / template rules.**
   - `publicId` that is not a valid UUID, or unknown: HTTP 404 using `report-not-found` (no echo of the input, no stack trace).
   - All text through `th:text` / `#{...}` (auto-escaped). `th:utext` is forbidden. All static text comes from `messages.properties` keys `report.*` through Thymeleaf message expressions; no hardcoded user-facing text in templates.
   - The page shows: hero and match id; the three coaching sections (strength, biggest leak, do this next game) and key numbers, in the same order as the embed; the final build list (item name and minute bought) when present; the chart `<img src="/r/{publicId}/chart.png">` when `hasChart`; the analysis date; a short footer. It must read well on a phone (viewport meta, single column).
   - Response headers on both routes: `X-Robots-Tag: noindex, nofollow`; `Content-Security-Policy: default-src 'none'; img-src 'self'; style-src 'self'; base-uri 'none'; frame-ancestors 'none'`; `Referrer-Policy: no-referrer`; `X-Content-Type-Options: nosniff`; `Cache-Control: private, max-age=300`. The HTML also has `<meta name="robots" content="noindex,nofollow">`.
   - No JavaScript, no external fonts, scripts or CDNs.
7. **Messages (exact keys, English).** `analyze.report_button` ("View full report"), `report.title` (page title with hero), `report.strength`, `report.leak`, `report.next_action`, `report.key_numbers`, `report.build`, `report.build_item` (`{0}` at minute `{1}`), `report.match` (`Match {0}`), `report.analyzed_on`, `report.degraded` (details unavailable), `report.footer`, `report.not_found.title`, `report.not_found.body`. Pass `MessagesIntegrationTest` (key format rules) unchanged in spirit.

## Out of scope
HTTPS, Caddy, domain, DNS (task 036 prepares the container, a later deploy step does the rest); interactive/JS charts and the soul curve vs average (task 018); storing rank, result or the AI input; authentication, accounts, deleting a single report, listing reports, sitemap; open-graph previews; any change to the AI prompt or `AnalysisResult`; rate limiting beyond the existing caches; coach sections from tasks 031-037.

## Tests
- `FlywayMigrationTest` / `MatchAnalysisRepositoryTest` (Testcontainers, PostgreSQL 17): V4 applies; a row inserted by plain SQL without `public_id` gets a non-null UUID; two rows get different UUIDs; `findByPublicId` finds the saved entity and returns empty for an unknown id; the public constructor sets a non-null `publicId`; the unique constraint rejects a duplicate.
- `ReportServiceTest` (mocks): unknown id empty; corrupted JSON empty; full view with telemetry (duration rounded, build and `hasChart`); `getMatch` throwing `DeadlockApiUnavailableException` and `MatchMetadataNotFoundException` degrades without throwing; no AI client or usage service is called (verify no interaction); hero fallback `hero_<id>`.
- `ReportControllerTest` (MockMvc with the Spring context, mocked `ReportService`): 200 HTML with the strength text; HTML special characters in the analysis text are escaped (use `<script>` in the text); 404 for a malformed id and for an unknown id (both render `report-not-found`, neither echoes the input); `chart.png` returns `image/png` bytes and 404 when empty; all security headers present on both routes; page contains no `<script`.
- `AnalyzeServiceTest`: stored path, fresh path and concurrent-duplicate path return the row's `publicId`; the fallback outcome returns `null`.
- `AnalyzeCommandTest`: with web enabled and a non-null `publicId` the reply has exactly one link button whose URL is `baseUrl/r/<id>` (also when `baseUrl` ends with `/`); with `baseUrl` blank, or `publicId == null`, there is no component; `analyze.report_button` resolves.

## Verification
`./mvnw clean verify` (on this machine `.\mvnw.cmd clean verify`, Docker running). Manual check (owner or implementer if a bot token is available; otherwise say it was not run): start Postgres and the bot with `WEB_BASE_URL=http://localhost:8080`, run `/analyze`, click the button, open the page on desktop and in a narrow browser window; open `/r/not-a-uuid` and `/r/<random uuid>` and confirm 404. Record what was actually run.

## Acceptance criteria
- [ ] Compiles, all tests pass, counts recorded in the report
- [ ] `GET /r/{publicId}` works, escapes all dynamic text and sends the required headers
- [ ] The `/analyze` reply is unchanged when web links are disabled
- [ ] V4 applies on a database that already has rows; no applied migration edited
- [ ] No page or log line exposes account ids or Discord ids; `.env` never read or committed
- [ ] Only the two web dependencies added; ADR-0018 written; no other file changed
- [ ] Task report created; `docs/PROJECT_CONTEXT.md` update marked pending integration
- [ ] Follows AGENTS.md conventions
