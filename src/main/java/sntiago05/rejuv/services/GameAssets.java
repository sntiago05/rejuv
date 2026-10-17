package sntiago05.rejuv.services;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;
import sntiago05.rejuv.clients.DeadlockApiClient;
import sntiago05.rejuv.clients.dto.HeroAsset;
import sntiago05.rejuv.clients.dto.ItemAsset;

/**
 * Lookup maps over the cached heroes and items assets. Each map is built once per cached asset list instead of on
 * every command: the client cache returns the same list instance until the entry expires, so a different instance
 * means fresh data and the maps are rebuilt. Lets DeadlockApiUnavailableException / DeadlockApiException propagate.
 */
@Component
public class GameAssets {

    private final DeadlockApiClient deadlockApiClient;

    // Two threads may rebuild the same index at once; both results are equal, so the race is harmless.
    private volatile HeroIndex heroIndex;
    private volatile ItemIndex itemIndex;

    public GameAssets(DeadlockApiClient deadlockApiClient) {
        this.deadlockApiClient = deadlockApiClient;
    }

    /** Hero name by id: the first non-null name for each id; heroes without a name are absent. */
    public Map<Integer, String> heroNames() {
        return heroIndex().names();
    }

    /** Heroes whose name is non-null and non-blank, in asset order. */
    public List<HeroAsset> namedHeroes() {
        return heroIndex().named();
    }

    /** Item by id: the first asset for each id. */
    public Map<Long, ItemAsset> itemsById() {
        return itemIndex().byId();
    }

    /** Item name by id: the first non-null name for each id; items without a name are absent. */
    public Map<Long, String> itemNames() {
        return itemIndex().names();
    }

    private HeroIndex heroIndex() {
        List<HeroAsset> heroes = deadlockApiClient.getHeroes();
        HeroIndex index = heroIndex;
        if (index == null || index.source() != heroes) {
            index = HeroIndex.of(heroes);
            heroIndex = index;
        }
        return index;
    }

    private ItemIndex itemIndex() {
        List<ItemAsset> items = deadlockApiClient.getItems();
        ItemIndex index = itemIndex;
        if (index == null || index.source() != items) {
            index = ItemIndex.of(items);
            itemIndex = index;
        }
        return index;
    }

    private record HeroIndex(List<HeroAsset> source, Map<Integer, String> names, List<HeroAsset> named) {

        static HeroIndex of(List<HeroAsset> heroes) {
            Map<Integer, String> names = HashMap.newHashMap(heroes.size());
            List<HeroAsset> named = new ArrayList<>(heroes.size());
            for (HeroAsset hero : heroes) {
                if (hero.name() != null) {
                    names.putIfAbsent(hero.id(), hero.name());
                    if (!hero.name().isBlank()) {
                        named.add(hero);
                    }
                }
            }
            return new HeroIndex(heroes, Collections.unmodifiableMap(names), List.copyOf(named));
        }
    }

    private record ItemIndex(List<ItemAsset> source, Map<Long, ItemAsset> byId, Map<Long, String> names) {

        static ItemIndex of(List<ItemAsset> items) {
            Map<Long, ItemAsset> byId = HashMap.newHashMap(items.size());
            Map<Long, String> names = HashMap.newHashMap(items.size());
            for (ItemAsset item : items) {
                byId.putIfAbsent(item.id(), item);
                if (item.name() != null) {
                    names.putIfAbsent(item.id(), item.name());
                }
            }
            return new ItemIndex(items, Collections.unmodifiableMap(byId), Collections.unmodifiableMap(names));
        }
    }
}
