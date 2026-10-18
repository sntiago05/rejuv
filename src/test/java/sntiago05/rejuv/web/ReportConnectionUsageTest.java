package sntiago05.rejuv.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import sntiago05.rejuv.TestcontainersConfiguration;
import sntiago05.rejuv.clients.DeadlockApiClient;
import sntiago05.rejuv.clients.DeadlockApiUnavailableException;
import sntiago05.rejuv.clients.FallbackMatchMetadataSource;
import sntiago05.rejuv.domain.MatchAnalysis;
import sntiago05.rejuv.repositories.MatchAnalysisRepository;

/**
 * A web report request must not keep a pooled database connection while it waits on deadlock-api (match metadata
 * can take up to the read timeout plus one Retry-After wait). With open-in-view enabled the request's session holds
 * the connection from the first query until the response is written.
 */
@SpringBootTest(properties = {"bot.token=", "bot.dev-guild-id="})
@Import(TestcontainersConfiguration.class)
class ReportConnectionUsageTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private MatchAnalysisRepository repository;

    @Autowired
    private DataSource dataSource;

    @MockitoBean
    private FallbackMatchMetadataSource matchMetadataSource;

    @MockitoBean
    private DeadlockApiClient deadlockApiClient;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        repository.deleteAll();
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @Test
    void reportPageReleasesTheDatabaseConnectionBeforeCallingDeadlockApi() throws Exception {
        MatchAnalysis stored = repository.save(new MatchAnalysis(1000001L, 900000003L, 1,
                "{\"strength\":\"s\",\"biggest_leak\":\"l\",\"next_game_action\":\"a\",\"key_numbers\":[]}",
                "claude-haiku-5-5", 10, 20, Instant.parse("2026-10-18T12:00:00Z")));
        HikariDataSource pool = dataSource.unwrap(HikariDataSource.class);
        AtomicInteger activeDuringMetadataCall = new AtomicInteger(-1);
        when(deadlockApiClient.getHeroes()).thenReturn(List.of());
        when(matchMetadataSource.getMatch(anyLong())).thenAnswer(invocation -> {
            activeDuringMetadataCall.set(pool.getHikariPoolMXBean().getActiveConnections());
            throw new DeadlockApiUnavailableException("busy", null, null);
        });

        mockMvc.perform(get("/r/" + stored.getPublicId())).andExpect(status().isOk());

        assertThat(activeDuringMetadataCall.get()).isZero();
    }
}
