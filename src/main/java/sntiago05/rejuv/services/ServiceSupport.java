package sntiago05.rejuv.services;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import sntiago05.rejuv.clients.DeadlockApiClient;
import sntiago05.rejuv.clients.DeadlockApiException;

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

    /**
     * Sorts the candidates in place and returns an immutable copy of the first {@code limit} of them.
     *
     * @param candidates mutable list, reordered by this call
     */
    static <T> List<T> top(List<T> candidates, Comparator<? super T> order, int limit) {
        candidates.sort(order);
        return List.copyOf(candidates.subList(0, Math.min(candidates.size(), limit)));
    }

    /**
     * Best-effort persona name lookup for account IDs. Logs a warning and returns an empty map on API failure.
     *
     * @param deadlockApiClient API client
     * @param accountIds account IDs to look up
     * @param log logger of the calling service
     * @param roleDescription description for logging (e.g. "teammates", "nemeses")
     * @return map of accountId to persona name
     */
    static Map<Long, String> fetchPlayerNames(DeadlockApiClient deadlockApiClient,
                                             Collection<Long> accountIds,
                                             Logger log,
                                             String roleDescription) {
        if (accountIds.isEmpty()) {
            return Map.of();
        }
        List<Long> ids = accountIds.stream().distinct().sorted().toList();
        try {
            return deadlockApiClient.getPlayerNames(ids);
        } catch (DeadlockApiException ex) {
            log.warn("Persona names unavailable for {} {}: {}: {}", ids.size(), roleDescription,
                    ex.getClass().getSimpleName(), ex.getMessage());
            return Map.of();
        }
    }
}
