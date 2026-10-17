package sntiago05.rejuv.services;

/** deadlock-api has no match history for the account, so it cannot be linked. */
public class NoMatchHistoryException extends RuntimeException {

    public NoMatchHistoryException(long accountId) {
        super("No match history for account " + accountId);
    }
}
