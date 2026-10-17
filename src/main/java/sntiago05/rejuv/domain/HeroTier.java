package sntiago05.rejuv.domain;

/** Tier by win rate percent (unrounded): S >= 53, A >= 51, B >= 49, C >= 47, D below. */
public enum HeroTier {
    S, A, B, C, D;

    public static HeroTier of(double winRatePercent) {
        if (winRatePercent >= 53.0) {
            return S;
        } else if (winRatePercent >= 51.0) {
            return A;
        } else if (winRatePercent >= 49.0) {
            return B;
        } else if (winRatePercent >= 47.0) {
            return C;
        } else {
            return D;
        }
    }
}
