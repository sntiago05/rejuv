package sntiago05.rejuv.domain;

/**
 * An item purchased by a player during a match.
 *
 * @param itemId    the item identifier
 * @param boughtAtS game time in seconds when the item was purchased
 * @param soldAtS   game time in seconds when the item was sold, or {@code null} if never sold
 */
public record ItemPurchase(long itemId, int boughtAtS, Integer soldAtS) {
}
