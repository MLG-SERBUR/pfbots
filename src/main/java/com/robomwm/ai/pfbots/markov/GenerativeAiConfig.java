package com.robomwm.ai.pfbots.markov;

import java.util.List;

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
