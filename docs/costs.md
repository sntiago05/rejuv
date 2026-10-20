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
- Estimated ~4,000 input + ~2,000 output tokens per new analysis (task 052: 3 tips, `max_tokens` 4000, effort medium): ~0.0014 USD (~6 COP). Worst case with the 4,000-token output ceiling: ~0.003 USD. 5 USD covers roughly 1,700 to 3,500 new analyses. Estimates; real cost is logged per analysis as `estimatedCostUsd`.
- Spend guard (task 052): 60 s cooldown, 10 new analyses per user per day and 150 per day in total; stored analyses are free. Limits are in `application.yaml` (`rejuv.analysis-limits`).
- Donations: optional support link (ADR-0019); no platform payment processing fee assumed until a donation platform is chosen.

## Safety
- Monthly spend limit set in the Claude Console.
- Cache analyses per match; cap `max_tokens`; medium effort level by default.
