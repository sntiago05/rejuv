package sntiago05.rejuv.config;

import java.net.http.HttpClient;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import sntiago05.rejuv.clients.Sleeper;

@Configuration
@EnableConfigurationProperties(DeadlockApiProperties.class)
public class DeadlockApiConfig {

    static final String USER_AGENT = "Rejuv-Discord-Bot";

    /**
     * Builds the deadlock-api {@link RestClient.Builder}. Public and static so tests (and Task 022)
     * can bind a {@code MockRestServiceServer} to the same configuration. Not exposed as a bean,
     * so it does not clash with Spring Boot's auto-configured builder.
     */
    public static RestClient.Builder restClientBuilder(DeadlockApiProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.readTimeout());
        return RestClient.builder()
                .baseUrl(properties.baseUrl())
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(HttpHeaders.USER_AGENT, USER_AGENT)
                .requestFactory(requestFactory);
    }

    @Bean
    RestClient deadlockApiRestClient(DeadlockApiProperties properties) {
        return restClientBuilder(properties).build();
    }

    @Bean
    Sleeper sleeper() {
        return duration -> Thread.sleep(duration);
    }
}
