package sntiago05.rejuv.repositories;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;
import sntiago05.rejuv.domain.MatchAnalysis;

public interface MatchAnalysisRepository extends JpaRepository<MatchAnalysis, Long> {

    Optional<MatchAnalysis> findByAccountIdAndMatchId(long accountId, long matchId);

    Optional<MatchAnalysis> findByPublicId(UUID publicId);

    /** Ids among matchIds that already have a stored analysis for the account (one query). */
    @Query("select m.matchId from MatchAnalysis m where m.accountId = :accountId and m.matchId in :matchIds")
    List<Long> findAnalyzedMatchIds(@Param("accountId") long accountId, @Param("matchIds") Collection<Long> matchIds);

    /** Deletes the analyses of the account unless some linked_account row still references the account. Returns the deleted row count. */
    @Transactional
    @Modifying
    @Query("delete from MatchAnalysis a where a.accountId = :accountId "
            + "and not exists (select 1 from LinkedAccount l where l.accountId = :accountId)")
    int deleteByAccountIdIfNotLinked(@Param("accountId") long accountId);
}
