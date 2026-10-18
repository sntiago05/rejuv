package sntiago05.rejuv.repositories;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import sntiago05.rejuv.TestcontainersConfiguration;
import sntiago05.rejuv.domain.AnalysisUsage;

@SpringBootTest(properties = {"bot.token=", "bot.dev-guild-id="})
@Import(TestcontainersConfiguration.class)
class AnalysisUsageRepositoryTest {

    private static final long USER_A = 100000001L;
    private static final long USER_B = 100000002L;
    private static final long MATCH_1 = 900000001L;
    private static final long MATCH_2 = 900000002L;
    private static final long MATCH_3 = 900000003L;

    @Autowired
    private AnalysisUsageRepository repository;

    @BeforeEach
    void cleanTable() {
        repository.deleteAll();
    }

    @Test
    void savesAndReadsBackAllFields() {
        Instant createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        AnalysisUsage saved = repository.save(new AnalysisUsage(USER_A, MATCH_1, createdAt));

        assertThat(saved.getId()).isNotNull();

        Optional<AnalysisUsage> found = repository.findById(saved.getId());
        assertThat(found).hasValueSatisfying(usage -> {
            assertThat(usage.getId()).isEqualTo(saved.getId());
            assertThat(usage.getDiscordUserId()).isEqualTo(USER_A);
            assertThat(usage.getMatchId()).isEqualTo(MATCH_1);
            assertThat(usage.getCreatedAt()).isEqualTo(createdAt);
        });
    }

    @Test
    void countCountsOnlyThatUserAndOnlyRowsInWindow() {
        Instant weekStart = Instant.parse("2026-10-19T00:00:00Z");

        // Before window for User A (1 second before weekStart)
        repository.save(new AnalysisUsage(USER_A, MATCH_1, weekStart.minus(1, ChronoUnit.SECONDS)));
        // Exact start boundary for User A
        repository.save(new AnalysisUsage(USER_A, MATCH_2, weekStart));
        // Inside window for User A
        repository.save(new AnalysisUsage(USER_A, MATCH_3, weekStart.plus(1, ChronoUnit.DAYS)));
        // Inside window for User B
        repository.save(new AnalysisUsage(USER_B, MATCH_1, weekStart.plus(2, ChronoUnit.DAYS)));

        long userACount = repository.countByDiscordUserIdAndCreatedAtGreaterThanEqual(USER_A, weekStart);
        assertThat(userACount).isEqualTo(2);

        long userBCount = repository.countByDiscordUserIdAndCreatedAtGreaterThanEqual(USER_B, weekStart);
        assertThat(userBCount).isEqualTo(1);

        long unknownUserCount = repository.countByDiscordUserIdAndCreatedAtGreaterThanEqual(999999L, weekStart);
        assertThat(unknownUserCount).isEqualTo(0);
    }
}
