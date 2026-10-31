package com.robomwm.ai.pfbots.app;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import com.robomwm.ai.pfbots.markov.GenerativeAiConfig;
import org.yaml.snakeyaml.Yaml;

public record BotConfig(String token, GenerativeAiConfig generativeAiConfig) {

    public static BotConfig load(Path path) throws IOException {
        if (!Files.exists(path)) {
            throw new IllegalStateException("Missing config file: " + path + " (copy config.yaml.example to config.yaml)");
        }

        Map<String, Object> data = loadYamlMap(path);
        if (data == null || data.isEmpty()) {
            throw new IllegalStateException("Config file is empty: " + path);
        }

        String token = readString(data, "discord.token");
        if (token == null || token.isBlank() || "PUT_YOUR_BOT_TOKEN_HERE".equals(token)) {
            throw new IllegalStateException("Please set discord.token in " + path);
        }

        Path aiPath = path.resolveSibling("ai.yaml");
        Map<String, Object> aiData = data;
        if (Files.exists(aiPath)) {
            Map<String, Object> loaded = loadYamlMap(aiPath);
            if (loaded != null && !loaded.isEmpty()) {
                aiData = normalizeAiData(loaded);
            } else {
                aiData = normalizeAiData(Map.of());
            }
        } else {
            aiData = normalizeAiData(data);
        }

        return new BotConfig(token, buildGenerativeAiConfig(aiData));
    }

    /**
     * AI backend config without requiring a bot token. Used when tokens come
     * from dumcord.yml; {@code config.yaml} is then only an ai.yaml fallback.
     */
    public static GenerativeAiConfig loadGenerativeAiConfig(Path path) throws IOException {
        Map<String, Object> data = Map.of();
        if (Files.exists(path)) {
            data = loadYamlMap(path);
            if (data == null) {
                data = Map.of();
            }
        }

        Path aiPath = path.resolveSibling("ai.yaml");
        Map<String, Object> aiData = data;
        if (Files.exists(aiPath)) {
            Map<String, Object> loaded = loadYamlMap(aiPath);
            if (loaded != null && !loaded.isEmpty()) {
                aiData = normalizeAiData(loaded);
            } else {
                aiData = normalizeAiData(Map.of());
            }
        } else {
            aiData = normalizeAiData(data);
        }

        return buildGenerativeAiConfig(aiData);
    }

    private static GenerativeAiConfig buildGenerativeAiConfig(Map<String, Object> aiData) {
        GenerativeAiConfig defaults = GenerativeAiConfig.defaults();
        return new GenerativeAiConfig(
                readString(aiData, "ai.cerebrasApiKey", defaults.cerebrasApiKey()),
                readString(aiData, "ai.groqApiKey", defaults.groqApiKey()),
                readString(aiData, "ai.openrouterApiKey", defaults.openrouterApiKey()),
                readString(aiData, "ai.geminiApiKey", defaults.geminiApiKey()),
                readString(aiData, "ai.mistralApiKey", defaults.mistralApiKey()),
                readString(aiData, "ai.zaiApiKey", defaults.zaiApiKey()),
                readString(aiData, "ai.cloudflareApiKey", defaults.cloudflareApiKey()),
                readString(aiData, "ai.cloudflareAccountId", defaults.cloudflareAccountId()),
                readString(aiData, "ai.ollamaApiKey", defaults.ollamaApiKey()),
                readString(aiData, "ai.sambaNovaApiKey", defaults.sambaNovaApiKey()),
                readString(aiData, "ai.arliApiKey", defaults.arliApiKey()));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> loadYamlMap(Path path) throws IOException {
        Yaml yaml = new Yaml();
        try (InputStream in = Files.newInputStream(path)) {
            Map<String, Object> map = yaml.loadAs(in, Map.class);
            return map == null ? Map.of() : map;
        }
    }

    private static Map<String, Object> normalizeAiData(Map<String, Object> raw) {
        if (raw.containsKey("ai")) {
            return raw;
        }
        return Map.of("ai", raw);
    }

    private static String readString(Map<String, Object> map, String dottedPath, String defaultValue) {
        String value = readString(map, dottedPath);
        return value == null || value.isBlank() ? defaultValue : value;
    }

    @SuppressWarnings("unchecked")
    private static String readString(Map<String, Object> map, String dottedPath) {
        String[] parts = dottedPath.split("\\.");
        Object current = map;

        for (String part : parts) {
            if (!(current instanceof Map<?, ?> currentMap)) {
                return null;
            }
            current = ((Map<String, Object>) currentMap).get(part);
            if (current == null) {
                return null;
            }
        }

        return current instanceof String value ? value : null;
    }
}
