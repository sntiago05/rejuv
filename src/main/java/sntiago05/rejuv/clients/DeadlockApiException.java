package sntiago05.rejuv.clients;

/**
 * A deadlock-api call failed with an unexpected status or an unreadable body.
 * The message is for logs only and is never shown to users.
 */
public class DeadlockApiException extends RuntimeException {

    public DeadlockApiException(String message) {
        super(message);
    }

    public DeadlockApiException(String message, Throwable cause) {
        super(message, cause);
    }
}
