package sntiago05.rejuv.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.InOrder;
import sntiago05.rejuv.clients.DeadlockApiClient;
import sntiago05.rejuv.clients.DeadlockApiUnavailableException;
import sntiago05.rejuv.clients.dto.HeroAsset;
import sntiago05.rejuv.clients.dto.HeroStat;
import sntiago05.rejuv.domain.HeroTier;
import sntiago05.rejuv.domain.MetaHero;
import sntiago05.rejuv.domain.MetaTierList;

class MetaServiceTest {

    private static final Instant FIXED_INSTANT = Instant.parse("2026-10-17T15:30:00Z");
    private static final long EXPECTED_TIMESTAMP = Instant.parse("2026-09-17T00:00:00Z").getEpochSecond();

    private DeadlockApiClient deadlockApiClient;
    private Clock clock;
    private MetaService metaService;

    @BeforeEach
    void setUp() {
        deadlockApiClient = mock(DeadlockApiClient.class);
        clock = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);
        metaService = new MetaService(deadlockApiClient, new GameAssets(deadlockApiClient), clock);
    }

    @Test
    void timestampIsStartOfUtcDayMinus30Days() {
        when(deadlockApiClient.getHeroStats(eq(1), eq(EXPECTED_TIMESTAMP))).thenReturn(List.of());
        when(deadlockApiClient.getHeroes()).thenReturn(List.of());

        metaService.getTierList(1);

        verify(deadlockApiClient).getHeroStats(1, EXPECTED_TIMESTAMP);
    }

    @Test
    void callsStatsThenHeroesSequentially() {
        when(deadlockApiClient.getHeroStats(eq(7), eq(EXPECTED_TIMESTAMP))).thenReturn(List.of());
        when(deadlockApiClient.getHeroes()).thenReturn(List.of());

        metaService.getTierList(7);

        InOrder inOrder = inOrder(deadlockApiClient);
        inOrder.verify(deadlockApiClient).getHeroStats(7, EXPECTED_TIMESTAMP);
        inOrder.verify(deadlockApiClient).getHeroes();
    }

    @Test
    void heroWithoutNameInAssetsHasNullName() {
        when(deadlockApiClient.getHeroStats(eq(null), eq(EXPECTED_TIMESTAMP)))
                .thenReturn(List.of(new HeroStat(4, 200, 100, 100)));
        when(deadlockApiClient.getHeroes()).thenReturn(List.of(new HeroAsset(4, "hero_nameless", null)));

        MetaTierList result = metaService.getTierList(null);

        assertThat(result.heroes()).singleElement()
                .satisfies(hero -> assertThat(hero.heroName()).isNull());
    }

    @Test
    void allRanksPassesNullTier() {
        when(deadlockApiClient.getHeroStats(eq(null), eq(EXPECTED_TIMESTAMP))).thenReturn(List.of());
        when(deadlockApiClient.getHeroes()).thenReturn(List.of());

        MetaTierList result = metaService.getTierList(null);

        verify(deadlockApiClient).getHeroStats(null, EXPECTED_TIMESTAMP);
        assertThat(result.rankTier()).isNull();
    }

    @Test
    void invalidTierIsRejectedWithoutCalls() {
        assertThatThrownBy(() -> metaService.getTierList(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> metaService.getTierList(12))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(deadlockApiClient);
    }

    @ParameterizedTest
    @CsvSource({
            "53.0, S",
            "52.99, A",
            "51.0, A",
            "49.0, B",
            "47.0, C",
            "46.99, D"
    })
    void tierThresholdBoundaries(double winRatePercent, HeroTier expectedTier) {
        assertThat(HeroTier.of(winRatePercent)).isEqualTo(expectedTier);

        long matches = 10000;
        long wins = Math.round(winRatePercent * matches / 100.0);
        when(deadlockApiClient.getHeroStats(eq(1), anyLong())).thenReturn(List.of(
                new HeroStat(1, matches, wins, matches - wins)
        ));
        when(deadlockApiClient.getHeroes()).thenReturn(List.of(
                new HeroAsset(1, "hero_inferno", "Infernus")
        ));

        MetaTierList tierList = metaService.getTierList(1);
        assertThat(tierList.heroes()).hasSize(1);
        assertThat(tierList.heroes().get(0).tier()).isEqualTo(expectedTier);
    }

    @Test
    void heroesBelowMinMatchesAreDropped() {
        when(deadlockApiClient.getHeroStats(eq(1), anyLong())).thenReturn(List.of(
                new HeroStat(1, 99, 60, 39),
                new HeroStat(2, 100, 55, 45),
                new HeroStat(3, 0, 0, 0)
        ));
        when(deadlockApiClient.getHeroes()).thenReturn(List.of(
                new HeroAsset(1, "hero_inferno", "Infernus"),
                new HeroAsset(2, "hero_gigawatt", "Seven"),
                new HeroAsset(3, "hero_hornet", "Vindicta")
        ));

        MetaTierList tierList = metaService.getTierList(1);

        assertThat(tierList.heroes()).hasSize(1);
        assertThat(tierList.heroes().get(0).heroId()).isEqualTo(2);
        assertThat(tierList.heroes().get(0).matches()).isEqualTo(100);
    }

    @Test
    void sortedByWinRateThenHeroId() {
        when(deadlockApiClient.getHeroStats(eq(1), anyLong())).thenReturn(List.of(
                new HeroStat(2, 1000, 500, 500), // 50.0%
                new HeroStat(3, 1000, 550, 450), // 55.0%
                new HeroStat(1, 1000, 500, 500)  // 50.0%
        ));
        when(deadlockApiClient.getHeroes()).thenReturn(List.of(
                new HeroAsset(1, "hero_inferno", "Infernus"),
                new HeroAsset(2, "hero_gigawatt", "Seven"),
                new HeroAsset(3, "hero_hornet", "Vindicta")
        ));

        MetaTierList tierList = metaService.getTierList(1);

        assertThat(tierList.heroes()).extracting(MetaHero::heroId).containsExactly(3, 1, 2);
    }

    @Test
    void unknownHeroHasNullName() {
        when(deadlockApiClient.getHeroStats(eq(1), anyLong())).thenReturn(List.of(
                new HeroStat(999, 100, 50, 50)
        ));
        when(deadlockApiClient.getHeroes()).thenReturn(List.of());

        MetaTierList tierList = metaService.getTierList(1);

        assertThat(tierList.heroes()).hasSize(1);
        assertThat(tierList.heroes().get(0).heroName()).isNull();
    }

    @Test
    void duplicateHeroIdKeepsFirst() {
        when(deadlockApiClient.getHeroStats(eq(1), anyLong())).thenReturn(List.of(
                new HeroStat(1, 100, 60, 40),
                new HeroStat(1, 200, 100, 100)
        ));
        when(deadlockApiClient.getHeroes()).thenReturn(List.of(
                new HeroAsset(1, "hero_inferno", "Infernus")
        ));

        MetaTierList tierList = metaService.getTierList(1);

        assertThat(tierList.heroes()).hasSize(1);
        assertThat(tierList.heroes().get(0).matches()).isEqualTo(100);
        assertThat(tierList.heroes().get(0).winRatePercent()).isEqualTo(60.0);
    }

    @Test
    void allBelowMinimumGivesEmptyList() {
        when(deadlockApiClient.getHeroStats(eq(1), anyLong())).thenReturn(List.of(
                new HeroStat(1, 50, 25, 25),
                new HeroStat(2, 99, 40, 59)
        ));
        when(deadlockApiClient.getHeroes()).thenReturn(List.of());

        MetaTierList tierList = metaService.getTierList(1);

        assertThat(tierList.heroes()).isEmpty();
    }

    @Test
    void propagatesUnavailableException() {
        when(deadlockApiClient.getHeroStats(any(), anyLong()))
                .thenThrow(new DeadlockApiUnavailableException("busy", Duration.ofSeconds(5), null));

        assertThatThrownBy(() -> metaService.getTierList(1))
                .isInstanceOf(DeadlockApiUnavailableException.class);
    }

    @Test
    void metaIgnoresTotals() {
        List<HeroStat> rowsWithoutTotals = List.of(
                new HeroStat(1, 1000, 550, 450),
                new HeroStat(2, 1000, 520, 480)
        );
        List<HeroStat> rowsWithTotals = List.of(
                new HeroStat(1, 1000, 550, 450, 10000, 5000, 8000),
                new HeroStat(2, 1000, 520, 480, 2000, 10000, 3000)
        );
        List<HeroAsset> heroes = List.of(
                new HeroAsset(1, "hero_inferno", "Infernus"),
                new HeroAsset(2, "hero_gigawatt", "Seven")
        );
        when(deadlockApiClient.getHeroes()).thenReturn(heroes);

        when(deadlockApiClient.getHeroStats(eq(1), anyLong())).thenReturn(rowsWithoutTotals);
        MetaTierList listWithoutTotals = metaService.getTierList(1);

        when(deadlockApiClient.getHeroStats(eq(1), anyLong())).thenReturn(rowsWithTotals);
        MetaTierList listWithTotals = metaService.getTierList(1);

        assertThat(listWithTotals.heroes()).isEqualTo(listWithoutTotals.heroes());
    }
}
