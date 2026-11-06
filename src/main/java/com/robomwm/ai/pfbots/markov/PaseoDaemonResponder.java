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
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A GenerativeAiResponder backed by a Paseo daemon over its API, not the
 * `paseo` CLI and not the old round-robin provider HTTP calls.
 *
 * <p>Java does not speak the daemon protocol directly (there is no Java SDK),
 * so each reply runs the small Node bridge in paseo-bridge/, which uses the
 * official @getpaseo/client SDK: one agent per reply, outputSchema {reply},
 * images passed through as {data: base64, mimeType}. Sessions are
 * archived on finish except the newest 5, which stay open, and all
 * existing pfbots sessions are archived once on the first reply after bot start. The bridge
 * is one-shot (stdin request, stdout reply, exit), so there is no long-lived
 * client connection for Java to manage or restart. Any bridge failure throws,
 * and MarkovListener falls back to Markov, same as any other AI failure.
 */
public class PaseoDaemonResponder implements GenerativeAiResponder {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Shared Paseo instructions, appended by each BotProfile's
     * paseoSystemPrompt after that bot's own voice text. Not a complete
     * system prompt on its own: BotMain passes profile.paseoSystemPrompt(),
     * and the bridge sends it exactly as Java gives it, with no
     * concatenation in the bridge.
     */
    public static final String PASEO_INSTRUCTIONS = """
            ## Job
            You do NOT send a message anywhere. Java posts your reply to Discord.
            Return it only through the reply schema. No plan, no sending.
            Do not load or use any skill.

            Working directory is the bot directory, so relative paths work.

            ## History
            Java sends no history. Read it yourself, only as much as needed:
            data/markov/<channelId>/ai.log
            - <Author> message, newest last, no timestamps.
            - Bot lines get scrubbed over time; do not look for them elsewhere.
            Read the tail first; grep for a name, phrase, or image id only for older stuff.
            Never paste the whole log, never edit a log file.

            ## Images
            Attached to this run: use them directly, do not guess.
            Older: data/markov/<channelId>/images/<imageId>.<ext>
            Log line: <Author> some text [image id=1566657284933951498 file=photo.png]
            Open that cached file for an older image. If missing, say you cannot see it.
            Never describe it from the placeholder or an earlier image.

            ## Answering
            Answer only the triggering message, in the bot voice.
            Read the log and cached images only. No edits, commands, or contacting anyone.
            Return only through the reply schema. No vision marker, tool transcript, or reasoning.
            ## Reaction
            Set react to the chosen r id, or "" if none.
            You may only choose a listed r candidate that already exists on an event message. Never invent a new reaction.
            Use free judgment. No candidate is required.
            """;
    // Static, not per-instance: both bots share one JVM, and the startup
    // archive must run exactly once per bot start, not once per bot.
    private static final AtomicBoolean STARTUP_ARCHIVE_SENT = new AtomicBoolean(false);

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

    public record ReplyWithReact(String reply, String react) {
    }

    public CompletableFuture<ReplyWithReact> generateReplyWithReact(GenerativeAiRequest request) {
        return generateReplyInternal(request);
    }

    @Override
    public CompletableFuture<String> generateReply(GenerativeAiRequest request) {
        return generateReplyInternal(request).thenApply(ReplyWithReact::reply);
    }

    private CompletableFuture<ReplyWithReact> generateReplyInternal(GenerativeAiRequest request) {
        String system = request.systemPromptOverride() != null ? request.systemPromptOverride() : systemPrompt;
        String prompt = String.join("\n", request.recentMessages());
        ObjectNode json = MAPPER.createObjectNode();
        json.put("provider", provider);
        if (system != null) json.put("systemPrompt", system);
        if (cwd != null) json.put("cwd", cwd);
        // Channel id drives the bridge's read confinement: without it the
        // bridge refuses to create the agent rather than running unconfined.
        // Sent as a STRING, not a JSON number: Discord snowflakes exceed
        // 2^53, and a JS Number silently rounds them (1349083001275027559
        // becomes 1349083001275027500), which would confine the agent to
        // the wrong channel dir and deny the real one.
        if (request.channelId() != null) json.put("channelId", request.channelId().toString());
        if (STARTUP_ARCHIVE_SENT.compareAndSet(false, true)) json.put("archiveExisting", true);
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

    private ReplyWithReact runBridge(String requestJson) {
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
            String react = replyJson.path("react").asText("");
            return new ReplyWithReact(reply, react == null ? "" : react.trim());
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new CompletionException(e);
        }
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
