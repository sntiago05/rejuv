package sntiago05.rejuv.domain;

import java.util.List;
import java.util.Optional;

/**
 * Detailed telemetry for a finished match.
 *
 * @param matchId      unique match identifier
 * @param durationS    total duration of the match in seconds
 * @param players      telemetry records for all players in the match
 * @param averageBadge average badge across teams (nullable)
 */
public record MatchTelemetry(long matchId, int durationS, List<PlayerTelemetry> players, Integer averageBadge) {

    public MatchTelemetry {
        players = List.copyOf(players);
    }

    public MatchTelemetry(long matchId, int durationS, List<PlayerTelemetry> players) {
        this(matchId, durationS, players, null);
    }

    /**
     * The player with this account id, if they played in the match.
     */
    public Optional<PlayerTelemetry> player(long accountId) {
        return players.stream()
                .filter(p -> p.accountId() == accountId)
                .findFirst();
    }

    /**
     * The player in this slot (0..11), if present. Players with null slot are skipped.
     */
    public Optional<PlayerTelemetry> playerBySlot(int playerSlot) {
        return players.stream()
                .filter(p -> p.playerSlot() != null && p.playerSlot() == playerSlot)
                .findFirst();
    }
}
