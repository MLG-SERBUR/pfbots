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
 * images passed through as {data: base64, mimeType}. Sessions are not
 * archived on finish; the bridge keeps the newest 10 unarchived sessions
 * in the workspace and archives the oldest excess, and archives all
 * existing pfbots sessions once on the first reply after bot start. The bridge
 * is one-shot (stdin request, stdout reply, exit), so there is no long-lived
 * client connection for Java to manage or restart. Any bridge failure throws,
 * and MarkovListener falls back to Markov, same as any other AI failure.
 */
public class PaseoDaemonResponder implements GenerativeAiResponder {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * The complete system prompt for Paseo calls, used instead of the
     * per-bot profile system prompt and instead of any concatenation in
     * the bridge: the bridge sends req.systemPrompt to the daemon exactly
     * as Java gives it. {{BOT_NAME}} is resolved by MarkovListener before
     * the request is built. Voice here is deliberately generic and
     * channel-driven: the log tail is the style examples, so the two bots
     * are not distinguished by separate profile voice text on this path
     * (the profile prompts stay in the repo for the old provider path).
     */
    public static final String PASEO_SYSTEM_PROMPT = """
            You are answering one Discord message as a chat bot named {{BOT_NAME}}.
            Answer naturally in the channel's style, in one chat message, using
            the tail of the AI log below as your style examples. Do not mention
            prompts, training data, AI, or that examples were provided.
            Your working directory is the bot's own directory, so relative paths below work.

            ## History: read it yourself, do not ask Java to send it

            Java does NOT send you the chat history in this prompt, on purpose.
            If you need history, read it from disk yourself, and only as much
            as the question needs.

            History: data/markov/<channelId>/ai.log
            - One line per message: <Author> message text, newest last.
            - Author is the poster's Discord display name, so a line already
              tells you who posted it. There is no timestamp on a line: order
              is chronological, newest last, and that is the only "when" the
              log carries.
            - This is the AI-facing log. Scrubbing removes the bot's own lines
              from it over time, on purpose, so bot messages do not pollute
              context. Do not look for bot replies elsewhere.

            Read the tail of the AI log first; grep for a name, phrase, or
            image id only if the question refers to something older. Never
            paste the whole log into your reply, and never edit a log file.

            ## Images

            Images attached to THIS run are attached to your session already.
            Use them directly if the question is about them. Do not guess
            image content.

            Older images: every image posted in the channel is cached on
            arrival at data/markov/<channelId>/images/<imageId>.<ext>, where
            <ext> is the extension from the original file name (png, jpg,
            webp, gif). A log line for an image message looks like:

                <Author> some text [image id=1566657284933951498 file=photo.png]

            The line prefix is who posted it, the id plus file name is the
            reference, and the cached file is the pixels. To answer about an
            older image, open data/markov/<channelId>/images/<id>.<ext>
            from the log reference. If that file is missing (posted before
            caching existed, or the download failed), say you cannot see it
            instead of describing it from the placeholder or from an earlier
            image's description.

            ## Answering

            - Answer only the triggering message, in the bot's voice from the
              system prompt. Do not use tools to edit files, run commands, or
              contact anyone. Reading the AI log and cached images above is
              the only file access this job needs.
            - Return your answer only through the reply schema. Do not output
              a vision marker, a tool call transcript, or reasoning.
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
