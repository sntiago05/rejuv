package sntiago05.rejuv.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sntiago05.rejuv.clients.AssetImageClient;
import sntiago05.rejuv.clients.DeadlockApiClient;
import sntiago05.rejuv.clients.DeadlockApiException;
import sntiago05.rejuv.clients.DeadlockApiUnavailableException;
import sntiago05.rejuv.clients.dto.HeroAsset;
import sntiago05.rejuv.clients.dto.HeroImages;
import sntiago05.rejuv.clients.dto.ItemAsset;

class EmojiSyncServiceTest {

    private DeadlockApiClient deadlockApiClient;
    private AssetImageClient assetImageClient;
    private FakeEmojiGateway emojiGateway;
    private IconCatalog iconCatalog;
    private EmojiSyncService syncService;

    @BeforeEach
    void setUp() {
        deadlockApiClient = mock(DeadlockApiClient.class);
        assetImageClient = mock(AssetImageClient.class);
        emojiGateway = new FakeEmojiGateway();
        iconCatalog = new IconCatalog();
        syncService = new EmojiSyncService(deadlockApiClient, assetImageClient, emojiGateway, iconCatalog);
    }

    @Test
    void existingEmojisArePublishedAndNotRecreated() {
        emojiGateway.existing.put("h_1", "<:h_1:1001>");
        when(deadlockApiClient.getHeroes()).thenReturn(List.of(
                new HeroAsset(1, "hero_inferno", "Infernus",
                        new HeroImages("https://assets-bucket.deadlock-api.com/inferno_sm.png", "card"))
        ));
        when(deadlockApiClient.getItems()).thenReturn(List.of());

        syncService.sync();

        assertThat(iconCatalog.hero(1)).contains("<:h_1:1001>");
        assertThat(emojiGateway.createdNames).isEmpty();
        verify(assetImageClient, never()).download(anyString());
    }

    @Test
    void missingEmojisAreCreatedAndAddedToCatalog() {
        emojiGateway.existing.put("h_1", "<:h_1:1001>");
        when(deadlockApiClient.getHeroes()).thenReturn(List.of(
                new HeroAsset(1, "hero_inferno", "Infernus",
                        new HeroImages("https://assets-bucket.deadlock-api.com/inferno_sm.png", "card")),
                new HeroAsset(2, "hero_seven", "Seven",
                        new HeroImages("https://assets-bucket.deadlock-api.com/seven_sm.png", "card"))
        ));
        when(deadlockApiClient.getItems()).thenReturn(List.of(
                new ItemAsset(100L, "upgrade_test", "Test Upgrade", "upgrade", 1, "weapon", 500,
                        "https://assets-bucket.deadlock-api.com/item100.png")
        ));
        byte[] dummyBytes = new byte[] {1, 2, 3};
        when(assetImageClient.download(anyString())).thenReturn(dummyBytes);

        syncService.sync();

        assertThat(emojiGateway.createdNames).containsExactly("h_2", "i_100");
        assertThat(iconCatalog.hero(1)).contains("<:h_1:1001>");
        assertThat(iconCatalog.hero(2)).isPresent();
        assertThat(iconCatalog.item(100L)).isPresent();
    }

    @Test
    void heroesWithoutIconAndNonUpgradeOrImagelessItemsAreIgnored() {
        when(deadlockApiClient.getHeroes()).thenReturn(List.of(
                new HeroAsset(3, "hero_vindicta", "Vindicta", null),
                new HeroAsset(4, "hero_wraith", "Wraith", new HeroImages(null, "card")),
                new HeroAsset(5, "hero_yamato", "Yamato", new HeroImages("   ", "card"))
        ));
        when(deadlockApiClient.getItems()).thenReturn(List.of(
                new ItemAsset(200L, "ability_golden_idol", "Soul Urn", "ability", null, null, null, "url"),
                new ItemAsset(201L, "upgrade_empty", "Empty", "upgrade", 1, "vitality", 500, null),
                new ItemAsset(202L, "upgrade_blank", "Blank", "upgrade", 1, "spirit", 500, "   ")
        ));

        syncService.sync();

        assertThat(emojiGateway.createdNames).isEmpty();
        verify(assetImageClient, never()).download(anyString());
    }

    @Test
    void failedDownloadOrCreationIsSkippedAndOthersContinue() {
        when(deadlockApiClient.getHeroes()).thenReturn(List.of(
                new HeroAsset(1, "hero_inferno", "Infernus",
                        new HeroImages("https://assets-bucket.deadlock-api.com/h1.png", "card")),
                new HeroAsset(2, "hero_seven", "Seven",
                        new HeroImages("https://assets-bucket.deadlock-api.com/h2.png", "card")),
                new HeroAsset(3, "hero_vindicta", "Vindicta",
                        new HeroImages("https://assets-bucket.deadlock-api.com/h3.png", "card"))
        ));
        when(deadlockApiClient.getItems()).thenReturn(List.of());

        when(assetImageClient.download("https://assets-bucket.deadlock-api.com/h1.png"))
                .thenThrow(new DeadlockApiException("Failed download"));
        when(assetImageClient.download("https://assets-bucket.deadlock-api.com/h2.png"))
                .thenReturn(new byte[] {1});
        when(assetImageClient.download("https://assets-bucket.deadlock-api.com/h3.png"))
                .thenReturn(new byte[] {2});

        emojiGateway.failOnName = "h_3"; // h_3 creation fails in gateway

        syncService.sync();

        assertThat(emojiGateway.createdNames).containsExactly("h_2", "h_3");
        assertThat(iconCatalog.hero(1)).isEmpty();
        assertThat(iconCatalog.hero(2)).isPresent();
        assertThat(iconCatalog.hero(3)).isEmpty();
    }

    @Test
    void maxNewEmojisStopsRun() {
        List<HeroAsset> heroes = new ArrayList<>();
        for (int i = 1; i <= EmojiSyncService.MAX_NEW_EMOJIS + 10; i++) {
            heroes.add(new HeroAsset(i, "hero_" + i, "Hero" + i,
                    new HeroImages("https://assets-bucket.deadlock-api.com/" + i + ".png", "card")));
        }
        when(deadlockApiClient.getHeroes()).thenReturn(heroes);
        when(deadlockApiClient.getItems()).thenReturn(List.of());
        when(assetImageClient.download(anyString())).thenReturn(new byte[] {1});

        syncService.sync();

        assertThat(emojiGateway.createdNames).hasSize(EmojiSyncService.MAX_NEW_EMOJIS);
    }

    @Test
    void listExistingFailureEndsRunWithoutThrowingAndLeavesCatalogUnchanged() {
        iconCatalog.replace(Map.of("h_99", "<:h_99:9999>"));
        emojiGateway.failListExisting = true;

        assertThatCode(() -> syncService.sync()).doesNotThrowAnyException();

        assertThat(iconCatalog.hero(99)).contains("<:h_99:9999>");
        verify(deadlockApiClient, never()).getHeroes();
    }

    @Test
    void assetUnavailableEndsRunWithoutThrowingAndLeavesCatalogUnchanged() {
        iconCatalog.replace(Map.of("h_99", "<:h_99:9999>"));
        when(deadlockApiClient.getHeroes()).thenThrow(new DeadlockApiUnavailableException("Down", null, null));

        assertThatCode(() -> syncService.sync()).doesNotThrowAnyException();

        assertThat(iconCatalog.hero(99)).contains("<:h_99:9999>");
    }

    static class FakeEmojiGateway implements EmojiGateway {
        final Map<String, String> existing = new HashMap<>();
        final List<String> createdNames = new ArrayList<>();
        boolean failListExisting = false;
        String failOnName = null;

        @Override
        public Map<String, String> listExisting() {
            if (failListExisting) {
                throw new RuntimeException("Discord API error");
            }
            return new HashMap<>(existing);
        }

        @Override
        public String create(String name, byte[] image) {
            createdNames.add(name);
            if (name.equals(failOnName)) {
                throw new RuntimeException("Discord upload error on " + name);
            }
            String markup = "<:" + name + ":" + (10000 + createdNames.size()) + ">";
            existing.put(name, markup);
            return markup;
        }
    }
}
