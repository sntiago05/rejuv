package sntiago05.rejuv.repositories;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import sntiago05.rejuv.TestcontainersConfiguration;
import sntiago05.rejuv.domain.LinkedAccount;

@SpringBootTest(properties = {"bot.token=", "bot.dev-guild-id="})
@Import(TestcontainersConfiguration.class)
class LinkedAccountRepositoryTest {

    @Autowired
    private LinkedAccountRepository repository;

    @BeforeEach
    void cleanTable() {
        repository.deleteAll();
    }

    @Test
    void savesAndReadsBackAllFields() {
        Instant linkedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        repository.save(new LinkedAccount(123456789012345678L, 4294967295L, linkedAt));

        assertThat(repository.findById(123456789012345678L)).hasValueSatisfying(account -> {
            assertThat(account.getDiscordUserId()).isEqualTo(123456789012345678L);
            assertThat(account.getAccountId()).isEqualTo(4294967295L);
            assertThat(account.getLinkedAt()).isEqualTo(linkedAt);
        });
    }

    @Test
    void savingSameDiscordUserIdReplacesTheLinkedAccount() {
        Instant firstLinkedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Instant secondLinkedAt = firstLinkedAt.plusSeconds(60);
        repository.save(new LinkedAccount(1L, 100L, firstLinkedAt));
        repository.save(new LinkedAccount(1L, 200L, secondLinkedAt));

        assertThat(repository.count()).isEqualTo(1);
        assertThat(repository.findById(1L)).hasValueSatisfying(account -> {
            assertThat(account.getAccountId()).isEqualTo(200L);
            assertThat(account.getLinkedAt()).isEqualTo(secondLinkedAt);
        });
    }

    @Test
    void deleteByIdRemovesOnlyThatUser() {
        Instant linkedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        repository.save(new LinkedAccount(1L, 100L, linkedAt));
        repository.save(new LinkedAccount(2L, 200L, linkedAt));

        repository.deleteById(1L);

        assertThat(repository.findById(1L)).isEmpty();
        assertThat(repository.findById(2L)).hasValueSatisfying(
                account -> assertThat(account.getAccountId()).isEqualTo(200L));
        assertThat(repository.count()).isEqualTo(1);
    }

    @Test
    void findByIdReturnsEmptyWhenNotLinked() {
        assertThat(repository.findById(999L)).isEmpty();
    }
}
