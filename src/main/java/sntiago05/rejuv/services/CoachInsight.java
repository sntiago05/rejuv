package sntiago05.rejuv.services;

/**
 * An individual insight produced by a {@link CoachInsightProvider} to enrich the AI analysis input.
 *
 * @param key     lowercase snake_case key identifying the insight
 * @param payload JSON-serializable, non-null payload
 */
public record CoachInsight(String key, Object payload) {
}
