package com.robomwm.ai.pfbots.masteroebot.feedback;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import com.robomwm.ai.pfbots.markov.MarkovManager;

import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.requests.restaction.CommandListUpdateAction;

public class FeedbackCommandListener extends ListenerAdapter {
    // Confirmation lines in early main-channel voice: earnest, "man"
    // everywhere, short fragments. Sampled from the first ~140 lines of
    // data/chat-logs/1349083001275027559.chat.log ("proud of you man",
    // "we're here for you man", "Good one?", "you really are the top 1 pengi").
    static final List<String> REPLIES = List.of(
            "noted man, proud of you ngl",
            "good one? logged it man",
            "heard you man, writing it down",
            "say less, got it man",
            "we're here for you man, saved it",
            "bet man, feedback locked in",
            "ok? logged. appreciate you man",
            "top 1 feedback, saved it ngl",
            "got it man, keep em coming",
            "^^ noted, thanks man"
    );

    private final FeedbackStore store;
    private final MarkovManager markovManager;

    /** Recent chat lines attached to each entry, humans and bot alike. */
    static final int CONTEXT_SIZE = 10;

    public FeedbackCommandListener() {
        this(new FeedbackStore(), null);
    }

    public FeedbackCommandListener(MarkovManager markovManager) {
        this(new FeedbackStore(), markovManager);
    }

    FeedbackCommandListener(FeedbackStore store, MarkovManager markovManager) {
        this.store = store;
        this.markovManager = markovManager;
    }

    public void registerCommands(CommandListUpdateAction updater) {
        // NOTE: Do not call queue() here. BotMain combines all listeners'
        // commands into a single updateCommands() action.
        updater.addCommands(
                Commands.slash("feedback", "Send feedback on the bot")
                        .addOption(OptionType.STRING, "message", "What should change, be added, or be fixed?", true)
        );
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        if (!"feedback".equals(event.getName())) {
            return;
        }

        OptionMapping messageOption = event.getOption("message");
        String message = messageOption == null ? "" : messageOption.getAsString();
        if (message.isBlank()) {
            event.reply("give me something to work with lol, message can't be empty.")
                    .setEphemeral(true).queue();
            return;
        }

        Guild guild = event.getGuild();
        long channelId = event.getChannel().getIdLong();
        String userId = event.getUser().getId();
        String username = event.getUser().getEffectiveName();
        String guildId = guild == null ? null : guild.getId();
        String guildName = guild == null ? null : guild.getName();
        String channelIdStr = event.getChannel().getId();

        // Defer: context comes from a live channel-history fetch (REST) so
        // each line carries its real timestamp. AI log is fallback.
        event.deferReply(true).queue();
        event.getChannel().getHistory().retrievePast(CONTEXT_SIZE).queue(
                messages -> {
                    saveAndConfirm(event, userId, username, guildId, guildName,
                            channelIdStr, message, formatHistory(messages));
                },
                error -> {
                    System.err.println("Feedback history fetch failed: " + error.getMessage());
                    saveAndConfirm(event, userId, username, guildId, guildName,
                            channelIdStr, message, gatherContext(channelId));
                });
    }

    private void saveAndConfirm(SlashCommandInteractionEvent event, String userId, String username,
                                String guildId, String guildName, String channelIdStr,
                                String message, List<String> context) {
        try {
            store.save(userId, username, guildId, guildName, channelIdStr, message, context);
        } catch (Exception e) {
            System.err.println("Failed to save feedback: " + e.getMessage());
            event.getHook().editOriginal("yo that didn't save, try again in a bit.").queue();
            return;
        }

        event.getHook().editOriginal(pickReply()).queue();
    }

    /** Newest-first history to oldest-first "[timestamp] <author> content" lines. */
    static List<String> formatHistory(List<Message> messages) {
        List<String> lines = new ArrayList<>();
        if (messages == null) {
            return lines;
        }
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message msg = messages.get(i);
            String content = msg.getContentDisplay().strip();
            if (content.isEmpty()) {
                continue;
            }
            String author = msg.getMember() != null
                    ? msg.getMember().getEffectiveName()
                    : msg.getAuthor().getEffectiveName();
            if (content.length() > FeedbackStore.MAX_CONTEXT_LINE_LENGTH) {
                content = content.substring(0, FeedbackStore.MAX_CONTEXT_LINE_LENGTH);
            }
            lines.add("[" + msg.getTimeCreated().toInstant() + "] <" + author + "> " + content);
        }
        return lines;
    }

    static String pickReply() {
        return REPLIES.get(ThreadLocalRandom.current().nextInt(REPLIES.size()));
    }

    List<String> gatherContext(long channelId) {
        if (markovManager == null) {
            return Collections.emptyList();
        }
        try {
            // AI log holds "<author> message" lines for humans and
            // "<MasterOEBot> ..." lines for bot replies, so feedback about a
            // recent exchange carries the exchange with it. No timestamps on
            // disk, so mark them as such.
            List<String> lines = new ArrayList<>();
            for (String line : markovManager.getRecentMessagesForAi(channelId, CONTEXT_SIZE)) {
                lines.add("[unknown time] " + line);
            }
            return lines;
        } catch (Exception e) {
            System.err.println("Failed to gather feedback context: " + e.getMessage());
            return Collections.emptyList();
        }
    }
}
