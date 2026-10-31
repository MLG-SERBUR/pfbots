package com.robomwm.ai.pfbots.markov;

import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public class RoundRobinGenerativeAiResponder implements GenerativeAiResponder {
    private static final int REQUEST_TIMEOUT_SECONDS = 45;
    private static final long GROQ_TOKEN_BUDGET = 8000;
    /** Observed Groq input-tokens-per-minute limits: qwen models get ~7k, gpt ~8k. */
    static final int GROQ_QWEN_IPTM_LIMIT = 7000;
    static final int GROQ_GPT_IPTM_LIMIT = 8000;
    static final int GROQ_DEFAULT_IPTM_LIMIT = 8000;
    // Free-tier per-request input budgets (researched Sep 2026; recheck when
    // fallbacks start failing — providers change these without notice):
    // Cerebras free trial: 30K uncached input TPM (official model docs).
    static final int CEREBRAS_INPUT_LIMIT = 30000;
    // Gemini free: ~250K input TPM on Flash-class models (conservative floor;
    // Lite models allow more). Context is 1M, TPM binds first.
    static final int GEMINI_INPUT_LIMIT = 250000;
    // SambaNova free: no per-minute token cap published (20 RPM / 20 RPD /
    // 200K TPD per model); binding per-request limit is model context, min
    // 128K across configured models (DeepSeek-V3.1, Llama-3.3-70B).
    static final int SAMBANOVA_INPUT_LIMIT = 128000;
    // Z.ai free Flash models (glm-4.7/4.5-flash, $0): ~200K context, no
    // published TPM; context binds per request.
    static final int ZAI_INPUT_LIMIT = 200000;
    // Cloudflare Workers AI: 300 RPM text-gen, 10K neurons/day free; no
    // per-request token cap published, so model context binds
    // (@cf/openai/gpt-oss-120b = 131K).
    static final int CLOUDFLARE_INPUT_LIMIT = 131072;
    static final int OLLAMA_BEAM_INPUT_LIMIT = 800000;
    // Mistral free (Experiment), Ollama Cloud (GPU-time quotas) and OpenRouter
    // :free (model-dependent context) have no usable published per-request
    // token number -> unknown (null), except Ollama Beam.
    /**
     * Fetch ceiling for history gathering: file reads are cheap (no Matrix
     * pagination timeout), but prompts above this are unusable without a
     * gather rework. Also the default when no chain attempt has a known limit.
     */
    static final int MAX_GATHER_BUDGET = 800000;
    /** Headroom kept when trimming to a limit, so the retry fits under TPM/context. */
    static final double TRIM_HEADROOM_RATIO = 0.85;
    private final HttpClient client;
    private final List<Provider> providers;
    private final String systemPrompt;

    /** Per-bot variant: shared provider backend, own system prompt from code. */
    public RoundRobinGenerativeAiResponder(GenerativeAiConfig config, String systemPrompt) {
        this(HttpClient.newHttpClient(), buildProviders(config), systemPrompt);
    }

    /** Vision-only responder: vision models only, Gemini first (fast, free-tier, multimodal). */
    public static RoundRobinGenerativeAiResponder visionResponder(GenerativeAiConfig config) {
        return new RoundRobinGenerativeAiResponder(HttpClient.newHttpClient(), visionProviders(config),
                "Answer only about the image provided. Describe only what is actually visible, never guess.");
    }

    /** Full provider chain filtered to vision models, in vision-preference order. */
    static List<Provider> visionProviders(GenerativeAiConfig config) {
        List<Provider> vision = new ArrayList<>(buildProviders(config).stream()
                .filter(p -> GenerativeAiConfig.supportsVision(p.model()))
                .toList());
        // Stable sort: Gemini, Groq, Mistral, Ollama, anything else last.
        vision.sort((a, b) -> Integer.compare(visionPriority(a.displayName()), visionPriority(b.displayName())));
        return vision;
    }

    private static int visionPriority(String displayName) {
        return switch (displayName) {
            case "Gemini" -> 0;
            case "Groq" -> 1;
            case "Mistral" -> 2;
            case "Ollama" -> 3;
            default -> 4;
        };
    }

    public RoundRobinGenerativeAiResponder(HttpClient client, List<Provider> providers, String systemPrompt) {
        this.client = client;
        this.providers = List.copyOf(providers);
        this.systemPrompt = systemPrompt;
    }

    public String getSystemPrompt() {
        return systemPrompt;
    }

    @Override
    public CompletableFuture<String> generateReply(GenerativeAiRequest request) {
        if (providers.isEmpty()) {
            return CompletableFuture.failedFuture(new IllegalStateException("No generative AI providers configured"));
        }
        long deadlineMs = System.currentTimeMillis() + (REQUEST_TIMEOUT_SECONDS * 1000L);
        return attemptGenerateReply(request, deadlineMs, 0, "none", false);
    }

    private CompletableFuture<String> attemptGenerateReply(GenerativeAiRequest request, long deadlineMs, int attempts, String reasoningEffort, boolean calibrationRetryDone) {
        long timeRemainingMs = deadlineMs - System.currentTimeMillis();
        if (attempts > 0 && timeRemainingMs < 0) {
            return CompletableFuture.failedFuture(new IllegalStateException("Not enough time remaining to try next provider"));
        }

        if (attempts >= providers.size()) {
            return CompletableFuture.failedFuture(new IllegalStateException("No more providers available"));
        }

        Provider provider = providers.get(attempts);
        return attemptWithProvider(provider, request, deadlineMs, attempts, reasoningEffort, calibrationRetryDone);
    }

    private CompletableFuture<String> attemptWithProvider(Provider provider, GenerativeAiRequest request, long deadlineMs, int attempts, String reasoningEffort, boolean calibrationRetryDone) {
        HttpRequest httpRequest;
        try {
            httpRequest = buildRequest(provider, request, reasoningEffort);
        } catch (Exception e) {
            return fallback(request, deadlineMs, attempts + 1, e, calibrationRetryDone);
        }

        System.out.println("Starting " + provider.displayName() + " (" + provider.model() + ") request with reasoning effort: " + reasoningEffort + "...");
        return client.sendAsync(httpRequest, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> parseResponse(provider, response))
                .exceptionallyCompose(e -> {
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    if (cause instanceof ReasoningMandatoryException && "none".equals(reasoningEffort)) {
                        System.out.println("Reasoning is mandatory for " + provider.model() + ". Retrying same provider with 'minimal' effort...");
                        return attemptWithProvider(provider, request, deadlineMs, attempts, "minimal", calibrationRetryDone);
                    }
                    // Self-calibration: heuristic underestimated -> update factor and retry once with truncated history
                    String errorMsg = cause.getMessage() != null ? cause.getMessage() : cause.toString();
                    if (!calibrationRetryDone && TokenCalibrationManager.isCalibrationError(errorMsg)) {
                        String promptForCalibration = buildCalibrationPrompt(request, provider);
                        TokenCalibrationManager.getInstance().recordFromError(promptForCalibration, errorMsg, provider.model());
                        Integer actual = TokenCalibrationManager.extractActualTokensForCalibration(errorMsg);
                        Integer limit = TokenCalibrationManager.extractLimitForCalibration(errorMsg);
                        if (actual != null && limit != null && request.recentMessages() != null && request.recentMessages().size() > 10) {
                            int newSize = TokenCalibrationManager.computeTrimmedSize(request.recentMessages().size(), actual, limit);
                            if (newSize < request.recentMessages().size()) {
                                double targetRatio = (double) newSize / request.recentMessages().size();
                                java.util.List<String> truncated = new java.util.ArrayList<>(request.recentMessages().subList(request.recentMessages().size() - newSize, request.recentMessages().size()));
                                GenerativeAiRequest truncatedRequest = new GenerativeAiRequest(truncated, request.systemPromptOverride());
                                String calMsg = "Context exceeded (" + actual + "/" + limit + "). Calibrated factor ["
                                        + TokenCalibrationManager.familyForModel(provider.model()) + "] to " + String.format("%.2f", TokenCalibrationManager.getInstance().getFactor(provider.model())) + " and retrying with " + newSize + " messages (" + (int) (targetRatio * 100) + "%)...";
                                System.out.println(calMsg);
                                return attemptWithProvider(provider, truncatedRequest, deadlineMs, attempts, reasoningEffort, true);
                            }
                        }
                    }
                    return fallback(request, deadlineMs, attempts + 1, e, calibrationRetryDone);
                });
    }

    private CompletableFuture<String> fallback(GenerativeAiRequest request, long deadlineMs, int nextAttempts, Throwable e, boolean calibrationRetryDone) {
        System.err.println("Provider failed: " + e.toString());
        long timeRemainingMs = deadlineMs - System.currentTimeMillis();

        if (nextAttempts >= providers.size() || timeRemainingMs <= 2000) {
            return CompletableFuture.failedFuture(e);
        }
        System.out.println("Retrying next provider...");
        return attemptGenerateReply(request, deadlineMs, nextAttempts, "none", calibrationRetryDone);
    }

    private String buildCalibrationPrompt(GenerativeAiRequest request, Provider provider) {
        String sys = request.systemPromptOverride() != null ? request.systemPromptOverride() : systemPrompt;
        java.util.List<String> capped = capForSmallContextProvider(provider, request.recentMessages(), sys);
        String joined = capped == null ? "" : String.join("\n", capped);
        return (sys != null ? sys : "") + "\n" + joined;
    }

    private HttpRequest buildRequest(Provider provider, GenerativeAiRequest request, String reasoningEffort) {
        String effectiveSystemPrompt = request.systemPromptOverride() == null ? systemPrompt : request.systemPromptOverride();
        List<String> cappedMessages = capForSmallContextProvider(provider, request.recentMessages(), effectiveSystemPrompt);
        DataObject userMessage = DataObject.empty().put("role", "user");
        if (request.images() == null || request.images().isEmpty()) {
            userMessage.put("content", String.join("\n", cappedMessages));
        } else {
            // OpenAI-compatible content parts: same shape for Gemini, Groq, Mistral, Ollama.
            // Image tokens are not in the text budget; vision calls carry one image only.
            DataArray parts = DataArray.empty()
                    .add(DataObject.empty()
                            .put("type", "text")
                            .put("text", String.join("\n", cappedMessages)));
            for (VisionImage image : request.images()) {
                parts.add(DataObject.empty()
                        .put("type", "image_url")
                        .put("image_url", DataObject.empty()
                                .put("url", "data:" + image.mimeType() + ";base64," + image.base64())));
            }
            userMessage.put("content", parts);
        }
        DataObject payload = DataObject.empty()
                .put("stream", false)
                .put("messages", DataArray.empty()
                        .add(DataObject.empty()
                                .put("role", "system")
                                .put("content", effectiveSystemPrompt))
                        .add(userMessage));
        if ("OpenRouter".equals(provider.displayName())) {
            payload.put("models", DataArray.empty().add(provider.model()));
        } else {
            payload.put("model", provider.model());
        }
        if (provider.disableReasoning()) {
            payload.put("reasoning", DataObject.empty()
                    .put("effort", reasoningEffort));
        }
        suppressGroqReasoningOutput(provider, payload, reasoningEffort);
        suppressGenericReasoning(provider, payload, reasoningEffort);

        byte[] payloadJson = payload.toJson();
        try {
            int previewCount = Math.min(5, cappedMessages.size());
            List<String> preview = cappedMessages.subList(Math.max(0, cappedMessages.size() - previewCount), cappedMessages.size());
            String previewStr = String.join(" | ", preview).replace("\n", " ");
            if (previewStr.length() > 1000) previewStr = previewStr.substring(0, 1000) + "...";
            // Log actual requested effort; payload may map none->low for gpt-oss, check payload for final value
            String loggedEffort = payload.hasKey("reasoning_effort") ? payload.getString("reasoning_effort") : payload.hasKey("reasoning") ? payload.getObject("reasoning").getString("effort", reasoningEffort) : reasoningEffort;
            if (payload.hasKey("chat_template_kwargs")) loggedEffort += "+no_think";
            String joinedForEst = String.join("\n", cappedMessages);
            long modelEst = PromptTokenizer.estimateTokens((effectiveSystemPrompt != null ? effectiveSystemPrompt + "\n" : "") + joinedForEst, provider.model());
            String dualEst = PromptTokenizer.formatDualTokenEstimates(joinedForEst);
            System.out.println("AI Request: " + provider.displayName() + " (" + provider.model() + ") reasoning=" + loggedEffort + " history=" + cappedMessages.size() + " est=" + PromptTokenizer.formatTokenCount(modelEst) + " (" + dualEst + ") previewLast" + previewCount + ": " + previewStr);
        } catch (Exception logEx) {
            System.out.println("AI Request: " + provider.displayName() + " (" + provider.model() + ") [preview log failed: " + logEx + "]");
        }

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(provider.url()))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + provider.apiKey())
                .timeout(Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS))
                .POST(HttpRequest.BodyPublishers.ofByteArray(payloadJson));

        for (Map.Entry<String, String> header : provider.extraHeaders().entrySet()) {
            builder.header(header.getKey(), header.getValue());
        }

        return builder.build();
    }

    private static boolean isFreeOpenRouterModel(String model) {
        return model != null && (model.equals("openrouter/free") || model.contains(":free"));
    }

    static boolean isGroqQwenModel(String model) {
        return model != null && model.toLowerCase().contains("qwen");
    }

    static int groqIptmLimitForModel(String model) {
        if (isGroqQwenModel(model)) return GROQ_QWEN_IPTM_LIMIT;
        if (model != null && model.toLowerCase().contains("gpt")) return GROQ_GPT_IPTM_LIMIT;
        return GROQ_DEFAULT_IPTM_LIMIT;
    }

    /** Token budget for a provider: known per-request input limit, else 8k. */
    static long tokenBudgetForProvider(Provider provider) {
        Integer known = knownInputLimitForProvider(provider);
        if (known != null) return known;
        return GROQ_TOKEN_BUDGET;
    }

    /**
     * Known input-token budget for a provider, or null when unknown.
     * Groq limits are observed values; Cerebras/Gemini are official free-tier
     * TPM; SambaNova/ZAI/Cloudflare fall back to model context (no published
     * per-request token cap). Unknown backends never constrain the gather.
     */
    static Integer knownInputLimitForProvider(Provider provider) {
        if (provider == null || provider.displayName() == null) return null;
        switch (provider.displayName()) {
            case "Groq": return groqIptmLimitForModel(provider.model());
            case "Cerebras": return CEREBRAS_INPUT_LIMIT;
            case "Gemini": return GEMINI_INPUT_LIMIT;
            case "SambaNova": return SAMBANOVA_INPUT_LIMIT;
            case "ZAI": return ZAI_INPUT_LIMIT;
            case "Cloudflare": return CLOUDFLARE_INPUT_LIMIT;
            case "Ollama": return "beam".equalsIgnoreCase(provider.model()) ? OLLAMA_BEAM_INPUT_LIMIT : null;
            default: return null;
        }
    }

    /**
     * Initial history-gather budget: the known limit of the first provider
     * with one (i.e. the model currently used first), capped at the fetch
     * ceiling, so reordering the fallback chain needs no code change.
     * Falls back to MAX_GATHER_BUDGET when no provider has a known limit.
     * Per-provider trim (capForSmallContextProvider) shrinks from here for
     * smaller fallbacks. No expand step: the file-based gather is single-shot
     * and the responder has no history access for re-gathering.
     */
    static int gatherBudgetForProviders(List<Provider> providers) {
        if (providers != null) {
            for (Provider provider : providers) {
                Integer limit = knownInputLimitForProvider(provider);
                if (limit != null) return Math.min(limit, MAX_GATHER_BUDGET);
            }
        }
        return MAX_GATHER_BUDGET;
    }

    /** Initial history-gather budget for this responder's provider chain. */
    public int gatherBudget() {
        return gatherBudgetForProviders(providers);
    }

    private List<String> capForSmallContextProvider(Provider provider, List<String> messages) {
        String effectiveSystemPrompt = this.systemPrompt;
        return capForSmallContextProvider(provider, messages, effectiveSystemPrompt);
    }

    private static List<String> capForSmallContextProvider(Provider provider, List<String> messages, String systemPrompt) {
        if (messages == null || messages.isEmpty()) {
            return messages;
        }
        Integer knownLimit = knownInputLimitForProvider(provider);
        if (knownLimit == null) {
            return messages;
        }
        long tokenBudget = knownLimit;
        long systemTokens = systemPrompt != null ? PromptTokenizer.estimateTokens(systemPrompt + "\n", provider.model()) : 0;
        long overheadTokens = PromptTokenizer.estimateTokens("system\nuser\n", provider.model());
        long effectiveBudget = tokenBudget - systemTokens - overheadTokens;
        if (effectiveBudget < 500) effectiveBudget = tokenBudget - systemTokens;
        if (effectiveBudget <= 0) effectiveBudget = tokenBudget;

        long[] tokenCounts = new long[messages.size()];
        long total = 0;
        for (int i = 0; i < messages.size(); i++) {
            tokenCounts[i] = PromptTokenizer.estimateTokens(messages.get(i), provider.model());
            total += tokenCounts[i];
        }
        if (total <= effectiveBudget) {
            return messages;
        }

        int start = messages.size() - 1;
        long budget = effectiveBudget - tokenCounts[start];
        while (start > 0 && tokenCounts[start - 1] <= budget) {
            start--;
            budget -= tokenCounts[start];
        }
        System.out.println("Trimmed " + start + " oldest messages to fit " + provider.displayName()
                + " (" + provider.model() + ") token budget of " + tokenBudget + " tokens (effective " + effectiveBudget + " after system=" + systemTokens + ").");
        return new ArrayList<>(messages.subList(start, messages.size()));
    }

    private void suppressGroqReasoningOutput(Provider provider, DataObject payload, String reasoningEffort) {
        if (!"Groq".equals(provider.displayName())) {
            return;
        }

        String model = provider.model().toLowerCase();
        if (model.startsWith("openai/gpt-oss-")) {
            payload.put("include_reasoning", false);
            String effort = "none".equals(reasoningEffort) ? "low" : "medium";
            payload.put("reasoning_effort", effort);
        } else if (isGroqQwenModel(model)) {
            // No reasoning to format: reasoning_effort="none" disables thinking entirely
            // (Groq docs: qwen3 models support none/default), so reasoning_format is omitted.
            payload.put("reasoning_effort", reasoningEffort);
            // ArliAI/vLLM style: Groq docs confirm reasoning_effort="none" disables for qwen3 (qwen/qwen3.6-27b supports none/default),
            // but underlying vLLM template also respects chat_template_kwargs.enable_thinking=false (see Qwen3, Featherless, vLLM docs).
            // Add it when we intend to disable reasoning to ensure true non-thinking mode and avoid hidden reasoning time.
            if ("none".equals(reasoningEffort)) {
                payload.put("chat_template_kwargs", DataObject.empty().put("enable_thinking", false));
            }
        }
    }

    private void suppressGenericReasoning(Provider provider, DataObject payload, String reasoningEffort) {
        if ("Groq".equals(provider.displayName()) || "OpenRouter".equals(provider.displayName())) {
            return;
        }
        String model = provider.model().toLowerCase();
        // gpt-oss family cannot be fully disabled (always-on per Groq, Cerebras, SambaNova docs) -> use low (minimal)
        if (model.contains("gpt-oss")) {
            String effort = "none".equals(reasoningEffort) ? "low" : "medium";
            payload.put("reasoning_effort", effort);
            payload.put("include_reasoning", false);
            return;
        }
        // For all other providers (Cerebras qwen, Gemini, Mistral, ZAI/GLM, Cloudflare, Ollama, SambaNova non-gpt-oss)
        // try true disable via multiple compatible signals:
        // - reasoning_effort=none (Groq qwen, Gemini OpenAI compat, Mistral, Cerebras, ZAI docs all support none)
        // - reasoning_format=hidden (suppress output even if reasoning still happens)
        // - chat_template_kwargs.enable_thinking=false (ArliAI/vLLM/Featherless/Qwen3 docs for true non-thinking mode)
        payload.put("reasoning_effort", reasoningEffort);
        payload.put("reasoning_format", "hidden");
        payload.put("chat_template_kwargs", DataObject.empty().put("enable_thinking", false));
        // Gemini 2.5 Flash via OpenAI compat also accepts none, but native Gemini thinkingBudget 0 is alternative;
        // we avoid adding thinking_budget to not break other providers, reasoning_effort none is sufficient
    }


    private String parseResponse(Provider provider, HttpResponse<String> response) {
        String bodyPreview = response.body() != null ? response.body().substring(0, Math.min(2000, response.body().length())) : "null";
        System.out.println("AI Response: " + provider.displayName() + " (" + provider.model() + ") status=" + response.statusCode() + " body=" + bodyPreview.replace("\n", " "));
        if (response.statusCode() != 200) {
            String body = response.body();
            if (response.statusCode() == 400 && body.contains("Reasoning is mandatory")) {
                throw new ReasoningMandatoryException(provider.model() + " requires reasoning.");
            }
            throw new IllegalStateException(provider.displayName() + " (" + provider.model()
                    + ") failed. Status: " + response.statusCode() + ", Body: " + body);
        }

        try {
            DataObject root = DataObject.fromJson(response.body());
            DataArray choices = root.getArray("choices");
            if (choices.isEmpty()) {
                throw new IllegalStateException("Missing 'choices' array");
            }
            String text = choices.getObject(0).getObject("message").getString("content", null);
            if (text == null || text.trim().isEmpty()) {
                throw new IllegalStateException("No response from " + provider.displayName() + " (" + provider.model() + ").");
            }
            return MarkovManager.stripBotPrefix(text);
        } catch (Exception e) {
            throw new IllegalStateException("Unexpected response from " + provider.displayName() + " ("
                    + provider.model() + "). Body: " + response.body(), e);
        }
    }

    static List<Provider> buildProviders(GenerativeAiConfig config) {
        List<Provider> providers = new ArrayList<>();
        // Ollama Cloud is first; remaining provider order mirrors matrix-robobot, excluding ArliAI.
        // Matrix order: GROQ, ARLIAI, OLLAMA_PROXY, FREELLM, CEREBRAS, GEMINI, MISTRAL, CLOUDFLARE, OLLAMA_CLOUD, ZAI, SAMBANOVA, OPENROUTER
        // pfbots mapping (ArliAI excluded, OllamaProxy/FreeLLM not present):
        // OLLAMA_CLOUD -> Ollama, GROQ -> Groq, CEREBRAS -> Cerebras, GEMINI -> Gemini, MISTRAL -> Mistral,
        // CLOUDFLARE -> Cloudflare, ZAI -> ZAI, SAMBANOVA -> SambaNova, OPENROUTER -> OpenRouter (last, free models only)
        if (hasText(config.ollamaApiKey())) {
            List<String> ms = models(config.ollamaModels());
            if (!ms.isEmpty()) {
                for (String model : ms) {
                    providers.add(new Provider(
                            "Ollama",
                            "https://ollama.com/v1/chat/completions",
                            config.ollamaApiKey(),
                            model,
                            Map.of(),
                            false));
                }
            }
        }
        if (hasText(config.groqApiKey())) {
            List<String> ms = models(config.groqModels());
            if (ms.isEmpty()) {
                // skipped: no models set
            } else {
                for (String model : ms) {
                    providers.add(new Provider(
                            "Groq",
                            "https://api.groq.com/openai/v1/chat/completions",
                            config.groqApiKey(),
                            model,
                            Map.of(),
                            false));
                }
            }
        }
        if (hasText(config.cerebrasApiKey())) {
            List<String> ms = models(config.cerebrasModels());
            if (!ms.isEmpty()) {
                for (String model : ms) {
                    providers.add(new Provider(
                            "Cerebras",
                            "https://api.cerebras.ai/v1/chat/completions",
                            config.cerebrasApiKey(),
                            model,
                            Map.of(),
                            false));
                }
            }
        }
        if (hasText(config.geminiApiKey())) {
            List<String> ms = models(config.geminiModels());
            if (!ms.isEmpty()) {
                for (String model : ms) {
                    providers.add(new Provider(
                            "Gemini",
                            "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions",
                            config.geminiApiKey(),
                            model,
                            Map.of(),
                            false));
                }
            }
        }
        if (hasText(config.mistralApiKey())) {
            List<String> ms = models(config.mistralModels());
            if (!ms.isEmpty()) {
                for (String model : ms) {
                    providers.add(new Provider(
                            "Mistral",
                            "https://api.mistral.ai/v1/chat/completions",
                            config.mistralApiKey(),
                            model,
                            Map.of(),
                            false));
                }
            }
        }
        if (hasText(config.cloudflareApiKey()) && hasText(config.cloudflareAccountId())) {
            List<String> ms = models(config.cloudflareModels());
            if (!ms.isEmpty()) {
                for (String model : ms) {
                    providers.add(new Provider(
                            "Cloudflare",
                            "https://api.cloudflare.com/client/v4/accounts/" + config.cloudflareAccountId()
                                    + "/ai/v1/chat/completions",
                            config.cloudflareApiKey(),
                            model,
                            Map.of(),
                            false));
                }
            }
        }
        if (hasText(config.zaiApiKey())) {
            List<String> ms = models(config.zaiModels());
            if (!ms.isEmpty()) {
                for (String model : ms) {
                    providers.add(new Provider(
                            "ZAI",
                            "https://api.z.ai/api/paas/v4/chat/completions",
                            config.zaiApiKey(),
                            model,
                            Map.of(),
                            false));
                }
            }
        }
        if (hasText(config.sambaNovaApiKey())) {
            List<String> ms = models(config.sambaNovaModels());
            if (!ms.isEmpty()) {
                for (String model : ms) {
                    providers.add(new Provider(
                            "SambaNova",
                            "https://api.sambanova.ai/v1/chat/completions",
                            config.sambaNovaApiKey(),
                            model,
                            Map.of(),
                            false));
                }
            }
        }
        if (hasText(config.openrouterApiKey())) {
            List<String> ms = models(config.openrouterModels()).stream().filter(RoundRobinGenerativeAiResponder::isFreeOpenRouterModel).toList();
            if (!ms.isEmpty()) {
                for (String model : ms) {
                    providers.add(new Provider(
                            "OpenRouter",
                            "https://openrouter.ai/api/v1/chat/completions",
                            config.openrouterApiKey(),
                            model,
                            Map.of(
                                    "HTTP-Referer", "https://github.com/MLG-SERBUR/pfbots",
                                    "X-Title", "pfbots"),
                            true));
                }
            }
        }
        // Note: ArliAI intentionally excluded from main response flow; see ArliAiReactionResponder for reaction use.
        return providers;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static List<String> models(List<String> models) {
        if (models == null) {
            return List.of();
        }
        return models.stream()
                .filter(RoundRobinGenerativeAiResponder::hasText)
                .toList();
    }

    public record Provider(String displayName, String url, String apiKey, String model,
                    Map<String, String> extraHeaders, boolean disableReasoning) {
    }

    private static final class ReasoningMandatoryException extends RuntimeException {
        public ReasoningMandatoryException(String message) {
            super(message);
        }
    }
}
