package sntiago05.rejuv.domain;

/**
 * result is null when the AI failed and the caller must show the non-AI fallback.
 * inputTokens/outputTokens are summed over all attempts that returned a response (0 if none).
 */
public record AnalysisOutcome(AnalysisResult result, String model, int attempts, int inputTokens, int outputTokens) {

    public boolean isFallback() {
        return result == null;
    }
}
