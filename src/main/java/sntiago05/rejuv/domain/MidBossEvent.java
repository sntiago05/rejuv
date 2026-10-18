package sntiago05.rejuv.domain;

/**
 * An event where the Mid Boss was killed and claimed.
 *
 * @param timeS       timestamp in seconds when the Mid Boss was destroyed
 * @param teamKilled  team identifier of the team that killed the Mid Boss
 * @param teamClaimed team identifier of the team that claimed the Rejuvenator
 */
public record MidBossEvent(int timeS, int teamKilled, int teamClaimed) {

    /**
     * True if the team claiming the Rejuvenator is different from the team that killed the boss (a steal).
     */
    public boolean stolen() {
        return teamKilled != teamClaimed;
    }
}
