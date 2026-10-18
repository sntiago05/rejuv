package sntiago05.rejuv.clients;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import sntiago05.rejuv.config.DeadlockApiGateProperties;

/**
 * Concurrency gate for deadlock-api requests. Limits concurrent in-flight requests across all adapters
 * to protect upstream limits and uphold the rule "never fire parallel uncached analytics requests".
 */
public final class DeadlockApiGate implements ClientHttpRequestInterceptor {

    private static final Logger log = LoggerFactory.getLogger(DeadlockApiGate.class);

    private final Semaphore semaphore;
    private final Duration acquireTimeout;

    public DeadlockApiGate(DeadlockApiGateProperties properties) {
        this.semaphore = new Semaphore(properties.maxConcurrent(), true);
        this.acquireTimeout = properties.acquireTimeout();
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        acquirePermit(request);
        ClientHttpResponse response;
        try {
            response = execution.execute(request, body);
        } catch (IOException | RuntimeException | Error e) {
            semaphore.release();
            throw e;
        }
        if (response == null) {
            semaphore.release();
            return null;
        }
        return new GatedClientHttpResponse(response, semaphore::release);
    }

    private void acquirePermit(HttpRequest request) throws IOException {
        boolean acquired;
        try {
            acquired = semaphore.tryAcquire(acquireTimeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for deadlock-api gate permit", e);
        }
        if (!acquired) {
            String path = sanitizedPath(request);
            log.warn("deadlock-api gate timeout on {} after {}", path, acquireTimeout);
            throw new IOException("deadlock-api gate timeout");
        }
    }

    private static String sanitizedPath(HttpRequest request) {
        if (request == null || request.getURI() == null) {
            return "unknown";
        }
        String path = request.getURI().getPath();
        if (path == null) {
            return "unknown";
        }
        return path.replaceAll("/\\d+", "/{id}");
    }

    int availablePermits() {
        return semaphore.availablePermits();
    }

    int queueLength() {
        return semaphore.getQueueLength();
    }

    private static final class GatedClientHttpResponse implements ClientHttpResponse {

        private final ClientHttpResponse delegate;
        private final Runnable onClose;
        private final AtomicBoolean closed = new AtomicBoolean(false);

        GatedClientHttpResponse(ClientHttpResponse delegate, Runnable onClose) {
            this.delegate = delegate;
            this.onClose = onClose;
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
            return delegate.getHeaders();
        }

        @Override
        public InputStream getBody() throws IOException {
            return delegate.getBody();
        }

        @Override
        public void close() {
            try {
                delegate.close();
            } finally {
                if (closed.compareAndSet(false, true)) {
                    onClose.run();
                }
            }
        }
    }
}
