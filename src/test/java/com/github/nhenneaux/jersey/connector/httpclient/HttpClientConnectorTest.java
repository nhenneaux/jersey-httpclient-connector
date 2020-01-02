package com.github.nhenneaux.jersey.connector.httpclient;

import org.awaitility.Awaitility;
import org.glassfish.jersey.client.ClientRequest;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HttpClientConnectorTest {

    static final Class<? extends HttpRequest.BodyPublisher> IS_PUBLISHER_CLASS = HttpRequest.BodyPublishers.ofInputStream(() -> null).getClass();
    @SuppressWarnings("unchecked")
    static final Class<? extends HttpResponse.BodyHandler<InputStream>> IS_HANDLER_CLASS = (Class<? extends HttpResponse.BodyHandler<InputStream>>) HttpResponse.BodyHandlers.ofInputStream().getClass();

    @Test
    void streamRequestBodySuccess() throws ExecutionException, InterruptedException {
        // Given
        final HttpClient httpClient = mock(HttpClient.class);
        final HttpRequest httpRequest = mock(HttpRequest.class);
        @SuppressWarnings("unchecked") final HttpResponse<InputStream> httpResponse = mock(HttpResponse.class);
        final CompletableFuture<HttpResponse<InputStream>> responseFuture = CompletableFuture.completedFuture(httpResponse);
        when(httpClient.sendAsync(eq(httpRequest), any(IS_HANDLER_CLASS))).thenReturn(responseFuture);
        final HttpClientConnector httpClientConnector = new HttpClientConnector(httpClient);

        final HttpRequest.Builder requestBuilder = mock(HttpRequest.Builder.class);
        final HttpRequest.Builder requestBuilderWithMethod = mock(HttpRequest.Builder.class);
        final ClientRequest clientRequest = mock(ClientRequest.class);
        final String method = "POST";
        when(clientRequest.getMethod()).thenReturn(method);
        when(requestBuilder.method(eq(method), any(IS_PUBLISHER_CLASS))).thenReturn(requestBuilderWithMethod);
        when(requestBuilderWithMethod.build()).thenReturn(httpRequest);
        // When
        final CompletableFuture<HttpResponse<InputStream>> httpResponseCompletableFuture = httpClientConnector.streamRequestBody(clientRequest, requestBuilder);
        // Then

        Awaitility.await()
                .atMost(Duration.ofSeconds(5L))
                .until(httpResponseCompletableFuture::isDone);
        assertSame(httpResponse, httpResponseCompletableFuture.get());
    }

    @Test
    void streamRequestBodyFailure() throws IOException {
        // Given
        final HttpClient httpClient = mock(HttpClient.class);
        final HttpRequest httpRequest = mock(HttpRequest.class);
        final CompletableFuture<HttpResponse<InputStream>> responseFuture = CompletableFuture.completedFuture(null);
        when(httpClient.sendAsync(eq(httpRequest), any(IS_HANDLER_CLASS))).thenReturn(responseFuture);
        final HttpClientConnector httpClientConnector = new HttpClientConnector(httpClient);

        final HttpRequest.Builder requestBuilder = mock(HttpRequest.Builder.class);
        final HttpRequest.Builder requestBuilderWithMethod = mock(HttpRequest.Builder.class);
        final ClientRequest clientRequest = mock(ClientRequest.class);
        final String method = "POST";
        when(clientRequest.getMethod()).thenReturn(method);
        when(requestBuilder.method(eq(method), any(IS_PUBLISHER_CLASS))).thenReturn(requestBuilderWithMethod);
        when(requestBuilderWithMethod.build()).thenReturn(httpRequest);
        final IOException ioException = new IOException(UUID.randomUUID().toString());
        doThrow(ioException).when(clientRequest).writeEntity();
        // When
        final CompletableFuture<HttpResponse<InputStream>> httpResponseCompletableFuture = httpClientConnector.streamRequestBody(clientRequest, requestBuilder);

        // Then
        Awaitility.await()
                .atMost(Duration.ofSeconds(5L))
                .until(httpResponseCompletableFuture::isDone);
        assertTrue(httpResponseCompletableFuture.isCompletedExceptionally());
        final ExecutionException executionException = assertThrows(ExecutionException.class, httpResponseCompletableFuture::get);
        assertEquals("The sending process failed with I/O error, " + ioException.getMessage(), executionException.getCause().getMessage());
        assertSame(ioException, executionException.getCause().getCause());
    }
}