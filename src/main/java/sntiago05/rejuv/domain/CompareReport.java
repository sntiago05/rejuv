package sntiago05.rejuv.domain;

import java.util.List;

public record CompareReport(int heroId,
                            String heroName,
                            int games,
                            int topTier,
                            Integer playerRankTier,
                            List<MetricGap> gaps) {
}
