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
                firstNonBlank(System.getenv("ARLI_API_KEY"), System.getenv("ARLIAI_API_KEY")),
                List.of("qwen-3-235b-a22b-instruct-2507"),
                List.of("meta-llama/llama-4-scout-17b-16e-instruct"),
                List.of("openrouter/free"),
                List.of("gemini-3.7-flash", "gemini-3.6-flash", "gemini-3.5-flash",
                        "gemini-2.5-flash", "gemini-2.5-flash-lite", "gemma-4-31b-it"),
                List.of("mistral-large-latest", "mistral-medium-latest", "magistral-small-latest",
                        "mistral-small-latest", "codestral-latest", "open-mistral-nemo", "ministral-8b-latest"),
                List.of("glm-4.7-flash", "glm-4.5-flash"),
                List.of("@cf/openai/gpt-oss-120b"),
                List.of("beam", "nemotron-3-ultra", "gpt-oss:120b", "gemma4:31b"),
                List.of("DeepSeek-V3.1", "gpt-oss-120b", "Meta-Llama-3.3-70B-Instruct"),
                List.of("Qwen3.5-27B-Derestricted"));
    }

    private static String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : second;
    }
}
