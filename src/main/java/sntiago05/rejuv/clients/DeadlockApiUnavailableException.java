package sntiago05.rejuv.clients;

import java.time.Duration;
import java.util.Optional;

/** The API is busy, rate limiting us, or unreachable. Callers show a "busy, try again in a minute" message. */
public class DeadlockApiUnavailableException extends DeadlockApiException {

    private final Duration retryAfter;

    /**
     * @param retryAfter how long the API asked us to wait; may be null
     * @param cause      the underlying error; may be null
     */
    public DeadlockApiUnavailableException(String message, Duration retryAfter, Throwable cause) {
        super(message, cause);
        this.retryAfter = retryAfter;
    }

    public Optional<Duration> retryAfter() {
        return Optional.ofNullable(retryAfter);
    }
}
