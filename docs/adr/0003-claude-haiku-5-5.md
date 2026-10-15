# ADR-0003: Claude Haiku 5.5 for match analysis

- **Status**: Accepted
- **Date**: 2026-10-15

## Context
The AI analysis is the core feature. It must be cheap enough for a freemium model.

## Options considered
| Option | Pros | Cons |
|---|---|---|
| Claude Haiku 5.5 | 0.10 / 0.50 USD per 1M tokens (in/out, under 100K), fast, effort levels | Less depth than larger models |
| Claude Sonnet | Deeper analysis | Several times more expensive |

## Decision
Haiku 5.5 (`claude-haiku-5-5`), low effort by default, capped `max_tokens`.

## Consequences
- Backend computes metrics; only a compact summary goes to the model (~3K tokens in, ~500 out, ~0.00055 USD per analysis).
- Pricing jumps above 100K input tokens; prompts must stay far below.
- Claude Pro does not include API usage; API billed separately with a monthly spend limit.
- Sonnet could power a future deeper premium report.
