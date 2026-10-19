package sntiago05.rejuv.repositories;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import sntiago05.rejuv.TestcontainersConfiguration;
import sntiago05.rejuv.domain.LinkedAccount;
import sntiago05.rejuv.domain.MatchAnalysis;

@SpringBootTest(properties = {"bot.token=", "bot.dev-guild-id="})
@Import(TestcontainersConfiguration.class)
class MatchAnalysisRepositoryTest {

    private static final long ACCOUNT_ID = 1000001L;
    private static final long MATCH_ID = 900000001L;
    private static final int HERO_ID = 1;
    private static final String RESULT_JSON = "{\"strength\":\"Good\",\"biggest_leak\":\"Dying\",\"next_game_action\":\"Ward\",\"key_numbers\":[\"1\"]}";
    private static final String MODEL = "claude-haiku-5-5";
    private static final int INPUT_TOKENS = 1200;
    private static final int OUTPUT_TOKENS = 350;

    @Autowired
    private MatchAnalysisRepository matchAnalysisRepository;

    @Autowired
    private LinkedAccountRepository linkedAccountRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanTables() {
        matchAnalysisRepository.deleteAll();
        linkedAccountRepository.deleteAll();
    }

    @Test
    void savesAndReadsBackAllFields() {
        Instant createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        MatchAnalysis saved = matchAnalysisRepository.save(new MatchAnalysis(
                ACCOUNT_ID, MATCH_ID, HERO_ID, RESULT_JSON, MODEL, INPUT_TOKENS, OUTPUT_TOKENS, createdAt));

        assertThat(saved.getId()).isNotNull();

        Optional<MatchAnalysis> found = matchAnalysisRepository.findById(saved.getId());
        assertThat(found).hasValueSatisfying(analysis -> {
            assertThat(analysis.getId()).isEqualTo(saved.getId());
            assertThat(analysis.getAccountId()).isEqualTo(ACCOUNT_ID);
            assertThat(analysis.getMatchId()).isEqualTo(MATCH_ID);
            assertThat(analysis.getHeroId()).isEqualTo(HERO_ID);
            assertThat(analysis.getResultJson()).isEqualTo(RESULT_JSON);
            assertThat(analysis.getModel()).isEqualTo(MODEL);
            assertThat(analysis.getInputTokens()).isEqualTo(INPUT_TOKENS);
            assertThat(analysis.getOutputTokens()).isEqualTo(OUTPUT_TOKENS);
            assertThat(analysis.getCreatedAt()).isEqualTo(createdAt);
            assertThat(analysis.getPublicId()).isNotNull().isEqualTo(saved.getPublicId());
        });
    }

    @Test
    void savingDuplicateAccountAndMatchThrowsDataIntegrityViolationException() {
        Instant createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        matchAnalysisRepository.save(new MatchAnalysis(
                ACCOUNT_ID, MATCH_ID, HERO_ID, RESULT_JSON, MODEL, INPUT_TOKENS, OUTPUT_TOKENS, createdAt));

        assertThatThrownBy(() -> matchAnalysisRepository.saveAndFlush(new MatchAnalysis(
                ACCOUNT_ID, MATCH_ID, 2, "{}", MODEL, 100, 50, createdAt)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void findByAccountIdAndMatchIdReturnsHitAndMiss() {
        Instant createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        matchAnalysisRepository.save(new MatchAnalysis(
                ACCOUNT_ID, MATCH_ID, HERO_ID, RESULT_JSON, MODEL, INPUT_TOKENS, OUTPUT_TOKENS, createdAt));

        Optional<MatchAnalysis> hit = matchAnalysisRepository.findByAccountIdAndMatchId(ACCOUNT_ID, MATCH_ID);
        assertThat(hit).isPresent();
        assertThat(hit.get().getHeroId()).isEqualTo(HERO_ID);

        Optional<MatchAnalysis> missOtherAccount = matchAnalysisRepository.findByAccountIdAndMatchId(999999L, MATCH_ID);
        assertThat(missOtherAccount).isEmpty();

        Optional<MatchAnalysis> missOtherMatch = matchAnalysisRepository.findByAccountIdAndMatchId(ACCOUNT_ID, 888888L);
        assertThat(missOtherMatch).isEmpty();
    }

    @Test
    void deleteByAccountIdIfNotLinkedDeletesWhenNoLinkedAccountReferencesTheAccount() {
        Instant createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        matchAnalysisRepository.save(new MatchAnalysis(
                ACCOUNT_ID, MATCH_ID, HERO_ID, RESULT_JSON, MODEL, INPUT_TOKENS, OUTPUT_TOKENS, createdAt));

        int deleted = matchAnalysisRepository.deleteByAccountIdIfNotLinked(ACCOUNT_ID);

        assertThat(deleted).isEqualTo(1);
        assertThat(matchAnalysisRepository.findByAccountIdAndMatchId(ACCOUNT_ID, MATCH_ID)).isEmpty();
    }

    @Test
    void deleteByAccountIdIfNotLinkedDeletesNothingWhenLinkedAccountReferencesTheAccount() {
        Instant createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        matchAnalysisRepository.save(new MatchAnalysis(
                ACCOUNT_ID, MATCH_ID, HERO_ID, RESULT_JSON, MODEL, INPUT_TOKENS, OUTPUT_TOKENS, createdAt));
        linkedAccountRepository.save(new LinkedAccount(12345L, ACCOUNT_ID, createdAt));

        int deleted = matchAnalysisRepository.deleteByAccountIdIfNotLinked(ACCOUNT_ID);

        assertThat(deleted).isEqualTo(0);
        assertThat(matchAnalysisRepository.findByAccountIdAndMatchId(ACCOUNT_ID, MATCH_ID)).isPresent();
    }

    @Test
    void deleteByAccountIdIfNotLinkedDeletesOnlyTheGivenAccount() {
        Instant createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        long otherAccount = 2000002L;
        matchAnalysisRepository.save(new MatchAnalysis(
                ACCOUNT_ID, MATCH_ID, HERO_ID, RESULT_JSON, MODEL, INPUT_TOKENS, OUTPUT_TOKENS, createdAt));
        matchAnalysisRepository.save(new MatchAnalysis(
                otherAccount, MATCH_ID + 1, HERO_ID, RESULT_JSON, MODEL, INPUT_TOKENS, OUTPUT_TOKENS, createdAt));

        int deleted = matchAnalysisRepository.deleteByAccountIdIfNotLinked(ACCOUNT_ID);

        assertThat(deleted).isEqualTo(1);
        assertThat(matchAnalysisRepository.findByAccountIdAndMatchId(ACCOUNT_ID, MATCH_ID)).isEmpty();
        assertThat(matchAnalysisRepository.findByAccountIdAndMatchId(otherAccount, MATCH_ID + 1)).isPresent();
    }

    @Test
    void findAnalyzedMatchIdsReturnsOnlyAccountsOwnAnalyzedIdsAmongGiven() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        matchAnalysisRepository.save(new MatchAnalysis(ACCOUNT_ID, 101L, 1, RESULT_JSON, MODEL, 100, 50, now));
        matchAnalysisRepository.save(new MatchAnalysis(ACCOUNT_ID, 102L, 1, RESULT_JSON, MODEL, 100, 50, now));
        matchAnalysisRepository.save(new MatchAnalysis(999999L, 103L, 1, RESULT_JSON, MODEL, 100, 50, now));

        List<Long> analyzed = matchAnalysisRepository.findAnalyzedMatchIds(
                ACCOUNT_ID, java.util.List.of(101L, 103L, 104L));

        assertThat(analyzed).containsExactly(101L);
    }

    @Test
    void publicConstructorSetsNonNullPublicId() {
        MatchAnalysis entity = new MatchAnalysis(
                ACCOUNT_ID, MATCH_ID, HERO_ID, RESULT_JSON, MODEL, INPUT_TOKENS, OUTPUT_TOKENS, Instant.now());

        assertThat(entity.getPublicId()).isNotNull();
    }

    @Test
    void findByPublicIdFindsSavedEntityAndReturnsEmptyForUnknownId() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        MatchAnalysis saved = matchAnalysisRepository.save(new MatchAnalysis(
                ACCOUNT_ID, MATCH_ID, HERO_ID, RESULT_JSON, MODEL, INPUT_TOKENS, OUTPUT_TOKENS, now));

        Optional<MatchAnalysis> hit = matchAnalysisRepository.findByPublicId(saved.getPublicId());
        assertThat(hit).isPresent();
        assertThat(hit.get().getId()).isEqualTo(saved.getId());

        Optional<MatchAnalysis> miss = matchAnalysisRepository.findByPublicId(UUID.randomUUID());
        assertThat(miss).isEmpty();
    }

    @Test
    void uniqueConstraintRejectsDuplicatePublicId() {
        UUID duplicate = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO match_analysis (account_id, match_id, hero_id, result_json, model, input_tokens, output_tokens, public_id)
                VALUES (1, 101, 1, '{}', 'model', 10, 10, ?::uuid)
                """, duplicate);

        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO match_analysis (account_id, match_id, hero_id, result_json, model, input_tokens, output_tokens, public_id)
                VALUES (2, 102, 1, '{}', 'model', 10, 10, ?::uuid)
                """, duplicate))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void savesAndReadsBackWithInputJson() {
        Instant createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        String inputJson = "{\"hero\":\"Infernus\",\"result\":\"win\"}";
        MatchAnalysis saved = matchAnalysisRepository.save(new MatchAnalysis(
                ACCOUNT_ID, MATCH_ID, HERO_ID, RESULT_JSON, MODEL, INPUT_TOKENS, OUTPUT_TOKENS, createdAt, inputJson));

        Optional<MatchAnalysis> found = matchAnalysisRepository.findById(saved.getId());
        assertThat(found).hasValueSatisfying(analysis -> {
            assertThat(analysis.getInputJson()).isEqualTo(inputJson);
        });
    }
}
