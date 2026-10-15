# Costs

Full model with formulas: `costos_bot_deadlock.xlsx`.

## Initial investment
| Item | Cost |
|---|---|
| Claude API prepaid credits | 5 USD (~20,000 COP) |
| Oracle Cloud Always Free | 0 (card needed for verification) |
| Discord developer account, GitHub, Docker, deadlock-api.com | 0 |
| Domain (optional) | ~50,000 COP/year |

Minimum: ~20,000 COP. Claude Pro is excluded (already paid).

## Running costs
- Claude Haiku 5.5: 0.10 USD / 1M input tokens, 0.50 USD / 1M output tokens (prompts under 100K tokens; verify current pricing).
- Estimated ~3,000 input + 500 output tokens per analysis: ~0.00055 USD (~2 COP) per analysis.
- Patreon / payment processing fee: assumed 12% (verify).

## Safety
- Monthly spend limit set in the Claude Console.
- Cache analyses per match; cap `max_tokens`; low effort level by default.
