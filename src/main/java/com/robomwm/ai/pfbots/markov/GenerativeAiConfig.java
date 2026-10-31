package com.robomwm.ai.pfbots.markov;

import java.util.List;
import java.util.Set;

public record GenerativeAiConfig(
        String cerebrasApiKey,
        String groqApiKey,
        String openrouterApiKey,
        String geminiApiKey,
        String mistralApiKey,
        String zaiApiKey,
        String cloudflareApiKey,
        String cloudflareAccountId,
        String ollamaApiKey,
        String sambaNovaApiKey,
        String arliApiKey,
        List<String> cerebrasModels,
        List<String> groqModels,
        List<String> openrouterModels,
        List<String> geminiModels,
        List<String> mistralModels,
        List<String> zaiModels,
        List<String> cloudflareModels,
        List<String> ollamaModels,
        List<String> sambaNovaModels,
        List<String> arliModels
) {
    public static final List<String> CEREBRAS_MODELS = List.of();
    public static final List<String> GROQ_MODELS = List.of(
            "openai/gpt-oss-20b",
            "qwen/qwen3.8-27b",
            "openai/gpt-oss-120b"
    );
    public static final List<String> OPENROUTER_MODELS = List.of(
            "openrouter/free"
    );
    public static final List<String> GEMINI_MODELS = List.of(
            "gemini-3.8-flash",
            "gemini-3.7-flash",
            "gemini-3.6-flash",
            "gemini-3.5-flash",
            "gemini-2.5-flash",
            "gemini-2.5-flash-lite",
            "gemma-4-31b-it"
    );
    public static final List<String> MISTRAL_MODELS = List.of(
            "mistral-large-latest",
            "mistral-medium-latest",
            "magistral-small-latest",
            "mistral-small-latest",
            "codestral-latest",
            "open-mistral-nemo",
            "ministral-8b-latest"
    );
    public static final List<String> ZAI_MODELS = List.of(
            "glm-4.7-flash",
            "glm-4.5-flash"
    );
    public static final List<String> CLOUDFLARE_MODELS = List.of(
            "@cf/openai/gpt-oss-120b"
    );
    public static final List<String> OLLAMA_MODELS = List.of(
            "beam",
            "nemotron-3-ultra",
            "gpt-oss:120b",
            "gemma4:31b"
    );
    public static final List<String> SAMBANOVA_MODELS = List.of(
            "DeepSeek-V3.1",
            "gpt-oss-120b",
            "Meta-Llama-3.3-70B-Instruct"
    );
    public static final List<String> ARLI_MODELS = List.of(
            "Qwen3.5-27B-Derestricted"
    );

    /**
     * Model ids (exact strings from the lists above) that accept image input.
     * Checked against provider docs Oct 2026; a false entry is survivable
     * (the vision responder falls back to the next vision provider), a
     * missing entry means that model is never tried for vision.
     * Vision: all Gemini Flash/Gemma 4 (natively multimodal), Groq
     * qwen/qwen3.8-27b (Groq docs: its only multimodal self-serve model),
     * Mistral large/medium/small/magistral-small/ministral-8b latest
     * (current general-purpose Mistral models take image input), Ollama
     * gemma4:31b (multimodal Gemma 4).
     * Not vision: beam (text-in, text-out), gpt-oss (all providers),
     * nemotron-3-ultra (the Omni variant is the multimodal one),
     * DeepSeek-V3.1, Meta-Llama-3.3-70B, glm-*-flash (text only; the
     * vision GLMs are the -v variants, not in this config), codestral
     * (code), open-mistral-nemo (text only), openrouter/free (routing
     * is unknown, so vision is not deterministic), Arli Qwen3.5 (text).
     */
    public static final Set<String> VISION_MODELS = Set.of(
            "gemini-3.8-flash",
            "gemini-3.7-flash",
            "gemini-3.6-flash",
            "gemini-3.5-flash",
            "gemini-2.5-flash",
            "gemini-2.5-flash-lite",
            "gemma-4-31b-it",
            "qwen/qwen3.8-27b",
            "mistral-large-latest",
            "mistral-medium-latest",
            "magistral-small-latest",
            "mistral-small-latest",
            "ministral-8b-latest",
            "gemma4:31b"
    );

    public static boolean supportsVision(String model) {
        return model != null && VISION_MODELS.contains(model);
    }

    public GenerativeAiConfig(
            String cerebrasApiKey,
            String groqApiKey,
            String openrouterApiKey,
            String geminiApiKey,
            String mistralApiKey,
            String zaiApiKey,
            String cloudflareApiKey,
            String cloudflareAccountId,
            String ollamaApiKey,
            String sambaNovaApiKey,
            String arliApiKey) {
        this(cerebrasApiKey, groqApiKey, openrouterApiKey, geminiApiKey, mistralApiKey,
                zaiApiKey, cloudflareApiKey, cloudflareAccountId, ollamaApiKey, sambaNovaApiKey, arliApiKey,
                CEREBRAS_MODELS, GROQ_MODELS, OPENROUTER_MODELS, GEMINI_MODELS, MISTRAL_MODELS,
                ZAI_MODELS, CLOUDFLARE_MODELS, OLLAMA_MODELS, SAMBANOVA_MODELS, ARLI_MODELS);
    }

    public static GenerativeAiConfig defaults() {
        return new GenerativeAiConfig(
                System.getenv("CEREBRAS_API_KEY"),
                System.getenv("GROQ_API_KEY"),
                System.getenv("OPENROUTER_API_KEY"),
                System.getenv("GEMINI_API_KEY"),
                System.getenv("MISTRAL_API_KEY"),
                System.getenv("ZAI_API_KEY"),
                System.getenv("CLOUDFLARE_API_KEY"),
                System.getenv("CLOUDFLARE_ACCOUNT_ID"),
                System.getenv("OLLAMA_API_KEY"),
                System.getenv("SAMBANOVA_API_KEY"),
                firstNonBlank(System.getenv("ARLI_API_KEY"), System.getenv("ARLIAI_API_KEY")));
    }

    private static String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : second;
    }
}
