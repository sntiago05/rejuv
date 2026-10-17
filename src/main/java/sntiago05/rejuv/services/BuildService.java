package sntiago05.rejuv.services;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import sntiago05.rejuv.clients.DeadlockApiClient;
import sntiago05.rejuv.clients.dto.AbilityOrderStat;
import sntiago05.rejuv.clients.dto.HeroAsset;
import sntiago05.rejuv.clients.dto.ItemAsset;
import sntiago05.rejuv.clients.dto.ItemFlowNode;
import sntiago05.rejuv.clients.dto.ItemFlowStats;
import sntiago05.rejuv.domain.BuildItem;
import sntiago05.rejuv.domain.BuildPhase;
import sntiago05.rejuv.domain.HeroBuild;

@Service
public class BuildService {

    public static final int MIN_ITEM_MATCHES = 20;
    static final int MAX_ITEMS_PER_PHASE = 4;
    static final int MAX_PHASES = 6;

    private static final Comparator<BuildItem> ITEM_COMPARATOR = Comparator
            .comparingLong(BuildItem::matches).reversed()
            .thenComparingLong(BuildItem::itemId);

    private static final Comparator<AbilityOrderStat> BEST_ABILITY_ORDER = (a, b) -> {
        int matchesCmp = Long.compare(a.matches(), b.matches());
        if (matchesCmp != 0) {
            return matchesCmp;
        }
        int winsCmp = Long.compare(a.wins(), b.wins());
        if (winsCmp != 0) {
            return winsCmp;
        }
        return -compareAbilities(a.abilities(), b.abilities());
    };

    private final DeadlockApiClient deadlockApiClient;
    private final HeroResolver heroResolver;
    private final Clock clock;

    @Autowired
    public BuildService(DeadlockApiClient deadlockApiClient, HeroResolver heroResolver) {
        this(deadlockApiClient, heroResolver, Clock.systemUTC());
    }

    BuildService(DeadlockApiClient deadlockApiClient, HeroResolver heroResolver, Clock clock) {
        this.deadlockApiClient = deadlockApiClient;
        this.heroResolver = heroResolver;
        this.clock = clock;
    }

    /**
     * @param heroInput text typed by the user
     * @param rankTier null = all ranks, else 1..11
     * @throws IllegalArgumentException for a tier outside 1..11 (before any call)
     * @throws HeroNotFoundException if the input does not resolve to a hero
     * Lets DeadlockApiUnavailableException / DeadlockApiException propagate.
     */
    public HeroBuild getBuild(String heroInput, Integer rankTier) {
        ServiceSupport.validateRankTier(rankTier);

        HeroAsset hero = heroResolver.resolve(heroInput)
                .orElseThrow(() -> new HeroNotFoundException(heroInput));

        long minUnixTimestamp = ServiceSupport.startOfUtcDayMinus(clock, ServiceSupport.WINDOW);

        ItemFlowStats flowStats = deadlockApiClient.getItemFlowStats(hero.id(), rankTier, minUnixTimestamp);
        List<AbilityOrderStat> abilityOrderStats = deadlockApiClient.getAbilityOrderStats(hero.id(), rankTier, minUnixTimestamp);
        List<ItemAsset> itemsAsset = deadlockApiClient.getItems();

        Map<Long, String> itemNames = new HashMap<>(Math.max((int) (itemsAsset.size() / 0.75f) + 1, 16));
        for (ItemAsset item : itemsAsset) {
            if (item.name() != null) {
                itemNames.putIfAbsent(item.id(), item.name());
            }
        }

        Map<Integer, List<BuildItem>> columnItems = new HashMap<>(MAX_PHASES * 2);
        Set<ColumnItemKey> seenColumnItem = new HashSet<>();

        List<Long> reachedPerColumn = flowStats.reachedPerColumn();
        int reachedSize = reachedPerColumn != null ? reachedPerColumn.size() : 0;

        for (ItemFlowNode node : flowStats.nodes()) {
            if (node.matches() < MIN_ITEM_MATCHES) {
                continue;
            }
            if (!seenColumnItem.add(new ColumnItemKey(node.column(), node.itemId()))) {
                continue;
            }
            long matches = node.matches();
            long wins = node.wins();
            double pickRatePercent = 0.0;
            if (node.column() >= 0 && node.column() < reachedSize) {
                Long reached = reachedPerColumn.get(node.column());
                if (reached != null && reached > 0) {
                    pickRatePercent = matches * 100.0 / reached;
                }
            }
            double winRatePercent = matches > 0 ? (wins * 100.0 / matches) : 0.0;
            String itemName = itemNames.get(node.itemId());
            columnItems.computeIfAbsent(node.column(), k -> new ArrayList<>())
                    .add(new BuildItem(node.itemId(), itemName, matches, pickRatePercent, winRatePercent));
        }

        List<Integer> sortedColumns = new ArrayList<>(columnItems.keySet());
        Collections.sort(sortedColumns);
        List<BuildPhase> phases = new ArrayList<>(Math.min(sortedColumns.size(), MAX_PHASES));
        for (int col : sortedColumns) {
            List<BuildItem> items = columnItems.get(col);
            if (items != null && !items.isEmpty()) {
                items.sort(ITEM_COMPARATOR);
                List<BuildItem> capped = items.subList(0, Math.min(items.size(), MAX_ITEMS_PER_PHASE));
                phases.add(new BuildPhase(col, List.copyOf(capped)));
                if (phases.size() == MAX_PHASES) {
                    break;
                }
            }
        }

        List<String> abilityOrder = List.of();
        long abilityOrderMatches = 0;

        AbilityOrderStat bestOrder = null;
        for (AbilityOrderStat s : abilityOrderStats) {
            if (s.abilities() != null && !s.abilities().isEmpty()) {
                if (bestOrder == null || BEST_ABILITY_ORDER.compare(s, bestOrder) > 0) {
                    bestOrder = s;
                }
            }
        }

        if (bestOrder != null) {
            abilityOrderMatches = bestOrder.matches();
            List<Long> abilities = bestOrder.abilities();
            List<String> order = new ArrayList<>(abilities.size());
            for (Long id : abilities) {
                String name = itemNames.get(id);
                order.add(name != null && !name.isBlank() ? name : "#" + id);
            }
            abilityOrder = List.copyOf(order);
        }

        return new HeroBuild(hero.id(), hero.name(), rankTier, phases, abilityOrder, abilityOrderMatches);
    }

    private record ColumnItemKey(int column, long itemId) {}

    private static int compareAbilities(List<Long> a, List<Long> b) {
        int minLen = Math.min(a.size(), b.size());
        for (int i = 0; i < minLen; i++) {
            int cmp = Long.compare(a.get(i), b.get(i));
            if (cmp != 0) {
                return cmp;
            }
        }
        return Integer.compare(a.size(), b.size());
    }
}
