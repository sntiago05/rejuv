package sntiago05.rejuv.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sntiago05.rejuv.clients.AssetImageClient;
import sntiago05.rejuv.clients.DeadlockApiException;
import sntiago05.rejuv.clients.dto.HeroAsset;
import sntiago05.rejuv.clients.dto.HeroImages;
import sntiago05.rejuv.clients.dto.ItemAsset;

class ReportIconServiceTest {

    private static final String HERO_URL = "https://assets-bucket.deadlock-api.com/hero.png";
    private static final String ITEM_URL = "https://assets-bucket.deadlock-api.com/item.webp";
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 0, 0, 0, 13};
    private static final byte[] WEBP = {'R', 'I', 'F', 'F', 1, 2, 3, 4, 'W', 'E', 'B', 'P'};

    private AssetImageClient client;
    private GameAssets assets;
    private ReportIconService service;

    @BeforeEach
    void setUp() {
        client = mock(AssetImageClient.class);
        assets = mock(GameAssets.class);
        service = new ReportIconService(client, assets);
        when(assets.namedHeroes()).thenReturn(List.of(
                new HeroAsset(7, "hero_x", "Seven", new HeroImages(HERO_URL, "https://assets-bucket.deadlock-api.com/card.png")),
                new HeroAsset(8, "hero_y", "Eight", null)));
        ItemAsset item = new ItemAsset(42L, "item_x", "Thing", "upgrade", 1, "weapon", 500);
        when(assets.itemsById()).thenReturn(Map.of(42L, item));
    }

    @Test
    void heroIconIsDownloadedOnceAndCached() {
        when(client.download(HERO_URL)).thenReturn(PNG);

        Optional<ReportIconService.Icon> first = service.hero(7);
        Optional<ReportIconService.Icon> second = service.hero(7);

        assertThat(first).isPresent();
        assertThat(first.get().contentType()).isEqualTo("image/png");
        assertThat(second).isPresent();
        verify(client, times(1)).download(HERO_URL);
    }

    @Test
    void unknownHeroOrHeroWithoutImagesIsEmptyWithoutDownload() {
        assertThat(service.hero(99)).isEmpty();
        assertThat(service.hero(8)).isEmpty();
        assertThat(service.item(1L)).isEmpty();
        verify(client, never()).download(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void downloadFailureIsEmptyAndNotCached() {
        when(client.download(HERO_URL)).thenThrow(new DeadlockApiException("down")).thenReturn(PNG);

        assertThat(service.hero(7)).isEmpty();
        assertThat(service.hero(7)).isPresent();
    }

    @Test
    void notAnImageIsRejected() {
        when(client.download(HERO_URL)).thenReturn("<html>oops, not an image</html>".getBytes(StandardCharsets.UTF_8));

        assertThat(service.hero(7)).isEmpty();
    }

    @Test
    void sniffRecognizesSupportedTypesOnly() {
        assertThat(ReportIconService.sniffContentType(PNG)).contains("image/png");
        assertThat(ReportIconService.sniffContentType(WEBP)).contains("image/webp");
        assertThat(ReportIconService.sniffContentType(new byte[] {(byte) 0xFF, (byte) 0xD8, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10}))
                .contains("image/jpeg");
        assertThat(ReportIconService.sniffContentType("  <svg xmlns='x'></svg>".getBytes(StandardCharsets.UTF_8)))
                .contains("image/svg+xml");
        assertThat(ReportIconService.sniffContentType("<?xml version='1.0'?><svg></svg>".getBytes(StandardCharsets.UTF_8)))
                .contains("image/svg+xml");
        assertThat(ReportIconService.sniffContentType(new byte[] {1, 2, 3})).isEmpty();
        assertThat(ReportIconService.sniffContentType(null)).isEmpty();
    }

    @Test
    void itemIconUsesTheAssetImageUrl() {
        ItemAsset withImage = new ItemAsset(43L, "item_y", "Other", "upgrade", 1, "weapon", 500, ITEM_URL);
        when(assets.itemsById()).thenReturn(Map.of(43L, withImage));
        when(client.download(ITEM_URL)).thenReturn(WEBP);

        Optional<ReportIconService.Icon> icon = service.item(43L);

        assertThat(icon).isPresent();
        assertThat(icon.get().contentType()).isEqualTo("image/webp");
    }

    @Test
    void itemWithoutImageUrlIsEmptyWithoutDownload() {
        assertThat(service.item(42L)).isEmpty();
        verify(client, never()).download(org.mockito.ArgumentMatchers.anyString());
    }
}
