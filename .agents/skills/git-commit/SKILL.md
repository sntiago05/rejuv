---
name: git-commit
description: Stage and commit changes in the Rejuv repo with Conventional Commit messages linked to the task's GitHub issue. Use this skill whenever work on a task is finished or a logical step is done and changes need to be committed, or when asked to commit, save progress, or prepare a branch for a pull request. Never pushes.
---

# Commit changes (never push)

## Hard rules
- Never commit on `main`. If the current branch is `main`, stop and tell the user.
- Never run `git push`, `git push --force`, `git rebase` or `git reset --hard`. Pushing is done by the human.
- Never stage `../../../.env`, `.env.*` (except `.env.example`), `target/`, IDE folders or credentials. If any appear in `git status`, stop and warn.
- Never use `git add .` or `git add -A`. Stage explicit paths.

## Steps
1. Run `git branch --show-current`, `git status` and `git diff`.
2. Group the changes into logical commits (e.g. production code, tests, docs). One concern per commit; small is better.
3. Before the last commit, run `./mvnw verify`. If it fails, fix or stop and report; do not commit failing code.
4. For each group: `git add <explicit paths>` then commit with the format below.
5. Finish by printing, for the human to run after reviewing:
   ```
   git log --oneline main..HEAD
   git push -u origin <branch>
   gh pr create --fill --body "Closes #<issue>"
   ```

## Message format (Conventional Commits)
```
<type>(<task>): <summary>

<optional body: what and why, wrapped at 72 chars>

Refs #<issue>
```
- `type`: `feat` (new behavior), `fix` (bug), `test`, `refactor` (no behavior change), `docs`, `chore` (build, config, deps).
- `task`: the three-digit task number from the branch name, e.g. `feature/005-link` gives `005`.
- `summary`: imperative, lowercase, no period, max 72 characters. "add /link command", not "Added link command."
- `Refs #<issue>`: the GitHub issue for the task. Issues were created in task order, so task `005` is usually issue `#5`; confirm with `gh issue list` if unsure.

## Examples
```
feat(005): add /link command with steam id parsing

Accepts SteamID64, profile URLs and account ids, converts them to
account_id and verifies match history before saving.

Refs #5
```
```
test(004): cover 503 retry-after handling in DeadlockApiClient

Refs #4
```