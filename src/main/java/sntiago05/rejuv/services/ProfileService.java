package sntiago05.rejuv.services;

import java.time.Clock;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.OptionalLong;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import sntiago05.rejuv.clients.DeadlockApiClient;
import sntiago05.rejuv.clients.dto.MatchHistoryEntry;
import sntiago05.rejuv.clients.dto.PlayerRank;
import sntiago05.rejuv.clients.dto.RankAsset;
import sntiago05.rejuv.config.CacheConfig;
import sntiago05.rejuv.domain.HeroUsage;
import sntiago05.rejuv.domain.LinkedAccount;
import sntiago05.rejuv.domain.ProfileSummary;
import sntiago05.rejuv.repositories.LinkedAccountRepository;

@Service
public class ProfileService {

    private static final Logger log = LoggerFactory.getLogger(ProfileService.class);

    static final int RECENT_MATCHES = 10;
    static final int TOP_HEROES = 3;
    static final Duration TOP_HEROES_WINDOW = Duration.ofDays(30);

    private final LinkedAccountRepository repository;
    private final DeadlockApiClient deadlockApiClient;
    private final GameAssets gameAssets;
    private final RankPercentileService rankPercentileService;
    private final Clock clock;

    @Autowired
    public ProfileService(LinkedAccountRepository repository, DeadlockApiClient deadlockApiClient,
                          GameAssets gameAssets,
                          @Autowired(required = false) RankPercentileService rankPercentileService) {
        this(repository, deadlockApiClient, gameAssets,
                rankPercentileService != null ? rankPercentileService : new RankPercentileService(deadlockApiClient),
                Clock.systemUTC());
    }

    ProfileService(LinkedAccountRepository repository, DeadlockApiClient deadlockApiClient, GameAssets gameAssets,
                   RankPercentileService rankPercentileService, Clock clock) {
        this.repository = repository;
        this.deadlockApiClient = deadlockApiClient;
        this.gameAssets = gameAssets;
        this.rankPercentileService = rankPercentileService;
        this.clock = clock;
    }

    ProfileService(LinkedAccountRepository repository, DeadlockApiClient deadlockApiClient, GameAssets gameAssets,
                   Clock clock) {
        this(repository, deadlockApiClient, gameAssets, new RankPercentileService(deadlockApiClient), clock);
    }

    /** Linked Deadlock account of the Discord user, or empty. Database only, no API call. */
    public OptionalLong findLinkedAccountId(long discordUserId) {
        return repository.findById(discordUserId)
                .map(LinkedAccount::getAccountId)
                .map(OptionalLong::of)
                .orElseGet(OptionalLong::empty);
    }

    /**
     * Builds the profile summary. Calls the client sequentially: history, rank, ranks asset, heroes asset.
     *
     * @throws NoMatchHistoryException when the match history is empty (not cached)
     * @throws sntiago05.rejuv.clients.DeadlockApiException when an API call fails (not cached)
     */
    @Cacheable(cacheNames = CacheConfig.PROFILE_SUMMARY, sync = true)
    public ProfileSummary getProfile(long accountId) {
        List<MatchHistoryEntry> history = deadlockApiClient.getMatchHistory(accountId);
        if (history.isEmpty()) {
            throw new NoMatchHistoryException(accountId);
        }
        PlayerRank rank = deadlockApiClient.getPlayerRank(accountId);
        List<RankAsset> ranks = deadlockApiClient.getRanks();
        Map<Integer, String> heroNames = gameAssets.heroNames();

        Integer rankTier = null;
        String rankName = null;
        int subrank = 0;
        if (rank.badge() != 0) {
            int tier = rank.rank();
            rankTier = tier;
            subrank = rank.subrank();
            rankName = ranks.stream().filter(asset -> asset.tier() == tier).map(RankAsset::name).findFirst().orElse(null);
        }

        // One pass over the recent matches (the history is non-empty here, so recentMatches > 0).
        int recentMatches = Math.min(RECENT_MATCHES, history.size());
        int recentWins = 0;
        long kills = 0;
        long deaths = 0;
        long assists = 0;
        for (MatchHistoryEntry entry : history.subList(0, recentMatches)) {
            if (isWin(entry)) {
                recentWins++;
            }
            kills += entry.playerKills();
            deaths += entry.playerDeaths();
            assists += entry.playerAssists();
        }
        int winRatePercent = (int) Math.round(recentWins * 100.0 / recentMatches);

        Double rankPercentile = null;
        if (rank.badge() != 0) {
            try {
                OptionalDouble p = rankPercentileService.percentile(rank);
                if (p.isPresent()) {
                    rankPercentile = p.getAsDouble();
                }
            } catch (Exception ex) {
                log.warn("Failed to compute rank percentile for account {}: {}", accountId, ex.getMessage());
            }
        }

        return new ProfileSummary(accountId, rankTier, rankName, subrank,
                recentMatches, recentWins, winRatePercent,
                (double) kills / recentMatches, (double) deaths / recentMatches, (double) assists / recentMatches,
                topHeroes(history, heroNames), rankPercentile);
    }

    private List<HeroUsage> topHeroes(List<MatchHistoryEntry> history, Map<Integer, String> namesById) {
        long windowStart = clock.instant().minus(TOP_HEROES_WINDOW).getEpochSecond();
        Map<Integer, Long> countsByHero = history.stream()
                .filter(entry -> entry.startTime() >= windowStart)
                .collect(Collectors.groupingBy(MatchHistoryEntry::heroId, Collectors.counting()));
        return countsByHero.entrySet().stream()
                .sorted(Map.Entry.<Integer, Long>comparingByValue(Comparator.reverseOrder())
                        .thenComparing(Map.Entry.comparingByKey()))
                .limit(TOP_HEROES)
                .map(entry -> new HeroUsage(entry.getKey(), namesById.get(entry.getKey()), entry.getValue().intValue()))
                .toList();
    }

    // matchResult == playerTeam; playerMatchOutcome is 0 for older matches, so it is not used.
    private static boolean isWin(MatchHistoryEntry entry) {
        return entry.matchResult() == entry.playerTeam();
    }
}
