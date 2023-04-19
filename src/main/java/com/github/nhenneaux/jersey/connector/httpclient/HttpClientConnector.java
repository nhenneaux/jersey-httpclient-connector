package com.github.nhenneaux.jersey.connector.httpclient;

import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.core.Configuration;
import jakarta.ws.rs.core.Response;
import org.glassfish.jersey.client.ClientProperties;
import org.glassfish.jersey.client.ClientRequest;
import org.glassfish.jersey.client.ClientResponse;
import org.glassfish.jersey.client.spi.AsyncConnectorCallback;
import org.glassfish.jersey.client.spi.Connector;
import org.glassfish.jersey.message.internal.Statuses;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.glassfish.jersey.client.ClientProperties.CONNECT_TIMEOUT;
import static org.glassfish.jersey.client.ClientProperties.PROXY_URI;
import static org.glassfish.jersey.client.ClientProperties.READ_TIMEOUT;

/**
 * Jersey connector for java.net.http.HttpClient.
 * <p>
 * To build a JAX-RS client, you can use the following.
 * <pre><code>
 * var client = ClientBuilder.newClient(new ClientConfig().connectorProvider(HttpClientConnector::new))
 * </code></pre>
 * If you want to customise the Java HTTP client you are using, you can use the following.
 * <pre><code>
 * var httpClient = HttpClient.newHttpClient();
 * var client = ClientBuilder.newClient(
 *                             new ClientConfig()
 *                               .connectorProvider(
 *                                  (jaxRsClient, config) ->  new HttpClientConnector(httpClient)))
 * </code></pre>
 */
public class HttpClientConnector implements Connector {

    private static final Runnable NO_OP = () -> {
    };
    private final HttpClient httpClient;

    public HttpClientConnector(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    public HttpClientConnector(Client jaxRsClient, Configuration configuration) {
        final HttpClient.Builder builder = HttpClient.newBuilder()
                .sslContext(jaxRsClient.getSslContext());

        Optional.of(configuration)
                .map(c -> c.getProperty(PROXY_URI))
                .map(String.class::cast)
                .map(URI::create)
                .ifPresent(proxyUri -> builder.proxy(ProxySelector.of(InetSocketAddress.createUnresolved(proxyUri.getHost(), proxyUri.getPort()))));

        this.httpClient = getDurationTimeout(configuration, CONNECT_TIMEOUT)
                .map(builder::connectTimeout)
                .orElse(builder)
                .build();
    }

    static <R> R handleInterruption(Interruptable<R> interruptable) {
        try {
            return interruptable.execute();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProcessingException("The sending process was interrupted", e);
        }
    }

    static void connectStream(PipedOutputStream pipedOutputStream, PipedInputStream pipedInputStream) {
        try {
            pipedInputStream.connect(pipedOutputStream);
        } catch (IOException e) {
            throw new ProcessingException("The input stream cannot be connected to the output stream, " + e.getMessage(), e);
        }
    }

    private Optional<Duration> getDurationTimeout(Configuration configuration, String property) {
        return Optional.of(configuration)
                .map(c -> c.getProperty(property))
                .map(Integer.class::cast)
                .map(Duration::ofMillis);
    }

    @Override
    public ClientResponse apply(ClientRequest clientRequest) {
        final HttpResponse<InputStream> response = send(clientRequest, this::send);
        return toJerseyResponse(clientRequest, response);
    }

    HttpResponse<InputStream> send(HttpRequest request) {
        return handleInterruption(() -> {
            try {
                return httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            } catch (IOException e) {
                throw new ProcessingException("The HTTP sending process failed with error, " + e.getMessage(), e);
            }
        });
    }

    private ClientResponse toJerseyResponse(ClientRequest clientRequest, HttpResponse<InputStream> inputStreamHttpResponse) {
        final Response.StatusType responseStatus = Statuses.from(inputStreamHttpResponse.statusCode());
        final ClientResponse jerseyResponse = new ClientResponse(responseStatus, clientRequest);
        final var headers = inputStreamHttpResponse.headers();

        final var contentLengthHeader = headers.firstValueAsLong("content-length");
        if ((contentLengthHeader.isEmpty() || contentLengthHeader.getAsLong() > 0) && inputStreamHttpResponse.statusCode() != Response.Status.NO_CONTENT.getStatusCode()) {
            final InputStream entityStream = inputStreamHttpResponse.body();
            jerseyResponse.setEntityStream(entityStream);
        } else {
            //noinspection EmptyTryBlock
            try (var ignored = inputStreamHttpResponse.body()) {
                // nothing to do
            } catch (IOException e) {
                // ignored exception since stream is not used
            }
        }
        headers.map().forEach((name, values) -> values.forEach(value -> jerseyResponse.header(name, value)));
        return jerseyResponse;
    }

    @Override
    public Future<?> apply(ClientRequest clientRequest, AsyncConnectorCallback asyncConnectorCallback) {
        final CompletableFuture<HttpResponse<InputStream>> httpResponseCompletableFuture = send(clientRequest, this::getSendAsync);
        return toJerseyResponseWithCallback(clientRequest, httpResponseCompletableFuture, asyncConnectorCallback);
    }

    private CompletableFuture<HttpResponse<InputStream>> getSendAsync(HttpRequest request) {
        final var httpResponseCompletableFuture = httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream());
        return futureTimeout(request, httpResponseCompletableFuture);
    }

    private static <T> CompletableFuture<T> futureTimeout(HttpRequest request, CompletableFuture<T> future) {
        return request.timeout().map(readTimeout -> future.orTimeout(readTimeout.toMillis() + 100, TimeUnit.MILLISECONDS)).orElse(future);
    }

    private <R> R send(ClientRequest clientRequest, Function<HttpRequest, R> sender) {
        final HttpRequest.Builder requestBuilder = HttpRequest.newBuilder();
        clientRequest.getRequestHeaders().forEach((key, values) -> values.forEach(value -> requestBuilder.header(key, value)));
        requestBuilder.uri(clientRequest.getUri());

        final var readTimeoutOptional = Optional.of(clientRequest)
                .map(ClientRequest::getConfiguration)
                .flatMap(configuration -> getDurationTimeout(configuration, READ_TIMEOUT));
        readTimeoutOptional
                .ifPresent(requestBuilder::timeout);


        final Object entity = clientRequest.getEntity();

        final var method = clientRequest.getMethod();
        if (entity == null) {
            requestBuilder.method(method, HttpRequest.BodyPublishers.noBody());
            return sender.apply(requestBuilder.build());
        }


        if (entity instanceof byte[]) {
            requestBuilder.method(method, HttpRequest.BodyPublishers.ofByteArray((byte[]) entity));
            return sender.apply(requestBuilder.build());
        }
        if (entity instanceof String) {
            requestBuilder.method(method, HttpRequest.BodyPublishers.ofString((String) entity));
            return sender.apply(requestBuilder.build());
        }
        clientRequest.enableBuffering();

        final var chunkedEnabled = Optional.of(clientRequest)
                .map(ClientRequest::getConfiguration)
                .map(configuration -> configuration.getProperty(ClientProperties.REQUEST_ENTITY_PROCESSING))
                .map(String.class::cast)
                .filter("CHUNKED"::equals)
                .isPresent();
        if (chunkedEnabled) {
            return streamRequestBody(clientRequest, requestBuilder, sender, method);
        }
        final var buffer = new AtomicReference<ByteArrayOutputStream>();

        clientRequest.setStreamProvider(size -> size > 0 ? buffer.updateAndGet(ignored -> new ByteArrayOutputStream(size)) : buffer.updateAndGet(ignored -> new ByteArrayOutputStream()));
        writeEntity(clientRequest, NO_OP);
        final HttpRequest httpRequest = requestBuilder.method(method, HttpRequest.BodyPublishers.ofByteArray(buffer.get().toByteArray())).build();
        return sender.apply(httpRequest);
    }


    Future<ClientResponse> toJerseyResponseWithCallback(ClientRequest clientRequest, CompletableFuture<HttpResponse<InputStream>> inputStreamHttpResponseFuture, AsyncConnectorCallback asyncConnectorCallback) {
        final CompletableFuture<ClientResponse> clientResponseCompletableFuture = inputStreamHttpResponseFuture.thenApply(inputStreamHttpResponse -> toJerseyResponse(clientRequest, inputStreamHttpResponse));
        clientResponseCompletableFuture.whenComplete((response, cause) -> {
            if (cause == null) {
                asyncConnectorCallback.response(response);
            } else {
                asyncConnectorCallback.failure(cause);
            }
        });
        return clientResponseCompletableFuture;
    }

    <R> R streamRequestBody(ClientRequest clientRequest, HttpRequest.Builder requestBuilder, Function<HttpRequest, R> sender, String method) {
        @SuppressWarnings("squid:S2095") // The stream cannot be closed here and is closed in Jersey client.
        final PipedOutputStream pipedOutputStream = new PipedOutputStream();
        @SuppressWarnings("squid:S2095") // The stream cannot be closed here and is closed in Jersey client.
        final PipedInputStream pipedInputStream = new PipedInputStream();
        connectStream(pipedOutputStream, pipedInputStream);
        clientRequest.setStreamProvider(contentLength -> pipedOutputStream);

        final HttpRequest httpRequest = requestBuilder.method(method, HttpRequest.BodyPublishers.ofInputStream(() -> pipedInputStream)).build();

        final CompletableFuture<R> httpCallFuture = supplyAsync(httpRequest, () -> sender.apply(httpRequest));


        CompletableFuture<Void> sendingRequestFuture = supplyAsync(httpRequest, () -> writeEntity(clientRequest, () -> httpCallFuture.cancel(true)));

        return handleInterruption(() -> {
            try {
                return sendingRequestFuture.thenCombine(httpCallFuture, (aVoid, inputStreamHttpResponse) -> inputStreamHttpResponse).get();
            } catch (ExecutionException e) {
                throw new ProcessingException(e);
            }
        });

    }

    private <T> CompletableFuture<T> supplyAsync(HttpRequest httpRequest, Supplier<T> entityWriter) {
        return futureTimeout(httpRequest, httpClient.executor().map(executor -> CompletableFuture.supplyAsync(entityWriter, executor)).orElseGet(() -> CompletableFuture.supplyAsync(entityWriter)));
    }

    private static Void writeEntity(ClientRequest clientRequest, Runnable onError) {
        try (var ignoredOnlyForClose = clientRequest.getEntityStream()) {
            clientRequest.writeEntity();
            return null;
        } catch (IOException e) {
            onError.run();
            throw new ProcessingException("The sending process failed with I/O error, " + e.getMessage(), e);
        }
    }

    public HttpClient getHttpClient() {
        return httpClient;
    }

    @Override
    public String getName() {
        return "Java HttpClient";
    }

    @Override
    public void close() {
        // Nothing to close
    }

    interface Interruptable<R> {
        R execute() throws InterruptedException;
    }

}
