package sntiago05.rejuv.clients;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.never;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.io.IOException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class AssetImageClientTest {

    private MockRestServiceServer server;
    private AssetImageClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new AssetImageClient(builder.build());
    }

    @Test
    void returnsBytesForAllowedHost() {
        String url = "https://assets-bucket.deadlock-api.com/assets-api-res/images/heroes/inferno_sm.png";
        byte[] expected = new byte[] {1, 2, 3, 4};
        server.expect(once(), requestTo(url))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(expected, MediaType.IMAGE_PNG));

        byte[] result = client.download(url);

        assertThat(result).isEqualTo(expected);
        server.verify();
    }

    @Test
    void returnsBytesForApiDeadlockApiHost() {
        String url = "https://api.deadlock-api.com/v1/assets/ranks/7/1/image";
        byte[] expected = new byte[] {5, 6, 7};
        server.expect(once(), requestTo(url))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(expected, MediaType.IMAGE_PNG));

        byte[] result = client.download(url);

        assertThat(result).isEqualTo(expected);
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://assets-bucket.deadlock-api.com/images/inferno.png",
            "https://evil.com/images/inferno.png",
            "https://assets-bucket.deadlock-api.com.evil.example/image.png",
            "ftp://assets-bucket.deadlock-api.com/image.png",
            "not-a-url"
    })
    void rejectsDisallowedUrlWithoutRequest(String url) {
        assertThatThrownBy(() -> client.download(url))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNullUrlWithoutRequest() {
        assertThatThrownBy(() -> client.download(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void bodyOver256KbThrowsDeadlockApiException() {
        String url = "https://assets-bucket.deadlock-api.com/images/large.png";
        byte[] oversized = new byte[262145];
        server.expect(once(), requestTo(url))
                .andRespond(withSuccess(oversized, MediaType.IMAGE_PNG));

        assertThatThrownBy(() -> client.download(url))
                .isInstanceOf(DeadlockApiException.class)
                .isNotInstanceOf(DeadlockApiUnavailableException.class)
                .hasMessageContaining("exceeds max size");
        server.verify();
    }

    @Test
    void emptyBodyThrowsDeadlockApiException() {
        String url = "https://assets-bucket.deadlock-api.com/images/empty.png";
        server.expect(once(), requestTo(url))
                .andRespond(withSuccess(new byte[0], MediaType.IMAGE_PNG));

        assertThatThrownBy(() -> client.download(url))
                .isInstanceOf(DeadlockApiException.class)
                .isNotInstanceOf(DeadlockApiUnavailableException.class)
                .hasMessageContaining("empty");
        server.verify();
    }

    @Test
    void timeoutOrIoErrorThrowsDeadlockApiUnavailableException() {
        String url = "https://assets-bucket.deadlock-api.com/images/inferno.png";
        server.expect(once(), requestTo(url))
                .andRespond(withException(new IOException("Connection timed out")));

        assertThatThrownBy(() -> client.download(url))
                .isInstanceOf(DeadlockApiUnavailableException.class);
        server.verify();
    }

    @Test
    void non2xxStatusThrowsDeadlockApiException() {
        String url = "https://assets-bucket.deadlock-api.com/images/missing.png";
        server.expect(once(), requestTo(url))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThatThrownBy(() -> client.download(url))
                .isInstanceOf(DeadlockApiException.class)
                .isNotInstanceOf(DeadlockApiUnavailableException.class);
        server.verify();
    }
}
