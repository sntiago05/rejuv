package sntiago05.rejuv.services;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import sntiago05.rejuv.clients.DeadlockApiClient;
import sntiago05.rejuv.clients.MatchMetadataSource;
import sntiago05.rejuv.clients.dto.PerformanceCurvePoint;
import sntiago05.rejuv.clients.dto.PlayerRank;
import sntiago05.rejuv.clients.dto.RankAsset;
import sntiago05.rejuv.domain.AnalysisResult;
import sntiago05.rejuv.domain.MatchAnalysis;
import sntiago05.rejuv.domain.MatchTelemetry;
import sntiago05.rejuv.domain.MatchVisuals;
import sntiago05.rejuv.domain.PlayerTelemetry;
import sntiago05.rejuv.domain.ReportView;
import sntiago05.rejuv.domain.StatSnapshot;
import sntiago05.rejuv.repositories.MatchAnalysisRepository;
import java.util.Collections;
import java.util.Iterator;
import java.util.Locale;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

@Service
public class ReportService {

    private static final Logger log = LoggerFactory.getLogger(ReportService.class);
    private static final ObjectMapper JSON_MAPPER = new JsonMapper();

    private final MatchAnalysisRepository repository;
    private final MatchMetadataSource matchMetadataSource;
    private final MatchVisualsService visualsService;
    private final GameAssets gameAssets;
    private final ChartRenderer chartRenderer;
    private final DeadlockApiClient deadlockApiClient;
    private final SoulCurveSvg soulCurveSvg;
    private final ReportChartsSvg reportChartsSvg;

    public ReportService(MatchAnalysisRepository repository,
                         MatchMetadataSource matchMetadataSource,
                         MatchVisualsService visualsService,
                         GameAssets gameAssets,
                         ChartRenderer chartRenderer,
                         DeadlockApiClient deadlockApiClient,
                         SoulCurveSvg soulCurveSvg,
                         ReportChartsSvg reportChartsSvg) {
        this.repository = repository;
        this.matchMetadataSource = matchMetadataSource;
        this.visualsService = visualsService;
        this.gameAssets = gameAssets;
        this.chartRenderer = chartRenderer;
        this.deadlockApiClient = deadlockApiClient;
        this.soulCurveSvg = soulCurveSvg;
        this.reportChartsSvg = reportChartsSvg;
    }

    /**
     * Loads the analysis report by public id. Empty when unknown or corrupted. Never throws for API failures.
     */
    public Optional<ReportView> find(UUID publicId) {
        if (publicId == null) {
            return Optional.empty();
        }
        Optional<MatchAnalysis> storedOpt = repository.findByPublicId(publicId);
        if (storedOpt.isEmpty()) {
            return Optional.empty();
        }
        MatchAnalysis stored = storedOpt.get();
        AnalysisResult analysis;
        try {
            analysis = JSON_MAPPER.readValue(stored.getResultJson(), AnalysisResult.class);
        } catch (Exception ex) {
            log.warn("Corrupted match analysis JSON for publicId={}", publicId);
            return Optional.empty();
        }

        String heroName = resolveHeroName(stored.getHeroId());

        Double durationMin = null;
        List<MatchVisuals.PurchasedItem> build = List.of();
        boolean hasChart = false;
        boolean hasSoulCurve = false;
        boolean hasSoulLead = false;
        String soulLeadAlt = "";
        String soulCurveAlt = "";

        try {
            MatchTelemetry telemetry = matchMetadataSource.getMatch(stored.getMatchId());
            if (telemetry != null) {
                durationMin = Math.round((telemetry.durationS() / 60.0) * 10.0) / 10.0;
                Optional<MatchVisuals> visuals = visualsService.build(stored.getAccountId(), telemetry);
                if (visuals.isPresent()) {
                    build = visuals.get().items();
                    MatchVisuals.ChartData chartData = visuals.get().chart();
                    hasChart = chartData != null;
                    if (chartData != null && chartData.soulLead() != null && chartData.soulLead().size() >= 2) {
                        hasSoulLead = true;
                        soulLeadAlt = reportChartsSvg.computeSoulLeadAlt(chartData.timesS(), chartData.soulLead());
                    }
                }
                Optional<SoulCurveData> soulCurveDataOpt = computeSoulCurveData(stored, telemetry);
                hasSoulCurve = soulCurveDataOpt.isPresent();
                if (hasSoulCurve) {
                    SoulCurveData scData = soulCurveDataOpt.get();
                    int lastIdx = scData.playerNetWorth().size() - 1;
                    int durM = (int) (scData.timesS().get(lastIdx) / 60);
                    soulCurveAlt = soulCurveSvg.computeSoulCurveAlt(scData.playerNetWorth().get(lastIdx), scData.avgNetWorth().get(lastIdx), durM);
                }
            }
        } catch (RuntimeException ex) {
            log.warn("Match metadata unavailable for publicId={}: {}", publicId, ex.getClass().getSimpleName());
            durationMin = null;
            build = List.of();
            hasChart = false;
            hasSoulCurve = false;
            hasSoulLead = false;
            soulLeadAlt = "";
            soulCurveAlt = "";
        }

        String rank = null;
        String result = null;
        boolean hasDetailedSections = false;
        boolean hasPhasesChart = false;
        boolean hasDeathsChart = false;
        boolean hasMetricsChart = false;
        String phasesAlt = "";
        String deathsAlt = "";
        String metricsAlt = "";
        List<ReportView.MetricRow> metricsTable = List.of();
        List<ReportView.CoachSection> coachSections = List.of();
        List<ReportView.OtherSignal> otherSignals = List.of();

        String inputJson = stored.getInputJson();
        if (inputJson != null && !inputJson.isBlank()) {
            try {
                JsonNode inputTree = JSON_MAPPER.readTree(inputJson);
                if (inputTree != null && inputTree.isObject()) {
                    hasDetailedSections = true;
                    if (inputTree.hasNonNull("rank")) {
                        rank = inputTree.get("rank").asText();
                    }
                    if (inputTree.hasNonNull("result")) {
                        result = inputTree.get("result").asText();
                    }
                    if (durationMin == null && inputTree.hasNonNull("duration_min")) {
                        durationMin = Math.round(inputTree.get("duration_min").asDouble() * 10.0) / 10.0;
                    }
                    if ((heroName == null || heroName.startsWith("hero_")) && inputTree.hasNonNull("hero")) {
                        heroName = inputTree.get("hero").asText();
                    }

                    metricsTable = parseMetricsTable(inputTree.path("metrics"));
                    coachSections = parseCoachSections(inputTree.path("coach"));
                    otherSignals = parseOtherSignals(inputTree.path("coach"));

                    // Phases chart
                    Optional<List<ReportChartsSvg.PhaseScore>> phaseScoresOpt = parseCoachPhases(inputTree);
                    if (phaseScoresOpt.isPresent()) {
                        hasPhasesChart = true;
                        phasesAlt = reportChartsSvg.computePhasesAlt(phaseScoresOpt.get());
                    }

                    // Deaths chart
                    Optional<CoachDeathsData> coachDeathsOpt = parseCoachDeaths(inputTree);
                    if (coachDeathsOpt.isPresent()) {
                        hasDeathsChart = true;
                        CoachDeathsData cd = coachDeathsOpt.get();
                        deathsAlt = reportChartsSvg.computeDeathsAlt(cd.laning(), cd.mid(), cd.late(), cd.total(), cd.longestRespawnS());
                    }

                    // Metrics chart
                    if (!metricsTable.isEmpty()) {
                        hasMetricsChart = true;
                        metricsAlt = reportChartsSvg.computeMetricsAlt(metricsTable);
                    }
                }
            } catch (Exception ex) {
                log.warn("Failed to parse analysis input JSON for publicId={}: {}", publicId, ex.getMessage());
                hasDetailedSections = false;
                hasPhasesChart = false;
                hasDeathsChart = false;
                hasMetricsChart = false;
                phasesAlt = "";
                deathsAlt = "";
                metricsAlt = "";
                rank = null;
                result = null;
                metricsTable = List.of();
                coachSections = List.of();
                otherSignals = List.of();
            }
        }

        return Optional.of(new ReportView(
                stored.getPublicId(),
                stored.getMatchId(),
                heroName,
                stored.getHeroId(),
                rank,
                result,
                durationMin,
                analysis,
                build,
                hasChart,
                stored.getCreatedAt(),
                hasSoulCurve,
                hasDetailedSections,
                metricsTable,
                coachSections,
                otherSignals,
                hasSoulLead,
                hasPhasesChart,
                hasDeathsChart,
                hasMetricsChart,
                soulLeadAlt,
                soulCurveAlt,
                phasesAlt,
                deathsAlt,
                metricsAlt
        ));
    }

    /**
     * Renders the chart as PNG. Empty when no report, no chart, or rendering fails.
     */
    public Optional<byte[]> chartPng(UUID publicId) {
        if (publicId == null) {
            return Optional.empty();
        }
        Optional<MatchAnalysis> storedOpt = repository.findByPublicId(publicId);
        if (storedOpt.isEmpty()) {
            return Optional.empty();
        }
        MatchAnalysis stored = storedOpt.get();

        MatchTelemetry telemetry;
        try {
            telemetry = matchMetadataSource.getMatch(stored.getMatchId());
        } catch (RuntimeException ex) {
            log.warn("Match metadata unavailable for chart publicId={}: {}", publicId, ex.getClass().getSimpleName());
            return Optional.empty();
        }
        if (telemetry == null) {
            return Optional.empty();
        }

        Optional<MatchVisuals> visuals = visualsService.build(stored.getAccountId(), telemetry);
        if (visuals.isEmpty() || visuals.get().chart() == null) {
            return Optional.empty();
        }

        try {
            return chartRenderer.render(visuals.get().chart());
        } catch (RuntimeException ex) {
            log.warn("Chart rendering failed for publicId={}: {}", publicId, ex.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    /**
     * Renders the soul curve as SVG. Empty when no report, no curve data, or rendering fails.
     */
    public Optional<String> soulCurveSvg(UUID publicId) {
        if (publicId == null || soulCurveSvg == null) {
            return Optional.empty();
        }
        Optional<MatchAnalysis> storedOpt = repository.findByPublicId(publicId);
        if (storedOpt.isEmpty()) {
            return Optional.empty();
        }
        MatchAnalysis stored = storedOpt.get();

        MatchTelemetry telemetry;
        try {
            telemetry = matchMetadataSource.getMatch(stored.getMatchId());
        } catch (RuntimeException ex) {
            log.warn("Match metadata unavailable for soul curve publicId={}: {}", publicId, ex.getClass().getSimpleName());
            return Optional.empty();
        }
        if (telemetry == null) {
            return Optional.empty();
        }

        Optional<SoulCurveData> dataOpt = computeSoulCurveData(stored, telemetry);
        if (dataOpt.isEmpty()) {
            return Optional.empty();
        }
        SoulCurveData data = dataOpt.get();
        return soulCurveSvg.render(data.heroName(), data.rankLabel(), data.ownRank(),
                data.timesS(), data.playerNetWorth(), data.avgNetWorth(), data.stdNetWorth());
    }

    /**
     * Renders the soul lead chart as SVG. Empty when no report, no telemetry/chart, or rendering fails.
     */
    public Optional<String> soulLeadSvg(UUID publicId) {
        if (publicId == null) {
            return Optional.empty();
        }
        Optional<MatchAnalysis> storedOpt = repository.findByPublicId(publicId);
        if (storedOpt.isEmpty()) {
            return Optional.empty();
        }
        MatchAnalysis stored = storedOpt.get();

        MatchTelemetry telemetry;
        try {
            telemetry = matchMetadataSource.getMatch(stored.getMatchId());
        } catch (RuntimeException ex) {
            log.warn("Match metadata unavailable for soul lead publicId={}: {}", publicId, ex.getClass().getSimpleName());
            return Optional.empty();
        }
        if (telemetry == null) {
            return Optional.empty();
        }

        Optional<MatchVisuals> visuals = visualsService.build(stored.getAccountId(), telemetry);
        if (visuals.isEmpty() || visuals.get().chart() == null) {
            return Optional.empty();
        }
        MatchVisuals.ChartData chart = visuals.get().chart();
        return reportChartsSvg.renderSoulLead(chart.timesS(), chart.soulLead(), chart.playerDeathTimesS());
    }

    /**
     * Renders the phase scorecard as SVG. Empty when no report, no phases payload, or rendering fails.
     */
    public Optional<String> phasesSvg(UUID publicId) {
        if (publicId == null) {
            return Optional.empty();
        }
        Optional<MatchAnalysis> storedOpt = repository.findByPublicId(publicId);
        if (storedOpt.isEmpty() || storedOpt.get().getInputJson() == null) {
            return Optional.empty();
        }
        try {
            JsonNode tree = JSON_MAPPER.readTree(storedOpt.get().getInputJson());
            Optional<List<ReportChartsSvg.PhaseScore>> phasesOpt = parseCoachPhases(tree);
            return phasesOpt.flatMap(reportChartsSvg::renderPhases);
        } catch (Exception ex) {
            log.warn("Failed to parse phases for SVG publicId={}: {}", publicId, ex.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Renders the deaths breakdown as SVG. Empty when no report, no deaths payload, or rendering fails.
     */
    public Optional<String> deathsSvg(UUID publicId) {
        if (publicId == null) {
            return Optional.empty();
        }
        Optional<MatchAnalysis> storedOpt = repository.findByPublicId(publicId);
        if (storedOpt.isEmpty() || storedOpt.get().getInputJson() == null) {
            return Optional.empty();
        }
        try {
            JsonNode tree = JSON_MAPPER.readTree(storedOpt.get().getInputJson());
            Optional<CoachDeathsData> deathsOpt = parseCoachDeaths(tree);
            return deathsOpt.flatMap(d -> reportChartsSvg.renderDeaths(d.laning(), d.mid(), d.late(), d.total(), d.longestRespawnS()));
        } catch (Exception ex) {
            log.warn("Failed to parse deaths for SVG publicId={}: {}", publicId, ex.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Renders the metrics vs peers comparison as SVG. Empty when no report, no metrics, or rendering fails.
     */
    public Optional<String> metricsSvg(UUID publicId) {
        if (publicId == null) {
            return Optional.empty();
        }
        Optional<MatchAnalysis> storedOpt = repository.findByPublicId(publicId);
        if (storedOpt.isEmpty() || storedOpt.get().getInputJson() == null) {
            return Optional.empty();
        }
        try {
            JsonNode tree = JSON_MAPPER.readTree(storedOpt.get().getInputJson());
            List<ReportView.MetricRow> metrics = parseMetricsTable(tree.path("metrics"));
            if (metrics.isEmpty()) {
                return Optional.empty();
            }
            return reportChartsSvg.renderMetrics(metrics);
        } catch (Exception ex) {
            log.warn("Failed to parse metrics for SVG publicId={}: {}", publicId, ex.getMessage());
            return Optional.empty();
        }
    }

    private record SoulCurveData(
            String heroName,
            String rankLabel,
            boolean ownRank,
            List<Integer> timesS,
            List<Long> playerNetWorth,
            List<Double> avgNetWorth,
            List<Double> stdNetWorth
    ) { }

    private Optional<SoulCurveData> computeSoulCurveData(MatchAnalysis stored, MatchTelemetry telemetry) {
        if (deadlockApiClient == null || telemetry == null) {
            return Optional.empty();
        }

        int tier;
        boolean ownRank;
        if (telemetry.averageBadge() != null) {
            tier = Math.clamp(telemetry.averageBadge() / 10, 1, 11);
            ownRank = false;
        } else {
            PlayerRank playerRank;
            try {
                playerRank = deadlockApiClient.getPlayerRank(stored.getAccountId());
            } catch (RuntimeException ex) {
                log.warn("Player rank unavailable for accountId={}: {}", stored.getAccountId(), ex.getClass().getSimpleName());
                return Optional.empty();
            }
            if (playerRank == null || playerRank.badge() <= 0 || playerRank.rank() < 1) {
                return Optional.empty();
            }
            tier = Math.clamp(playerRank.rank(), 1, 11);
            ownRank = true;
        }

        Optional<PlayerTelemetry> playerOpt = telemetry.player(stored.getAccountId());
        if (playerOpt.isEmpty()) {
            return Optional.empty();
        }
        PlayerTelemetry player = playerOpt.get();
        if (player.snapshots() == null || player.snapshots().isEmpty()) {
            return Optional.empty();
        }

        Map<Integer, Long> playerNetWorthByTime = new HashMap<>();
        for (StatSnapshot s : player.snapshots()) {
            playerNetWorthByTime.putIfAbsent(s.timeS(), s.netWorth());
        }

        List<PerformanceCurvePoint> curve;
        try {
            curve = deadlockApiClient.getPerformanceCurve(stored.getHeroId(), tier);
        } catch (RuntimeException ex) {
            log.warn("Performance curve unavailable for heroId={}, tier={}: {}",
                    stored.getHeroId(), tier, ex.getClass().getSimpleName());
            return Optional.empty();
        }
        if (curve == null || curve.isEmpty()) {
            return Optional.empty();
        }

        List<Integer> timesS = new ArrayList<>();
        List<Long> playerNetWorth = new ArrayList<>();
        List<Double> avgNetWorth = new ArrayList<>();
        List<Double> stdNetWorth = new ArrayList<>();

        for (PerformanceCurvePoint pt : curve) {
            int t = pt.gameTime();
            if (t <= telemetry.durationS() && pt.netWorthStd() > 0.0 && playerNetWorthByTime.containsKey(t)) {
                timesS.add(t);
                playerNetWorth.add(playerNetWorthByTime.get(t));
                avgNetWorth.add(pt.netWorthAvg());
                stdNetWorth.add(pt.netWorthStd());
            }
        }

        if (timesS.size() < 3) {
            return Optional.empty();
        }

        String heroName = resolveHeroName(stored.getHeroId());
        String rankLabel = resolveRankLabel(tier);

        return Optional.of(new SoulCurveData(heroName, rankLabel, ownRank, timesS, playerNetWorth, avgNetWorth, stdNetWorth));
    }

    private String resolveRankLabel(int tier) {
        try {
            List<RankAsset> ranks = deadlockApiClient.getRanks();
            if (ranks != null) {
                for (RankAsset rank : ranks) {
                    if (rank.tier() == tier && rank.name() != null && !rank.name().isBlank()) {
                        return rank.name();
                    }
                }
            }
        } catch (RuntimeException ex) {
            log.warn("Rank assets unavailable for soul curve: {}", ex.getClass().getSimpleName());
        }
        return "rank " + tier;
    }

    private String resolveHeroName(int heroId) {
        String heroName;
        try {
            heroName = gameAssets.heroNames().get(heroId);
        } catch (RuntimeException ex) {
            heroName = null;
        }
        if (heroName == null || heroName.isBlank()) {
            heroName = "hero_" + heroId;
        }
        return heroName;
    }

    private static final List<String> KNOWN_COACH_KEYS = List.of(
            "phases", "deaths", "objectives", "aim_mechanics", "build", "recent_form", "matchup");

    public static String computeVerdict(String metricKey, double value, Double p25, Double p50) {
        if (p25 == null || p50 == null) {
            return "-";
        }
        boolean lowerIsBetter = isLowerBetterMetric(metricKey);
        if (lowerIsBetter) {
            if (value > p50) {
                return "below p25";
            }
            if (value < p25) {
                return "above p50";
            }
            return "between p25 and p50";
        } else {
            if (value < p25) {
                return "below p25";
            }
            if (value > p50) {
                return "above p50";
            }
            return "between p25 and p50";
        }
    }

    public static boolean isLowerBetterMetric(String metricKey) {
        if (metricKey == null) {
            return false;
        }
        return metricKey.equals("deaths")
                || metricKey.equals("deaths_per_10_min")
                || metricKey.equals("first_permanent_buff_min")
                || metricKey.contains("death")
                || metricKey.endsWith("_time_s");
    }

    public static String formatNumber(Double value) {
        if (value == null) {
            return "-";
        }
        if (value == Math.rint(value)) {
            return String.valueOf((long) value.doubleValue());
        }
        return String.format(Locale.ROOT, "%.1f", value);
    }

    public static String mapWidthClass(double percentage) {
        if (Double.isNaN(percentage) || percentage <= 0.0) {
            return "w0";
        }
        if (percentage >= 100.0) {
            return "w100";
        }
        int rounded = (int) Math.round(percentage / 10.0) * 10;
        rounded = Math.clamp(rounded, 0, 100);
        return "w" + rounded;
    }

    private static List<ReportView.MetricRow> parseMetricsTable(JsonNode metricsNode) {
        if (metricsNode == null || !metricsNode.isObject()) {
            return List.of();
        }
        List<ReportView.MetricRow> rows = new ArrayList<>();
        for (Map.Entry<String, JsonNode> entry : metricsNode.properties()) {
            String metricKey = entry.getKey();
            JsonNode compNode = entry.getValue();
            if (compNode == null || !compNode.isObject()) {
                continue;
            }
            double value = compNode.path("value").asDouble();
            Double p25 = compNode.hasNonNull("p25") ? compNode.path("p25").asDouble() : null;
            Double p50 = compNode.hasNonNull("p50") ? compNode.path("p50").asDouble() : null;
            String verdict = computeVerdict(metricKey, value, p25, p50);
            String labelKey = "analyze.metric." + metricKey;
            String yourValueStr = formatNumber(value);
            String p25Str = formatNumber(p25);
            String p50Str = formatNumber(p50);
            double pct;
            if (p50 != null && p50 > 0) {
                pct = (value / (2.0 * p50)) * 100.0;
            } else {
                pct = 50.0;
            }
            String widthClass = mapWidthClass(pct);
            rows.add(new ReportView.MetricRow(metricKey, labelKey, yourValueStr, p25Str, p50Str, verdict, widthClass));
        }
        return Collections.unmodifiableList(rows);
    }

    private record CoachDeathsData(int laning, int mid, int late, int total, int longestRespawnS) { }

    private static Optional<List<ReportChartsSvg.PhaseScore>> parseCoachPhases(JsonNode root) {
        if (root == null) {
            return Optional.empty();
        }
        JsonNode phasesNode = root.path("coach").path("phases").path("phases");
        if (!phasesNode.isArray() || phasesNode.isEmpty()) {
            return Optional.empty();
        }
        List<ReportChartsSvg.PhaseScore> phases = new ArrayList<>();
        for (JsonNode p : phasesNode) {
            phases.add(new ReportChartsSvg.PhaseScore(p.path("name").asText(), p.path("change").asDouble()));
        }
        return Optional.of(phases);
    }

    private static Optional<CoachDeathsData> parseCoachDeaths(JsonNode root) {
        if (root == null) {
            return Optional.empty();
        }
        JsonNode deathsNode = root.path("coach").path("deaths");
        if (!deathsNode.isObject() || deathsNode.isEmpty()) {
            return Optional.empty();
        }
        int total = deathsNode.path("total").asInt(0);
        int longestRespawn = deathsNode.path("longest_respawn_s").asInt(0);
        JsonNode byPhase = deathsNode.path("by_phase");
        int laning = byPhase.path("laning").asInt(0);
        int mid = byPhase.path("mid").asInt(0);
        int late = byPhase.path("late").asInt(0);
        return Optional.of(new CoachDeathsData(laning, mid, late, total, longestRespawn));
    }

    private static List<ReportView.CoachSection> parseCoachSections(JsonNode coachNode) {
        if (coachNode == null || !coachNode.isObject()) {
            return List.of();
        }
        List<ReportView.CoachSection> sections = new ArrayList<>();
        for (String key : KNOWN_COACH_KEYS) {
            if (!coachNode.hasNonNull(key)) {
                continue;
            }
            JsonNode sectionNode = coachNode.get(key);
            if (!sectionNode.isObject()) {
                continue;
            }
            String significance = sectionNode.path("significance").asText("notable");
            String titleKey = "report.coach." + key;
            List<ReportView.CoachField> fields = buildCoachFields(key, sectionNode);
            if (!fields.isEmpty()) {
                sections.add(new ReportView.CoachSection(key, titleKey, significance, fields));
            }
        }
        return Collections.unmodifiableList(sections);
    }

    private static List<ReportView.CoachField> buildCoachFields(String key, JsonNode node) {
        List<ReportView.CoachField> fields = new ArrayList<>();
        switch (key) {
            case "phases" -> {
                if (node.hasNonNull("weakest_phase")) {
                    fields.add(new ReportView.CoachField("report.coach.phases.weakest_phase", capitalize(node.get("weakest_phase").asText())));
                }
                if (node.hasNonNull("strongest_phase")) {
                    fields.add(new ReportView.CoachField("report.coach.phases.strongest_phase", capitalize(node.get("strongest_phase").asText())));
                }
                JsonNode phases = node.path("phases");
                if (phases.isArray()) {
                    for (JsonNode p : phases) {
                        String name = p.path("name").asText();
                        double change = p.path("change").asDouble();
                        fields.add(new ReportView.CoachField("report.coach.phases.phase_" + name, String.format(Locale.ROOT, "%+.1f z-score", change)));
                    }
                }
            }
            case "deaths" -> {
                if (node.hasNonNull("total")) {
                    fields.add(new ReportView.CoachField("report.coach.deaths.total", node.get("total").asText()));
                }
                JsonNode byPhase = node.path("by_phase");
                if (byPhase.isObject()) {
                    int l = byPhase.path("laning").asInt();
                    int m = byPhase.path("mid").asInt();
                    int lt = byPhase.path("late").asInt();
                    fields.add(new ReportView.CoachField("report.coach.deaths.by_phase", l + " / " + m + " / " + lt));
                }
                if (node.hasNonNull("seconds_dead")) {
                    fields.add(new ReportView.CoachField("report.coach.deaths.seconds_dead", node.get("seconds_dead").asInt() + "s"));
                }
                if (node.hasNonNull("percent_of_match_dead")) {
                    fields.add(new ReportView.CoachField("report.coach.deaths.percent_of_match_dead", String.format(Locale.ROOT, "%.1f%%", node.get("percent_of_match_dead").asDouble())));
                }
                if (node.hasNonNull("longest_respawn_s")) {
                    fields.add(new ReportView.CoachField("report.coach.deaths.longest_respawn_s", node.get("longest_respawn_s").asInt() + "s"));
                }
                JsonNode topKiller = node.path("top_killer");
                if (topKiller.isObject() && topKiller.hasNonNull("hero")) {
                    fields.add(new ReportView.CoachField("report.coach.deaths.top_killer", topKiller.get("hero").asText() + " (" + topKiller.path("count").asInt() + ")"));
                }
                if (node.hasNonNull("repeat_killer")) {
                    fields.add(new ReportView.CoachField("report.coach.deaths.repeat_killer", node.get("repeat_killer").asBoolean() ? "Yes" : "No"));
                }
                if (node.hasNonNull("deaths_while_behind") && node.hasNonNull("deaths_counted")) {
                    fields.add(new ReportView.CoachField("report.coach.deaths.deaths_while_behind", node.get("deaths_while_behind").asInt() + " / " + node.get("deaths_counted").asInt()));
                }
                if (node.hasNonNull("deaths_z")) {
                    fields.add(new ReportView.CoachField("report.coach.deaths.deaths_z", String.format(Locale.ROOT, "%+.1f", node.get("deaths_z").asDouble())));
                }
                if (node.hasNonNull("ttk_median_s")) {
                    fields.add(new ReportView.CoachField("report.coach.deaths.ttk_median_s", String.format(Locale.ROOT, "%.1fs", node.get("ttk_median_s").asDouble())));
                }
            }
            case "objectives" -> {
                if (node.hasNonNull("mid_boss_team")) {
                    fields.add(new ReportView.CoachField("report.coach.objectives.mid_boss_team", node.get("mid_boss_team").asText()));
                }
                if (node.hasNonNull("mid_boss_enemy")) {
                    fields.add(new ReportView.CoachField("report.coach.objectives.mid_boss_enemy", node.get("mid_boss_enemy").asText()));
                }
                if (node.hasNonNull("rejuvenator_stolen_by_team") && node.get("rejuvenator_stolen_by_team").asInt() > 0) {
                    fields.add(new ReportView.CoachField("report.coach.objectives.rejuvenator_stolen_by_team", node.get("rejuvenator_stolen_by_team").asText()));
                }
                if (node.hasNonNull("rejuvenator_stolen_by_enemy") && node.get("rejuvenator_stolen_by_enemy").asInt() > 0) {
                    fields.add(new ReportView.CoachField("report.coach.objectives.rejuvenator_stolen_by_enemy", node.get("rejuvenator_stolen_by_enemy").asText()));
                }
                if (node.hasNonNull("player_boss_damage")) {
                    fields.add(new ReportView.CoachField("report.coach.objectives.player_boss_damage", formatNumber(node.get("player_boss_damage").asDouble())));
                }
                if (node.hasNonNull("boss_damage_share_pct")) {
                    fields.add(new ReportView.CoachField("report.coach.objectives.boss_damage_share_pct", String.format(Locale.ROOT, "%.1f%%", node.get("boss_damage_share_pct").asDouble())));
                }
                if (node.hasNonNull("first_mid_boss_min")) {
                    fields.add(new ReportView.CoachField("report.coach.objectives.first_mid_boss_min", String.format(Locale.ROOT, "%.1f min", node.get("first_mid_boss_min").asDouble())));
                }
            }
            case "build" -> {
                if (node.hasNonNull("match_rate_pct")) {
                    fields.add(new ReportView.CoachField("report.coach.build.match_rate_pct", String.format(Locale.ROOT, "%.1f%%", node.get("match_rate_pct").asDouble())));
                }
                JsonNode missing = node.path("missing_popular");
                if (missing.isArray() && !missing.isEmpty()) {
                    List<String> items = new ArrayList<>();
                    for (JsonNode m : missing) {
                        items.add(m.path("item").asText() + " (" + String.format(Locale.ROOT, "%.0f%% pick", m.path("pick_rate").asDouble()) + ")");
                    }
                    fields.add(new ReportView.CoachField("report.coach.build.missing_popular", String.join(", ", items)));
                }
                JsonNode offMeta = node.path("off_meta");
                if (offMeta.isArray() && !offMeta.isEmpty()) {
                    List<String> items = new ArrayList<>();
                    for (JsonNode o : offMeta) {
                        items.add(o.path("item").asText());
                    }
                    fields.add(new ReportView.CoachField("report.coach.build.off_meta", String.join(", ", items)));
                }
            }
            case "recent_form" -> {
                if (node.hasNonNull("sample_size")) {
                    fields.add(new ReportView.CoachField("report.coach.recent_form.sample_size", node.get("sample_size").asText()));
                }
                if (node.hasNonNull("win_rate_pct")) {
                    fields.add(new ReportView.CoachField("report.coach.recent_form.win_rate_pct", String.format(Locale.ROOT, "%.1f%%", node.get("win_rate_pct").asDouble())));
                }
                if (node.hasNonNull("avg_kda")) {
                    fields.add(new ReportView.CoachField("report.coach.recent_form.avg_kda", String.format(Locale.ROOT, "%.1f", node.get("avg_kda").asDouble())));
                }
                if (node.hasNonNull("avg_souls_per_min")) {
                    fields.add(new ReportView.CoachField("report.coach.recent_form.avg_souls_per_min", String.format(Locale.ROOT, "%.1f", node.get("avg_souls_per_min").asDouble())));
                }
                if (node.hasNonNull("avg_deaths_per_10_min")) {
                    fields.add(new ReportView.CoachField("report.coach.recent_form.avg_deaths_per_10_min", String.format(Locale.ROOT, "%.1f", node.get("avg_deaths_per_10_min").asDouble())));
                }
                if (node.hasNonNull("losses_in_a_row_before") && node.get("losses_in_a_row_before").asInt() > 0) {
                    fields.add(new ReportView.CoachField("report.coach.recent_form.losses_in_a_row_before", node.get("losses_in_a_row_before").asText()));
                }
                if (node.hasNonNull("games_last_24h")) {
                    fields.add(new ReportView.CoachField("report.coach.recent_form.games_last_24h", node.get("games_last_24h").asText()));
                }
            }
            case "matchup" -> {
                if (node.hasNonNull("lane_result")) {
                    fields.add(new ReportView.CoachField("report.coach.matchup.lane_result", capitalize(node.get("lane_result").asText())));
                }
                if (node.hasNonNull("lane_net_worth_diff")) {
                    fields.add(new ReportView.CoachField("report.coach.matchup.lane_net_worth_diff", String.format(Locale.ROOT, "%+d", node.get("lane_net_worth_diff").asLong())));
                }
                JsonNode laneOpponents = node.path("lane_opponents");
                if (laneOpponents.isArray() && !laneOpponents.isEmpty()) {
                    List<String> opps = new ArrayList<>();
                    for (JsonNode opp : laneOpponents) {
                        opps.add(opp.path("hero").asText());
                    }
                    fields.add(new ReportView.CoachField("report.coach.matchup.lane_opponents", String.join(", ", opps)));
                }
                if (node.hasNonNull("enemy_avg_win_rate_pct")) {
                    fields.add(new ReportView.CoachField("report.coach.matchup.enemy_avg_win_rate_pct", String.format(Locale.ROOT, "%.1f%%", node.get("enemy_avg_win_rate_pct").asDouble())));
                }
                JsonNode hardest = node.path("hardest");
                if (hardest.isArray() && !hardest.isEmpty()) {
                    List<String> hList = new ArrayList<>();
                    for (JsonNode h : hardest) {
                        hList.add(h.path("hero").asText() + " (" + String.format(Locale.ROOT, "%.1f%%", h.path("win_rate_pct").asDouble()) + ")");
                    }
                    fields.add(new ReportView.CoachField("report.coach.matchup.hardest", String.join(", ", hList)));
                }
                JsonNode easiest = node.path("easiest");
                if (easiest.isObject() && easiest.hasNonNull("hero")) {
                    fields.add(new ReportView.CoachField("report.coach.matchup.easiest", easiest.get("hero").asText() + " (" + String.format(Locale.ROOT, "%.1f%%", easiest.path("win_rate_pct").asDouble()) + ")"));
                }
            }
            default -> {
                for (Map.Entry<String, JsonNode> entry : node.properties()) {
                    if ("significance".equals(entry.getKey())) continue;
                    if (entry.getValue().isValueNode()) {
                        fields.add(new ReportView.CoachField("report.coach." + key + "." + entry.getKey(), entry.getValue().asText()));
                    }
                }
            }
        }
        return Collections.unmodifiableList(fields);
    }

    private static List<ReportView.OtherSignal> parseOtherSignals(JsonNode coachNode) {
        if (coachNode == null || !coachNode.isObject()) {
            return List.of();
        }
        List<ReportView.OtherSignal> signals = new ArrayList<>();
        for (Map.Entry<String, JsonNode> entry : coachNode.properties()) {
            String key = entry.getKey();
            if (KNOWN_COACH_KEYS.contains(key)) {
                continue;
            }
            JsonNode val = entry.getValue();
            if (val.isValueNode()) {
                signals.add(new ReportView.OtherSignal(key, val.asText()));
            } else if (val.isObject()) {
                for (Map.Entry<String, JsonNode> subEntry : val.properties()) {
                    if (subEntry.getValue().isValueNode()) {
                        signals.add(new ReportView.OtherSignal(key + "." + subEntry.getKey(), subEntry.getValue().asText()));
                    }
                }
            }
        }
        return Collections.unmodifiableList(signals);
    }

    private static String capitalize(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        return Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }
}
