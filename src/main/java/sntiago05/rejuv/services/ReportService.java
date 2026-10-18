package sntiago05.rejuv.services;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import sntiago05.rejuv.clients.MatchMetadataSource;
import sntiago05.rejuv.domain.AnalysisResult;
import sntiago05.rejuv.domain.MatchAnalysis;
import sntiago05.rejuv.domain.MatchTelemetry;
import sntiago05.rejuv.domain.MatchVisuals;
import sntiago05.rejuv.domain.ReportView;
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

    public ReportService(MatchAnalysisRepository repository,
                         MatchMetadataSource matchMetadataSource,
                         MatchVisualsService visualsService,
                         GameAssets gameAssets,
                         ChartRenderer chartRenderer) {
        this.repository = repository;
        this.matchMetadataSource = matchMetadataSource;
        this.visualsService = visualsService;
        this.gameAssets = gameAssets;
        this.chartRenderer = chartRenderer;
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

        String heroName;
        try {
            heroName = gameAssets.heroNames().get(stored.getHeroId());
        } catch (RuntimeException ex) {
            heroName = null;
        }
        if (heroName == null || heroName.isBlank()) {
            heroName = "hero_" + stored.getHeroId();
        }

        Double durationMin = null;
        List<MatchVisuals.PurchasedItem> build = List.of();
        boolean hasChart = false;

        try {
            MatchTelemetry telemetry = matchMetadataSource.getMatch(stored.getMatchId());
            if (telemetry != null) {
                durationMin = Math.round((telemetry.durationS() / 60.0) * 10.0) / 10.0;
                Optional<MatchVisuals> visuals = visualsService.build(stored.getAccountId(), telemetry);
                if (visuals.isPresent()) {
                    build = visuals.get().items();
                    hasChart = visuals.get().chart() != null;
                }
            }
        } catch (RuntimeException ex) {
            log.warn("Match metadata unavailable for publicId={}: {}", publicId, ex.getClass().getSimpleName());
            durationMin = null;
            build = List.of();
            hasChart = false;
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
                stored.getCreatedAt()
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
}
