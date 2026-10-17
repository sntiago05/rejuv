package sntiago05.rejuv.services;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import sntiago05.rejuv.clients.DeadlockApiClient;
import sntiago05.rejuv.clients.dto.HeroAsset;
import sntiago05.rejuv.clients.dto.HeroCounterStat;
import sntiago05.rejuv.clients.dto.ItemAsset;
import sntiago05.rejuv.clients.dto.ItemStat;
import sntiago05.rejuv.domain.CounterGuide;
import sntiago05.rejuv.domain.CounterItem;
import sntiago05.rejuv.domain.CounterMatchup;

@Service
public class CounterService {

    static final int MIN_MATCHUP_MATCHES = 50;
    static final int MIN_ITEM_MATCHES = 100;
    static final int MAX_MATCHUPS = 5;
    static final int MAX_ITEMS = 5;
    static final String UPGRADE_TYPE = "upgrade";

    private static final Comparator<CounterMatchup> MATCHUP_COMPARATOR = (a, b) -> {
        int cmp = Double.compare(b.winRatePercent(), a.winRatePercent());
        if (cmp != 0) {
            return cmp;
        }
        cmp = Long.compare(b.matches(), a.matches());
        if (cmp != 0) {
            return cmp;
        }
        return Integer.compare(a.heroId(), b.heroId());
    };

    private static final Comparator<CounterItem> ITEM_COMPARATOR = (a, b) -> {
        int cmp = Double.compare(b.winRatePercent(), a.winRatePercent());
        if (cmp != 0) {
            return cmp;
        }
        cmp = Long.compare(b.matches(), a.matches());
        if (cmp != 0) {
            return cmp;
        }
        return Long.compare(a.itemId(), b.itemId());
    };

    private final DeadlockApiClient deadlockApiClient;
    private final HeroResolver heroResolver;
    private final Clock clock;

    @Autowired
    public CounterService(DeadlockApiClient deadlockApiClient, HeroResolver heroResolver) {
        this(deadlockApiClient, heroResolver, Clock.systemUTC());
    }

    CounterService(DeadlockApiClient deadlockApiClient, HeroResolver heroResolver, Clock clock) {
        this.deadlockApiClient = deadlockApiClient;
        this.heroResolver = heroResolver;
        this.clock = clock;
    }

    /**
     * @param heroInput text typed by the user
     * @param rankTier  null = all ranks, else 1..11
     * @throws IllegalArgumentException for a tier outside 1..11 (before any call)
     * @throws HeroNotFoundException    if the input does not resolve to a hero
     * Lets DeadlockApiUnavailableException / DeadlockApiException propagate.
     */
    public CounterGuide getCounterGuide(String heroInput, Integer rankTier) {
        ServiceSupport.validateRankTier(rankTier);

        HeroAsset targetHero = heroResolver.resolve(heroInput)
                .orElseThrow(() -> new HeroNotFoundException(heroInput));

        long minUnixTimestamp = ServiceSupport.startOfUtcDayMinus(clock, ServiceSupport.WINDOW);

        List<HeroCounterStat> counterStats = deadlockApiClient.getHeroCounterStats(rankTier, minUnixTimestamp);
        List<ItemStat> itemStats = deadlockApiClient.getItemStatsAgainst(targetHero.id(), rankTier, minUnixTimestamp);
        List<HeroAsset> heroesAsset = deadlockApiClient.getHeroes();
        List<ItemAsset> itemsAsset = deadlockApiClient.getItems();

        Map<Integer, String> heroNames = new HashMap<>(Math.max((int) (heroesAsset.size() / 0.75f) + 1, 16));
        for (HeroAsset hero : heroesAsset) {
            heroNames.putIfAbsent(hero.id(), hero.name());
        }

        Set<Integer> seenCounterOpponents = new HashSet<>();
        List<CounterMatchup> counterCandidates = new ArrayList<>();
        Set<Integer> seenFavorableOpponents = new HashSet<>();
        List<CounterMatchup> favorableCandidates = new ArrayList<>();

        int targetHeroId = targetHero.id();
        for (HeroCounterStat stat : counterStats) {
            if (stat.matchesPlayed() < MIN_MATCHUP_MATCHES) {
                continue;
            }
            int heroId = stat.heroId();
            int enemyHeroId = stat.enemyHeroId();

            double winRate = stat.wins() * 100.0 / stat.matchesPlayed();
            if (enemyHeroId == targetHeroId && heroId != targetHeroId) {
                if (seenCounterOpponents.add(heroId)) {
                    counterCandidates.add(new CounterMatchup(
                            heroId,
                            heroNames.get(heroId),
                            stat.matchesPlayed(),
                            winRate));
                }
            } else if (heroId == targetHeroId && enemyHeroId != targetHeroId) {
                if (seenFavorableOpponents.add(enemyHeroId)) {
                    favorableCandidates.add(new CounterMatchup(
                            enemyHeroId,
                            heroNames.get(enemyHeroId),
                            stat.matchesPlayed(),
                            winRate));
                }
            }
        }

        counterCandidates.sort(MATCHUP_COMPARATOR);
        List<CounterMatchup> counters = List.copyOf(counterCandidates.subList(0, Math.min(counterCandidates.size(), MAX_MATCHUPS)));

        favorableCandidates.sort(MATCHUP_COMPARATOR);
        List<CounterMatchup> favorable = List.copyOf(favorableCandidates.subList(0, Math.min(favorableCandidates.size(), MAX_MATCHUPS)));

        List<CounterItem> itemCandidates = getItemCandidates(itemsAsset, itemStats);
        itemCandidates.sort(ITEM_COMPARATOR);
        List<CounterItem> items = List.copyOf(itemCandidates.subList(0, Math.min(itemCandidates.size(), MAX_ITEMS)));

        return new CounterGuide(targetHero.id(), targetHero.name(), rankTier, counters, favorable, items);
    }

    private static @NonNull List<CounterItem> getItemCandidates(List<ItemAsset> itemsAsset, List<ItemStat> itemStats) {
        Map<Long, ItemAsset> itemAssets = new HashMap<>(Math.max((int) (itemsAsset.size() / 0.75f) + 1, 16));
        for (ItemAsset item : itemsAsset) {
            itemAssets.putIfAbsent(item.id(), item);
        }

        Set<Long> seenItemIds = new HashSet<>();
        List<CounterItem> itemCandidates = new ArrayList<>();
        for (ItemStat stat : itemStats) {
            if (stat.matches() < MIN_ITEM_MATCHES) {
                continue;
            }
            ItemAsset asset = itemAssets.get(stat.itemId());
            if (asset == null || asset.name() == null || asset.name().isBlank()) {
                continue;
            }
            if (asset.type() == null || !asset.type().equalsIgnoreCase(UPGRADE_TYPE)) {
                continue;
            }
            if (seenItemIds.add(stat.itemId())) {
                double winRate = stat.wins() * 100.0 / stat.matches();
                itemCandidates.add(new CounterItem(stat.itemId(), asset.name(), stat.matches(), winRate));
            }
        }
        return itemCandidates;
    }
}
