package sntiago05.rejuv.services;

public class NotEnoughGamesException extends RuntimeException {

    private final int games;
    private final int minRequired;

    public NotEnoughGamesException(int games, int minRequired) {
        super("Not enough games: found " + games + ", minimum is " + minRequired);
        this.games = games;
        this.minRequired = minRequired;
    }

    public int games() {
        return games;
    }

    public int minRequired() {
        return minRequired;
    }
}
