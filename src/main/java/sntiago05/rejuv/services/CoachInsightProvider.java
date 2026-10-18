package sntiago05.rejuv.services;

import java.util.Optional;

/**
 * Extension point for coaching layers (Tasks 031-035).
 * Each implementation produces an optional insight map section keyed by {@link #key()}.
 */
public interface CoachInsightProvider {

    /**
     * The lowercase snake_case key identifying this insight (e.g. "phase_stats", "deaths").
     * Must match the {@link CoachInsight#key()} returned by {@link #provide(CoachRequest)}.
     */
    String key();

    /**
     * Evaluates the request and returns an insight, or empty if this provider has nothing to say.
     * Implementations may call cached clients, but must never run uncached analytics requests in parallel.
     */
    Optional<CoachInsight> provide(CoachRequest request);
}
