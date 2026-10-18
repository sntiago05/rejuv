package sntiago05.rejuv.domain;

import java.util.List;

public record ItemsReport(int heroId,
                           String heroName,
                           int rankTier,
                           List<ItemComparison> underperforming,
                           List<ItemComparison> outperforming,
                           int itemsConsidered) {

    public ItemsReport {
        underperforming = underperforming != null ? List.copyOf(underperforming) : List.of();
        outperforming = outperforming != null ? List.copyOf(outperforming) : List.of();
    }
}
