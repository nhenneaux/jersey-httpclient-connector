package com.github.nhenneaux.jersey.connector.httpclient;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.glassfish.jersey.client.ClientConfig;
import org.glassfish.jersey.client.ClientProperties;
import org.jboss.weld.environment.se.Weld;
import org.jboss.weld.environment.se.WeldContainer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import javax.ws.rs.HttpMethod;
import javax.ws.rs.ProcessingException;
import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.Entity;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.http.HttpClient;
import java.security.KeyStore;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static com.github.nhenneaux.jersey.connector.httpclient.JettyServer.TlsSecurityConfiguration.getKeyStore;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SuppressWarnings("squid:S00112")
class JettyServerTest {
    static final int PORT = 2223;
    private static final String PING = "/ping";

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
    }    static WebTarget getClientChunk(int port) {
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
        int port = PORT;
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
        int port = PORT;
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
    }    @Test
    @Timeout(20)
    void testPostChunk() throws Exception {
        int port = PORT;
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
        int port = PORT;
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
        int port = PORT;
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
        int port = PORT;
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
    }    @Test
    @Timeout(20)
    void testPostStringChunk() throws Exception {
        int port = PORT;
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
        int port = PORT;
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
        int port = PORT;
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
        int port = PORT;
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
        int port = PORT;
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
        int port = PORT;
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
        int port = PORT;
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
    @Timeout(60)
    void testConcurrentHttp1() throws Exception {
        testConcurrent(new ClientConfig());
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
        int port = PORT;
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
            client.target("https://localhost:" + port).path(path).request().method(method).close();

            AtomicInteger counter = new AtomicInteger();
            final Runnable runnable = () -> {
                long start = System.nanoTime();
                for (int i = 0; i < iterations; i++) {
                    try (Response response = client
                            .target("https://localhost:" + port).path(path).request().method(method)) {
                        response.readEntity(InputStream.class).readAllBytes();
                        response.getStatus();
                        counter.incrementAndGet();
                        int reportEveryRequests = 1_000;
                        if (i % reportEveryRequests == 0) {
                            System.out.println(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) * 1.0 / reportEveryRequests);
                            start = System.nanoTime();
                        }
                    } catch (IOException e) {
                        throw new IllegalStateException(e);
                    }
                }
            };
            Thread.setDefaultUncaughtExceptionHandler((t1, e) -> e.printStackTrace());
            final Set<Thread> threads = IntStream
                    .range(0, nThreads)
                    .mapToObj(i -> runnable)
                    .map(Thread::new)
                    .collect(Collectors.toSet());

            threads.forEach(Thread::start);


            for (Thread thread : threads) {
                thread.join();
            }

            assertEquals((long) nThreads * iterations, counter.get());

        }
    }


    @Test
    void shouldWorkInLoop() throws Exception {
        int port = PORT;
        JettyServer.TlsSecurityConfiguration tlsSecurityConfiguration = tlsConfig();
        for (int i = 0; i < 100; i++) {
            try (
                    @SuppressWarnings("unused") WeldContainer container = new Weld().initialize();
                    AutoCloseable ignored = jerseyServer(port, tlsSecurityConfiguration, DummyRestService.class)
            ) {
                try (final var head = getClient(port).path(PING).request().head()) {
                    assertEquals(204, head.getStatus());
                }
            }
        }
    }


}