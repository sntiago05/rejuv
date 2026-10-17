package sntiago05.rejuv.services;

/** The text given to {@code /link} is not a supported account format. The message never contains the input. */
public class InvalidAccountInputException extends RuntimeException {

    public enum Reason { UNRECOGNIZED, VANITY_URL }

    private final Reason reason;

    public InvalidAccountInputException(Reason reason) {
        super("Invalid account input: " + reason);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
