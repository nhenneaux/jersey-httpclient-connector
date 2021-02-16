package com.github.nhenneaux.jersey.connector.httpclient;

import org.glassfish.jersey.client.ClientProperties;
import org.glassfish.jersey.client.ClientRequest;
import org.glassfish.jersey.client.ClientResponse;
import org.glassfish.jersey.client.spi.AsyncConnectorCallback;
import org.glassfish.jersey.client.spi.Connector;
import org.glassfish.jersey.message.internal.Statuses;

import javax.ws.rs.ProcessingException;
import javax.ws.rs.client.Client;
import javax.ws.rs.core.Configuration;
import javax.ws.rs.core.Response;
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
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.glassfish.jersey.client.ClientProperties.CONNECT_TIMEOUT;
import static org.glassfish.jersey.client.ClientProperties.PROXY_URI;

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

    private final HttpClient httpClient;

    public HttpClientConnector(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    public HttpClientConnector(Client jaxRsClient, Configuration configuration) {
        final HttpClient.Builder builder = HttpClient.newBuilder()
                .sslContext(jaxRsClient.getSslContext());

        Optional.ofNullable(configuration.getProperty(PROXY_URI))
                .map(String.class::cast)
                .map(URI::create)
                .ifPresent(proxyUri -> builder.proxy(ProxySelector.of(InetSocketAddress.createUnresolved(proxyUri.getHost(), proxyUri.getPort()))));

        this.httpClient = Optional.ofNullable(configuration.getProperty(CONNECT_TIMEOUT))
                .map(Integer.class::cast)
                .map(connectTimeoutInMillis -> Duration.of(connectTimeoutInMillis, ChronoUnit.MILLIS))
                .map(builder::connectTimeout)
                .orElse(builder)
                .build();
    }

    static boolean isGreaterThanZero(Object object) {
        return object instanceof Integer && (Integer) object > 0;
    }

    static HttpResponse<InputStream> handleInterruption(Interruptable interruptable) {
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

    @Override
    public ClientResponse apply(ClientRequest clientRequest) {
        final HttpRequest.Builder requestBuilder = toHttpClientRequestBuilder(clientRequest);
        final Optional<Integer> optionalReadTimeoutInMilliseconds = Optional.ofNullable(clientRequest.getClient().getConfiguration().getProperty(ClientProperties.READ_TIMEOUT))
                .map(Integer.class::cast);

        if (clientRequest.getEntity() == null) {
            requestBuilder.method(clientRequest.getMethod(), HttpRequest.BodyPublishers.noBody());

            final HttpResponse<InputStream> inputStreamHttpResponse = handleInterruption(() -> {
                try {
                    optionalReadTimeoutInMilliseconds
                            .map(Duration::ofMillis)
                            .ifPresent(requestBuilder::timeout);
                    return httpClient
                            .send(requestBuilder.build(), HttpResponse.BodyHandlers.ofInputStream());
                } catch (IOException e) {
                    throw new ProcessingException("The HTTP exchange failed with I/O error, " + e.getMessage(), e);
                }
            });

            return toJerseyResponse(clientRequest, inputStreamHttpResponse);
        }
        final int readTimeoutInMilliseconds = optionalReadTimeoutInMilliseconds.orElse(0);
        final CompletableFuture<HttpResponse<InputStream>> httpResponseCompletableFuture = streamRequestBody(clientRequest, requestBuilder);

        final HttpResponse<InputStream> inputStreamHttpResponse = waitResponse(httpResponseCompletableFuture, readTimeoutInMilliseconds);

        return toJerseyResponse(clientRequest, inputStreamHttpResponse);
    }

    HttpResponse<InputStream> waitResponse(CompletableFuture<HttpResponse<InputStream>> httpResponseCompletableFuture, int readTimeoutInMilliseconds) {
        return handleInterruption(() -> {
            try {
                return readTimeoutInMilliseconds == 0 ? httpResponseCompletableFuture.get() : httpResponseCompletableFuture.get(readTimeoutInMilliseconds, TimeUnit.MILLISECONDS);
            } catch (ExecutionException e) {
                throw new ProcessingException("The async sending process failed with error, " + e.getMessage(), e);
            } catch (TimeoutException e) {
                throw new ProcessingException("No response received within " + readTimeoutInMilliseconds + "ms.", e);
            }
        });
    }

    private ClientResponse toJerseyResponse(ClientRequest clientRequest, HttpResponse<InputStream> inputStreamHttpResponse) {
        final Response.StatusType responseStatus = Statuses.from(inputStreamHttpResponse.statusCode());
        final ClientResponse jerseyResponse = new ClientResponse(responseStatus, clientRequest);
        final InputStream entityStream = inputStreamHttpResponse.body();
        jerseyResponse.setEntityStream(entityStream);
        inputStreamHttpResponse.headers().map().forEach((name, values) -> values.forEach(value -> jerseyResponse.header(name, value)));
        return jerseyResponse;
    }

    @Override
    public Future<?> apply(ClientRequest clientRequest, AsyncConnectorCallback asyncConnectorCallback) {

        final HttpRequest.Builder requestBuilder = toHttpClientRequestBuilder(clientRequest);
        if (clientRequest.getEntity() == null) {
            requestBuilder.method(clientRequest.getMethod(), HttpRequest.BodyPublishers.noBody());
            final CompletableFuture<HttpResponse<InputStream>> httpResponseCompletableFuture = httpClient.sendAsync(requestBuilder.build(), HttpResponse.BodyHandlers.ofInputStream());
            return toJerseyResponseWithCallback(clientRequest, httpResponseCompletableFuture, asyncConnectorCallback);
        }
        final CompletableFuture<HttpResponse<InputStream>> httpResponseCompletableFuture = streamRequestBody(clientRequest, requestBuilder);

        return toJerseyResponseWithCallback(clientRequest, httpResponseCompletableFuture, asyncConnectorCallback);
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

    CompletableFuture<HttpResponse<InputStream>> streamRequestBody(ClientRequest clientRequest, HttpRequest.Builder requestBuilder) {
        @SuppressWarnings("squid:S2095") // The stream cannot be closed here and is closed in Jersey client.
        final PipedOutputStream pipedOutputStream = new PipedOutputStream();
        @SuppressWarnings("squid:S2095") // The stream cannot be closed here and is closed in Jersey client.
        final PipedInputStream pipedInputStream = new PipedInputStream();
        connectStream(pipedOutputStream, pipedInputStream);
        clientRequest.setStreamProvider(contentLength -> pipedOutputStream);

        final HttpRequest httpRequest = requestBuilder
                .method(clientRequest.getMethod(), HttpRequest.BodyPublishers.ofInputStream(() -> pipedInputStream))
                .build();
        final CompletableFuture<HttpResponse<InputStream>> httpResponseCompletableFuture = httpClient.sendAsync(httpRequest, HttpResponse.BodyHandlers.ofInputStream());

        final Runnable entityWriter = () -> {
            try {
                clientRequest.writeEntity();
            } catch (IOException e) {
                throw new ProcessingException("The sending process failed with I/O error, " + e.getMessage(), e);
            }
        };

        final CompletableFuture<Void> sendingFuture = httpClient.executor()
                .map(executor -> CompletableFuture.runAsync(entityWriter, executor))
                .orElseGet(() -> CompletableFuture.runAsync(entityWriter));

        return sendingFuture.thenCombine(httpResponseCompletableFuture, (aVoid, inputStreamHttpResponse) -> inputStreamHttpResponse);
    }

    private HttpRequest.Builder toHttpClientRequestBuilder(ClientRequest clientRequest) {
        final HttpRequest.Builder requestBuilder = HttpRequest.newBuilder();
        // TODO handle changed headers after pushed of payload
        clientRequest.getRequestHeaders()
                .forEach((key, value1) -> value1.forEach(value -> requestBuilder.header(key, value)));
        requestBuilder.uri(clientRequest.getUri());
        final Object readTimeoutInMilliseconds = clientRequest.getConfiguration().getProperties().get(ClientProperties.READ_TIMEOUT);
        if (isGreaterThanZero(readTimeoutInMilliseconds)) {
            return requestBuilder.timeout(Duration.of((Integer) readTimeoutInMilliseconds, ChronoUnit.MILLIS));
        }
        return requestBuilder;
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

    interface Interruptable {
        HttpResponse<InputStream> execute() throws InterruptedException;
    }

}
