package sntiago05.rejuv;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.assertj.core.api.Assertions.within;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(properties = {"bot.token=", "bot.dev-guild-id="})
@Import(TestcontainersConfiguration.class)
class FlywayMigrationTest {

    private static final String INSERT_ACCOUNT =
            "INSERT INTO linked_account (discord_user_id, account_id) VALUES (?, ?)";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    @AfterEach
    void cleanTable() {
        jdbcTemplate.update("DELETE FROM linked_account");
    }

    @Test
    void appliesVersion1Successfully() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT description, success FROM flyway_schema_history WHERE version = '1'");

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("description")).isEqualTo("create linked account");
        assertThat(rows.get(0).get("success")).isEqualTo(true);
    }

    @Test
    void linkedAccountHasExpectedColumns() {
        List<Map<String, Object>> columns = jdbcTemplate.queryForList("""
                SELECT column_name, data_type, is_nullable
                FROM information_schema.columns
                WHERE table_schema = current_schema() AND table_name = 'linked_account'
                ORDER BY ordinal_position
                """);

        assertThat(columns)
                .extracting(column -> tuple(
                        column.get("column_name"), column.get("data_type"), column.get("is_nullable")))
                .containsExactly(
                        tuple("discord_user_id", "bigint", "NO"),
                        tuple("account_id", "bigint", "NO"),
                        tuple("linked_at", "timestamp with time zone", "NO"));
    }

    @Test
    void discordUserIdIsThePrimaryKey() {
        List<String> primaryKeyColumns = jdbcTemplate.queryForList("""
                SELECT kcu.column_name
                FROM information_schema.table_constraints tc
                JOIN information_schema.key_column_usage kcu
                  ON tc.constraint_schema = kcu.constraint_schema
                 AND tc.constraint_name = kcu.constraint_name
                WHERE tc.table_schema = current_schema()
                  AND tc.table_name = 'linked_account'
                  AND tc.constraint_type = 'PRIMARY KEY'
                ORDER BY kcu.ordinal_position
                """, String.class);

        assertThat(primaryKeyColumns).containsExactly("discord_user_id");
    }

    @Test
    void linkedAtDefaultsToNow() {
        jdbcTemplate.update(INSERT_ACCOUNT, 1L, 100L);

        OffsetDateTime linkedAt = jdbcTemplate.queryForObject(
                "SELECT linked_at FROM linked_account WHERE discord_user_id = ?", OffsetDateTime.class, 1L);

        assertThat(linkedAt).isNotNull();
        assertThat(linkedAt.toInstant()).isCloseTo(Instant.now(), within(1, ChronoUnit.MINUTES));
    }

    @Test
    void enforcesConstraints() {
        jdbcTemplate.update(INSERT_ACCOUNT, 1L, 100L);

        assertThatThrownBy(() -> jdbcTemplate.update(INSERT_ACCOUNT, 1L, 200L))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO linked_account (discord_user_id, account_id) VALUES (?, NULL)", 2L))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
