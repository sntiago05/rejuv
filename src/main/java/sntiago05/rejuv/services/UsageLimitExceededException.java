package sntiago05.rejuv.services;

import java.time.Instant;

public final class UsageLimitExceededException extends RuntimeException {

    private final Instant resetsAt;
    private final int limit;

    public UsageLimitExceededException(Instant resetsAt, int limit) {
        super("Weekly analysis limit of " + limit + " reached; resets at " + resetsAt);
        this.resetsAt = resetsAt;
        this.limit = limit;
    }

    public Instant resetsAt() {
        return resetsAt;
    }

    public int limit() {
        return limit;
    }
}
