package sntiago05.rejuv.services;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Service;
import sntiago05.rejuv.clients.DeadlockApiClient;
import sntiago05.rejuv.clients.dto.MatchHistoryEntry;
import sntiago05.rejuv.domain.MatchFilter;
import sntiago05.rejuv.domain.MatchSummary;

@Service
public class MatchSearchService {

    public static final int DEFAULT_LIMIT = 10;

    private final DeadlockApiClient deadlockApiClient;
    private final GameAssets gameAssets;

    public MatchSearchService(DeadlockApiClient deadlockApiClient, GameAssets gameAssets) {
        this.deadlockApiClient = deadlockApiClient;
        this.gameAssets = gameAssets;
    }

    /**
     * Newest first, at most limit entries. Empty history gives an empty list.
     * Lets DeadlockApi exceptions propagate.
     */
    public List<MatchSummary> find(long accountId, MatchFilter filter, int limit) {
        List<MatchHistoryEntry> history = deadlockApiClient.getMatchHistory(accountId);
        if (history.isEmpty() || limit <= 0) {
            return List.of();
        }

        Map<Integer, String> heroNames = gameAssets.heroNames();
        List<MatchSummary> summaries = new ArrayList<>(Math.min(history.size(), limit));
        for (MatchHistoryEntry entry : history) {
            if (matches(entry, filter)) {
                boolean win = entry.matchResult() == entry.playerTeam();
                String heroName = heroNames.get(entry.heroId());
                summaries.add(new MatchSummary(entry.matchId(), entry.heroId(), heroName,
                        entry.startTime(), entry.matchDurationS(), win,
                        entry.playerKills(), entry.playerDeaths(), entry.playerAssists()));
                if (summaries.size() >= limit) {
                    break;
                }
            }
        }
        return List.copyOf(summaries);
    }

    /**
     * The newest match that passes the filter (a matchId filter picks exactly that match).
     */
    public Optional<MatchHistoryEntry> select(long accountId, MatchFilter filter) {
        List<MatchHistoryEntry> history = deadlockApiClient.getMatchHistory(accountId);
        if (history.isEmpty()) {
            return Optional.empty();
        }

        for (MatchHistoryEntry entry : history) {
            if (matches(entry, filter)) {
                return Optional.of(entry);
            }
        }
        return Optional.empty();
    }

    private static boolean matches(MatchHistoryEntry entry, MatchFilter filter) {
        if (filter == null) {
            return true;
        }
        if (filter.matchId() != null && entry.matchId() != filter.matchId()) {
            return false;
        }
        if (filter.heroId() != null && entry.heroId() != filter.heroId()) {
            return false;
        }
        if (filter.win() != null) {
            boolean win = entry.matchResult() == entry.playerTeam();
            if (win != filter.win()) {
                return false;
            }
        }
        return true;
    }
}
