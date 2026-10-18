# Task 027: `/analyze` accepts a typed or pasted match id and rejects pasted label text

- **Branch**: `feature/027-match-id-input`
- **Implementer**: Antigravity
- **Related ADRs**: ADR-0008 (English, i18n-ready), ADR-0014 (free personal utilities)

## Goal
Make the `match` option of `/analyze` usable in two ways: pick a match from the autocomplete list (as today) **or** type or paste a match id. If the user pastes anything that is not a match id (for example the whole autocomplete label), the bot must say so instead of silently analyzing the latest match.

## Context (verified from the repository, 2026-10-17)
Do not repeat this discovery. If a fact below is false in your checkout, stop and ask.

- Depends on 025 and 019 (both merged in `main`). Start from the latest `origin/main`.
- Observed bug (owner, 2026-10-17): the owner pasted the label `17/10/2026 :h_16: Calico - Victory - 13/4/18 - 26:08 - 118802457` into `match` and `/analyze` returned the **latest** match (Solomon, match 119025753) instead. Picking the same entry from the dropdown worked and was correctly blocked by the weekly limit. Cause (by reading the code): `AnalyzeCommand` declares `match` as `OptionType.INTEGER` with autocomplete, so Discord does not deliver a pasted label as a value, `event.getOption("match")` is `null`, and `handle` falls through to `MatchFilter.LATEST`. This diagnosis was not reproduced against Discord; Step 0 asks you to confirm it.
- Real code: `commands/AnalyzeCommand` builds the option at `data()` (`new OptionData(OptionType.INTEGER, "match", ...)` with `setAutoComplete(true)`), reads it in `handle` (`filter = new MatchFilter(matchOpt.getAsLong(), null, null)`), and fills the choices in `autocompleteMatch` (`new Command.Choice(choiceName, m.matchId())`, up to 10 choices from `MatchSearchService.find`). `MatchFilter(Long matchId, Integer heroId, Boolean win)`. `MatchSearchService.select` finds a match **only inside the 200-entry cached history**; a valid id outside it ends in `MatchNotFoundException` and the existing `analyze.match_not_found` reply.
- `analyze.option.match` and `analyze.match_not_found` already exist in `messages.properties`.
- Rules from `AGENTS.md`: no business logic in `commands/` beyond input validation, user-facing text in `messages.properties` (messages with arguments contain no single apostrophe), no new dependency.

## Step 0: verify (before writing code)
1. Confirm in JDA 5.6.1 (sources or `javap`) that an `OptionType.STRING` option supports autocomplete, and that `Command.Choice(String name, String value)` exists; record the signatures in the report.
2. Confirm that a `STRING` choice value may be at most 100 characters (a match id has at most about 10 digits).
3. Confirm the diagnosis above if you can reproduce it in the dev guild; otherwise say "not reproduced" in the report. The fix does not depend on it.

## Files to create or modify
| File | Change |
|---|---|
| `src/main/java/sntiago05/rejuv/commands/AnalyzeCommand.java` | **Modify.** `match` becomes a `STRING` option with autocomplete; choice value is the match id as text; parse and validate before any API call. |
| `src/main/resources/messages.properties` | **Modify.** Add `analyze.match_invalid` at the **end of the file**. |
| `src/test/java/sntiago05/rejuv/commands/AnalyzeCommandTest.java` | **Modify.** Option type, choice values, valid id, invalid text. |
| `docs/task-reports/027-match-id-input.md` | **Create.** Task report (format of `docs/task-reports/014-heroes.md`). |

No `pom.xml` change, no migration, no service change. If you think another file is needed, stop and ask.

## Interfaces / signatures
- Option: `new OptionData(OptionType.STRING, "match", messages.get("analyze.option.match"), false).setAutoComplete(true)` (keep the existing description key; update its text only if it says the value must be chosen from the list, see Behavior).
- Autocomplete choices: `new Command.Choice(choiceName, String.valueOf(m.matchId()))`. The label text (`choiceName`) is unchanged.
- Parsing rule, a private static helper in `AnalyzeCommand` returning `OptionalLong`: trim the value; accept only a non-empty string of ASCII digits with 1 to 18 characters; the parsed value must be greater than 0. Anything else is "invalid". Do not accept the label, a URL, a sign or separators.

## Behavior
1. `/analyze` with no `match` option behaves exactly as today (`hero` and `result` filters, then latest).
2. `match` present and valid (digits only): `filter = new MatchFilter(id, null, null)`; the rest of the flow (linked account check, `deferReply()`, `answer`) is unchanged. Choosing from the dropdown yields the same digits and the same flow.
3. `match` present and invalid (for example the pasted label, or `abc`): reply **ephemeral** with `analyze.match_invalid` and return **before** the linked-account lookup, before `deferReply()` and before any service call. No usage row, no API call.
4. `match` present and valid but not in the account's cached history: unchanged, `analyze.match_not_found` (existing behavior).
5. `match` and `hero`/`result` together: `match` wins, as today.
6. Autocomplete behavior is otherwise unchanged (same query, same limit of 10, same filters from `hero` and `result`, empty list for unlinked users or on failure).

### User-facing text (English)
- `analyze.match_invalid`: tells the user that the value is not a match id and to either pick a match from the list or type only the numeric match id. No apostrophes, no arguments.
- If `analyze.option.match` currently says the value is picked from a list, adjust its wording to "pick a match from the list or type a match id" (a change to this existing key is authorized).

## Tests
1. `data()` declares `match` as `STRING` with autocomplete enabled.
2. Autocomplete returns choices whose `getAsString()` equals the match id as text and whose name is the unchanged label.
3. `handle` with `match` = `"118802457"` passes `MatchFilter(118802457L, null, null)` to the service, with the caller id (the existing caller-id assertion is kept).
4. `handle` with `match` = the full label from the Context section replies ephemeral `analyze.match_invalid`, and `ProfileService`, `AnalyzeService` and the deferral are never touched (`verifyNoInteractions` or equivalent).
5. Table-driven invalid values: empty, spaces only, `abc`, `-5`, `0`, `12 34`, 19 digits, `1e9`. Table-driven valid values: `"1"`, `" 118802457 "` (trimmed), 18 digits.
6. All catalog keys used by the command resolve (follow the existing pattern in `AnalyzeCommandTest`).

## Verification
1. `./mvnw clean verify` passes with Docker running (Windows: `.\mvnw.cmd clean verify`; see the `MAVEN_USER_HOME` note in `docs/PROJECT_CONTEXT.md`). Report the real test count and the baseline difference.
2. `git diff --check`; the diff contains only the files in the table; `.env` never read or printed.
3. Manual check (needs the usual credentials): in the dev guild run `/analyze match:` with (a) an entry picked from the dropdown, (b) the same id typed by hand, (c) the pasted full label. Expected: (a) and (b) analyze the same match; (c) the ephemeral `analyze.match_invalid` message and nothing analyzed. If you do not run it, say so in the report.

## Documentation
- Task report `docs/task-reports/027-match-id-input.md`: status, summary, files, commands run with real results, deviations, known issues.
- `docs/PROJECT_CONTEXT.md`: **do not edit in this branch** while other task branches are active; mark it "pending integration" and list the facts (`match` is now a STRING option, the validation rule, the new key).
- Do not edit ADRs, `docs/mvp-spec.md`, `docs/implementation-plan.md` or other specs.

## Out of scope
- Looking up a match id that is **not** in the 200-entry history (it still ends in `analyze.match_not_found`).
- Accepting the label text, a URL or a pasted list line and extracting the id from it.
- Changes to `/matches`, `MatchSearchService`, `AnalyzeService`, usage limits or the prompt.
- Task 026 (items and charts) also edits `AnalyzeCommand`; **merge 027 before 026**.

## Acceptance criteria
- [ ] `match` accepts a dropdown choice and a typed or pasted numeric id with the same result
- [ ] Pasted label text or any non-numeric value gives the ephemeral `analyze.match_invalid` and triggers no service call
- [ ] No option still analyzes the latest match, unchanged
- [ ] All user-facing text is in `messages.properties`
- [ ] Compiles, all tests pass (`./mvnw clean verify`)
- [ ] Follows AGENTS.md conventions; no new dependency
- [ ] Task report created; `docs/PROJECT_CONTEXT.md` update marked pending integration; final diff has no unrelated files

## Decisions and open questions
Decision taken in this spec (architect, owner can veto): switch the option type to `STRING` rather than add a second option, because a numeric option cannot carry pasted text and two options for one concept confuse users. Open: whether to extract the trailing id from a pasted label instead of rejecting it (not blocking; rejecting is the safe default).
