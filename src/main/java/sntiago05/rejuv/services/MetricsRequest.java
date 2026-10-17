package sntiago05.rejuv.services;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import sntiago05.rejuv.clients.dto.HeroAsset;
import sntiago05.rejuv.clients.dto.ItemAsset;
import sntiago05.rejuv.clients.dto.ItemStat;
import sntiago05.rejuv.clients.dto.MatchHistoryEntry;
import sntiago05.rejuv.clients.dto.MetricPercentiles;
import sntiago05.rejuv.clients.dto.RankAsset;
import sntiago05.rejuv.domain.MatchRank;
import sntiago05.rejuv.domain.MatchTelemetry;

/**
 * Everything MetricsService needs for one analysis. Collections are copied (immutable).
 * telemetry: null = degraded mode (history data only).
 * rank: null = no rank known (no benchmarks); benchmarks and itemStats must then be empty.
 * benchmarks / itemStats: empty when not fetched or not available.
 */
public record MetricsRequest(long accountId,
                             MatchHistoryEntry match,
                             MatchTelemetry telemetry,
                             MatchRank rank,
                             Map<String, MetricPercentiles> benchmarks,
                             Map<Long, ItemStat> itemStats,
                             List<ItemAsset> items,
                             List<HeroAsset> heroes,
                             List<RankAsset> ranks) {

    public MetricsRequest {
        Objects.requireNonNull(match, "match");
        benchmarks = Map.copyOf(benchmarks);
        itemStats = Map.copyOf(itemStats);
        items = List.copyOf(items);
        heroes = List.copyOf(heroes);
        ranks = List.copyOf(ranks);
    }
}
