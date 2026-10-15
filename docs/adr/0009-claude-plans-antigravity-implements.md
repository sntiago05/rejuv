# ADR-0009: Claude plans, Antigravity implements

- **Status**: Accepted (supersedes ADR-0007 roles)
- **Date**: 2026-10-15

## Context
Claude Pro usage is limited. Implementation consumes far more tokens than planning (file reads, builds, retries). Antigravity has its own separate quota.

## Options considered
| Option | Pros | Cons |
|---|---|---|
| Claude Code implements, Antigravity reviews | Strong implementation | Burns Claude Pro quota fastest |
| Claude plans, Antigravity implements | Spreads load across two quotas; best reasoning where it matters | Requires precise task specs |
| Antigravity does everything | Saves Claude quota | Loses Claude's design and review strengths |

## Decision
Claude Project designs and writes a task spec per task (`docs/tasks/`). Antigravity implements it in its own worktree. Claude Code is reserved for hard tasks. Human reviews every diff.

## Consequences
- Task specs are the handoff contract: exact files, signatures, behavior, acceptance criteria.
- Revisit if Antigravity quality or quotas are insufficient.
