# Task 054: Docs cleanup after the free model (051) and the spend guard (052)

- **Branch**: `feature/054-docs-cleanup-free-model`
- **Implementer**: Antigravity
- **Related ADRs**: ADR-0019 (free with donations), ADR-0006 and ADR-0013 (superseded in practice), ADR-0014. **Read only: do not edit any ADR** (AGENTS.md: never rewrite ADR history).

## Goal
Make the project docs tell the truth: no premium tier, no Patreon role check, no weekly usage limits, no `/usage`, no `USAGE_*` / `PREMIUM_*` variables (all removed by task 051), and the AI settings, cost figures and spend guard added by task 052. Documentation only; no code, no config, no tests.

## Verified facts to use (do not invent others)
- 051 removed premium, Patreon, usage limits, `/usage`, `PremiumFeatureGate`, `UsageService`; V6 drops `analysis_usage`. `/items` and `/compare` need only a linked account. Support is an optional donation link (`DONATION_URL`, `DONATION_LABEL`, task 050).
- 052 added `AnalysisSpendGuard` (in memory, rolling 24 h): 60 s cooldown per user, 10 new analyses per user per day, 150 per day in total. Config `rejuv.analysis-limits.*`; env `ANALYSIS_LIMITS_ENABLED`, `ANALYSIS_COOLDOWN`, `ANALYSIS_USER_DAILY`, `ANALYSIS_GLOBAL_DAILY`; a value <= 0 disables that limit. It runs in `AnalyzeService` right before `analysisClient.analyze`, after the stored-analysis branch, so stored analyses are free and never counted. State resets on restart.
- 052 also set `anthropic.max-tokens` 4000, `effort` medium, and added `tips` (up to 3, each `tip`/`evidence`/`drill`) to the AI result.
- `compose.prod.yaml` passes `DONATION_URL` and `DONATION_LABEL` but **not** the `ANALYSIS_*` variables (so in Docker they keep the `application.yaml` defaults unless that file is changed in a later task).
- Before writing any statement about the AI result fields, the flow order or config keys, read `AnalysisResult.java`, `MessagesRequest.java` (`RESULT_SCHEMA`), `AnalyzeService.java` and `application.yaml` and use what is there. If a fact is not in the repo, leave it out.

## Files to create or modify
| File | Change |
|---|---|
| `docs/mvp-spec.md` | Model settings line: effort `medium`, `max_tokens` 4000 (as in `application.yaml`). JSON output contract: match the real result fields (verify in `AnalysisResult`/`RESULT_SCHEMA`; add `tips` and any other field that exists). "Out of scope for MVP" line: remove "Usage limits, premium, Patreon" and say they were dropped (051); mention the spend guard in one sentence where `/analyze` limits are described, if such a place exists. |
| `docs/features.md` | Principles: replace the "Free = ... Premium = ..." pair with the free model (ADR-0019); say the old premium list is now free. Tier column: every "Premium", "Premium (server)" and "Free (limited later)" becomes "Free" (keep the column). Keep rows, timing and data sources unchanged. |
| `docs/product.md` | Replace "Free tier" / "Premium tier" / "Monetization flow" with one free product section plus the optional donation link (ADR-0019, tasks 050-051). `/analyze` line: free, protected by a spend guard (60 s cooldown, 10 per user per day, 150 per day in total). Validation plan: remove "users hitting the limit", the Patreon/Founder pricing test and "before charging" wording; keep retention, referrals and quality checks. |
| `docs/costs.md` | Replace the Patreon / payment fee line with a donation note (no fee assumed until a platform is chosen). Safety section: effort is `medium` (not low). Keep the 052 figures already there. |
| `docs/roadmap.md` | Week 2 heading and day 10 row: keep the history but mark it as built then removed by 051 (free model, ADR-0019). Replace the "Gate to switch premium on" paragraph with a one-line note that premium was dropped. Do not rewrite other rows. |
| `docs/architecture.md` | "Payments" row: Patreon role check becomes "Donations (optional link) / ADR-0019". Package layout: "usage limits" becomes "AI spend guard". Main flow `/analyze`: replace the `UsageService` step with the real `AnalysisSpendGuard` step in its real position (verify in `AnalyzeService`), AI settings "medium effort, `max_tokens` cap". |
| `docs/implementation-plan.md` | Rows 019, 020, 021: append "(premium/limits removed by task 051)" style notes in the task cell; do not delete rows or renumber. No other change. |
| `docs/deployment.md` | Remove `USAGE_LIMITS_ENABLED` and `USAGE_WEEKLY_FREE_ANALYSES` from the required variables. Add an optional block for the four `ANALYSIS_*` variables with their defaults (`true`, `60s`, `10`, `150`) and the note that `compose.prod.yaml` does not pass them yet, so Docker uses the defaults. Donation block: the Patreon example stays valid (it is just a link), no change needed. |
| `docs/README.md` | `product.md` description: "free vs premium" becomes "free model and donations". |
| `docs/task-reports/019-usage-limits.md`, `020-patreon-role-check.md`, `021-items-premium.md`, `038-compare-premium.md` | Add exactly one line right under the title: `> Superseded: the premium tier, Patreon check and usage limits were removed by task 051 (ADR-0019). This report is kept as history.` Nothing else changes. |
| `docs/PROJECT_CONTEXT.md` | "Next" item 4: remove the docs listed as cleaned and keep only what remains (ADRs, `plan-de-implementacion-017-029.md`, the `compose.prod.yaml` gap for `ANALYSIS_*`, the unused `discordUserId` follow-up). Add row 054 to the task table. No other change. |
| `docs/task-reports/054-docs-cleanup-free-model.md` (new) | Task report (see AGENTS.md "After implementation"). |

## Behavior
1. Search first: `grep -rn -i -E "premium|patreon|usage limit|USAGE_|PREMIUM_|/usage|weekly" docs/*.md` and note every hit.
2. Edit only the listed files. Keep the existing tone, headings and table shapes; change the minimum text that makes each statement true. Docs are English.
3. Never state that something is deployed, verified live or tested if it is not.
4. After editing, run the same grep. Every remaining hit must be either (a) in a file under Out of scope, (b) a deliberate historical statement that says it was removed, or (c) a new-tense mention such as the superseded notes. List the remaining hits and why in the task report.

## Out of scope
- All ADRs (`docs/adr/*`), including adding notes to ADR-0006, 0013, 0014 or 0019. ADR-0019 line "no cooldown, deferred" is outdated since 052; the owner decides whether a new ADR is worth it.
- `AGENTS.md`, `CLAUDE.md`, `docs/tasks/*` (other specs), other task reports, `docs/plan-de-implementacion-017-029.md` (historical, Spanish), `docs/manual-testing.md` (already current), `docs/agent-workflow.md`.
- Any code, test, `application.yaml`, `compose*.yaml`, `.env.example`, `pom.xml`, `Dockerfile`, migrations.
- Adding missing rows 039-053 to `implementation-plan.md`.

## Acceptance criteria
- [ ] Only the files in the table above changed (`git diff --name-only`).
- [ ] No doc outside Out of scope describes premium, Patreon role check, weekly usage limits, `/usage`, `USAGE_*` or `PREMIUM_*` as current behavior.
- [ ] `mvp-spec.md`, `architecture.md` and `costs.md` match the real AI settings (effort medium, `max_tokens` 4000) and the 052 spend guard values, checked against the code.
- [ ] `deployment.md` lists no removed variable and documents the `ANALYSIS_*` defaults and the compose gap.
- [ ] Four old task reports have exactly the one-line superseded note.
- [ ] `./mvnw verify` passes (nothing in code changed, so it must still pass).
- [ ] Task report written, including the grep before/after and the remaining hits; `docs/PROJECT_CONTEXT.md` updated as described.
- [ ] Final diff inspected: documentation files only.
