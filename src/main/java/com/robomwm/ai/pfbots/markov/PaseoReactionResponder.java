package com.robomwm.ai.pfbots.markov;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

/**
 * Reaction decider: Paseo primary, ArliAI fallback.
 *
 * <p>Same candidate data as the old Arli-only path (rN = candidate to add,
 * sN = own reaction that may be retracted), so Paseo can only mirror an
 * existing reaction or retract its own, never invent a new emoji.
 * Java resolves the ids back to messageId+emoji and applies them;
 * the agent never talks to Discord, it only returns ids through the
 * bridge's reply schema. Any Paseo failure delegates to the fallback
 * responder (ArliAiReactionResponder), whose bare rN reply MarkovListener
 * still parses as ADD.
 */
public class PaseoReactionResponder implements GenerativeAiResponder {
    public static final String PASEO_REACTION_INSTRUCTIONS = """
            You do NOT send a message or add/remove any reaction yourself. Java applies your choices to Discord.
            Candidates are existing user reactions only. Never invent a new emoji or reaction; choose only listed r ids to add.
            Own reactions are reactions you already added. Choose only listed s ids under REMOVE to retract your own reaction when it no longer fits.
            Return only through the reply schema, one of:
            ADD r1,r2 REMOVE s1
            ADD r1
            REMOVE s1
            NONE
            No other text. Do not load or use any skill.
            """;

    private static final Pattern ID_PATTERN = Pattern.compile("\\b[rs]\\d+\\b", Pattern.CASE_INSENSITIVE);

    private final GenerativeAiResponder paseo;
    private final GenerativeAiResponder fallback;
    private final String systemPrompt;

    public PaseoReactionResponder(GenerativeAiResponder paseo, GenerativeAiResponder fallback, String systemPrompt) {
        if (paseo == null) throw new IllegalArgumentException("paseo responder must not be null");
        this.paseo = paseo;
        this.fallback = fallback;
        this.systemPrompt = systemPrompt;
    }

    public PaseoReactionResponder(List<String> bridgeCommand, String provider, GenerativeAiResponder fallback, String systemPrompt) {
        this(new PaseoDaemonResponder(bridgeCommand, provider, systemPrompt), fallback, systemPrompt);
    }

    public String getSystemPrompt() {
        return systemPrompt;
    }

    @Override
    public CompletableFuture<String> generateReply(GenerativeAiRequest request) {
        String base = request.systemPromptOverride() != null ? request.systemPromptOverride() : systemPrompt;
        String effective = (base == null || base.isBlank() ? "" : base.trim() + "\n\n") + PASEO_REACTION_INSTRUCTIONS;
        GenerativeAiRequest paseoRequest = new GenerativeAiRequest(
                request.recentMessages(), effective, request.images(), request.channelId());
        CompletableFuture<String> paseoFuture;
        try {
            paseoFuture = paseo.generateReply(paseoRequest);
        } catch (Exception e) {
            return fallbackOrFail(request, e);
        }
        if (paseoFuture == null) {
            return fallbackOrFail(request, new IllegalStateException("Paseo reaction responder returned null future"));
        }
        return paseoFuture.handle((reply, error) -> {
            if (error == null && isUsableReactionReply(reply)) {
                return CompletableFuture.completedFuture(reply);
            }
            Throwable failure = error != null ? error : new IllegalStateException("Paseo reaction reply had no usable ids: " + reply);
            return fallbackOrFail(request, failure);
        }).thenCompose(future -> future);
    }

    private CompletableFuture<String> fallbackOrFail(GenerativeAiRequest request, Throwable error) {
        if (fallback == null) return CompletableFuture.failedFuture(error);
        System.out.println("Paseo reaction failed, falling back to ArliAI reactions: " + error);
        try {
            CompletableFuture<String> fallbackFuture = fallback.generateReply(request);
            return fallbackFuture != null ? fallbackFuture : CompletableFuture.failedFuture(error);
        } catch (Exception e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    static boolean isUsableReactionReply(String reply) {
        if (reply == null || reply.isBlank()) return false;
        String trimmed = reply.trim();
        if (trimmed.equalsIgnoreCase("NONE")) return true;
        return ID_PATTERN.matcher(trimmed).find();
    }
}
