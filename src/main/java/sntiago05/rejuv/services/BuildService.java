package sntiago05.rejuv.services;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

    static final int MIN_ITEM_MATCHES = 20;
    static final int MAX_ITEMS_PER_PHASE = 4;
    static final int MAX_PHASES = 6;
    static final Duration WINDOW = Duration.ofDays(30);
    static final int MIN_RANK_TIER = 1;
    static final int MAX_RANK_TIER = 11;

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
        if (rankTier != null && (rankTier < MIN_RANK_TIER || rankTier > MAX_RANK_TIER)) {
            throw new IllegalArgumentException("rankTier must be in " + MIN_RANK_TIER + ".." + MAX_RANK_TIER
                    + ", was " + rankTier);
        }

        HeroAsset hero = heroResolver.resolve(heroInput)
                .orElseThrow(() -> new HeroNotFoundException(heroInput));

        LocalDate todayUtc = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
        long minUnixTimestamp = todayUtc.atStartOfDay(ZoneOffset.UTC).minus(WINDOW).toEpochSecond();

        ItemFlowStats flowStats = deadlockApiClient.getItemFlowStats(hero.id(), rankTier, minUnixTimestamp);
        List<AbilityOrderStat> abilityOrderStats = deadlockApiClient.getAbilityOrderStats(hero.id(), rankTier, minUnixTimestamp);
        List<ItemAsset> itemsAsset = deadlockApiClient.getItems();

        Map<Long, String> itemNames = new HashMap<>();
        for (ItemAsset item : itemsAsset) {
            if (item.name() != null) {
                itemNames.putIfAbsent(item.id(), item.name());
            }
        }

        Map<Integer, List<BuildItem>> columnItems = new HashMap<>();
        Set<String> seenColumnItem = new HashSet<>();

        for (ItemFlowNode node : flowStats.nodes()) {
            if (node.matches() < MIN_ITEM_MATCHES) {
                continue;
            }
            String colItemKey = node.column() + ":" + node.itemId();
            if (!seenColumnItem.add(colItemKey)) {
                continue;
            }
            long matches = node.matches();
            long wins = node.wins();
            double pickRatePercent = 0.0;
            List<Long> reachedPerColumn = flowStats.reachedPerColumn();
            if (reachedPerColumn != null && node.column() >= 0 && node.column() < reachedPerColumn.size()) {
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

        List<BuildPhase> phases = columnItems.entrySet().stream()
                .filter(entry -> !entry.getValue().isEmpty())
                .sorted(Map.Entry.comparingByKey())
                .limit(MAX_PHASES)
                .map(entry -> {
                    List<BuildItem> items = entry.getValue();
                    items.sort(ITEM_COMPARATOR);
                    List<BuildItem> capped = items.subList(0, Math.min(items.size(), MAX_ITEMS_PER_PHASE));
                    return new BuildPhase(entry.getKey(), capped);
                })
                .toList();

        List<String> abilityOrder = List.of();
        long abilityOrderMatches = 0;

        Optional<AbilityOrderStat> bestOrder = abilityOrderStats.stream()
                .filter(s -> s.abilities() != null && !s.abilities().isEmpty())
                .max(BEST_ABILITY_ORDER);

        if (bestOrder.isPresent()) {
            AbilityOrderStat chosen = bestOrder.get();
            abilityOrderMatches = chosen.matches();
            abilityOrder = chosen.abilities().stream()
                    .map(id -> {
                        String name = itemNames.get(id);
                        if (name == null || name.isBlank()) {
                            return "#" + id;
                        }
                        return name;
                    })
                    .toList();
        }

        return new HeroBuild(hero.id(), hero.name(), rankTier, phases, abilityOrder, abilityOrderMatches);
    }

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
