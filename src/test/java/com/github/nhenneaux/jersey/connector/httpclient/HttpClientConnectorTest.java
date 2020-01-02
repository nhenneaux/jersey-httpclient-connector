package com.github.nhenneaux.jersey.connector.httpclient;

import org.glassfish.jersey.client.ClientRequest;
import org.junit.jupiter.api.Test;

import javax.ws.rs.ProcessingException;
import java.io.IOException;
import java.io.InputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        await()
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
        await()
                .atMost(Duration.ofSeconds(5L))
                .until(httpResponseCompletableFuture::isDone);
        assertTrue(httpResponseCompletableFuture.isCompletedExceptionally());
        final ExecutionException executionException = assertThrows(ExecutionException.class, httpResponseCompletableFuture::get);
        assertEquals("The sending process failed with I/O error, " + ioException.getMessage(), executionException.getCause().getMessage());
        assertSame(ioException, executionException.getCause().getCause());
    }

    @Test
    void shouldIgnoreObjectsInIsGreaterThanZero() {
        assertFalse(HttpClientConnector.isGreaterThanZero(new Object()));
    }

    @Test
    void shouldHandleNegativeInIsGreaterThanZero() {
        assertFalse(HttpClientConnector.isGreaterThanZero(-1));
    }

    @Test
    void shouldHandleZeroInIsGreaterThanZero() {
        assertFalse(HttpClientConnector.isGreaterThanZero(0));
    }

    @Test
    void shouldHandlePositiveInIsGreaterThanZero() {
        assertTrue(HttpClientConnector.isGreaterThanZero(1));
    }

    @Test
    void shouldThrowWhenConnectingStreamAlreadyConnected() throws IOException {
        final PipedInputStream pipedInputStream = new PipedInputStream();
        final PipedOutputStream pipedOutputStream = new PipedOutputStream();
        pipedOutputStream.connect(pipedInputStream);
        final ProcessingException expectedException = assertThrows(ProcessingException.class, () -> HttpClientConnector.connectStream(pipedOutputStream, pipedInputStream));
        assertEquals("The input stream cannot be connected to the output stream, Already connected", expectedException.getMessage());
    }

    @Test
    void shouldHandleInterruption() {
        final InterruptedException interruptedException = new InterruptedException(UUID.randomUUID().toString());
        final Thread thread = new Thread(() -> HttpClientConnector.handleInterruption(() -> {
            throw interruptedException;
        }));
        AtomicReference<Throwable> expectedInterruptedException = new AtomicReference<>();
        thread.setUncaughtExceptionHandler((t, e) -> {
            expectedInterruptedException.set(e);
        });
        thread.start();

        await()
                .atMost(Duration.ofSeconds(2L))
                .until(() -> !thread.isAlive());
        assertFalse(thread::isAlive);
        assertEquals(ProcessingException.class, expectedInterruptedException.get().getClass());
        assertSame(interruptedException, expectedInterruptedException.get().getCause());

    }
}