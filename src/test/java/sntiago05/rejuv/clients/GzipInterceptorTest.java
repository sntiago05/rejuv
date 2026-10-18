package sntiago05.rejuv.clients;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPOutputStream;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.web.client.RestClient;
import sntiago05.rejuv.clients.dto.MatchHistoryEntry;
import sntiago05.rejuv.config.DeadlockApiConfig;
import sntiago05.rejuv.config.DeadlockApiGateProperties;
import sntiago05.rejuv.config.DeadlockApiProperties;
import tools.jackson.databind.ObjectReader;

class GzipInterceptorTest {

    private HttpServer server;
    private int port;
    private RestClient restClient;
    private final GzipInterceptor interceptor = new GzipInterceptor();

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        port = server.getAddress().getPort();

        DeadlockApiProperties properties = new DeadlockApiProperties(
                "http://127.0.0.1:" + port,
                Duration.ofSeconds(3),
                Duration.ofSeconds(10),
                Duration.ofSeconds(2),
                Duration.ofSeconds(10));
        restClient = DeadlockApiConfig.restClientBuilder(properties).build();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    private static byte[] gzipCompress(byte[] uncompressed) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (GZIPOutputStream gzos = new GZIPOutputStream(baos)) {
            gzos.write(uncompressed);
        }
        return baos.toByteArray();
    }

    @Test
    void requestCarriesAcceptEncodingGzip() {
        AtomicReference<String> serverReceivedHeader = new AtomicReference<>();
        server.createContext("/test-header", exchange -> {
            serverReceivedHeader.set(exchange.getRequestHeaders().getFirst(HttpHeaders.ACCEPT_ENCODING));
            byte[] response = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });

        String body = restClient.get().uri("/test-header").retrieve().body(String.class);

        assertThat(body).isEqualTo("{\"ok\":true}");
        assertThat(serverReceivedHeader.get()).isEqualTo("gzip");
    }

    @Test
    void gzipBodyDecompressedAndJsonReadCorrectlyOver100Kb() throws Exception {
        StringBuilder sb = new StringBuilder("{\"items\":[");
        for (int i = 0; i < 2500; i++) {
            if (i > 0) {
                sb.append(",");
            }
            sb.append("{\"id\":").append(i)
                    .append(",\"name\":\"hero_item_name_").append(i)
                    .append("\",\"description\":\"some verbose description text to make the payload large #").append(i)
                    .append("\"}");
        }
        sb.append("]}");
        String originalJson = sb.toString();
        byte[] uncompressed = originalJson.getBytes(StandardCharsets.UTF_8);
        byte[] compressed = gzipCompress(uncompressed);

        assertThat(uncompressed.length).isGreaterThan(100_000);
        assertThat(compressed.length).isLessThan(uncompressed.length / 5);

        server.createContext("/gzip-large", exchange -> {
            exchange.getResponseHeaders().set(HttpHeaders.CONTENT_ENCODING, "gzip");
            exchange.getResponseHeaders().set(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
            exchange.sendResponseHeaders(200, compressed.length);
            exchange.getResponseBody().write(compressed);
            exchange.close();
        });

        String responseBody = restClient.get().uri("/gzip-large").retrieve().body(String.class);

        assertThat(responseBody).isEqualTo(originalJson);
    }

    @Test
    void plainBodyReturnedUnchanged() {
        String plainJson = "{\"status\":\"plain\",\"payload\":[1,2,3]}";
        server.createContext("/plain", exchange -> {
            byte[] bytes = plainJson.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });

        String responseBody = restClient.get().uri("/plain").retrieve().body(String.class);

        assertThat(responseBody).isEqualTo(plainJson);
    }

    @Test
    void compressionHeadersRemovedFromWrappedResponseAndUppercaseHandled() throws Exception {
        byte[] original = "{\"test\":true}".getBytes(StandardCharsets.UTF_8);
        byte[] compressed = gzipCompress(original);

        HttpRequest request = new MockClientHttpRequest(HttpMethod.GET, URI.create("http://localhost/test"));

        // Case 1: lowercase gzip
        HttpHeaders headers1 = new HttpHeaders();
        headers1.set(HttpHeaders.CONTENT_ENCODING, "gzip");
        headers1.set(HttpHeaders.CONTENT_LENGTH, String.valueOf(compressed.length));
        headers1.set(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
        TestResponse fake1 = new TestResponse(HttpStatus.OK, headers1, compressed);

        ClientHttpResponse wrapped1 = interceptor.intercept(request, new byte[0], (req, body) -> fake1);

        assertThat(wrapped1).isNotSameAs(fake1);
        assertThat(wrapped1.getHeaders().getFirst(HttpHeaders.CONTENT_ENCODING)).isNull();
        assertThat(wrapped1.getHeaders().containsHeader(HttpHeaders.CONTENT_ENCODING)).isFalse();
        assertThat(wrapped1.getHeaders().containsHeader(HttpHeaders.CONTENT_LENGTH)).isFalse();
        assertThat(wrapped1.getHeaders().getContentLength()).isEqualTo(-1L);
        assertThat(wrapped1.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE)).isEqualTo(MediaType.APPLICATION_JSON_VALUE);
        assertThat(new String(wrapped1.getBody().readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("{\"test\":true}");

        // Case 2: uppercase GZIP
        HttpHeaders headers2 = new HttpHeaders();
        headers2.set(HttpHeaders.CONTENT_ENCODING, "GZIP");
        headers2.set(HttpHeaders.CONTENT_LENGTH, String.valueOf(compressed.length));
        TestResponse fake2 = new TestResponse(HttpStatus.OK, headers2, compressed);

        ClientHttpResponse wrapped2 = interceptor.intercept(request, new byte[0], (req, body) -> fake2);

        assertThat(wrapped2).isNotSameAs(fake2);
        assertThat(wrapped2.getHeaders().containsHeader(HttpHeaders.CONTENT_ENCODING)).isFalse();
        assertThat(wrapped2.getHeaders().containsHeader(HttpHeaders.CONTENT_LENGTH)).isFalse();
        assertThat(new String(wrapped2.getBody().readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("{\"test\":true}");
    }

    @Test
    void callerProvidedAcceptEncodingHeaderNotOverriddenAndIdentityPassesThrough() {
        AtomicReference<String> serverReceivedHeader = new AtomicReference<>();
        server.createContext("/identity", exchange -> {
            serverReceivedHeader.set(exchange.getRequestHeaders().getFirst(HttpHeaders.ACCEPT_ENCODING));
            byte[] response = "{\"encoding\":\"identity\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set(HttpHeaders.CONTENT_ENCODING, "identity");
            exchange.getResponseHeaders().set(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });

        String responseBody = restClient.get()
                .uri("/identity")
                .header(HttpHeaders.ACCEPT_ENCODING, "identity")
                .retrieve()
                .body(String.class);

        assertThat(responseBody).isEqualTo("{\"encoding\":\"identity\"}");
        assertThat(serverReceivedHeader.get()).isEqualTo("identity");
    }

    @Test
    void emptyBodyWithGzipEncodingOn204Or304OrErrorDoesNotThrow() throws Exception {
        HttpRequest request = new MockClientHttpRequest(HttpMethod.GET, URI.create("http://localhost/test"));

        // 204 No Content
        HttpHeaders h204 = new HttpHeaders();
        h204.set(HttpHeaders.CONTENT_ENCODING, "gzip");
        TestResponse r204 = new TestResponse(HttpStatus.NO_CONTENT, h204, new byte[0]);
        ClientHttpResponse out204 = interceptor.intercept(request, new byte[0], (req, body) -> r204);
        assertThat(out204).isSameAs(r204);
        assertThat(out204.getBody().readAllBytes()).isEmpty();
        out204.close();

        // 205 Reset Content
        HttpHeaders h205 = new HttpHeaders();
        h205.set(HttpHeaders.CONTENT_ENCODING, "gzip");
        TestResponse r205 = new TestResponse(HttpStatus.RESET_CONTENT, h205, new byte[0]);
        ClientHttpResponse out205 = interceptor.intercept(request, new byte[0], (req, body) -> r205);
        assertThat(out205).isSameAs(r205);
        assertThat(out205.getBody().readAllBytes()).isEmpty();
        out205.close();

        // 304 Not Modified
        HttpHeaders h304 = new HttpHeaders();
        h304.set(HttpHeaders.CONTENT_ENCODING, "gzip");
        TestResponse r304 = new TestResponse(HttpStatus.NOT_MODIFIED, h304, new byte[0]);
        ClientHttpResponse out304 = interceptor.intercept(request, new byte[0], (req, body) -> r304);
        assertThat(out304).isSameAs(r304);
        assertThat(out304.getBody().readAllBytes()).isEmpty();
        out304.close();

        // 500 Internal Server Error
        HttpHeaders h500 = new HttpHeaders();
        h500.set(HttpHeaders.CONTENT_ENCODING, "gzip");
        TestResponse r500 = new TestResponse(HttpStatus.INTERNAL_SERVER_ERROR, h500, new byte[0]);
        ClientHttpResponse out500 = interceptor.intercept(request, new byte[0], (req, body) -> r500);
        assertThat(out500).isSameAs(r500);
        assertThat(out500.getBody().readAllBytes()).isEmpty();
        out500.close();

        // 200 OK with Content-Length: 0
        HttpHeaders h200Empty = new HttpHeaders();
        h200Empty.set(HttpHeaders.CONTENT_ENCODING, "gzip");
        h200Empty.setContentLength(0);
        TestResponse r200Empty = new TestResponse(HttpStatus.OK, h200Empty, new byte[0]);
        ClientHttpResponse out200Empty = interceptor.intercept(request, new byte[0], (req, body) -> r200Empty);
        assertThat(out200Empty).isSameAs(r200Empty);
        assertThat(out200Empty.getBody().readAllBytes()).isEmpty();
        out200Empty.close();
    }

    @Test
    void truncatedGzipBodyThrowsIoExceptionOnRead() throws Exception {
        byte[] validGzip = gzipCompress("Hello world compressed data payload that is long enough".getBytes(StandardCharsets.UTF_8));
        byte[] truncated = Arrays.copyOf(validGzip, 15);

        HttpRequest request = new MockClientHttpRequest(HttpMethod.GET, URI.create("http://localhost/test"));
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.CONTENT_ENCODING, "gzip");
        TestResponse fake = new TestResponse(HttpStatus.OK, headers, truncated);

        ClientHttpResponse wrapped = interceptor.intercept(request, new byte[0], (req, body) -> fake);

        InputStream in = wrapped.getBody();
        assertThatThrownBy(in::readAllBytes).isInstanceOf(IOException.class);
    }

    @Test
    void closeIsIdempotentAndClosesDelegateOnce() throws Exception {
        byte[] compressed = gzipCompress("{\"ok\":true}".getBytes(StandardCharsets.UTF_8));
        HttpRequest request = new MockClientHttpRequest(HttpMethod.GET, URI.create("http://localhost/test"));

        // Case 1: Body was read
        HttpHeaders h1 = new HttpHeaders();
        h1.set(HttpHeaders.CONTENT_ENCODING, "gzip");
        TestResponse r1 = new TestResponse(HttpStatus.OK, h1, compressed);
        ClientHttpResponse wrapped1 = interceptor.intercept(request, new byte[0], (req, body) -> r1);

        assertThat(r1.getBodyCalled.get()).isFalse();
        wrapped1.getBody().readAllBytes();
        assertThat(r1.getBodyCalled.get()).isTrue();

        wrapped1.close();
        assertThat(r1.closeCount.get()).isEqualTo(1);
        wrapped1.close();
        assertThat(r1.closeCount.get()).isEqualTo(1);

        // Case 2: Body was never read (lazy creation)
        HttpHeaders h2 = new HttpHeaders();
        h2.set(HttpHeaders.CONTENT_ENCODING, "gzip");
        TestResponse r2 = new TestResponse(HttpStatus.OK, h2, compressed);
        ClientHttpResponse wrapped2 = interceptor.intercept(request, new byte[0], (req, body) -> r2);

        wrapped2.close();
        assertThat(r2.closeCount.get()).isEqualTo(1);
        assertThat(r2.getBodyCalled.get()).isFalse();
        wrapped2.close();
        assertThat(r2.closeCount.get()).isEqualTo(1);
    }

    @Test
    void gateStillReleasesPermitAfterGzipResponse() throws Exception {
        byte[] compressed = gzipCompress("{\"ok\":true}".getBytes(StandardCharsets.UTF_8));
        server.createContext("/gzip-gate", exchange -> {
            exchange.getResponseHeaders().set(HttpHeaders.CONTENT_ENCODING, "gzip");
            exchange.getResponseHeaders().set(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
            exchange.sendResponseHeaders(200, compressed.length);
            exchange.getResponseBody().write(compressed);
            exchange.close();
        });

        DeadlockApiProperties apiProperties = new DeadlockApiProperties(
                "http://127.0.0.1:" + port,
                Duration.ofSeconds(3),
                Duration.ofSeconds(10),
                Duration.ofSeconds(2),
                Duration.ofSeconds(10));
        DeadlockApiGateProperties gateProperties = new DeadlockApiGateProperties(1, Duration.ofSeconds(2));

        // Build with gzip (installed in restClientBuilder) and gate (installed after)
        RestClient gatedClient = DeadlockApiConfig.restClientBuilder(apiProperties)
                .requestInterceptor(new DeadlockApiGate(gateProperties))
                .build();

        // Sequential call 1
        String res1 = gatedClient.get().uri("/gzip-gate").retrieve().body(String.class);
        assertThat(res1).isEqualTo("{\"ok\":true}");

        // Sequential call 2 (will time out if permit was not released)
        String res2 = gatedClient.get().uri("/gzip-gate").retrieve().body(String.class);
        assertThat(res2).isEqualTo("{\"ok\":true}");
    }

    @Test
    void matchHistoryEarlyStopReaderWorksOverGzipBody() throws Exception {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < 50; i++) {
            if (i > 0) {
                sb.append(",");
            }
            sb.append("{\"match_id\":").append(1000 + i)
                    .append(",\"hero_id\":1,\"hero_level\":10,\"start_time\":1789000000,\"match_duration_s\":1800")
                    .append(",\"match_mode\":4,\"match_result\":1,\"player_team\":1,\"player_match_outcome\":1")
                    .append(",\"player_kills\":5,\"player_deaths\":2,\"player_assists\":8,\"net_worth\":20000")
                    .append(",\"last_hits\":150,\"denies\":10,\"ranked_display_badge\":50}");
        }
        sb.append("]");
        byte[] compressed = gzipCompress(sb.toString().getBytes(StandardCharsets.UTF_8));

        server.createContext("/history-early-stop", exchange -> {
            exchange.getResponseHeaders().set(HttpHeaders.CONTENT_ENCODING, "gzip");
            exchange.getResponseHeaders().set(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
            exchange.sendResponseHeaders(200, compressed.length);
            exchange.getResponseBody().write(compressed);
            exchange.close();
        });

        ObjectReader elementReader = new JacksonJsonHttpMessageConverter().getMapper().readerFor(MatchHistoryEntry.class);
        DeadlockApiRequests.BodyReader<List<MatchHistoryEntry>> prefixReader =
                DeadlockApiRequests.jsonArrayPrefix(elementReader, 5);

        List<MatchHistoryEntry> entries = restClient.get()
                .uri("/history-early-stop")
                .exchange((req, res) -> prefixReader.read(res));

        assertThat(entries).isNotNull().hasSize(5);
        assertThat(entries.get(0).matchId()).isEqualTo(1000L);
        assertThat(entries.get(4).matchId()).isEqualTo(1004L);
    }

    private static class TestResponse implements ClientHttpResponse {
        private final HttpStatusCode status;
        private final HttpHeaders headers;
        private final InputStream body;
        final AtomicInteger closeCount = new AtomicInteger(0);
        final AtomicBoolean getBodyCalled = new AtomicBoolean(false);

        TestResponse(HttpStatusCode status, HttpHeaders headers, byte[] bodyBytes) {
            this.status = status;
            this.headers = headers;
            this.body = new ByteArrayInputStream(bodyBytes);
        }

        @Override
        public HttpStatusCode getStatusCode() {
            return status;
        }

        @Override
        public String getStatusText() {
            return status.toString();
        }

        @Override
        public HttpHeaders getHeaders() {
            return headers;
        }

        @Override
        public InputStream getBody() {
            getBodyCalled.set(true);
            return body;
        }

        @Override
        public void close() {
            closeCount.incrementAndGet();
        }
    }
}
