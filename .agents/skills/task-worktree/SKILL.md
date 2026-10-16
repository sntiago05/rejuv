---
name: task-worktree
description: Create an isolated Git worktree and feature branch for a Rejuv task, based on the latest origin/main. Takes a task number or task filename (for example 003, 003-postgres-flyway-linked-account or 003-postgres-flyway-linked-account.md). Use when starting work on a numbered task, or when asked to prepare a workspace, branch or worktree for a task. Never implements the task and never pushes.
---

# Create a task worktree

## Usage
```
/task-worktree 003
/task-worktree 003-postgres-flyway-linked-account
/task-worktree 003-postgres-flyway-linked-account.md
```
Result for task 003:
- Branch: `feature/003-postgres-flyway-linked-account`
- Worktree: `../rejuv-wt/003` (sibling of the repo folder)

This skill only prepares the workspace. Do not implement the task.

## Hard rules
- Never create the branch from the current branch. The base is always `origin/main`.
- Never checkout, reset, merge, stash or pull in the current working directory.
- Never use `--force`, `-B`, `git branch -D`, `git worktree remove` or `rm`. Never overwrite an existing directory or branch.
- Never push.
- If `git fetch origin main` fails, stop. Do not fall back to a local or stale `main`.
- If anything is unclear or conflicting, stop and explain. Do not guess.

## Steps
Run the git commands with `git -C <repo-root>`. They work the same in PowerShell and in Unix shells. Quote paths.

1. **Repo root.** `git rev-parse --show-toplevel`. If the current directory is itself a linked worktree, `git rev-parse --git-common-dir` still points to the main repo; use the root of the working tree you are in only to read `docs/tasks/`.
2. **Resolve the task.** Take the input. Strip a trailing `.md`. Take the leading digits as the task number and pad them to 3 digits.
   - List `docs/tasks/<NNN>-*.md` (ignore `000-template.md`).
   - If the input had a full slug, the file name must match it exactly.
   - If zero or more than one file matches, stop and list what was found.
   - Read the task spec. If it has a `Branch` line that differs from `feature/<NNN>-<slug>`, stop and report the difference.
3. **Name things.** `id` = `NNN`. `slug` = file name without `NNN-` and `.md`; it must be lowercase letters, digits and hyphens only, otherwise stop.
   - Branch: `feature/<id>-<slug>`
   - Worktree path: `<repo-root>/../rejuv-wt/<id>` (resolve to an absolute path; show it with the OS path style).
4. **Fetch.** `git -C <root> fetch origin main`. Then `git -C <root> rev-parse origin/main` and keep the hash as `BASE`.
5. **Check for conflicts** (read only):
   - Branch exists locally? `git -C <root> show-ref --verify --quiet refs/heads/<branch>`.
   - Branch exists on origin? `git -C <root> ls-remote --exit-code --heads origin <branch>`.
   - Worktree known to git? `git -C <root> worktree list --porcelain`.
   - Path exists on disk? `Test-Path` (PowerShell) or `test -e` (Unix).
6. **Decide.**
   - Nothing exists: go to step 7.
   - Worktree for this path is already registered, on `<branch>`: it is safe to reuse. Report its HEAD, `git -C <path> status --short` and whether `BASE` is an ancestor (`git merge-base --is-ancestor BASE HEAD`). Do not change it. Skip to step 8.
   - Branch exists (local or remote) but has no worktree: stop. Report the branch tip and `git rev-list --count origin/main..<branch>`. Tell the user to choose (use `git worktree add <path> <branch>` themselves, or rename). Do not do it for them.
   - Path exists but is not a registered worktree, or the branch is checked out in another worktree: stop and report.
7. **Create.** Create the parent folder if it is missing (`New-Item -ItemType Directory -Force ../rejuv-wt` in PowerShell, `mkdir -p ../rejuv-wt` in Unix; this only makes the parent). Then:
   ```
   git -C <root> worktree add -b <branch> <path> BASE
   ```
   `-b` fails if the branch exists, which is the wanted safety. Use the `BASE` hash, not the name, so the start commit is exact. If it fails, report the error and stop.
8. **Verify.** All of these must hold, otherwise report the failure:
   - `git -C <path> branch --show-current` equals `<branch>`.
   - `git -C <path> rev-parse HEAD` equals `BASE` (for a new worktree).
   - `git -C <root> worktree list` shows `<path>`.
   - `git -C <root> branch --show-current` is the same as before you started.
9. **Report.** Print a short result:
   ```
   Task:      <id> <slug>  (docs/tasks/<file>)
   Branch:    <branch>
   Worktree:  <absolute path>
   Base:      origin/main @ <short hash>
   Status:    created | reused (<state>)

   Next:
     Claude Code:  cd "<absolute path>" ; claude
     Antigravity / IntelliJ: open the folder "<absolute path>"
     Then implement docs/tasks/<file> following AGENTS.md.
   When done: the human pushes (git push -u origin <branch>).
   ```
   Note that `.env` and `target/` are not shared with the new worktree; copy `.env` by hand if the task needs it (never print it).
