# ADR-0016: Application Emoji Icons for Rich Embed Presentation

- **Status**: Accepted
- **Date**: 2026-10-17

## Context
Discord embeds permit only a single thumbnail and a single large image per embed. Commands such as `/build`, `/counter`, and `/meta` display multiple items and hero matchups, where inline icons next to each name are essential for a friendly, readable presentation. Furthermore, we must not store image binary files in the bot repository, nor crash or delay commands if image assets or Discord emoji endpoints are unreachable.

## Options considered
| Option | Pros | Cons |
|---|---|---|
| 1. Discord application emojis uploaded from deadlock-api URLs | Renders natively inline beside text in field values and descriptions; no local image storage; cached permanently by Discord CDN | Requires JDA >= 5.2.0; 2,000 app emoji limit; initial sync takes a few minutes |
| 2. Composite generated images as attachments | Full layout freedom | Heavy CPU/memory overhead; complex image rendering pipeline; attachment latency |
| 3. Thumbnail-only presentation (no inline icons) | No JDA bump or emoji sync required | No per-item pictures in builds and counter guides; inferior UX |

## Decision
We use Discord application emojis for inline hero and item icons, uploaded sequentially in the background from deadlock-api image URLs upon bot startup, with an automatic text-only fallback.

To support the Discord application emoji API (`retrieveApplicationEmojis()`, `createApplicationEmoji()`), JDA is bumped from 5.0.0 to 5.6.1. Emojis are named `h_<heroId>` and `i_<itemId>`, created only for heroes with `icon_image_small` and shop items of type `upgrade` with an `image` (about 315 emojis total, well under Discord's 2,000 application emoji cap). A hard cap of `MAX_NEW_EMOJIS = 400` per run prevents runaway creation. If emoji synchronization fails or has not finished, every command falls back smoothly to text-only rendering without failing.

## Consequences
- Inline hero and item icons render cleanly in embed field values and descriptions across `/profile`, `/meta`, `/build`, and `/counter`.
- Hero cards serve as thumbnails for `/build` and `/counter`; rank badges serve as thumbnails for `/profile`.
- No image binary files are stored in the repository; all assets originate from deadlock-api CDN URLs.
- Soft degradation ensures zero user-facing errors if image assets or Discord emoji APIs are unavailable.
- JDA is bumped to 5.6.1 across the project.
