# Task 018: Soul curve on the web report and optional HTTPS with Caddy

- **Branch**: `feature/018-report-soul-curve`
- **Implementer**: Antigravity
- **Related ADRs**: ADR-0012 (web reports, Caddy), ADR-0018 (web stack)
- **Depends on**: 017, 029, 036 (all merged)

## Goal
(1) Add to the public report page a chart of the player's net worth over time against the average (and one standard deviation band) of players of the same hero at the match's rank, drawn server-side as an SVG so the page keeps its strict Content-Security-Policy and has no JavaScript. (2) Add an optional Caddy service to the production compose file so the report can be served over HTTPS once the owner has a domain. No domain is bought or configured by this task.

Decision recorded here: the soul curve is **not** premium-gated. The report page is public by link and cannot know who is viewing; it is shown whenever the data exists. (`docs/features.md` lists it as premium; the owner can gate it later.)

## Files to create or modify
| File | Change |
|---|---|
| `clients/dto/MatchMetadataResponse.java` | Modify: add `average_badge_team0` and `average_badge_team1` (`Integer`) to `MatchInfo`. |
| `clients/JsonMatchMetadataSource.java` | Modify: fill `averageBadge` (see Behavior 1). |
| `domain/MatchTelemetry.java` | Modify: add last component `Integer averageBadge` (nullable); keep the 3-argument constructor (null). |
| `services/SoulCurveSvg.java` | **New.** Pure SVG renderer. |
| `services/ReportService.java` | Modify: add `soulCurveSvg(UUID)`; compute `hasSoulCurve` in `find`. |
| `domain/ReportView.java` | Modify: add last component `boolean hasSoulCurve`; keep the previous constructor (false). |
| `web/ReportController.java` | Modify: add the SVG route. |
| `src/main/resources/templates/report.html` | Modify: add the image section. |
| `src/main/resources/static/report.css` | Modify: style for the new section only. |
| `src/main/resources/messages.properties` | Add keys at the end. |
| `compose.prod.yaml` | Modify: add `caddy` service under profile `https`; bot gets `WEB_BIND_ADDRESS: 0.0.0.0` and `WEB_BASE_URL` from `${WEB_BASE_URL:-}`. |
| `Caddyfile` | **New.** |
| `.env.example` | Add `DOMAIN=` (empty, with a comment). |
| `docs/deployment.md` | Modify: add an "HTTPS with Caddy" section (domain A record, firewall ports 80/443 on Oracle, `docker compose --profile https up -d`, set `DOMAIN` and `WEB_BASE_URL=https://<domain>`). |
| Tests | **New**: `SoulCurveSvgTest`. Modify: `JsonMatchMetadataSourceTest`, `ReportServiceTest`, `ReportControllerTest`, `MatchTelemetryTest` if it asserts the component list. |
| `docs/task-reports/018-report-soul-curve.md` | **New.** |

Do not edit `RawMatchMetadataParser`, `AnalyzeService`, `AnalysisReport`, `ChartRenderer`, `CacheConfig`, `DeadlockApiClient` or anything else. `docs/PROJECT_CONTEXT.md`: pending integration.

## Interfaces / signatures
```java
// domain/MatchTelemetry.java  (the raw adapter keeps using the 3-argument constructor, so averageBadge is null there)
public record MatchTelemetry(long matchId, int durationS, List<PlayerTelemetry> players, Integer averageBadge)
// averageBadge = mean of the non-zero values of average_badge_team0/1 (rounded down to int), null when both are 0 or absent.

// services/SoulCurveSvg.java   @Component, constructor(Messages)
public Optional<String> render(List<Integer> timesS, List<Long> playerNetWorth,
                               List<Double> avgNetWorth, List<Double> stdNetWorth)
// empty when fewer than 3 points or the lists differ in size.

// services/ReportService.java
public Optional<String> soulCurveSvg(UUID publicId)

// web/ReportController.java
@GetMapping("/r/{publicId}/soul-curve.svg")   // image/svg+xml, same security headers as the other routes; 404 with empty body otherwise
```

## Behavior
1. **Rank of the lobby.** In `ReportService`, `tier = clamp(telemetry.averageBadge() / 10, 1, 11)`; no `averageBadge` -> no soul curve. (This is the lobby average rank, not the player's own rank; the SVG caption says so. The report does not store the player's rank.)
2. **Data.** For the player (`telemetry.player(stored.getAccountId())`): `DeadlockApiClient.getPerformanceCurve(stored.getHeroId(), tier)` (cached). Points are the curve times `t` such that `t <= telemetry.durationS()`, the curve `netWorthStd > 0` and the player has a snapshot with exactly time `t` (the grids coincide: 180, 360, ..., 2400 s). At least 3 points are required. An expected API failure (`DeadlockApiException`) or a `RuntimeException` from the metadata read means "no soul curve" (one WARN with the exception class), never an error page. `find` sets `hasSoulCurve` using the same computation (the curve call is cached, so no extra request when the SVG is fetched afterwards).
3. **SVG.** One `<svg viewBox="0 0 640 360">` with `role="img"` and an `aria-label` from the messages. X axis in minutes (ticks every 5 minutes or fewer when short), Y axis in thousands of souls; shaded band `avg +/- std` (lower bound clamped at 0), average as a dashed line, player as a solid thicker line, a legend and a one-line caption `report.curve.caption` (`{0}` hero, `{1}` rank label from `DeadlockApiClient.getRanks()` or "rank {tier}" when unknown). Colors inside an embedded `<style>` with a `@media (prefers-color-scheme: dark)` variant; the player line must be distinguishable by more than color (solid vs dashed, thicker, labelled in the legend). All text goes through `Messages` keys and is XML-escaped (hero and rank names). No scripts, no external references, no `<foreignObject>`.
4. **Route and page.** The SVG route returns `Content-Type: image/svg+xml;charset=UTF-8` with the same headers as the chart route. `report.html` shows `<img src="/r/{id}/soul-curve.svg" alt="...">` (alt from `#{report.curve.alt}`) after the existing chart, only when `hasSoulCurve`. The CSP is unchanged (`img-src 'self'` already allows it).
5. **Telemetry mapping.** `JsonMatchMetadataSource` fills `averageBadge` from `average_badge_team0/1`; absent or both zero = `null`. Existing fixtures must still map; add one case with badges.
6. **Caddy (compose + docs only).** Service `caddy`: image `caddy:2` (pin a minor version), `profiles: ["https"]`, `restart: unless-stopped`, ports `80:80` and `443:443`, volumes `./Caddyfile:/etc/caddy/Caddyfile:ro`, `caddy-data:/data`, `caddy-config:/config`, `depends_on: bot`. `Caddyfile`: `{$DOMAIN} { encode zstd gzip; reverse_proxy bot:8080 }` (nothing else; Caddy handles certificates). The `bot` service gets `WEB_BIND_ADDRESS: 0.0.0.0` (safe: the bot still publishes no port; only Caddy reaches it on the compose network). With no profile, `docker compose -f compose.prod.yaml up` behaves as before. The file must pass `docker compose -f compose.prod.yaml config` and `docker compose -f compose.prod.yaml --profile https config` (with dummy `POSTGRES_PASSWORD` and `DOMAIN`).

## Messages (English, end of file)
`report.curve.title`, `report.curve.alt`, `report.curve.aria`, `report.curve.caption`, `report.curve.legend.you`, `report.curve.legend.average`, `report.curve.legend.band`, `report.curve.axis.minutes`, `report.curve.axis.souls`.

## Out of scope
Buying or configuring a domain or DNS; Let's Encrypt tuning; JavaScript charts; player-rank storage or a Flyway migration; premium gating; kills/deaths curves; changes to the Discord embed; the raw adapter; new dependencies.

## Tests
- `SoulCurveSvgTest`: output is well-formed XML (parse it with the JDK parser); contains no `<script`, `foreignObject` or `href=`/`xlink` to external resources; hero names with `<`, `&` and quotes are escaped; fewer than 3 points or mismatched lists give empty; lower band clamped at 0; point coordinates stay inside the viewBox for large and small values; deterministic output for identical input.
- `ReportServiceTest`: soul curve built for a 30-minute match (points at the shared grid times only), skipped when `averageBadge` is null, when fewer than 3 shared points exist, when the curve is empty, when std is 0 at all points, when the client throws `DeadlockApiUnavailableException`; `hasSoulCurve` matches; tier clamping (badge 5, 125); only one curve call per computation.
- `ReportControllerTest`: SVG route returns 200 with `image/svg+xml`, all security headers, 404 for malformed and unknown ids and when the service returns empty; page contains the SVG `<img>` only when `hasSoulCurve`; page still has no `<script`.
- `JsonMatchMetadataSourceTest`: `averageBadge` mean of two non-zero values, one zero value uses the other, both zero or absent gives null.

## Verification
`./mvnw clean verify` (on this machine `.\mvnw.cmd clean verify`, Docker running); counts in the report. Both `docker compose ... config` commands above with dummy variables. A manual check of the page in a browser (light and dark) if the owner can run the bot locally; otherwise state it was not run. No real certificate or domain is needed or used.

## Acceptance criteria
- [ ] Compiles, all tests pass, counts recorded
- [ ] SVG is valid, escaped, script-free; CSP unchanged; page degrades gracefully without the curve
- [ ] Compose file validates with and without the `https` profile; default `up` behaves as before; bot still publishes no port
- [ ] No Flyway migration, no new dependency, no file outside the list changed
- [ ] Task report created; `docs/PROJECT_CONTEXT.md` update marked pending integration
- [ ] Follows AGENTS.md conventions
