package sntiago05.rejuv.clients;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import sntiago05.rejuv.config.DeadlockApiConfig;
import sntiago05.rejuv.config.DeadlockApiProperties;
import sntiago05.rejuv.domain.MatchTelemetry;

class RawMatchMetadataSourceTest {

    static final String BASE_URL = "https://deadlock-api.test";
    static final long MATCH_ID = 900000003L;
    static final String METADATA_RAW_URL = BASE_URL + "/v1/matches/" + MATCH_ID + "/metadata/raw";

    static final DeadlockApiProperties PROPERTIES = new DeadlockApiProperties(BASE_URL, Duration.ofSeconds(3),
            Duration.ofSeconds(10), Duration.ofSeconds(2), Duration.ofSeconds(10));

    private final List<Duration> sleeps = new ArrayList<>();
    private RestClient.Builder clientBuilder;
    private MockRestServiceServer server;
    private RawMatchMetadataSource source;

    @BeforeEach
    void setUp() {
        clientBuilder = DeadlockApiConfig.restClientBuilder(PROPERTIES);
        server = MockRestServiceServer.bindTo(clientBuilder).build();
        source = new RawMatchMetadataSource(clientBuilder.build(), PROPERTIES, sleeps::add);
    }

    @AfterEach
    void verifyServer() {
        server.verify();
    }

    @Test
    void sendsExpectedRequest() {
        byte[] compressed = RawMetadataTestData.compress(RawMetadataTestData.defaultValidPayload());
        server.expect(once(), requestTo(METADATA_RAW_URL))
                .andExpect(method(HttpMethod.GET))
                .andExpect(request -> assertThat(request.getURI().getQuery()).isNull())
                .andRespond(withSuccess(compressed, MediaType.APPLICATION_OCTET_STREAM));

        MatchTelemetry telemetry = source.getMatch(MATCH_ID);

        assertThat(telemetry.matchId()).isEqualTo(MATCH_ID);
        assertThat(telemetry.durationS()).isEqualTo(1800);
        assertThat(telemetry.players()).hasSize(2);
    }

    @Test
    void retriesOnceOn503WithinCap() {
        byte[] compressed = RawMetadataTestData.compress(RawMetadataTestData.defaultValidPayload());
        server.expect(once(), requestTo(METADATA_RAW_URL))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE).header("Retry-After", "1"));
        server.expect(once(), requestTo(METADATA_RAW_URL))
                .andRespond(withSuccess(compressed, MediaType.APPLICATION_OCTET_STREAM));

        MatchTelemetry telemetry = source.getMatch(MATCH_ID);

        assertThat(telemetry.matchId()).isEqualTo(MATCH_ID);
        assertThat(sleeps).containsExactly(Duration.ofSeconds(1));
    }

    @Test
    void status503AboveCapIsNotRetried() {
        server.expect(once(), requestTo(METADATA_RAW_URL))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE).header("Retry-After", "1265"));

        assertThatThrownBy(() -> source.getMatch(MATCH_ID))
                .isInstanceOfSatisfying(DeadlockApiUnavailableException.class,
                        e -> assertThat(e.retryAfter()).contains(Duration.ofSeconds(1265)));
        assertThat(sleeps).isEmpty();
    }

    @Test
    void rateLimited429IsNotRetried() {
        server.expect(once(), requestTo(METADATA_RAW_URL))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).header("Retry-After", "5"));

        assertThatThrownBy(() -> source.getMatch(MATCH_ID))
                .isInstanceOfSatisfying(DeadlockApiUnavailableException.class,
                        e -> assertThat(e.retryAfter()).contains(Duration.ofSeconds(5)));
        assertThat(sleeps).isEmpty();
    }

    @Test
    void notFound404ThrowsNotFound() {
        server.expect(once(), requestTo(METADATA_RAW_URL))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThatThrownBy(() -> source.getMatch(MATCH_ID))
                .isInstanceOf(MatchMetadataNotFoundException.class);
        assertThat(sleeps).isEmpty();
    }

    @Test
    void serverError500ThrowsApiException() {
        server.expect(once(), requestTo(METADATA_RAW_URL))
                .andRespond(withServerError());

        assertThatThrownBy(() -> source.getMatch(MATCH_ID))
                .isInstanceOf(DeadlockApiException.class)
                .isNotInstanceOf(DeadlockApiUnavailableException.class);
        assertThat(sleeps).isEmpty();
    }

    @Test
    void ioErrorThrowsUnavailable() {
        server.expect(once(), requestTo(METADATA_RAW_URL))
                .andRespond(withException(new IOException("connection reset")));

        assertThatThrownBy(() -> source.getMatch(MATCH_ID))
                .isInstanceOf(DeadlockApiUnavailableException.class)
                .hasCauseInstanceOf(Exception.class);
        assertThat(sleeps).isEmpty();
    }

    @Test
    void unknownCompressionThrowsApiException() {
        // 1. BZh body
        server.expect(once(), requestTo(METADATA_RAW_URL))
                .andRespond(withSuccess("BZh91AY&SY".getBytes(StandardCharsets.UTF_8), MediaType.APPLICATION_OCTET_STREAM));

        assertThatThrownBy(() -> source.getMatch(MATCH_ID))
                .isInstanceOf(DeadlockApiException.class)
                .hasMessageContaining("Unrecognized metadata compression format");

        // 2. Empty body
        server.reset();
        server.expect(once(), requestTo(METADATA_RAW_URL))
                .andRespond(withSuccess(new byte[0], MediaType.APPLICATION_OCTET_STREAM));

        assertThatThrownBy(() -> source.getMatch(MATCH_ID))
                .isInstanceOf(DeadlockApiException.class);
    }

    @Test
    void corruptZstdThrowsApiException() {
        byte[] corruptZstd = new byte[] { 0x28, (byte) 0xB5, 0x2F, (byte) 0xFD, 0x01, 0x02, 0x03, 0x04 };
        server.expect(once(), requestTo(METADATA_RAW_URL))
                .andRespond(withSuccess(corruptZstd, MediaType.APPLICATION_OCTET_STREAM));

        assertThatThrownBy(() -> source.getMatch(MATCH_ID))
                .isInstanceOf(DeadlockApiException.class);
    }

    @Test
    void oversizedBodyThrowsApiException() {
        byte[] compressed = RawMetadataTestData.compress(RawMetadataTestData.defaultValidPayload());
        RawMatchMetadataSource smallLimitSource = new RawMatchMetadataSource(
                clientBuilder.build(), PROPERTIES, sleeps::add, 10, RawMatchMetadataSource.DEFAULT_MAX_DECOMPRESSED_BYTES);

        server.expect(once(), requestTo(METADATA_RAW_URL))
                .andRespond(withSuccess(compressed, MediaType.APPLICATION_OCTET_STREAM));

        assertThatThrownBy(() -> smallLimitSource.getMatch(MATCH_ID))
                .isInstanceOf(DeadlockApiException.class);
    }

    @Test
    void decompressionBombThrowsApiException() {
        byte[] compressed = RawMetadataTestData.compress(RawMetadataTestData.defaultValidPayload());
        RawMatchMetadataSource smallDecompressLimitSource = new RawMatchMetadataSource(
                clientBuilder.build(), PROPERTIES, sleeps::add, RawMatchMetadataSource.DEFAULT_MAX_COMPRESSED_BYTES, 20);

        server.expect(once(), requestTo(METADATA_RAW_URL))
                .andRespond(withSuccess(compressed, MediaType.APPLICATION_OCTET_STREAM));

        assertThatThrownBy(() -> smallDecompressLimitSource.getMatch(MATCH_ID))
                .isInstanceOf(DeadlockApiException.class);
    }
}
