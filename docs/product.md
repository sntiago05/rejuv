# Product

## Vision
An English-language Discord bot that acts as a personal coach for Deadlock players. It turns raw match data into concrete, actionable advice using AI, so players feel they are improving.

## Target users
- English-speaking Deadlock players worldwide (largest market).
- Later: Spanish via i18n (see ADR-0008).
- Discord communities and streamers' servers that play Deadlock.

## Differentiation
Stats sites (e.g. Statlocker) already show numbers for free. Our value is:
1. AI explanations that are specific and personal ("you bought your key item 4 minutes later than higher-ranked players"), not generic tips.
2. Social features inside the server (rankings, progress, highlights).

## Features

### Free tier
- `/link`, `/unlink`, `/profile`, `/help`.
- `/analyze`: AI coaching report on the latest match vs same hero and rank (limited to 3 per week when premium is switched on).
See `mvp-spec.md` for the exact behavior.

### Premium tier (planned, ~2 USD/month per user)
- Unlimited AI analyses.
- Weekly progress tracking.
- Build recommendations per hero and play style.
- Server rankings and automatic highlight alerts.

## Monetization flow
1. User hits the free limit; the bot shows a Premium button linking to Patreon.
2. User subscribes on Patreon and links Discord.
3. Patreon assigns a `Premium` role in the bot's official support server.
4. Before each command the bot checks that role by user ID.
5. Patreon collects payments monthly; payouts go to PayPal or Payoneer.
See ADR-0006.

## Validation plan
- Month 1: everything free. Measure weekly retention, users hitting the limit, organic referrals.
- Quality check: friends who play rate each analysis. Target reaction: "I didn't notice that", not "I already knew that".
- Pricing test: "Founder" plan at 1 USD/month for the first 20 users. If it fills, raise the price for new users.
- If nobody returns after first use, improve the analysis before charging.

## Risks
- Data coverage: deadlock-api may not have every player's recent matches.
- Competition: many tracker sites already exist; the coaching quality is the product.
