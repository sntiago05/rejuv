package sntiago05.rejuv.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import sntiago05.rejuv.config.AnalysisLimitsProperties;
import sntiago05.rejuv.services.AnalysisLimitExceededException.Reason;

class AnalysisSpendGuardTest {

    private static final long USER = 111L;
    private static final long OTHER_USER = 222L;

    /** Clock the test can move forward. */
    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-01-01T00:00:00Z");

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private final MutableClock clock = new MutableClock();

    private AnalysisSpendGuard guard(boolean enabled, Duration cooldown, int userDaily, int globalDaily) {
        return new AnalysisSpendGuard(new AnalysisLimitsProperties(enabled, cooldown, userDaily, globalDaily), clock);
    }

    private static AnalysisLimitExceededException refusal(AnalysisSpendGuard guard, long user) {
        try {
            guard.reserve(user);
        } catch (AnalysisLimitExceededException ex) {
            return ex;
        }
        throw new AssertionError("expected a refusal");
    }

    @Test
    void passesUnderLimits() {
        AnalysisSpendGuard guard = guard(true, Duration.ofSeconds(60), 10, 150);

        assertThatCode(() -> guard.reserve(USER)).doesNotThrowAnyException();
    }

    @Test
    void cooldownBlocksThenPassesAfterTheWait() {
        AnalysisSpendGuard guard = guard(true, Duration.ofSeconds(60), 10, 150);
        guard.reserve(USER);
        clock.advance(Duration.ofSeconds(20));

        AnalysisLimitExceededException ex = refusal(guard, USER);

        assertThat(ex.reason()).isEqualTo(Reason.COOLDOWN);
        assertThat(ex.retryAfter()).isEqualTo(Duration.ofSeconds(40));
        assertThat(ex.limit()).isZero();

        clock.advance(Duration.ofSeconds(40));
        assertThatCode(() -> guard.reserve(USER)).doesNotThrowAnyException();
    }

    @Test
    void cooldownIsPerUser() {
        AnalysisSpendGuard guard = guard(true, Duration.ofSeconds(60), 10, 150);
        guard.reserve(USER);

        assertThatCode(() -> guard.reserve(OTHER_USER)).doesNotThrowAnyException();
    }

    @Test
    void userDailyCapBlocksAtTheLimitAndReleasesWhenTheOldestLeavesTheWindow() {
        AnalysisSpendGuard guard = guard(true, Duration.ZERO, 2, 150);
        guard.reserve(USER);
        clock.advance(Duration.ofHours(1));
        guard.reserve(USER);
        clock.advance(Duration.ofHours(1));

        AnalysisLimitExceededException ex = refusal(guard, USER);

        assertThat(ex.reason()).isEqualTo(Reason.USER_DAILY);
        assertThat(ex.limit()).isEqualTo(2);
        assertThat(ex.retryAfter()).isEqualTo(Duration.ofHours(22));

        clock.advance(Duration.ofHours(22));
        assertThatCode(() -> guard.reserve(USER)).doesNotThrowAnyException();
    }

    @Test
    void globalDailyCapBlocksAcrossUsers() {
        AnalysisSpendGuard guard = guard(true, Duration.ZERO, 10, 2);
        guard.reserve(USER);
        guard.reserve(OTHER_USER);

        AnalysisLimitExceededException ex = refusal(guard, 333L);

        assertThat(ex.reason()).isEqualTo(Reason.GLOBAL_DAILY);
        assertThat(ex.limit()).isEqualTo(2);
        assertThat(ex.retryAfter()).isEqualTo(Duration.ofHours(24));
    }

    @Test
    void rejectedCallRecordsNothing() {
        AnalysisSpendGuard guard = guard(true, Duration.ZERO, 10, 2);
        guard.reserve(USER);
        guard.reserve(USER);
        refusal(guard, OTHER_USER);
        refusal(guard, OTHER_USER);

        clock.advance(Duration.ofHours(25));

        // Both original entries expired; if the refusals had been recorded, the global cap would still block.
        assertThatCode(() -> guard.reserve(OTHER_USER)).doesNotThrowAnyException();
        assertThatCode(() -> guard.reserve(OTHER_USER)).doesNotThrowAnyException();
        assertThat(refusal(guard, USER).reason()).isEqualTo(Reason.GLOBAL_DAILY);
    }

    @Test
    void cooldownRefusalDoesNotConsumeDailyQuota() {
        AnalysisSpendGuard guard = guard(true, Duration.ofSeconds(60), 2, 150);
        guard.reserve(USER);
        refusal(guard, USER);
        refusal(guard, USER);
        clock.advance(Duration.ofSeconds(61));

        assertThatCode(() -> guard.reserve(USER)).doesNotThrowAnyException();
        clock.advance(Duration.ofSeconds(61));
        assertThat(refusal(guard, USER).reason()).isEqualTo(Reason.USER_DAILY);
    }

    @Test
    void disabledGuardNeverThrows() {
        AnalysisSpendGuard guard = guard(false, Duration.ofSeconds(60), 1, 1);

        for (int i = 0; i < 5; i++) {
            guard.reserve(USER);
        }
    }

    @Test
    void zeroValueDisablesOnlyThatLimit() {
        AnalysisSpendGuard noCooldown = guard(true, Duration.ZERO, 3, 150);
        noCooldown.reserve(USER);
        noCooldown.reserve(USER);
        noCooldown.reserve(USER);
        assertThat(refusal(noCooldown, USER).reason()).isEqualTo(Reason.USER_DAILY);

        AnalysisSpendGuard noUserCap = guard(true, Duration.ZERO, 0, 2);
        noUserCap.reserve(USER);
        noUserCap.reserve(USER);
        assertThat(refusal(noUserCap, USER).reason()).isEqualTo(Reason.GLOBAL_DAILY);

        AnalysisSpendGuard noGlobalCap = guard(true, Duration.ZERO, 0, 0);
        for (int i = 0; i < 50; i++) {
            noGlobalCap.reserve(USER);
        }
        AnalysisSpendGuard onlyCooldown = guard(true, Duration.ofSeconds(60), 0, 0);
        onlyCooldown.reserve(USER);
        assertThat(refusal(onlyCooldown, USER).reason()).isEqualTo(Reason.COOLDOWN);
    }

    @Test
    void simultaneousCallsFromOneUserOnlyLetOneThroughTheCooldown() throws Exception {
        AnalysisSpendGuard guard = guard(true, Duration.ofSeconds(60), 100, 1000);
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger passed = new AtomicInteger();
        AtomicInteger refused = new AtomicInteger();
        try {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    start.await();
                    try {
                        guard.reserve(USER);
                        passed.incrementAndGet();
                    } catch (AnalysisLimitExceededException ex) {
                        refused.incrementAndGet();
                    }
                    return null;
                });
            }
            start.countDown();
        } finally {
            pool.shutdown();
            assertThat(pool.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        }

        assertThat(passed.get()).isEqualTo(1);
        assertThat(refused.get()).isEqualTo(threads - 1);
    }

    @Test
    void refusalMessageNamesTheReason() {
        AnalysisSpendGuard guard = guard(true, Duration.ofSeconds(60), 10, 150);
        guard.reserve(USER);

        assertThatThrownBy(() -> guard.reserve(USER))
                .isInstanceOf(AnalysisLimitExceededException.class)
                .hasMessageContaining("COOLDOWN");
    }
}
