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
                         Instant analyzedAt,
                         boolean hasSoulCurve,
                         boolean hasDetailedSections,
                         List<MetricRow> metricsTable,
                         List<CoachSection> coachSections,
                         List<OtherSignal> otherSignals) {

    public ReportView {
        build = build == null ? List.of() : List.copyOf(build);
        metricsTable = metricsTable == null ? List.of() : List.copyOf(metricsTable);
        coachSections = coachSections == null ? List.of() : List.copyOf(coachSections);
        otherSignals = otherSignals == null ? List.of() : List.copyOf(otherSignals);
    }

    public ReportView(UUID publicId,
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
        this(publicId, matchId, hero, heroId, rank, result, durationMin, analysis, build, hasChart, analyzedAt, false);
    }

    public ReportView(UUID publicId,
                      long matchId,
                      String hero,
                      int heroId,
                      String rank,
                      String result,
                      Double durationMin,
                      AnalysisResult analysis,
                      List<MatchVisuals.PurchasedItem> build,
                      boolean hasChart,
                      Instant analyzedAt,
                      boolean hasSoulCurve) {
        this(publicId, matchId, hero, heroId, rank, result, durationMin, analysis, build, hasChart, analyzedAt, hasSoulCurve,
                false, List.of(), List.of(), List.of());
    }

    public record MetricRow(String key, String labelKey, String yourValue, String p25, String p50, String verdict) {
    }

    public record CoachField(String labelKey, String value) {
    }

    public record CoachSection(String key, String titleKey, String significance, List<CoachField> fields) {
        public CoachSection {
            fields = fields == null ? List.of() : List.copyOf(fields);
        }
    }

    public record OtherSignal(String key, String value) {
    }
}
