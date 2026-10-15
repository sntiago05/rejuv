# ADR-0008: English for the bot and all project work

- **Status**: Accepted (supersedes the Spanish-first positioning in product.md)
- **Date**: 2026-10-15

## Context
The original plan targeted Spanish-speaking players, using language as a differentiator. The developer wants the bot and all artifacts in English.

## Options considered
| Option | Pros | Cons |
|---|---|---|
| Spanish only | Less competition, clear niche | Smaller market, lower willingness to pay in USD |
| English only | Largest market, better portfolio, international clients | More competition; differentiation must come from analysis quality |
| English first, i18n-ready | Best of both later at low cost | Small upfront discipline |

## Decision
English first, built i18n-ready: every user-facing string lives in `messages.properties` (Spring `MessageSource`), never hardcoded. Code, docs, commits, PRs and ADRs in English. Commands: `/link`, `/profile`, `/analyze`.

## Consequences
- The quality of the AI analysis is now the main differentiator.
- Adding Spanish later = add `messages_es.properties` and a language preference per user/server.
- The AI prompt asks for the response in the user's configured language (default English).
