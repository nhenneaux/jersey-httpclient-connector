package com.github.nhenneaux.jersey.connector.httpclient;

import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.Invocation;
import jakarta.ws.rs.client.InvocationCallback;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.glassfish.jersey.client.ClientConfig;
import org.glassfish.jersey.client.ClientProperties;
import org.glassfish.jersey.media.multipart.MultiPart;
import org.glassfish.jersey.media.multipart.MultiPartFeature;
import org.glassfish.jersey.media.multipart.file.FileDataBodyPart;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.both;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

@Tag("unstableGithub")
class HttpClientConnectorIT {

    private static final NoOpCallback CALLBACK = new NoOpCallback();
    private static final String JSON = "\n" +
            "\n" +
            "{\n" +
            "  \"id\": \"7875be4b-917d-4aff-8cc4-5606c36bf418\",\n" +
            "  \"name\": \"Sample Postman Collection\",\n" +
            "  \"description\": \"A sample collection to demonstrate collections as a set of related requests\",\n" +
            "  \"order\": [\n" +
            "    \"4d9134be-e8bf-4693-9cd7-1c0fc66ae739\",\n" +
            "    \"141ba274-cc50-4377-a59c-e080066f375e\"\n" +
            "  ],\n" +
            "  \"folders\": [],\n" +
            "  \"requests\": [\n" +
            "    {\n" +
            "      \"id\": \"4d9134be-e8bf-4693-9cd7-1c0fc66ae739\",\n" +
            "      \"name\": \"A simple GET request\",\n" +
            "      \"collectionId\": \"877b9dae-a50e-4152-9b89-870c37216f78\",\n" +
            "      \"method\": \"GET\",\n" +
            "      \"headers\": \"\",\n" +
            "      \"data\": [],\n" +
            "      \"rawModeData\": \"\",\n" +
            "      \"tests\": \"tests['response code is 200'] = (responseCode.code === 200);\",\n" +
            "      \"preRequestScript\": \"\",\n" +
            "      \"url\": \"https://postman-echo.com/get?source=newman-sample-github-collection\"\n" +
            "    },\n" +
            "    {\n" +
            "      \"id\": \"141ba274-cc50-4377-a59c-e080066f375e\",\n" +
            "      \"name\": \"A simple POST request\",\n" +
            "      \"collectionId\": \"877b9dae-a50e-4152-9b89-870c37216f78\",\n" +
            "      \"method\": \"POST\",\n" +
            "      \"headers\": \"Content-Type: text/plain\",\n" +
            "      \"dataMode\": \"raw\",\n" +
            "      \"data\": [],\n" +
            "      \"rawModeData\": \"Duis posuere augue vel cursus pharetra. In luctus a ex nec pretium...\",\n" +
            "      \"url\": \"https://postman-echo.com/post\"\n" +
            "    }\n" +
            "  ]\n" +
            "}\n" +
            "\n";


    private static final String HTTPS_DEVOXX_BE = "https://devoxx.be";

    @Test
    void shouldWorkWithJaxRsClientForStatus() {
        final Client client = ClientBuilder.newClient(new ClientConfig().connectorProvider((jaxRsClient, config) -> new HttpClientConnector(HttpClient.newBuilder().sslContext(jaxRsClient.getSslContext()).build())));
        final WebTarget target = client.target(HTTPS_DEVOXX_BE);
        final Response response = target.request().get();
        assertEquals(200, response.getStatus());
    }

    @Test
    void shouldWorkWithJaxRsClientForStatusWithMethodReference() {
        final Client client = ClientBuilder.newClient(new ClientConfig().connectorProvider(HttpClientConnector::new));
        final WebTarget target = client.target(HTTPS_DEVOXX_BE);
        final Response response = target.request().get();
        assertEquals(200, response.getStatus());
    }

    @Test
    void shouldWorkWithJaxRsClientForStatusAsync() throws InterruptedException, ExecutionException, TimeoutException {
        final Client client = ClientBuilder.newClient(new ClientConfig().connectorProvider((jaxRsClient, config) -> new HttpClientConnector(HttpClient.newBuilder().sslContext(jaxRsClient.getSslContext()).build())));
        final WebTarget target = client.target(HTTPS_DEVOXX_BE);
        final Response response = target.request().async().get().get(2, TimeUnit.SECONDS);
        assertEquals(200, response.getStatus());
    }

    @Test
    void shouldWorkWithJaxRsClientForStatusAsyncWithCallback() throws InterruptedException, ExecutionException, TimeoutException {
        final Client client = ClientBuilder.newClient(new ClientConfig().connectorProvider((jaxRsClient, config) -> new HttpClientConnector(HttpClient.newBuilder().sslContext(jaxRsClient.getSslContext()).build())));
        final WebTarget target = client.target(HTTPS_DEVOXX_BE);
        final Response response = target.request().async().get(CALLBACK).get(2, TimeUnit.SECONDS);
        assertEquals(200, response.getStatus());
    }

    @Test
    void shouldWorkWithJaxRsClientForStatusAsyncWithCallbackCheck() throws InterruptedException, ExecutionException, TimeoutException {
        final Client client = ClientBuilder.newClient(new ClientConfig().connectorProvider((jaxRsClient, config) -> new HttpClientConnector(HttpClient.newBuilder().sslContext(jaxRsClient.getSslContext()).build())));
        final WebTarget target = client.target(HTTPS_DEVOXX_BE);
        final AtomicReference<Response> objectAtomicReference = new AtomicReference<>();
        final Response response = target.request().async().get(new InvocationCallback<Response>() {
            @Override
            public void completed(Response response) {
                objectAtomicReference.set(response);
            }

            @Override
            public void failed(Throwable throwable) {
// Ignored
            }
        }).get(2, TimeUnit.SECONDS);

        await().atMost(Duration.ofSeconds(1L))
                .until(() -> objectAtomicReference.get() != null);

        assertSame(response, objectAtomicReference.get());
        assertEquals(200, response.getStatus());
        assertEquals(200, objectAtomicReference.get().getStatus());
    }

    @Test
    void shouldWorkWithJaxRsClientForString() {
        final Client client = ClientBuilder.newClient(new ClientConfig().connectorProvider((jaxRsClient, config) -> new HttpClientConnector(HttpClient.newBuilder().sslContext(jaxRsClient.getSslContext()).build())));
        final WebTarget target = client.target(HTTPS_DEVOXX_BE);
        final Response response = target.request().get();
        assertNotNull(response.readEntity(String.class));
    }

    @Test
    void shouldWorkWithJaxRsClientForStringForTwoHundredRequests() {
        final Client client = ClientBuilder.newClient(new ClientConfig().connectorProvider((jaxRsClient, config) -> new HttpClientConnector(HttpClient.newBuilder().sslContext(jaxRsClient.getSslContext()).version(HttpClient.Version.HTTP_2).build())));
        final WebTarget target = client.target(HTTPS_DEVOXX_BE);
        for (int i = 0; i < 200; i++) {
            try (final Response response = target.request().get()) {
                response.readEntity(String.class);
                assertEquals(200, response.getStatus());
            }
        }
    }

    @Test
    void shouldWorkWithJaxRsClientWithPost() {
        final Client client = ClientBuilder.newClient(new ClientConfig().connectorProvider((jaxRsClient, config) -> new HttpClientConnector(HttpClient.newBuilder().sslContext(jaxRsClient.getSslContext()).build())));
        final WebTarget target = client.target(HTTPS_DEVOXX_BE);
        try (final Response response = target.request().post(Entity.json(JSON))) {

            assertEquals(200, response.getStatus());
            assertNotNull(response.readEntity(String.class));
        }
    }

    @Test
    void shouldWorkWithJaxRsClientWithMethodPost() {
        final Client client = ClientBuilder.newClient(new ClientConfig().connectorProvider((jaxRsClient, config) -> new HttpClientConnector(HttpClient.newBuilder().sslContext(jaxRsClient.getSslContext()).build())));
        final WebTarget target = client.target(HTTPS_DEVOXX_BE);
        final Response response = target.request()
                .method("POST", Entity.json(JSON));
        assertEquals(200, response.getStatus());
        assertNotNull(response.readEntity(String.class));
        response.close();
    }

    @Test
    void shouldWorkWithJaxRsClientWithJsonPost() {
        final Client client = ClientBuilder.newClient(new ClientConfig().connectorProvider((jaxRsClient, config) -> new HttpClientConnector(HttpClient.newBuilder().sslContext(jaxRsClient.getSslContext()).build())));
        final WebTarget target = client.target(HTTPS_DEVOXX_BE);
        try (final Response response = target.request().post(Entity.entity(JSON, MediaType.APPLICATION_JSON_TYPE))) {

            assertEquals(200, response.getStatus());
            assertNotNull(response.readEntity(String.class));
        }
    }

    @Test
    @Timeout(2L)
    void shouldWorkWithJaxRsClientWithJsonPostAndShortTimeout() {
        final Client client = ClientBuilder.newClient(new ClientConfig().connectorProvider((jaxRsClient, config) -> new HttpClientConnector(HttpClient.newBuilder().sslContext(jaxRsClient.getSslContext()).build())));
        client.property(ClientProperties.READ_TIMEOUT, 10);
        final WebTarget target = client.target(HTTPS_DEVOXX_BE);
        final var request = target.request();
        final Exception expectedException = Assertions.assertThrows(Exception.class,
                () -> {
                    //noinspection EmptyTryBlock
                    try (var ignored = request.post(Entity.entity(JSON, MediaType.APPLICATION_JSON_TYPE))){
                        // nothing to do
                    }
                });
        assertEquals(HttpConnectTimeoutException.class, expectedException.getCause().getClass());
    }

    @Test
    @Timeout(3L)
    void shouldWorkWithJaxRsClientWithLongTimeoutFailure() {
        final var timeout = 1_000;
        final Client client = ClientBuilder.newClient(new ClientConfig().connectorProvider((jaxRsClient, config) -> new HttpClientConnector(HttpClient.newBuilder().sslContext(jaxRsClient.getSslContext()).build())))
                .property(ClientProperties.READ_TIMEOUT, timeout);

        final WebTarget target = client.target("https://httpstat.us")
                .path("200")
                .queryParam("sleep", "5000");

        final var start = System.nanoTime();
        Invocation.Builder request = target.request();
        final var margin = 400L;

        final ProcessingException processingException = Assertions.assertThrows(ProcessingException.class, request::get);

        final var durationInMillis = Duration.ofNanos(System.nanoTime() - start).toMillis();
        assertThat(durationInMillis, is(both(greaterThan(timeout - margin)).and(lessThan(timeout + margin))));
        System.out.println("end in " + durationInMillis + " ms");
        assertThat(processingException.getCause(), Matchers.anyOf(Matchers.instanceOf(HttpConnectTimeoutException.class), Matchers.instanceOf(HttpTimeoutException.class)));

    }

    @Test
    @Timeout(2L)
    void shouldWorkWithJaxRsClientWithLongTimeoutSuccess() {
        final Client client = ClientBuilder.newClient(new ClientConfig().connectorProvider((jaxRsClient, config) -> new HttpClientConnector(HttpClient.newBuilder().sslContext(jaxRsClient.getSslContext()).build())));
        client.property(ClientProperties.READ_TIMEOUT, 2_000);
        final WebTarget target = client.target("https://httpstat.us")
                .path("200")
                .queryParam("sleep", "500");

        final Response response = target.request().get();

        // Then
        assertEquals(200, response.getStatus());
    }

    @Test
    @Timeout(5L)
    void shouldWorkWithJaxRsClientWithLongBuilderTimeoutFailure() {
        final Client client = ClientBuilder.newBuilder()
                .withConfig(new ClientConfig().connectorProvider((jaxRsClient, config) -> new HttpClientConnector(HttpClient.newBuilder().sslContext(jaxRsClient.getSslContext()).build())))
                .readTimeout(1L, TimeUnit.SECONDS)
                .build();
        final WebTarget target = client.target("https://httpstat.us")
                .path("200")
                .queryParam("sleep", "5000");

        Invocation.Builder request = target.request();
        final ProcessingException processingException = Assertions.assertThrows(ProcessingException.class, request::get);
        assertEquals(HttpTimeoutException.class, processingException.getCause().getClass());

    }

    @Test
    @Timeout(5L)
    void shouldWorkWithJaxRsClientWithLongBuilderTimeoutSuccess() {
        final Client client = ClientBuilder.newBuilder()
                .withConfig(new ClientConfig().connectorProvider((jaxRsClient, config) -> new HttpClientConnector(HttpClient.newBuilder().sslContext(jaxRsClient.getSslContext()).build())))
                .readTimeout(2L, TimeUnit.SECONDS)
                .build();
        final WebTarget target = client.target("https://httpstat.us")
                .path("200")
                .queryParam("sleep", "500");

        final Response response = target.request().get();

        // Then
        assertEquals(200, response.getStatus());
    }

    @Test
    void shouldWorkWithJaxRsClientWithJsonPostAsync() throws ExecutionException, InterruptedException, TimeoutException {
        final Client client = ClientBuilder.newClient(new ClientConfig().connectorProvider((jaxRsClient, config) -> new HttpClientConnector(HttpClient.newBuilder().sslContext(jaxRsClient.getSslContext()).build())));
        final WebTarget target = client.target(HTTPS_DEVOXX_BE);
        final Future<Response> responseFuture = target.request().async().post(Entity.json(JSON));
        final Response response = responseFuture.get(2, TimeUnit.SECONDS);
        assertEquals(200, response.getStatus());
        assertNotNull(response.readEntity(String.class));
    }

    @Test
    void shouldWorkWithJaxRsClientWithJsonPostAsyncWithCallback() throws ExecutionException, InterruptedException, TimeoutException {
        final Client client = ClientBuilder.newClient(new ClientConfig().connectorProvider((jaxRsClient, config) -> new HttpClientConnector(HttpClient.newBuilder().sslContext(jaxRsClient.getSslContext()).build())));
        final WebTarget target = client.target(HTTPS_DEVOXX_BE);
        final Future<Response> responseFuture = target.request().async().post(Entity.json(JSON), CALLBACK);
        final Response response = responseFuture.get(2, TimeUnit.SECONDS);
        assertEquals(200, response.getStatus());
        assertNotNull(response.readEntity(String.class));
    }

    @Test
    void shouldWorkWithJaxRsClientWithJsonPostAsyncWithCallbackCheck() throws ExecutionException, InterruptedException, TimeoutException {
        final Client client = ClientBuilder.newClient(new ClientConfig().connectorProvider((jaxRsClient, config) -> new HttpClientConnector(HttpClient.newBuilder().sslContext(jaxRsClient.getSslContext()).build())));
        final WebTarget target = client.target(HTTPS_DEVOXX_BE);

        final AtomicReference<Response> objectAtomicReference = new AtomicReference<>();
        final Future<Response> responseFuture = target.request().async().post(Entity.json(JSON), new InvocationCallback<>() {
            @Override
            public void completed(Response response) {
                objectAtomicReference.set(response);
            }

            @Override
            public void failed(Throwable throwable) {
                // ignored
            }
        });
        final Response response = responseFuture.get(2, TimeUnit.SECONDS);

        await().atMost(Duration.ofSeconds(1L))
                .until(() -> objectAtomicReference.get() != null);
        assertSame(response, objectAtomicReference.get());
        assertEquals(200, response.getStatus());
        assertEquals(200, objectAtomicReference.get().getStatus());
        assertNotNull(response.readEntity(String.class));
    }

    @Test
    void shouldWorkWithJaxRsClientWithStreamPost() throws IOException {
        final ClientConfig configuration = new ClientConfig().connectorProvider((jaxRsClient, config) -> new HttpClientConnector(HttpClient.newBuilder().sslContext(jaxRsClient.getSslContext()).build()));
        configuration.register(MultiPartFeature.class);
        final Client client = ClientBuilder.newClient(configuration);
        final WebTarget target = client.target(HTTPS_DEVOXX_BE);

        final Path file = Files.createTempFile("shouldWorkWithJaxRsClientWithStreamPost", ".json");
        Files.write(file, JSON.getBytes(StandardCharsets.UTF_8));

        MultiPart multiPart = new MultiPart();
        multiPart.setMediaType(MediaType.MULTIPART_FORM_DATA_TYPE);

        FileDataBodyPart fileDataBodyPart = new FileDataBodyPart("file",
                file.toFile(),
                MediaType.APPLICATION_OCTET_STREAM_TYPE);
        multiPart.bodyPart(fileDataBodyPart);

        try (Response response = target.request(MediaType.APPLICATION_JSON_TYPE)
                .post(Entity.entity(multiPart, multiPart.getMediaType()))) {
            assertEquals(200, response.getStatus());
            assertNotNull(response.readEntity(String.class));
        }
    }

    private static class NoOpCallback implements InvocationCallback<Response> {
        @Override
        public void completed(Response o) {
            // Completing the response
        }

        @Override
        public void failed(Throwable throwable) {
            // Failed to response

        }
    }
}