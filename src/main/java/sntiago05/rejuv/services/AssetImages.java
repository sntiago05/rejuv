package sntiago05.rejuv.services;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import sntiago05.rejuv.clients.DeadlockApiClient;
import sntiago05.rejuv.clients.DeadlockApiException;
import sntiago05.rejuv.clients.DeadlockApiUnavailableException;
import sntiago05.rejuv.clients.dto.HeroAsset;
import sntiago05.rejuv.clients.dto.RankAsset;

/**
 * Resolves hero card and rank badge image URLs from cached deadlock-api assets.
 */
@Component
public class AssetImages {

    private static final Logger log = LoggerFactory.getLogger(AssetImages.class);

    private final DeadlockApiClient deadlockApiClient;

    public AssetImages(DeadlockApiClient deadlockApiClient) {
        this.deadlockApiClient = deadlockApiClient;
    }

    /**
     * Resolves the icon_hero_card of the hero, or empty if unknown hero, null images,
     * blank URL, or the asset call failed.
     */
    public Optional<String> heroCardUrl(int heroId) {
        List<HeroAsset> heroes;
        try {
            heroes = deadlockApiClient.getHeroes();
        } catch (DeadlockApiException e) {
            log.warn("Failed to load heroes asset for hero card: {}", e.getMessage());
            return Optional.empty();
        }

        return heroes.stream()
                .filter(h -> h.id() == heroId)
                .findFirst()
                .map(HeroAsset::images)
                .map(images -> images.iconHeroCard())
                .filter(url -> url != null && !url.isBlank());
    }

    /**
     * Resolves the rank badge URL for tier 0..11.
     * subrank 1..6 -> images "large_subrank<N>"; otherwise (0 or missing key) -> "large".
     * Empty if the tier or url is missing or the asset call failed.
     */
    public Optional<String> rankBadgeUrl(int tier, int subrank) {
        if (tier < 0 || tier > 11) {
            return Optional.empty();
        }

        List<RankAsset> ranks;
        try {
            ranks = deadlockApiClient.getRanks();
        } catch (DeadlockApiException e) {
            log.warn("Failed to load ranks asset for rank badge: {}", e.getMessage());
            return Optional.empty();
        }

        return ranks.stream()
                .filter(r -> r.tier() == tier)
                .findFirst()
                .map(RankAsset::images)
                .flatMap(images -> resolveRankUrl(images, subrank));
    }

    private static Optional<String> resolveRankUrl(Map<String, String> images, int subrank) {
        if (images == null) {
            return Optional.empty();
        }
        if (subrank >= 1 && subrank <= 6) {
            String subrankUrl = images.get("large_subrank" + subrank);
            if (subrankUrl != null && !subrankUrl.isBlank()) {
                return Optional.of(subrankUrl);
            }
        }
        String largeUrl = images.get("large");
        if (largeUrl != null && !largeUrl.isBlank()) {
            return Optional.of(largeUrl);
        }
        return Optional.empty();
    }
}
