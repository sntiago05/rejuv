package sntiago05.rejuv.services;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import sntiago05.rejuv.clients.DeadlockApiClient;
import sntiago05.rejuv.clients.dto.HeroStat;
import sntiago05.rejuv.domain.HeroTier;
import sntiago05.rejuv.domain.MetaHero;
import sntiago05.rejuv.domain.MetaTierList;

@Service
public class MetaService {

    static final int MIN_MATCHES = 100;

    private static final Comparator<MetaHero> COMPARATOR = Comparator
            .comparingDouble(MetaHero::winRatePercent).reversed()
            .thenComparingInt(MetaHero::heroId);

    private final DeadlockApiClient deadlockApiClient;
    private final GameAssets gameAssets;
    private final Clock clock;

    @Autowired
    public MetaService(DeadlockApiClient deadlockApiClient, GameAssets gameAssets) {
        this(deadlockApiClient, gameAssets, Clock.systemUTC());
    }

    MetaService(DeadlockApiClient deadlockApiClient, GameAssets gameAssets, Clock clock) {
        this.deadlockApiClient = deadlockApiClient;
        this.gameAssets = gameAssets;
        this.clock = clock;
    }

    /**
     * @param rankTier null = all ranks, else 1..11
     * @throws IllegalArgumentException for a tier outside 1..11 (before any call)
     * Lets DeadlockApiUnavailableException / DeadlockApiException propagate.
     */
    public MetaTierList getTierList(Integer rankTier) {
        ServiceSupport.validateRankTier(rankTier);

        long minUnixTimestamp = ServiceSupport.startOfUtcDayMinus(clock, ServiceSupport.WINDOW);

        List<HeroStat> stats = deadlockApiClient.getHeroStats(rankTier, minUnixTimestamp);
        Map<Integer, String> heroNames = gameAssets.heroNames();

        Set<Integer> seenHeroIds = new HashSet<>();
        List<MetaHero> metaHeroes = new ArrayList<>();

        for (HeroStat stat : stats) {
            if (stat.matches() < MIN_MATCHES) {
                continue;
            }
            if (!seenHeroIds.add(stat.heroId())) {
                continue;
            }
            double winRatePercent = stat.wins() * 100.0 / stat.matches();
            HeroTier tier = HeroTier.of(winRatePercent);
            String heroName = heroNames.get(stat.heroId());
            metaHeroes.add(new MetaHero(stat.heroId(), heroName, stat.matches(), winRatePercent, tier));
        }

        metaHeroes.sort(COMPARATOR);
        return new MetaTierList(rankTier, MIN_MATCHES, metaHeroes);
    }
}
