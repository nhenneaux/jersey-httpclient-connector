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

public class HttpClientConnector implements Connector {

    private final HttpClient httpClient;

    public HttpClientConnector(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    public HttpClientConnector(Client jaxRsClient, Configuration configuration) {
        this.httpClient = HttpClient.newBuilder()
                .sslContext(jaxRsClient.getSslContext())
                .connectTimeout(Duration.of((Integer) configuration.getProperty(ClientProperties.CONNECT_TIMEOUT), ChronoUnit.MILLIS))
                .build();
    }

    @Override
    public ClientResponse apply(ClientRequest clientRequest) {
        final HttpRequest.Builder requestBuilder = toHttpClientRequestBuilder(clientRequest);
        if (clientRequest.getEntity() == null) {
            requestBuilder.method(clientRequest.getMethod(), HttpRequest.BodyPublishers.noBody());
            final HttpResponse<InputStream> inputStreamHttpResponse;
            try {
                inputStreamHttpResponse = httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofInputStream());
            } catch (IOException e) {
                throw new ProcessingException("The sending process failed with I/O error, " + e.getMessage(), e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ProcessingException("The sending process was interrupted", e);
            }
            return toJerseyResponse(clientRequest, inputStreamHttpResponse);
        }
        final int readTimeoutInMilliseconds = Optional.ofNullable(clientRequest.getClient().getConfiguration().getProperty(ClientProperties.READ_TIMEOUT))
                .map(Integer.class::cast).orElse(0);
        final CompletableFuture<HttpResponse<InputStream>> httpResponseCompletableFuture = streamRequestBody(clientRequest, requestBuilder);

        final HttpResponse<InputStream> inputStreamHttpResponse = waitResponse(httpResponseCompletableFuture, readTimeoutInMilliseconds);

        return toJerseyResponse(clientRequest, inputStreamHttpResponse);
    }

    private HttpResponse<InputStream> waitResponse(CompletableFuture<HttpResponse<InputStream>> httpResponseCompletableFuture, int readTimeoutInMilliseconds) {
        final HttpResponse<InputStream> inputStreamHttpResponse;
        try {
            inputStreamHttpResponse = readTimeoutInMilliseconds == 0 ? httpResponseCompletableFuture.get() : httpResponseCompletableFuture.get(readTimeoutInMilliseconds, TimeUnit.MILLISECONDS);
        } catch (ExecutionException e) {
            throw new ProcessingException("The async sending process failed with error, " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProcessingException("The sending process was interrupted", e);
        } catch (TimeoutException e) {
            throw new ProcessingException("No response received within " + readTimeoutInMilliseconds + "ms.", e);
        }
        return inputStreamHttpResponse;
    }

    private ClientResponse toJerseyResponse(ClientRequest clientRequest, HttpResponse<InputStream> inputStreamHttpResponse) {
        final Response.StatusType responseStatus = Statuses.from(inputStreamHttpResponse.statusCode());
        final ClientResponse jerseyResponse = new ClientResponse(responseStatus, clientRequest);
        inputStreamHttpResponse.headers().map().forEach((name, values) -> values.forEach(value -> jerseyResponse.header(name, value)));
        jerseyResponse.setEntityStream(inputStreamHttpResponse.body());
        return jerseyResponse;
    }

    private boolean isGreaterThanZero(Object object) {
        return object instanceof Integer && (Integer) object > 0;
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

    private Future<?> toJerseyResponseWithCallback(ClientRequest clientRequest, CompletableFuture<HttpResponse<InputStream>> inputStreamHttpResponseFuture, AsyncConnectorCallback asyncConnectorCallback) {
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

    private CompletableFuture<HttpResponse<InputStream>> streamRequestBody(ClientRequest clientRequest, HttpRequest.Builder requestBuilder) {
        final CompletableFuture<HttpResponse<InputStream>> httpResponseCompletableFuture;
        try {
            final PipedOutputStream pipedOutputStream = new PipedOutputStream();
            final PipedInputStream pipedInputStream = new PipedInputStream(pipedOutputStream);
            clientRequest.setStreamProvider(contentLength -> pipedOutputStream);
            requestBuilder.method(clientRequest.getMethod(), HttpRequest.BodyPublishers.ofInputStream(() -> pipedInputStream));

            httpResponseCompletableFuture = httpClient.sendAsync(requestBuilder.build(), HttpResponse.BodyHandlers.ofInputStream());

            clientRequest.writeEntity();
        } catch (IOException e) {
            throw new ProcessingException("The sending process failed with I/O error, " + e.getMessage(), e);
        }
        return httpResponseCompletableFuture;
    }

    private HttpRequest.Builder toHttpClientRequestBuilder(ClientRequest clientRequest) {
        final HttpRequest.Builder requestBuilder = HttpRequest.newBuilder();
        // TODO handle changed headers after pushed of payload
        clientRequest.getRequestHeaders().forEach((key, clientRequestValue) -> clientRequestValue.forEach(value -> requestBuilder.header(key, value)));
        requestBuilder.uri(clientRequest.getUri());
        final Object readTimeoutInMilliseconds = clientRequest.getConfiguration().getProperties().get(ClientProperties.READ_TIMEOUT);
        if (isGreaterThanZero(readTimeoutInMilliseconds)) {
            requestBuilder.timeout(Duration.of((Integer) readTimeoutInMilliseconds, ChronoUnit.MILLIS));
        }
        return requestBuilder;
    }

    @Override
    public String getName() {
        return "Java HttpClient";
    }

    @Override
    public void close() {
           // Nothing to close
    }
}
