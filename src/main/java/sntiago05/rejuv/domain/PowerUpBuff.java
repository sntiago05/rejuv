package sntiago05.rejuv.domain;

import java.util.List;

/**
 * A power-up buff acquired by a player.
 *
 * @param type         the buff identifier/type name
 * @param permanent    whether the buff is permanent
 * @param pickupTimesS list of pickup timestamps in seconds, ordered ascending
 */
public record PowerUpBuff(String type, boolean permanent, List<Integer> pickupTimesS) {

    public PowerUpBuff {
        pickupTimesS = List.copyOf(pickupTimesS);
    }
}
