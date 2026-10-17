package sntiago05.rejuv.domain;

/** itemName is never null: items without a name in the items asset are dropped. winRatePercent is unrounded. */
public record CounterItem(long itemId, String itemName, long matches, double winRatePercent) {
}
