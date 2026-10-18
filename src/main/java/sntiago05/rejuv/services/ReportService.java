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

    public ReportService(MatchAnalysisRepository repository,
                         MatchMetadataSource matchMetadataSource,
                         MatchVisualsService visualsService,
                         GameAssets gameAssets,
                         ChartRenderer chartRenderer,
                         DeadlockApiClient deadlockApiClient,
                         SoulCurveSvg soulCurveSvg) {
        this.repository = repository;
        this.matchMetadataSource = matchMetadataSource;
        this.visualsService = visualsService;
        this.gameAssets = gameAssets;
        this.chartRenderer = chartRenderer;
        this.deadlockApiClient = deadlockApiClient;
        this.soulCurveSvg = soulCurveSvg;
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

        try {
            MatchTelemetry telemetry = matchMetadataSource.getMatch(stored.getMatchId());
            if (telemetry != null) {
                durationMin = Math.round((telemetry.durationS() / 60.0) * 10.0) / 10.0;
                Optional<MatchVisuals> visuals = visualsService.build(stored.getAccountId(), telemetry);
                if (visuals.isPresent()) {
                    build = visuals.get().items();
                    hasChart = visuals.get().chart() != null;
                }
                hasSoulCurve = computeSoulCurveData(stored, telemetry).isPresent();
            }
        } catch (RuntimeException ex) {
            log.warn("Match metadata unavailable for publicId={}: {}", publicId, ex.getClass().getSimpleName());
            durationMin = null;
            build = List.of();
            hasChart = false;
            hasSoulCurve = false;
        }

        return Optional.of(new ReportView(
                stored.getPublicId(),
                stored.getMatchId(),
                heroName,
                stored.getHeroId(),
                null,
                null,
                durationMin,
                analysis,
                build,
                hasChart,
                stored.getCreatedAt(),
                hasSoulCurve
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
}
