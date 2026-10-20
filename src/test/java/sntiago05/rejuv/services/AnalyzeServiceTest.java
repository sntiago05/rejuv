package sntiago05.rejuv.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataIntegrityViolationException;
import sntiago05.rejuv.clients.AnalysisClient;
import sntiago05.rejuv.clients.DeadlockApiClient;
import sntiago05.rejuv.clients.DeadlockApiException;
import sntiago05.rejuv.clients.DeadlockApiUnavailableException;
import sntiago05.rejuv.clients.MatchMetadataNotFoundException;
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
import sntiago05.rejuv.domain.ItemPurchase;
import sntiago05.rejuv.domain.MatchAnalysis;
import sntiago05.rejuv.domain.MatchFilter;
import sntiago05.rejuv.domain.MatchTelemetry;
import sntiago05.rejuv.domain.MatchVisuals;
import sntiago05.rejuv.domain.PlayerTelemetry;
import sntiago05.rejuv.domain.StatSnapshot;
import sntiago05.rejuv.repositories.MatchAnalysisRepository;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(OutputCaptureExtension.class)
class AnalyzeServiceTest {

    private static final long CALLER_ID = 123456789L;
    private static final long ACCOUNT_ID = 1000001L;
    private static final long MATCH_ID = 900000001L;
    private static final int HERO_ID = 1;

    private static final MatchHistoryEntry MATCH = new MatchHistoryEntry(
            MATCH_ID, 1, HERO_ID, 1700000000L, 1800, 4, 0, 0, 1, 5, 3, 7, 30000L, 150, 10, 73);
    private static final PlayerRank RANK = new PlayerRank(73, 7, 3);
    private static final List<HeroAsset> HEROES = List.of(new HeroAsset(HERO_ID, "hero_infernus", "Infernus"));
    private static final List<RankAsset> RANKS = List.of(new RankAsset(7, "Archon"));
    private static final List<ItemAsset> ITEMS = List.of(new ItemAsset(101L, "citadel_item_basic_magazine", "Basic Magazine", "upgrade", 1, "weapon", 500));
    private static final Map<String, MetricPercentiles> BENCHMARKS = Map.of(
            "kills", new MetricPercentiles(5.0, 3.0, 5.0, 8.0));
    private static final Map<Long, ItemStat> ITEM_STATS = Map.of(
            101L, new ItemStat(101L, 1000L, 550L, 450L, 300.0, 0.15));
    private static final MatchTelemetry TELEMETRY = new MatchTelemetry(
            MATCH_ID, 1800, List.of(new PlayerTelemetry(
                    ACCOUNT_ID, 0, HERO_ID,
                    List.of(new ItemPurchase(101L, 250, null)),
                    List.of(new StatSnapshot(180, 1500, 2000, 0)),
                    List.of())));

    private static final AnalysisResult AI_RESULT = new AnalysisResult(
            "Great farming", "Too many deaths", "Ward more", List.of("10 kills", "20k souls"));

    private DeadlockApiClient deadlockApiClient;
    private MatchMetadataSource matchMetadataSource;
    private MetricsService metricsService;
    private AnalysisClient analysisClient;
    private MatchAnalysisRepository repository;
    private MatchVisualsService visualsService;
    private CoachContextService coachContextService;
    private AnalysisSpendGuard spendGuard;
    private AnalyzeService service;

    @BeforeEach
    void setUp() {
        deadlockApiClient = mock(DeadlockApiClient.class);
        matchMetadataSource = mock(MatchMetadataSource.class);
        metricsService = new MetricsService();
        analysisClient = mock(AnalysisClient.class);
        repository = mock(MatchAnalysisRepository.class);
        visualsService = mock(MatchVisualsService.class);
        coachContextService = mock(CoachContextService.class);
        when(coachContextService.build(any())).thenReturn(Map.of());
        spendGuard = mock(AnalysisSpendGuard.class);
        GameAssets gameAssets = mock(GameAssets.class);
        when(gameAssets.heroNames()).thenReturn(Map.of(HERO_ID, "Infernus", 2, "Seven"));
        MatchSearchService matchSearchService = new MatchSearchService(deadlockApiClient, gameAssets);
        AnthropicPricingProperties pricing = new AnthropicPricingProperties(0, 0);
        service = new AnalyzeService(deadlockApiClient, matchMetadataSource, metricsService, analysisClient, repository, matchSearchService, pricing, visualsService, coachContextService, spendGuard);
    }

    @Test
    void freshAiPathStoresRowAndReturnsAiResultWithSequentialCalls() {
        when(deadlockApiClient.getMatchHistory(ACCOUNT_ID)).thenReturn(List.of(MATCH));
        when(deadlockApiClient.getPlayerRank(ACCOUNT_ID)).thenReturn(RANK);
        when(deadlockApiClient.getHeroes()).thenReturn(HEROES);
        when(deadlockApiClient.getRanks()).thenReturn(RANKS);
        when(repository.findByAccountIdAndMatchId(ACCOUNT_ID, MATCH_ID)).thenReturn(Optional.empty());
        when(matchMetadataSource.getMatch(MATCH_ID)).thenReturn(TELEMETRY);
        when(deadlockApiClient.getBenchmarks(HERO_ID, 7)).thenReturn(BENCHMARKS);
        when(deadlockApiClient.getItemStats(HERO_ID, 7)).thenReturn(ITEM_STATS);
        when(deadlockApiClient.getItems()).thenReturn(ITEMS);

        AnalysisOutcome outcome = new AnalysisOutcome(AI_RESULT, "claude-haiku-5-5", 1, 1000, 250);
        when(analysisClient.analyze(any())).thenReturn(outcome);

        AnalysisReport report = service.analyze(CALLER_ID, ACCOUNT_ID, MatchFilter.LATEST);

        assertThat(report.hero()).isEqualTo("Infernus");
        assertThat(report.rank()).isEqualTo("Archon 3");
        assertThat(report.result()).isEqualTo("win");
        assertThat(report.durationMin()).isEqualTo(30.0);
        assertThat(report.analysis()).isEqualTo(AI_RESULT);
        assertThat(report.metrics()).isEmpty();
        assertThat(report.degraded()).isFalse();

        ArgumentCaptor<MatchAnalysis> savedCaptor = ArgumentCaptor.forClass(MatchAnalysis.class);
        verify(repository).save(savedCaptor.capture());
        MatchAnalysis saved = savedCaptor.getValue();
        assertThat(saved.getAccountId()).isEqualTo(ACCOUNT_ID);
        assertThat(saved.getMatchId()).isEqualTo(MATCH_ID);
        assertThat(saved.getHeroId()).isEqualTo(HERO_ID);
        assertThat(saved.getModel()).isEqualTo("claude-haiku-5-5");
        assertThat(saved.getInputTokens()).isEqualTo(1000);
        assertThat(saved.getOutputTokens()).isEqualTo(250);
        assertThat(report.publicId()).isNotNull().isEqualTo(saved.getPublicId());

        InOrder inOrder = inOrder(deadlockApiClient, repository, matchMetadataSource, analysisClient);
        inOrder.verify(deadlockApiClient).getMatchHistory(ACCOUNT_ID);
        inOrder.verify(deadlockApiClient).getPlayerRank(ACCOUNT_ID);
        inOrder.verify(deadlockApiClient).getHeroes();
        inOrder.verify(deadlockApiClient).getRanks();
        inOrder.verify(repository).findByAccountIdAndMatchId(ACCOUNT_ID, MATCH_ID);
        inOrder.verify(matchMetadataSource).getMatch(MATCH_ID);
        inOrder.verify(deadlockApiClient).getBenchmarks(HERO_ID, 7);
        inOrder.verify(deadlockApiClient).getItemStats(HERO_ID, 7);
        inOrder.verify(deadlockApiClient).getItems();

        ArgumentCaptor<AnalysisInput> inputCaptor = ArgumentCaptor.forClass(AnalysisInput.class);
        inOrder.verify(analysisClient).analyze(inputCaptor.capture());
        assertThat(inputCaptor.getValue().hero()).isEqualTo("Infernus");
        assertThat(inputCaptor.getValue().result()).isEqualTo("win");

        assertThat(saved.getInputJson()).isNotNull();
        String expectedInputJson = new JsonMapper().writeValueAsString(inputCaptor.getValue());
        assertThat(saved.getInputJson()).isEqualTo(expectedInputJson);
        assertThat(saved.getInputJson())
                .doesNotContain("token")
                .doesNotContain("key")
                .doesNotContain("secret");
    }

    @Test
    void storedPathReturnsStoredAnalysisWithoutBenchmarksOrAiCall() throws Exception {
        String resultJson = new JsonMapper().writeValueAsString(AI_RESULT);
        MatchAnalysis stored = new MatchAnalysis(
                ACCOUNT_ID, MATCH_ID, HERO_ID, resultJson, "claude-haiku-5-5", 800, 200, Instant.now());

        when(deadlockApiClient.getMatchHistory(ACCOUNT_ID)).thenReturn(List.of(MATCH));
        when(deadlockApiClient.getPlayerRank(ACCOUNT_ID)).thenReturn(RANK);
        when(deadlockApiClient.getHeroes()).thenReturn(HEROES);
        when(deadlockApiClient.getRanks()).thenReturn(RANKS);
        when(repository.findByAccountIdAndMatchId(ACCOUNT_ID, MATCH_ID)).thenReturn(Optional.of(stored));

        AnalysisReport report = service.analyze(CALLER_ID, ACCOUNT_ID, MatchFilter.LATEST);

        assertThat(report.hero()).isEqualTo("Infernus");
        assertThat(report.rank()).isEqualTo("Archon 3");
        assertThat(report.result()).isEqualTo("win");
        assertThat(report.durationMin()).isEqualTo(30.0);
        assertThat(report.analysis()).isEqualTo(AI_RESULT);
        assertThat(report.metrics()).isEmpty();
        assertThat(report.degraded()).isFalse();
        assertThat(report.publicId()).isNotNull().isEqualTo(stored.getPublicId());

        verify(matchMetadataSource, times(1)).getMatch(MATCH_ID);
        assertThat(report.visuals()).isNull();
        verify(deadlockApiClient, never()).getBenchmarks(anyInt(), anyInt());
        verify(deadlockApiClient, never()).getItemStats(anyInt(), anyInt());
        verify(deadlockApiClient, never()).getItems();
        verify(analysisClient, never()).analyze(any());
        verify(repository, never()).save(any());
        verify(coachContextService, never()).build(any());
    }

    @Test
    void storedPathDoesNotConsumeSpendGuardQuota() throws Exception {
        String resultJson = new JsonMapper().writeValueAsString(AI_RESULT);
        MatchAnalysis stored = new MatchAnalysis(
                ACCOUNT_ID, MATCH_ID, HERO_ID, resultJson, "claude-haiku-5-5", 800, 200, Instant.now());
        when(deadlockApiClient.getMatchHistory(ACCOUNT_ID)).thenReturn(List.of(MATCH));
        when(repository.findByAccountIdAndMatchId(ACCOUNT_ID, MATCH_ID)).thenReturn(Optional.of(stored));

        service.analyze(CALLER_ID, ACCOUNT_ID, MatchFilter.LATEST);

        verify(spendGuard, never()).reserve(anyLong());
    }

    @Test
    void freshAiPathReservesQuotaOnceForTheCaller() {
        when(deadlockApiClient.getMatchHistory(ACCOUNT_ID)).thenReturn(List.of(MATCH));
        when(deadlockApiClient.getPlayerRank(ACCOUNT_ID)).thenReturn(RANK);
        when(deadlockApiClient.getHeroes()).thenReturn(HEROES);
        when(deadlockApiClient.getRanks()).thenReturn(RANKS);
        when(repository.findByAccountIdAndMatchId(ACCOUNT_ID, MATCH_ID)).thenReturn(Optional.empty());
        when(matchMetadataSource.getMatch(MATCH_ID)).thenReturn(TELEMETRY);
        when(deadlockApiClient.getBenchmarks(HERO_ID, 7)).thenReturn(BENCHMARKS);
        when(deadlockApiClient.getItemStats(HERO_ID, 7)).thenReturn(ITEM_STATS);
        when(deadlockApiClient.getItems()).thenReturn(ITEMS);
        when(analysisClient.analyze(any())).thenReturn(new AnalysisOutcome(AI_RESULT, "claude-haiku-5-5", 1, 1000, 250));

        service.analyze(CALLER_ID, ACCOUNT_ID, MatchFilter.LATEST);

        verify(spendGuard, times(1)).reserve(CALLER_ID);
        verify(analysisClient, times(1)).analyze(any());
    }

    @Test
    void spendGuardRefusalSkipsAiCallAndStoresNothing() {
        when(deadlockApiClient.getMatchHistory(ACCOUNT_ID)).thenReturn(List.of(MATCH));
        when(deadlockApiClient.getPlayerRank(ACCOUNT_ID)).thenReturn(RANK);
        when(deadlockApiClient.getHeroes()).thenReturn(HEROES);
        when(deadlockApiClient.getRanks()).thenReturn(RANKS);
        when(repository.findByAccountIdAndMatchId(ACCOUNT_ID, MATCH_ID)).thenReturn(Optional.empty());
        when(matchMetadataSource.getMatch(MATCH_ID)).thenReturn(TELEMETRY);
        when(deadlockApiClient.getBenchmarks(HERO_ID, 7)).thenReturn(BENCHMARKS);
        when(deadlockApiClient.getItemStats(HERO_ID, 7)).thenReturn(ITEM_STATS);
        when(deadlockApiClient.getItems()).thenReturn(ITEMS);
        when(analysisClient.analyze(any())).thenReturn(new AnalysisOutcome(AI_RESULT, "claude-haiku-5-5", 1, 1000, 250));
        doThrow(new AnalysisLimitExceededException(
                AnalysisLimitExceededException.Reason.COOLDOWN, java.time.Duration.ofSeconds(30), 0))
                .when(spendGuard).reserve(CALLER_ID);

        assertThatThrownBy(() -> service.analyze(CALLER_ID, ACCOUNT_ID, MatchFilter.LATEST))
                .isInstanceOf(AnalysisLimitExceededException.class)
                .extracting(e -> ((AnalysisLimitExceededException) e).reason())
                .isEqualTo(AnalysisLimitExceededException.Reason.COOLDOWN);

        verify(analysisClient, never()).analyze(any());
        verify(repository, never()).save(any());
    }

    @Test
    void storedPathAttachesVisualsFromCachedTelemetryWithoutAi() throws Exception {
        MatchVisuals visuals = new MatchVisuals(List.of(new MatchVisuals.PurchasedItem(101L, "Basic Magazine", 250)), null);
        stubStored();
        when(matchMetadataSource.getMatch(MATCH_ID)).thenReturn(TELEMETRY);
        when(visualsService.build(ACCOUNT_ID, TELEMETRY)).thenReturn(Optional.of(visuals));

        AnalysisReport report = service.analyze(CALLER_ID, ACCOUNT_ID, MatchFilter.LATEST);

        assertThat(report.analysis()).isEqualTo(AI_RESULT);
        assertThat(report.visuals()).isSameAs(visuals);
        verify(matchMetadataSource, times(1)).getMatch(MATCH_ID);
        verify(analysisClient, never()).analyze(any());
        verify(repository, never()).save(any());
    }

    @Test
    void storedPathWithUnavailableMetadataReturnsStoredTextWithoutVisuals() throws Exception {
        stubStored();
        when(matchMetadataSource.getMatch(MATCH_ID)).thenThrow(new DeadlockApiUnavailableException("down", null, null));

        AnalysisReport report = service.analyze(CALLER_ID, ACCOUNT_ID, MatchFilter.LATEST);

        assertThat(report.analysis()).isEqualTo(AI_RESULT);
        assertThat(report.visuals()).isNull();
        assertThat(report.degraded()).isFalse();
        verify(analysisClient, never()).analyze(any());
        verify(repository, never()).delete(any());
    }

    @Test
    void storedPathUnexpectedVisualsFailureKeepsStoredRowAndText() throws Exception {
        stubStored();
        when(matchMetadataSource.getMatch(MATCH_ID)).thenReturn(TELEMETRY);
        when(visualsService.build(ACCOUNT_ID, TELEMETRY)).thenThrow(new IllegalStateException("boom"));

        AnalysisReport report = service.analyze(CALLER_ID, ACCOUNT_ID, MatchFilter.LATEST);

        assertThat(report.analysis()).isEqualTo(AI_RESULT);
        assertThat(report.visuals()).isNull();
        verify(repository, never()).delete(any());
        verify(analysisClient, never()).analyze(any());
    }

    @Test
    void freshPathAttachesVisualsBuiltFromFetchedTelemetry() {
        MatchVisuals visuals = new MatchVisuals(List.of(new MatchVisuals.PurchasedItem(101L, "Basic Magazine", 250)), null);
        stubFresh();
        when(visualsService.build(ACCOUNT_ID, TELEMETRY)).thenReturn(Optional.of(visuals));

        AnalysisReport report = service.analyze(CALLER_ID, ACCOUNT_ID, MatchFilter.LATEST);

        assertThat(report.visuals()).isSameAs(visuals);
        verify(matchMetadataSource, times(1)).getMatch(MATCH_ID);
    }

    @Test
    void freshPathWithoutTelemetryHasNoVisuals() {
        stubFresh();
        when(matchMetadataSource.getMatch(MATCH_ID)).thenThrow(new DeadlockApiUnavailableException("down", null, null));
        when(visualsService.build(ACCOUNT_ID, null)).thenReturn(Optional.empty());

        AnalysisReport report = service.analyze(CALLER_ID, ACCOUNT_ID, MatchFilter.LATEST);

        assertThat(report.degraded()).isTrue();
        assertThat(report.visuals()).isNull();
        assertThat(report.publicId()).isNull();
        assertThat(report.analysis()).isNotNull();
        verify(repository, never()).save(any());
    }

    @Test
    void degradedRunIsNotStoredAndSecondCallCallsAiAgain() {
        stubFresh();
        when(matchMetadataSource.getMatch(MATCH_ID)).thenThrow(new DeadlockApiUnavailableException("down", null, null));
        when(visualsService.build(ACCOUNT_ID, null)).thenReturn(Optional.empty());

        AnalysisReport first = service.analyze(CALLER_ID, ACCOUNT_ID, MatchFilter.LATEST);
        assertThat(first.degraded()).isTrue();
        assertThat(first.analysis()).isNotNull();
        assertThat(first.publicId()).isNull();

        AnalysisReport second = service.analyze(CALLER_ID, ACCOUNT_ID, MatchFilter.LATEST);
        assertThat(second.degraded()).isTrue();
        assertThat(second.analysis()).isNotNull();
        assertThat(second.publicId()).isNull();

        verify(analysisClient, times(2)).analyze(any());
        verify(repository, never()).save(any());
    }

    private void stubStored() throws Exception {
        String resultJson = new JsonMapper().writeValueAsString(AI_RESULT);
        MatchAnalysis stored = new MatchAnalysis(
                ACCOUNT_ID, MATCH_ID, HERO_ID, resultJson, "claude-haiku-5-5", 800, 200, Instant.now());
        when(deadlockApiClient.getMatchHistory(ACCOUNT_ID)).thenReturn(List.of(MATCH));
        when(deadlockApiClient.getPlayerRank(ACCOUNT_ID)).thenReturn(RANK);
        when(deadlockApiClient.getHeroes()).thenReturn(HEROES);
        when(deadlockApiClient.getRanks()).thenReturn(RANKS);
        when(repository.findByAccountIdAndMatchId(ACCOUNT_ID, MATCH_ID)).thenReturn(Optional.of(stored));
    }

    private void stubFresh() {
        when(deadlockApiClient.getMatchHistory(ACCOUNT_ID)).thenReturn(List.of(MATCH));
        when(deadlockApiClient.getPlayerRank(ACCOUNT_ID)).thenReturn(RANK);
        when(deadlockApiClient.getHeroes()).thenReturn(HEROES);
        when(deadlockApiClient.getRanks()).thenReturn(RANKS);
        when(repository.findByAccountIdAndMatchId(ACCOUNT_ID, MATCH_ID)).thenReturn(Optional.empty());
        when(matchMetadataSource.getMatch(MATCH_ID)).thenReturn(TELEMETRY);
        when(deadlockApiClient.getBenchmarks(HERO_ID, 7)).thenReturn(BENCHMARKS);
        when(deadlockApiClient.getItemStats(HERO_ID, 7)).thenReturn(ITEM_STATS);
        when(deadlockApiClient.getItems()).thenReturn(ITEMS);
        when(analysisClient.analyze(any())).thenReturn(new AnalysisOutcome(AI_RESULT, "claude-haiku-5-5", 1, 1000, 250));
    }

    @Test
    void corruptedStoredJsonIsDeletedAndExecutionContinuesAsFresh() {
        MatchAnalysis corrupted = new MatchAnalysis(
                ACCOUNT_ID, MATCH_ID, HERO_ID, "{invalid_json", "claude-haiku-5-5", 800, 200, Instant.now());

        when(deadlockApiClient.getMatchHistory(ACCOUNT_ID)).thenReturn(List.of(MATCH));
        when(deadlockApiClient.getPlayerRank(ACCOUNT_ID)).thenReturn(RANK);
        when(deadlockApiClient.getHeroes()).thenReturn(HEROES);
        when(deadlockApiClient.getRanks()).thenReturn(RANKS);
        when(repository.findByAccountIdAndMatchId(ACCOUNT_ID, MATCH_ID)).thenReturn(Optional.of(corrupted));
        when(matchMetadataSource.getMatch(MATCH_ID)).thenReturn(TELEMETRY);
        when(deadlockApiClient.getBenchmarks(HERO_ID, 7)).thenReturn(BENCHMARKS);
        when(deadlockApiClient.getItemStats(HERO_ID, 7)).thenReturn(ITEM_STATS);
        when(deadlockApiClient.getItems()).thenReturn(ITEMS);
        when(analysisClient.analyze(any())).thenReturn(new AnalysisOutcome(AI_RESULT, "claude-haiku-5-5", 1, 1000, 250));

        AnalysisReport report = service.analyze(CALLER_ID, ACCOUNT_ID, MatchFilter.LATEST);

        verify(repository).delete(corrupted);
        assertThat(report.analysis()).isEqualTo(AI_RESULT);
        verify(analysisClient).analyze(any());
        verify(repository).save(any());
    }

    static List<DeadlockApiException> metadataExceptions() {
        return List.of(
                new MatchMetadataNotFoundException("Match not found: " + MATCH_ID),
                new DeadlockApiUnavailableException("busy", null, null),
                new DeadlockApiException("unexpected failure")
        );
    }

    @ParameterizedTest
    @MethodSource("metadataExceptions")
    void metadataExceptionsDegradeRunAndSkipItemStatsAndItems(DeadlockApiException exception) {
        when(deadlockApiClient.getMatchHistory(ACCOUNT_ID)).thenReturn(List.of(MATCH));
        when(deadlockApiClient.getPlayerRank(ACCOUNT_ID)).thenReturn(RANK);
        when(deadlockApiClient.getHeroes()).thenReturn(HEROES);
        when(deadlockApiClient.getRanks()).thenReturn(RANKS);
        when(repository.findByAccountIdAndMatchId(ACCOUNT_ID, MATCH_ID)).thenReturn(Optional.empty());
        when(matchMetadataSource.getMatch(MATCH_ID)).thenThrow(exception);
        when(deadlockApiClient.getBenchmarks(HERO_ID, 7)).thenReturn(BENCHMARKS);
        when(analysisClient.analyze(any())).thenReturn(new AnalysisOutcome(AI_RESULT, "claude-haiku-5-5", 1, 1000, 250));

        AnalysisReport report = service.analyze(CALLER_ID, ACCOUNT_ID, MatchFilter.LATEST);

        assertThat(report.degraded()).isTrue();
        assertThat(report.analysis()).isEqualTo(AI_RESULT);
        assertThat(report.publicId()).isNull();
        verify(repository, never()).save(any());
        verify(deadlockApiClient, never()).getItemStats(anyInt(), anyInt());
        verify(deadlockApiClient, never()).getItems();
        verify(analysisClient).analyze(any());
    }

    @Test
    void itemStatsFailureKeepsRunNonDegradedAndSendsItemsWithoutBenchmark() {
        when(deadlockApiClient.getMatchHistory(ACCOUNT_ID)).thenReturn(List.of(MATCH));
        when(deadlockApiClient.getPlayerRank(ACCOUNT_ID)).thenReturn(RANK);
        when(deadlockApiClient.getHeroes()).thenReturn(HEROES);
        when(deadlockApiClient.getRanks()).thenReturn(RANKS);
        when(repository.findByAccountIdAndMatchId(ACCOUNT_ID, MATCH_ID)).thenReturn(Optional.empty());
        when(matchMetadataSource.getMatch(MATCH_ID)).thenReturn(TELEMETRY);
        when(deadlockApiClient.getBenchmarks(HERO_ID, 7)).thenReturn(BENCHMARKS);
        when(deadlockApiClient.getItemStats(HERO_ID, 7)).thenThrow(new DeadlockApiException("item stats failed"));
        when(deadlockApiClient.getItems()).thenReturn(ITEMS);

        when(analysisClient.analyze(any())).thenReturn(new AnalysisOutcome(AI_RESULT, "claude-haiku-5-5", 1, 1000, 250));

        AnalysisReport report = service.analyze(CALLER_ID, ACCOUNT_ID, MatchFilter.LATEST);

        assertThat(report.degraded()).isFalse();
        assertThat(report.analysis()).isEqualTo(AI_RESULT);

        ArgumentCaptor<AnalysisInput> captor = ArgumentCaptor.forClass(AnalysisInput.class);
        verify(analysisClient).analyze(captor.capture());
        assertThat(captor.getValue().items()).isNotEmpty();
        assertThat(captor.getValue().items().get(0).avgBoughtAtMin()).isNull();
    }

    @Test
    void unrankedPlayerSkipsBenchmarksAndItemStatsAndReportsNullRank() {
        MatchHistoryEntry unrankedMatch = new MatchHistoryEntry(
                MATCH_ID, 1, HERO_ID, 1700000000L, 1800, 0, 0, 0, 1, 5, 3, 7, 30000L, 150, 10, null);
        PlayerRank unrankedPlayer = new PlayerRank(0, 0, 0);

        when(deadlockApiClient.getMatchHistory(ACCOUNT_ID)).thenReturn(List.of(unrankedMatch));
        when(deadlockApiClient.getPlayerRank(ACCOUNT_ID)).thenReturn(unrankedPlayer);
        when(deadlockApiClient.getHeroes()).thenReturn(HEROES);
        when(deadlockApiClient.getRanks()).thenReturn(RANKS);
        when(repository.findByAccountIdAndMatchId(ACCOUNT_ID, MATCH_ID)).thenReturn(Optional.empty());
        when(matchMetadataSource.getMatch(MATCH_ID)).thenReturn(TELEMETRY);
        when(analysisClient.analyze(any())).thenReturn(new AnalysisOutcome(AI_RESULT, "claude-haiku-5-5", 1, 1000, 250));

        AnalysisReport report = service.analyze(CALLER_ID, ACCOUNT_ID, MatchFilter.LATEST);

        assertThat(report.rank()).isNull();
        verify(deadlockApiClient, never()).getBenchmarks(anyInt(), anyInt());
        verify(deadlockApiClient, never()).getItemStats(anyInt(), anyInt());
        verify(deadlockApiClient, never()).getItems();
    }

    @Test
    void aiFallbackStoresNothingAndReturnsInputMetrics() {
        when(deadlockApiClient.getMatchHistory(ACCOUNT_ID)).thenReturn(List.of(MATCH));
        when(deadlockApiClient.getPlayerRank(ACCOUNT_ID)).thenReturn(RANK);
        when(deadlockApiClient.getHeroes()).thenReturn(HEROES);
        when(deadlockApiClient.getRanks()).thenReturn(RANKS);
        when(repository.findByAccountIdAndMatchId(ACCOUNT_ID, MATCH_ID)).thenReturn(Optional.empty());
        when(matchMetadataSource.getMatch(MATCH_ID)).thenReturn(TELEMETRY);
        when(deadlockApiClient.getBenchmarks(HERO_ID, 7)).thenReturn(BENCHMARKS);
        when(deadlockApiClient.getItemStats(HERO_ID, 7)).thenReturn(ITEM_STATS);
        when(deadlockApiClient.getItems()).thenReturn(ITEMS);

        AnalysisOutcome fallbackOutcome = new AnalysisOutcome(null, "claude-haiku-5-5", 2, 500, 0);
        when(analysisClient.analyze(any())).thenReturn(fallbackOutcome);

        AnalysisReport report = service.analyze(CALLER_ID, ACCOUNT_ID, MatchFilter.LATEST);

        assertThat(report.analysis()).isNull();
        assertThat(report.metrics()).isNotEmpty();
        assertThat(report.publicId()).isNull();
        verify(repository, never()).save(any());
    }

    @Test
    void dataIntegrityViolationExceptionOnSaveReturnsConcurrentlyStoredReport() throws Exception {
        when(deadlockApiClient.getMatchHistory(ACCOUNT_ID)).thenReturn(List.of(MATCH));
        when(deadlockApiClient.getPlayerRank(ACCOUNT_ID)).thenReturn(RANK);
        when(deadlockApiClient.getHeroes()).thenReturn(HEROES);
        when(deadlockApiClient.getRanks()).thenReturn(RANKS);
        when(matchMetadataSource.getMatch(MATCH_ID)).thenReturn(TELEMETRY);
        when(deadlockApiClient.getBenchmarks(HERO_ID, 7)).thenReturn(BENCHMARKS);
        when(deadlockApiClient.getItemStats(HERO_ID, 7)).thenReturn(ITEM_STATS);
        when(deadlockApiClient.getItems()).thenReturn(ITEMS);

        AnalysisOutcome outcome = new AnalysisOutcome(AI_RESULT, "claude-haiku-5-5", 1, 1000, 250);
        when(analysisClient.analyze(any())).thenReturn(outcome);

        String resultJson = new JsonMapper().writeValueAsString(AI_RESULT);
        MatchAnalysis concurrent = new MatchAnalysis(
                ACCOUNT_ID, MATCH_ID, HERO_ID, resultJson, "claude-haiku-5-5", 1000, 250, Instant.now());

        when(repository.findByAccountIdAndMatchId(ACCOUNT_ID, MATCH_ID))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(concurrent));

        doThrow(new DataIntegrityViolationException("duplicate")).when(repository).save(any());

        AnalysisReport report = service.analyze(CALLER_ID, ACCOUNT_ID, MatchFilter.LATEST);

        assertThat(report.analysis()).isEqualTo(AI_RESULT);
        assertThat(report.degraded()).isFalse();
        assertThat(report.publicId()).isNotNull().isEqualTo(concurrent.getPublicId());
    }

    @Test
    void emptyHistoryThrowsNoMatchHistoryExceptionWithoutFurtherCalls() {
        when(deadlockApiClient.getMatchHistory(ACCOUNT_ID)).thenReturn(List.of());

        assertThatThrownBy(() -> service.analyze(CALLER_ID, ACCOUNT_ID, MatchFilter.LATEST))
                .isInstanceOf(NoMatchHistoryException.class);

        verify(deadlockApiClient, never()).getPlayerRank(anyLong());
        verify(repository, never()).findByAccountIdAndMatchId(anyLong(), anyLong());
    }

    @Test
    void getPlayerRankThrowingUnavailablePropagatesAndSavesNothing() {
        when(deadlockApiClient.getMatchHistory(ACCOUNT_ID)).thenReturn(List.of(MATCH));
        DeadlockApiUnavailableException unavailable = new DeadlockApiUnavailableException("busy", null, null);
        when(deadlockApiClient.getPlayerRank(ACCOUNT_ID)).thenThrow(unavailable);

        assertThatThrownBy(() -> service.analyze(CALLER_ID, ACCOUNT_ID, MatchFilter.LATEST)).isSameAs(unavailable);
        verify(repository, never()).save(any());
    }

    @Test
    void getBenchmarksThrowingUnavailablePropagatesAndSavesNothing() {
        when(deadlockApiClient.getMatchHistory(ACCOUNT_ID)).thenReturn(List.of(MATCH));
        when(deadlockApiClient.getPlayerRank(ACCOUNT_ID)).thenReturn(RANK);
        when(deadlockApiClient.getHeroes()).thenReturn(HEROES);
        when(deadlockApiClient.getRanks()).thenReturn(RANKS);
        when(repository.findByAccountIdAndMatchId(ACCOUNT_ID, MATCH_ID)).thenReturn(Optional.empty());
        when(matchMetadataSource.getMatch(MATCH_ID)).thenReturn(TELEMETRY);

        DeadlockApiUnavailableException unavailable = new DeadlockApiUnavailableException("busy", null, null);
        when(deadlockApiClient.getBenchmarks(HERO_ID, 7)).thenThrow(unavailable);

        assertThatThrownBy(() -> service.analyze(CALLER_ID, ACCOUNT_ID, MatchFilter.LATEST)).isSameAs(unavailable);
        verify(repository, never()).save(any());
    }

    @Test
    void storedResultJsonRoundTripsThroughAnalysisResult() throws Exception {
        ObjectMapper mapper = new JsonMapper();
        AnalysisResult original = new AnalysisResult(
                "Strong laning phase and map presence",
                "High death count in mid-game team fights",
                "Play around team cooldowns and vision",
                List.of("12 kills (p50: 8.5)", "35,000 net worth @ 20 min (p50: 28,000)")
        );

        String json = mapper.writeValueAsString(original);
        assertThat(json).contains("\"biggest_leak\":");
        assertThat(json).contains("\"next_game_action\":");
        assertThat(json).contains("\"key_numbers\":");

        AnalysisResult deserialized = mapper.readValue(json, AnalysisResult.class);
        assertThat(deserialized).isEqualTo(original);
    }

    @Test
    void heroAndResultFilterPicksRightMatch() {
        MatchHistoryEntry loss = new MatchHistoryEntry(
                900000002L, 1, HERO_ID, 1700000100L, 1800, 4, 1, 0, 1, 2, 5, 3, 20000L, 100, 5, 73);
        when(deadlockApiClient.getMatchHistory(ACCOUNT_ID)).thenReturn(List.of(loss, MATCH));
        when(deadlockApiClient.getPlayerRank(ACCOUNT_ID)).thenReturn(RANK);
        when(deadlockApiClient.getHeroes()).thenReturn(HEROES);
        when(deadlockApiClient.getRanks()).thenReturn(RANKS);
        when(repository.findByAccountIdAndMatchId(ACCOUNT_ID, MATCH_ID)).thenReturn(Optional.empty());
        when(matchMetadataSource.getMatch(MATCH_ID)).thenReturn(TELEMETRY);
        when(deadlockApiClient.getBenchmarks(HERO_ID, 7)).thenReturn(BENCHMARKS);
        when(deadlockApiClient.getItemStats(HERO_ID, 7)).thenReturn(ITEM_STATS);
        when(deadlockApiClient.getItems()).thenReturn(ITEMS);
        when(analysisClient.analyze(any())).thenReturn(new AnalysisOutcome(AI_RESULT, "claude-haiku-5-5", 1, 1000, 250));

        AnalysisReport report = service.analyze(CALLER_ID, ACCOUNT_ID, new MatchFilter(null, HERO_ID, true));

        assertThat(report.result()).isEqualTo("win");
        ArgumentCaptor<MatchAnalysis> captor = ArgumentCaptor.forClass(MatchAnalysis.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getMatchId()).isEqualTo(MATCH_ID);
    }

    @Test
    void matchIdFilterAnalyzesExactlyThatMatch() {
        MatchHistoryEntry other = new MatchHistoryEntry(
                900000002L, 1, 2, 1700000100L, 1800, 4, 0, 0, 1, 5, 3, 7, 30000L, 150, 10, 73);
        when(deadlockApiClient.getMatchHistory(ACCOUNT_ID)).thenReturn(List.of(other, MATCH));
        when(deadlockApiClient.getPlayerRank(ACCOUNT_ID)).thenReturn(RANK);
        when(deadlockApiClient.getHeroes()).thenReturn(HEROES);
        when(deadlockApiClient.getRanks()).thenReturn(RANKS);
        when(repository.findByAccountIdAndMatchId(ACCOUNT_ID, MATCH_ID)).thenReturn(Optional.empty());
        when(matchMetadataSource.getMatch(MATCH_ID)).thenReturn(TELEMETRY);
        when(deadlockApiClient.getBenchmarks(HERO_ID, 7)).thenReturn(BENCHMARKS);
        when(deadlockApiClient.getItemStats(HERO_ID, 7)).thenReturn(ITEM_STATS);
        when(deadlockApiClient.getItems()).thenReturn(ITEMS);
        when(analysisClient.analyze(any())).thenReturn(new AnalysisOutcome(AI_RESULT, "claude-haiku-5-5", 1, 1000, 250));

        AnalysisReport report = service.analyze(CALLER_ID, ACCOUNT_ID, new MatchFilter(MATCH_ID, null, null));

        assertThat(report.hero()).isEqualTo("Infernus");
        ArgumentCaptor<MatchAnalysis> captor = ArgumentCaptor.forClass(MatchAnalysis.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getMatchId()).isEqualTo(MATCH_ID);
    }

    @Test
    void nothingPassesFilterThrowsMatchNotFoundException() {
        when(deadlockApiClient.getMatchHistory(ACCOUNT_ID)).thenReturn(List.of(MATCH));

        assertThatThrownBy(() -> service.analyze(CALLER_ID, ACCOUNT_ID, new MatchFilter(null, 999, null)))
                .isInstanceOf(MatchNotFoundException.class);
        verify(analysisClient, never()).analyze(any());
        verify(repository, never()).save(any());
    }

    @Test
    void sameMatchSelectedThreeWaysMakesExactlyOneAiCallAndOneStoredRow() {
        when(deadlockApiClient.getMatchHistory(ACCOUNT_ID)).thenReturn(List.of(MATCH));
        when(deadlockApiClient.getPlayerRank(ACCOUNT_ID)).thenReturn(RANK);
        when(deadlockApiClient.getHeroes()).thenReturn(HEROES);
        when(deadlockApiClient.getRanks()).thenReturn(RANKS);
        when(matchMetadataSource.getMatch(MATCH_ID)).thenReturn(TELEMETRY);
        when(deadlockApiClient.getBenchmarks(HERO_ID, 7)).thenReturn(BENCHMARKS);
        when(deadlockApiClient.getItemStats(HERO_ID, 7)).thenReturn(ITEM_STATS);
        when(deadlockApiClient.getItems()).thenReturn(ITEMS);

        AnalysisOutcome outcome = new AnalysisOutcome(AI_RESULT, "claude-haiku-5-5", 1, 1000, 250);
        when(analysisClient.analyze(any())).thenReturn(outcome);

        // First call: default (LATEST) -> triggers AI and saves
        when(repository.findByAccountIdAndMatchId(ACCOUNT_ID, MATCH_ID)).thenReturn(Optional.empty());
        AnalysisReport report1 = service.analyze(CALLER_ID, ACCOUNT_ID, MatchFilter.LATEST);
        assertThat(report1.analysis()).isEqualTo(AI_RESULT);

        // Simulate repository now holding the stored row
        MatchAnalysis storedEntity = new MatchAnalysis(
                ACCOUNT_ID, MATCH_ID, HERO_ID,
                "{\"strength\":\"Great farming\",\"biggest_leak\":\"Too many deaths\",\"next_game_action\":\"Ward more\",\"key_numbers\":[\"10 kills\",\"20k souls\"]}",
                "claude-haiku-5-5", 1000, 250, Instant.now());
        when(repository.findByAccountIdAndMatchId(ACCOUNT_ID, MATCH_ID)).thenReturn(Optional.of(storedEntity));

        // Second call: by matchId
        AnalysisReport report2 = service.analyze(CALLER_ID, ACCOUNT_ID, new MatchFilter(MATCH_ID, null, null));
        assertThat(report2.analysis()).isEqualTo(AI_RESULT);

        // Third call: by filter (hero and win)
        AnalysisReport report3 = service.analyze(CALLER_ID, ACCOUNT_ID, new MatchFilter(null, HERO_ID, true));
        assertThat(report3.analysis()).isEqualTo(AI_RESULT);

        // Verification: exactly one AI call, exactly one save
        verify(analysisClient, times(1)).analyze(any());
        verify(repository, times(1)).save(any());
    }

    @Test
    void estimatedCostUsdIsUnknownWithZeroPricingAndExactValueWithConfiguredPricing(CapturedOutput output) {
        GameAssets gameAssets = mock(GameAssets.class);
        MatchSearchService searchService = new MatchSearchService(deadlockApiClient, gameAssets);

        // Zero pricing
        AnalyzeService zeroService = new AnalyzeService(
                deadlockApiClient, matchMetadataSource, metricsService, analysisClient, repository,
                searchService, new AnthropicPricingProperties(0, 0), visualsService, coachContextService, spendGuard);
        assertThat(zeroService.estimatedCostUsd(1000, 250)).isEqualTo("unknown");

        // Configured pricing: e.g. input $0.80 / 1M, output $4.00 / 1M
        // 1000 / 1e6 * 0.80 + 250 / 1e6 * 4.00 = 0.0008 + 0.0010 = 0.001800
        AnalyzeService pricedService = new AnalyzeService(
                deadlockApiClient, matchMetadataSource, metricsService, analysisClient, repository,
                searchService, new AnthropicPricingProperties(0.80, 4.00), visualsService, coachContextService, spendGuard);
        assertThat(pricedService.estimatedCostUsd(1000, 250)).isEqualTo("0.001800");

        // Trigger AI analysis with pricedService and check log output
        when(deadlockApiClient.getMatchHistory(ACCOUNT_ID)).thenReturn(List.of(MATCH));
        when(deadlockApiClient.getPlayerRank(ACCOUNT_ID)).thenReturn(RANK);
        when(deadlockApiClient.getHeroes()).thenReturn(HEROES);
        when(deadlockApiClient.getRanks()).thenReturn(RANKS);
        when(repository.findByAccountIdAndMatchId(ACCOUNT_ID, MATCH_ID)).thenReturn(Optional.empty());
        when(matchMetadataSource.getMatch(MATCH_ID)).thenReturn(TELEMETRY);
        when(deadlockApiClient.getBenchmarks(HERO_ID, 7)).thenReturn(BENCHMARKS);
        when(deadlockApiClient.getItemStats(HERO_ID, 7)).thenReturn(ITEM_STATS);
        when(deadlockApiClient.getItems()).thenReturn(ITEMS);
        when(analysisClient.analyze(any())).thenReturn(new AnalysisOutcome(AI_RESULT, "claude-haiku-5-5", 1, 1000, 250));

        pricedService.analyze(CALLER_ID, ACCOUNT_ID, MatchFilter.LATEST);
        assertThat(output.getOut()).contains("estimatedCostUsd=0.001800");
    }

    @Test
    void freshPathPassesCoachToAnalysisClientAndCarriesContextInCoachRequest() {
        stubFresh();
        Map<String, Object> coachInsights = Map.of("phase_stats", Map.of("z_score", 1.5));
        when(coachContextService.build(any())).thenReturn(coachInsights);

        AnalysisReport report = service.analyze(CALLER_ID, ACCOUNT_ID, MatchFilter.LATEST);

        assertThat(report.analysis()).isEqualTo(AI_RESULT);

        ArgumentCaptor<CoachRequest> requestCaptor = ArgumentCaptor.forClass(CoachRequest.class);
        verify(coachContextService).build(requestCaptor.capture());
        CoachRequest request = requestCaptor.getValue();
        assertThat(request.accountId()).isEqualTo(ACCOUNT_ID);
        assertThat(request.match()).isEqualTo(MATCH);
        assertThat(request.history()).containsExactly(MATCH);
        assertThat(request.telemetry()).isEqualTo(TELEMETRY);
        assertThat(request.player()).isEqualTo(TELEMETRY.player(ACCOUNT_ID).orElseThrow());
        assertThat(request.rankTier()).isEqualTo(7);
        assertThat(request.heroes()).isEqualTo(HEROES);
        assertThat(request.items()).isEqualTo(ITEMS);

        ArgumentCaptor<AnalysisInput> inputCaptor = ArgumentCaptor.forClass(AnalysisInput.class);
        verify(analysisClient).analyze(inputCaptor.capture());
        assertThat(inputCaptor.getValue().coach()).isEqualTo(coachInsights);
    }

    @Test
    void overSizedInputJsonStoresNullAndLogsWarning(CapturedOutput output) {
        stubFresh();
        String largePadding = "a".repeat(AnalyzeService.MAX_INPUT_JSON_CHARS + 50);
        when(coachContextService.build(any())).thenReturn(Map.of("padding", largePadding));

        AnalysisReport report = service.analyze(CALLER_ID, ACCOUNT_ID, MatchFilter.LATEST);

        assertThat(report.degraded()).isFalse();
        ArgumentCaptor<MatchAnalysis> savedCaptor = ArgumentCaptor.forClass(MatchAnalysis.class);
        verify(repository).save(savedCaptor.capture());
        MatchAnalysis saved = savedCaptor.getValue();
        assertThat(saved.getInputJson()).isNull();
        assertThat(output.getOut()).contains("Analysis input JSON exceeds max length of 20000");
    }
}
