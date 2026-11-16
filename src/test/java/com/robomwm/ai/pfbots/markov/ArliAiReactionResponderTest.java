package com.robomwm.ai.pfbots.markov;

import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArliAiReactionResponderTest {
    @Test
    void hasDifferentTimeoutThanMain() {
        assertEquals(15, getMainTimeout());
        assertEquals(600, ArliAiReactionResponder.getTimeoutSeconds());
        assertTrue(ArliAiReactionResponder.getTimeoutSeconds() != getMainTimeout());
    }

    private int getMainTimeout() {
        // RoundRobin timeout is 15 (hardcoded); reflect via reflection or known value
        return 15;
    }

    @Test
    void buildsArliProvidersFromConfig() {
        GenerativeAiConfig config = new GenerativeAiConfig(
                null, null, null,
                null, null, null, null, null, null, null, "arli-key",
                List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of("Qwen3.5-27B-Derestricted", "ArliModel2"));

        List<ArliAiReactionResponder.Provider> providers = ArliAiReactionResponder.buildProviders(config);
        assertEquals(List.of("Qwen3.5-27B-Derestricted", "ArliModel2"),
                providers.stream().map(ArliAiReactionResponder.Provider::model).toList());
        assertTrue(providers.stream().allMatch(p -> "ArliAI".equals(p.displayName())));
        assertEquals("https://api.arliai.com/v1/chat/completions", providers.get(0).url());
    }

    @Test
    void disablesArliAiReasoningWithNonThinkingDefaults() {
        RecordingHttpClient client = new RecordingHttpClient();
        ArliAiReactionResponder responder = new ArliAiReactionResponder(client, List.of(
                new ArliAiReactionResponder.Provider("ArliAI", "https://api.arliai.com/v1/chat/completions", "ak", "Qwen3.5-27B-Derestricted", Map.of(), false)
        ), "system prompt");
        GenerativeAiRequest request = new GenerativeAiRequest(List.of("hello?"), "You decide reactions");

        responder.generateReply(request).join();

        DataObject payload = DataObject.fromJson(client.bodies.get(0));
        assertEquals(0.3, payload.getDouble("temperature"));
        assertEquals(0.9, payload.getDouble("top_p"));
        assertEquals(20, payload.getInt("top_k"));
        assertEquals(0.0, payload.getDouble("min_p"));
        assertEquals(1.5, payload.getDouble("presence_penalty"));
        assertEquals(1.0, payload.getDouble("repetition_penalty"));
        assertEquals("none", payload.getString("reasoning_effort"));
        assertEquals(0, payload.getInt("thinking_token_budget"));
        assertEquals("delta", payload.getString("output_kind"));
        assertFalse(payload.getObject("chat_template_kwargs").getBoolean("enable_thinking"));
    }

    @Test
    void reactionPromptIncludesConversationHistoryBeforeCandidates() {
        List<String> prompt = MarkovListener.reactionPromptWithHistory(
                List.of("<Alice> that's wild", "<Bob> lol"),
                List.of("Candidates:", "r1 reaction=😂 message=\"lol\""));

        assertEquals(List.of(
                "Recent conversation:",
                "<Alice> that's wild",
                "<Bob> lol",
                "",
                "Candidates:",
                "r1 reaction=😂 message=\"lol\""), prompt);
    }

    @Test
    void orderedFallbackTriesNextArliModelOnFailure() {
        RecordingHttpClient client = new RecordingHttpClient();
        client.responseSequence.add(new StringResponse(null, 500, "Internal server error"));
        client.responseSequence.add(new StringResponse(null, 200, "{\"choices\":[{\"message\":{\"content\":\"ok from second arli\"}}]}"));
        ArliAiReactionResponder responder = new ArliAiReactionResponder(client, List.of(
                new ArliAiReactionResponder.Provider("ArliAI", "https://api.arliai.com/v1/chat/completions", "ak", "model1", Map.of(), false),
                new ArliAiReactionResponder.Provider("ArliAI", "https://api.arliai.com/v1/chat/completions", "ak", "model2", Map.of(), false)
        ), "system prompt");

        String reply = responder.generateReply(new GenerativeAiRequest(List.of("hi"))).join();
        assertEquals("ok from second arli", reply);
        assertEquals(2, client.bodies.size());
        assertEquals("model1", DataObject.fromJson(client.bodies.get(0)).getString("model"));
        assertEquals("model2", DataObject.fromJson(client.bodies.get(1)).getString("model"));
    }

    @Test
    void skipsArliWhenKeyMissing() {
        GenerativeAiConfig config = new GenerativeAiConfig(
                null, null, null,
                null, null, null, null, null, null, null, null,
                List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of("Qwen3.5-27B-Derestricted"));
        assertTrue(ArliAiReactionResponder.buildProviders(config).isEmpty());
    }

    // Copy of recording client from other test (duplicated for isolation)
    private static final class RecordingHttpClient extends HttpClient {
        private final List<URI> uris = new ArrayList<>();
        private final List<String> bodies = new ArrayList<>();
        private final List<HttpResponse<String>> responseSequence = new ArrayList<>();
        private int responseIndex = 0;

        @Override
        public Optional<CookieHandler> cookieHandler() { return Optional.empty(); }
        @Override
        public Optional<Duration> connectTimeout() { return Optional.empty(); }
        @Override
        public Redirect followRedirects() { return Redirect.NEVER; }
        @Override
        public Optional<ProxySelector> proxy() { return Optional.empty(); }
        @Override
        public SSLContext sslContext() { try { return SSLContext.getDefault(); } catch (Exception e) { throw new IllegalStateException(e); } }
        @Override
        public SSLParameters sslParameters() { return new SSLParameters(); }
        @Override
        public Optional<Authenticator> authenticator() { return Optional.empty(); }
        @Override
        public Version version() { return Version.HTTP_1_1; }
        @Override
        public Optional<Executor> executor() { return Optional.empty(); }
        @Override
        public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler) throws IOException, InterruptedException { throw new UnsupportedOperationException(); }
        @Override
        @SuppressWarnings("unchecked")
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler) {
            uris.add(request.uri());
            bodies.add(readBody(request));
            HttpResponse<String> response = responseIndex < responseSequence.size()
                    ? responseSequence.get(responseIndex++)
                    : new StringResponse(request, 200, "{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}");
            return CompletableFuture.completedFuture((HttpResponse<T>) response);
        }
        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler, HttpResponse.PushPromiseHandler<T> pushPromiseHandler) { return sendAsync(request, responseBodyHandler); }
        private String readBody(HttpRequest request) {
            StringBodySubscriber subscriber = new StringBodySubscriber();
            request.bodyPublisher().orElseThrow().subscribe(subscriber);
            return subscriber.bodyFuture.join();
        }
    }

    private static final class StringBodySubscriber implements Flow.Subscriber<ByteBuffer> {
        private final CompletableFuture<String> bodyFuture = new CompletableFuture<>();
        private final StringBuilder body = new StringBuilder();
        @Override public void onSubscribe(Flow.Subscription subscription) { subscription.request(Long.MAX_VALUE); }
        @Override public void onNext(ByteBuffer item) { body.append(StandardCharsets.UTF_8.decode(item)); }
        @Override public void onError(Throwable throwable) { bodyFuture.completeExceptionally(throwable); }
        @Override public void onComplete() { bodyFuture.complete(body.toString()); }
    }

    private record StringResponse(HttpRequest request, int statusCode, String body) implements HttpResponse<String> {
        @Override public HttpHeaders headers() { return HttpHeaders.of(Map.of(), (name, value) -> true); }
        @Override public Optional<HttpResponse<String>> previousResponse() { return Optional.empty(); }
        @Override public URI uri() { return request != null ? request.uri() : URI.create("http://localhost"); }
        @Override public HttpClient.Version version() { return HttpClient.Version.HTTP_1_1; }
        @Override public Optional<javax.net.ssl.SSLSession> sslSession() { return Optional.empty(); }
    }
}
