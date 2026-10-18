package sntiago05.rejuv.clients;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import sntiago05.rejuv.domain.MatchTelemetry;

/**
 * Composite {@link MatchMetadataSource} that reads from the JSON metadata endpoint first, falling back to the raw
 * protobuf endpoint when JSON is unavailable or returns an invalid body.
 */
@Primary
@Component
public class FallbackMatchMetadataSource implements MatchMetadataSource {

    private static final Logger log = LoggerFactory.getLogger(FallbackMatchMetadataSource.class);

    private final JsonMatchMetadataSource json;
    private final RawMatchMetadataSource raw;

    public FallbackMatchMetadataSource(JsonMatchMetadataSource json, RawMatchMetadataSource raw) {
        this.json = json;
        this.raw = raw;
    }

    @Override
    public MatchTelemetry getMatch(long matchId) {
        try {
            return json.getMatch(matchId);
        } catch (MatchMetadataNotFoundException e) {
            throw e;
        } catch (DeadlockApiException jsonEx) {
            log.warn("JSON match metadata failed for match {}: {}: {}",
                    matchId, jsonEx.getClass().getSimpleName(), jsonEx.getMessage());
            try {
                return raw.getMatch(matchId);
            } catch (MatchMetadataNotFoundException rawEx) {
                jsonEx.addSuppressed(rawEx);
                throw jsonEx;
            } catch (RuntimeException rawEx) {
                rawEx.addSuppressed(jsonEx);
                throw rawEx;
            }
        }
    }
}
