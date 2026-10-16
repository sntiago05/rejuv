# ADR-0007: Multi-agent workflow with shared context

- **Status**: Superseded by ADR-0009 (roles changed; shared-context rules still apply)
- **Date**: 2026-10-15

## Context
Development uses Claude (Project + Claude Code) and Antigravity. Agents need consistent rules and must not conflict.

## Decision
- `AGENTS.md` as single source of rules; `CLAUDE.md` imports it.
- Same SKILL.md format mirrored in `.claude/skills/` and `../../.agents/skills`.
- One agent per branch, git worktrees for isolation, human review before merge.
- Docs and ADRs in English, updated in the same PR as the code.
- herdr postponed until running 3+ terminal agents in parallel.

## Consequences
See `docs/agent-workflow.md`.
