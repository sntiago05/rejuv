package sntiago05.rejuv.services;

import java.time.Clock;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import sntiago05.rejuv.clients.DeadlockApiClient;
import sntiago05.rejuv.clients.dto.HeroAsset;
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

    static final int RECENT_MATCHES = 10;
    static final int TOP_HEROES = 3;
    static final Duration TOP_HEROES_WINDOW = Duration.ofDays(30);

    private final LinkedAccountRepository repository;
    private final DeadlockApiClient deadlockApiClient;
    private final Clock clock;

    @Autowired
    public ProfileService(LinkedAccountRepository repository, DeadlockApiClient deadlockApiClient) {
        this(repository, deadlockApiClient, Clock.systemUTC());
    }

    ProfileService(LinkedAccountRepository repository, DeadlockApiClient deadlockApiClient, Clock clock) {
        this.repository = repository;
        this.deadlockApiClient = deadlockApiClient;
        this.clock = clock;
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
        List<HeroAsset> heroes = deadlockApiClient.getHeroes();

        Integer rankTier = null;
        String rankName = null;
        int subrank = 0;
        if (rank.badge() != 0) {
            int tier = rank.rank();
            rankTier = tier;
            subrank = rank.subrank();
            rankName = ranks.stream().filter(asset -> asset.tier() == tier).map(RankAsset::name).findFirst().orElse(null);
        }

        List<MatchHistoryEntry> recent = history.subList(0, Math.min(RECENT_MATCHES, history.size()));
        int recentMatches = recent.size();
        int recentWins = (int) recent.stream().filter(ProfileService::isWin).count();
        int winRatePercent = (int) Math.round(recentWins * 100.0 / recentMatches);
        double avgKills = recent.stream().mapToInt(MatchHistoryEntry::playerKills).average().orElse(0);
        double avgDeaths = recent.stream().mapToInt(MatchHistoryEntry::playerDeaths).average().orElse(0);
        double avgAssists = recent.stream().mapToInt(MatchHistoryEntry::playerAssists).average().orElse(0);

        return new ProfileSummary(accountId, rankTier, rankName, subrank,
                recentMatches, recentWins, winRatePercent, avgKills, avgDeaths, avgAssists,
                topHeroes(history, heroes));
    }

    private List<HeroUsage> topHeroes(List<MatchHistoryEntry> history, List<HeroAsset> heroes) {
        long windowStart = clock.instant().minus(TOP_HEROES_WINDOW).getEpochSecond();
        Map<Integer, Long> countsByHero = history.stream()
                .filter(entry -> entry.startTime() >= windowStart)
                .collect(Collectors.groupingBy(MatchHistoryEntry::heroId, Collectors.counting()));
        // toMap rejects null values, so heroes without a name are skipped (their name stays null).
        Map<Integer, String> namesById = heroes.stream()
                .filter(hero -> hero.name() != null)
                .collect(Collectors.toMap(HeroAsset::id, HeroAsset::name, (first, second) -> first));
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
