package sntiago05.rejuv.services;

import java.time.Duration;

/** A new AI analysis was refused by the spend guard. Stored analyses never raise this. */
public class AnalysisLimitExceededException extends RuntimeException {

    public enum Reason { COOLDOWN, USER_DAILY, GLOBAL_DAILY }

    private final Reason reason;
    private final Duration retryAfter;
    private final int limit;

    /**
     * @param retryAfter time until the request would be allowed
     * @param limit the limit value that was hit (0 for {@link Reason#COOLDOWN})
     */
    public AnalysisLimitExceededException(Reason reason, Duration retryAfter, int limit) {
        super("Analysis limit " + reason + " reached, retry after " + retryAfter.toSeconds() + " s");
        this.reason = reason;
        this.retryAfter = retryAfter;
        this.limit = limit;
    }

    public Reason reason() {
        return reason;
    }

    public Duration retryAfter() {
        return retryAfter;
    }

    public int limit() {
        return limit;
    }
}
