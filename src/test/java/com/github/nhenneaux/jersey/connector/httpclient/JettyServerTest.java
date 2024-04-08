package com.github.nhenneaux.jersey.connector.httpclient;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.HttpMethod;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.glassfish.jersey.client.ClientConfig;
import org.glassfish.jersey.client.ClientProperties;
import org.glassfish.jersey.client.HttpUrlConnectorProvider;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.*;

import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.http.HttpClient;
import java.security.KeyStore;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static com.github.nhenneaux.jersey.connector.httpclient.JettyServer.TlsSecurityConfiguration.getKeyStore;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import javax.net.ServerSocketFactory;
import java.util.function.IntPredicate;


@SuppressWarnings("squid:S00112")
class JettyServerTest {
    private static final Set<Integer> alreadyProvidedPort = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private static final String PING = "/ping";

    @BeforeEach
    void setUp(TestInfo testInfo) {
        var testClass = testInfo.getTestClass().orElseThrow();
        var testMethod = testInfo.getTestMethod().orElseThrow();
        System.out.println(testClass.getSimpleName() + "::" + testMethod.getName() + " test has started.");
    }

    @AfterEach
    void tearDown(TestInfo testInfo) {
        var testClass = testInfo.getTestClass().orElseThrow();
        var testMethod = testInfo.getTestMethod().orElseThrow();
        System.out.println(testClass.getSimpleName() + "::" + testMethod.getName() + " test has finished.");
    }

    /**
     * Return an available (not used by another process) port between 49152 and 65535.
     * Beware, that the port can be used by another thread between the check this method is doing and its usage by the caller. This means that this method only provide a light guarantee about the availability of the port.
     *
     * @return a port between 49152 and 65535
     */
    @SuppressWarnings({"findsecbugs:PREDICTABLE_RANDOM", "java:S2245"})
    // "The use of java.util.concurrent.ThreadLocalRandom is predictable"
    // This is not a cryptography/secure logic, so we ignore this warning
    public static int findAvailablePort() {
        final IntStream interval = ThreadLocalRandom.current().ints(49152, 65535);
        return findAvailablePort(interval);
    }

    static int findAvailablePort(final IntStream interval) {
        return findAvailablePort(interval, new AvailablePortTester());
    }

    static int findAvailablePort(final IntStream interval, final IntPredicate predicate) {
        return interval
                .filter(predicate)
                .filter(alreadyProvidedPort::add)
                .findFirst()
                .orElseThrow(IllegalStateException::new);
    }

    static class AvailablePortTester implements IntPredicate {

        private final ServerSocketFactory factory;

        private AvailablePortTester() {
            this(ServerSocketFactory.getDefault());
        }

        AvailablePortTester(final ServerSocketFactory factory) {
            this.factory = factory;
        }


        @Override
        @SuppressWarnings("squid:S1166")
        public boolean test(final int port) {
            try {
                factory.createServerSocket(port).close();
                return true;
            } catch (IOException e) {
                return false;
            }
        }

    }


    private static WebTarget getClient(int port, KeyStore trustStore, ClientConfig clientConfig) {
        return ClientBuilder.newBuilder()
                .trustStore(trustStore)
                .withConfig(clientConfig)
                .build()
                .target("https://localhost:" + port);
    }

    private static ClientConfig http2ClientConfig() {
        return new ClientConfig()
                .connectorProvider(HttpClientConnector::new);
    }

    static AutoCloseable jerseyServer(int port, JettyServer.TlsSecurityConfiguration tlsSecurityConfiguration, final Class<?>... serviceClasses) {
        return new JettyServer(port, tlsSecurityConfiguration, serviceClasses);
    }

    static WebTarget getClient(int port) {
        return getClient(port, trustStore(), http2ClientConfig());
    }

    static WebTarget getClientChunk(int port) {
        return getClient(port, trustStore(), http2ClientConfig().property(ClientProperties.REQUEST_ENTITY_PROCESSING, "CHUNKED"));
    }

    private static KeyStore trustStore() {
        return getKeyStore("TEST==ONLY==truststore-password".toCharArray(), "truststore.p12");
    }

    static JettyServer.TlsSecurityConfiguration tlsConfig() {
        return new JettyServer.TlsSecurityConfiguration(
                getKeyStore("TEST==ONLY==key-store-password".toCharArray(), "keystore.p12"),
                "localhost with alternate ip",
                "TEST==ONLY==key-store-password",
                "TLSv1.3"
        );
    }

    @Test
    @Timeout(20)
    void testValidTls() throws Exception {
        int port = findAvailablePort();
        JettyServer.TlsSecurityConfiguration tlsSecurityConfiguration = tlsConfig();
        try (AutoCloseable ignored = jerseyServer(
                port,
                tlsSecurityConfiguration,
                DummyRestService.class)) {
            try (final Response ping = getClient(port).path(PING).request().head()) {
                assertEquals(204, ping.getStatus());
            }
        }
    }

    @Test
    @Timeout(20)
    void testPost() throws Exception {
        int port = findAvailablePort();
        JettyServer.TlsSecurityConfiguration tlsSecurityConfiguration = tlsConfig();
        try (AutoCloseable ignored = jerseyServer(
                port,
                tlsSecurityConfiguration,
                DummyRestService.class)) {
            String data = UUID.randomUUID().toString();
            try (final Response response = getClient(port).path("post").request().post(Entity.json(new DummyRestService.Data(data)))) {
                assertEquals(200, response.getStatus());
                assertEquals(data, response.readEntity(DummyRestService.Data.class).getData());
            }
        }
    }

    @Test
    @Timeout(20)
    void testPostChunk() throws Exception {
        int port = findAvailablePort();
        JettyServer.TlsSecurityConfiguration tlsSecurityConfiguration = tlsConfig();
        try (AutoCloseable ignored = jerseyServer(
                port,
                tlsSecurityConfiguration,
                DummyRestService.class)) {
            String data = UUID.randomUUID().toString();
            try (final Response response = getClientChunk(port).path("post").request().post(Entity.json(new DummyRestService.Data(data)))) {
                assertEquals(200, response.getStatus());
                assertEquals(data, response.readEntity(DummyRestService.Data.class).getData());
            }
        }
    }

    @Test
    @Timeout(20)
    void testPostAsync() throws Exception {
        int port = findAvailablePort();
        JettyServer.TlsSecurityConfiguration tlsSecurityConfiguration = tlsConfig();
        try (AutoCloseable ignored = jerseyServer(
                port,
                tlsSecurityConfiguration,
                DummyRestService.class)) {
            String data = UUID.randomUUID().toString();
            try (final Response response = getClient(port).path("post").request().async().post(Entity.json(new DummyRestService.Data(data))).get()) {
                assertEquals(200, response.getStatus());
                assertEquals(data, response.readEntity(DummyRestService.Data.class).getData());
            }
        }
    }

    @Test
    @Timeout(20)
    void testPostAsyncChunk() throws Exception {
        int port = findAvailablePort();
        JettyServer.TlsSecurityConfiguration tlsSecurityConfiguration = tlsConfig();
        try (AutoCloseable ignored = jerseyServer(
                port,
                tlsSecurityConfiguration,
                DummyRestService.class)) {
            String data = UUID.randomUUID().toString();
            try (final Response response = getClientChunk(port).path("post").request().async().post(Entity.json(new DummyRestService.Data(data))).get()) {
                assertEquals(200, response.getStatus());
                assertEquals(data, response.readEntity(DummyRestService.Data.class).getData());
            }
        }
    }

    @Test
    @Timeout(20)
    void testPostString() throws Exception {
        int port = findAvailablePort();
        JettyServer.TlsSecurityConfiguration tlsSecurityConfiguration = tlsConfig();
        try (AutoCloseable ignored = jerseyServer(
                port,
                tlsSecurityConfiguration,
                DummyRestService.class)) {
            String data = UUID.randomUUID().toString();

            final var objectMapper = new ObjectMapper();
            final var valueAsString = objectMapper.writeValueAsString(new DummyRestService.Data(data));
            try (final Response response = getClient(port).path("post").request().post(Entity.entity(
                    valueAsString,
                    MediaType.APPLICATION_JSON_TYPE
            ))) {
                assertEquals(200, response.getStatus());
                assertEquals(data, response.readEntity(DummyRestService.Data.class).getData());
            }
        }
    }

    @Test
    @Timeout(20)
    void testPostStringChunk() throws Exception {
        int port = findAvailablePort();
        JettyServer.TlsSecurityConfiguration tlsSecurityConfiguration = tlsConfig();
        try (AutoCloseable ignored = jerseyServer(
                port,
                tlsSecurityConfiguration,
                DummyRestService.class)) {
            String data = UUID.randomUUID().toString();

            final var objectMapper = new ObjectMapper();
            final var valueAsString = objectMapper.writeValueAsString(new DummyRestService.Data(data));
            try (final Response response = getClientChunk(port).path("post").request().post(Entity.entity(
                    valueAsString,
                    MediaType.APPLICATION_JSON_TYPE
            ))) {
                assertEquals(200, response.getStatus());
                assertEquals(data, response.readEntity(DummyRestService.Data.class).getData());
            }
        }
    }

    @Test
    @Timeout(20)
    void testPostBytes() throws Exception {
        int port = findAvailablePort();
        JettyServer.TlsSecurityConfiguration tlsSecurityConfiguration = tlsConfig();
        try (AutoCloseable ignored = jerseyServer(
                port,
                tlsSecurityConfiguration,
                DummyRestService.class)) {
            String data = UUID.randomUUID().toString();

            final var objectMapper = new ObjectMapper();
            final var writeValueAsBytes = objectMapper.writeValueAsBytes(new DummyRestService.Data(data));
            try (final Response response = getClient(port).path("post").request().post(Entity.entity(
                    writeValueAsBytes,
                    MediaType.APPLICATION_JSON_TYPE
            ))) {
                assertEquals(200, response.getStatus());
                assertEquals(data, response.readEntity(DummyRestService.Data.class).getData());
            }
        }
    }

    @Test
    @Timeout(20)
    void testPostBytesChunk() throws Exception {
        int port = findAvailablePort();
        JettyServer.TlsSecurityConfiguration tlsSecurityConfiguration = tlsConfig();
        try (AutoCloseable ignored = jerseyServer(
                port,
                tlsSecurityConfiguration,
                DummyRestService.class)) {
            String data = UUID.randomUUID().toString();

            final var objectMapper = new ObjectMapper();
            final var writeValueAsBytes = objectMapper.writeValueAsBytes(new DummyRestService.Data(data));
            try (final Response response = getClientChunk(port).path("post").request().post(Entity.entity(
                    writeValueAsBytes,
                    MediaType.APPLICATION_JSON_TYPE
            ))) {
                assertEquals(200, response.getStatus());
                assertEquals(data, response.readEntity(DummyRestService.Data.class).getData());
            }
        }
    }


    @Test
    @Timeout(20)
    void testMethodPost() throws Exception {
        int port = findAvailablePort();
        JettyServer.TlsSecurityConfiguration tlsSecurityConfiguration = tlsConfig();
        try (AutoCloseable ignored = jerseyServer(
                port,
                tlsSecurityConfiguration,
                DummyRestService.class)) {
            String data = UUID.randomUUID().toString();
            try (final Response response = getClient(port).path("post").request()
                    .method("POST", Entity.json(new DummyRestService.Data(data)))) {
                assertEquals(200, response.getStatus());
                assertEquals(data, response.readEntity(DummyRestService.Data.class).getData());
            }
        }
    }

    @Test
    @Timeout(20)
    void testMethodPostChunk() throws Exception {
        int port = findAvailablePort();
        JettyServer.TlsSecurityConfiguration tlsSecurityConfiguration = tlsConfig();
        try (AutoCloseable ignored = jerseyServer(
                port,
                tlsSecurityConfiguration,
                DummyRestService.class)) {
            String data = UUID.randomUUID().toString();
            try (final Response response = getClientChunk(port).path("post").request()
                    .method("POST", Entity.json(new DummyRestService.Data(data)))) {
                assertEquals(200, response.getStatus());
                assertEquals(data, response.readEntity(DummyRestService.Data.class).getData());
            }
        }
    }

    @Test
    @Timeout(20)
    void testConnectTimeout() throws Exception {
        int port = findAvailablePort();
        JettyServer.TlsSecurityConfiguration tlsSecurityConfiguration = tlsConfig();
        //noinspection EmptyTryBlock
        try (AutoCloseable ignored = jerseyServer(
                port,
                tlsSecurityConfiguration,
                DummyRestService.class)) {
            // nothing to do but re-use the port after stop

        }
        String data = UUID.randomUUID().toString();

        final var request = getClient(port).path("post").request();
        final var json = Entity.json(new DummyRestService.Data(data));
        final var processingException = assertThrows(ProcessingException.class, () -> {
            //noinspection EmptyTryBlock
            try (final Response ignored = request.method("POST", json)) {
                // nothing to do expecting exception
            }
        });
        assertEquals(ConnectException.class, processingException.getCause().getClass());
    }

    @Test
    @Timeout(20)
    void testConnectTimeoutChunk() throws Exception {
        int port = findAvailablePort();
        JettyServer.TlsSecurityConfiguration tlsSecurityConfiguration = tlsConfig();
        //noinspection EmptyTryBlock
        try (AutoCloseable ignored = jerseyServer(
                port,
                tlsSecurityConfiguration,
                DummyRestService.class)) {
            // nothing to do but re-use the port after stop

        }
        String data = UUID.randomUUID().toString();

        final var request = getClientChunk(port).path("post").request();
        final var json = Entity.json(new DummyRestService.Data(data));
        final var processingException = assertThrows(ProcessingException.class, () -> {
            //noinspection EmptyTryBlock
            try (final Response ignored = request.method("POST", json)) {
                // nothing to do expecting exception
            }
        });
        assertEquals(ConnectException.class, processingException.getCause().getCause().getCause().getClass());
    }


    @Test
    @Timeout(60)
    void testConcurrent() throws Exception {
        testConcurrent(http2ClientConfig());
    }

    @Test
    @Timeout(120)
    void testConcurrentHttpUrlConnectionHttp1() throws Exception {
        if (!System.getProperty("os.name").toLowerCase().contains("mac")) { // Broken on MacOS with java.net.SocketException: Too many open files
            testConcurrent(new ClientConfig());
        }
    }

    @Test
    @Timeout(60)
    void testConcurrentHttp2JavaHttpClient() throws Exception {
        testConcurrent(new ClientConfig()
                .connectorProvider((jaxRsClient, configuration) -> getHttpClientConnector(jaxRsClient, HttpClient.Version.HTTP_2)));
    }

    @Test
    @Timeout(60)
    void testConcurrentGetHttp2JavaHttpClient() throws Exception {
        testConcurrent(new ClientConfig()
                .connectorProvider((jaxRsClient, configuration) -> getHttpClientConnector(jaxRsClient, HttpClient.Version.HTTP_2)), HttpMethod.GET, "/pingWithSleep");
    }

    @Test
    @Timeout(60)
    void testConcurrentHttp1JavaHttpClient() throws Exception {
        testConcurrent(new ClientConfig()
                .connectorProvider((jaxRsClient, configuration) -> getHttpClientConnector(jaxRsClient, HttpClient.Version.HTTP_1_1)));
    }

    private HttpClientConnector getHttpClientConnector(Client jaxRsClient, HttpClient.Version version) {
        return new HttpClientConnector(HttpClient.newBuilder().sslContext(jaxRsClient.getSslContext()).version(version).build());
    }


    private void testConcurrent(ClientConfig clientConfig) throws Exception {
        testConcurrent(clientConfig, "HEAD", PING);
    }

    private void testConcurrent(ClientConfig clientConfig, String method, String path) throws Exception {
        int port = findAvailablePort();
        JettyServer.TlsSecurityConfiguration tlsSecurityConfiguration = tlsConfig();
        final KeyStore truststore = trustStore();
        try (AutoCloseable ignored = jerseyServer(
                port,
                tlsSecurityConfiguration,
                DummyRestService.class)) {
            final int nThreads = 4;
            final int iterations = 10_000;
            // Warmup
            Client client = ClientBuilder.newBuilder()
                    .trustStore(truststore)
                    .withConfig(clientConfig)
                    .build();
            final var webTarget = client.target("https://localhost:" + port).path(path);
            webTarget.request().method(method).close();

            AtomicInteger counter = new AtomicInteger();
            final Runnable runnable = () -> {
                long start = System.nanoTime();
                for (int i = 0; i < iterations; i++) {
                    try (Response response = webTarget.request().method(method)) {
                        response.readEntity(InputStream.class).readAllBytes();
                        response.getStatus();
                        counter.incrementAndGet();
                        int reportEveryRequests = 1_000;
                        if (i % reportEveryRequests == 0) {
                            System.out.println(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) * 1.0 / reportEveryRequests);
                            start = System.nanoTime();
                        }
                    } catch (ProcessingException | IOException e) {
                        if (e.getMessage().contains("GOAWAY")
                                || e.getMessage().contains("Broken pipe") //  The HTTP sending process failed with error, Broken pipe
                                || e.getMessage().contains("EOF reached while reading")
                                || e.getMessage().contains(" cancelled")) {//  The HTTP sending process failed with error, Stream 673 cancelled
                            i--;
                        } else {
                        throw new IllegalStateException(e);
                    }
                }
                }
            };
            List<Throwable> thrown = new ArrayList<>();
            Thread.setDefaultUncaughtExceptionHandler((t1, e) -> {
                thrown.add(e);
                e.printStackTrace();
            });
            final Set<Thread> threads = IntStream
                    .range(0, nThreads)
                    .mapToObj(i -> runnable)
                    .map(Thread::new)
                    .collect(Collectors.toSet());

            threads.forEach(Thread::start);


            for (Thread thread : threads) {
                thread.join();
            }
            assertThat(thrown, Matchers.empty());
            assertEquals((long) nThreads * iterations, counter.get());

        }
    }


    @Test
    void shouldWorkInLoop() throws Exception {
        int port = findAvailablePort();
        JettyServer.TlsSecurityConfiguration tlsSecurityConfiguration = tlsConfig();
        for (int i = 0; i < 100; i++) {
            try (
                    var ignored = jerseyServer(port, tlsSecurityConfiguration, DummyRestService.class);
                    final var head = getClient(port).path(PING).request().head()
            ) {
                assertEquals(204, head.getStatus());
            }
        }
    }


}