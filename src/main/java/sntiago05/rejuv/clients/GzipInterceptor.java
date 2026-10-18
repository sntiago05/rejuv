package sntiago05.rejuv.clients;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.GZIPInputStream;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

/**
 * Interceptor that advertises gzip support and transparently decompresses gzip response bodies
 * from deadlock-api.
 */
public final class GzipInterceptor implements ClientHttpRequestInterceptor {

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        if (!request.getHeaders().containsHeader(HttpHeaders.ACCEPT_ENCODING)) {
            request.getHeaders().set(HttpHeaders.ACCEPT_ENCODING, "gzip");
        }
        ClientHttpResponse response = execution.execute(request, body);
        if (response != null && shouldDecompress(response)) {
            return new GzipClientHttpResponse(response);
        }
        return response;
    }

    private static boolean shouldDecompress(ClientHttpResponse response) throws IOException {
        String encoding = response.getHeaders().getFirst(HttpHeaders.CONTENT_ENCODING);
        if (encoding == null || !"gzip".equalsIgnoreCase(encoding.trim())) {
            return false;
        }
        HttpStatusCode status = response.getStatusCode();
        if (!status.is2xxSuccessful()) {
            return false;
        }
        int code = status.value();
        if (code == HttpStatus.NO_CONTENT.value()
                || code == HttpStatus.RESET_CONTENT.value()
                || code == HttpStatus.NOT_MODIFIED.value()) {
            return false;
        }
        if (response.getHeaders().getContentLength() == 0) {
            return false;
        }
        return true;
    }

    private static final class GzipClientHttpResponse implements ClientHttpResponse {

        private final ClientHttpResponse delegate;
        private final HttpHeaders headers;
        private final AtomicBoolean closed = new AtomicBoolean(false);
        private GZIPInputStream gzipStream;

        GzipClientHttpResponse(ClientHttpResponse delegate) {
            this.delegate = delegate;
            HttpHeaders copy = new HttpHeaders();
            copy.putAll(delegate.getHeaders());
            copy.remove(HttpHeaders.CONTENT_ENCODING);
            copy.remove(HttpHeaders.CONTENT_LENGTH);
            this.headers = HttpHeaders.readOnlyHttpHeaders(copy);
        }

        @Override
        public HttpStatusCode getStatusCode() throws IOException {
            return delegate.getStatusCode();
        }

        @Override
        public String getStatusText() throws IOException {
            return delegate.getStatusText();
        }

        @Override
        public HttpHeaders getHeaders() {
            return headers;
        }

        @Override
        public synchronized InputStream getBody() throws IOException {
            if (closed.get()) {
                throw new IOException("Response is closed");
            }
            if (gzipStream == null) {
                InputStream delegateBody = delegate.getBody();
                if (delegateBody == null) {
                    return null;
                }
                gzipStream = new GZIPInputStream(delegateBody);
            }
            return gzipStream;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                synchronized (this) {
                    try {
                        if (gzipStream != null) {
                            gzipStream.close();
                        }
                    } catch (IOException ignored) {
                        // Suppress stream close exception on response close
                    } finally {
                        delegate.close();
                    }
                }
            }
        }
    }
}
