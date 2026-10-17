package sntiago05.rejuv.services;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import sntiago05.rejuv.clients.DeadlockApiClient;
import sntiago05.rejuv.clients.dto.HeroAsset;
import sntiago05.rejuv.clients.dto.HeroStat;
import sntiago05.rejuv.domain.HeroTier;
import sntiago05.rejuv.domain.MetaHero;
import sntiago05.rejuv.domain.MetaTierList;

@Service
public class MetaService {

    static final int MIN_MATCHES = 100;
    static final Duration WINDOW = Duration.ofDays(30);
    static final int MIN_RANK_TIER = 1;
    static final int MAX_RANK_TIER = 11;

    private static final Comparator<MetaHero> COMPARATOR = Comparator
            .comparingDouble(MetaHero::winRatePercent).reversed()
            .thenComparingInt(MetaHero::heroId);

    private final DeadlockApiClient deadlockApiClient;
    private final Clock clock;

    @Autowired
    public MetaService(DeadlockApiClient deadlockApiClient) {
        this(deadlockApiClient, Clock.systemUTC());
    }

    MetaService(DeadlockApiClient deadlockApiClient, Clock clock) {
        this.deadlockApiClient = deadlockApiClient;
        this.clock = clock;
    }

    /**
     * @param rankTier null = all ranks, else 1..11
     * @throws IllegalArgumentException for a tier outside 1..11 (before any call)
     * Lets DeadlockApiUnavailableException / DeadlockApiException propagate.
     */
    public MetaTierList getTierList(Integer rankTier) {
        if (rankTier != null && (rankTier < MIN_RANK_TIER || rankTier > MAX_RANK_TIER)) {
            throw new IllegalArgumentException("rankTier must be in " + MIN_RANK_TIER + ".." + MAX_RANK_TIER
                    + ", was " + rankTier);
        }

        LocalDate todayUtc = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
        long minUnixTimestamp = todayUtc.atStartOfDay(ZoneOffset.UTC).minus(WINDOW).toEpochSecond();

        List<HeroStat> stats = deadlockApiClient.getHeroStats(rankTier, minUnixTimestamp);
        List<HeroAsset> heroesAsset = deadlockApiClient.getHeroes();

        Map<Integer, String> heroNames = heroesAsset.stream()
                .collect(Collectors.toMap(HeroAsset::id, HeroAsset::name, (first, second) -> first));

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
