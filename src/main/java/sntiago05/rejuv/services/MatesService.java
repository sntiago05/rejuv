package sntiago05.rejuv.services;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import sntiago05.rejuv.clients.DeadlockApiClient;
import sntiago05.rejuv.clients.dto.MateStat;
import sntiago05.rejuv.domain.MatesSummary;
import sntiago05.rejuv.domain.Teammate;

@Service
public class MatesService {

    public static final int TOP_MATES = 5;
    public static final int MIN_MATCHES_TOGETHER = 5;
    public static final Duration WINDOW = Duration.ofDays(90);

    private static final Comparator<Teammate> COMPARATOR = Comparator
            .comparingDouble(Teammate::winRatePercent).reversed()
            .thenComparing(Comparator.comparingInt(Teammate::matches).reversed())
            .thenComparingLong(Teammate::accountId);

    private final DeadlockApiClient deadlockApiClient;
    private final Clock clock;

    @Autowired
    public MatesService(DeadlockApiClient deadlockApiClient) {
        this(deadlockApiClient, Clock.systemUTC());
    }

    MatesService(DeadlockApiClient deadlockApiClient, Clock clock) {
        this.deadlockApiClient = deadlockApiClient;
        this.clock = clock;
    }

    /**
     * Finds the best teammates of the given account in the 90-day window.
     * Lets DeadlockApiUnavailableException / DeadlockApiException propagate.
     */
    public MatesSummary getMates(long accountId) {
        long minUnixTimestamp = ServiceSupport.startOfUtcDayMinus(clock, WINDOW);
        List<MateStat> stats = deadlockApiClient.getMateStats(accountId, minUnixTimestamp);

        List<Teammate> validTeammates = new ArrayList<>();
        for (MateStat stat : stats) {
            if (stat.mateId() == accountId || stat.matches() < MIN_MATCHES_TOGETHER) {
                continue;
            }
            int matches = Math.toIntExact(stat.matches());
            int wins = Math.toIntExact(stat.wins());
            double winRatePercent = wins * 100.0 / matches;
            validTeammates.add(new Teammate(stat.mateId(), matches, wins, winRatePercent));
        }

        List<Teammate> topTeammates = ServiceSupport.top(validTeammates, COMPARATOR, TOP_MATES);
        return new MatesSummary(accountId, topTeammates);
    }
}
