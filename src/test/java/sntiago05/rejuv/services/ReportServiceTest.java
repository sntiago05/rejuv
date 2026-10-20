package sntiago05.rejuv.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sntiago05.rejuv.clients.DeadlockApiClient;
import sntiago05.rejuv.clients.DeadlockApiUnavailableException;
import sntiago05.rejuv.clients.MatchMetadataNotFoundException;
import sntiago05.rejuv.clients.MatchMetadataSource;
import sntiago05.rejuv.clients.dto.PerformanceCurvePoint;
import sntiago05.rejuv.clients.dto.PlayerRank;
import sntiago05.rejuv.clients.dto.RankAsset;
import sntiago05.rejuv.domain.AnalysisResult;
import sntiago05.rejuv.domain.ItemPurchase;
import sntiago05.rejuv.domain.MatchAnalysis;
import sntiago05.rejuv.domain.MatchTelemetry;
import sntiago05.rejuv.domain.MatchVisuals;
import sntiago05.rejuv.domain.PlayerTelemetry;
import sntiago05.rejuv.domain.ReportView;
import sntiago05.rejuv.domain.StatSnapshot;
import sntiago05.rejuv.repositories.MatchAnalysisRepository;

class ReportServiceTest {

    private static final long ACCOUNT_ID = 1000001L;
    private static final long MATCH_ID = 900000001L;
    private static final int HERO_ID = 1;
    private static final String RESULT_JSON = "{\"strength\":\"Great farming\",\"biggest_leak\":\"Too many deaths\",\"next_game_action\":\"Ward more\",\"key_numbers\":[\"10 kills\"]}";
    private static final UUID PUBLIC_ID = UUID.fromString("12345678-1234-1234-1234-123456789abc");

    private MatchAnalysisRepository repository;
    private MatchMetadataSource matchMetadataSource;
    private MatchVisualsService visualsService;
    private GameAssets gameAssets;
    private ChartRenderer chartRenderer;
    private DeadlockApiClient deadlockApiClient;
    private SoulCurveSvg soulCurveSvg;
    private ReportChartsSvg reportChartsSvg;
    private ReportService reportService;

    @BeforeEach
    void setUp() {
        repository = mock(MatchAnalysisRepository.class);
        matchMetadataSource = mock(MatchMetadataSource.class);
        visualsService = mock(MatchVisualsService.class);
        gameAssets = mock(GameAssets.class);
        chartRenderer = mock(ChartRenderer.class);
        deadlockApiClient = mock(DeadlockApiClient.class);
        soulCurveSvg = mock(SoulCurveSvg.class);
        reportChartsSvg = mock(ReportChartsSvg.class);
        reportService = new ReportService(repository, matchMetadataSource, visualsService,
                gameAssets, chartRenderer, deadlockApiClient, soulCurveSvg, reportChartsSvg);
    }

    private MatchAnalysis createStoredEntity(String resultJson) {
        return new MatchAnalysis(ACCOUNT_ID, MATCH_ID, HERO_ID, resultJson,
                "claude-haiku-5-5", 1000, 250, Instant.now());
    }

    private static PerformanceCurvePoint curvePoint(int gameTime, double avg, double std) {
        return new PerformanceCurvePoint(gameTime, avg, std, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0);
    }

    @Test
    void findUnknownIdReturnsEmpty() {
        when(repository.findByPublicId(PUBLIC_ID)).thenReturn(Optional.empty());

        assertThat(reportService.find(PUBLIC_ID)).isEmpty();
    }

    @Test
    void findCorruptedJsonReturnsEmpty() {
        MatchAnalysis stored = createStoredEntity("{corrupted json");
        when(repository.findByPublicId(PUBLIC_ID)).thenReturn(Optional.of(stored));

        assertThat(reportService.find(PUBLIC_ID)).isEmpty();
    }

    @Test
    void findFullViewWithTelemetryRoundsDurationAndIncludesBuildAndChart() {
        MatchAnalysis stored = createStoredEntity(RESULT_JSON);
        when(repository.findByPublicId(PUBLIC_ID)).thenReturn(Optional.of(stored));
        when(gameAssets.heroNames()).thenReturn(Map.of(HERO_ID, "Infernus"));

        MatchTelemetry telemetry = new MatchTelemetry(MATCH_ID, 1825, List.of(new PlayerTelemetry(
                ACCOUNT_ID, 0, HERO_ID,
                List.of(new ItemPurchase(101L, 250, null)),
                List.of(new StatSnapshot(180, 1500, 2000, 0)),
                List.of())));
        when(matchMetadataSource.getMatch(MATCH_ID)).thenReturn(telemetry);

        MatchVisuals.PurchasedItem item = new MatchVisuals.PurchasedItem(101L, "Basic Magazine", 250);
        MatchVisuals.ChartData chartData = new MatchVisuals.ChartData(List.of(0, 180), List.of(0L, 500L), List.of(0L, 800L), List.of(0L, 700L));
        MatchVisuals visuals = new MatchVisuals(List.of(item), chartData);
        when(visualsService.build(ACCOUNT_ID, telemetry)).thenReturn(Optional.of(visuals));

        Optional<ReportView> result = reportService.find(PUBLIC_ID);

        assertThat(result).isPresent();
        ReportView view = result.get();
        assertThat(view.publicId()).isEqualTo(stored.getPublicId());
        assertThat(view.matchId()).isEqualTo(MATCH_ID);
        assertThat(view.hero()).isEqualTo("Infernus");
        assertThat(view.heroId()).isEqualTo(HERO_ID);
        assertThat(view.rank()).isNull();
        assertThat(view.result()).isNull();
        assertThat(view.durationMin()).isEqualTo(30.4);
        assertThat(view.analysis().strength()).isEqualTo("Great farming");
        assertThat(view.analysis().biggestLeak()).isEqualTo("Too many deaths");
        assertThat(view.analysis().nextGameAction()).isEqualTo("Ward more");
        assertThat(view.analysis().keyNumbers()).containsExactly("10 kills");
        assertThat(view.build()).containsExactly(item);
        assertThat(view.hasChart()).isTrue();
        assertThat(view.hasSoulCurve()).isFalse();
        assertThat(view.analyzedAt()).isEqualTo(stored.getCreatedAt());
    }

    @Test
    void findGetMatchThrowingUnavailableDegradesWithoutThrowing() {
        MatchAnalysis stored = createStoredEntity(RESULT_JSON);
        when(repository.findByPublicId(PUBLIC_ID)).thenReturn(Optional.of(stored));
        when(gameAssets.heroNames()).thenReturn(Map.of(HERO_ID, "Infernus"));
        when(matchMetadataSource.getMatch(MATCH_ID)).thenThrow(new DeadlockApiUnavailableException("503 unavailable", null, null));

        Optional<ReportView> result = reportService.find(PUBLIC_ID);

        assertThat(result).isPresent();
        ReportView view = result.get();
        assertThat(view.durationMin()).isNull();
        assertThat(view.build()).isEmpty();
        assertThat(view.hasChart()).isFalse();
        assertThat(view.hasSoulCurve()).isFalse();
        assertThat(view.hero()).isEqualTo("Infernus");
    }

    @Test
    void findGetMatchThrowingNotFoundDegradesWithoutThrowing() {
        MatchAnalysis stored = createStoredEntity(RESULT_JSON);
        when(repository.findByPublicId(PUBLIC_ID)).thenReturn(Optional.of(stored));
        when(gameAssets.heroNames()).thenReturn(Map.of(HERO_ID, "Infernus"));
        when(matchMetadataSource.getMatch(MATCH_ID)).thenThrow(new MatchMetadataNotFoundException("Match not found: " + MATCH_ID));

        Optional<ReportView> result = reportService.find(PUBLIC_ID);

        assertThat(result).isPresent();
        ReportView view = result.get();
        assertThat(view.durationMin()).isNull();
        assertThat(view.build()).isEmpty();
        assertThat(view.hasChart()).isFalse();
        assertThat(view.hasSoulCurve()).isFalse();
    }

    @Test
    void findHeroFallbackWhenUnknownInAssets() {
        MatchAnalysis stored = createStoredEntity(RESULT_JSON);
        when(repository.findByPublicId(PUBLIC_ID)).thenReturn(Optional.of(stored));
        when(gameAssets.heroNames()).thenReturn(Map.of());
        when(matchMetadataSource.getMatch(MATCH_ID)).thenThrow(new MatchMetadataNotFoundException("Match not found: " + MATCH_ID));

        Optional<ReportView> result = reportService.find(PUBLIC_ID);

        assertThat(result).isPresent();
        assertThat(result.get().hero()).isEqualTo("hero_1");
    }

    @Test
    void chartPngReturnsRenderedBytes() {
        MatchAnalysis stored = createStoredEntity(RESULT_JSON);
        when(repository.findByPublicId(PUBLIC_ID)).thenReturn(Optional.of(stored));

        MatchTelemetry telemetry = new MatchTelemetry(MATCH_ID, 1800, List.of());
        when(matchMetadataSource.getMatch(MATCH_ID)).thenReturn(telemetry);

        MatchVisuals.ChartData chartData = new MatchVisuals.ChartData(List.of(0, 180), List.of(0L, 500L), List.of(0L, 800L), List.of(0L, 700L));
        when(visualsService.build(ACCOUNT_ID, telemetry)).thenReturn(Optional.of(new MatchVisuals(List.of(), chartData)));

        byte[] png = {1, 2, 3, 4};
        when(chartRenderer.render(chartData)).thenReturn(Optional.of(png));

        Optional<byte[]> result = reportService.chartPng(PUBLIC_ID);

        assertThat(result).contains(png);
    }

    @Test
    void chartPngReturnsEmptyWhenNotFoundOrNoChartOrRenderFails() {
        when(repository.findByPublicId(PUBLIC_ID)).thenReturn(Optional.empty());
        assertThat(reportService.chartPng(PUBLIC_ID)).isEmpty();

        MatchAnalysis stored = createStoredEntity(RESULT_JSON);
        when(repository.findByPublicId(PUBLIC_ID)).thenReturn(Optional.of(stored));
        when(matchMetadataSource.getMatch(MATCH_ID)).thenThrow(new MatchMetadataNotFoundException("Match not found: " + MATCH_ID));
        assertThat(reportService.chartPng(PUBLIC_ID)).isEmpty();
    }

    @Test
    void soulCurveBuiltForThirtyMinuteMatchPointsAtSharedGridTimesOnly() {
        MatchAnalysis stored = createStoredEntity(RESULT_JSON);
        when(repository.findByPublicId(PUBLIC_ID)).thenReturn(Optional.of(stored));
        when(gameAssets.heroNames()).thenReturn(Map.of(HERO_ID, "Infernus"));

        // Match duration: 1800s (30 min). Player has snapshots at grid times + extra time past duration
        PlayerTelemetry player = new PlayerTelemetry(ACCOUNT_ID, 0, HERO_ID, List.of(),
                List.of(
                        new StatSnapshot(180, 1500L, 500L, 0L),
                        new StatSnapshot(360, 3200L, 1200L, 100L),
                        new StatSnapshot(540, 5100L, 2200L, 200L),
                        new StatSnapshot(720, 7500L, 3800L, 300L),
                        new StatSnapshot(900, 10200L, 5500L, 450L),
                        new StatSnapshot(1200, 14000L, 8000L, 700L),
                        new StatSnapshot(1500, 18500L, 12000L, 950L),
                        new StatSnapshot(1800, 23000L, 16500L, 1200L),
                        new StatSnapshot(1950, 26000L, 19500L, 1500L) // past 1800s duration
                ),
                List.of());
        MatchTelemetry telemetry = new MatchTelemetry(MATCH_ID, 1800, List.of(player), 75);
        when(matchMetadataSource.getMatch(MATCH_ID)).thenReturn(telemetry);

        // Curve has points at grid times, including 2100 (> duration)
        List<PerformanceCurvePoint> curve = List.of(
                curvePoint(180, 1400.0, 200.0),
                curvePoint(360, 3000.0, 400.0),
                curvePoint(540, 5000.0, 600.0),
                curvePoint(720, 7200.0, 800.0),
                curvePoint(900, 9800.0, 1000.0),
                curvePoint(1200, 13500.0, 1300.0),
                curvePoint(1500, 18000.0, 1600.0),
                curvePoint(1800, 22500.0, 2000.0),
                curvePoint(2100, 27000.0, 2400.0)
        );
        // tier = 75 / 10 = 7
        when(deadlockApiClient.getPerformanceCurve(HERO_ID, 7)).thenReturn(curve);
        when(deadlockApiClient.getRanks()).thenReturn(List.of(new RankAsset(7, "Archon")));

        List<Integer> expectedTimes = List.of(180, 360, 540, 720, 900, 1200, 1500, 1800);
        List<Long> expectedPlayerNw = List.of(1500L, 3200L, 5100L, 7500L, 10200L, 14000L, 18500L, 23000L);
        List<Double> expectedAvgNw = List.of(1400.0, 3000.0, 5000.0, 7200.0, 9800.0, 13500.0, 18000.0, 22500.0);
        List<Double> expectedStdNw = List.of(200.0, 400.0, 600.0, 800.0, 1000.0, 1300.0, 1600.0, 2000.0);

        when(soulCurveSvg.render(eq("Infernus"), eq("Archon"), eq(false),
                eq(expectedTimes), eq(expectedPlayerNw), eq(expectedAvgNw), eq(expectedStdNw)))
                .thenReturn(Optional.of("<svg>curve</svg>"));

        Optional<ReportView> viewOpt = reportService.find(PUBLIC_ID);
        assertThat(viewOpt).isPresent();
        assertThat(viewOpt.get().hasSoulCurve()).isTrue();

        Optional<String> svgOpt = reportService.soulCurveSvg(PUBLIC_ID);
        assertThat(svgOpt).contains("<svg>curve</svg>");
        verify(deadlockApiClient, never()).getPlayerRank(anyLong());
    }

    @Test
    void badgeNullUnrankedPlayerBadgeZeroNoCurve() {
        MatchAnalysis stored = createStoredEntity(RESULT_JSON);
        when(repository.findByPublicId(PUBLIC_ID)).thenReturn(Optional.of(stored));

        PlayerTelemetry player = new PlayerTelemetry(ACCOUNT_ID, 0, HERO_ID, List.of(),
                List.of(new StatSnapshot(180, 1500L, 500L, 0L)), List.of());
        MatchTelemetry telemetry = new MatchTelemetry(MATCH_ID, 1800, List.of(player), null);
        when(matchMetadataSource.getMatch(MATCH_ID)).thenReturn(telemetry);
        when(deadlockApiClient.getPlayerRank(ACCOUNT_ID)).thenReturn(new PlayerRank(0, 0, 0));

        Optional<ReportView> view = reportService.find(PUBLIC_ID);
        assertThat(view).isPresent();
        assertThat(view.get().hasSoulCurve()).isFalse();

        Optional<String> svg = reportService.soulCurveSvg(PUBLIC_ID);
        assertThat(svg).isEmpty();

        verify(deadlockApiClient, times(2)).getPlayerRank(ACCOUNT_ID);
        verify(deadlockApiClient, never()).getPerformanceCurve(any(Integer.class), any(Integer.class));
        verifyNoInteractions(soulCurveSvg);
    }

    @Test
    void badgeNullRankedPlayerBuildsCurveWithPlayerTierAndOwnRankTrue() {
        MatchAnalysis stored = createStoredEntity(RESULT_JSON);
        when(repository.findByPublicId(PUBLIC_ID)).thenReturn(Optional.of(stored));
        when(gameAssets.heroNames()).thenReturn(Map.of(HERO_ID, "Infernus"));

        PlayerTelemetry player = new PlayerTelemetry(ACCOUNT_ID, 0, HERO_ID, List.of(),
                List.of(
                        new StatSnapshot(180, 1500L, 500L, 0L),
                        new StatSnapshot(360, 3200L, 1200L, 100L),
                        new StatSnapshot(540, 5100L, 2200L, 200L)
                ),
                List.of());
        MatchTelemetry telemetry = new MatchTelemetry(MATCH_ID, 1800, List.of(player), null);
        when(matchMetadataSource.getMatch(MATCH_ID)).thenReturn(telemetry);

        List<PerformanceCurvePoint> curve = List.of(
                curvePoint(180, 1400.0, 200.0),
                curvePoint(360, 3000.0, 400.0),
                curvePoint(540, 5000.0, 600.0)
        );
        when(deadlockApiClient.getPlayerRank(ACCOUNT_ID)).thenReturn(new PlayerRank(42, 4, 2));
        when(deadlockApiClient.getPerformanceCurve(HERO_ID, 4)).thenReturn(curve);
        when(deadlockApiClient.getRanks()).thenReturn(List.of(new RankAsset(4, "Emissary")));

        List<Integer> expectedTimes = List.of(180, 360, 540);
        List<Long> expectedPlayerNw = List.of(1500L, 3200L, 5100L);
        List<Double> expectedAvgNw = List.of(1400.0, 3000.0, 5000.0);
        List<Double> expectedStdNw = List.of(200.0, 400.0, 600.0);

        when(soulCurveSvg.render(eq("Infernus"), eq("Emissary"), eq(true),
                eq(expectedTimes), eq(expectedPlayerNw), eq(expectedAvgNw), eq(expectedStdNw)))
                .thenReturn(Optional.of("<svg>own-rank-curve</svg>"));

        Optional<ReportView> viewOpt = reportService.find(PUBLIC_ID);
        assertThat(viewOpt).isPresent();
        assertThat(viewOpt.get().hasSoulCurve()).isTrue();

        Optional<String> svgOpt = reportService.soulCurveSvg(PUBLIC_ID);
        assertThat(svgOpt).contains("<svg>own-rank-curve</svg>");

        verify(deadlockApiClient, times(2)).getPlayerRank(ACCOUNT_ID);
        verify(deadlockApiClient, times(2)).getPerformanceCurve(HERO_ID, 4);
    }

    @Test
    void badgeNullGetPlayerRankThrowsReturnsEmptyAndNoException() {
        MatchAnalysis stored = createStoredEntity(RESULT_JSON);
        when(repository.findByPublicId(PUBLIC_ID)).thenReturn(Optional.of(stored));

        PlayerTelemetry player = new PlayerTelemetry(ACCOUNT_ID, 0, HERO_ID, List.of(),
                List.of(new StatSnapshot(180, 1500L, 500L, 0L)), List.of());
        MatchTelemetry telemetry = new MatchTelemetry(MATCH_ID, 1800, List.of(player), null);
        when(matchMetadataSource.getMatch(MATCH_ID)).thenReturn(telemetry);
        when(deadlockApiClient.getPlayerRank(ACCOUNT_ID))
                .thenThrow(new DeadlockApiUnavailableException("API unavailable", null, null));

        Optional<ReportView> view = reportService.find(PUBLIC_ID);
        assertThat(view).isPresent();
        assertThat(view.get().hasSoulCurve()).isFalse();

        Optional<String> svg = reportService.soulCurveSvg(PUBLIC_ID);
        assertThat(svg).isEmpty();

        verifyNoInteractions(soulCurveSvg);
    }

    @Test
    void badgeNullTierClampedForRank12() {
        MatchAnalysis stored = createStoredEntity(RESULT_JSON);
        when(repository.findByPublicId(PUBLIC_ID)).thenReturn(Optional.of(stored));

        PlayerTelemetry player = new PlayerTelemetry(ACCOUNT_ID, 0, HERO_ID, List.of(),
                List.of(new StatSnapshot(180, 1500L, 500L, 0L),
                        new StatSnapshot(360, 3000L, 1000L, 0L),
                        new StatSnapshot(540, 4500L, 1500L, 0L)), List.of());
        MatchTelemetry telemetry = new MatchTelemetry(MATCH_ID, 1800, List.of(player), null);
        when(matchMetadataSource.getMatch(MATCH_ID)).thenReturn(telemetry);
        when(deadlockApiClient.getPlayerRank(ACCOUNT_ID)).thenReturn(new PlayerRank(120, 12, 0));
        when(deadlockApiClient.getPerformanceCurve(HERO_ID, 11)).thenReturn(List.of());

        reportService.find(PUBLIC_ID);
        verify(deadlockApiClient).getPerformanceCurve(HERO_ID, 11);
    }

    @Test
    void soulCurveSkippedWhenFewerThanThreeSharedPoints() {
        MatchAnalysis stored = createStoredEntity(RESULT_JSON);
        when(repository.findByPublicId(PUBLIC_ID)).thenReturn(Optional.of(stored));

        // Only 2 points shared
        PlayerTelemetry player = new PlayerTelemetry(ACCOUNT_ID, 0, HERO_ID, List.of(),
                List.of(new StatSnapshot(180, 1500L, 500L, 0L),
                        new StatSnapshot(360, 3000L, 1000L, 0L)), List.of());
        MatchTelemetry telemetry = new MatchTelemetry(MATCH_ID, 1800, List.of(player), 50);
        when(matchMetadataSource.getMatch(MATCH_ID)).thenReturn(telemetry);

        List<PerformanceCurvePoint> curve = List.of(
                curvePoint(180, 1400.0, 200.0),
                curvePoint(360, 3000.0, 400.0),
                curvePoint(540, 5000.0, 600.0) // player lacks 540
        );
        when(deadlockApiClient.getPerformanceCurve(HERO_ID, 5)).thenReturn(curve);

        Optional<ReportView> view = reportService.find(PUBLIC_ID);
        assertThat(view).isPresent();
        assertThat(view.get().hasSoulCurve()).isFalse();

        Optional<String> svg = reportService.soulCurveSvg(PUBLIC_ID);
        assertThat(svg).isEmpty();
        verifyNoInteractions(soulCurveSvg);
    }

    @Test
    void soulCurveSkippedWhenCurveIsEmpty() {
        MatchAnalysis stored = createStoredEntity(RESULT_JSON);
        when(repository.findByPublicId(PUBLIC_ID)).thenReturn(Optional.of(stored));

        PlayerTelemetry player = new PlayerTelemetry(ACCOUNT_ID, 0, HERO_ID, List.of(),
                List.of(new StatSnapshot(180, 1500L, 500L, 0L),
                        new StatSnapshot(360, 3000L, 1000L, 0L),
                        new StatSnapshot(540, 4500L, 1500L, 0L)), List.of());
        MatchTelemetry telemetry = new MatchTelemetry(MATCH_ID, 1800, List.of(player), 50);
        when(matchMetadataSource.getMatch(MATCH_ID)).thenReturn(telemetry);

        when(deadlockApiClient.getPerformanceCurve(HERO_ID, 5)).thenReturn(List.of());

        Optional<ReportView> view = reportService.find(PUBLIC_ID);
        assertThat(view).isPresent();
        assertThat(view.get().hasSoulCurve()).isFalse();

        Optional<String> svg = reportService.soulCurveSvg(PUBLIC_ID);
        assertThat(svg).isEmpty();
        verifyNoInteractions(soulCurveSvg);
    }

    @Test
    void soulCurveSkippedWhenStdIsZeroAtAllPoints() {
        MatchAnalysis stored = createStoredEntity(RESULT_JSON);
        when(repository.findByPublicId(PUBLIC_ID)).thenReturn(Optional.of(stored));

        PlayerTelemetry player = new PlayerTelemetry(ACCOUNT_ID, 0, HERO_ID, List.of(),
                List.of(new StatSnapshot(180, 1500L, 500L, 0L),
                        new StatSnapshot(360, 3000L, 1000L, 0L),
                        new StatSnapshot(540, 4500L, 1500L, 0L)), List.of());
        MatchTelemetry telemetry = new MatchTelemetry(MATCH_ID, 1800, List.of(player), 50);
        when(matchMetadataSource.getMatch(MATCH_ID)).thenReturn(telemetry);

        // std is 0.0 at all points -> all points filtered out
        List<PerformanceCurvePoint> curve = List.of(
                curvePoint(180, 1400.0, 0.0),
                curvePoint(360, 3000.0, 0.0),
                curvePoint(540, 5000.0, 0.0)
        );
        when(deadlockApiClient.getPerformanceCurve(HERO_ID, 5)).thenReturn(curve);

        Optional<ReportView> view = reportService.find(PUBLIC_ID);
        assertThat(view).isPresent();
        assertThat(view.get().hasSoulCurve()).isFalse();

        Optional<String> svg = reportService.soulCurveSvg(PUBLIC_ID);
        assertThat(svg).isEmpty();
        verifyNoInteractions(soulCurveSvg);
    }

    @Test
    void soulCurveSkippedWhenClientThrowsUnavailable() {
        MatchAnalysis stored = createStoredEntity(RESULT_JSON);
        when(repository.findByPublicId(PUBLIC_ID)).thenReturn(Optional.of(stored));

        PlayerTelemetry player = new PlayerTelemetry(ACCOUNT_ID, 0, HERO_ID, List.of(),
                List.of(new StatSnapshot(180, 1500L, 500L, 0L),
                        new StatSnapshot(360, 3000L, 1000L, 0L),
                        new StatSnapshot(540, 4500L, 1500L, 0L)), List.of());
        MatchTelemetry telemetry = new MatchTelemetry(MATCH_ID, 1800, List.of(player), 50);
        when(matchMetadataSource.getMatch(MATCH_ID)).thenReturn(telemetry);

        when(deadlockApiClient.getPerformanceCurve(HERO_ID, 5))
                .thenThrow(new DeadlockApiUnavailableException("API unavailable", null, null));

        Optional<ReportView> view = reportService.find(PUBLIC_ID);
        assertThat(view).isPresent();
        assertThat(view.get().hasSoulCurve()).isFalse();

        Optional<String> svg = reportService.soulCurveSvg(PUBLIC_ID);
        assertThat(svg).isEmpty();
        verifyNoInteractions(soulCurveSvg);
    }

    @Test
    void tierClampingForLowAndHighBadges() {
        MatchAnalysis stored = createStoredEntity(RESULT_JSON);
        when(repository.findByPublicId(PUBLIC_ID)).thenReturn(Optional.of(stored));

        PlayerTelemetry player = new PlayerTelemetry(ACCOUNT_ID, 0, HERO_ID, List.of(),
                List.of(new StatSnapshot(180, 1500L, 500L, 0L),
                        new StatSnapshot(360, 3000L, 1000L, 0L),
                        new StatSnapshot(540, 4500L, 1500L, 0L)), List.of());

        // Low badge 5 -> 5/10 = 0 -> clamped to 1
        MatchTelemetry telemetryLow = new MatchTelemetry(MATCH_ID, 1800, List.of(player), 5);
        when(matchMetadataSource.getMatch(MATCH_ID)).thenReturn(telemetryLow);
        when(deadlockApiClient.getPerformanceCurve(HERO_ID, 1)).thenReturn(List.of());

        reportService.find(PUBLIC_ID);
        verify(deadlockApiClient).getPerformanceCurve(HERO_ID, 1);

        // High badge 125 -> 125/10 = 12 -> clamped to 11
        MatchTelemetry telemetryHigh = new MatchTelemetry(MATCH_ID, 1800, List.of(player), 125);
        when(matchMetadataSource.getMatch(MATCH_ID)).thenReturn(telemetryHigh);
        when(deadlockApiClient.getPerformanceCurve(HERO_ID, 11)).thenReturn(List.of());

        reportService.find(PUBLIC_ID);
        verify(deadlockApiClient).getPerformanceCurve(HERO_ID, 11);
    }

    @Test
    void onlyOneCurveCallPerComputation() {
        MatchAnalysis stored = createStoredEntity(RESULT_JSON);
        when(repository.findByPublicId(PUBLIC_ID)).thenReturn(Optional.of(stored));

        PlayerTelemetry player = new PlayerTelemetry(ACCOUNT_ID, 0, HERO_ID, List.of(),
                List.of(new StatSnapshot(180, 1500L, 500L, 0L),
                        new StatSnapshot(360, 3000L, 1000L, 0L),
                        new StatSnapshot(540, 4500L, 1500L, 0L)), List.of());
        MatchTelemetry telemetry = new MatchTelemetry(MATCH_ID, 1800, List.of(player), 60);
        when(matchMetadataSource.getMatch(MATCH_ID)).thenReturn(telemetry);
        when(deadlockApiClient.getPerformanceCurve(HERO_ID, 6)).thenReturn(List.of(
                curvePoint(180, 1400.0, 200.0),
                curvePoint(360, 3000.0, 400.0),
                curvePoint(540, 5000.0, 600.0)
        ));

        reportService.find(PUBLIC_ID);
        verify(deadlockApiClient, times(1)).getPerformanceCurve(HERO_ID, 6);

        reportService.soulCurveSvg(PUBLIC_ID);
        verify(deadlockApiClient, times(2)).getPerformanceCurve(HERO_ID, 6); // 1 in find + 1 in soulCurveSvg
    }

    @Test
    void findWithInputJsonPopulatesDetailedSectionsAndMetadata() {
        String inputJson = """
                {
                  "hero": "Infernus",
                  "rank": "Archon 3",
                  "result": "win",
                  "duration_min": 30.5,
                  "metrics": {
                    "kills": {"value": 10.0, "p25": 5.0, "p50": 8.0},
                    "deaths": {"value": 7.0, "p25": 4.0, "p50": 6.0}
                  },
                  "coach": {
                    "phases": {
                      "significance": "major",
                      "weakest_phase": "laning",
                      "strongest_phase": "late",
                      "phases": [{"name": "laning", "change": -1.2}]
                    },
                    "deaths": {
                      "significance": "notable",
                      "total": 7,
                      "by_phase": {"laning": 1, "mid": 3, "late": 3},
                      "seconds_dead": 120,
                      "percent_of_match_dead": 6.6,
                      "longest_respawn_s": 35,
                      "top_killer": {"hero": "Seven", "count": 3},
                      "repeat_killer": true,
                      "deaths_while_behind": 4,
                      "deaths_counted": 7,
                      "deaths_z": 0.8,
                      "ttk_median_s": 4.2
                    },
                    "custom_future_signal": "active"
                  }
                }
                """;
        MatchAnalysis stored = new MatchAnalysis(ACCOUNT_ID, MATCH_ID, HERO_ID, RESULT_JSON,
                "claude-haiku-5-5", 1000, 250, Instant.now(), inputJson);
        when(repository.findByPublicId(PUBLIC_ID)).thenReturn(Optional.of(stored));
        when(matchMetadataSource.getMatch(MATCH_ID)).thenThrow(new MatchMetadataNotFoundException("down"));

        Optional<ReportView> result = reportService.find(PUBLIC_ID);

        assertThat(result).isPresent();
        ReportView view = result.get();
        assertThat(view.hasDetailedSections()).isTrue();
        assertThat(view.hero()).isEqualTo("Infernus");
        assertThat(view.rank()).isEqualTo("Archon 3");
        assertThat(view.result()).isEqualTo("win");
        assertThat(view.durationMin()).isEqualTo(30.5);

        // Metrics table
        assertThat(view.metricsTable()).hasSize(2);
        ReportView.MetricRow killsRow = view.metricsTable().get(0);
        assertThat(killsRow.key()).isEqualTo("kills");
        assertThat(killsRow.yourValue()).isEqualTo("10");
        assertThat(killsRow.p25()).isEqualTo("5");
        assertThat(killsRow.p50()).isEqualTo("8");
        assertThat(killsRow.verdict()).isEqualTo("above p50");

        ReportView.MetricRow deathsRow = view.metricsTable().get(1);
        assertThat(deathsRow.key()).isEqualTo("deaths");
        assertThat(deathsRow.yourValue()).isEqualTo("7");
        assertThat(deathsRow.p25()).isEqualTo("4");
        assertThat(deathsRow.p50()).isEqualTo("6");
        assertThat(deathsRow.verdict()).isEqualTo("below p25");

        // Coach sections
        assertThat(view.coachSections()).hasSize(2);
        ReportView.CoachSection phases = view.coachSections().get(0);
        assertThat(phases.key()).isEqualTo("phases");
        assertThat(phases.significance()).isEqualTo("major");
        assertThat(phases.fields()).extracting(ReportView.CoachField::labelKey)
                .contains("report.coach.phases.weakest_phase", "report.coach.phases.strongest_phase", "report.coach.phases.phase_laning");

        ReportView.CoachSection deaths = view.coachSections().get(1);
        assertThat(deaths.key()).isEqualTo("deaths");
        assertThat(deaths.significance()).isEqualTo("notable");
        assertThat(deaths.fields()).extracting(ReportView.CoachField::labelKey)
                .contains("report.coach.deaths.total", "report.coach.deaths.by_phase", "report.coach.deaths.seconds_dead", "report.coach.deaths.top_killer");

        // Other signals
        assertThat(view.otherSignals()).hasSize(1);
        assertThat(view.otherSignals().get(0).key()).isEqualTo("custom_future_signal");
        assertThat(view.otherSignals().get(0).value()).isEqualTo("active");
    }

    @Test
    void findWithCorruptInputJsonLogsWarningAndRendersAsNullInputJson() {
        MatchAnalysis stored = new MatchAnalysis(ACCOUNT_ID, MATCH_ID, HERO_ID, RESULT_JSON,
                "claude-haiku-5-5", 1000, 250, Instant.now(), "{not valid json");
        when(repository.findByPublicId(PUBLIC_ID)).thenReturn(Optional.of(stored));
        when(gameAssets.heroNames()).thenReturn(Map.of(HERO_ID, "Infernus"));
        when(matchMetadataSource.getMatch(MATCH_ID)).thenThrow(new MatchMetadataNotFoundException("down"));

        Optional<ReportView> result = reportService.find(PUBLIC_ID);

        assertThat(result).isPresent();
        ReportView view = result.get();
        assertThat(view.hasDetailedSections()).isFalse();
        assertThat(view.rank()).isNull();
        assertThat(view.result()).isNull();
        assertThat(view.metricsTable()).isEmpty();
        assertThat(view.coachSections()).isEmpty();
        assertThat(view.otherSignals()).isEmpty();
    }

    @Test
    void computeVerdictHigherIsBetterCases() {
        // Higher is better (e.g. kills, p25 = 3.0, p50 = 5.0)
        assertThat(ReportService.computeVerdict("kills", 2.0, 3.0, 5.0)).isEqualTo("below p25");
        assertThat(ReportService.computeVerdict("kills", 3.0, 3.0, 5.0)).isEqualTo("between p25 and p50");
        assertThat(ReportService.computeVerdict("kills", 4.0, 3.0, 5.0)).isEqualTo("between p25 and p50");
        assertThat(ReportService.computeVerdict("kills", 5.0, 3.0, 5.0)).isEqualTo("between p25 and p50");
        assertThat(ReportService.computeVerdict("kills", 6.0, 3.0, 5.0)).isEqualTo("above p50");
    }

    @Test
    void computeVerdictLowerIsBetterFlippedCases() {
        // Lower is better (e.g. deaths, p25 = 3.0, p50 = 5.0: higher is worse)
        assertThat(ReportService.computeVerdict("deaths", 2.0, 3.0, 5.0)).isEqualTo("above p50");
        assertThat(ReportService.computeVerdict("deaths", 3.0, 3.0, 5.0)).isEqualTo("between p25 and p50");
        assertThat(ReportService.computeVerdict("deaths", 4.0, 3.0, 5.0)).isEqualTo("between p25 and p50");
        assertThat(ReportService.computeVerdict("deaths", 5.0, 3.0, 5.0)).isEqualTo("between p25 and p50");
        assertThat(ReportService.computeVerdict("deaths", 6.0, 3.0, 5.0)).isEqualTo("below p25");

        // Timings: first_permanent_buff_min
        assertThat(ReportService.computeVerdict("first_permanent_buff_min", 10.0, 12.0, 15.0)).isEqualTo("above p50");
        assertThat(ReportService.computeVerdict("first_permanent_buff_min", 20.0, 12.0, 15.0)).isEqualTo("below p25");

        // Missing benchmark returns "-"
        assertThat(ReportService.computeVerdict("deaths_per_10_min", 3.0, null, 5.0)).isEqualTo("-");
        assertThat(ReportService.computeVerdict("kills", 5.0, 3.0, null)).isEqualTo("-");
    }

    @Test
    void mapWidthClassRoundsToNearestTen() {
        assertThat(ReportService.mapWidthClass(0.0)).isEqualTo("w0");
        assertThat(ReportService.mapWidthClass(4.0)).isEqualTo("w0");
        assertThat(ReportService.mapWidthClass(5.0)).isEqualTo("w10");
        assertThat(ReportService.mapWidthClass(14.0)).isEqualTo("w10");
        assertThat(ReportService.mapWidthClass(15.0)).isEqualTo("w20");
        assertThat(ReportService.mapWidthClass(54.0)).isEqualTo("w50");
        assertThat(ReportService.mapWidthClass(55.0)).isEqualTo("w60");
        assertThat(ReportService.mapWidthClass(94.0)).isEqualTo("w90");
        assertThat(ReportService.mapWidthClass(95.0)).isEqualTo("w100");
        assertThat(ReportService.mapWidthClass(100.0)).isEqualTo("w100");
        assertThat(ReportService.mapWidthClass(-10.0)).isEqualTo("w0");
        assertThat(ReportService.mapWidthClass(150.0)).isEqualTo("w100");
    }

    @Test
    void chartSvgMethodsDelegateToReportChartsSvg() {
        String inputJson = """
                {
                  "coach": {
                    "phases": {
                      "phases": [{"name": "laning", "change": 1.2}]
                    },
                    "deaths": {
                      "total": 5,
                      "longest_respawn_s": 40,
                      "by_phase": {"laning": 1, "mid": 3, "late": 1}
                    }
                  },
                  "metrics": {
                    "kills": {"value": 10, "p25": 5, "p50": 8}
                  }
                }
                """;
        MatchAnalysis stored = new MatchAnalysis(ACCOUNT_ID, MATCH_ID, HERO_ID, RESULT_JSON,
                "claude-haiku-5-5", 1000, 250, Instant.now(), inputJson);
        when(repository.findByPublicId(PUBLIC_ID)).thenReturn(Optional.of(stored));

        when(reportChartsSvg.renderPhases(any())).thenReturn(Optional.of("<svg>phases</svg>"));
        when(reportChartsSvg.renderDeaths(1, 3, 1, 5, 40)).thenReturn(Optional.of("<svg>deaths</svg>"));
        when(reportChartsSvg.renderMetrics(any())).thenReturn(Optional.of("<svg>metrics</svg>"));

        assertThat(reportService.phasesSvg(PUBLIC_ID)).contains("<svg>phases</svg>");
        assertThat(reportService.deathsSvg(PUBLIC_ID)).contains("<svg>deaths</svg>");
        assertThat(reportService.metricsSvg(PUBLIC_ID)).contains("<svg>metrics</svg>");

        // Null publicId returns empty
        assertThat(reportService.phasesSvg(null)).isEmpty();
        assertThat(reportService.deathsSvg(null)).isEmpty();
        assertThat(reportService.metricsSvg(null)).isEmpty();
        assertThat(reportService.soulLeadSvg(null)).isEmpty();
        assertThat(reportService.buildSvg(null)).isEmpty();
    }

    @Test
    void parseTipsFromStoredResultJson() throws Exception {
        tools.jackson.databind.ObjectMapper mapper = new tools.jackson.databind.json.JsonMapper();

        // 1. Normal 3 tips
        String json3 = """
                {"tips": [
                    {"tip": "Tip 1", "evidence": "Ev 1", "drill": "Drill 1"},
                    {"tip": "Tip 2", "evidence": "Ev 2", "drill": "Drill 2"},
                    {"tip": "Tip 3", "evidence": "Ev 3", "drill": "Drill 3"}
                ]}
                """;
        List<ReportView.Tip> tips3 = reportService.parseTips(mapper.readTree(json3));
        assertThat(tips3).hasSize(3);
        assertThat(tips3.get(0).tip()).isEqualTo("Tip 1");
        assertThat(tips3.get(0).evidence()).isEqualTo("Ev 1");
        assertThat(tips3.get(0).drill()).isEqualTo("Drill 1");

        // 2. 5 tips capped to 3
        String json5 = """
                {"tips": [
                    {"tip": "T1"}, {"tip": "T2"}, {"tip": "T3"}, {"tip": "T4"}, {"tip": "T5"}
                ]}
                """;
        List<ReportView.Tip> tips5 = reportService.parseTips(mapper.readTree(json5));
        assertThat(tips5).hasSize(3);
        assertThat(tips5.stream().map(ReportView.Tip::tip)).containsExactly("T1", "T2", "T3");

        // 3. Blank tip dropped
        String jsonBlank = """
                {"tips": [
                    {"tip": "Valid 1"},
                    {"tip": ""},
                    {"tip": "   "},
                    {"evidence": "No tip here"},
                    {"tip": "Valid 2"}
                ]}
                """;
        List<ReportView.Tip> tipsBlank = reportService.parseTips(mapper.readTree(jsonBlank));
        assertThat(tipsBlank).hasSize(2);
        assertThat(tipsBlank.stream().map(ReportView.Tip::tip)).containsExactly("Valid 1", "Valid 2");

        // 4. Missing tips key
        String jsonMissing = "{\"strength\": \"great\"}";
        assertThat(reportService.parseTips(mapper.readTree(jsonMissing))).isEmpty();

        // 5. Malformed tips value (string instead of array)
        String jsonMalformed = "{\"tips\": \"not an array\"}";
        assertThat(reportService.parseTips(mapper.readTree(jsonMalformed))).isEmpty();
    }

    @Test
    void buildTimelineFlagAndSvgDelegationRules() {
        // Fewer than 3 items -> hasBuildTimeline is false
        MatchAnalysis stored = createStoredEntity(RESULT_JSON);
        when(repository.findByPublicId(PUBLIC_ID)).thenReturn(Optional.of(stored));
        when(gameAssets.heroNames()).thenReturn(Map.of(HERO_ID, "Infernus"));

        MatchTelemetry telemetry2Items = new MatchTelemetry(MATCH_ID, 1200, List.of(new PlayerTelemetry(
                ACCOUNT_ID, 0, HERO_ID,
                List.of(new ItemPurchase(101L, 100, null), new ItemPurchase(102L, 200, null)),
                List.of(), List.of())));
        when(matchMetadataSource.getMatch(MATCH_ID)).thenReturn(telemetry2Items);

        MatchVisuals visuals2 = new MatchVisuals(List.of(
                new MatchVisuals.PurchasedItem(101L, "Item 1", 100),
                new MatchVisuals.PurchasedItem(102L, "Item 2", 200)
        ), null);
        when(visualsService.build(ACCOUNT_ID, telemetry2Items)).thenReturn(Optional.of(visuals2));

        Optional<ReportView> view2 = reportService.find(PUBLIC_ID);
        assertThat(view2).isPresent();
        assertThat(view2.get().hasBuildTimeline()).isFalse();

        // 3 or more items with buy times -> hasBuildTimeline is true
        MatchTelemetry telemetry3Items = new MatchTelemetry(MATCH_ID, 1200, List.of(new PlayerTelemetry(
                ACCOUNT_ID, 0, HERO_ID,
                List.of(new ItemPurchase(101L, 100, null), new ItemPurchase(102L, 200, null), new ItemPurchase(103L, 300, null)),
                List.of(), List.of())));
        when(matchMetadataSource.getMatch(MATCH_ID)).thenReturn(telemetry3Items);

        List<MatchVisuals.PurchasedItem> items3 = List.of(
                new MatchVisuals.PurchasedItem(101L, "Item 1", 100),
                new MatchVisuals.PurchasedItem(102L, "Item 2", 200),
                new MatchVisuals.PurchasedItem(103L, "Item 3", 300)
        );
        MatchVisuals visuals3 = new MatchVisuals(items3, null);
        when(visualsService.build(ACCOUNT_ID, telemetry3Items)).thenReturn(Optional.of(visuals3));
        when(reportChartsSvg.computeBuildAlt(any())).thenReturn("3 items bought; first at minute 1, last at minute 5.");
        when(reportChartsSvg.renderBuildTimeline(items3)).thenReturn(Optional.of("<svg>build-timeline</svg>"));

        Optional<ReportView> view3 = reportService.find(PUBLIC_ID);
        assertThat(view3).isPresent();
        assertThat(view3.get().hasBuildTimeline()).isTrue();
        assertThat(view3.get().buildAlt()).contains("3 items bought");

        // buildSvg delegation
        Optional<String> svg = reportService.buildSvg(PUBLIC_ID);
        assertThat(svg).contains("<svg>build-timeline</svg>");
    }

    @Test
    void severityAndVerdictClassNamesExistInGeneratedStylesheet() throws Exception {
        String css = java.nio.file.Files.readString(java.nio.file.Path.of("src/main/resources/static/report.css"));

        // Severity mapping
        List<String> severities = List.of("major", "notable", "minor", "none");
        for (String s : severities) {
            String badgeClass = ReportService.severityBadgeClass(s);
            assertThat(badgeClass).isNotEmpty();
            assertThat(css).contains("." + badgeClass);

            String leakClass = ReportService.leakBorderClass(s);
            if (!leakClass.isEmpty()) {
                assertThat(css).contains("." + leakClass);
            }
        }

        // Verdict mapping
        List<String> verdicts = List.of("above p50", "below p25", "between p25 and p50", "-");
        for (String v : verdicts) {
            String vClass = ReportService.verdictClass(v);
            assertThat(vClass).isNotEmpty();
            assertThat(css).contains("." + vClass);
        }

        // Result mapping
        List<String> results = List.of("win", "loss");
        for (String r : results) {
            String rClass = ReportService.resultBadgeClass(r);
            assertThat(rClass).isNotEmpty();
            assertThat(css).contains("." + rClass);
        }
    }
}
