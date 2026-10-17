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
import java.util.Set;
import java.util.stream.Collectors;

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
    static final Duration WINDOW = Duration.ofDays(30);
    static final int MIN_RANK_TIER = 1;
    static final int MAX_RANK_TIER = 11;

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
        if (rankTier != null && (rankTier < MIN_RANK_TIER || rankTier > MAX_RANK_TIER)) {
            throw new IllegalArgumentException("rankTier must be in " + MIN_RANK_TIER + ".." + MAX_RANK_TIER
                    + ", was " + rankTier);
        }

        HeroAsset targetHero = heroResolver.resolve(heroInput)
                .orElseThrow(() -> new HeroNotFoundException(heroInput));

        LocalDate todayUtc = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
        long minUnixTimestamp = todayUtc.atStartOfDay(ZoneOffset.UTC).minus(WINDOW).toEpochSecond();

        List<HeroCounterStat> counterStats = deadlockApiClient.getHeroCounterStats(rankTier, minUnixTimestamp);
        List<ItemStat> itemStats = deadlockApiClient.getItemStatsAgainst(targetHero.id(), rankTier, minUnixTimestamp);
        List<HeroAsset> heroesAsset = deadlockApiClient.getHeroes();
        List<ItemAsset> itemsAsset = deadlockApiClient.getItems();

        Map<Integer, String> heroNames = new HashMap<>();
        for (HeroAsset hero : heroesAsset) {
            heroNames.putIfAbsent(hero.id(), hero.name());
        }

        Set<Integer> seenCounterOpponents = new HashSet<>();
        List<CounterMatchup> counterCandidates = new ArrayList<>();
        for (HeroCounterStat stat : counterStats) {
            if (stat.enemyHeroId() == targetHero.id()
                    && stat.heroId() != targetHero.id()
                    && stat.matchesPlayed() >= MIN_MATCHUP_MATCHES) {
                if (seenCounterOpponents.add(stat.heroId())) {
                    double winRate = stat.wins() * 100.0 / stat.matchesPlayed();
                    counterCandidates.add(new CounterMatchup(
                            stat.heroId(),
                            heroNames.get(stat.heroId()),
                            stat.matchesPlayed(),
                            winRate));
                }
            }
        }
        counterCandidates.sort(MATCHUP_COMPARATOR);
        List<CounterMatchup> counters = counterCandidates.stream()
                .limit(MAX_MATCHUPS)
                .toList();

        Set<Integer> seenFavorableOpponents = new HashSet<>();
        List<CounterMatchup> favorableCandidates = new ArrayList<>();
        for (HeroCounterStat stat : counterStats) {
            if (stat.heroId() == targetHero.id()
                    && stat.enemyHeroId() != targetHero.id()
                    && stat.matchesPlayed() >= MIN_MATCHUP_MATCHES) {
                if (seenFavorableOpponents.add(stat.enemyHeroId())) {
                    double winRate = stat.wins() * 100.0 / stat.matchesPlayed();
                    favorableCandidates.add(new CounterMatchup(
                            stat.enemyHeroId(),
                            heroNames.get(stat.enemyHeroId()),
                            stat.matchesPlayed(),
                            winRate));
                }
            }
        }
        favorableCandidates.sort(MATCHUP_COMPARATOR);
        List<CounterMatchup> favorable = favorableCandidates.stream()
                .limit(MAX_MATCHUPS)
                .toList();

        Map<Long, ItemAsset> itemAssets = itemsAsset.stream()
                .collect(Collectors.toMap(ItemAsset::id, item -> item, (first, second) -> first));

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
        itemCandidates.sort(ITEM_COMPARATOR);
        List<CounterItem> items = itemCandidates.stream()
                .limit(MAX_ITEMS)
                .toList();

        return new CounterGuide(targetHero.id(), targetHero.name(), rankTier, counters, favorable, items);
    }
}
