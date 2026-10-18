# Task 018: Soul curve on the web report and optional HTTPS with Caddy

## Status

Implemented on `feature/018-report-soul-curve`. `.\mvnw.cmd clean verify` passes with **814 tests** (0 failures, 0 errors, 2 skipped benchmark tests). Not pushed, not merged.

## Summary

- **Lobby Average Badge Mapping**:
  - Added `average_badge_team0` and `average_badge_team1` (`Integer`) to `clients/dto/MatchMetadataResponse.java` (`MatchInfo`).
  - Added `Integer averageBadge` (nullable) to `domain/MatchTelemetry.java`, keeping backward compatibility via the 3-argument constructor.
  - Implemented `computeAverageBadge` in `clients/JsonMatchMetadataSource.java` computing the mean of non-zero team badges rounded down to int, returning `null` when both are zero or absent.
- **Pure Server-Side SVG Renderer (`SoulCurveSvg`)**:
  - Created `services/SoulCurveSvg.java` as a pure, script-free, deterministic SVG generator rendering `<svg viewBox="0 0 640 360" role="img" aria-label="...">`.
  - Supports light and dark modes via embedded `<style>` and `@media (prefers-color-scheme: dark)`.
  - Distinguishes player line by more than color: solid thicker line (stroke-width 3) vs dashed line (stroke-width 2, dasharray 6 4) for lobby average.
  - Renders shaded band for `avg +/- std` with lower bound clamped at 0.
  - Renders Y-axis in thousands of souls, X-axis in minutes with adaptive 2-min or 5-min tick marks, dynamic legend, and one-line caption escaping all text.
  - Returns `Optional.empty()` when fewer than 3 points are present or list sizes differ.
- **Report Presentation & Service Integration**:
  - Added `boolean hasSoulCurve` to `domain/ReportView.java`, retaining backward compatibility via the 11-argument constructor.
  - Updated `services/ReportService.java` to compute lobby rank tier (`tier = Math.clamp(averageBadge / 10, 1, 11)`), fetch cached performance curve (`deadlockApiClient.getPerformanceCurve(heroId, tier)`), align shared grid times (`t <= durationS` and `netWorthStd > 0`), and expose `soulCurveSvg(UUID publicId)` and `hasSoulCurve` in `find(UUID publicId)`.
  - Handles missing metadata, missing badges, empty curves, or `DeadlockApiException` by logging one WARN with exception class and gracefully degrading (`hasSoulCurve = false`, `soulCurveSvg = Optional.empty()`) without failing requests.
- **Web Controller & Template Updates**:
  - Exposed `GET /r/{publicId}/soul-curve.svg` in `web/ReportController.java` returning `image/svg+xml;charset=UTF-8` with identical security headers (`X-Robots-Tag`, `Content-Security-Policy`, `Referrer-Policy`, `X-Content-Type-Options`, `Cache-Control`) or 404 with empty body.
  - Updated `src/main/resources/templates/report.html` to include `<section th:if="${report.hasSoulCurve}" class="card card-soul-curve"><img th:src="@{/r/{id}/soul-curve.svg(id=${report.publicId})}" th:alt="#{report.curve.alt}" class="soul-curve-img"></section>` placed immediately after the existing chart.
  - Added `.card-soul-curve` and `.soul-curve-img` styles to `src/main/resources/static/report.css`.
  - Appended required i18n keys to `src/main/resources/messages.properties`.
- **Caddy Reverse Proxy & Deployment Documentation**:
  - Created `Caddyfile` with reverse proxy configuration `{$DOMAIN} { encode zstd gzip; reverse_proxy bot:8080 }`.
  - Updated `compose.prod.yaml` adding the `caddy` service under the `https` profile (ports 80:80, 443:443, data and config volumes), and set bot environment `WEB_BIND_ADDRESS: "0.0.0.0"` and `WEB_BASE_URL: ${WEB_BASE_URL:-}`.
  - Added `DOMAIN=` to `.env.example`.
  - Updated `docs/deployment.md` with an "HTTPS with Caddy (Optional)" section detailing DNS A record configuration, firewall rules, and compose execution with `--profile https`.

## Important Files

- `src/main/java/sntiago05/rejuv/services/SoulCurveSvg.java` (created: pure SVG chart generator)
- `Caddyfile` (created: reverse proxy Caddy configuration)
- `src/main/java/sntiago05/rejuv/clients/dto/MatchMetadataResponse.java` (modified: added team badge fields)
- `src/main/java/sntiago05/rejuv/clients/JsonMatchMetadataSource.java` (modified: mapped average badge)
- `src/main/java/sntiago05/rejuv/domain/MatchTelemetry.java` (modified: added averageBadge component)
- `src/main/java/sntiago05/rejuv/domain/ReportView.java` (modified: added hasSoulCurve component)
- `src/main/java/sntiago05/rejuv/services/ReportService.java` (modified: added soulCurveSvg and hasSoulCurve computation)
- `src/main/java/sntiago05/rejuv/web/ReportController.java` (modified: added soul-curve.svg route)
- `src/main/resources/templates/report.html` (modified: added soul curve image section)
- `src/main/resources/static/report.css` (modified: added soul curve classes)
- `src/main/resources/messages.properties` (modified: added report.curve.* message keys)
- `compose.prod.yaml` (modified: added caddy service and profile https)
- `.env.example` (modified: added DOMAIN=)
- `docs/deployment.md` (modified: added HTTPS with Caddy section)
- `src/test/java/sntiago05/rejuv/services/SoulCurveSvgTest.java` (created: 8 tests)
- `src/test/java/sntiago05/rejuv/services/ReportServiceTest.java` (modified: +8 tests for soul curve logic and clamping)
- `src/test/java/sntiago05/rejuv/web/ReportControllerTest.java` (modified: +4 tests for SVG route and template)
- `src/test/java/sntiago05/rejuv/clients/JsonMatchMetadataSourceTest.java` (modified: +3 tests for averageBadge mapping)
- `src/test/java/sntiago05/rejuv/domain/MatchTelemetryTest.java` (modified: +1 test for averageBadge component)
- `docs/task-reports/018-report-soul-curve.md` (created: this report)

## Decisions

- **Pure Server-Side SVG Rendering**: Implemented SVG chart rendering purely in Java without client-side JavaScript or external chart libraries, preserving the strict `Content-Security-Policy` (`default-src 'none'; img-src 'self'; style-src 'self'`).
- **No Premium Gating**: As specified in the task description, the soul curve chart is public by link and shown on the web report whenever sufficient data exists.
- **Fail-Safe Graceful Degradation**: If telemetry has no `averageBadge`, fewer than 3 shared points exist, or the performance curve API fails, the report view simply sets `hasSoulCurve = false` and omits the section without failing the report.
- **Zero Port Exposure for Bot**: In `compose.prod.yaml`, the bot binds to `0.0.0.0` inside the internal Docker network so Caddy can reach it, while publishing no host ports. Only Caddy publishes ports 80 and 443 when running under the `https` profile.

## Verification

- **Baseline before changes**: 790 tests passed, 0 failures, 2 skipped benchmark tests.
- **Unit and Controller Test Suites**:
  - `.\mvnw.cmd test "-Dtest=SoulCurveSvgTest"`: 8 tests, 0 failures.
  - `.\mvnw.cmd test "-Dtest=JsonMatchMetadataSourceTest"`: 17 tests, 0 failures.
  - `.\mvnw.cmd test "-Dtest=MatchTelemetryTest"`: 4 tests, 0 failures.
  - `.\mvnw.cmd test "-Dtest=ReportServiceTest"`: 16 tests, 0 failures.
  - `.\mvnw.cmd test "-Dtest=ReportControllerTest"`: 10 tests, 0 failures.
  - `.\mvnw.cmd test "-Dtest=MessagesIntegrationTest"`: 5 tests, 0 failures.
- **Docker Compose Configuration Validation**:
  - `docker compose -f compose.prod.yaml config --quiet`: exit code 0.
  - `docker compose -f compose.prod.yaml --profile https config --quiet`: exit code 0.
- **Full Build Verification**:
  - Command: `.\mvnw.cmd clean verify`
  - Result: **BUILD SUCCESS**
  - Total tests run: **814 tests** (0 failures, 0 errors, 2 skipped benchmark tests). (+24 tests over baseline).
- **Manual Verification Record**:
  - No live domain or TLS certificate was configured (as specified by task scope).
  - Live Discord bot interaction was not run because no bot token is configured in the environment.
  - MockMvc verified HTTP 200 SVG response, `image/svg+xml;charset=UTF-8` content type, security headers, 404 behavior, and presence/absence of `<img>` tag in rendered HTML.

## Deviations

None. All interfaces, signatures, behavior, routes, compose services, and test specifications were implemented exactly as specified.

## Known Issues

None.

## Merge Risks

- None expected. No Flyway migrations were added. No existing database tables or schemas were changed.
- If concurrent branches touch `ReportService` or `ReportView`, resolve constructor calls by passing `hasSoulCurve`.

## PROJECT_CONTEXT update pending integration

The following facts are ready to be integrated into `docs/PROJECT_CONTEXT.md` once merged:
- **Soul Curve on Web Report**: Web reports at `/r/{publicId}` include an SVG soul curve chart (`/r/{publicId}/soul-curve.svg`) comparing player net worth over time against the lobby rank average (`DeadlockApiClient.getPerformanceCurve(heroId, tier)`) and `+/- 1 std dev` band.
- **Server-Side SVG Generation**: `SoulCurveSvg` generates responsive, valid XML SVGs with dark/light mode support without client-side JavaScript.
- **Lobby Average Badge**: `MatchTelemetry` and `JsonMatchMetadataSource` map `averageBadge` from `average_badge_team0` and `average_badge_team1`.
- **Caddy HTTPS Service**: `compose.prod.yaml` includes an optional `caddy` service under profile `https` with automatic TLS provisioning for public web reports.
