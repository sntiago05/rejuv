package sntiago05.rejuv.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

class JdaConfigTest {

    @Test
    void eventPoolRunsTasksOnVirtualThreadsConcurrently() throws Exception {
        ExecutorService pool = JdaConfig.eventPool();
        try {
            CountDownLatch enteredBoth = new CountDownLatch(2);
            CountDownLatch releaseTasks = new CountDownLatch(1);
            AtomicBoolean task1IsVirtual = new AtomicBoolean(false);
            AtomicBoolean task2IsVirtual = new AtomicBoolean(false);

            Future<?> f1 = pool.submit(() -> {
                task1IsVirtual.set(Thread.currentThread().isVirtual());
                enteredBoth.countDown();
                try {
                    releaseTasks.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });

            Future<?> f2 = pool.submit(() -> {
                task2IsVirtual.set(Thread.currentThread().isVirtual());
                enteredBoth.countDown();
                try {
                    releaseTasks.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });

            // Both tasks must enter concurrently; if single-threaded, enteredBoth would never reach 0
            assertThat(enteredBoth.await(5, TimeUnit.SECONDS)).isTrue();
            releaseTasks.countDown();

            f1.get(5, TimeUnit.SECONDS);
            f2.get(5, TimeUnit.SECONDS);

            assertThat(task1IsVirtual.get()).isTrue();
            assertThat(task2IsVirtual.get()).isTrue();
        } finally {
            pool.shutdownNow();
            assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }
}
