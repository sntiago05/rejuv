package sntiago05.rejuv.domain;

/**
 * itemName is null when the item id is not in the items asset. pick and win rate are unrounded percents.
 */
public record BuildItem(long itemId, String itemName, long matches, double pickRatePercent, double winRatePercent) { }
