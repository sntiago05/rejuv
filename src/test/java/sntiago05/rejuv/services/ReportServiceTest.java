package sntiago05.rejuv.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sntiago05.rejuv.clients.DeadlockApiUnavailableException;
import sntiago05.rejuv.clients.MatchMetadataNotFoundException;
import sntiago05.rejuv.clients.MatchMetadataSource;
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
    private ReportService reportService;

    @BeforeEach
    void setUp() {
        repository = mock(MatchAnalysisRepository.class);
        matchMetadataSource = mock(MatchMetadataSource.class);
        visualsService = mock(MatchVisualsService.class);
        gameAssets = mock(GameAssets.class);
        chartRenderer = mock(ChartRenderer.class);
        reportService = new ReportService(repository, matchMetadataSource, visualsService, gameAssets, chartRenderer);
    }

    private MatchAnalysis createStoredEntity(String resultJson) {
        return new MatchAnalysis(ACCOUNT_ID, MATCH_ID, HERO_ID, resultJson,
                "claude-haiku-5-5", 1000, 250, Instant.now());
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
}
