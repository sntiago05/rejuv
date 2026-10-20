package sntiago05.rejuv.services;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import sntiago05.rejuv.clients.DeadlockApiClient;
import sntiago05.rejuv.clients.dto.HeroSynergyStat;

@Service
public class SynergyService {

    public static final int MIN_PAIR_MATCHES = 50;
    public static final double SHRINKAGE_K = 50.0;
    static final int MAX_PARTNERS = 5;

    public record SynergyResult(int heroId, List<Partner> best, List<Partner> worst) {
        public SynergyResult {
            best = List.copyOf(best);
            worst = List.copyOf(worst);
        }
    }

    public record Partner(int heroId, double winRate, long matches) {}

    private static final Comparator<Partner> BEST_COMPARATOR = (a, b) -> {
        int cmp = Double.compare(b.winRate(), a.winRate());
        if (cmp != 0) {
            return cmp;
        }
        cmp = Long.compare(b.matches(), a.matches());
        if (cmp != 0) {
            return cmp;
        }
        return Integer.compare(a.heroId(), b.heroId());
    };

    private static final Comparator<Partner> WORST_COMPARATOR = (a, b) -> {
        int cmp = Double.compare(a.winRate(), b.winRate());
        if (cmp != 0) {
            return cmp;
        }
        cmp = Long.compare(b.matches(), a.matches());
        if (cmp != 0) {
            return cmp;
        }
        return Integer.compare(a.heroId(), b.heroId());
    };

    private final DeadlockApiClient deadlockApiClient;
    private final Clock clock;

    @Autowired
    public SynergyService(DeadlockApiClient deadlockApiClient) {
        this(deadlockApiClient, Clock.systemUTC());
    }

    SynergyService(DeadlockApiClient deadlockApiClient, Clock clock) {
        this.deadlockApiClient = Objects.requireNonNull(deadlockApiClient, "deadlockApiClient must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    public SynergyResult forHero(int heroId, Integer rankTier) {
        ServiceSupport.validateRankTier(rankTier);

        long minUnixTimestamp = ServiceSupport.startOfUtcDayMinus(clock, ServiceSupport.WINDOW);
        List<HeroSynergyStat> stats = deadlockApiClient.getHeroSynergyStats(rankTier, minUnixTimestamp);

        Map<Integer, HeroSynergyStat> partnerStats = new HashMap<>();
        for (HeroSynergyStat stat : stats) {
            int partnerId = -1;
            if (stat.heroId1() == heroId && stat.heroId2() != heroId) {
                partnerId = stat.heroId2();
            } else if (stat.heroId2() == heroId && stat.heroId1() != heroId) {
                partnerId = stat.heroId1();
            }
            if (partnerId != -1) {
                partnerStats.putIfAbsent(partnerId, stat);
            }
        }

        List<Partner> candidates = new ArrayList<>();
        for (Map.Entry<Integer, HeroSynergyStat> entry : partnerStats.entrySet()) {
            HeroSynergyStat stat = entry.getValue();
            if (stat.matchesPlayed() >= MIN_PAIR_MATCHES) {
                double winRate = (stat.wins() + SHRINKAGE_K * 0.5) / (stat.matchesPlayed() + SHRINKAGE_K);
                candidates.add(new Partner(entry.getKey(), winRate, stat.matchesPlayed()));
            }
        }

        List<Partner> best = ServiceSupport.top(new ArrayList<>(candidates), BEST_COMPARATOR, MAX_PARTNERS);
        List<Partner> worst = ServiceSupport.top(new ArrayList<>(candidates), WORST_COMPARATOR, MAX_PARTNERS);

        return new SynergyResult(heroId, best, worst);
    }
}
