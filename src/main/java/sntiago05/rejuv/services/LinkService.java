package sntiago05.rejuv.services;

import java.time.Instant;
import java.util.Optional;

import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sntiago05.rejuv.clients.DeadlockApiClient;
import sntiago05.rejuv.config.CacheConfig;
import sntiago05.rejuv.domain.LinkedAccount;
import sntiago05.rejuv.repositories.LinkedAccountRepository;
import sntiago05.rejuv.repositories.MatchAnalysisRepository;

@Service
public class LinkService {

    private final DeadlockApiClient deadlockApiClient;
    private final LinkedAccountRepository repository;
    private final MatchAnalysisRepository matchAnalysisRepository;
    private final CacheManager cacheManager;

    public LinkService(DeadlockApiClient deadlockApiClient, LinkedAccountRepository repository,
                       MatchAnalysisRepository matchAnalysisRepository, CacheManager cacheManager) {
        this.deadlockApiClient = deadlockApiClient;
        this.repository = repository;
        this.matchAnalysisRepository = matchAnalysisRepository;
        this.cacheManager = cacheManager;
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
        Optional<LinkedAccount> existing = repository.findById(discordUserId);
        repository.save(new LinkedAccount(discordUserId, accountId, Instant.now()));
        evict(accountId);
        if (existing.isPresent() && existing.get().getAccountId() != accountId) {
            evict(existing.get().getAccountId());
        }
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
        evict(accountId);
        return true;
    }

    private void evict(long accountId) {
        if (cacheManager == null) {
            return;
        }
        evictFrom(CacheConfig.PROFILE_SUMMARY, accountId);
        evictFrom(CacheConfig.DEADLOCK_MATCH_HISTORY, accountId);
        evictFrom(CacheConfig.DEADLOCK_PLAYER_RANK, accountId);
    }

    private void evictFrom(String cacheName, long accountId) {
        Cache cache = cacheManager.getCache(cacheName);
        if (cache != null) {
            cache.evict(accountId);
        }
    }
}
