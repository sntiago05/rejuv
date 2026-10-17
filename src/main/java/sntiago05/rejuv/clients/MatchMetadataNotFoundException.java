package sntiago05.rejuv.clients;

/**
 * Thrown when deadlock-api indicates the match has no metadata (HTTP 404).
 */
public class MatchMetadataNotFoundException extends DeadlockApiException {

    public MatchMetadataNotFoundException(String message) {
        super(message);
    }
}
