package sntiago05.rejuv.domain;

public record ItemComparison(long itemId,
                             String itemName,
                             long playerMatches,
                             double playerWinRatePercent,
                             long metaMatches,
                             double metaWinRatePercent,
                             double deltaPoints,
                             Double playerBuyMin,
                             Double metaBuyMin) {
}
