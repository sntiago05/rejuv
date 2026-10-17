package sntiago05.rejuv.config;

import java.net.http.HttpClient;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties(AnthropicProperties.class)
public class AnthropicConfig {

    public static final String ANTHROPIC_VERSION = "2023-06-01";
    public static final String X_API_KEY_HEADER = "x-api-key";
    public static final String ANTHROPIC_VERSION_HEADER = "anthropic-version";

    /**
     * Builds the Anthropic Messages API {@link RestClient.Builder}.
     * Public and static so tests can bind a {@code MockRestServiceServer} to the same configuration.
     */
    public static RestClient.Builder restClientBuilder(AnthropicProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.readTimeout());
        return RestClient.builder()
                .baseUrl(properties.baseUrl())
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(ANTHROPIC_VERSION_HEADER, ANTHROPIC_VERSION)
                .defaultHeader(X_API_KEY_HEADER, properties.apiKey() != null ? properties.apiKey() : "")
                .requestFactory(requestFactory);
    }

    @Bean
    RestClient anthropicRestClient(AnthropicProperties properties) {
        return restClientBuilder(properties).build();
    }
}
