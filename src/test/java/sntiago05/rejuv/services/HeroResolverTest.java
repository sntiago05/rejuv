package sntiago05.rejuv.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sntiago05.rejuv.clients.DeadlockApiClient;
import sntiago05.rejuv.clients.DeadlockApiUnavailableException;
import sntiago05.rejuv.clients.dto.HeroAsset;

class HeroResolverTest {

    private DeadlockApiClient deadlockApiClient;
    private HeroResolver resolver;

    @BeforeEach
    void setUp() {
        deadlockApiClient = mock(DeadlockApiClient.class);
        resolver = new HeroResolver(deadlockApiClient);
    }

    @Test
    void exactNameIgnoringCase() {
        when(deadlockApiClient.getHeroes()).thenReturn(List.of(
                new HeroAsset(1, "hero_inferno", "Infernus"),
                new HeroAsset(2, "hero_gigawatt", "Seven")
        ));

        Optional<HeroAsset> hero = resolver.resolve("infernus");

        assertThat(hero).contains(new HeroAsset(1, "hero_inferno", "Infernus"));
    }

    @Test
    void trimsAndCollapsesWhitespace() {
        when(deadlockApiClient.getHeroes()).thenReturn(List.of(
                new HeroAsset(12, "hero_lady_geist", "Lady Geist"),
                new HeroAsset(1, "hero_inferno", "Infernus")
        ));

        Optional<HeroAsset> hero = resolver.resolve("   lady    geist   ");

        assertThat(hero).contains(new HeroAsset(12, "hero_lady_geist", "Lady Geist"));
    }

    @Test
    void uniquePrefixResolves() {
        when(deadlockApiClient.getHeroes()).thenReturn(List.of(
                new HeroAsset(1, "hero_inferno", "Infernus"),
                new HeroAsset(2, "hero_gigawatt", "Seven")
        ));

        Optional<HeroAsset> hero = resolver.resolve("inf");

        assertThat(hero).contains(new HeroAsset(1, "hero_inferno", "Infernus"));
    }

    @Test
    void ambiguousPrefixIsEmpty() {
        when(deadlockApiClient.getHeroes()).thenReturn(List.of(
                new HeroAsset(1, "hero_inferno", "Infernus"),
                new HeroAsset(4, "hero_ivy", "Ivy")
        ));

        Optional<HeroAsset> hero = resolver.resolve("i");

        assertThat(hero).isEmpty();
    }

    @Test
    void exactNameBeatsLongerPrefixMatch() {
        when(deadlockApiClient.getHeroes()).thenReturn(List.of(
                new HeroAsset(5, "hero_warden", "Warden"),
                new HeroAsset(6, "hero_warden_plus", "Warden Plus")
        ));

        Optional<HeroAsset> hero = resolver.resolve("warden");

        assertThat(hero).contains(new HeroAsset(5, "hero_warden", "Warden"));
    }

    @Test
    void unknownIsEmpty() {
        when(deadlockApiClient.getHeroes()).thenReturn(List.of(
                new HeroAsset(1, "hero_inferno", "Infernus")
        ));

        Optional<HeroAsset> hero = resolver.resolve("nonexistent");

        assertThat(hero).isEmpty();
    }

    @Test
    void blankOrNullIsEmpty() {
        assertThat(resolver.resolve(null)).isEmpty();
        assertThat(resolver.resolve("")).isEmpty();
        assertThat(resolver.resolve("   ")).isEmpty();
    }

    @Test
    void heroesWithoutNameAreIgnored() {
        when(deadlockApiClient.getHeroes()).thenReturn(List.of(
                new HeroAsset(99, "hero_unknown", null),
                new HeroAsset(98, "hero_blank", "   "),
                new HeroAsset(1, "hero_inferno", "Infernus")
        ));

        assertThat(resolver.resolve("hero_unknown")).isEmpty();
        assertThat(resolver.resolve("Infernus")).contains(new HeroAsset(1, "hero_inferno", "Infernus"));
    }

    @Test
    void propagatesUnavailableException() {
        when(deadlockApiClient.getHeroes()).thenThrow(new DeadlockApiUnavailableException("busy", null, null));

        assertThatThrownBy(() -> resolver.resolve("Infernus"))
                .isInstanceOf(DeadlockApiUnavailableException.class);
    }
}
