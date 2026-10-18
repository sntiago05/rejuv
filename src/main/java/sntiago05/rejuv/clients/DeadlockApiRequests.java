package sntiago05.rejuv.clients;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriBuilder;
import sntiago05.rejuv.config.DeadlockApiProperties;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.exc.JacksonIOException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectReader;

/**
 * GET request rules shared by every deadlock-api adapter (Task 004 rules): one retry on HTTP 503 when the
 * {@code Retry-After} wait is within the cap; 429, I/O errors and timeouts become
 * {@link DeadlockApiUnavailableException}; other statuses and unreadable bodies become {@link DeadlockApiException}.
 */
final class DeadlockApiRequests {

    private static final Logger log = LoggerFactory.getLogger(DeadlockApiRequests.class);

    /** Reads a 2xx (or accepted 429) body. Returning null means "empty body". */
    @FunctionalInterface
    interface BodyReader<T> {
        T read(ConvertibleClientHttpResponse response) throws IOException;
    }

    private final RestClient restClient;
    private final DeadlockApiProperties properties;
    private final Sleeper sleeper;

    DeadlockApiRequests(RestClient restClient, DeadlockApiProperties properties, Sleeper sleeper) {
        this.restClient = restClient;
        this.properties = properties;
        this.sleeper = sleeper;
    }

    static <T> BodyReader<T> json(ParameterizedTypeReference<T> type) {
        return response -> response.bodyTo(type);
    }

    /**
     * Reads at most {@code limit} elements of a JSON array body and stops reading there, so a large array
     * (the match history can be about 5 MB) is neither fully downloaded nor fully parsed.
     * An empty or {@code null} body returns null; any other non-array body is unreadable.
     */
    static <T> BodyReader<List<T>> jsonArrayPrefix(ObjectReader elementReader, int limit) {
        // Each element is read from the middle of the array, so the next elements are not "trailing tokens".
        ObjectReader reader = elementReader.without(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        return response -> {
            try (JsonParser parser = reader.createParser(response.getBody())) {
                JsonToken first = parser.nextToken();
                if (first == null || first == JsonToken.VALUE_NULL) {
                    return null;
                }
                if (first != JsonToken.START_ARRAY) {
                    throw new RestClientException("Expected a JSON array but found " + first);
                }
                List<T> elements = new ArrayList<>(limit);
                while (elements.size() < limit) {
                    JsonToken token = parser.nextToken();
                    if (token == JsonToken.END_ARRAY) {
                        break;
                    }
                    if (token == null) {
                        throw new RestClientException("JSON array ended unexpectedly");
                    }
                    elements.add(reader.readValue(parser));
                }
                return elements;
            } catch (JacksonIOException e) {
                throw new IOException(e.getMessage(), e);
            } catch (JacksonException e) {
                throw new RestClientException("Could not read the JSON array", e);
            }
        };
    }

    static BodyReader<byte[]> bytes(int maxBytes) {
        return response -> {
            long contentLength = response.getHeaders().getContentLength();
            if (contentLength > maxBytes) {
                throw new DeadlockApiException("Response body exceeded " + maxBytes + " bytes");
            }
            InputStream in = response.getBody();
            if (in == null) {
                return new byte[0];
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int total = 0;
            int n;
            while ((n = in.read(buffer)) != -1) {
                total += n;
                if (total > maxBytes) {
                    throw new DeadlockApiException("Response body exceeded " + maxBytes + " bytes");
                }
                out.write(buffer, 0, n);
            }
            return out.toByteArray();
        };
    }

    <T> T get(String path, Function<UriBuilder, URI> uri, BodyReader<T> reader) {
        return get(path, uri, reader, false, null);
    }

    /**
     * @param rateLimitedBodyIsSuccess HTTP 429 with a readable body is a success (the match history returns the
     *                                 stored history on 429)
     * @param notFound                 exception for HTTP 404, or null to treat 404 like any other failure status
     */
    <T> T get(String path, Function<UriBuilder, URI> uri, BodyReader<T> reader,
              boolean rateLimitedBodyIsSuccess, Supplier<? extends RuntimeException> notFound) {
        Response<T> response = exchange(path, uri, reader, rateLimitedBodyIsSuccess);
        if (response.status() == HttpStatus.SERVICE_UNAVAILABLE.value()) {
            Duration wait = parseRetryAfter(response.retryAfter()).orElse(properties.defaultRetryAfter());
            if (wait.compareTo(properties.maxRetryAfter()) > 0) {
                log.warn("deadlock-api {} returned HTTP 503, wait {} s is above the cap, not retrying",
                        path, wait.toSeconds());
                throw new DeadlockApiUnavailableException("GET " + path + " returned HTTP 503", wait, null);
            }
            log.warn("deadlock-api {} returned HTTP 503, retrying once after {} ms", path, wait.toMillis());
            sleep(path, wait);
            response = exchange(path, uri, reader, rateLimitedBodyIsSuccess);
            if (!response.success()) {
                Duration retryAfter = parseRetryAfter(response.retryAfter()).orElse(properties.defaultRetryAfter());
                log.warn("deadlock-api {} returned HTTP {} after one retry", path, response.status());
                throw new DeadlockApiUnavailableException(
                        "GET " + path + " returned HTTP " + response.status() + " after one retry", retryAfter, null);
            }
        }
        if (response.success()) {
            return response.body();
        }
        if (response.status() == HttpStatus.TOO_MANY_REQUESTS.value()) {
            log.warn("deadlock-api {} returned HTTP 429, not retrying", path);
            throw new DeadlockApiUnavailableException("GET " + path + " returned HTTP 429",
                    parseRetryAfter(response.retryAfter()).orElse(null), null);
        }
        if (response.status() == HttpStatus.NOT_FOUND.value() && notFound != null) {
            log.warn("deadlock-api {} returned HTTP 404, not retrying", path);
            throw notFound.get();
        }
        throw new DeadlockApiException("GET " + path + " returned HTTP " + response.status());
    }

    private <T> Response<T> exchange(String path, Function<UriBuilder, URI> uri, BodyReader<T> reader,
                                     boolean rateLimitedBodyIsSuccess) {
        log.debug("deadlock-api GET {}", path);
        try {
            return restClient.get().uri(uri).exchange((request, response) -> {
                int status = response.getStatusCode().value();
                if (response.getStatusCode().is2xxSuccessful()) {
                    return Response.success(status, readBody(path, status, response, reader));
                }
                if (status == HttpStatus.TOO_MANY_REQUESTS.value() && rateLimitedBodyIsSuccess) {
                    return Response.success(status, readRateLimitedBody(path, response, reader));
                }
                return Response.failure(status, response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER));
            });
        } catch (ResourceAccessException e) {
            log.warn("deadlock-api {} failed with an I/O error or timeout", path);
            throw new DeadlockApiUnavailableException("GET " + path + " failed with an I/O error or timeout", null, e);
        } catch (RestClientException e) {
            throw new DeadlockApiException("GET " + path + " failed", e);
        }
    }

    private static <T> T readBody(String path, int status, ConvertibleClientHttpResponse response,
                                  BodyReader<T> reader) {
        T body;
        try {
            body = reader.read(response);
        } catch (IOException e) {
            throw readFailedWithIoError(path, e);
        } catch (RestClientException e) {
            if (e.getCause() instanceof IOException || e.getCause() instanceof UncheckedIOException) {
                throw readFailedWithIoError(path, e);
            }
            throw new DeadlockApiException("GET " + path + " returned an unreadable body (HTTP " + status + ")", e);
        }
        if (body == null) {
            throw new DeadlockApiException("GET " + path + " returned an empty body (HTTP " + status + ")");
        }
        return body;
    }

    private static DeadlockApiUnavailableException readFailedWithIoError(String path, Exception e) {
        log.warn("deadlock-api {} failed with an I/O error while reading the body", path);
        return new DeadlockApiUnavailableException("GET " + path + " failed while reading the body", null, e);
    }

    /** On HTTP 429 the history endpoint returns the stored history as the body. */
    private static <T> T readRateLimitedBody(String path, ConvertibleClientHttpResponse response,
                                             BodyReader<T> reader) {
        Duration retryAfter = parseRetryAfter(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).orElse(null);
        T body;
        try {
            body = reader.read(response);
        } catch (IOException | RestClientException e) {
            log.warn("deadlock-api {} returned HTTP 429 without a readable stored history", path);
            throw new DeadlockApiUnavailableException("GET " + path + " returned HTTP 429", retryAfter, e);
        }
        if (body == null) {
            log.warn("deadlock-api {} returned HTTP 429 without a stored history", path);
            throw new DeadlockApiUnavailableException("GET " + path + " returned HTTP 429", retryAfter, null);
        }
        log.warn("deadlock-api {} returned HTTP 429, using the stored history from the body", path);
        return body;
    }

    private void sleep(String path, Duration wait) {
        try {
            sleeper.sleep(wait);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DeadlockApiUnavailableException("GET " + path + " was interrupted while waiting to retry",
                    wait, e);
        }
    }

    /** An integer number of seconds greater than zero; anything else is treated as missing. */
    private static Optional<Duration> parseRetryAfter(String header) {
        if (header == null) {
            return Optional.empty();
        }
        try {
            long seconds = Long.parseLong(header.trim());
            return seconds > 0 ? Optional.of(Duration.ofSeconds(seconds)) : Optional.empty();
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private record Response<T>(int status, boolean success, T body, String retryAfter) {

        static <T> Response<T> success(int status, T body) {
            return new Response<>(status, true, body, null);
        }

        static <T> Response<T> failure(int status, String retryAfter) {
            return new Response<>(status, false, null, retryAfter);
        }
    }
}
