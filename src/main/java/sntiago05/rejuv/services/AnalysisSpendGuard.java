package sntiago05.rejuv.services;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import sntiago05.rejuv.config.AnalysisLimitsProperties;
import sntiago05.rejuv.services.AnalysisLimitExceededException.Reason;

/**
 * In-memory limiter for new AI analyses: a per-user cooldown, a per-user daily cap and a global daily cap, all over a
 * rolling 24 h window. State resets on restart. Each user keeps a deque of timestamps pruned from the head, so a call
 * costs O(pruned entries); no scan over all users.
 */
@Service
@EnableConfigurationProperties(AnalysisLimitsProperties.class)
public class AnalysisSpendGuard {

    private static final Logger log = LoggerFactory.getLogger(AnalysisSpendGuard.class);
    static final Duration WINDOW = Duration.ofHours(24);
    private static final int MAX_TRACKED_USERS = 10_000;

    private final AnalysisLimitsProperties properties;
    private final Clock clock;
    private final Cache<Long, Deque<Instant>> userCalls = Caffeine.newBuilder()
            .expireAfterAccess(WINDOW)
            .maximumSize(MAX_TRACKED_USERS)
            .build();
    private final Deque<Instant> globalCalls = new ArrayDeque<>();

    @Autowired
    public AnalysisSpendGuard(AnalysisLimitsProperties properties) {
        this(properties, Clock.systemUTC());
    }

    AnalysisSpendGuard(AnalysisLimitsProperties properties, Clock clock) {
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * Checks all limits and, only if all pass, records one AI call for the user and globally.
     *
     * @throws AnalysisLimitExceededException when a limit is reached; nothing is recorded then
     */
    public synchronized void reserve(long discordUserId) {
        evaluate(discordUserId, true);
    }

    /**
     * Same checks as {@link #reserve(long)} but records nothing. Lets a caller refuse early, before slow data
     * fetches; {@code reserve} must still be called right before the AI call because the state can change meanwhile.
     *
     * @throws AnalysisLimitExceededException when a limit is reached
     */
    public synchronized void check(long discordUserId) {
        evaluate(discordUserId, false);
    }

    private void evaluate(long discordUserId, boolean record) {
        if (!properties.enabled()) {
            return;
        }
        Instant now = clock.instant();
        Instant windowStart = now.minus(WINDOW);
        Deque<Instant> user = userCalls.get(discordUserId, id -> new ArrayDeque<>());
        prune(user, windowStart);
        prune(globalCalls, windowStart);

        Duration cooldown = properties.cooldown();
        if (cooldown != null && !cooldown.isZero() && !cooldown.isNegative() && !user.isEmpty()) {
            Duration remaining = Duration.between(now, user.peekLast().plus(cooldown));
            if (!remaining.isNegative() && !remaining.isZero()) {
                throw reject(discordUserId, Reason.COOLDOWN, remaining, 0);
            }
        }
        if (properties.userDaily() > 0 && user.size() >= properties.userDaily()) {
            throw reject(discordUserId, Reason.USER_DAILY, untilExpiry(user.peekFirst(), now), properties.userDaily());
        }
        if (properties.globalDaily() > 0 && globalCalls.size() >= properties.globalDaily()) {
            throw reject(discordUserId, Reason.GLOBAL_DAILY, untilExpiry(globalCalls.peekFirst(), now),
                    properties.globalDaily());
        }
        if (record) {
            user.addLast(now);
            globalCalls.addLast(now);
        }
    }

    private static void prune(Deque<Instant> calls, Instant windowStart) {
        while (!calls.isEmpty() && !calls.peekFirst().isAfter(windowStart)) {
            calls.removeFirst();
        }
    }

    private static Duration untilExpiry(Instant oldest, Instant now) {
        return Duration.between(now, oldest.plus(WINDOW));
    }

    private static AnalysisLimitExceededException reject(long userId, Reason reason, Duration retryAfter, int limit) {
        log.info("Analyze limited userId={} reason={} retryAfterSeconds={}", userId, reason, retryAfter.toSeconds());
        return new AnalysisLimitExceededException(reason, retryAfter, limit);
    }
}
