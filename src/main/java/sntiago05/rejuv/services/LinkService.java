package sntiago05.rejuv.services;

import java.time.Instant;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sntiago05.rejuv.clients.DeadlockApiClient;
import sntiago05.rejuv.domain.LinkedAccount;
import sntiago05.rejuv.repositories.LinkedAccountRepository;
import sntiago05.rejuv.repositories.MatchAnalysisRepository;

@Service
public class LinkService {

    private final DeadlockApiClient deadlockApiClient;
    private final LinkedAccountRepository repository;
    private final MatchAnalysisRepository matchAnalysisRepository;

    public LinkService(DeadlockApiClient deadlockApiClient, LinkedAccountRepository repository,
                       MatchAnalysisRepository matchAnalysisRepository) {
        this.deadlockApiClient = deadlockApiClient;
        this.repository = repository;
        this.matchAnalysisRepository = matchAnalysisRepository;
    }

    /**
     * Verifies match history, then upserts the link. Returns nothing; the caller already knows the account id.
     *
     * @throws NoMatchHistoryException when deadlock-api has no match for the account (nothing is saved)
     * @throws sntiago05.rejuv.clients.DeadlockApiException when the API call fails (nothing is saved)
     */
    public void link(long discordUserId, long accountId) {
        if (deadlockApiClient.getMatchHistory(accountId).isEmpty()) {
            throw new NoMatchHistoryException(accountId);
        }
        repository.save(new LinkedAccount(discordUserId, accountId, Instant.now()));
    }

    /** @return true if a link existed and was deleted, false if the user had no link. */
    @Transactional
    public boolean unlink(long discordUserId) {
        Optional<LinkedAccount> link = repository.findById(discordUserId);
        if (link.isEmpty()) {
            return false;
        }
        long accountId = link.get().getAccountId();
        repository.deleteById(discordUserId);
        matchAnalysisRepository.deleteByAccountIdIfNotLinked(accountId);
        return true;
    }
}
