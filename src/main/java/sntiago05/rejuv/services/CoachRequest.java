package sntiago05.rejuv.services;

import java.util.List;

import sntiago05.rejuv.clients.dto.HeroAsset;
import sntiago05.rejuv.clients.dto.ItemAsset;
import sntiago05.rejuv.clients.dto.MatchHistoryEntry;
import sntiago05.rejuv.domain.MatchTelemetry;
import sntiago05.rejuv.domain.PlayerTelemetry;

/**
 * Context provided to {@link CoachInsightProvider}s when generating coaching insights.
 *
 * @param accountId Steam account ID of the target player
 * @param match     the match being analyzed
 * @param history   cached recent match history of the player (newest first)
 * @param telemetry full match telemetry, or null in degraded mode
 * @param player    player telemetry of the target player, or null if telemetry unavailable / player missing
 * @param rankTier  rank tier (0..11) for benchmark comparisons, or null if unranked / unknown
 * @param heroes    all hero assets
 * @param items     all item assets
 */
public record CoachRequest(long accountId,
                            MatchHistoryEntry match,
                            List<MatchHistoryEntry> history,
                            MatchTelemetry telemetry,
                            PlayerTelemetry player,
                            Integer rankTier,
                            List<HeroAsset> heroes,
                            List<ItemAsset> items) {

    public CoachRequest {
        history = history == null ? List.of() : List.copyOf(history);
        heroes = heroes == null ? List.of() : List.copyOf(heroes);
        items = items == null ? List.of() : List.copyOf(items);
    }
}
