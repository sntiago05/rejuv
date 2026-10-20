# Deadlock-style notes for the web report (task 055)

## What was actually checked (2026-10-20)
- Two web searches returned only titles and short summaries. No page could be opened: `playdeadlock.com` and `statlocker.gg` failed (certificate error in the fetch tool, no connection from the shell). **No colors, fonts or CSS were read from any site.**
- Search summaries only confirmed: Deadlock is set in an occult, 1920s-style alternate New York with a "noir" mood (Wikipedia, Dexerto). Statlocker, Tracklock, Deadlock Labs and tracker.gg are existing trackers whose match page lists all 12 players in two teams.
- The owner's two screenshots of a tracker match page (conversation, 2026-10-20) are the only visual reference: near-black panels with thin borders, a green bar on the winning team, team header with totals, one row per player (hero portrait, name, rank number, K/D/A with KDA, creeps, kill participation, damage with a thin bar, item icons), small letter grades, muted blue links.

## Decisions (original design, not copied from any site)
- Original CSS only. No game art, logos or fonts. No external fonts or CDNs (CSP stays `default-src 'none'; img-src 'self'; style-src 'self'`).
- Mood: noir with brass accents. Surfaces charcoal, text warm off-white, accent brass/gold (the same gold the charts use for "you").
- Teams use the chart colors (blue and orange) plus the words "Your team" and "Enemy team"; outcome is a word, never only a color.
- Exact hex values are chosen in `src/main/tailwind/report.css` and their contrast is checked by a unit test, not copied from a reference.
- Not done: no comparison with the real game UI. For a closer match, the owner can provide in-game screenshots.
