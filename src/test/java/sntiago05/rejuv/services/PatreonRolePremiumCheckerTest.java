package sntiago05.rejuv.services;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import com.github.benmanes.caffeine.cache.Ticker;
import net.dv8tion.jda.api.exceptions.ErrorResponseException;
import net.dv8tion.jda.api.requests.ErrorResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sntiago05.rejuv.config.PremiumProperties;

class PatreonRolePremiumCheckerTest {

    private static final long GUILD_ID = 1000L;
    private static final long ROLE_ID = 2000L;

    private FakeGateway fakeGateway;
    private FakeTicker fakeTicker;
    private PremiumProperties properties;
    private PatreonRolePremiumChecker checker;

    static class FakeGateway implements PremiumRoleGateway {
        final AtomicInteger callCount = new AtomicInteger();
        final Map<Long, AtomicInteger> callsPerUser = new ConcurrentHashMap<>();
        final Map<Long, Boolean> answers = new ConcurrentHashMap<>();
        final Map<Long, RuntimeException> failures = new ConcurrentHashMap<>();
        volatile CountDownLatch slowUserLatch = null;

        @Override
        public boolean hasRole(long guildId, long roleId, long userId) {
            callCount.incrementAndGet();
            callsPerUser.computeIfAbsent(userId, k -> new AtomicInteger()).incrementAndGet();

            CountDownLatch latch = slowUserLatch;
            if (latch != null) {
                try {
                    latch.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(e);
                }
            }

            RuntimeException failure = failures.get(userId);
            if (failure != null) {
                throw failure;
            }
            return answers.getOrDefault(userId, false);
        }
    }

    static class FakeTicker implements Ticker {
        private final AtomicLong nanos = new AtomicLong();

        @Override
        public long read() {
            return nanos.get();
        }

        public void advance(Duration duration) {
            nanos.addAndGet(duration.toNanos());
        }
    }

    @BeforeEach
    void setUp() {
        fakeGateway = new FakeGateway();
        fakeTicker = new FakeTicker();
        properties = new PremiumProperties(true, GUILD_ID, ROLE_ID, Duration.ofMinutes(2));
        checker = new PatreonRolePremiumChecker(fakeGateway, properties, fakeTicker);
    }

    @Test
    void trueAndFalseAnswersReturned() {
        fakeGateway.answers.put(100L, true);
        fakeGateway.answers.put(200L, false);

        assertThat(checker.isPremium(100L)).isTrue();
        assertThat(checker.isPremium(200L)).isFalse();
        assertThat(fakeGateway.callsPerUser.get(100L).get()).isEqualTo(1);
        assertThat(fakeGateway.callsPerUser.get(200L).get()).isEqualTo(1);
    }

    @Test
    void secondCallWithinTtlDoesNotHitGateway() {
        fakeGateway.answers.put(100L, true);
        fakeGateway.answers.put(200L, false);

        assertThat(checker.isPremium(100L)).isTrue();
        assertThat(checker.isPremium(200L)).isFalse();
        assertThat(fakeGateway.callCount.get()).isEqualTo(2);

        fakeTicker.advance(Duration.ofMinutes(1));

        assertThat(checker.isPremium(100L)).isTrue();
        assertThat(checker.isPremium(200L)).isFalse();
        assertThat(fakeGateway.callCount.get()).isEqualTo(2);
    }

    @Test
    void afterTtlCallsGatewayAgain() {
        fakeGateway.answers.put(100L, true);

        assertThat(checker.isPremium(100L)).isTrue();
        assertThat(fakeGateway.callsPerUser.get(100L).get()).isEqualTo(1);

        fakeTicker.advance(Duration.ofMinutes(2).plusSeconds(1));

        assertThat(checker.isPremium(100L)).isTrue();
        assertThat(fakeGateway.callsPerUser.get(100L).get()).isEqualTo(2);
    }

    @Test
    void failureReturnsFalseAndLaterSuccessIsNotBlockedByCachedFailure() {
        fakeGateway.failures.put(300L, new PremiumLookupException("Discord is unavailable"));

        assertThat(checker.isPremium(300L)).isFalse();
        assertThat(fakeGateway.callsPerUser.get(300L).get()).isEqualTo(1);

        // Later recovery without advancing ticker: failure was not cached
        fakeGateway.failures.remove(300L);
        fakeGateway.answers.put(300L, true);

        assertThat(checker.isPremium(300L)).isTrue();
        assertThat(fakeGateway.callsPerUser.get(300L).get()).isEqualTo(2);
    }

    @Test
    void runtimeExceptionThatIsNotPremiumLookupExceptionAlsoReturnsFalse() {
        fakeGateway.failures.put(400L, new IllegalStateException("Internal state failure"));

        assertThat(checker.isPremium(400L)).isFalse();
        assertThat(fakeGateway.callsPerUser.get(400L).get()).isEqualTo(1);
    }

    @Test
    void twoThreadsAskingForSameUserTriggerOneGatewayCall() throws Exception {
        fakeGateway.answers.put(500L, true);
        fakeGateway.slowUserLatch = new CountDownLatch(1);

        CountDownLatch readyLatch = new CountDownLatch(2);
        CountDownLatch startLatch = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<Boolean> f1 = executor.submit(() -> {
                readyLatch.countDown();
                startLatch.await();
                return checker.isPremium(500L);
            });
            Future<Boolean> f2 = executor.submit(() -> {
                readyLatch.countDown();
                startLatch.await();
                return checker.isPremium(500L);
            });

            readyLatch.await();
            startLatch.countDown();

            // Give a brief window to ensure both threads called isPremium
            Thread.sleep(100);
            fakeGateway.slowUserLatch.countDown();

            assertThat(f1.get(5, TimeUnit.SECONDS)).isTrue();
            assertThat(f2.get(5, TimeUnit.SECONDS)).isTrue();
            assertThat(fakeGateway.callsPerUser.get(500L).get()).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void isPremiumNeverThrows() {
        fakeGateway.failures.put(600L, new NullPointerException("Simulated NPE"));
        assertThat(checker.isPremium(600L)).isFalse();

        ErrorResponseException ere = org.mockito.Mockito.mock(ErrorResponseException.class);
        org.mockito.Mockito.when(ere.getErrorResponse()).thenReturn(ErrorResponse.SERVER_ERROR);
        fakeGateway.failures.put(700L, ere);
        assertThat(checker.isPremium(700L)).isFalse();
    }

    @Test
    void productionConstructorUsesSystemTicker() {
        PatreonRolePremiumChecker prodChecker = new PatreonRolePremiumChecker(fakeGateway, properties);
        fakeGateway.answers.put(800L, true);
        assertThat(prodChecker.isPremium(800L)).isTrue();
    }
}
