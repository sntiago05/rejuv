package sntiago05.rejuv.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ReportView(UUID publicId,
                         long matchId,
                         String hero,
                         int heroId,
                         String rank,
                         String result,
                         Double durationMin,
                         AnalysisResult analysis,
                         List<MatchVisuals.PurchasedItem> build,
                         boolean hasChart,
                         Instant analyzedAt) {

    public ReportView {
        build = build == null ? List.of() : List.copyOf(build);
    }
}
