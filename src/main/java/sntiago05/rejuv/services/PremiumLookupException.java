package sntiago05.rejuv.services;

public class PremiumLookupException extends RuntimeException {

    public PremiumLookupException(String message) {
        super(message);
    }

    public PremiumLookupException(String message, Throwable cause) {
        super(message, cause);
    }
}
