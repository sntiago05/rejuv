# Task 050: Configurable donation link

## Status
Implemented on branch `feature/050-donations`. `env -u ANTHROPIC_BASE_URL -u ANTHROPIC_CUSTOM_HEADERS ./mvnw verify` passes (1117 tests run, 0 failures, 14 skipped). Not pushed, not merged.

## Summary
Task 050 adds a configurable donation link sourced purely from configuration/environment variables (`DONATION_URL`, `DONATION_LABEL`), enabling users to support Rejuv voluntarily. When no valid URL is configured, the donation link is hidden across all surfaces with zero code changes required.

1. **Configuration & Validation (`DonationProperties.java`, `DonationService.java`)**:
   - `DonationProperties` binds `rejuv.donation.url` and `rejuv.donation.label` as a Spring `@ConfigurationProperties` record.
   - `DonationService` validates the configured URL:
     - URL must be non-blank, contain no whitespace, be at most 512 characters, and parse via `java.net.URI` with an `http` or `https` scheme and a non-empty host.
     - Invalid non-blank URLs log one `WARN` line at startup and do not throw.
     - `label` is trimmed, falling back to `donation.label.default` (`Support Rejuv`) if blank, and is capped at 80 characters (Discord button limit).
     - Link state is resolved once during construction into `Optional<Donation>`.
2. **Discord Slash Commands (`DonateCommand.java`, `HelpCommand.java`, `AnalyzeCommand.java`)**:
   - `/donate` (`DonateCommand`): quick ephemeral response without `deferReply()`. Shows an embed (`EmbedStyle.COLOR`) with title `donate.title` and description `donate.body`, plus a link button (`Button.link(url, label)`). When no link is configured, responds with ephemeral plain text `donate.disabled`.
   - `/help` (`HelpCommand`): appends the message `help.donate` (`\n\n💛 **Support Rejuv:** [{0}]({1})`) to the description when a donation link is present; unchanged when absent.
   - `/analyze` embed (`AnalyzeCommand`): adds a final field `analyze.field.support` with markdown link value `analyze.support.line` if a donation link is present and the embed fits within Discord limits (`builder.length()` + field length <= 6000 and field count < 25). Skipped silently if it would overflow, giving analysis content absolute priority. Backwards-compatible constructors preserved for existing tests.
3. **Web Report Footer (`DonationModelAdvice.java`, `report.html`, `report.css`)**:
   - `DonationModelAdvice` is a `@ControllerAdvice` exposing `@ModelAttribute("donation")`, leaving `ReportController` and `ReportView` untouched.
   - `report.html` renders a paragraph containing `report.donate.text` and `<a th:href="${donation.url}" th:text="${donation.label}" rel="noopener noreferrer nofollow">` within `<footer class="report-footer">` when `donation != null`. Uses `th:text` only; no `th:utext`, no inline `style=`, no `<script>`.
   - Page remains completely unchanged when `donation` is `null`.
   - `report.css` adds styling for `.donate-footer` and `.donate-footer a` only, using `--accent-color`.
4. **Environment & Deployment (`application.yaml`, `.env.example`, `compose.prod.yaml`, `docs/deployment.md`)**:
   - `application.yaml`: binds `rejuv.donation.url: ${DONATION_URL:}` and `rejuv.donation.label: ${DONATION_LABEL:}` (empty by default).
   - `.env.example`: documented `DONATION_URL` with placeholder (`https://example.com/donate-placeholder`) and `DONATION_LABEL`.
   - `compose.prod.yaml`: passes `DONATION_URL: ${DONATION_URL:-}` and `DONATION_LABEL: ${DONATION_LABEL:-}` to the bot container environment.
   - `docs/deployment.md`: documented the two optional variables in the environment configuration section.

## Files Created or Modified
| File | Changes |
|---|---|
| `src/main/java/sntiago05/rejuv/config/DonationProperties.java` | New: `@ConfigurationProperties("rejuv.donation")` record (`url`, `label`). |
| `src/main/java/sntiago05/rejuv/services/DonationService.java` | New: `@Service` validating URL/label and computing `Optional<Donation>`. No JDA types. |
| `src/main/java/sntiago05/rejuv/commands/DonateCommand.java` | New: `/donate` slash command with ephemeral reply, embed, and link button or disabled text. |
| `src/main/java/sntiago05/rejuv/commands/HelpCommand.java` | Injected `DonationService`, appended donation line to description when link exists. |
| `src/main/java/sntiago05/rejuv/commands/AnalyzeCommand.java` | Added `DonationService` to long constructor, short constructors pass disabled service, appended support field if embed fits. |
| `src/main/java/sntiago05/rejuv/web/DonationModelAdvice.java` | New: `@ControllerAdvice` adding `@ModelAttribute("donation")`. |
| `src/main/resources/templates/report.html` | Added conditional `<p th:if="${donation != null}" class="donate-footer">` with link in footer. |
| `src/main/resources/static/report.css` | Added styling for `.donate-footer` and `.donate-footer a`. |
| `src/main/resources/application.yaml` | Added `rejuv.donation.url` and `rejuv.donation.label` properties. |
| `.env.example` | Added `DONATION_URL` placeholder and `DONATION_LABEL`. |
| `compose.prod.yaml` | Added `DONATION_URL` and `DONATION_LABEL` pass-throughs to bot container environment. |
| `docs/deployment.md` | Documented `DONATION_URL` and `DONATION_LABEL` in deployment guide. |
| `src/main/resources/messages.properties` | Added keys `donation.label.default`, `donate.*`, `help.donate`, `analyze.field.support`, `analyze.support.line`, `report.donate.text`. |
| `src/test/java/sntiago05/rejuv/config/DonationPropertiesTest.java` | New: test binding properties and defaults. |
| `src/test/java/sntiago05/rejuv/services/DonationServiceTest.java` | New: tests for valid URLs (http/https), blank/null, invalid schemes, whitespace, >512 chars, no host, label defaults, label cut to 80 chars, no throwing. |
| `src/test/java/sntiago05/rejuv/commands/DonateCommandTest.java` | New: tests command name, description, ephemeral embed + button with link, ephemeral disabled message without link. |
| `src/test/java/sntiago05/rejuv/commands/HelpCommandTest.java` | Tested help embed with and without donation line. |
| `src/test/java/sntiago05/rejuv/commands/AnalyzeCommandTest.java` | Tested support field present with link, absent without link, and skipped when embed would exceed size limit. |
| `src/test/java/sntiago05/rejuv/web/ReportControllerTest.java` | Mocked `DonationService`; tested footer link rendered when present (href, rel, label, no script, no style=, CSP intact), and omitted when absent. Saved rendered sample to `target/rendered-footer-sample.html`. |
| `docs/task-reports/050-donations.md` | This task report. |

No other files were touched. No migrations, no ADR changes, no dependencies added, no changes to `pom.xml`, and no edits to `docs/PROJECT_CONTEXT.md`.

## Rendered Footer Sample
As verified during `ReportControllerTest`, the rendered footer with `donation` present was saved to `target/rendered-footer-sample.html` (not committed):
```html
<footer class="report-footer">
            <p class="analyzed-date">Analyzed on 2026-10-18T12:00:00Z</p>
            <p class="brand-footer">Rejuv · AI coach for Deadlock</p>
            <p class="donate-footer">
                <span>Rejuv is free. If it helped you, you can support it:</span>
                <a href="https://example.com/donate" rel="noopener noreferrer nofollow">Support Rejuv</a>
            </p>
        </footer>
```
When `donation` is `null`, the `.donate-footer` `<p>` tag is omitted entirely, matching the pre-task markup.

## Manual Checks Checklist
- [x] `docker compose -f compose.prod.yaml config` showing both variables: verified with `POSTGRES_PASSWORD=dummy` and with `DONATION_URL` / `DONATION_LABEL` set (`DONATION_URL: https://example.com/donate`, `DONATION_LABEL: Support Us`).
- [x] Web report footer with donation link: verified via Spring MVC MockMvc integration test and inspectable HTML in `target/rendered-footer-sample.html`.
- [x] Web report footer without donation link: verified via `ReportControllerTest.viewReportOmitsFooterDonationLinkWhenAbsent`.
- [ ] `/donate` with `DONATION_URL` in live Discord: automated unit test passes (`DonateCommandTest.handleWithLinkRepliesEphemerallyWithEmbedAndButton`); live Discord verification pending bot deployment.
- [ ] `/donate` without `DONATION_URL` in live Discord: automated unit test passes (`DonateCommandTest.handleWithoutLinkRepliesEphemerallyWithDisabledMessageAndNoButton`); live Discord verification pending bot deployment.
- [ ] `/help` in live Discord: automated unit tests pass (`HelpCommandTest`); live Discord verification pending bot deployment.
- [ ] `/analyze` embed in live Discord: automated unit tests pass (`AnalyzeCommandTest`); live Discord verification pending bot deployment.

## Verification
- Base verification command: `env -u ANTHROPIC_BASE_URL -u ANTHROPIC_CUSTOM_HEADERS ./mvnw verify`
  - Result: `BUILD SUCCESS`
  - Tests run: 1117 (0 failures, 0 errors, 14 skipped)
  - Time: 28.771 s
- Git diff check: all modified files are within the spec-authorized list.
- Conventional Commits: small, focused commits on `feature/050-donations`.

## Deviations
- `AnalyzeCommand` has three constructors, not two. Besides the long one (now ending with `DonationService`) and the short one (9 arguments), I kept the previous 10-argument constructor (ends with `WebProperties`). It passes a disabled `DonationService`. The spec did not list it. I kept it so existing callers and tests that use that signature still compile. Remove it later if no caller needs it.

## Known Issues / Blockers
None.

## Pending Integration
Per parallel worktree rules, `docs/PROJECT_CONTEXT.md` was not modified directly.
Facts to integrate upon merge:
- **New configuration properties**: `rejuv.donation.url` (`DONATION_URL`) and `rejuv.donation.label` (`DONATION_LABEL`) mapped in `DonationProperties`. Passed to Docker Compose bot container via `compose.prod.yaml`.
- **New slash command**: `/donate` provides an ephemeral reply with an embed and link button to support Rejuv, or plain text if unconfigured.
- **Surfaces showing donation link**:
  1. `/donate` (embed + link button)
  2. `/help` (appended line with markdown link)
  3. `/analyze` embed (trailing support field if embed length <= 6000 and field count < 25)
  4. Web report `/r/{publicId}` footer (`.donate-footer` with `rel="noopener noreferrer nofollow"`, no script, no inline style, strict CSP).
- **Task 049 compatibility**: Footer changes in `report.html` and `ReportControllerTest` were made against the current worktree base; conflicts with parallel branch 049 will be resolved at integration time.
