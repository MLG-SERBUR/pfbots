package com.robomwm.ai.pfbots.bot;

/**
 * Per-bot identity and voice. Prompts live in code (not yaml) so each bot
 * keeps its own personality while sharing the same provider backend.
 * Index 0 is always the primary bot (MasterOEBot).
 */
public record BotProfile(
        String key,
        String displayName,
        String botTag,
        String systemPrompt,
        String secondChancePrompt,
        String reactionPrompt,
        boolean registersCommands,
        String paseoSystemPrompt) {
    /** Variable for live Discord display name inside prompts. */
    public static final String BOT_NAME_VARIABLE = "{{BOT_NAME}}";

    public BotProfile {
        if (!botTag.endsWith(" ")) {
            botTag = botTag + " ";
        }
    }

    /** Replace {{BOT_NAME}} with live display name, fallback to profile displayName. */
    public String resolvePrompt(String template, String liveDisplayName) {
        if (template == null) return null;
        String name = (liveDisplayName == null || liveDisplayName.isBlank()) ? displayName() : liveDisplayName;
        return template.replace(BOT_NAME_VARIABLE, name);
    }
}
