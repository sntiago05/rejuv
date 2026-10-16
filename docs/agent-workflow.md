# Agent workflow

## Roles
| Tool | Role |
|---|---|
| Claude Project (claude.ai) | Architect: design, decisions, ADRs, writes task specs in `docs/tasks/`, reviews diffs. Holds `docs/PROJECT_CONTEXT.md`. |
| Antigravity | Main implementer: executes task specs in its own worktree. |
| Claude Code | Reserved for hard tasks: complex bugs, the AI analysis service, risky refactors. |
| Human (me) | Reviews every change, merges to `main`, owns decisions. |

## Shared context
- `AGENTS.md` at repo root is the single source of rules (stack, architecture, conventions).
- `CLAUDE.md` only contains `@AGENTS.md`.
- Skills live in `.claude/skills/` (Claude Code) and are mirrored in `../.agents/skills` (Antigravity).
- Create a skill only after explaining the same thing to an agent 2-3 times.

## Flow for each task
1. **Plan**: pick a card. In the Claude Project, produce a task spec `docs/tasks/NNN-name.md` (copy `docs/tasks/000-template.md`). Write an ADR if a decision is made.
2. **Isolate**: one branch per task, one agent per branch. Use git worktrees so each agent has its own folder:
   `git worktree add ../bot-feature-x -b feature/x`
3. **Implement**: Antigravity opens the worktree and executes the task spec. It should not need to explore beyond the files the spec lists.
4. **Verify**: tests pass in CI; paste the diff (or only the questionable parts) into the Claude Project for review.
5. **Review and merge**: I read and understand the diff before merging. No code I can't explain.
6. **Document**: update `docs/` and ADRs in the same PR.

## Rules
- Never let two agents edit the same files at the same time.
- Small commits, conventional commit messages (`feat:`, `fix:`, `docs:`).
- New ideas go to the parking lot in `roadmap.md`, not into the current task.

## Tooling
- Start simple: git worktrees + one terminal per agent.
- Consider herdr (terminal multiplexer that shows each CLI agent's state) once running 3+ terminal agents in parallel. It does not manage GUI IDEs like Antigravity.

## Token / usage budget
- Planning is cheap in tokens but needs the best reasoning: do it in Claude.
- Implementation is token-heavy (reading files, builds, retries): do it in Antigravity, which has its own quota.
- Claude chats: one topic per chat, paste only relevant code, use Sonnet for routine questions and Opus for architecture.
- Claude Code: `/clear` between tasks, keep `CLAUDE.md`/`AGENTS.md` short.
