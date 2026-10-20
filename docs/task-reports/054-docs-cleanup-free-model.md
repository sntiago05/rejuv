# Task 054: Docs cleanup after the free model (051) and the spend guard (052)

## Status
Completed on branch `feature/054-docs-cleanup-free-model`. Documentation only. Full build (`env -u ANTHROPIC_BASE_URL -u ANTHROPIC_CUSTOM_HEADERS ./mvnw verify`) passes with 1137 tests run, 0 failures, 14 skipped. Not pushed, not merged.

## Summary
Updated repository documentation to align with the free model implemented in task 051 (ADR-0019) and the AI coach settings & spend guard implemented in task 052:
1. `docs/mvp-spec.md`:
   - Updated model settings: effort `medium`, `max_tokens` 4000.
   - Updated JSON output contract: added `focus_area`, `severity`, and `tips` (array of `tip`, `evidence`, `drill`) matching `AnalysisResult` and `MessagesRequest.RESULT_SCHEMA`.
   - Updated `/analyze` step 7 and "Out of scope for MVP" to note that usage limits, premium tier, and Patreon were dropped in task 051, with new analyses protected by the in-memory spend guard instead.
2. `docs/features.md`:
   - Replaced "Free = ... Premium = ..." principles with the free model under ADR-0019.
   - Changed Tier column entries from "Premium", "Premium (server)", and "Free (limited later)" to "Free" across all tables (timing, data sources, and rows preserved).
3. `docs/product.md`:
   - Replaced "Free tier", "Premium tier", and "Monetization flow" with a single free model and features section describing the optional donation link (`DONATION_URL`/`DONATION_LABEL`).
   - Updated `/analyze` line to reflect that it is free and protected by the spend guard (60 s cooldown, 10 daily per user, 150 daily global cap).
   - Cleaned validation plan: removed users hitting limit, Patreon/Founder pricing tests, and "before charging" wording.
4. `docs/costs.md`:
   - Replaced Patreon / payment processing fee line with an optional donations note (no fee assumed until a donation platform is chosen).
   - Updated Safety section: effort is `medium` (was `low`). Preserved task 052 figures.
5. `docs/roadmap.md`:
   - Updated Week 2 heading and Day 10 row to keep historical context while marking premium/limits as built and subsequently removed by task 051 under ADR-0019.
   - Replaced the "Gate to switch premium on" paragraph with a note that premium was dropped.
6. `docs/architecture.md`:
   - Updated "Payments" row in Stack table: replaced Patreon role check with "Donations (optional link) / ADR-0019".
   - Updated package layout: `services/` description changed from "usage limits" to "AI spend guard".
   - Updated `/analyze` main flow: replaced `UsageService` with the real `AnalysisSpendGuard` step in its actual position right before `AnalysisClient.analyze`, and updated AI settings to medium effort and `max_tokens` cap (4000).
7. `docs/implementation-plan.md`:
   - Added notes to rows 019, 020, and 021 stating that usage limits, Patreon check, and the premium gate were removed by task 051. Rows and numbers untouched.
8. `docs/deployment.md`:
   - Removed `USAGE_LIMITS_ENABLED` and `USAGE_WEEKLY_FREE_ANALYSES` from required environment variables.
   - Added documentation for optional `ANALYSIS_*` variables (`ANALYSIS_LIMITS_ENABLED`, `ANALYSIS_COOLDOWN`, `ANALYSIS_USER_DAILY`, `ANALYSIS_GLOBAL_DAILY`) with defaults (`true`, `60s`, `10`, `150`) and a note that `compose.prod.yaml` does not pass them yet.
9. `docs/README.md`:
   - Updated `product.md` row description: "free vs premium" becomes "free model and donations".
10. `docs/task-reports/019-usage-limits.md`, `020-patreon-role-check.md`, `021-items-premium.md`, `038-compare-premium.md`:
    - Added the single superseded notice right under the title: `> Superseded: the premium tier, Patreon check and usage limits were removed by task 051 (ADR-0019). This report is kept as history.`
11. `docs/PROJECT_CONTEXT.md`:
    - Added row 054 to the task table.
    - Updated "Next" item 4 to remove the docs cleaned by this task, keeping only remaining follow-ups (ADRs, `plan-de-implementacion-017-029.md`, `compose.prod.yaml` gap for `ANALYSIS_*`, and unused `discordUserId` parameter).

## Important Files
- Modified canonical documentation:
  - `docs/mvp-spec.md`
  - `docs/features.md`
  - `docs/product.md`
  - `docs/costs.md`
  - `docs/roadmap.md`
  - `docs/architecture.md`
  - `docs/implementation-plan.md`
  - `docs/deployment.md`
  - `docs/README.md`
  - `docs/PROJECT_CONTEXT.md`
- Modified historical reports:
  - `docs/task-reports/019-usage-limits.md`
  - `docs/task-reports/020-patreon-role-check.md`
  - `docs/task-reports/021-items-premium.md`
  - `docs/task-reports/038-compare-premium.md`
- Created:
  - `docs/task-reports/054-docs-cleanup-free-model.md`

## Decisions
- All edits were kept strictly minimal and focused on documentation accuracy without changing table structures, styling, or doc tones.
- ADRs were not touched, in accordance with AGENTS.md rules and spec instructions.

## Verification

### 1. Build Verification
Ran full build:
```bash
env -u ANTHROPIC_BASE_URL -u ANTHROPIC_CUSTOM_HEADERS ./mvnw verify
```
Result:
`BUILD SUCCESS` (1137 tests run, 0 failures, 14 skipped).
*Note*: Unsetting `ANTHROPIC_BASE_URL` and `ANTHROPIC_CUSTOM_HEADERS` is needed in this shell environment because the ambient environment variable routes Anthropic calls through a local gateway (`https://gateway.context-mode.com`), which causes `AnthropicPropertiesTest.bindsDefaultsFromApplicationYaml` to fail if set.

### 2. Keyword Grep Audit
Command executed before and after:
```bash
grep -rn -i -E "premium|patreon|usage limit|USAGE_|PREMIUM_|/usage|weekly" docs/*.md
```

#### Remaining Hits and Justification:
- `docs/deployment.md:52`: Patreon mentioned only as an illustrative example of an optional donation link URL (`e.g., Ko-fi, Patreon`). Valid per spec.
- `docs/features.md:11`: Historical explanation in principles explaining that features formerly planned as premium are now free under ADR-0019.
- `docs/implementation-plan.md:27-29`: Historical task titles for tasks 019, 020, and 021 with explicit "(...removed by task 051)" notes.
- `docs/manual-testing.md:40-43, 114`: Explicitly Out of Scope per task spec ("`docs/manual-testing.md` (already current)"); documents manual verification that `/usage` is gone and premium checks do not trigger.
- `docs/mvp-spec.md:122`: Out of scope section noting that usage limits, premium tier, and Patreon were dropped by task 051.
- `docs/plan-de-implementacion-017-029.md:14-16, 78`: Explicitly Out of Scope per task spec ("`docs/plan-de-implementacion-017-029.md` (historical, Spanish)").
- `docs/product.md:18`: Explains that Rejuv is free with no weekly usage limits.
- `docs/product.md:26`: "weekly retention" in validation plan is a product retention metric, not a bot limit.
- `docs/PROJECT_CONTEXT.md`: All hits are verified historical facts (task summary rows for 019, 020, 021, 038, 051, free model summary, and ADR follow-up in "Next").
- `docs/roadmap.md:3`: "weekly dedication" refers to developer work time.
- `docs/roadmap.md:17, 24, 26`: Historical records of Week 2 plan and Day 10 row noting they were removed by task 051 under ADR-0019.
- `docs/roadmap.md:37`: "Claude Pro usage limits" refers to developer tooling quota limits.
- `docs/roadmap.md:43`: "weekly progress DMs" refers to a potential future roadmap idea.

### 3. Scope and Diff Check
`git status --short` and `git diff --name-only origin/main...HEAD` show only the 14 documentation files listed in the spec table plus this task report. No source code, config files, ADRs, or test files were modified.

## Deviations
None.

## Known Issues
None.

## Merge Risks
None. Documentation only.
