---
name: execute-task
description: Implement a numbered Rejuv task spec from docs/tasks/ end to end, following AGENTS.md - read the spec and only the docs it needs, check the branch and dependencies, implement exactly the listed files, run ./mvnw verify, check every acceptance criterion, write the task report and update or defer docs/PROJECT_CONTEXT.md. Takes a task number (for example 004, 4 or 004-deadlock-api-client). Use when asked to execute, implement, do, build or finish a task. Never pushes, never merges.
---

# Execute a task spec

## Usage
```
/execute-task 004
/execute-task 4
/execute-task 004-deadlock-api-client
```
The argument is the task number. You are the **implementer** (AGENTS.md): the design is already in the spec and the ADRs. Implement exactly the spec. Do not redesign.

## Hard rules
- Work only on the task branch inside its worktree. Never on `main`. Never create the branch here: that is `/task-worktree`.
- Touch only the files in the spec's "Files to create or modify" table, plus the closeout files: `docs/task-reports/NNN-<slug>.md` and `docs/PROJECT_CONTEXT.md`.
- No new dependency, no architecture change, no ADR edit, no edit to another task spec, unless the spec says so.
- Never read, print or commit `.env`. Never commit real user data (real account ids, match ids, player names), tokens or keys.
- Never `git push`, merge, rebase, `reset --hard` or force anything. The human pushes and merges.
- Never claim a test passed unless you ran it and saw it pass. Report real failures with their output.
- **Stop and ask** (one short question, with the options you see) when the spec is ambiguous, contradicts `AGENTS.md` or the code, names a class or file that does not exist when it should, needs a file outside its table, or has a "stop and ask" condition that triggers. Never guess.

## Steps

### 1. Resolve the task
1. Take the argument. Strip a trailing `.md`. Pad the leading digits to 3: `N=$(printf '%03d' $((10#<digits>)))`.
2. `ls docs/tasks/$N-*.md`. Ignore `000-template.md`. Zero or more than one match: stop and list what you found. If the argument has a slug, it must match the file name.
3. Read the spec in full. Keep: the `Branch` line, the Files table, Interfaces, Behavior, Tests, Verification, Documentation, Out of scope, Acceptance criteria, Decisions and open questions.
4. If `docs/task-reports/$N-*.md` already exists, read it. It says what is already done. Continue from there; do not redo or overwrite verified work.

### 2. Check the workspace (read only)
- `git branch --show-current` must equal the spec's `Branch`. If not, stop. Tell the user to run `/task-worktree <N>` and open that worktree.
- `git status --short`: if there are changes you did not make, stop and ask.
- `git fetch origin main` then `git merge-base --is-ancestor origin/main HEAD`. If `main` moved ahead, report it. Do not rebase or merge; ask.
- Dependencies: `grep -E "^\| $N \|" docs/implementation-plan.md` gives the "Depends on" column. For each dependency, check for evidence in this tree: its task report in `docs/task-reports/` **and** the code it adds. Missing: stop and report. Never assume a task is merged.
- Note other active task work for step 6: `git worktree list` and `git branch --no-merged origin/main --format='%(refname:short)' 'feature/*'`.

### 3. Load context (only what the task needs)
Read in this order, then stop exploring:
1. `AGENTS.md`, `docs/PROJECT_CONTEXT.md`, `docs/agent-workflow.md`.
2. The docs, ADRs (`docs/adr/NNNN-*.md`), task reports and source files the spec names.
3. The source and tests the task calls or extends, to confirm real names and signatures.

Do not read all ADRs or all docs. If the spec has a "Step 0" or "To verify" items, do them now and keep the results for the report. If a result changes scope, stop and ask.

### 4. Baseline
Run the full build once before you change anything, and record the test count:
- Linux, this machine: `.claude/skills/run-rejuv/driver.sh verify` (Docker access for Testcontainers). If that driver is missing, use `./mvnw verify`.
- Windows: `.\mvnw.cmd verify` (see the Task 001 report for the `MAVEN_USER_HOME` workaround).

If the baseline fails, stop and report: do not build on a red tree. If the count differs from the one the spec expects, note it for the report.

### 5. Implement
1. Make a checklist (task list) from the Files table, every listed test, and every acceptance criterion.
2. Follow the conventions in `AGENTS.md` (packages, `Messages`, records, constructor injection, SLF4J, no Lombok). For slash command work, also follow the `new-discord-command` skill.
3. Write the tests the spec lists with the exact names it gives. Fixtures go in `src/test/resources/fixtures/`, with fake ids only.
4. Database changes only as a new `V<n>__*.sql` migration. Never edit an applied one.
5. Run the narrow tests often: `./mvnw -q test -Dtest=<ClassName>`.
6. Commit at logical steps with the `git-commit` skill (Conventional Commits, `<type>(<NNN>): ...`, explicit paths).

### 6. Verify
All of these must hold. Fix and re-run until they do, or stop and report the real failure.
1. Full build passes (same command as step 4). The test count equals the spec's expected total, or you can explain the difference.
2. Each acceptance criterion: check it and note the evidence (a test name, a command output, a diff line).
3. Manual check, when the task has runtime behavior. Use the `run-rejuv` skill if it exists:
   `driver.sh cmd <name> [opt=value]` for a slash command, `driver.sh eval '<java>'` for a service or client,
   `driver.sh boot` for startup and Flyway, `driver.sh psql '<sql>'` for the schema. If you cannot run a
   check (no token, no network), say so. Do not claim it.
4. Scope: `git diff --name-only origin/main...HEAD` plus `git status --short` show only files from the spec's table and the closeout files.
5. `git diff --check` is clean. No line-ending-only changes. `git grep` the diff for tokens, keys and real ids.

### 7. Close out
1. **Task report** `docs/task-reports/$N-<slug>.md`. Use the format of the latest report (for example `003-postgres-flyway-linked-account.md`):
   `# Task NNN: <title>`, then `## Status`, `## Summary`, `## Important Files`, `## Decisions`, `## Verification`
   (commands you really ran, with their real results and test counts), `## Deviations` (each with its reason, or "None"),
   `## Known Issues`, `## Merge Risks`. Add every extra section the spec's "Documentation" part asks for.
2. **`docs/PROJECT_CONTEXT.md`**: if step 2 found no other active unmerged task branch, update "Current status" with verified facts only (Done, Next, constraints, known limitations). Otherwise, do not touch it. Write "PROJECT_CONTEXT update pending integration" in the report.
3. Do not edit `docs/mvp-spec.md`, `docs/architecture.md`, the roadmap or ADRs unless the spec says so. List the doc follow-ups it names in the report.
4. Commit the docs (`docs(<NNN>): ...`) with the `git-commit` skill. Do not push.

### 8. Reply
Short, in this order (AGENTS.md "Done means"):
- Files changed.
- How to test it by hand (the exact `driver.sh` commands, when they apply).
- Verification commands and their results.
- Documentation updated (or "PROJECT_CONTEXT pending integration").
- What the reviewer should double-check (deviations, Step 0 results, risky spots).
- Remaining risks, blockers and follow-up work.
- For the human: `git log --oneline origin/main..HEAD` and `git push -u origin <branch>`.

If any step stopped the task, reply with: where it stopped, why, what is already done, and the question for the owner.
