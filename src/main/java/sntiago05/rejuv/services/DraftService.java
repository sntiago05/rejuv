package sntiago05.rejuv.services;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import sntiago05.rejuv.clients.DeadlockApiClient;
import sntiago05.rejuv.clients.dto.HeroAsset;
import sntiago05.rejuv.clients.dto.HeroCounterStat;
import sntiago05.rejuv.clients.dto.HeroSynergyStat;

@Service
public class DraftService {

    public static final int MIN_PAIR_MATCHES = 50;
    public static final double SHRINKAGE_K = 50.0;
    private static final double MIN_PROBABILITY = 0.05;
    private static final double MAX_PROBABILITY = 0.95;
    private static final int MIN_CONFIDENCE_PAIRS = 3;
    private static final int MAX_SUGGESTIONS = 3;
    private static final int MAX_ALLIES_FOR_SUGGESTION = 5;

    public record DraftRequest(List<Integer> allyHeroIds, List<Integer> enemyHeroIds, Integer rankTier) {
        public DraftRequest {
            allyHeroIds = allyHeroIds != null ? List.copyOf(allyHeroIds) : List.of();
            enemyHeroIds = enemyHeroIds != null ? List.copyOf(enemyHeroIds) : List.of();
        }
    }

    public record DraftResult(double winProbability, int allyCount, int enemyCount,
                              List<PickSuggestion> suggestions, boolean lowConfidence) {
        public DraftResult {
            suggestions = List.copyOf(suggestions);
        }
    }

    public record PickSuggestion(int heroId, double winProbability, long sampleMatches) {}

    private record PairKey(int h1, int h2) {
        static PairKey of(int a, int b) {
            return new PairKey(Math.min(a, b), Math.max(a, b));
        }
    }

    private record CounterKey(int allyHeroId, int enemyHeroId) {}

    private record Evaluation(double probability, int qualifyingPairs) {}

    private static final Comparator<PickSuggestion> SUGGESTION_COMPARATOR = (a, b) -> {
        int cmp = Double.compare(b.winProbability(), a.winProbability());
        if (cmp != 0) {
            return cmp;
        }
        cmp = Long.compare(b.sampleMatches(), a.sampleMatches());
        if (cmp != 0) {
            return cmp;
        }
        return Integer.compare(a.heroId(), b.heroId());
    };

    private final DeadlockApiClient deadlockApiClient;
    private final GameAssets gameAssets;
    private final Clock clock;

    @Autowired
    public DraftService(DeadlockApiClient deadlockApiClient, GameAssets gameAssets) {
        this(deadlockApiClient, gameAssets, Clock.systemUTC());
    }

    DraftService(DeadlockApiClient deadlockApiClient, GameAssets gameAssets, Clock clock) {
        this.deadlockApiClient = Objects.requireNonNull(deadlockApiClient, "deadlockApiClient must not be null");
        this.gameAssets = Objects.requireNonNull(gameAssets, "gameAssets must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    public DraftResult estimate(DraftRequest request) {
        ServiceSupport.validateRankTier(request.rankTier());

        long minUnixTimestamp = ServiceSupport.startOfUtcDayMinus(clock, ServiceSupport.WINDOW);

        List<HeroSynergyStat> synergyStats = deadlockApiClient.getHeroSynergyStats(request.rankTier(), minUnixTimestamp);
        List<HeroCounterStat> counterStats = deadlockApiClient.getHeroCounterStats(request.rankTier(), minUnixTimestamp);

        Map<PairKey, HeroSynergyStat> synergyMap = HashMap.newHashMap(synergyStats.size());
        for (HeroSynergyStat stat : synergyStats) {
            if (stat.heroId1() != stat.heroId2()) {
                synergyMap.putIfAbsent(PairKey.of(stat.heroId1(), stat.heroId2()), stat);
            }
        }

        Map<CounterKey, HeroCounterStat> counterMap = HashMap.newHashMap(counterStats.size());
        for (HeroCounterStat stat : counterStats) {
            counterMap.putIfAbsent(new CounterKey(stat.heroId(), stat.enemyHeroId()), stat);
        }

        List<Integer> allies = request.allyHeroIds();
        List<Integer> enemies = request.enemyHeroIds();

        Evaluation eval = evaluate(allies, enemies, synergyMap, counterMap);
        boolean lowConfidence = eval.qualifyingPairs() < MIN_CONFIDENCE_PAIRS;

        List<PickSuggestion> suggestions = new ArrayList<>();
        if (allies.size() < MAX_ALLIES_FOR_SUGGESTION) {
            Set<Integer> picked = new HashSet<>();
            picked.addAll(allies);
            picked.addAll(enemies);

            List<HeroAsset> namedHeroes = gameAssets.namedHeroes();
            for (HeroAsset hero : namedHeroes) {
                if (picked.contains(hero.id())) {
                    continue;
                }
                List<Integer> candidateAllies = new ArrayList<>(allies.size() + 1);
                candidateAllies.addAll(allies);
                candidateAllies.add(hero.id());

                Evaluation candEval = evaluate(candidateAllies, enemies, synergyMap, counterMap);
                long sampleMatches = computeSampleMatchesForCandidate(hero.id(), allies, enemies, synergyMap, counterMap);
                suggestions.add(new PickSuggestion(hero.id(), candEval.probability(), sampleMatches));
            }
            suggestions = ServiceSupport.top(suggestions, SUGGESTION_COMPARATOR, MAX_SUGGESTIONS);
        }

        return new DraftResult(eval.probability(), allies.size(), enemies.size(), suggestions, lowConfidence);
    }

    private static double computeDelta(long wins, long matches) {
        double p = (wins + SHRINKAGE_K * 0.5) / (matches + SHRINKAGE_K);
        return Math.log(p / (1.0 - p));
    }

    private static Evaluation evaluate(List<Integer> allies, List<Integer> enemies,
                                       Map<PairKey, HeroSynergyStat> synergyMap,
                                       Map<CounterKey, HeroCounterStat> counterMap) {
        double synergyDeltaSum = 0.0;
        int synergyPairCount = 0;

        for (int i = 0; i < allies.size(); i++) {
            int a1 = allies.get(i);
            for (int j = i + 1; j < allies.size(); j++) {
                int a2 = allies.get(j);
                HeroSynergyStat stat = synergyMap.get(PairKey.of(a1, a2));
                if (stat != null && stat.matchesPlayed() >= MIN_PAIR_MATCHES) {
                    synergyDeltaSum += computeDelta(stat.wins(), stat.matchesPlayed());
                    synergyPairCount++;
                }
            }
        }

        double counterDeltaSum = 0.0;
        int counterPairCount = 0;

        for (int ally : allies) {
            for (int enemy : enemies) {
                CounterKey directKey = new CounterKey(ally, enemy);
                HeroCounterStat stat = counterMap.get(directKey);
                if (stat != null) {
                    if (stat.matchesPlayed() >= MIN_PAIR_MATCHES) {
                        counterDeltaSum += computeDelta(stat.wins(), stat.matchesPlayed());
                        counterPairCount++;
                    }
                } else {
                    CounterKey reverseKey = new CounterKey(enemy, ally);
                    HeroCounterStat revStat = counterMap.get(reverseKey);
                    if (revStat != null && revStat.matchesPlayed() >= MIN_PAIR_MATCHES) {
                        long allyWins = revStat.matchesPlayed() - revStat.wins();
                        counterDeltaSum += computeDelta(allyWins, revStat.matchesPlayed());
                        counterPairCount++;
                    }
                }
            }
        }

        double synergyMean = synergyPairCount > 0 ? synergyDeltaSum / synergyPairCount : 0.0;
        double counterMean = counterPairCount > 0 ? counterDeltaSum / counterPairCount : 0.0;

        double totalLogOdds = synergyMean + counterMean;
        double probability = 1.0 / (1.0 + Math.exp(-totalLogOdds));
        double clamped = Math.max(MIN_PROBABILITY, Math.min(MAX_PROBABILITY, probability));

        int totalQualifyingPairs = synergyPairCount + counterPairCount;
        return new Evaluation(clamped, totalQualifyingPairs);
    }

    private static long computeSampleMatchesForCandidate(int candidateId, List<Integer> allies, List<Integer> enemies,
                                                         Map<PairKey, HeroSynergyStat> synergyMap,
                                                         Map<CounterKey, HeroCounterStat> counterMap) {
        long totalMatches = 0;
        for (int ally : allies) {
            HeroSynergyStat stat = synergyMap.get(PairKey.of(candidateId, ally));
            if (stat != null && stat.matchesPlayed() >= MIN_PAIR_MATCHES) {
                totalMatches += stat.matchesPlayed();
            }
        }
        for (int enemy : enemies) {
            HeroCounterStat stat = counterMap.get(new CounterKey(candidateId, enemy));
            if (stat != null && stat.matchesPlayed() >= MIN_PAIR_MATCHES) {
                totalMatches += stat.matchesPlayed();
            } else {
                HeroCounterStat revStat = counterMap.get(new CounterKey(enemy, candidateId));
                if (revStat != null && revStat.matchesPlayed() >= MIN_PAIR_MATCHES) {
                    totalMatches += revStat.matchesPlayed();
                }
            }
        }
        return totalMatches;
    }
}
