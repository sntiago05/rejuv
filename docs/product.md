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

## Features and free model

Rejuv is completely free for everyone with no tiers, paywalls, or weekly usage limits (ADR-0019, tasks 050-051). Operational costs are supported via an optional donation link (`/donate`, configured with `DONATION_URL` and `DONATION_LABEL`).

- `/link`, `/unlink`, `/profile`, `/help`, `/donate`.
- `/analyze`: AI coaching report on the latest match vs same hero and rank. Free, protected by an in-memory spend guard (60 s cooldown, 10 per user per day, 150 per day in total).
- Hero knowledge and personal insights: `/build`, `/counter`, `/meta`, `/heroes`, `/mates`, `/items`, `/compare`, `/leaderboard server`.
See `mvp-spec.md` and `features.md` for exact behavior.

## Validation plan
- Measure weekly retention and organic referrals.
- Quality check: friends who play rate each analysis. Target reaction: "I didn't notice that", not "I already knew that".
- If nobody returns after first use, improve the analysis prompt and coaching quality.

## Risks
- Data coverage: deadlock-api may not have every player's recent matches.
- Competition: many tracker sites already exist; the coaching quality is the product.
