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
                         List<OtherSignal> otherSignals,
                         boolean hasSoulLead,
                         boolean hasPhasesChart,
                         boolean hasDeathsChart,
                         boolean hasMetricsChart,
                         boolean hasBuildTimeline,
                         String soulLeadAlt,
                         String soulCurveAlt,
                         String phasesAlt,
                         String deathsAlt,
                         String metricsAlt,
                         String buildAlt,
                         List<Tip> tips,
                         Scoreboard scoreboard) {

    public ReportView {
        build = build == null ? List.of() : List.copyOf(build);
        metricsTable = metricsTable == null ? List.of() : List.copyOf(metricsTable);
        coachSections = coachSections == null ? List.of() : List.copyOf(coachSections);
        otherSignals = otherSignals == null ? List.of() : List.copyOf(otherSignals);
        tips = tips == null ? List.of() : List.copyOf(tips);
        soulLeadAlt = soulLeadAlt == null ? "" : soulLeadAlt;
        soulCurveAlt = soulCurveAlt == null ? "" : soulCurveAlt;
        phasesAlt = phasesAlt == null ? "" : phasesAlt;
        deathsAlt = deathsAlt == null ? "" : deathsAlt;
        metricsAlt = metricsAlt == null ? "" : metricsAlt;
        buildAlt = buildAlt == null ? "" : buildAlt;
    }

    /**
     * Backward-compatible constructor for 25-parameter caller tests.
     */
    public ReportView(UUID publicId, long matchId, String hero, int heroId, String rank,
                      String result, Double durationMin, AnalysisResult analysis,
                      List<MatchVisuals.PurchasedItem> build, boolean hasChart,
                      Instant analyzedAt, boolean hasSoulCurve, boolean hasDetailedSections,
                      List<MetricRow> metricsTable, List<CoachSection> coachSections,
                      List<OtherSignal> otherSignals, boolean hasSoulLead, boolean hasPhasesChart,
                      boolean hasDeathsChart, boolean hasMetricsChart, String soulLeadAlt,
                      String soulCurveAlt, String phasesAlt, String deathsAlt, String metricsAlt) {
        this(publicId, matchId, hero, heroId, rank, result, durationMin, analysis, build,
                hasChart, analyzedAt, hasSoulCurve, hasDetailedSections, metricsTable,
                coachSections, otherSignals, hasSoulLead, hasPhasesChart, hasDeathsChart,
                hasMetricsChart, false, soulLeadAlt, soulCurveAlt, phasesAlt, deathsAlt,
                metricsAlt, "", List.of(), null);
    }

    /**
     * Backward-compatible constructor for callers that predate the scoreboard (task 055).
     */
    public ReportView(UUID publicId, long matchId, String hero, int heroId, String rank,
                      String result, Double durationMin, AnalysisResult analysis,
                      List<MatchVisuals.PurchasedItem> build, boolean hasChart,
                      Instant analyzedAt, boolean hasSoulCurve, boolean hasDetailedSections,
                      List<MetricRow> metricsTable, List<CoachSection> coachSections,
                      List<OtherSignal> otherSignals, boolean hasSoulLead, boolean hasPhasesChart,
                      boolean hasDeathsChart, boolean hasMetricsChart, boolean hasBuildTimeline,
                      String soulLeadAlt, String soulCurveAlt, String phasesAlt, String deathsAlt,
                      String metricsAlt, String buildAlt, List<Tip> tips) {
        this(publicId, matchId, hero, heroId, rank, result, durationMin, analysis, build,
                hasChart, analyzedAt, hasSoulCurve, hasDetailedSections, metricsTable,
                coachSections, otherSignals, hasSoulLead, hasPhasesChart, hasDeathsChart,
                hasMetricsChart, hasBuildTimeline, soulLeadAlt, soulCurveAlt, phasesAlt, deathsAlt,
                metricsAlt, buildAlt, tips, null);
    }

    /** True when the match scoreboard has at least one team with rows. */
    public boolean hasScoreboard() {
        return scoreboard != null && !scoreboard.teams().isEmpty();
    }

    /** Both teams of the match. {@code outcome} is {@code win}, {@code loss} or null when unknown. */
    public record Scoreboard(List<Team> teams) {
        public Scoreboard {
            teams = teams == null ? List.of() : List.copyOf(teams);
        }
    }

    public record Team(String labelKey, boolean viewerTeam, String outcome, String killsText,
                       String netWorthText, List<Row> rows) {
        public Team {
            rows = rows == null ? List.of() : List.copyOf(rows);
        }
    }

    /**
     * One player. {@code playerName} is empty when unknown. {@code damageWidthClass} is one of w0..w100.
     */
    public record Row(boolean viewer, int heroId, String heroName, String playerName, int kills, int deaths,
                      int assists, String kda, int creeps, String damageText, String damageWidthClass,
                      List<ScoreItem> items) {
        public Row {
            playerName = playerName == null ? "" : playerName;
            items = items == null ? List.of() : List.copyOf(items);
        }
    }

    public record ScoreItem(long itemId, String name) { }

    public record Tip(String tip, String evidence, String drill) {
        public Tip {
            tip = tip == null ? "" : tip;
            evidence = evidence == null ? "" : evidence;
            drill = drill == null ? "" : drill;
        }
    }

    public record MetricRow(String key, String labelKey, String yourValue, String p25, String p50, String verdict, String widthClass) {
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
