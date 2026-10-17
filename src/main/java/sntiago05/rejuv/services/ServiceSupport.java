package sntiago05.rejuv.services;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * Shared helper methods and constants for services handling rank tiers and time windows.
 */
final class ServiceSupport {

    static final int MIN_RANK_TIER = 1;
    static final int MAX_RANK_TIER = 11;
    static final Duration WINDOW = Duration.ofDays(30);

    private ServiceSupport() {
    }

    /**
     * Validates that rankTier is null (all ranks) or within the valid tier range [1..11].
     *
     * @param rankTier nullable rank tier
     * @throws IllegalArgumentException if rankTier is outside [1..11]
     */
    static void validateRankTier(Integer rankTier) {
        if (rankTier != null && (rankTier < MIN_RANK_TIER || rankTier > MAX_RANK_TIER)) {
            throw new IllegalArgumentException("rankTier must be in " + MIN_RANK_TIER + ".." + MAX_RANK_TIER
                    + ", was " + rankTier);
        }
    }

    /**
     * Computes the epoch second at the start of UTC day minus the given duration.
     *
     * @param clock clock instance
     * @param duration duration to subtract from start of today (UTC)
     * @return epoch seconds
     */
    static long startOfUtcDayMinus(Clock clock, Duration duration) {
        LocalDate todayUtc = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
        return todayUtc.atStartOfDay(ZoneOffset.UTC).minus(duration).toEpochSecond();
    }
}
