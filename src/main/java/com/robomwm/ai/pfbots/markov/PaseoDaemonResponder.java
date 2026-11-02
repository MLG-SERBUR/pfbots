package com.robomwm.ai.pfbots.markov;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * A GenerativeAiResponder backed by a Paseo daemon over its API, not the
 * `paseo` CLI and not the old round-robin provider HTTP calls.
 *
 * <p>Java does not speak the daemon protocol directly (there is no Java SDK),
 * so each reply runs the small Node bridge in paseo-bridge/, which uses the
 * official @getpaseo/client SDK: one agent per reply, outputSchema {reply},
 * autoArchive, images passed through as {data: base64, mimeType}. The bridge
 * is one-shot (stdin request, stdout reply, exit), so there is no long-lived
 * client connection for Java to manage or restart. Any bridge failure throws,
 * and MarkovListener falls back to Markov, same as any other AI failure.
 */
public class PaseoDaemonResponder implements GenerativeAiResponder {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final List<String> bridgeCommand;
    private final String provider;
    private final String systemPrompt;
    private final String cwd;

    /** @param bridgeCommand e.g. ["node", "paseo-bridge/paseo-bridge.mjs"] */
    public PaseoDaemonResponder(List<String> bridgeCommand, String provider, String systemPrompt) {
        this(bridgeCommand, provider, systemPrompt, System.getProperty("user.dir"));
    }

    public PaseoDaemonResponder(List<String> bridgeCommand, String provider, String systemPrompt, String cwd) {
        if (bridgeCommand == null || bridgeCommand.isEmpty()) {
            throw new IllegalArgumentException("bridgeCommand must not be empty");
        }
        if (provider == null || provider.isBlank()) {
            throw new IllegalArgumentException("provider must not be blank, e.g. opencode/<model>");
        }
        this.bridgeCommand = List.copyOf(bridgeCommand);
        this.provider = provider;
        this.systemPrompt = systemPrompt;
        this.cwd = cwd;
    }

    public String getSystemPrompt() {
        return systemPrompt;
    }

    @Override
    public CompletableFuture<String> generateReply(GenerativeAiRequest request) {
        String system = request.systemPromptOverride() != null ? request.systemPromptOverride() : systemPrompt;
        String prompt = String.join("\n", request.recentMessages());
        ObjectNode json = MAPPER.createObjectNode();
        json.put("provider", provider);
        if (system != null) json.put("systemPrompt", system);
        if (cwd != null) json.put("cwd", cwd);
        // Channel id drives the bridge's read confinement: without it the
        // bridge refuses to create the agent rather than running unconfined.
        if (request.channelId() != null) json.put("channelId", request.channelId());
        json.put("prompt", prompt);
        ArrayNode images = json.putArray("images");
        for (VisionImage image : request.images()) {
            ObjectNode img = images.addObject();
            img.put("data", image.base64());
            img.put("mimeType", image.mimeType());
        }
        String requestJson;
        try {
            requestJson = MAPPER.writeValueAsString(json);
        } catch (IOException e) {
            return CompletableFuture.failedFuture(e);
        }
        return CompletableFuture.supplyAsync(() -> runBridge(requestJson));
    }

    private String runBridge(String requestJson) {
        try {
            Process process = new ProcessBuilder(bridgeCommand).start();
            process.getOutputStream().write(requestJson.getBytes(StandardCharsets.UTF_8));
            process.getOutputStream().close();
            String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            int exit = process.waitFor();
            if (exit != 0) {
                throw new IOException("Paseo bridge exited " + exit + ": " + truncate(stderr, 500));
            }
            JsonNode replyJson = MAPPER.readTree(stdout);
            String reply = replyJson.path("reply").asText("");
            if (reply.isBlank()) {
                throw new IOException("Paseo bridge returned no reply");
            }
            return reply;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new CompletionException(e);
        }
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
