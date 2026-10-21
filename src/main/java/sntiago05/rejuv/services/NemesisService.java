package sntiago05.rejuv.services;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import sntiago05.rejuv.clients.DeadlockApiClient;
import sntiago05.rejuv.clients.dto.EnemyStat;

@Service
public class NemesisService {

    private static final Logger log = LoggerFactory.getLogger(NemesisService.class);

    public static final int TOP_NEMESES = 5;
    public static final int MIN_NEMESIS_MATCHES = 5;
    public static final Duration WINDOW = Duration.ofDays(90);

    public record NemesisLine(long accountId, String name, long matches, long wins, double winRate) {}

    public record NemesisSummary(long accountId, List<NemesisLine> nemeses) {
        public NemesisSummary {
            nemeses = List.copyOf(nemeses);
        }
    }

    private static final Comparator<EnemyCandidate> COMPARATOR = Comparator
            .comparingDouble(EnemyCandidate::winRate)
            .thenComparing(Comparator.comparingLong(EnemyCandidate::matches).reversed())
            .thenComparingLong(EnemyCandidate::accountId);

    private record EnemyCandidate(long accountId, long matches, long wins, double winRate) {}

    private final DeadlockApiClient deadlockApiClient;
    private final Clock clock;

    @Autowired
    public NemesisService(DeadlockApiClient deadlockApiClient) {
        this(deadlockApiClient, Clock.systemUTC());
    }

    NemesisService(DeadlockApiClient deadlockApiClient, Clock clock) {
        this.deadlockApiClient = deadlockApiClient;
        this.clock = clock;
    }

    /**
     * Finds the opponents you lose to most often in the 90-day window.
     * Lets DeadlockApiUnavailableException / DeadlockApiException of the enemy-stats query propagate.
     * Persona names are best effort: if that second call fails, lines have null names.
     */
    public NemesisSummary nemeses(long accountId) {
        long minUnixTimestamp = ServiceSupport.startOfUtcDayMinus(clock, WINDOW);
        List<EnemyStat> stats = deadlockApiClient.getEnemyStats(accountId, minUnixTimestamp);

        List<EnemyCandidate> candidates = new ArrayList<>();
        for (EnemyStat stat : stats) {
            if (stat.enemyId() == accountId || stat.matches() < MIN_NEMESIS_MATCHES) {
                continue;
            }
            long matches = stat.matches();
            long wins = stat.wins();
            double winRate = wins * 100.0 / matches;
            candidates.add(new EnemyCandidate(stat.enemyId(), matches, wins, winRate));
        }

        List<EnemyCandidate> topCandidates = ServiceSupport.top(candidates, COMPARATOR, TOP_NEMESES);
        if (topCandidates.isEmpty()) {
            return new NemesisSummary(accountId, List.of());
        }

        List<Long> ids = topCandidates.stream().map(EnemyCandidate::accountId).toList();
        Map<Long, String> names = ServiceSupport.fetchPlayerNames(deadlockApiClient, ids, log, "nemeses");

        List<NemesisLine> nemeses = topCandidates.stream()
                .map(c -> new NemesisLine(c.accountId(), names.get(c.accountId()), c.matches(), c.wins(), c.winRate()))
                .toList();

        return new NemesisSummary(accountId, nemeses);
    }
}
