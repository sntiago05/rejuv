package sntiago05.rejuv.services;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.function.Supplier;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import sntiago05.rejuv.clients.AssetImageClient;
import sntiago05.rejuv.clients.DeadlockApiException;
import sntiago05.rejuv.clients.dto.HeroAsset;
import sntiago05.rejuv.clients.dto.HeroImages;
import sntiago05.rejuv.clients.dto.ItemAsset;

/**
 * Serves hero and item icons for the public report from our own origin: the report CSP only allows
 * {@code img-src 'self'}, so the browser cannot load the CDN URLs directly. Bytes are cached in memory (bounded);
 * only images that really are PNG, JPEG, WebP or SVG are returned. Callers must check that the id belongs to the
 * match before asking, so this is not an open proxy.
 */
@Component
public class ReportIconService {

    private static final Logger log = LoggerFactory.getLogger(ReportIconService.class);
    private static final int MAX_CACHED_ICONS = 600;
    private static final Duration ICON_TTL = Duration.ofHours(6);

    /** Image bytes with a content type that matches them. */
    public record Icon(byte[] bytes, String contentType) {
    }

    private final AssetImageClient assetImageClient;
    private final GameAssets gameAssets;
    private final Cache<String, Icon> cache = Caffeine.newBuilder()
            .maximumSize(MAX_CACHED_ICONS)
            .expireAfterWrite(ICON_TTL)
            .build();

    public ReportIconService(AssetImageClient assetImageClient, GameAssets gameAssets) {
        this.assetImageClient = assetImageClient;
        this.gameAssets = gameAssets;
    }

    public Optional<Icon> hero(int heroId) {
        return load("hero:" + heroId, () -> gameAssets.namedHeroes().stream()
                .filter(hero -> hero.id() == heroId)
                .findFirst()
                .map(ReportIconService::heroIconUrl));
    }

    public Optional<Icon> item(long itemId) {
        return load("item:" + itemId, () -> Optional.ofNullable(gameAssets.itemsById().get(itemId))
                .map(ItemAsset::image));
    }

    private static String heroIconUrl(HeroAsset hero) {
        HeroImages images = hero.images();
        if (images == null) {
            return null;
        }
        return notBlank(images.iconImageSmall()) ? images.iconImageSmall() : images.iconHeroCard();
    }

    private Optional<Icon> load(String key, Supplier<Optional<String>> urlSupplier) {
        Icon cached = cache.getIfPresent(key);
        if (cached != null) {
            return Optional.of(cached);
        }
        try {
            Optional<String> url = urlSupplier.get().filter(ReportIconService::notBlank);
            if (url.isEmpty()) {
                return Optional.empty();
            }
            byte[] bytes = assetImageClient.download(url.get());
            Optional<String> type = sniffContentType(bytes);
            if (type.isEmpty()) {
                log.warn("Icon {} is not a supported image type", key);
                return Optional.empty();
            }
            Icon icon = new Icon(bytes, type.get());
            cache.put(key, icon);
            return Optional.of(icon);
        } catch (DeadlockApiException | IllegalArgumentException ex) {
            log.warn("Icon {} unavailable: {}: {}", key, ex.getClass().getSimpleName(), ex.getMessage());
            return Optional.empty();
        }
    }

    /** Content type from the first bytes; empty for anything that is not PNG, JPEG, WebP or SVG. */
    static Optional<String> sniffContentType(byte[] bytes) {
        if (bytes == null || bytes.length < 12) {
            return Optional.empty();
        }
        if ((bytes[0] & 0xFF) == 0x89 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G') {
            return Optional.of("image/png");
        }
        if ((bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8) {
            return Optional.of("image/jpeg");
        }
        if (bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
                && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P') {
            return Optional.of("image/webp");
        }
        String head = new String(bytes, 0, Math.min(bytes.length, 256), StandardCharsets.UTF_8).stripLeading();
        if (head.startsWith("<svg") || (head.startsWith("<?xml") && head.contains("<svg"))) {
            return Optional.of("image/svg+xml");
        }
        return Optional.empty();
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
