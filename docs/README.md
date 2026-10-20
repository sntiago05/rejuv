# Documentation

Living documentation for Rejuv, an AI coaching Discord bot for Deadlock. Everything here is written in English and kept in the repo so humans and AI agents (Claude Code, Antigravity) share the same source of truth.

| File | What it contains |
|---|---|
| `mvp-spec.md` | Exactly what the MVP bot does: commands, metrics, AI contract, errors |
| `implementation-plan.md` | Task sequence, conventions, definition of done |
| `features.md` | Full feature catalog: commands, data sources, AI usage, tier, timing |
| `product.md` | Vision, target users, features, free model and donations, validation plan |
| `architecture.md` | System overview, stack, layers, data flow |
| `roadmap.md` | Phases with exit criteria |
| `getting-started.md` | Exact steps: Claude Project, repo, worktrees, task loop, daily rhythm |
| `prompts.md` | Copy-paste prompts for specs, implementation and reviews |
| `setup-checklist.md` | Phase 0 accounts, tools and configuration |
| `costs.md` | Cost model summary (details in `costos_bot_deadlock.xlsx`) |
| `agent-workflow.md` | How we work with AI agents, branches and worktrees |
| `adr/` | Architecture Decision Records, one file per decision |
| `tasks/` | Task specs written by Claude, implemented by agents |

## Rules
- Every significant technical or product decision gets an ADR (copy `adr/0000-template.md`).
- ADRs are never deleted. If a decision changes, write a new ADR and mark the old one as `Superseded by ADR-XXXX`.
- When code changes contradict a doc, update the doc in the same pull request.
