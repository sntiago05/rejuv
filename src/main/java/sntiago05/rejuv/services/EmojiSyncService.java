package sntiago05.rejuv.services;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import sntiago05.rejuv.clients.AssetImageClient;
import sntiago05.rejuv.clients.DeadlockApiClient;
import sntiago05.rejuv.clients.DeadlockApiException;
import sntiago05.rejuv.clients.dto.HeroAsset;
import sntiago05.rejuv.clients.dto.ItemAsset;

/**
 * Synchronizes hero and item emojis with Discord application emojis in the background.
 */
@Service
public class EmojiSyncService {

    public static final int MAX_NEW_EMOJIS = 400;
    static final int INTERIM_PUBLISH_BATCH = 25;

    private static final Logger log = LoggerFactory.getLogger(EmojiSyncService.class);

    private final DeadlockApiClient deadlockApiClient;
    private final AssetImageClient assetImageClient;
    private final EmojiGateway emojiGateway;
    private final IconCatalog iconCatalog;

    public EmojiSyncService(DeadlockApiClient deadlockApiClient, AssetImageClient assetImageClient,
                            EmojiGateway emojiGateway, IconCatalog iconCatalog) {
        this.deadlockApiClient = deadlockApiClient;
        this.assetImageClient = assetImageClient;
        this.emojiGateway = emojiGateway;
        this.iconCatalog = iconCatalog;
    }

    public void sync() {
        Map<String, String> existing;
        try {
            existing = emojiGateway.listExisting();
        } catch (Exception e) {
            log.warn("Failed to list existing emojis from Discord: {}", e.getMessage());
            return;
        }

        List<HeroAsset> heroes;
        List<ItemAsset> items;
        try {
            heroes = deadlockApiClient.getHeroes();
            items = deadlockApiClient.getItems();
        } catch (DeadlockApiException e) {
            log.warn("Failed to fetch assets for emoji sync: {}", e.getMessage());
            return;
        }

        Map<String, String> currentMap = new HashMap<>(existing);
        iconCatalog.replace(currentMap);

        List<DesiredEmoji> desiredList = new ArrayList<>();
        for (HeroAsset hero : heroes) {
            if (hero.images() != null && hero.images().iconImageSmall() != null && !hero.images().iconImageSmall().isBlank()) {
                desiredList.add(new DesiredEmoji(IconCatalog.heroEmojiName(hero.id()), hero.images().iconImageSmall()));
            }
        }
        for (ItemAsset item : items) {
            if ("upgrade".equalsIgnoreCase(item.type()) && item.image() != null && !item.image().isBlank()) {
                desiredList.add(new DesiredEmoji(IconCatalog.itemEmojiName(item.id()), item.image()));
            }
        }

        int createdCount = 0;
        int skippedCount = 0;

        for (DesiredEmoji desired : desiredList) {
            if (existing.containsKey(desired.name())) {
                continue;
            }
            if (createdCount >= MAX_NEW_EMOJIS) {
                log.warn("Reached maximum new emoji creation limit of {} in one run, stopping sync", MAX_NEW_EMOJIS);
                break;
            }
            try {
                byte[] bytes = assetImageClient.download(desired.url());
                String markup = emojiGateway.create(desired.name(), bytes);
                currentMap.put(desired.name(), markup);
                createdCount++;
                if (createdCount % INTERIM_PUBLISH_BATCH == 0) {
                    iconCatalog.replace(currentMap);
                }
            } catch (Exception e) {
                log.warn("Failed to create emoji {}: {}", desired.name(), e.getMessage());
                skippedCount++;
            }
        }

        iconCatalog.replace(currentMap);
        log.info("Emoji sync completed: existing={}, created={}, skipped={}",
                existing.size(), createdCount, skippedCount);
    }

    private record DesiredEmoji(String name, String url) { }
}
