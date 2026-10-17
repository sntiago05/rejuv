package sntiago05.rejuv.clients;

import sntiago05.rejuv.domain.MatchTelemetry;

/**
 * Port providing detailed telemetry for a finished match.
 */
public interface MatchMetadataSource {

    /**
     * Detailed telemetry for a finished match.
     *
     * @param matchId match identifier
     * @return telemetry for the match
     * @throws MatchMetadataNotFoundException  the API says the match has no metadata (HTTP 404)
     * @throws DeadlockApiUnavailableException busy, rate limited, Steam fallback failed, timeout, I/O
     * @throws DeadlockApiException            any other failure (unexpected status, unreadable or invalid body)
     */
    MatchTelemetry getMatch(long matchId);
}
