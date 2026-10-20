# Task 050: Configurable donation link

- **Branch**: `feature/050-donations`
- **Implementer**: Antigravity
- **Related ADRs**: ADR-0006 (freemium, Patreon role). This task does not change it. Removing the premium code is a separate task and a new ADR.
- **Depends on**: 049 merged (it rewrites `report.html` and `ReportControllerTest`; this task edits the footer of the new page).

## Goal
Rejuv becomes free to use and accepts optional donations. Add one donation link whose URL and label come only from configuration (environment variables), and show it in `/donate`, `/help`, the `/analyze` embed and the web report footer. When no valid URL is configured, the link is hidden everywhere.

## Why (verified)
- `WebProperties` (`rejuv.web.base-url`, from `WEB_BASE_URL`) already shows the pattern: a `@ConfigurationProperties` record with an `enabled()` method, registered with `@EnableConfigurationProperties` on the class that uses it (`AnalyzeCommand`).
- `compose.prod.yaml` passes variables to the app through an explicit `environment:` list (for example `WEB_BASE_URL: ${WEB_BASE_URL:-}`), so a new variable must be added there or it never reaches the container.
- `AnalyzeCommand` has two constructors: a long one (ends with `WebProperties webProperties`) and a short one that passes `new WebProperties(null)`. Keep both, so existing tests still compile.
- The report page is rendered by `ReportController` with the model attribute `report`. Its CSP is `default-src 'none'; img-src 'self'; style-src 'self'; base-uri 'none'; frame-ancestors 'none'`. A normal `<a href>` link is not blocked by this CSP.

## Files to create or modify
| File | Change |
|---|---|
| `src/main/java/sntiago05/rejuv/config/DonationProperties.java` (new) | `@ConfigurationProperties("rejuv.donation")` record. |
| `src/main/java/sntiago05/rejuv/services/DonationService.java` (new) | Validates the config and returns the link to show. No JDA types. |
| `src/main/java/sntiago05/rejuv/commands/DonateCommand.java` (new) | `/donate` command. |
| `src/main/java/sntiago05/rejuv/commands/HelpCommand.java` | Add the donation line when a link exists. |
| `src/main/java/sntiago05/rejuv/commands/AnalyzeCommand.java` | Add one field at the end of the embed when a link exists. |
| `src/main/java/sntiago05/rejuv/web/DonationModelAdvice.java` (new) | `@ControllerAdvice` that puts `donation` in the model, so `ReportController` and `ReportView` stay unchanged. |
| `src/main/resources/templates/report.html` | Donation link in the footer. |
| `src/main/resources/static/report.css` | Small style for the footer link only. |
| `src/main/resources/application.yaml` | `rejuv.donation.url` and `rejuv.donation.label`. |
| `.env.example` | Add `DONATION_URL` (placeholder value) and `DONATION_LABEL`. Placeholder values only. |
| `compose.prod.yaml` | Pass `DONATION_URL` and `DONATION_LABEL` to the app (`${DONATION_URL:-}`, `${DONATION_LABEL:-}`). |
| `docs/deployment.md` | One short paragraph that lists the two variables. |
| `src/main/resources/messages.properties` | New keys (below). |
| Tests | `DonationPropertiesTest`, `DonationServiceTest`, `DonateCommandTest` (new); `HelpCommandTest`, `AnalyzeCommandTest`, `ReportControllerTest` (extend). |
| `docs/task-reports/050-donations.md` | New: task report. |

No other file. Do not edit migrations, ADRs, `pom.xml`, the CSP string or any security header. Do not touch the premium, Patreon or usage-limit code.

## Interfaces / signatures
```java
// config
@ConfigurationProperties("rejuv.donation")
public record DonationProperties(String url, String label) { }

// services
public class DonationService {                       // @Service
    public DonationService(DonationProperties properties, Messages messages);
    public Optional<Donation> get();                 // empty when disabled
    public record Donation(String url, String label) { }
}
```
- Register `DonationProperties` with `@EnableConfigurationProperties(DonationProperties.class)` on `DonationService`, as `PatreonRolePremiumChecker` does for `PremiumProperties`.
- `application.yaml`:
  ```yaml
  rejuv:
    donation:
      url: ${DONATION_URL:}
      label: ${DONATION_LABEL:}
  ```
  The default is empty, so no fake link ships in code. The placeholder lives only in `.env.example` (for example `https://example.com/donate-placeholder`).
- `AnalyzeCommand`: add `DonationService donationService` as the last parameter of the long constructor. The short constructor passes a disabled service (a `DonationService` built with `new DonationProperties(null, null)`).
- `HelpCommand(Messages messages, DonationService donationService)`.
- `DonateCommand(Messages messages, DonationService donationService)`, `name()` returns `"donate"`.

## Behavior

### Validation (`DonationService`)
1. `url` is valid only when it is not blank, has no whitespace, is at most 512 characters, and parses with `java.net.URI` to scheme `http` or `https` with a non-empty host. Anything else counts as "no link".
2. If `url` is not blank but invalid, log one WARN line at startup (the URL value is allowed in the log; it is not a secret). Do not throw.
3. `label` is trimmed. If blank, use `messages.get("donation.label.default")`. Cut it to 80 characters (the Discord button limit).
4. `get()` returns `Optional.of(new Donation(url, label))` when valid, otherwise `Optional.empty()`. Compute once in the constructor.

### `/donate`
- Quick static reply: no `deferReply()`. The reply is ephemeral.
- Link present: an embed (`EmbedStyle.COLOR`) with title `donate.title` and description `donate.body`, plus one link button (`Button.link(url, label)`). Use the JDA call that compiles with the project's JDA version (`addComponents` or `addActionRow`).
- No link: ephemeral plain text `donate.disabled`.
- Log one INFO line per command like the other commands do (user id, command, duration, outcome).

### `/help`
- Add the line `help.donate` (arguments: label, url) at the end of the description when a link exists. Nothing changes when there is no link.

### `/analyze` embed
- Add one last field named `analyze.field.support` with value `analyze.support.line` (arguments: label, url) when a link exists.
- Add it only if the embed still fits: `builder.length()` plus the field text must stay within `MessageEmbed.EMBED_MAX_LENGTH_BOT`, and the field count must stay below 25. If it does not fit, skip it silently. The analysis content always has priority.

### Web report footer
- `DonationModelAdvice` adds `donation` (`DonationService.Donation` or `null`) with `@ModelAttribute("donation")`.
- In `report.html` footer, when `donation != null`: a paragraph with the text `report.donate.text` and a link `<a th:href="${donation.url}" th:text="${donation.label}" rel="noopener noreferrer nofollow">`. Use `th:text` only. No `th:utext`, no inline `style=`, no `<script`.
- The page must be unchanged when `donation` is `null`.

### User-facing text (all in `messages.properties`)
| Key | Text |
|---|---|
| `donation.label.default` | `Support Rejuv` |
| `donate.description` | `Show how to support Rejuv` |
| `donate.title` | `💛 Support Rejuv` |
| `donate.body` | `Rejuv is free to use. If it helps you improve, you can chip in for hosting and AI costs. Donations are optional and never unlock anything.` |
| `donate.disabled` | `Donations are not set up yet. Thanks for your interest!` |
| `help.donate` | `\n\n💛 **Support Rejuv:** [{0}]({1})` |
| `analyze.field.support` | `💛 Support Rejuv` |
| `analyze.support.line` | `[{0}]({1})` |
| `report.donate.text` | `Rejuv is free. If it helped you, you can support it:` |

Watch the `MessageFormat` traps: no unescaped apostrophe in a pattern that takes arguments. The URL is passed as an argument, never written into the pattern.

## Out of scope
- Removing Patreon, premium gating, `/items` and `/compare` premium checks, usage limits (separate task).
- Any payment processing, webhooks, donor roles, donor lists or storing donations.
- Changing the model, prompts or analysis data.
- Localization.

## Tests
- `DonationPropertiesTest`: binds `rejuv.donation.*` from properties; empty by default.
- `DonationServiceTest`: valid `https` URL; valid `http` URL; blank, null, no scheme, `ftp://`, `javascript:`, whitespace, over 512 characters, no host all give empty; blank label falls back to the default; label over 80 characters is cut; an invalid URL does not throw.
- `DonateCommandTest`: name and description from the catalog; with a link, the reply is ephemeral, has an embed and a link button with the right URL and label; without a link, the reply is ephemeral with `donate.disabled` and no button.
- `HelpCommandTest`: donation line present with a link, absent without one.
- `AnalyzeCommandTest`: support field present with a link and absent without one (use the existing helpers; the short constructor must keep working); skipped when the embed would exceed the size limit.
- `ReportControllerTest`: footer link present with the right `href`, `rel` and label when a `Donation` exists; absent when it does not; the page still has no `<script` and no `style=`; the CSP and the other security headers are unchanged. Add `DonationService` as a mock bean in the test context the way the other services are provided.

## Verification
- `env -u ANTHROPIC_BASE_URL -u ANTHROPIC_CUSTOM_HEADERS ./mvnw verify` must pass. Report the real test counts.
- Save a rendered footer sample under `target/` and describe it in the report (not committed).
- Manual checks to list as done or not done in the report: `/donate` with and without `DONATION_URL`, `/help`, an `/analyze` embed, the report footer, and `docker compose -f compose.prod.yaml config` showing both variables.

## Documentation
Task report `docs/task-reports/050-donations.md`. Do not edit `docs/PROJECT_CONTEXT.md`; list under "Pending integration": the two variables, `/donate`, and where the link appears.

## Acceptance criteria
- [ ] Compiles, all tests pass
- [ ] Unit tests for `DonationService`
- [ ] Follows AGENTS.md conventions (no hardcoded user-facing text, constructor injection, `final` fields, no Lombok)
- [ ] URL and label come only from `DONATION_URL` and `DONATION_LABEL`; changing them needs no code change
- [ ] With no valid URL, `/donate` says donations are not set up, and `/help`, `/analyze` and the report show no donation text
- [ ] CSP and security headers unchanged; no JavaScript, no inline `style=`
- [ ] Only the listed files changed; no new dependency or migration
