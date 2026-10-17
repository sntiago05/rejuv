package sntiago05.rejuv.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sntiago05.rejuv.clients.DeadlockApiClient;
import sntiago05.rejuv.clients.DeadlockApiException;
import sntiago05.rejuv.clients.DeadlockApiUnavailableException;
import sntiago05.rejuv.clients.dto.HeroAsset;
import sntiago05.rejuv.clients.dto.HeroImages;
import sntiago05.rejuv.clients.dto.RankAsset;

class AssetImagesTest {

    private DeadlockApiClient deadlockApiClient;
    private AssetImages assetImages;

    @BeforeEach
    void setUp() {
        deadlockApiClient = mock(DeadlockApiClient.class);
        assetImages = new AssetImages(deadlockApiClient);
    }

    @Test
    void heroCardReturnsUrlWhenPresent() {
        when(deadlockApiClient.getHeroes()).thenReturn(List.of(
                new HeroAsset(1, "hero_inferno", "Infernus",
                        new HeroImages("https://assets-bucket.deadlock-api.com/inferno_sm.png",
                                "https://assets-bucket.deadlock-api.com/inferno_card.png"))
        ));

        Optional<String> url = assetImages.heroCardUrl(1);

        assertThat(url).contains("https://assets-bucket.deadlock-api.com/inferno_card.png");
    }

    @Test
    void heroCardReturnsEmptyWhenImagesAbsentOrBlank() {
        when(deadlockApiClient.getHeroes()).thenReturn(List.of(
                new HeroAsset(2, "hero_seven", "Seven", null),
                new HeroAsset(3, "hero_vindicta", "Vindicta", new HeroImages("...", "   "))
        ));

        assertThat(assetImages.heroCardUrl(2)).isEmpty();
        assertThat(assetImages.heroCardUrl(3)).isEmpty();
    }

    @Test
    void heroCardReturnsEmptyForUnknownHero() {
        when(deadlockApiClient.getHeroes()).thenReturn(List.of(
                new HeroAsset(1, "hero_inferno", "Infernus")
        ));

        assertThat(assetImages.heroCardUrl(999)).isEmpty();
    }

    @Test
    void heroCardReturnsEmptyOnAssetException() {
        when(deadlockApiClient.getHeroes()).thenThrow(new DeadlockApiUnavailableException("Down", null, null));

        assertThat(assetImages.heroCardUrl(1)).isEmpty();
    }

    @Test
    void rankBadgeUsesSubrankUrlWhenAvailable() {
        when(deadlockApiClient.getRanks()).thenReturn(List.of(
                new RankAsset(7, "Emissary", Map.of(
                        "large", "https://api.deadlock-api.com/large.png",
                        "large_subrank3", "https://api.deadlock-api.com/subrank3.png"
                ))
        ));

        Optional<String> badge = assetImages.rankBadgeUrl(7, 3);

        assertThat(badge).contains("https://api.deadlock-api.com/subrank3.png");
    }

    @Test
    void rankBadgeFallsBackToLargeForSubrank0OrMissingSubrankKey() {
        when(deadlockApiClient.getRanks()).thenReturn(List.of(
                new RankAsset(7, "Emissary", Map.of(
                        "large", "https://api.deadlock-api.com/large.png",
                        "large_subrank1", "https://api.deadlock-api.com/subrank1.png"
                ))
        ));

        // subrank 0 -> large
        assertThat(assetImages.rankBadgeUrl(7, 0)).contains("https://api.deadlock-api.com/large.png");
        // subrank 4 missing key -> fallback to large
        assertThat(assetImages.rankBadgeUrl(7, 4)).contains("https://api.deadlock-api.com/large.png");
    }

    @Test
    void rankBadgeReturnsEmptyForUnknownTierOrMissingImages() {
        when(deadlockApiClient.getRanks()).thenReturn(List.of(
                new RankAsset(0, "Obscurus", Map.of("large", "https://api.deadlock-api.com/0.png")),
                new RankAsset(2, "Seeker", null)
        ));

        assertThat(assetImages.rankBadgeUrl(99, 1)).isEmpty();
        assertThat(assetImages.rankBadgeUrl(-1, 1)).isEmpty();
        assertThat(assetImages.rankBadgeUrl(12, 1)).isEmpty();
        assertThat(assetImages.rankBadgeUrl(2, 1)).isEmpty();
    }

    @Test
    void rankBadgeReturnsEmptyOnAssetException() {
        when(deadlockApiClient.getRanks()).thenThrow(new DeadlockApiException("Bad json"));

        assertThat(assetImages.rankBadgeUrl(7, 1)).isEmpty();
    }
}
