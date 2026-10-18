package sntiago05.rejuv.clients;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import sntiago05.rejuv.config.DeadlockApiConfig;
import sntiago05.rejuv.config.DeadlockApiGateProperties;
import sntiago05.rejuv.config.DeadlockApiProperties;

class DeadlockApiGateTest {

    private static class ObservableResponse implements ClientHttpResponse {
        final AtomicBoolean closed = new AtomicBoolean(false);
        final AtomicInteger closeCount = new AtomicInteger(0);

        @Override
        public HttpStatusCode getStatusCode() {
            return HttpStatus.OK;
        }

        @Override
        public String getStatusText() {
            return "OK";
        }

        @Override
        public HttpHeaders getHeaders() {
            return new HttpHeaders();
        }

        @Override
        public InputStream getBody() {
            return InputStream.nullInputStream();
        }

        @Override
        public void close() {
            closed.set(true);
            closeCount.incrementAndGet();
        }
    }

    @Test
    void maxConcurrentOneSerializesCallsOnVirtualThreads() throws Exception {
        DeadlockApiGateProperties properties = new DeadlockApiGateProperties(1, Duration.ofSeconds(5));
        DeadlockApiGate gate = new DeadlockApiGate(properties);

        AtomicInteger inFlight = new AtomicInteger(0);
        AtomicInteger maxObserved = new AtomicInteger(0);
        CountDownLatch thread1Started = new CountDownLatch(1);
        CountDownLatch releaseThread1 = new CountDownLatch(1);

        HttpRequest request = new MockClientHttpRequest(HttpMethod.GET, URI.create("https://api.deadlock-api.com/v1/test"));

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<ClientHttpResponse> f1 = executor.submit(() -> gate.intercept(request, new byte[0], (req, body) -> {
                int count = inFlight.incrementAndGet();
                maxObserved.accumulateAndGet(count, Math::max);
                thread1Started.countDown();
                try {
                    releaseThread1.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                inFlight.decrementAndGet();
                return new ObservableResponse();
            }));

            assertThat(thread1Started.await(5, TimeUnit.SECONDS)).isTrue();

            Future<ClientHttpResponse> f2 = executor.submit(() -> gate.intercept(request, new byte[0], (req, body) -> {
                int count = inFlight.incrementAndGet();
                maxObserved.accumulateAndGet(count, Math::max);
                inFlight.decrementAndGet();
                return new ObservableResponse();
            }));

            // Thread 2 must be blocked waiting for permit, so maxObserved is still 1
            while (gate.queueLength() == 0) {
                Thread.onSpinWait();
            }
            assertThat(maxObserved.get()).isEqualTo(1);
            assertThat(gate.queueLength()).isEqualTo(1);

            // Release thread 1 execution
            releaseThread1.countDown();
            ClientHttpResponse r1 = f1.get(5, TimeUnit.SECONDS);

            // Permit is still held until r1 is closed
            assertThat(maxObserved.get()).isEqualTo(1);
            r1.close();

            // Thread 2 can now proceed and finish
            ClientHttpResponse r2 = f2.get(5, TimeUnit.SECONDS);
            r2.close();

            assertThat(maxObserved.get()).isEqualTo(1);
        }
    }

    @Test
    void maxConcurrentTwoAllowsTwoTogetherNeverThree() throws Exception {
        DeadlockApiGateProperties properties = new DeadlockApiGateProperties(2, Duration.ofSeconds(5));
        DeadlockApiGate gate = new DeadlockApiGate(properties);

        AtomicInteger inFlight = new AtomicInteger(0);
        AtomicInteger maxObserved = new AtomicInteger(0);
        CountDownLatch twoRunning = new CountDownLatch(2);
        CountDownLatch releaseAll = new CountDownLatch(1);

        HttpRequest request = new MockClientHttpRequest(HttpMethod.GET, URI.create("https://api.deadlock-api.com/v1/test"));

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            ClientHttpRequestExecution execution = (req, body) -> {
                int count = inFlight.incrementAndGet();
                maxObserved.accumulateAndGet(count, Math::max);
                twoRunning.countDown();
                try {
                    releaseAll.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                inFlight.decrementAndGet();
                return new ObservableResponse();
            };

            Future<ClientHttpResponse> f1 = executor.submit(() -> gate.intercept(request, new byte[0], execution));
            Future<ClientHttpResponse> f2 = executor.submit(() -> gate.intercept(request, new byte[0], execution));

            assertThat(twoRunning.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(maxObserved.get()).isEqualTo(2);

            Future<ClientHttpResponse> f3 = executor.submit(() -> gate.intercept(request, new byte[0], execution));

            while (gate.queueLength() == 0) {
                Thread.onSpinWait();
            }
            assertThat(maxObserved.get()).isEqualTo(2);

            releaseAll.countDown();
            ClientHttpResponse r1 = f1.get(5, TimeUnit.SECONDS);
            r1.close();

            ClientHttpResponse r2 = f2.get(5, TimeUnit.SECONDS);
            r2.close();

            ClientHttpResponse r3 = f3.get(5, TimeUnit.SECONDS);
            r3.close();

            assertThat(maxObserved.get()).isEqualTo(2);
        }
    }

    @Test
    void permitReleasedOnResponseClose() throws Exception {
        DeadlockApiGateProperties properties = new DeadlockApiGateProperties(1, Duration.ofSeconds(5));
        DeadlockApiGate gate = new DeadlockApiGate(properties);
        HttpRequest request = new MockClientHttpRequest(HttpMethod.GET, URI.create("https://api.deadlock-api.com/v1/test"));

        ObservableResponse obs = new ObservableResponse();
        ClientHttpResponse wrapped = gate.intercept(request, new byte[0], (req, body) -> obs);
        assertThat(gate.availablePermits()).isEqualTo(0);

        wrapped.close();
        assertThat(gate.availablePermits()).isEqualTo(1);
        assertThat(obs.closed.get()).isTrue();
        assertThat(obs.closeCount.get()).isEqualTo(1);

        // Idempotent release if closed twice
        wrapped.close();
        assertThat(gate.availablePermits()).isEqualTo(1);
        assertThat(obs.closeCount.get()).isEqualTo(2);
    }

    @Test
    void permitReleasedWhenExecuteThrows() {
        DeadlockApiGateProperties properties = new DeadlockApiGateProperties(1, Duration.ofSeconds(5));
        DeadlockApiGate gate = new DeadlockApiGate(properties);
        HttpRequest request = new MockClientHttpRequest(HttpMethod.GET, URI.create("https://api.deadlock-api.com/v1/test"));

        assertThatThrownBy(() -> gate.intercept(request, new byte[0], (req, body) -> {
            throw new IOException("network failure");
        })).isInstanceOf(IOException.class).hasMessage("network failure");

        assertThat(gate.availablePermits()).isEqualTo(1);
    }

    @Test
    void waiterExceedingAcquireTimeoutGetsIoExceptionAndDoesNotConsumePermit() throws Exception {
        DeadlockApiGateProperties properties = new DeadlockApiGateProperties(1, Duration.ofMillis(50));
        DeadlockApiGate gate = new DeadlockApiGate(properties);
        HttpRequest request = new MockClientHttpRequest(HttpMethod.GET, URI.create("https://api.deadlock-api.com/v1/analytics/item-stats?account_id=123"));

        ObservableResponse holderResponse = new ObservableResponse();
        ClientHttpResponse holder = gate.intercept(request, new byte[0], (req, body) -> holderResponse);
        assertThat(gate.availablePermits()).isEqualTo(0);

        assertThatThrownBy(() -> gate.intercept(request, new byte[0], (req, body) -> new ObservableResponse()))
                .isInstanceOf(IOException.class)
                .hasMessage("deadlock-api gate timeout");

        // Permit was not consumed
        assertThat(gate.availablePermits()).isEqualTo(0);

        holder.close();
        assertThat(gate.availablePermits()).isEqualTo(1);

        // Next caller can proceed
        ClientHttpResponse next = gate.intercept(request, new byte[0], (req, body) -> new ObservableResponse());
        assertThat(gate.availablePermits()).isEqualTo(0);
        next.close();
        assertThat(gate.availablePermits()).isEqualTo(1);
    }

    @Test
    void interruptionRestoresInterruptFlagAndThrowsIoException() throws Exception {
        DeadlockApiGateProperties properties = new DeadlockApiGateProperties(1, Duration.ofSeconds(10));
        DeadlockApiGate gate = new DeadlockApiGate(properties);
        HttpRequest request = new MockClientHttpRequest(HttpMethod.GET, URI.create("https://api.deadlock-api.com/v1/test"));

        ClientHttpResponse holder = gate.intercept(request, new byte[0], (req, body) -> new ObservableResponse());

        AtomicBoolean wasInterrupted = new AtomicBoolean(false);
        AtomicReference<Throwable> exception = new AtomicReference<>();
        CountDownLatch threadStarted = new CountDownLatch(1);

        Thread t = Thread.ofVirtual().start(() -> {
            threadStarted.countDown();
            try {
                gate.intercept(request, new byte[0], (req, body) -> new ObservableResponse());
            } catch (Throwable e) {
                wasInterrupted.set(Thread.currentThread().isInterrupted());
                exception.set(e);
            }
        });

        assertThat(threadStarted.await(5, TimeUnit.SECONDS)).isTrue();
        while (gate.queueLength() == 0) {
            Thread.onSpinWait();
        }

        t.interrupt();
        t.join(5000);

        assertThat(exception.get()).isInstanceOf(IOException.class);
        assertThat(wasInterrupted.get()).isTrue();

        holder.close();
        assertThat(gate.availablePermits()).isEqualTo(1);
    }

    @Test
    void fifoFairnessServesQueuedCallersInArrivalOrder() throws Exception {
        DeadlockApiGateProperties properties = new DeadlockApiGateProperties(1, Duration.ofSeconds(10));
        DeadlockApiGate gate = new DeadlockApiGate(properties);
        HttpRequest request = new MockClientHttpRequest(HttpMethod.GET, URI.create("https://api.deadlock-api.com/v1/test"));

        ClientHttpResponse holder = gate.intercept(request, new byte[0], (req, body) -> new ObservableResponse());

        List<String> completionOrder = new CopyOnWriteArrayList<>();

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> fA = executor.submit(() -> {
                try {
                    ClientHttpResponse r = gate.intercept(request, new byte[0], (req, b) -> new ObservableResponse());
                    completionOrder.add("A");
                    r.close();
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });

            while (gate.queueLength() < 1) {
                Thread.onSpinWait();
            }

            Future<?> fB = executor.submit(() -> {
                try {
                    ClientHttpResponse r = gate.intercept(request, new byte[0], (req, b) -> new ObservableResponse());
                    completionOrder.add("B");
                    r.close();
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });

            while (gate.queueLength() < 2) {
                Thread.onSpinWait();
            }

            Future<?> fC = executor.submit(() -> {
                try {
                    ClientHttpResponse r = gate.intercept(request, new byte[0], (req, b) -> new ObservableResponse());
                    completionOrder.add("C");
                    r.close();
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });

            while (gate.queueLength() < 3) {
                Thread.onSpinWait();
            }

            // Release holder
            holder.close();

            fA.get(5, TimeUnit.SECONDS);
            fB.get(5, TimeUnit.SECONDS);
            fC.get(5, TimeUnit.SECONDS);

            assertThat(completionOrder).containsExactly("A", "B", "C");
        }
    }

    @Test
    void mockRestServiceServerHonorsInterceptors() {
        DeadlockApiProperties apiProperties = new DeadlockApiProperties(
                "https://api.deadlock-api.com",
                Duration.ofSeconds(3),
                Duration.ofSeconds(10),
                Duration.ofSeconds(2),
                Duration.ofSeconds(10));
        DeadlockApiGateProperties gateProperties = new DeadlockApiGateProperties(1, Duration.ofSeconds(10));
        DeadlockApiGate gate = new DeadlockApiGate(gateProperties);

        RestClient.Builder builder = DeadlockApiConfig.restClientBuilder(apiProperties)
                .requestInterceptor(gate);

        MockRestServiceServer mockServer = MockRestServiceServer.bindTo(builder).build();
        mockServer.expect(requestTo("https://api.deadlock-api.com/v1/test"))
                .andRespond(withSuccess("{\"status\":\"ok\"}", MediaType.APPLICATION_JSON));

        RestClient client = builder.build();
        assertThat(gate.availablePermits()).isEqualTo(1);
        String body = client.get().uri("/v1/test").retrieve().body(String.class);
        assertThat(body).isEqualTo("{\"status\":\"ok\"}");
        // When response closed, permit is released back
        assertThat(gate.availablePermits()).isEqualTo(1);
        mockServer.verify();
    }

    @Test
    void localHttpServerSerializesParallelRestClientCalls() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger serverInFlight = new AtomicInteger(0);
        AtomicInteger maxServerConcurrency = new AtomicInteger(0);
        server.createContext("/test", exchange -> {
            int in = serverInFlight.incrementAndGet();
            maxServerConcurrency.accumulateAndGet(in, Math::max);
            try {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                byte[] response = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
            } finally {
                serverInFlight.decrementAndGet();
                exchange.close();
            }
        });
        server.start();

        try {
            int port = server.getAddress().getPort();
            DeadlockApiProperties apiProperties = new DeadlockApiProperties(
                    "http://127.0.0.1:" + port,
                    Duration.ofSeconds(3),
                    Duration.ofSeconds(10),
                    Duration.ofSeconds(2),
                    Duration.ofSeconds(10));
            DeadlockApiGateProperties gateProperties = new DeadlockApiGateProperties(1, Duration.ofSeconds(10));

            RestClient restClient = DeadlockApiConfig.restClientBuilder(apiProperties)
                    .requestInterceptor(new DeadlockApiGate(gateProperties))
                    .build();

            int callCount = 4;
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                List<Future<String>> futures = new ArrayList<>();
                for (int i = 0; i < callCount; i++) {
                    futures.add(executor.submit(() -> restClient.get()
                            .uri("/test")
                            .retrieve()
                            .body(String.class)));
                }
                for (Future<String> f : futures) {
                    assertThat(f.get(5, TimeUnit.SECONDS)).isEqualTo("{\"ok\":true}");
                }
            }
            assertThat(maxServerConcurrency.get()).isEqualTo(1);
        } finally {
            server.stop(0);
        }
    }
}
