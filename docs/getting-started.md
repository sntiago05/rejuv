# Getting started: exact steps

## 1. Claude Project (claude.ai)
1. Sidebar → **Projects** → **Create project**. Name: `Rejuv`.
2. **Instructions**: paste
   > Read PROJECT_CONTEXT.md first. You are the architect of this project: you design, write task specs (docs/tasks template) and review diffs; you do not implement whole features here. Explain decisions to me in Spanish; everything produced (code, docs, specs, commits) is in English. Keep answers focused; ask before large changes.
3. **Project knowledge**: upload only `PROJECT_CONTEXT.md`, `AGENTS.md`, `docs/mvp-spec.md`, `docs/features.md`, `docs/tasks/000-template.md`. Keep it small; paste other docs into a chat only when needed.
4. Model: Sonnet for daily specs/reviews; switch to Opus for hard design questions.

## 2. Repository
1. GitHub → New repository → private, name `rejuv`, **empty**.
2. start.spring.io:
   - Maven, Java 21, Spring Boot **4.1.x (latest stable)**, Jar
   - Group `dev.<your-github-user>`, Artifact `rejuv`, package `sntiago05.rejuv`
   - Dependencies: Spring Data JPA, PostgreSQL Driver, Flyway Migration, Validation, Spring Cache Abstraction, Docker Compose Support
3. Unzip, copy into it: `docs/`, `scripts/`, `.agent/`, `.claude/`, `AGENTS.md`, `CLAUDE.md`, `PROJECT_CONTEXT.md`.
4. ```bash
   git init && git add . && git commit -m "chore: initial skeleton and docs"
   git branch -M main
   git remote add origin https://github.com/<user>/rejuv.git
   git push -u origin main
   ```
5. Install GitHub CLI, `gh auth login`, then `bash scripts/create-issues.sh`. Create a **Project (board)** in GitHub and add the issues; columns: Todo / In progress / Review / Done.

## 3. Folder layout on disk
```
dev/
├── rejuv/        main branch: you, in IntelliJ (review, run, merge)
└── rejuv-wt/     one folder per task branch, created with git worktree
```

## 4. The loop for every task
| Step | Where | What |
|---|---|---|
| 1. Spec | Claude Project chat | "Write the spec for task NNN" (prompt in `prompts.md`). Save it as `docs/tasks/NNN-name.md` on `main`, commit `docs: spec NNN`. |
| 2. Worktree | Terminal in `rejuv/` | `git worktree add ../rejuv-wt/NNN -b feature/NNN-name` and copy `.env` into it |
| 3. Implement | Antigravity (or Claude Code if labeled) opened on that worktree folder | Paste the implement prompt from `prompts.md` |
| 4. Verify | Same worktree | `./mvnw verify`, run the bot, try the command in the dev server |
| 5. PR | Terminal | `git push -u origin feature/NNN-name` then `gh pr create --fill` |
| 6. Review | IntelliJ diff + Claude Project | Read the diff yourself; for non-trivial tasks paste it with the review prompt |
| 7. Merge & clean | Terminal | `gh pr merge --squash --delete-branch`, `git worktree remove ../rejuv-wt/NNN`, `git pull` |

## 5. Parallel work (dependencies)
```
001 ─┬─ 002
     ├─ 003 ─┐
     ├─ 004 ─┴─ 005 ─┬─ 006
     │       └─ 007 ─┼─ 009 ─ 010
     └─ 008 ─────────┘
```
- 001 alone first (everything touches `pom.xml`).
- Then up to two agents at once on independent tasks, e.g. Antigravity on 003 while Claude Code does 008.
- Avoid two open branches that both edit `pom.xml` or `application.yaml`; merge one, rebase the other.

## 6. Daily rhythm
- **Start of day (15 min):** in one Claude Project chat, write the specs for that day's tasks.
- **During the day:** run the loop per task.
- **End of day (10 min):** update `PROJECT_CONTEXT.md` → "Current status", re-upload it to the Claude Project, move cards.
- New chat per day or per task in the Claude Project; never one endless chat.
