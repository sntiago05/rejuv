package sntiago05.rejuv.services;

import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;

/**
 * In-memory catalog mapping hero and item IDs to Discord application emoji markup (<:name:id>).
 * Thread-safe and non-blocking.
 */
@Component
public class IconCatalog {

    private volatile Map<String, String> catalog = Map.of();

    public static String heroEmojiName(int heroId) {
        return "h_" + heroId;
    }

    public static String itemEmojiName(long itemId) {
        return "i_" + itemId;
    }

    public Optional<String> hero(int heroId) {
        return Optional.ofNullable(catalog.get(heroEmojiName(heroId)));
    }

    public Optional<String> item(long itemId) {
        return Optional.ofNullable(catalog.get(itemEmojiName(itemId)));
    }

    /**
     * Atomically replaces the catalog with an immutable copy of the provided markup map.
     */
    public void replace(Map<String, String> markupByEmojiName) {
        this.catalog = markupByEmojiName != null ? Map.copyOf(markupByEmojiName) : Map.of();
    }
}
