package sntiago05.rejuv.domain;

import java.time.Instant;

/**
 * limit and used are for the current UTC week; resetsAt is the next Monday 00:00 UTC.
 * When !enabled or premium, nothing is limited.
 */
public record UsageStatus(boolean limitsEnabled, boolean premium, int used, int limit, Instant resetsAt) {

    public boolean unlimited() {
        return !limitsEnabled || premium;
    }
}
