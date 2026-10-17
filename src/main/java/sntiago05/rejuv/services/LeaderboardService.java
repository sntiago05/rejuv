package sntiago05.rejuv.services;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import sntiago05.rejuv.clients.DeadlockApiClient;
import sntiago05.rejuv.clients.dto.AccountRank;
import sntiago05.rejuv.clients.dto.RankAsset;
import sntiago05.rejuv.domain.LeaderboardEntry;
import sntiago05.rejuv.domain.LinkedMember;
import sntiago05.rejuv.domain.ServerLeaderboard;
import sntiago05.rejuv.repositories.LinkedAccountRepository;

@Service
public class LeaderboardService {

    public static final int LEADERBOARD_SIZE = 10;
    static final int MAX_CANDIDATES = 500;

    private static final Comparator<LeaderboardEntry> ENTRY_COMPARATOR = Comparator
            .comparingInt(LeaderboardEntry::badge).reversed()
            .thenComparingLong(LeaderboardEntry::discordUserId);

    private final LinkedAccountRepository repository;
    private final DeadlockApiClient deadlockApiClient;

    public LeaderboardService(LinkedAccountRepository repository, DeadlockApiClient deadlockApiClient) {
        this.repository = repository;
        this.deadlockApiClient = deadlockApiClient;
    }

    /**
     * The most recently linked accounts (at most MAX_CANDIDATES), database only.
     */
    public List<LinkedMember> findCandidates() {
        return repository.findAll(PageRequest.of(0, MAX_CANDIDATES, Sort.by(Sort.Direction.DESC, "linkedAt")))
                .map(account -> new LinkedMember(account.getDiscordUserId(), account.getAccountId()))
                .getContent();
    }

    /**
     * Ranks the given members (the linked members who are in the server). One batch API call. Lets
     * DeadlockApiUnavailableException / DeadlockApiException propagate.
     */
    public ServerLeaderboard buildLeaderboard(List<LinkedMember> membersInServer) {
        if (membersInServer == null || membersInServer.isEmpty()) {
            return new ServerLeaderboard(List.of(), 0, 0);
        }

        List<Long> distinctSortedAccountIds = membersInServer.stream()
                .map(LinkedMember::accountId)
                .distinct()
                .sorted()
                .toList();

        Map<Long, AccountRank> ranksByAccountId = deadlockApiClient.getPlayerRanks(distinctSortedAccountIds);
        Map<Integer, String> rankNamesByTier = deadlockApiClient.getRanks().stream()
                .collect(Collectors.toMap(RankAsset::tier, RankAsset::name, (first, second) -> first));

        List<LeaderboardEntry> rankedEntries = new ArrayList<>();
        int unrankedCount = 0;

        for (LinkedMember member : membersInServer) {
            AccountRank rank = ranksByAccountId.get(member.accountId());
            if (rank == null || rank.badge() == 0) {
                unrankedCount++;
            } else {
                String rankName = rankNamesByTier.get(rank.rank());
                rankedEntries.add(new LeaderboardEntry(
                        member.discordUserId(),
                        member.accountId(),
                        rank.badge(),
                        rank.rank(),
                        rank.subrank(),
                        rankName));
            }
        }

        rankedEntries.sort(ENTRY_COMPARATOR);

        int rankedCount = rankedEntries.size();
        List<LeaderboardEntry> topEntries = rankedEntries.stream()
                .limit(LEADERBOARD_SIZE)
                .toList();

        return new ServerLeaderboard(topEntries, rankedCount, unrankedCount);
    }
}
