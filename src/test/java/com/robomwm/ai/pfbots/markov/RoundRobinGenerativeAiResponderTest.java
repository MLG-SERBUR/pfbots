package com.robomwm.ai.pfbots.markov;

import net.dv8tion.jda.api.utils.data.DataArray;
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

class RoundRobinGenerativeAiResponderTest {
    @Test
    void usesOrderedProvidersFirstProviderEachInvocation() {
        RecordingHttpClient client = new RecordingHttpClient();
        RoundRobinGenerativeAiResponder responder = new RoundRobinGenerativeAiResponder(client, List.of(
                new RoundRobinGenerativeAiResponder.Provider("Cerebras", "https://cerebras.example/chat", "ck", "cm", Map.of(), false),
                new RoundRobinGenerativeAiResponder.Provider("Groq", "https://groq.example/chat", "gk", "gm", Map.of(), false),
                new RoundRobinGenerativeAiResponder.Provider("OpenRouter", "https://openrouter.example/chat", "ok", "om", Map.of(), true)
        ), "system prompt");
        GenerativeAiRequest request = new GenerativeAiRequest(List.of("hello?", "reply"));

        responder.generateReply(request).join();
        responder.generateReply(request).join();
        responder.generateReply(request).join();
        responder.generateReply(request).join();

        // Ordered fallback: each new request starts at first provider in configured order (Cerebras)
        assertEquals(List.of(
                URI.create("https://cerebras.example/chat"),
                URI.create("https://cerebras.example/chat"),
                URI.create("https://cerebras.example/chat"),
                URI.create("https://cerebras.example/chat")
        ), client.uris);
    }

    @Test
    void orderedFallbackTriesNextProviderOnFailure() {
        RecordingHttpClient client = new RecordingHttpClient();
        client.responseSequence.add(new StringResponse(null, 500, "Internal server error")); // first fails
        client.responseSequence.add(new StringResponse(null, 200, "{\"choices\":[{\"message\":{\"content\":\"ok from second\"}}]}")); // second succeeds
        RoundRobinGenerativeAiResponder responder = new RoundRobinGenerativeAiResponder(client, List.of(
                new RoundRobinGenerativeAiResponder.Provider("Cerebras", "https://cerebras.example/chat", "ck", "cm", Map.of(), false),
                new RoundRobinGenerativeAiResponder.Provider("Groq", "https://groq.example/chat", "gk", "gm", Map.of(), false)
        ), "system prompt");
        GenerativeAiRequest request = new GenerativeAiRequest(List.of("hello?"));

        String reply = responder.generateReply(request).join();

        assertEquals("ok from second", reply);
        assertEquals(List.of(
                URI.create("https://cerebras.example/chat"),
                URI.create("https://groq.example/chat")
        ), client.uris);
        assertEquals(List.of(
                Duration.ofSeconds(45),
                Duration.ofSeconds(45)
        ), client.timeouts);
    }

    @Test
    void disablesReasoningOnlyForOpenRouter() {
        RecordingHttpClient client = new RecordingHttpClient();
        // Set up Groq to fail so OpenRouter fallback is triggered
        client.responseSequence.add(new StringResponse(null, 500, "Internal server error")); // Groq fails
        client.responseSequence.add(new StringResponse(null, 200, "{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}")); // OpenRouter succeeds

        RoundRobinGenerativeAiResponder responder = new RoundRobinGenerativeAiResponder(client, List.of(
                new RoundRobinGenerativeAiResponder.Provider("Groq", "https://groq.example/chat", "gk", "gm", Map.of(), false),
                new RoundRobinGenerativeAiResponder.Provider("OpenRouter", "https://openrouter.example/chat", "ok", "om", Map.of(), true)
        ), "system prompt");
        GenerativeAiRequest request = new GenerativeAiRequest(List.of("hello?"));

        responder.generateReply(request).join();

        // First request should be Groq (which fails), second should be OpenRouter fallback
        DataObject groqPayload = DataObject.fromJson(client.bodies.get(0));
        org.junit.jupiter.api.Assertions.assertFalse(groqPayload.hasKey("reasoning_effort"));
        org.junit.jupiter.api.Assertions.assertFalse(groqPayload.hasKey("reasoning"));

        DataObject openRouterPayload = DataObject.fromJson(client.bodies.get(1));
        DataObject reasoning = openRouterPayload.getObject("reasoning");
        assertEquals("none", reasoning.getString("effort"));
        org.junit.jupiter.api.Assertions.assertFalse(client.bodies.get(1).contains("\"exclude\""));
    }

    @Test
    void hidesGroqReasoningOutputForReasoningModels() {
        // Each provider is tested in isolation to avoid ordering dependencies
        RecordingHttpClient clientQwen = new RecordingHttpClient();
        RoundRobinGenerativeAiResponder responderQwen = new RoundRobinGenerativeAiResponder(clientQwen, List.of(
                new RoundRobinGenerativeAiResponder.Provider("Groq", "https://groq.example/chat", "gk", "qwen/qwen3-32b", Map.of(), false)
        ), "system prompt");
        responderQwen.generateReply(new GenerativeAiRequest(List.of("hello?"))).join();
        DataObject qwenPayload = DataObject.fromJson(clientQwen.bodies.get(0));
        assertEquals("none", qwenPayload.getString("reasoning_effort"));
        org.junit.jupiter.api.Assertions.assertFalse(clientQwen.bodies.get(0).contains("\"reasoning_format\""));
        org.junit.jupiter.api.Assertions.assertFalse(clientQwen.bodies.get(0).contains("\"include_reasoning\""));

        // Dot-version qwen models (qwen3.6/qwen3.8) must get the same non-thinking params
        for (String dotQwen : List.of("qwen/qwen3.6-27b", "qwen/qwen3.8-27b")) {
            RecordingHttpClient clientDotQwen = new RecordingHttpClient();
            RoundRobinGenerativeAiResponder responderDotQwen = new RoundRobinGenerativeAiResponder(clientDotQwen, List.of(
                    new RoundRobinGenerativeAiResponder.Provider("Groq", "https://groq.example/chat", "gk", dotQwen, Map.of(), false)
            ), "system prompt");
            responderDotQwen.generateReply(new GenerativeAiRequest(List.of("hello?"))).join();
            DataObject dotQwenPayload = DataObject.fromJson(clientDotQwen.bodies.get(0));
            assertEquals("none", dotQwenPayload.getString("reasoning_effort"));
            org.junit.jupiter.api.Assertions.assertFalse(clientDotQwen.bodies.get(0).contains("\"reasoning_format\""));
            org.junit.jupiter.api.Assertions.assertFalse(dotQwenPayload.getObject("chat_template_kwargs").getBoolean("enable_thinking"));
        }

        RecordingHttpClient clientGpt = new RecordingHttpClient();
        RoundRobinGenerativeAiResponder responderGpt = new RoundRobinGenerativeAiResponder(clientGpt, List.of(
                new RoundRobinGenerativeAiResponder.Provider("Groq", "https://groq.example/chat", "gk", "openai/gpt-oss-20b", Map.of(), false)
        ), "system prompt");
        responderGpt.generateReply(new GenerativeAiRequest(List.of("hello?"))).join();
        DataObject gptOssPayload = DataObject.fromJson(clientGpt.bodies.get(0));
        org.junit.jupiter.api.Assertions.assertFalse(gptOssPayload.getBoolean("include_reasoning"));
        assertEquals("low", gptOssPayload.getString("reasoning_effort"));
        org.junit.jupiter.api.Assertions.assertFalse(clientGpt.bodies.get(0).contains("\"reasoning_format\""));

        RecordingHttpClient clientLlama = new RecordingHttpClient();
        RoundRobinGenerativeAiResponder responderLlama = new RoundRobinGenerativeAiResponder(clientLlama, List.of(
                new RoundRobinGenerativeAiResponder.Provider("Groq", "https://groq.example/chat", "gk", "llama-3.3-70b-versatile", Map.of(), false)
        ), "system prompt");
        responderLlama.generateReply(new GenerativeAiRequest(List.of("hello?"))).join();
        org.junit.jupiter.api.Assertions.assertFalse(clientLlama.bodies.get(0).contains("\"reasoning_effort\""));
        org.junit.jupiter.api.Assertions.assertFalse(clientLlama.bodies.get(0).contains("\"include_reasoning\""));
        org.junit.jupiter.api.Assertions.assertFalse(clientLlama.bodies.get(0).contains("\"reasoning_format\""));
    }

    @Test
    void retriesWithMinimalEffortWhenReasoningIsMandatory() {
        RecordingHttpClient client = new RecordingHttpClient();
        // First request fails with mandatory reasoning error, second succeeds
        client.responseSequence.add(new StringResponse(null, 400, "{\"error\":{\"message\":\"Reasoning is mandatory\"}}"));
        client.responseSequence.add(new StringResponse(null, 200, "{\"choices\":[{\"message\":{\"content\":\"ok after retry\"}}]}"));

        RoundRobinGenerativeAiResponder responder = new RoundRobinGenerativeAiResponder(client, List.of(
                new RoundRobinGenerativeAiResponder.Provider("OpenRouter", "https://openrouter.example/chat", "ok", "om", Map.of(), true)
        ), "system prompt");
        GenerativeAiRequest request = new GenerativeAiRequest(List.of("hello?"));

        String reply = responder.generateReply(request).join();

        assertEquals("ok after retry", reply);
        assertEquals(2, client.bodies.size());
        assertEquals("none", DataObject.fromJson(client.bodies.get(0)).getObject("reasoning").getString("effort"));
        assertEquals("minimal", DataObject.fromJson(client.bodies.get(1)).getObject("reasoning").getString("effort"));
    }

    @Test
    void sendsOpenRouterModelRoutingPayloadWithoutZdr() {
        RecordingHttpClient client = new RecordingHttpClient();
        RoundRobinGenerativeAiResponder responder = new RoundRobinGenerativeAiResponder(client, List.of(
                new RoundRobinGenerativeAiResponder.Provider("OpenRouter", "https://openrouter.example/chat", "ok", "z-ai/glm-4.5-air:free", Map.of(), true)
        ), "system prompt");
        GenerativeAiRequest request = new GenerativeAiRequest(List.of("hello?"));

        responder.generateReply(request).join();

        DataObject payload = DataObject.fromJson(client.bodies.get(0));
        assertEquals("z-ai/glm-4.5-air:free", payload.getArray("models").getString(0));
        org.junit.jupiter.api.Assertions.assertFalse(payload.hasKey("provider"));
        org.junit.jupiter.api.Assertions.assertFalse(payload.hasKey("model"));
    }

    @Test
    void trimsGroqHistoryToTokenBudgetKeepingNewest() {
        RecordingHttpClient client = new RecordingHttpClient();
        RoundRobinGenerativeAiResponder responder = new RoundRobinGenerativeAiResponder(client, List.of(
                new RoundRobinGenerativeAiResponder.Provider("Groq", "https://groq.example/chat", "ok", "openai/gpt-oss-120b", Map.of(), false)
        ), "system prompt");
        String filler = "lorem ipsum ".repeat(100);
        List<String> messages = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            messages.add("msg" + i + " " + filler);
        }
        GenerativeAiRequest request = new GenerativeAiRequest(messages);

        responder.generateReply(request).join();

        DataObject payload = DataObject.fromJson(client.bodies.get(0));
        String content = payload.getArray("messages").getObject(1).getString("content");
        assertFalse(content.contains("msg0 "));
        assertTrue(content.endsWith(messages.get(messages.size() - 1)));
    }

    @Test
    void emptyCerebrasModelsSkipsProviderEntirely() {
        GenerativeAiConfig config = config("ck", "gk", null,
                List.of(), List.of("gm"), List.of());

        List<RoundRobinGenerativeAiResponder.Provider> providers = RoundRobinGenerativeAiResponder.buildProviders(config);

        assertFalse(providers.stream().anyMatch(p -> "Cerebras".equals(p.displayName())));
        assertTrue(providers.stream().anyMatch(p -> "Groq".equals(p.displayName())));
    }

    @Test
    void buildsOnlyFreeOpenRouterModelsFromConfig() {
        GenerativeAiConfig config = config(null, null, "ok",
                List.of(), List.of(),
                List.of("openai/gpt-4.1-mini", "z-ai/glm-4.5-air:free", "openrouter/free"));

        List<RoundRobinGenerativeAiResponder.Provider> providers = RoundRobinGenerativeAiResponder.buildProviders(config);

        assertEquals(List.of("z-ai/glm-4.5-air:free", "openrouter/free"),
                providers.stream().map(RoundRobinGenerativeAiResponder.Provider::model).toList());
        assertEquals(List.of("OpenRouter", "OpenRouter"),
                providers.stream().map(RoundRobinGenerativeAiResponder.Provider::displayName).toList());
    }

    @Test
    void buildsMultipleGroqModelsFromConfig() {
        GenerativeAiConfig config = config(null, "gk", null,
                List.of("cm"), List.of("groq-model-1", "groq-model-2"), List.of());

        List<RoundRobinGenerativeAiResponder.Provider> providers = RoundRobinGenerativeAiResponder.buildProviders(config);

        assertEquals(List.of("groq-model-1", "groq-model-2"),
                providers.stream().map(RoundRobinGenerativeAiResponder.Provider::model).toList());
        assertEquals(List.of("Groq", "Groq"),
                providers.stream().map(RoundRobinGenerativeAiResponder.Provider::displayName).toList());
    }

    @Test
    void buildsMultipleCerebrasModelsFromConfig() {
        GenerativeAiConfig config = config("ck", null, null,
                List.of("cerebras-model-1", "cerebras-model-2"), List.of(), List.of());

        List<RoundRobinGenerativeAiResponder.Provider> providers = RoundRobinGenerativeAiResponder.buildProviders(config);

        assertEquals(List.of("cerebras-model-1", "cerebras-model-2"),
                providers.stream().map(RoundRobinGenerativeAiResponder.Provider::model).toList());
        assertEquals(List.of("Cerebras", "Cerebras"),
                providers.stream().map(RoundRobinGenerativeAiResponder.Provider::displayName).toList());
    }

    @Test
    void buildsProvidersInMatrixOrderExcludingArliAi() {
        GenerativeAiConfig config = new GenerativeAiConfig(
                "ck", "gk", "ok",
                "gemini-key", "mistral-key", "zai-key",
                "cf-key", "cf-account", "ollama-key", "sn-key", "arli-key",
                List.of("cerebras-model"),
                List.of("groq-model"),
                List.of("z-ai/glm-4.5-air:free"),
                List.of("gemini-model"),
                List.of("mistral-model"),
                List.of("zai-model"),
                List.of("@cf/openai/gpt-oss-120b"),
                List.of("beam"),
                List.of("gpt-oss-120b"),
                List.of("Qwen3.5-27B-Derestricted"));

        List<RoundRobinGenerativeAiResponder.Provider> providers = RoundRobinGenerativeAiResponder.buildProviders(config);

        // Ollama Cloud is first; remaining providers retain their configured fallback order.
        // ArliAI excluded from main responder
        List<String> actualOrder = providers.stream().map(RoundRobinGenerativeAiResponder.Provider::displayName).toList();
        assertEquals(List.of("Ollama", "Groq", "Cerebras", "Gemini", "Mistral", "Cloudflare", "ZAI", "SambaNova", "OpenRouter"), actualOrder);
        assertEquals("beam", providers.get(0).model());
        assertEquals(800000, RoundRobinGenerativeAiResponder.knownInputLimitForProvider(providers.get(0)));
        assertEquals(800000, RoundRobinGenerativeAiResponder.gatherBudgetForProviders(providers));
        assertFalse(actualOrder.contains("ArliAI"));
    }

    @Test
    void buildsNewProvidersWithCorrectUrls() {
        GenerativeAiConfig config = new GenerativeAiConfig(
                null, null, null,
                "gemini-key", "mistral-key", "zai-key",
                "cf-key", "cf-account", "ollama-key", "sn-key", null,
                List.of(), List.of(), List.of(),
                List.of("gemini-model"),
                List.of("mistral-model"),
                List.of("zai-model"),
                List.of("@cf/openai/gpt-oss-120b"),
                List.of("gpt-oss:120b"),
                List.of("gpt-oss-120b"),
                List.of());

        List<RoundRobinGenerativeAiResponder.Provider> providers = RoundRobinGenerativeAiResponder.buildProviders(config);

        assertEquals(
                Map.of("Gemini", "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions",
                        "Mistral", "https://api.mistral.ai/v1/chat/completions",
                        "ZAI", "https://api.z.ai/api/paas/v4/chat/completions",
                        "Cloudflare", "https://api.cloudflare.com/client/v4/accounts/cf-account/ai/v1/chat/completions",
                        "Ollama", "https://ollama.com/v1/chat/completions",
                        "SambaNova", "https://api.sambanova.ai/v1/chat/completions"),
                providers.stream().collect(java.util.stream.Collectors.toMap(
                        RoundRobinGenerativeAiResponder.Provider::displayName,
                        RoundRobinGenerativeAiResponder.Provider::url)));
    }

    @Test
    void skipsNewProvidersWhenKeysMissing() {
        GenerativeAiConfig config = new GenerativeAiConfig(
                null, null, null,
                null, "", null,
                null, "account-without-key", null, "", null,
                List.of(), List.of(), List.of(),
                List.of("gemini-model"), List.of("m"), List.of("z"), List.of("c"), List.of("o"), List.of("s"), List.of());

        assertTrue(RoundRobinGenerativeAiResponder.buildProviders(config).isEmpty());
    }

    @Test
    void cloudflareSkippedWithoutAccountIdEvenWithKey() {
        GenerativeAiConfig config = new GenerativeAiConfig(
                null, null, null,
                null, null, null,
                "cf-key", null,
                null, null, null,
                List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of("@cf/openai/gpt-oss-120b"), List.of(), List.of(), List.of());

        assertTrue(RoundRobinGenerativeAiResponder.buildProviders(config).isEmpty());
    }

    @Test
    void usesOpenRouterAsFallbackOnlyAfterAllOthersFail() {
        RecordingHttpClient client = new RecordingHttpClient();
        // Set up responses: all regular providers fail, then OpenRouter succeeds
        // Ordered list is Groq, Cerebras, OpenRouter (as injected)
        client.responseSequence.add(new StringResponse(null, 500, "Internal server error")); // Groq fails (first in ordered list if Groq first? but we inject Cerebras first, so Cerebras first)
        client.responseSequence.add(new StringResponse(null, 500, "Internal server error")); // second fails
        client.responseSequence.add(new StringResponse(null, 200, "{\"choices\":[{\"message\":{\"content\":\"ok from fallback\"}}]}")); // OpenRouter succeeds

        // Inject in order Cerebras, Groq, OpenRouter to verify ordered fallback respects injected order
        RoundRobinGenerativeAiResponder responder = new RoundRobinGenerativeAiResponder(client, List.of(
                new RoundRobinGenerativeAiResponder.Provider("Cerebras", "https://cerebras.example/chat", "ck", "cm", Map.of(), false),
                new RoundRobinGenerativeAiResponder.Provider("Groq", "https://groq.example/chat", "gk", "gm", Map.of(), false),
                new RoundRobinGenerativeAiResponder.Provider("OpenRouter", "https://openrouter.example/chat", "ok", "om", Map.of(), true)
        ), "system prompt");
        GenerativeAiRequest request = new GenerativeAiRequest(List.of("hello?"));

        String reply = responder.generateReply(request).join();

        // Should have tried Cerebras, Groq, then OpenRouter as fallback
        assertEquals(List.of(
                URI.create("https://cerebras.example/chat"),
                URI.create("https://groq.example/chat"),
                URI.create("https://openrouter.example/chat")
        ), client.uris);
        assertEquals("ok from fallback", reply);
    }

    @Test
    void arliAiExcludedFromMainProviderOrder() {
        GenerativeAiConfig config = new GenerativeAiConfig(
                "ck", "gk", "ok",
                "gemini-key", "mistral-key", "zai-key",
                "cf-key", "cf-account", "ollama-key", "sn-key", "arli-test-key",
                List.of("cm"), List.of("gm"), List.of("z-ai/glm-4.5-air:free"),
                List.of("gem"), List.of("mist"), List.of("z"), List.of("@cf/m"), List.of("oll"), List.of("sn"), List.of("arli-model"));

        List<RoundRobinGenerativeAiResponder.Provider> providers = RoundRobinGenerativeAiResponder.buildProviders(config);
        assertFalse(providers.stream().anyMatch(p -> "ArliAI".equals(p.displayName())));
        // Reaction responder should have it
        List<ArliAiReactionResponder.Provider> arliProviders = ArliAiReactionResponder.buildProviders(config);
        assertTrue(arliProviders.stream().anyMatch(p -> "ArliAI".equals(p.displayName())));
    }

    private static GenerativeAiConfig config(String cerebrasKey, String groqKey, String openrouterKey,
                                              List<String> cerebrasModels, List<String> groqModels,
                                              List<String> openrouterModels) {
        return new GenerativeAiConfig(
                cerebrasKey, groqKey, openrouterKey,
                null, null, null, null, null, null, null, null,
                cerebrasModels, groqModels, openrouterModels,
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
    }


    private static final class RecordingHttpClient extends HttpClient {
        private final List<URI> uris = new ArrayList<>();
        private final List<Duration> timeouts = new ArrayList<>();
        private final List<String> bodies = new ArrayList<>();
        private final List<HttpResponse<String>> responseSequence = new ArrayList<>();
        private int responseIndex = 0;

        @Override
        public Optional<CookieHandler> cookieHandler() {
            return Optional.empty();
        }

        @Override
        public Optional<Duration> connectTimeout() {
            return Optional.empty();
        }

        @Override
        public Redirect followRedirects() {
            return Redirect.NEVER;
        }

        @Override
        public Optional<ProxySelector> proxy() {
            return Optional.empty();
        }

        @Override
        public SSLContext sslContext() {
            try {
                return SSLContext.getDefault();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public SSLParameters sslParameters() {
            return new SSLParameters();
        }

        @Override
        public Optional<Authenticator> authenticator() {
            return Optional.empty();
        }

        @Override
        public Version version() {
            return Version.HTTP_1_1;
        }

        @Override
        public Optional<Executor> executor() {
            return Optional.empty();
        }

        @Override
        public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler)
                throws IOException, InterruptedException {
            throw new UnsupportedOperationException();
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request,
                                                                 HttpResponse.BodyHandler<T> responseBodyHandler) {
            uris.add(request.uri());
            timeouts.add(request.timeout().orElseThrow());
            bodies.add(readBody(request));
            HttpResponse<String> response = responseIndex < responseSequence.size()
                    ? responseSequence.get(responseIndex++)
                    : new StringResponse(request, 200, "{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}");
            return CompletableFuture.completedFuture((HttpResponse<T>) response);
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request,
                                                                 HttpResponse.BodyHandler<T> responseBodyHandler,
                                                                 HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
            return sendAsync(request, responseBodyHandler);
        }

        private String readBody(HttpRequest request) {
            StringBodySubscriber subscriber = new StringBodySubscriber();
            request.bodyPublisher().orElseThrow().subscribe(subscriber);
            return subscriber.bodyFuture.join();
        }
    }

    private static final class StringBodySubscriber implements Flow.Subscriber<ByteBuffer> {
        private final CompletableFuture<String> bodyFuture = new CompletableFuture<>();
        private final StringBuilder body = new StringBuilder();

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(ByteBuffer item) {
            body.append(StandardCharsets.UTF_8.decode(item));
        }

        @Override
        public void onError(Throwable throwable) {
            bodyFuture.completeExceptionally(throwable);
        }

        @Override
        public void onComplete() {
            bodyFuture.complete(body.toString());
        }
    }

    @Test
    void imageRequestBuildsContentPartsNotJoinedString() {
        RecordingHttpClient client = new RecordingHttpClient();
        RoundRobinGenerativeAiResponder responder = new RoundRobinGenerativeAiResponder(client, List.of(
                new RoundRobinGenerativeAiResponder.Provider("Gemini", "https://gemini.example/chat", "gk", "gemini-2.5-flash", Map.of(), false)
        ), "system prompt");
        GenerativeAiRequest request = new GenerativeAiRequest(List.of("what color?"), "system prompt",
                List.of(new VisionImage("aGVsbG8=", "image/png")));

        responder.generateReply(request).join();

        DataObject payload = DataObject.fromJson(client.bodies.get(0));
        DataObject userMessage = payload.getArray("messages").getObject(1);
        DataArray parts = userMessage.getArray("content");
        assertEquals("text", parts.getObject(0).getString("type"));
        assertEquals("what color?", parts.getObject(0).getString("text"));
        assertEquals("image_url", parts.getObject(1).getString("type"));
        assertEquals("data:image/png;base64,aGVsbG8=", parts.getObject(1).getObject("image_url").getString("url"));
    }

    @Test
    void visionProvidersFilterToVisionModelsGeminiFirst() {
        // defaults() reads API keys from env (empty here), and buildProviders
        // skips keyless providers, so pass dummy keys to get a full chain.
        GenerativeAiConfig config = new GenerativeAiConfig(
                "ck", "gk", "ok", "gemk", "mk", "zk", "cfk", "cfid", "ollk", "snk", "ark");
        List<RoundRobinGenerativeAiResponder.Provider> vision = RoundRobinGenerativeAiResponder.visionProviders(config);

        assertFalse(vision.isEmpty());
        assertTrue(vision.stream().allMatch(p -> GenerativeAiConfig.supportsVision(p.model())));
        assertTrue(vision.stream().noneMatch(p -> p.model().equals("beam")));
        assertEquals("Gemini", vision.get(0).displayName());
    }

    private record StringResponse(HttpRequest request, int statusCode, String body) implements HttpResponse<String> {
        @Override
        public HttpHeaders headers() {
            return HttpHeaders.of(Map.of(), (name, value) -> true);
        }

        @Override
        public Optional<HttpResponse<String>> previousResponse() {
            return Optional.empty();
        }

        @Override
        public URI uri() {
            return request != null ? request.uri() : URI.create("http://localhost");
        }

        @Override
        public HttpClient.Version version() {
            return HttpClient.Version.HTTP_1_1;
        }

        @Override
        public Optional<javax.net.ssl.SSLSession> sslSession() {
            return Optional.empty();
        }
    }
}
