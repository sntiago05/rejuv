package sntiago05.rejuv.services;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import sntiago05.rejuv.clients.DeadlockApiClient;
import sntiago05.rejuv.clients.dto.HeroAsset;
import sntiago05.rejuv.clients.dto.ItemAsset;
import sntiago05.rejuv.clients.dto.ItemStat;
import sntiago05.rejuv.clients.dto.PlayerItemStat;
import sntiago05.rejuv.clients.dto.PlayerRank;
import sntiago05.rejuv.domain.ItemComparison;
import sntiago05.rejuv.domain.ItemsReport;

@Service
public class ItemsService {

    public static final int MIN_PLAYER_MATCHES = 8;
    public static final int MIN_META_MATCHES = 100;
    public static final double MIN_DELTA = 5.0;
    public static final int MAX_RESULTS = 5;
    public static final Duration PLAYER_WINDOW = Duration.ofDays(90);

    private static final String UPGRADE_TYPE = "upgrade";

    private static final Comparator<ItemComparison> UNDERPERFORMING_COMPARATOR = Comparator
            .comparingDouble(ItemComparison::deltaPoints)
            .thenComparing(Comparator.comparingLong(ItemComparison::playerMatches).reversed())
            .thenComparingLong(ItemComparison::itemId);

    private static final Comparator<ItemComparison> OUTPERFORMING_COMPARATOR = Comparator
            .comparingDouble(ItemComparison::deltaPoints).reversed()
            .thenComparing(Comparator.comparingLong(ItemComparison::playerMatches).reversed())
            .thenComparingLong(ItemComparison::itemId);

    private final DeadlockApiClient deadlockApiClient;
    private final HeroResolver heroResolver;
    private final GameAssets gameAssets;
    private final Clock clock;

    @Autowired
    public ItemsService(DeadlockApiClient deadlockApiClient, HeroResolver heroResolver, GameAssets gameAssets) {
        this(deadlockApiClient, heroResolver, gameAssets, Clock.systemUTC());
    }

    ItemsService(DeadlockApiClient deadlockApiClient, HeroResolver heroResolver, GameAssets gameAssets, Clock clock) {
        this.deadlockApiClient = deadlockApiClient;
        this.heroResolver = heroResolver;
        this.gameAssets = gameAssets;
        this.clock = clock;
    }

    /**
     * Compares the caller's item win rates and timings on a hero against the meta for the determined rank tier.
     *
     * @param accountId    Deadlock account id
     * @param heroInput    hero name typed by the user
     * @param rankOverride optional rank tier (1..11) to override current rank
     * @return items report with underperforming and outperforming item lists
     * @throws IllegalArgumentException if rankOverride is present but outside 1..11
     * @throws HeroNotFoundException    if heroInput cannot be resolved to a known hero
     * @throws NoRankException          if no rankOverride is given and the player has no ranked badge
     */
    public ItemsReport compare(long accountId, String heroInput, Integer rankOverride) {
        ServiceSupport.validateRankTier(rankOverride);

        HeroAsset hero = heroResolver.resolve(heroInput)
                .orElseThrow(() -> new HeroNotFoundException(heroInput));
        int heroId = hero.id();
        String heroName = hero.name();

        int tier = resolveTier(accountId, rankOverride);

        long startOf90Days = ServiceSupport.startOfUtcDayMinus(clock, PLAYER_WINDOW);
        List<PlayerItemStat> playerStats = deadlockApiClient.getPlayerItemStats(accountId, heroId, startOf90Days);
        Map<Long, ItemStat> metaStats = deadlockApiClient.getItemStats(heroId, tier);
        Map<Long, ItemAsset> itemsById = gameAssets.itemsById();

        int itemsConsidered = 0;
        List<ItemComparison> underperformingCandidates = new ArrayList<>();
        List<ItemComparison> outperformingCandidates = new ArrayList<>();

        for (PlayerItemStat playerStat : playerStats) {
            if (playerStat.matches() < MIN_PLAYER_MATCHES) {
                continue;
            }

            ItemStat metaStat = metaStats.get(playerStat.itemId());
            if (metaStat == null || metaStat.matches() < MIN_META_MATCHES) {
                continue;
            }

            ItemAsset asset = itemsById.get(playerStat.itemId());
            if (asset == null || asset.name() == null || asset.name().isBlank()) {
                continue;
            }
            if (asset.type() == null || !asset.type().equalsIgnoreCase(UPGRADE_TYPE)) {
                continue;
            }

            itemsConsidered++;

            double playerWinRate = (playerStat.wins() * 100.0) / playerStat.matches();
            double metaWinRate = (metaStat.wins() * 100.0) / metaStat.matches();
            double delta = playerWinRate - metaWinRate;

            Double playerBuyMin = null;
            Double metaBuyMin = null;
            if (playerStat.avgBuyTimeS() != null && playerStat.avgBuyTimeS() > 0 && metaStat.avgBuyTimeS() > 0) {
                playerBuyMin = roundOneDecimal(playerStat.avgBuyTimeS() / 60.0);
                metaBuyMin = roundOneDecimal(metaStat.avgBuyTimeS() / 60.0);
            }

            ItemComparison comparison = new ItemComparison(
                    playerStat.itemId(),
                    asset.name(),
                    playerStat.matches(),
                    playerWinRate,
                    metaStat.matches(),
                    metaWinRate,
                    delta,
                    playerBuyMin,
                    metaBuyMin
            );

            if (delta <= -MIN_DELTA) {
                underperformingCandidates.add(comparison);
            } else if (delta >= MIN_DELTA) {
                outperformingCandidates.add(comparison);
            }
        }

        List<ItemComparison> underperforming = ServiceSupport.top(underperformingCandidates, UNDERPERFORMING_COMPARATOR, MAX_RESULTS);
        List<ItemComparison> outperforming = ServiceSupport.top(outperformingCandidates, OUTPERFORMING_COMPARATOR, MAX_RESULTS);

        return new ItemsReport(heroId, heroName, tier, underperforming, outperforming, itemsConsidered);
    }

    private int resolveTier(long accountId, Integer rankOverride) {
        if (rankOverride != null) {
            return rankOverride;
        }
        PlayerRank playerRank = deadlockApiClient.getPlayerRank(accountId);
        if (playerRank.badge() <= 0
                || playerRank.rank() < ServiceSupport.MIN_RANK_TIER
                || playerRank.rank() > ServiceSupport.MAX_RANK_TIER) {
            throw new NoRankException(accountId);
        }
        return playerRank.rank();
    }

    private static double roundOneDecimal(double value) {
        return Math.round(value * 10) / 10.0;
    }
}
