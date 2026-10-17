package sntiago05.rejuv.clients;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import sntiago05.rejuv.config.DeadlockApiProperties;

/**
 * Downloads image bytes for bot emojis.
 * Not cached: runs only for missing emojis uploaded to Discord.
 */
@Component
public class AssetImageClient {

    public static final int MAX_IMAGE_BYTES = 262144; // 256 KB
    private static final String USER_AGENT = "Rejuv-Discord-Bot";
    private static final Set<String> ALLOWED_HOSTS = Set.of(
            "assets-bucket.deadlock-api.com",
            "api.deadlock-api.com"
    );

    private final RestClient restClient;

    @Autowired
    public AssetImageClient(DeadlockApiProperties properties) {
        this(buildRestClient(properties));
    }

    AssetImageClient(RestClient restClient) {
        this.restClient = restClient;
    }

    private static RestClient buildRestClient(DeadlockApiProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.readTimeout());
        return RestClient.builder()
                .defaultHeader(HttpHeaders.USER_AGENT, USER_AGENT)
                .requestFactory(requestFactory)
                .build();
    }

    /**
     * Downloads an image from the specified URL.
     *
     * @param url image URL (must be https and hosted on deadlock-api domains)
     * @return raw image bytes (non-empty, <= 256 KB)
     * @throws IllegalArgumentException if the URL is invalid, not https, or not on an allowed host
     * @throws DeadlockApiUnavailableException on I/O error or timeout
     * @throws DeadlockApiException on non-2xx status, empty body, or body exceeding 256 KB
     */
    public byte[] download(String url) {
        if (url == null) {
            throw new IllegalArgumentException("URL must not be null");
        }
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid URL: " + url, e);
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalArgumentException("Only https URLs are allowed: " + url);
        }
        String host = uri.getHost();
        if (host == null || !ALLOWED_HOSTS.contains(host.toLowerCase())) {
            throw new IllegalArgumentException("Host not allowed: " + host);
        }

        try {
            return restClient.get()
                    .uri(uri)
                    .exchange((request, response) -> {
                        if (!response.getStatusCode().is2xxSuccessful()) {
                            throw new DeadlockApiException("Image download returned HTTP " + response.getStatusCode().value());
                        }
                        byte[] bytes;
                        try {
                            bytes = response.getBody().readAllBytes();
                        } catch (IOException e) {
                            throw new ResourceAccessException("Failed to read image body", e);
                        }
                        if (bytes.length == 0) {
                            throw new DeadlockApiException("Image body is empty");
                        }
                        if (bytes.length > MAX_IMAGE_BYTES) {
                            throw new DeadlockApiException("Image exceeds max size of " + MAX_IMAGE_BYTES + " bytes: " + bytes.length);
                        }
                        return bytes;
                    });
        } catch (ResourceAccessException e) {
            throw new DeadlockApiUnavailableException("Failed to download image from " + url, null, e);
        } catch (DeadlockApiException e) {
            throw e;
        } catch (RestClientException e) {
            throw new DeadlockApiException("Image download failed: " + e.getMessage(), e);
        }
    }
}
