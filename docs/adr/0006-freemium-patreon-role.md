# ADR-0006: Freemium with Patreon and a Discord role check

- **Status**: Accepted (implementation in week 2, behind a feature flag)
- **Date**: 2026-10-15

## Context
We need recurring revenue without handling payments ourselves. Stripe is not directly available in Colombia.

## Decision
- Free: 3 AI analyses per week. Premium: ~2 USD/month per user, unlimited.
- Patreon handles billing and assigns a `Premium` role in the bot's official support server.
- The bot checks that role by user ID (JDA) before applying limits.
- Launch fully free for the first month; introduce limits after validating usage.

## Consequences
- No payment code, no card data.
- Premium users must join the support server.
- Expected conversion 1-3%; verify Patreon fees and payout options (PayPal / Payoneer) for Colombia.
- Revisit Discord native app subscriptions if available in Colombia.
