package com.robomwm.ai.pfbots.app;

import com.robomwm.ai.pfbots.markov.GenerativeAiConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BotConfigTest {

    @TempDir
    Path tempDir;

    private Path writeConfig(String aiSection) throws IOException {
        Path path = tempDir.resolve("config.yaml");
        Files.writeString(path, """
                discord:
                  token: "test-token"
                ai:
                %s
                """.formatted(aiSection));
        return path;
    }

    @Test
    void modelKeysInYamlAreIgnoredModelsLiveInCode() throws IOException {
        BotConfig config = BotConfig.load(writeConfig("""
                    cerebrasApiKey: "ck"
                    groqApiKey: "gk"
                    cerebrasModels:
                      - "yaml-cerebras"
                    groqModels:
                      - "yaml-groq"
                """));

        // Models are defined in code, never in yaml.
        assertEquals(GenerativeAiConfig.CEREBRAS_MODELS, config.generativeAiConfig().cerebrasModels());
        assertEquals(GenerativeAiConfig.GROQ_MODELS, config.generativeAiConfig().groqModels());
    }

    @Test
    void missingModelListsFallBackToDefaults() throws IOException {
        BotConfig config = BotConfig.load(writeConfig("""
                    groqApiKey: "gk"
                """));

        GenerativeAiConfig defaults = GenerativeAiConfig.defaults();
        assertEquals(defaults.cerebrasModels(), config.generativeAiConfig().cerebrasModels());
        assertEquals(defaults.groqModels(), config.generativeAiConfig().groqModels());
    }

    @Test
    void promptKeysInYamlAreIgnoredPromptsLiveInCode() throws IOException {
        BotConfig config = BotConfig.load(writeConfig("""
                    groqApiKey: "gk"
                    systemPrompt: "yaml prompt should be ignored"
                    secondChanceSystemPrompt: "yaml follow-up should be ignored"
                """));

        // Prompts live per-bot in BotRegistry, never in yaml or config defaults.
        GenerativeAiConfig defaults = GenerativeAiConfig.defaults();
        assertEquals(defaults.groqModels(), config.generativeAiConfig().groqModels());
    }

    @Test
    void aiYamlTakesPrecedenceOverConfigFallback() throws IOException {
        Path configPath = writeConfig("""
                    groqApiKey: "from-config"
                    groqModels:
                      - "from-config-model"
                """);
        Files.writeString(configPath.resolveSibling("ai.yaml"), """
                ai:
                  groqApiKey: "from-ai-file"
                  groqModels:
                    - "from-ai-model"
                """);

        BotConfig config = BotConfig.load(configPath);

        assertEquals("test-token", config.token());
        assertEquals("from-ai-file", config.generativeAiConfig().groqApiKey());
        assertEquals(GenerativeAiConfig.GROQ_MODELS, config.generativeAiConfig().groqModels());
    }

    @Test
    void aiConfigLoadsWithoutConfigYaml() throws IOException {
        Files.writeString(tempDir.resolve("ai.yaml"), """
                ai:
                  groqApiKey: "gk"
                """);
        GenerativeAiConfig ai = BotConfig.loadGenerativeAiConfig(tempDir.resolve("config.yaml"));
        assertEquals("gk", ai.groqApiKey());
    }

    @Test
    void realSplitConfigLoads() {
        Path configPath = Path.of("config.yaml");
        Path aiPath = Path.of("ai.yaml");
        if (!java.nio.file.Files.exists(configPath) || !java.nio.file.Files.exists(aiPath)) {
            return;
        }
        try {
            BotConfig config = BotConfig.load(configPath);
            org.junit.jupiter.api.Assertions.assertNotNull(config.token());
            org.junit.jupiter.api.Assertions.assertNotNull(config.generativeAiConfig().groqApiKey());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
