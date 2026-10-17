package sntiago05.rejuv.domain;

/** All fields optional; null = no filter. win: true = victories only, false = defeats only. */
public record MatchFilter(Long matchId, Integer heroId, Boolean win) {
    public static final MatchFilter LATEST = new MatchFilter(null, null, null);
}
