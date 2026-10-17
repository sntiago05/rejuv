package sntiago05.rejuv.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sntiago05.rejuv.clients.DeadlockApiClient;
import sntiago05.rejuv.clients.dto.HeroAsset;
import sntiago05.rejuv.clients.dto.ItemAsset;

class GameAssetsTest {

    private DeadlockApiClient client;
    private GameAssets assets;

    @BeforeEach
    void setUp() {
        client = mock(DeadlockApiClient.class);
        assets = new GameAssets(client);
    }

    @Test
    void heroNamesKeepTheFirstNonNullNameAndSkipHeroesWithoutName() {
        when(client.getHeroes()).thenReturn(List.of(
                new HeroAsset(1, "hero_inferno", "Infernus"),
                new HeroAsset(1, "hero_inferno_dup", "Duplicate"),
                new HeroAsset(2, "hero_nameless", null),
                new HeroAsset(2, "hero_named_later", "Named Later"),
                new HeroAsset(3, "hero_blank", " ")));

        assertThat(assets.heroNames())
                .containsEntry(1, "Infernus")
                .containsEntry(2, "Named Later")
                .containsEntry(3, " ")
                .hasSize(3);
        assertThat(assets.namedHeroes()).extracting(HeroAsset::className)
                .containsExactly("hero_inferno", "hero_inferno_dup", "hero_named_later");
    }

    @Test
    void itemsKeepTheFirstAssetAndTheFirstNonNullName() {
        ItemAsset unnamed = new ItemAsset(10, "upgrade_unnamed", null, "upgrade", 1, "weapon", 500);
        ItemAsset named = new ItemAsset(10, "upgrade_named", "Basic Magazine", "upgrade", 1, "weapon", 500);
        when(client.getItems()).thenReturn(List.of(unnamed, named));

        assertThat(assets.itemsById()).containsEntry(10L, unnamed).hasSize(1);
        assertThat(assets.itemNames()).containsEntry(10L, "Basic Magazine").hasSize(1);
    }

    @Test
    void indexIsBuiltOncePerCachedListAndRebuiltWhenTheListChanges() {
        List<HeroAsset> first = List.of(new HeroAsset(1, "hero_inferno", "Infernus"));
        List<HeroAsset> refreshed = List.of(new HeroAsset(1, "hero_inferno", "Infernus Renamed"));
        when(client.getHeroes()).thenReturn(first);

        var names = assets.heroNames();
        assertThat(assets.heroNames()).isSameAs(names);

        when(client.getHeroes()).thenReturn(refreshed);
        assertThat(assets.heroNames()).isNotSameAs(names).containsEntry(1, "Infernus Renamed");
    }
}
