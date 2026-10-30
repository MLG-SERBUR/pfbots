package com.robomwm.ai.pfbots.markov;

import com.robomwm.ai.pfbots.bot.BotProfile;
import com.robomwm.ai.pfbots.bot.BotRegistry;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageReference;
import net.dv8tion.jda.api.entities.MessageReaction;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.unions.MessageChannelUnion;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.entities.emoji.RichCustomEmoji;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.events.message.react.MessageReactionAddEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MarkovListener extends ListenerAdapter {
    private final MarkovManager manager;
    private final MarkovConfig config;
    private final GenerativeAiResponder generativeAiResponder;
    private final GenerativeAiResponder reactionResponder;
    private final GenerativeAiResponder secondChanceResponder;
    private final ArliAiCoordinator coordinator;
    private final String botTag;
    private final String botDisplayName;
    private final String botKey;
    private final String reactionPrompt;
    private JDA jda;
    private final Random rand = new Random();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    private final Map<Long, Deque<Long>> recentMessagesByChannel = new HashMap<>();
    private final Map<Long, LinkedHashMap<Long, PendingReactionMessage>> pendingReactionMessagesByChannel = new HashMap<>();
    private final Map<Long, ScheduledFuture<?>> pendingReactionDebounceByChannel = new HashMap<>();
    private final Map<Long, Long> firstInvocationTimeByChannel = new ConcurrentHashMap<>();
    private final Set<Long> channelsNeedingScrub = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final AtomicInteger messageCount = new AtomicInteger(100);
    private static final Pattern MENTION_PATTERN = Pattern.compile("<@!?\\d+>|<@&\\d+>|<#\\d+>");
    private static final Pattern CUSTOM_EMOJI_PATTERN = Pattern.compile("(<a?:([A-Za-z0-9_]{2,32}):(\\d+)>)|(:([A-Za-z0-9_]{2,32}):(\\d+)?)");
    private static final long RESPONSE_DAMPENING_WINDOW_MS = TimeUnit.SECONDS.toMillis(10);
    private static final int RESPONSE_DAMPENING_FREE_MESSAGES = 2;
    private static final double RESPONSE_DAMPENING_STEP = 0.1;
    private static final double MIN_RESPONSE_CHANCE = 0.0;
    public static final long GENERATIVE_AI_TOKEN_BUDGET = 8000;
    private static final long GENERATIVE_AI_TIMEOUT_SECONDS = 45;

    /**
     * Initial history-gather budget for a responder chain: the known input
     * limit of the first provider with one, so reordering the fallback chain
     * needs no code change. Falls back to GENERATIVE_AI_TOKEN_BUDGET for
     * unknown responder types (e.g. placeholders in tests).
     */
    public static long gatherBudgetFor(GenerativeAiResponder responder) {
        if (responder instanceof RoundRobinGenerativeAiResponder rr) return rr.gatherBudget();
        if (responder instanceof ArliAiSecondChanceResponder sc) return sc.gatherBudget();
        if (responder instanceof ArliAiReactionResponder ar) return ar.gatherBudget();
        return GENERATIVE_AI_TOKEN_BUDGET;
    }
    private static final long REACTION_AI_TIMEOUT_SECONDS = 600;
    private static final long REACTION_DEBOUNCE_MIN_SECONDS = 120;
    private static final long REACTION_DEBOUNCE_MAX_SECONDS = 300;
    private static final long REACTION_RATE_LIMIT_DELAY_MS = 1500;
    private static final String REACTION_AI_SYSTEM_PROMPT = """
            You decide whether MasterOEBot should add existing Discord reactions to messages.
            Choose a candidate only when MasterOEBot would independently agree with that exact reaction on that exact message.
            Do not choose reactions merely because other users used them.
            Return only comma-separated candidate ids, or NONE.
            """;

    public MarkovListener(MarkovManager manager, MarkovConfig config, JDA jda) {
        this(manager, config, jda, new PlaceholderGenerativeAiResponder(), new PlaceholderGenerativeAiResponder(), new PlaceholderGenerativeAiResponder(), null);
    }

    public MarkovListener(MarkovManager manager, MarkovConfig config, JDA jda, GenerativeAiResponder generativeAiResponder) {
        this(manager, config, jda, generativeAiResponder, new PlaceholderGenerativeAiResponder(), new PlaceholderGenerativeAiResponder(), null);
    }

    public MarkovListener(MarkovManager manager, MarkovConfig config, JDA jda, GenerativeAiResponder generativeAiResponder, GenerativeAiResponder reactionResponder) {
        this(manager, config, jda, generativeAiResponder, reactionResponder, new PlaceholderGenerativeAiResponder(), null);
    }

    public MarkovListener(MarkovManager manager, MarkovConfig config, JDA jda, GenerativeAiResponder generativeAiResponder, GenerativeAiResponder reactionResponder, GenerativeAiResponder secondChanceResponder, ArliAiCoordinator coordinator) {
        this(manager, config, jda, generativeAiResponder, reactionResponder, secondChanceResponder, coordinator,
                BotRegistry.MasterOEBot.botTag(), BotRegistry.MasterOEBot.reactionPrompt());
    }

    /** Per-bot variant: own log tag and reaction prompt, shared backend. */
    public MarkovListener(MarkovManager manager, MarkovConfig config, JDA jda, GenerativeAiResponder generativeAiResponder, GenerativeAiResponder reactionResponder, GenerativeAiResponder secondChanceResponder, ArliAiCoordinator coordinator, String botTag, String reactionPrompt) {
        this(manager, config, jda, generativeAiResponder, reactionResponder, secondChanceResponder, coordinator,
                botTag, reactionPrompt, BotRegistry.MasterOEBot.key());
    }

    /** Per-bot variant with explicit identity for per-bot toggles (e.g. paraokabot). */
    public MarkovListener(MarkovManager manager, MarkovConfig config, JDA jda, GenerativeAiResponder generativeAiResponder, GenerativeAiResponder reactionResponder, GenerativeAiResponder secondChanceResponder, ArliAiCoordinator coordinator, String botTag, String reactionPrompt, String botKey) {
        this.manager = manager;
        this.config = config;
        this.jda = jda;
        this.botKey = (botKey == null || botKey.isBlank()) ? BotRegistry.MasterOEBot.key() : botKey;
        this.generativeAiResponder = generativeAiResponder;
        this.reactionResponder = reactionResponder;
        this.secondChanceResponder = secondChanceResponder;
        this.coordinator = coordinator;
        String normalizedTag = (botTag == null || botTag.isBlank()) ? BotRegistry.MasterOEBot.botTag() : botTag;
        this.botTag = normalizedTag.endsWith(" ") ? normalizedTag : normalizedTag + " ";
        String stripped = this.botTag.trim();
        this.botDisplayName = (stripped.startsWith("<") && stripped.endsWith(">")
                ? stripped.substring(1, stripped.length() - 1) : stripped);
        this.reactionPrompt = (reactionPrompt == null || reactionPrompt.isBlank())
                ? BotRegistry.MasterOEBot.reactionPrompt() : reactionPrompt;
        this.scheduler.scheduleAtFixedRate(this::checkAndScrubAiLogs, 5, 5, TimeUnit.MINUTES);
        startStartupTimers();
    }

    private void startStartupTimers() {
        long now = System.currentTimeMillis();
        java.util.Set<Long> enabledChannels = new java.util.HashSet<>(config.getEnabledChannelIds());
        enabledChannels.addAll(config.getParaokaEnabledChannelIds());
        for (long channelId : enabledChannels) {
            if (manager.aiLogExists(channelId)) {
                firstInvocationTimeByChannel.putIfAbsent(channelId, now);
                channelsNeedingScrub.add(channelId);
            }
            // Permanent chat log keeps bot lines scrub removes from AI log.
            // Backfill once so pre-existing history is retained for prompt work.
            try {
                manager.backfillChatLogFromAiLog(channelId);
            } catch (Exception e) {
                System.err.println("Failed to backfill chat log for channel " + channelId + ": " + e.getMessage());
            }
        }
    }

    public void setJDA(JDA jda) {
        this.jda = jda;
    }

    /** True when this listener belongs to paraokabot (per-bot toggle applies). */
    private boolean isParaoka() {
        return BotRegistry.PARAOKA.key().equals(botKey);
    }

    /** False when this bot's own per-channel toggle silences this listener. */
    private boolean isBotEnabled(long channelId) {
        if (isParaoka()) {
            return config.isParaokaEnabled(channelId);
        }
        return config.isEnabled(channelId);
    }

    private String sanitizeOutput(String text) {
        if (text == null) return "";
        return MENTION_PATTERN.matcher(text).replaceAll("");
    }

    private String escapeMassMentions(String text) {
        if (text == null) return "";
        return text.replace("@everyone", "@\u200beveryone")
                .replace("@here", "@\u200bhere");
    }

    String resolveGuildEmoji(Guild guild, String text) {
        if (text == null || text.isEmpty() || guild == null) return text != null ? text : "";

        Matcher matcher = CUSTOM_EMOJI_PATTERN.matcher(text);
        StringBuilder resolved = new StringBuilder();
        while (matcher.find()) {
            String name;
            if (matcher.group(1) != null) {
                name = matcher.group(2);
            } else {
                name = matcher.group(5);
            }

            List<RichCustomEmoji> emojis = guild.getEmojisByName(name, false);
            if (emojis.isEmpty()) {
                matcher.appendReplacement(resolved, Matcher.quoteReplacement(matcher.group(0)));
                continue;
            }

            String replacement = emojis.stream()
                    .filter(RichCustomEmoji::isAvailable)
                    .findFirst()
                    .orElse(emojis.get(0))
                    .getAsMention();
            matcher.appendReplacement(resolved, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(resolved);
        return resolved.toString();
    }

    @Override
    public void onMessageReceived(MessageReceivedEvent event) {
        if (!event.isFromGuild()) return;

        long channelId = event.getChannel().getIdLong();

        if (!isBotEnabled(channelId)) return;

        Message message = event.getMessage();

        if (jda != null && message.getAuthor().getIdLong() == jda.getSelfUser().getIdLong()) {
            return;
        }

        manager.loadBrain(channelId);

        String content = MarkovUtils.getDisplayNameContent(message);

        if (content == null || content.trim().isEmpty() || content.trim().startsWith("!")) {
            String author = message.getAuthor() != null ? message.getAuthor().getEffectiveName() : "unknown";
            System.out.println("Ignored message in channel " + channelId + " from " + author + ": empty or starts with ! content='" + content + "'");
            return;
        }

        boolean isBot = message.getAuthor().isBot();

        if (!isBot) {
            manager.train(channelId, content);
            if (!manager.aiLogExists(channelId)) {
                seedAiLogFromHistory(event, channelId);
            }
            manager.appendToBrain(channelId, content);
            String authorName = event.getMember() != null ? event.getMember().getEffectiveName() : event.getAuthor().getEffectiveName();
            manager.appendToAiLog(channelId, authorName, content);
            manager.appendToChatLog(channelId, authorName, content);
            
            if (messageCount.incrementAndGet() >= 100) {
                messageCount.set(0);
                String statusMsg = escapeMassMentions(sanitizeOutput(manager.generateReply(channelId)));
                if (statusMsg != null && !statusMsg.trim().isEmpty()) {
                    if (statusMsg.length() > 128) {
                        statusMsg = statusMsg.substring(0, 128);
                    }
                    if (jda != null) {
                        jda.getPresence().setActivity(Activity.customStatus(statusMsg));
                    }
                }
            }
        }

        String botName = liveBotDisplayName(event.getGuild()).toLowerCase(Locale.ROOT);
        String lowerContent = content.toLowerCase(Locale.ROOT);

        boolean isReplyToSelfSync = isReplyToSelf(message);
        boolean mentionsEveryone = isMassMention(message, content);
        // Exclusive direct address: a message naming/pinging/replying to one
        // bot must not trigger the other bot. Random 0.001 replies below stay
        // independent per bot.
        // NOTE: @everyone/@here counts as direct address for every bot, so a
        // mass ping makes both reply regardless of who else is mentioned.
        boolean selfNamed = containsAny(lowerContent, selfIdentifiers(event.getGuild()));
        boolean otherNamed = containsAny(lowerContent, otherIdentifiers());
        boolean mentionsSelf = isMentioningSelf(message);
        boolean mentionsOtherBot = isMentioningOtherBot(message);
        boolean selfExplicit = selfNamed || mentionsSelf;
        // Explicit naming of another bot wins over reply-to-self: replying to
        // this bot while naming the other bot addresses the other bot.
        boolean selfReply = isReplyToSelfSync && !(otherNamed || mentionsOtherBot);
        boolean directlyAddressed = selfExplicit || selfReply || mentionsEveryone;

        if (directlyAddressed) {
            firstInvocationTimeByChannel.putIfAbsent(channelId, System.currentTimeMillis());
            channelsNeedingScrub.add(channelId);
        }

        // Dampening only tracks direct addresses. Regular messages neither
        // consume budget nor get blocked.
        boolean responseAllowed = true;
        if (directlyAddressed) {
            responseAllowed = shouldRespondAfterDampening(channelId);
        }

        if (responseAllowed) {
            if (directlyAddressed) {
                System.out.println("Triggered reply in channel " + channelId + " for message '" + content + "' from " + message.getAuthor().getEffectiveName() + " isReplyToSelfSync=" + isReplyToSelfSync + " selfNamed=" + selfNamed + " otherNamed=" + otherNamed + " mentionsEveryone=" + mentionsEveryone);
                sendTriggeredReply(event, channelId, content, message.getReferencedMessage());
                return;
            } else if (rand.nextDouble() < 0.001) {
                sendMarkovReplies(event, channelId, content);
                return;
            } else {
                if (message.getMessageReference() != null) {
                    System.out.println("Message in channel " + channelId + " has reference but not directlyAddressed (botName=" + botName + " isReplyToSelfSync=" + isReplyToSelfSync + ") content='" + content + "' will try async resolve");
                }
            }
        } else {
            if (directlyAddressed) {
                System.out.println("Ignored directlyAddressed message in channel " + channelId + " due to dampening: content='" + content + "' from " + message.getAuthor().getEffectiveName() + " isReplyToSelfSync=" + isReplyToSelfSync + " selfNamed=" + selfNamed + " otherNamed=" + otherNamed + " mentionsEveryone=" + mentionsEveryone);
            }
        }

        MessageReference reference = message.getMessageReference();
        final boolean syncDirect = directlyAddressed;
        final boolean syncAllowed = responseAllowed;
        // Captured for async reply-to-self: explicit naming of the other bot
        // suppresses a late-discovered reply trigger too.
        final boolean asyncSuppressedByOther = (otherNamed || mentionsOtherBot) && !selfExplicit;
        if (reference != null && message.getReferencedMessage() == null) {
            System.out.println("Attempting async resolve for message in channel " + channelId + " content='" + content + "' syncDirect=" + syncDirect + " syncAllowed=" + syncAllowed + " isReplyToSelfSync=" + isReplyToSelfSync);
            reference.resolve().queue(referenced -> {
                boolean isSelf = isMessageFromSelf(referenced);
                boolean suppressed = asyncSuppressedByOther;
                // Sync-direct messages already counted. Only newly discovered
                // direct replies consume dampening budget, at resolve time.
                boolean asyncAllowed = true;
                if (isSelf && !syncDirect) {
                    asyncAllowed = !suppressed && shouldRespondAfterDampening(channelId);
                } else if (isSelf) {
                    asyncAllowed = syncAllowed;
                }
                System.out.println("Async resolve result for channel " + channelId + " content='" + content + "' isSelf=" + isSelf + " syncDirect=" + syncDirect + " asyncAllowed=" + asyncAllowed + " suppressedByOther=" + suppressed + " referencedAuthor=" + (referenced != null ? referenced.getAuthor().getEffectiveName() : "null") + " referencedContent='" + (referenced != null ? MarkovUtils.getDisplayNameContent(referenced) : "null") + "'");
                if (asyncAllowed && isSelf && !syncDirect && !suppressed) {
                    System.out.println("Triggered async reply in channel " + channelId + " for content='" + content + "'");
                    sendTriggeredReply(event, channelId, content, referenced);
                } else if (syncDirect) {
                    System.out.println("Ignored async resolve in channel " + channelId + " content='" + content + "' reason=already-handled-sync-direct");
                } else {
                    String reason = suppressed ? "other-bot-addressed" : (!asyncAllowed ? "dampened" : (!isSelf ? "not reply to self" : "unknown"));
                    System.out.println("Ignored async reply in channel " + channelId + " content='" + content + "' reason=" + reason);
                }
            }, error -> {
                System.err.println("Failed to resolve referenced message in channel " + channelId + " for content='" + content + "': " + error);
                error.printStackTrace(System.err);
            });
        } else if (reference != null && isReplyToSelfSync && !responseAllowed) {
            // already logged as dampened
        } else if (directlyAddressed) {
            // already handled
        } else if (reference != null) {
            System.out.println("Message has reference but was not handled: channel " + channelId + " content='" + content + "' isReplyToSelfSync=" + isReplyToSelfSync + " responseAllowed=" + responseAllowed + " referencedMessageCached=" + (message.getReferencedMessage() != null));
        } else {
            if (directlyAddressed) {
                // already handled
            } else {
                System.out.println("Ignored non-addressed message in channel " + channelId + " content='" + content + "'");
            }
        }
    }

    @Override
    public void onMessageReactionAdd(MessageReactionAddEvent event) {
        if (!event.isFromGuild()) return;

        long channelId = event.getChannel().getIdLong();
        if (!isBotEnabled(channelId)) return;
        if (jda != null && event.getUserIdLong() == jda.getSelfUser().getIdLong()) return;

        event.retrieveMessage().queue(message -> rememberPendingReactionMessage(channelId, message, event.getEmoji()), error -> {
        });
    }

    private synchronized boolean shouldRespondAfterDampening(long channelId) {
        long now = System.currentTimeMillis();
        Deque<Long> recentMessages = recentMessagesByChannel.computeIfAbsent(channelId, id -> new ArrayDeque<>());
        while (!recentMessages.isEmpty() && now - recentMessages.peekFirst() >= RESPONSE_DAMPENING_WINDOW_MS) {
            recentMessages.removeFirst();
        }

        recentMessages.addLast(now);
        int dampenedMessages = Math.max(0, recentMessages.size() - RESPONSE_DAMPENING_FREE_MESSAGES);
        double responseChance = Math.max(MIN_RESPONSE_CHANCE, 1.0 - dampenedMessages * RESPONSE_DAMPENING_STEP);
        double roll = rand.nextDouble();
        boolean allowed = roll < responseChance;
        if (!allowed) {
            System.out.println("Dampening blocked response in channel " + channelId + ": size=" + recentMessages.size() + " dampened=" + dampenedMessages + " chance=" + responseChance + " roll=" + roll);
        }
        return allowed;
    }

    private int calculateDelay(String text) {
        if (text == null || text.trim().isEmpty()) return 0;
        return Math.min(10, text.split("\\s+").length / 4);
    }

    private void sendMarkovReplies(MessageReceivedEvent event, long channelId, String content) {
        sendMarkovReply(event, channelId, content, true, true);
    }

    private void sendTriggeredReply(MessageReceivedEvent event, long channelId, String content, Message referencedMessage) {
        sendGenerativeAiReplyWithFallback(event, channelId, content, referencedMessage);
    }

    private void sendImmediateSeededMarkovReply(MessageReceivedEvent event, long channelId, String content) {
        sendMarkovReply(event, channelId, content, false, false);
    }

    private void sendMarkovReply(MessageReceivedEvent event, long channelId, String content,
                                 boolean useDelay, boolean allowSecondReply) {
        String reply = escapeMassMentions(resolveGuildEmoji(event.getGuild(), sanitizeOutput(generateReplyWithSeed(channelId, content, 0.2))));
        if (!reply.isEmpty()) {
            int delaySeconds = useDelay ? calculateDelay(reply) : 0;
            final List<ScheduledFuture<?>> typingTasks = (delaySeconds > 0) ? startTyping(event, delaySeconds) : null;
            
            Runnable sendReply = () -> {
                event.getChannel().sendMessage(reply)
                        .setAllowedMentions(Collections.emptyList())
                        .queue(success -> {
                            stopTyping(typingTasks);
                            manager.appendBotMessageToChatLog(channelId, reply, botTag);
                            channelsNeedingScrub.add(channelId);
                            if (allowSecondReply) {
                                scheduleSecondReply(event, channelId, content);
                            }
                        }, error -> stopTyping(typingTasks));
            };

            if (delaySeconds == 0) {
                sendReply.run();
            } else {
                scheduler.schedule(sendReply, delaySeconds, TimeUnit.SECONDS);
            }
        }
    }

    private void sendGenerativeAiReplyWithFallback(MessageReceivedEvent event, long channelId, String content, Message referencedMessage) {
        List<ScheduledFuture<?>> typingTasks = startTyping(event, (int) GENERATIVE_AI_TIMEOUT_SECONDS);

        String systemPrompt = null;
        if (generativeAiResponder instanceof RoundRobinGenerativeAiResponder rr) {
            systemPrompt = resolvePrompt(rr.getSystemPrompt(), event.getGuild());
        }
        List<String> recentMessages = manager.getRecentMessagesForAiUntilTokenBudget(channelId, gatherBudgetFor(generativeAiResponder), systemPrompt);

        if (referencedMessage != null && !recentMessages.isEmpty()) {
            String referencedContent = MarkovUtils.getDisplayNameContent(referencedMessage);
            String formattedReferenced = botTag + referencedContent;

            boolean isReferencingLastMessage = recentMessages.size() >= 2
                    && recentMessages.get(recentMessages.size() - 2).equals(formattedReferenced);

            if (!isReferencingLastMessage) {
                String currentMessageLine = recentMessages.get(recentMessages.size() - 1);
                String context = "(replying to " + liveBotDisplayName(event.getGuild()) + ": \"" + referencedContent + "\") ";

                int tagEnd = currentMessageLine.indexOf("> ");
                if (tagEnd != -1) {
                    String updated = currentMessageLine.substring(0, tagEnd + 2) + context + currentMessageLine.substring(tagEnd + 2);
                    recentMessages.set(recentMessages.size() - 1, updated);
                } else {
                    recentMessages.set(recentMessages.size() - 1, context + currentMessageLine);
                }
            }
        }

        GenerativeAiRequest request = new GenerativeAiRequest(recentMessages, systemPrompt);

        CompletableFuture<String> replyFuture;
        try {
            replyFuture = generativeAiResponder.generateReply(request);
        } catch (Exception e) {
            stopTyping(typingTasks);
            logGenerativeAiFailure(channelId, e);
            sendImmediateSeededMarkovReply(event, channelId, content);
            return;
        }

        if (replyFuture == null) {
            stopTyping(typingTasks);
            logGenerativeAiFailure(channelId, new IllegalStateException("Generative AI responder returned null future"));
            sendImmediateSeededMarkovReply(event, channelId, content);
            return;
        }

        replyFuture.orTimeout(GENERATIVE_AI_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .whenComplete((reply, error) -> {
                    stopTyping(typingTasks);
                    if (error != null) {
                        logGenerativeAiFailure(channelId, error);
                        sendImmediateSeededMarkovReply(event, channelId, content);
                        return;
                    }



                    String safeReply = escapeMassMentions(resolveGuildEmoji(event.getGuild(), sanitizeOutput(MarkovManager.stripBotPrefix(reply))));
                    if (safeReply.trim().isEmpty()) {
                        logGenerativeAiFailure(channelId, new IllegalStateException("Generative AI responder returned empty reply"));
                        sendImmediateSeededMarkovReply(event, channelId, content);
                        return;
                    }

                    event.getChannel().sendMessage(safeReply)
                            .setAllowedMentions(Collections.emptyList())
                            .queue(success -> {
                                trackAiMessage(channelId, safeReply);
                                scheduleSecondReply(event, channelId, content);
                            });
                });
    }

    private final Set<String> seenReactionCandidates = ConcurrentHashMap.newKeySet();

    private void rememberPendingReactionMessage(long channelId, Message message, Emoji newEmoji) {
        if (message == null || message.getAuthor() == null || newEmoji == null) return;

        String content = message.getContentDisplay();
        if (content == null || content.trim().isEmpty() || content.trim().startsWith("!")) return;

        String reactionCode;
        String display;
        try {
            reactionCode = newEmoji.getAsReactionCode();
            display = newEmoji.getFormatted();
        } catch (Exception e) {
            return;
        }
        if (reactionCode == null || reactionCode.isEmpty()) return;

        // Skip emojis the bot already added; nothing to mirror for those.
        try {
            for (MessageReaction reaction : message.getReactions()) {
                if (reaction.isSelf() && reactionCode.equals(reaction.getEmoji().getAsReactionCode())) {
                    return;
                }
            }
        } catch (Exception ignored) {
        }

        // Only new emoji reactions are queued; already-considered ones are skipped.
        if (!seenReactionCandidates.add(message.getIdLong() + ":" + reactionCode)) return;

        synchronized (pendingReactionMessagesByChannel) {
            LinkedHashMap<Long, PendingReactionMessage> pendingMessages =
                    pendingReactionMessagesByChannel.computeIfAbsent(channelId, id -> new LinkedHashMap<>());
            PendingReactionMessage pendingMessage = pendingMessages.computeIfAbsent(
                    message.getIdLong(),
                    id -> new PendingReactionMessage(message.getChannel(), message.getIdLong(), content));
            pendingMessage.reactions.putIfAbsent(
                    reactionCode,
                    new PendingReaction(display, newEmoji));
        }
        scheduleDebouncedReactionEvaluation(channelId);
    }

    private void scheduleDebouncedReactionEvaluation(long channelId) {
        synchronized (pendingReactionDebounceByChannel) {
            ScheduledFuture<?> existing = pendingReactionDebounceByChannel.get(channelId);
            if (existing != null && !existing.isDone()) {
                existing.cancel(false);
                System.out.println("Cancelled previous reaction debounce for channel " + channelId + " (lull reset)");
            }
            long delaySeconds = REACTION_DEBOUNCE_MIN_SECONDS + rand.nextInt((int) (REACTION_DEBOUNCE_MAX_SECONDS - REACTION_DEBOUNCE_MIN_SECONDS + 1));
            System.out.println("Scheduling debounced reaction evaluation for channel " + channelId + " in " + delaySeconds + "s (waiting for lull)");
            ScheduledFuture<?> future = scheduler.schedule(() -> {
                synchronized (pendingReactionDebounceByChannel) {
                    pendingReactionDebounceByChannel.remove(channelId);
                }
                evaluatePendingReactions(channelId);
            }, delaySeconds, TimeUnit.SECONDS);
            pendingReactionDebounceByChannel.put(channelId, future);
        }
    }

    private void evaluatePendingReactions(long channelId) {
        // If second chance is currently awaiting ArliAI, wait and retry after it completes
        // Keep pending messages queued (do not drain yet) and retry promptly after second chance
        if (coordinator != null && coordinator.isSecondChanceActive()) {
            System.out.println("Reaction evaluation for channel " + channelId + " waiting for second chance to complete before calling ArliAI");
            coordinator.awaitSecondChance().thenRun(() -> {
                // Retry evaluation after second chance (immediate, not debounced)
                scheduler.schedule(() -> evaluatePendingReactions(channelId), 100, TimeUnit.MILLISECONDS);
            });
            return;
        }
        List<PendingReactionMessage> pendingMessages = drainPendingReactionMessages(channelId);
        if (pendingMessages.isEmpty()) return;
        // No limit: consider all pending; sort newest-first (higher snowflake = newer)
        pendingMessages.sort((a, b) -> Long.compare(b.messageId, a.messageId));

        Map<String, PendingReaction> candidatesById = new LinkedHashMap<>();
        List<String> promptLines = new ArrayList<>();
        promptLines.add("Candidates:");

        int candidateNumber = 1;
        for (PendingReactionMessage message : pendingMessages) {
            for (PendingReaction reaction : message.reactions.values()) {
                String candidateId = "r" + candidateNumber++;
                candidatesById.put(candidateId, reaction.withMessage(message.channel, message.messageId));
                promptLines.add(candidateId
                        + " messageId=" + message.messageId
                        + " reaction=" + reaction.display
                        + " message=\"" + sanitizeReactionPromptText(message.content) + "\"");
            }
        }

        if (candidatesById.isEmpty()) return;

        GenerativeAiRequest request = new GenerativeAiRequest(promptLines, resolvePrompt(reactionPrompt, guildForChannel(channelId)));
        GenerativeAiResponder responderToUse = reactionResponder != null ? reactionResponder : generativeAiResponder;
        CompletableFuture<String> reactionFuture;
        try {
            reactionFuture = responderToUse.generateReply(request);
        } catch (Exception e) {
            logGenerativeAiFailure(channelId, e);
            return;
        }

        if (reactionFuture == null) {
            logGenerativeAiFailure(channelId, new IllegalStateException("Generative AI responder returned null reaction future"));
            return;
        }

        long reactionTimeout = (responderToUse instanceof ArliAiReactionResponder) ? ArliAiReactionResponder.getTimeoutSeconds() : REACTION_AI_TIMEOUT_SECONDS;
        // Keep copy for retry
        List<PendingReactionMessage> pendingForRetry = new ArrayList<>(pendingMessages);
        reactionFuture.orTimeout(reactionTimeout, TimeUnit.SECONDS)
                .whenComplete((reply, error) -> {
                    if (error != null) {
                        logGenerativeAiFailure(channelId, error);
                        Throwable unwrapped = unwrapCompletionException(error);
                        String errMsg = unwrapped.getMessage() != null ? unwrapped.getMessage() : unwrapped.toString();
                        // retry later except for context exceeded
                        if (TokenCalibrationManager.isContextLengthError(errMsg)) {
                            System.out.println("Not retrying reaction for channel " + channelId + " due to context exceeded error");
                            return;
                        }
                        System.out.println("Scheduling retry for reaction in channel " + channelId + " after failure: " + errMsg);
                        requeuePendingReactionMessages(channelId, pendingForRetry);
                        long retryDelaySeconds = 60 + rand.nextInt(60);
                        scheduler.schedule(() -> evaluatePendingReactions(channelId), retryDelaySeconds, TimeUnit.SECONDS);
                        return;
                    }

                    List<PendingReaction> agreed = new ArrayList<>();
                    for (String candidateId : parseReactionCandidateIds(reply)) {
                        PendingReaction reaction = candidatesById.get(candidateId);
                        if (reaction == null) continue;
                        agreed.add(reaction);
                    }
                    if (agreed.isEmpty()) return;
                    // Newest-first for rate-limit queue
                    agreed.sort((a, b) -> Long.compare(b.messageId(), a.messageId()));
                    System.out.println("Queueing " + agreed.size() + " agreed reactions for channel " + channelId + " newest-first with " + REACTION_RATE_LIMIT_DELAY_MS + "ms spacing");
                    for (int i = 0; i < agreed.size(); i++) {
                        PendingReaction reaction = agreed.get(i);
                        long delayMs = i * REACTION_RATE_LIMIT_DELAY_MS;
                        scheduler.schedule(() -> addAgreedReaction(reaction), delayMs, TimeUnit.MILLISECONDS);
                    }
                });
    }

    private void addAgreedReaction(PendingReaction reaction) {
        reaction.channel
                .retrieveMessageById(reaction.messageId)
                .queue(message -> {
                    MessageReaction existingReaction = message.getReaction(reaction.emoji);
                    if (existingReaction != null && !existingReaction.isSelf()) {
                        message.addReaction(reaction.emoji).queue();
                    }
                }, error -> {
                });
    }

    private List<PendingReactionMessage> drainPendingReactionMessages(long channelId) {
        synchronized (pendingReactionMessagesByChannel) {
            LinkedHashMap<Long, PendingReactionMessage> pendingMessages =
                    pendingReactionMessagesByChannel.remove(channelId);
            if (pendingMessages == null || pendingMessages.isEmpty()) {
                return List.of();
            }
            return new ArrayList<>(pendingMessages.values());
        }
    }

    private void requeuePendingReactionMessages(long channelId, List<PendingReactionMessage> toRequeue) {
        if (toRequeue == null || toRequeue.isEmpty()) return;
        synchronized (pendingReactionMessagesByChannel) {
            LinkedHashMap<Long, PendingReactionMessage> pending =
                    pendingReactionMessagesByChannel.computeIfAbsent(channelId, id -> new LinkedHashMap<>());
            for (PendingReactionMessage msg : toRequeue) {
                PendingReactionMessage existing = pending.computeIfAbsent(msg.messageId,
                        id -> new PendingReactionMessage(msg.channel, msg.messageId, msg.content));
                for (Map.Entry<String, PendingReaction> e : msg.reactions.entrySet()) {
                    existing.reactions.putIfAbsent(e.getKey(), e.getValue());
                }
            }
        }
    }

    private String sanitizeReactionPromptText(String text) {
        return text.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", " ")
                .replace("\n", " ");
    }

    private Set<String> parseReactionCandidateIds(String reply) {
        if (reply == null) return Set.of();

        Set<String> ids = new LinkedHashSet<>();
        Matcher matcher = Pattern.compile("\\br\\d+\\b", Pattern.CASE_INSENSITIVE).matcher(reply);
        while (matcher.find()) {
            ids.add(matcher.group().toLowerCase(Locale.ROOT));
        }
        return ids;
    }

    private void logGenerativeAiFailure(long channelId, Throwable error) {
        Throwable unwrapped = unwrapCompletionException(error);
        System.err.println("Generative AI reply failed for channel " + channelId + ": " + unwrapped);
        unwrapped.printStackTrace(System.err);
    }



    private Throwable unwrapCompletionException(Throwable error) {
        if ((error instanceof CompletionException || error instanceof java.util.concurrent.ExecutionException)
                && error.getCause() != null) {
            return error.getCause();
        }
        return error;
    }

    private void scheduleSecondReply(MessageReceivedEvent event, long channelId, String content) {
        if (rand.nextDouble() >= 0.1) {
            return;
        }
        // Try ArliAI second chance path if available (not placeholder)
        boolean hasSecondChance = secondChanceResponder != null && !(secondChanceResponder instanceof PlaceholderGenerativeAiResponder);
        if (hasSecondChance) {
            // Only start if reaction service isn't currently awaiting ArliAI
            if (coordinator != null && coordinator.isReactionActive()) {
                System.out.println("Skipping second chance reply in channel " + channelId + " - reaction service is active (awaiting ArliAI)");
                return;
            }
            // Determine system prompt for token budgeting (follow-up prompt for second chance)
            String systemPrompt = null;
            if (secondChanceResponder instanceof ArliAiSecondChanceResponder sc) {
                systemPrompt = resolvePrompt(sc.getSystemPrompt(), event.getGuild());
            } else if (generativeAiResponder instanceof RoundRobinGenerativeAiResponder rr) {
                systemPrompt = resolvePrompt(rr.getSystemPrompt(), event.getGuild());
            } else if (secondChanceResponder instanceof ArliAiReactionResponder ar) {
                systemPrompt = resolvePrompt(ar.getSystemPrompt(), event.getGuild());
            }
            // Include as part of logs the first AI's response: fetch latest AI log at invocation time
            List<String> recentMessages = manager.getRecentMessagesForAiUntilTokenBudget(channelId, gatherBudgetFor(secondChanceResponder), systemPrompt);
            String latest = recentMessages.isEmpty() ? "none" : recentMessages.get(recentMessages.size() - 1);
            System.out.println("Second chance ArliAI request for channel " + channelId + " with " + recentMessages.size() + " messages, latest at invocation: " + latest.substring(0, Math.min(500, latest.length())).replace("\n", " "));
            GenerativeAiRequest request = new GenerativeAiRequest(recentMessages, systemPrompt);
            int timeoutSeconds = (secondChanceResponder instanceof ArliAiSecondChanceResponder) ? ArliAiSecondChanceResponder.getTimeoutSeconds() : (int) GENERATIVE_AI_TIMEOUT_SECONDS;
            // For second chance we use 600s timeout; start typing for that duration
            List<ScheduledFuture<?>> typingTasks = startTyping(event, timeoutSeconds);
            CompletableFuture<String> future;
            try {
                future = secondChanceResponder.generateReply(request);
            } catch (Exception e) {
                stopTyping(typingTasks);
                System.err.println("Second chance generateReply threw for channel " + channelId + ": " + e);
                e.printStackTrace(System.err);
                return;
            }
            if (future == null) {
                stopTyping(typingTasks);
                System.err.println("Second chance responder returned null future for channel " + channelId);
                return;
            }
            future.orTimeout(timeoutSeconds, TimeUnit.SECONDS).whenComplete((reply, error) -> {
                stopTyping(typingTasks);
                if (error != null) {
                    Throwable unwrapped = unwrapCompletionException(error);
                    // If skipped due to reaction active, already logged
                    if (unwrapped.getMessage() != null && unwrapped.getMessage().contains("Reaction service is currently awaiting")) {
                        System.out.println("Second chance skipped in channel " + channelId + ": " + unwrapped.getMessage());
                        return;
                    }
                    System.err.println("Second chance ArliAI failed for channel " + channelId + ": " + unwrapped);
                    unwrapped.printStackTrace(System.err);
                    // 10% chance Markov fallback on ArliAI failure
                    if (rand.nextDouble() < 0.1) {
                        System.out.println("Second chance ArliAI failed, attempting 10% Markov fallback for channel " + channelId);
                        String fallbackText = generateReplyWithSeed(channelId, content, 0.5);
                        String fallbackReply = escapeMassMentions(resolveGuildEmoji(event.getGuild(), sanitizeOutput(fallbackText)));
                        if (!fallbackReply.isEmpty()) {
                            int delaySeconds = calculateDelay(fallbackReply) + 2 + rand.nextInt(5);
                            List<ScheduledFuture<?>> fallbackTyping = startTyping(event, delaySeconds);
                            scheduler.schedule(() -> {
                                event.getChannel().sendMessage(fallbackReply)
                                        .setAllowedMentions(Collections.emptyList())
                                        .queue(success -> {
                                            stopTyping(fallbackTyping);
                                            manager.appendBotMessageToChatLog(channelId, fallbackReply, botTag);
                                            channelsNeedingScrub.add(channelId);
                                        }, err -> stopTyping(fallbackTyping));
                            }, delaySeconds, TimeUnit.SECONDS);
                        }
                    }
                    return;
                }
                String safeReply = escapeMassMentions(resolveGuildEmoji(event.getGuild(), sanitizeOutput(MarkovManager.stripBotPrefix(reply))));
                if (safeReply.trim().isEmpty()) {
                    System.err.println("Second chance ArliAI returned empty reply for channel " + channelId);
                    return;
                }
                int delaySeconds = calculateDelay(safeReply);
                Runnable send = () -> event.getChannel().sendMessage(safeReply)
                        .setAllowedMentions(Collections.emptyList())
                        .queue(success -> {
                            trackAiMessage(channelId, safeReply);
                        }, err -> {
                            System.err.println("Failed to send second chance reply in channel " + channelId + ": " + err);
                        });
                if (delaySeconds > 0) {
                    scheduler.schedule(send, delaySeconds, TimeUnit.SECONDS);
                } else {
                    send.run();
                }
            });
            return;
        }
        // Legacy Markov fallback (used in tests or when no second chance responder)
        String replyText = generateReplyWithSeed(channelId, content, 0.5);
        String secondReply = escapeMassMentions(resolveGuildEmoji(event.getGuild(), sanitizeOutput(replyText)));
        if (!secondReply.isEmpty()) {
            int delaySeconds = calculateDelay(secondReply) + 2 + rand.nextInt(5);
            final List<ScheduledFuture<?>> typingTasks = startTyping(event, delaySeconds);
            scheduler.schedule(() -> {
                event.getChannel().sendMessage(secondReply)
                        .setAllowedMentions(Collections.emptyList())
                        .queue(success -> {
                            stopTyping(typingTasks);
                            manager.appendBotMessageToChatLog(channelId, secondReply, botTag);
                            channelsNeedingScrub.add(channelId);
                        }, error -> stopTyping(typingTasks));
            }, delaySeconds, TimeUnit.SECONDS);
        }
    }

    private void trackAiMessage(long channelId, String message) {
        manager.appendBotMessageToAiLog(channelId, message, botTag);
        manager.appendBotMessageToChatLog(channelId, message, botTag);
        channelsNeedingScrub.add(channelId);
    }

    private void checkAndScrubAiLogs() {
        long now = System.currentTimeMillis();
        long hourMs = TimeUnit.HOURS.toMillis(1);
        Iterator<Long> it = channelsNeedingScrub.iterator();
        while (it.hasNext()) {
            Long channelId = it.next();
            Long firstInvocation = firstInvocationTimeByChannel.get(channelId);
            if (firstInvocation != null && (now - firstInvocation > hourMs)) {
                manager.scrubAiLog(channelId);
                firstInvocationTimeByChannel.remove(channelId);
                it.remove();
            }
        }
    }

    private void seedAiLogFromHistory(MessageReceivedEvent event, long channelId) {
        System.out.println("Initializing AI log for channel " + channelId + " from history...");
        manager.ensureAiLogInitialized(channelId);
        manager.ensureChatLogInitialized(channelId);
        event.getChannel().getHistory().retrievePast(100).queue(messages -> {
            for (int i = messages.size() - 1; i >= 0; i--) {
                Message msg = messages.get(i);
                String content = MarkovUtils.getDisplayNameContent(msg).trim();
                if (!content.isEmpty() && !ProfanityFilter.containsProfanity(content)) {
                    if (msg.getAuthor().getIdLong() == jda.getSelfUser().getIdLong()) {
                        manager.appendBotMessageToAiLog(channelId, content, botTag);
                        manager.appendBotMessageToChatLog(channelId, content, botTag);
                        channelsNeedingScrub.add(channelId);
                    } else {
                        String authorName = msg.getMember() != null ? msg.getMember().getEffectiveName() : msg.getAuthor().getEffectiveName();
                        manager.appendToAiLog(channelId, authorName, content);
                        manager.appendToChatLog(channelId, authorName, content);
                    }
                }
            }
            System.out.println("AI log for channel " + channelId + " seeded with " + messages.size() + " messages.");
        }, error -> {
            System.err.println("Failed to seed AI log from history for channel " + channelId + ": " + error.getMessage());
        });
    }

    private List<ScheduledFuture<?>> startTyping(MessageReceivedEvent event, int delaySeconds) {
        List<ScheduledFuture<?>> futures = new ArrayList<>();
        event.getChannel().sendTyping().queue();
        for (int d = 8; d < delaySeconds; d += 8) {
            futures.add(scheduler.schedule(() -> event.getChannel().sendTyping().queue(), d, TimeUnit.SECONDS));
        }
        return futures;
    }

    private void stopTyping(List<ScheduledFuture<?>> futures) {
        if (futures != null) {
            for (ScheduledFuture<?> future : futures) {
                future.cancel(false);
            }
        }
    }

    private boolean isReplyToSelf(Message message) {
        return isMessageFromSelf(message.getReferencedMessage());
    }

    private boolean isMessageFromSelf(Message message) {
        return message != null
                && jda != null
                && jda.getSelfUser() != null
                && message.getAuthor().getIdLong() == jda.getSelfUser().getIdLong();
    }

    private long selfId() {
        try {
            if (jda != null && jda.getSelfUser() != null) return jda.getSelfUser().getIdLong();
        } catch (Exception ignored) {
        }
        return -1L;
    }

    /** True when message pings this bot's own Discord user. */
    private boolean isMentioningSelf(Message message) {
        long self = selfId();
        if (message == null || self == -1L) return false;
        try {
            return message.getMentions().getUsers().stream().anyMatch(u -> u.getIdLong() == self);
        } catch (Exception ignored) {
            return false;
        }
    }

    /** True when message pings any other bot account (e.g. the sibling bot). */
    private boolean isMentioningOtherBot(Message message) {
        long self = selfId();
        if (message == null) return false;
        try {
            return message.getMentions().getUsers().stream()
                    .anyMatch(u -> u.getIdLong() != self && u.isBot());
        } catch (Exception ignored) {
            return false;
        }
    }

    private static void addIdentifiers(Set<String> out, BotProfile profile) {
        if (profile == null) return;
        addLower(out, profile.key());
        addLower(out, profile.displayName());
        String tag = profile.botTag() != null ? profile.botTag().trim() : null;
        addLower(out, tag);
        if (tag != null && tag.startsWith("<") && tag.endsWith(">") && tag.length() > 2) {
            addLower(out, tag.substring(1, tag.length() - 1));
        }
    }

    private static void addLower(Set<String> out, String value) {
        if (value == null) return;
        String v = value.trim().toLowerCase(Locale.ROOT);
        if (!v.isEmpty()) out.add(v);
    }

    private Guild guildForChannel(long channelId) {
        try {
            if (jda != null) {
                var channel = jda.getGuildChannelById(channelId);
                if (channel != null) return channel.getGuild();
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /** Live Discord display name: guild nickname first, else global display, else username. */
    String liveBotDisplayName(Guild guild) {
        try {
            if (guild != null && jda != null) {
                var selfMember = guild.getSelfMember();
                if (selfMember != null && selfMember.getEffectiveName() != null
                        && !selfMember.getEffectiveName().isBlank()) {
                    return selfMember.getEffectiveName();
                }
            }
        } catch (Exception ignored) {
        }
        try {
            if (jda != null && jda.getSelfUser() != null) {
                String effective = jda.getSelfUser().getEffectiveName();
                if (effective != null && !effective.isBlank()) return effective;
                String global = jda.getSelfUser().getGlobalName();
                if (global != null && !global.isBlank()) return global;
                String name = jda.getSelfUser().getName();
                if (name != null && !name.isBlank()) return name;
            }
        } catch (Exception ignored) {
        }
        BotProfile profile = currentProfile();
        if (profile != null && profile.displayName() != null && !profile.displayName().isBlank()) {
            return profile.displayName();
        }
        return botDisplayName != null ? botDisplayName : "masteroebot";
    }

    /** All live name forms for mention filtering: username, global display, nickname. */
    private Set<String> liveBotNameForms(Guild guild) {
        Set<String> out = new HashSet<>();
        try {
            if (jda != null && jda.getSelfUser() != null) {
                var self = jda.getSelfUser();
                addLower(out, self.getName());
                addLower(out, self.getGlobalName());
                addLower(out, self.getEffectiveName());
            }
        } catch (Exception ignored) {
        }
        try {
            if (guild != null) {
                var selfMember = guild.getSelfMember();
                if (selfMember != null) {
                    addLower(out, selfMember.getNickname());
                    addLower(out, selfMember.getEffectiveName());
                }
            }
        } catch (Exception ignored) {
        }
        addLower(out, liveBotDisplayName(guild));
        return out;
    }

    private BotProfile currentProfile() {
        for (BotProfile profile : BotRegistry.PROFILES) {
            if (profile != null && profile.key() != null && profile.key().equals(botKey)) {
                return profile;
            }
        }
        return null;
    }

    /** Resolve {{BOT_NAME}} prompt variable with live display name. */
    String resolvePrompt(String template, Guild guild) {
        BotProfile profile = currentProfile();
        String fallback = profile != null ? profile.displayName() : botDisplayName;
        String live = liveBotDisplayName(guild);
        if (template == null) return null;
        if (profile != null) return profile.resolvePrompt(template, live);
        return template.replace(BotProfile.BOT_NAME_VARIABLE, live != null ? live : fallback);
    }

    /** Name forms addressing this listener (profile names plus live JDA names). */
    private Set<String> selfIdentifiers(Guild guild) {
        Set<String> out = new HashSet<>();
        for (BotProfile profile : BotRegistry.PROFILES) {
            if (profile != null && profile.key() != null && profile.key().equals(botKey)) {
                addIdentifiers(out, profile);
            }
        }
        out.addAll(liveBotNameForms(guild));
        try {
            if (botDisplayName != null) addLower(out, botDisplayName);
        } catch (Exception ignored) {
        }
        return out;
    }

    /** Name forms addressing any bot except this listener. */
    private Set<String> otherIdentifiers() {
        Set<String> out = new HashSet<>();
        for (BotProfile profile : BotRegistry.PROFILES) {
            if (profile == null || (profile.key() != null && profile.key().equals(botKey))) continue;
            addIdentifiers(out, profile);
        }
        return out;
    }

    private static boolean containsAny(String lowerContent, Set<String> names) {
        if (lowerContent == null || lowerContent.isEmpty() || names == null || names.isEmpty()) return false;
        for (String name : names) {
            if (!name.isEmpty() && lowerContent.contains(name)) return true;
        }
        return false;
    }

    private boolean isMassMention(Message message, String resolvedContent) {
        if (message == null) return false;
        try {
            if (message.getMentions() != null && message.getMentions().mentionsEveryone()) {
                return true;
            }
        } catch (Exception ignored) {
        }
        try {
            String raw = message.getContentRaw();
            if (raw != null) {
                String lowerRaw = raw.toLowerCase(Locale.ROOT);
                if (lowerRaw.contains("@everyone") || lowerRaw.contains("@here")) {
                    return true;
                }
            }
        } catch (Exception ignored) {
        }
        if (resolvedContent != null) {
            String lowerResolved = resolvedContent.toLowerCase(Locale.ROOT);
            if (lowerResolved.contains("@everyone") || lowerResolved.contains("@here")) {
                return true;
            }
        }
        return false;
    }

    private String generateReplyWithSeed(long channelId, String originalMessage, double seedChance) {
        if (countWords(originalMessage) <= 2) {
            return manager.generateReply(channelId);
        }

        if (rand.nextDouble() < seedChance) {
            String[] words = originalMessage.split("\\s+");
            String[] validWords = Arrays.stream(words)
                    .filter(w -> w.length() > 2)
                    .toArray(String[]::new);
            if (validWords.length > 0) {
                String seedWord = validWords[rand.nextInt(validWords.length)];
                return manager.generateReply(channelId, seedWord);
            }
        }
        return manager.generateReply(channelId);
    }

    static int countWords(String text) {
        if (text == null || text.trim().isEmpty()) {
            return 0;
        }
        return text.trim().split("\\s+").length;
    }




    public void shutdown() {
        synchronized (pendingReactionDebounceByChannel) {
            for (ScheduledFuture<?> f : pendingReactionDebounceByChannel.values()) {
                f.cancel(false);
            }
            pendingReactionDebounceByChannel.clear();
        }
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
        }
    }

    private static final class PendingReactionMessage {
        private final MessageChannelUnion channel;
        private final long messageId;
        private final String content;
        private final LinkedHashMap<String, PendingReaction> reactions = new LinkedHashMap<>();

        private PendingReactionMessage(MessageChannelUnion channel, long messageId, String content) {
            this.channel = channel;
            this.messageId = messageId;
            this.content = content;
        }
    }

    private record PendingReaction(MessageChannelUnion channel, long messageId, String display, Emoji emoji) {
        private PendingReaction(String display, Emoji emoji) {
            this(null, 0L, display, emoji);
        }

        private PendingReaction withMessage(MessageChannelUnion channel, long messageId) {
            return new PendingReaction(channel, messageId, display, emoji);
        }
    }
}
