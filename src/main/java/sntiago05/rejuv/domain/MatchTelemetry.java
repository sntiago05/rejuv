package sntiago05.rejuv.domain;

import java.util.List;
import java.util.Optional;

/**
 * Detailed telemetry for a finished match.
 *
 * @param matchId   unique match identifier
 * @param durationS total duration of the match in seconds
 * @param players   telemetry records for all players in the match
 */
public record MatchTelemetry(long matchId, int durationS, List<PlayerTelemetry> players) {

    public MatchTelemetry {
        players = List.copyOf(players);
    }

    /**
     * The player with this account id, if they played in the match.
     */
    public Optional<PlayerTelemetry> player(long accountId) {
        return players.stream()
                .filter(p -> p.accountId() == accountId)
                .findFirst();
    }
}
