package sntiago05.rejuv.services;

public class NoRankException extends RuntimeException {

    public NoRankException(long accountId) {
        super("No ranked badge found for account " + accountId);
    }
}
