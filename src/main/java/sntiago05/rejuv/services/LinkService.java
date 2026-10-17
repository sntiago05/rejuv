package sntiago05.rejuv.services;

import java.time.Instant;

import org.springframework.stereotype.Service;
import sntiago05.rejuv.clients.DeadlockApiClient;
import sntiago05.rejuv.domain.LinkedAccount;
import sntiago05.rejuv.repositories.LinkedAccountRepository;

@Service
public class LinkService {

    private final DeadlockApiClient deadlockApiClient;
    private final LinkedAccountRepository repository;

    public LinkService(DeadlockApiClient deadlockApiClient, LinkedAccountRepository repository) {
        this.deadlockApiClient = deadlockApiClient;
        this.repository = repository;
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
    public boolean unlink(long discordUserId) {
        if (!repository.existsById(discordUserId)) {
            return false;
        }
        repository.deleteById(discordUserId);
        return true;
    }
}
