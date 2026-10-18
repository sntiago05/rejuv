package sntiago05.rejuv.repositories;

import java.time.Instant;

import org.springframework.data.jpa.repository.JpaRepository;
import sntiago05.rejuv.domain.AnalysisUsage;

public interface AnalysisUsageRepository extends JpaRepository<AnalysisUsage, Long> {

    long countByDiscordUserIdAndCreatedAtGreaterThanEqual(long discordUserId, Instant createdAt);
}
