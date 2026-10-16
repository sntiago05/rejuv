package sntiago05.rejuv.clients;

import java.time.Duration;

/**
 * Waits before a retry. Injected so tests never really sleep.
 */
@FunctionalInterface
public interface Sleeper {

    void sleep(Duration duration) throws InterruptedException;
}
