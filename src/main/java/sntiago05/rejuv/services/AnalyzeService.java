package sntiago05.rejuv.services;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import sntiago05.rejuv.clients.AnalysisClient;
import sntiago05.rejuv.clients.DeadlockApiClient;
import sntiago05.rejuv.clients.DeadlockApiException;
import sntiago05.rejuv.clients.MatchMetadataSource;
import sntiago05.rejuv.clients.dto.HeroAsset;
import sntiago05.rejuv.clients.dto.ItemAsset;
import sntiago05.rejuv.clients.dto.ItemStat;
import sntiago05.rejuv.clients.dto.MatchHistoryEntry;
import sntiago05.rejuv.clients.dto.MetricPercentiles;
import sntiago05.rejuv.clients.dto.PlayerRank;
import sntiago05.rejuv.clients.dto.RankAsset;
import sntiago05.rejuv.config.AnthropicPricingProperties;
import sntiago05.rejuv.domain.AnalysisInput;
import sntiago05.rejuv.domain.AnalysisOutcome;
import sntiago05.rejuv.domain.AnalysisReport;
import sntiago05.rejuv.domain.AnalysisResult;
import sntiago05.rejuv.domain.MatchAnalysis;
import sntiago05.rejuv.domain.MatchFilter;
import sntiago05.rejuv.domain.MatchRank;
import sntiago05.rejuv.domain.MatchTelemetry;
import sntiago05.rejuv.domain.MatchVisuals;
import sntiago05.rejuv.repositories.MatchAnalysisRepository;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

@Service
public class AnalyzeService {

    private static final Logger log = LoggerFactory.getLogger(AnalyzeService.class);
    private static final ObjectMapper JSON_MAPPER = new JsonMapper();

    private final DeadlockApiClient deadlockApiClient;
    private final MatchMetadataSource matchMetadataSource;
    private final MetricsService metricsService;
    private final AnalysisClient analysisClient;
    private final MatchAnalysisRepository repository;
    private final MatchSearchService matchSearchService;
    private final AnthropicPricingProperties pricingProperties;
    private final UsageService usageService;
    private final MatchVisualsService visualsService;

    public AnalyzeService(DeadlockApiClient deadlockApiClient,
                          MatchMetadataSource matchMetadataSource,
                          MetricsService metricsService,
                          AnalysisClient analysisClient,
                          MatchAnalysisRepository repository,
                          MatchSearchService matchSearchService,
                          AnthropicPricingProperties pricingProperties,
                          UsageService usageService,
                          MatchVisualsService visualsService) {
        this.deadlockApiClient = deadlockApiClient;
        this.matchMetadataSource = matchMetadataSource;
        this.metricsService = metricsService;
        this.analysisClient = analysisClient;
        this.repository = repository;
        this.matchSearchService = matchSearchService;
        this.pricingProperties = pricingProperties;
        this.usageService = usageService;
        this.visualsService = visualsService;
    }

    /**
     * @throws UsageLimitExceededException when the free weekly limit is used up and a fresh AI call would be needed
     * @throws NoMatchHistoryException when the account has no match history
     * @throws MatchNotFoundException when nothing passes the filter
     * @throws sntiago05.rejuv.clients.DeadlockApiException (including DeadlockApiUnavailableException) when history,
     *         rank, benchmarks or assets cannot be read
     */
    public AnalysisReport analyze(long discordUserId, long accountId, MatchFilter filter) {
        MatchHistoryEntry match = matchSearchService.select(accountId, filter != null ? filter : MatchFilter.LATEST)
                .orElseGet(() -> {
                    List<MatchHistoryEntry> history = deadlockApiClient.getMatchHistory(accountId);
                    if (history.isEmpty()) {
                        throw new NoMatchHistoryException(accountId);
                    }
                    throw new MatchNotFoundException("No match found for account " + accountId + " with filter " + filter);
                });

        PlayerRank currentRank = deadlockApiClient.getPlayerRank(accountId);
        Optional<MatchRank> rank = metricsService.benchmarkRank(match, currentRank);
        List<HeroAsset> heroes = deadlockApiClient.getHeroes();
        List<RankAsset> ranks = deadlockApiClient.getRanks();

        Optional<MatchAnalysis> storedOpt = repository.findByAccountIdAndMatchId(accountId, match.matchId());
        if (storedOpt.isPresent()) {
            MatchAnalysis stored = storedOpt.get();
            try {
                AnalysisResult analysis = JSON_MAPPER.readValue(stored.getResultJson(), AnalysisResult.class);
                AnalysisInput headerInput = metricsService.buildInput(new MetricsRequest(
                        accountId, match, null, rank.orElse(null), Map.of(), Map.of(), List.of(), heroes, ranks));
                log.info("Analyze accountId={} matchId={} source=stored degraded=false inputTokens={} outputTokens={}",
                        accountId, match.matchId(), stored.getInputTokens(), stored.getOutputTokens());
                return new AnalysisReport(match.heroId(), headerInput.hero(), headerInput.rank(), headerInput.result(),
                        headerInput.durationMin(), analysis, Map.of(), false, storedVisuals(accountId, match.matchId()));
            } catch (Exception ex) {
                log.warn("Corrupted match analysis JSON for accountId={} matchId={}", accountId, match.matchId());
                repository.delete(stored);
            }
        }

        usageService.checkAllowance(discordUserId);

        MatchTelemetry telemetry;
        boolean degraded = false;
        try {
            telemetry = matchMetadataSource.getMatch(match.matchId());
        } catch (DeadlockApiException ex) {
            log.warn("Match metadata unavailable for accountId={} matchId={}: {}",
                    accountId, match.matchId(), ex.getClass().getSimpleName());
            telemetry = null;
            degraded = true;
        }

        Map<String, MetricPercentiles> benchmarks;
        Map<Long, ItemStat> itemStats = Map.of();
        List<ItemAsset> items = List.of();

        if (rank.isPresent()) {
            benchmarks = deadlockApiClient.getBenchmarks(match.heroId(), rank.get().tier());
            if (telemetry != null) {
                try {
                    itemStats = deadlockApiClient.getItemStats(match.heroId(), rank.get().tier());
                } catch (DeadlockApiException ex) {
                    log.warn("Item stats unavailable for heroId={} rankTier={}: {}",
                            match.heroId(), rank.get().tier(), ex.getClass().getSimpleName());
                    itemStats = Map.of();
                }
                items = deadlockApiClient.getItems();
            }
        } else {
            benchmarks = Map.of();
        }

        AnalysisInput input = metricsService.buildInput(new MetricsRequest(
                accountId, match, telemetry, rank.orElse(null), benchmarks, itemStats, items, heroes, ranks));
        AnalysisOutcome outcome = analysisClient.analyze(input);

        if (outcome.isFallback()) {
            log.info("Analyze accountId={} matchId={} source=fallback degraded={} inputTokens={} outputTokens={}",
                    accountId, match.matchId(), degraded, outcome.inputTokens(), outcome.outputTokens());
            return new AnalysisReport(match.heroId(), input.hero(), input.rank(), input.result(), input.durationMin(),
                    null, input.metrics(), degraded, visuals(accountId, telemetry));
        }

        String json;
        try {
            json = JSON_MAPPER.writeValueAsString(outcome.result());
        } catch (Exception ex) {
            throw new RuntimeException("Failed to serialize analysis result", ex);
        }

        MatchAnalysis entity = new MatchAnalysis(accountId, match.matchId(), match.heroId(),
                json, outcome.model(), outcome.inputTokens(), outcome.outputTokens(), Instant.now());
        try {
            repository.save(entity);
            usageService.recordAnalysis(discordUserId, match.matchId());
            log.info("Analyze accountId={} matchId={} source=ai degraded={} inputTokens={} outputTokens={} estimatedCostUsd={}",
                    accountId, match.matchId(), degraded, outcome.inputTokens(), outcome.outputTokens(),
                    estimatedCostUsd(outcome.inputTokens(), outcome.outputTokens()));
            return new AnalysisReport(match.heroId(), input.hero(), input.rank(), input.result(), input.durationMin(),
                    outcome.result(), Map.of(), degraded, visuals(accountId, telemetry));
        } catch (DataIntegrityViolationException ex) {
            Optional<MatchAnalysis> concurrent = repository.findByAccountIdAndMatchId(accountId, match.matchId());
            if (concurrent.isPresent()) {
                try {
                    AnalysisResult concurrentResult = JSON_MAPPER.readValue(concurrent.get().getResultJson(), AnalysisResult.class);
                    log.info("Analyze accountId={} matchId={} source=stored degraded=false inputTokens={} outputTokens={}",
                            accountId, match.matchId(), concurrent.get().getInputTokens(), concurrent.get().getOutputTokens());
                    return new AnalysisReport(match.heroId(), input.hero(), input.rank(), input.result(), input.durationMin(),
                            concurrentResult, Map.of(), false, visuals(accountId, telemetry));
                } catch (Exception parseEx) {
                    log.warn("Corrupted concurrent match analysis JSON for accountId={} matchId={}", accountId, match.matchId());
                }
            }
            return new AnalysisReport(match.heroId(), input.hero(), input.rank(), input.result(), input.durationMin(),
                    outcome.result(), Map.of(), degraded, visuals(accountId, telemetry));
        }
    }

    private MatchVisuals visuals(long accountId, MatchTelemetry telemetry) {
        return visualsService.build(accountId, telemetry).orElse(null);
    }

    /** Cached metadata read (not an AI call, no usage); any API failure just means no details. */
    private MatchVisuals storedVisuals(long accountId, long matchId) {
        try {
            return visuals(accountId, matchMetadataSource.getMatch(matchId));
        } catch (RuntimeException ex) {
            // Must never escape: the caller treats any exception as a corrupted stored row and deletes it.
            log.warn("Visuals unavailable for stored analysis accountId={} matchId={}: {}",
                    accountId, matchId, ex.getClass().getSimpleName());
            return null;
        }
    }

    String estimatedCostUsd(int inputTokens, int outputTokens) {
        if (pricingProperties == null
                || pricingProperties.inputUsdPerMillion() <= 0.0
                || pricingProperties.outputUsdPerMillion() <= 0.0) {
            return "unknown";
        }
        double cost = (inputTokens / 1_000_000.0) * pricingProperties.inputUsdPerMillion()
                + (outputTokens / 1_000_000.0) * pricingProperties.outputUsdPerMillion();
        return String.format(Locale.ROOT, "%.6f", cost);
    }
}
