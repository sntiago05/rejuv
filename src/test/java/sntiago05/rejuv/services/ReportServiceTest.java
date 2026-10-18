package sntiago05.rejuv.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
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
        reportService = new ReportService(repository, matchMetadataSource, visualsService,
                gameAssets, chartRenderer, deadlockApiClient, soulCurveSvg);
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

        when(soulCurveSvg.render(eq("Infernus"), eq("Archon"),
                eq(expectedTimes), eq(expectedPlayerNw), eq(expectedAvgNw), eq(expectedStdNw)))
                .thenReturn(Optional.of("<svg>curve</svg>"));

        Optional<ReportView> viewOpt = reportService.find(PUBLIC_ID);
        assertThat(viewOpt).isPresent();
        assertThat(viewOpt.get().hasSoulCurve()).isTrue();

        Optional<String> svgOpt = reportService.soulCurveSvg(PUBLIC_ID);
        assertThat(svgOpt).contains("<svg>curve</svg>");
    }

    @Test
    void soulCurveSkippedWhenAverageBadgeNull() {
        MatchAnalysis stored = createStoredEntity(RESULT_JSON);
        when(repository.findByPublicId(PUBLIC_ID)).thenReturn(Optional.of(stored));

        PlayerTelemetry player = new PlayerTelemetry(ACCOUNT_ID, 0, HERO_ID, List.of(),
                List.of(new StatSnapshot(180, 1500L, 500L, 0L)), List.of());
        MatchTelemetry telemetry = new MatchTelemetry(MATCH_ID, 1800, List.of(player), null);
        when(matchMetadataSource.getMatch(MATCH_ID)).thenReturn(telemetry);

        Optional<ReportView> view = reportService.find(PUBLIC_ID);
        assertThat(view).isPresent();
        assertThat(view.get().hasSoulCurve()).isFalse();

        Optional<String> svg = reportService.soulCurveSvg(PUBLIC_ID);
        assertThat(svg).isEmpty();

        verifyNoInteractions(deadlockApiClient);
        verifyNoInteractions(soulCurveSvg);
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
}
