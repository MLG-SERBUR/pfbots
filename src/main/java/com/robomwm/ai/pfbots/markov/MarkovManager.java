package com.robomwm.ai.pfbots.markov;

import java.io.*;
import java.nio.file.*;
import java.util.*;

import com.robomwm.ai.pfbots.bot.BotRegistry;

public class MarkovManager {
    private static final Path DEFAULT_BRAIN_DIR = Paths.get("data/markov");
    private static final long MESSAGE_DEDUPE_TTL_MS = 10 * 60 * 1000L;
    private static final long MESSAGE_DEDUPE_CLEANUP_INTERVAL_MS = 60 * 1000L;
    private static final int MAX_DEDUPED_MESSAGE_IDS = 100_000;
    public static final String BOT_MESSAGE_PREFIX = "<MasterOEBot> ";
    private static final String BOT_MESSAGE_TAG = BOT_MESSAGE_PREFIX.trim();
    // Per-channel layout: data/markov/<channelId>/brain and ai.log.
    // Chat log is debugging only and lives elsewhere, never in this dir.
    private static final String BRAIN_FILE = "brain";
    private static final String AI_LOG_FILE = "ai.log";
    private static final String CHAT_LOG_EXTENSION = ".chat.log";
    private final MarkovConfig config;
    private final Path brainDir;
    private final Map<Long, JMegaHal> brains = new HashMap<>();
    private final Map<Long, Boolean> brainLoaded = new HashMap<>();
    // Multiple bot listeners share this manager and can receive the same Discord message.
    // Keep the idempotency state beside the shared brain and log writers.
    private final LinkedHashMap<Long, Long> recentlyIngestedMessageIds = new LinkedHashMap<>();
    private long nextMessageDedupeCleanupAt;

    public MarkovManager() {
        this(null);
    }

    public MarkovManager(MarkovConfig config) {
        this(config, DEFAULT_BRAIN_DIR);
    }

    MarkovManager(MarkovConfig config, Path brainDir) {
        this.config = config;
        this.brainDir = brainDir;
    }

    /**
     * Claims a human Discord message for ingestion into this shared manager.
     * Returns false when another listener already claimed it recently.
     */
    public synchronized boolean claimMessageForIngestion(long messageId) {
        long now = System.currentTimeMillis();
        Long previouslyIngestedAt = recentlyIngestedMessageIds.get(messageId);
        if (previouslyIngestedAt != null) {
            if (now - previouslyIngestedAt < MESSAGE_DEDUPE_TTL_MS) {
                return false;
            }
            recentlyIngestedMessageIds.remove(messageId);
        }

        if (now >= nextMessageDedupeCleanupAt) {
            long cutoff = now - MESSAGE_DEDUPE_TTL_MS;
            Iterator<Map.Entry<Long, Long>> entries = recentlyIngestedMessageIds.entrySet().iterator();
            while (entries.hasNext()) {
                if (entries.next().getValue() < cutoff) {
                    entries.remove();
                }
            }
            nextMessageDedupeCleanupAt = now + MESSAGE_DEDUPE_CLEANUP_INTERVAL_MS;
        }

        while (recentlyIngestedMessageIds.size() >= MAX_DEDUPED_MESSAGE_IDS) {
            Iterator<Long> oldestIds = recentlyIngestedMessageIds.keySet().iterator();
            if (!oldestIds.hasNext()) {
                break;
            }
            oldestIds.next();
            oldestIds.remove();
        }
        recentlyIngestedMessageIds.put(messageId, now);
        return true;
    }

    public synchronized void loadBrain(long channelId) {
        if (brainLoaded.getOrDefault(channelId, false)) return;
        brainLoaded.put(channelId, true);

        JMegaHal brain = newBrain(channelId);
        Path path = getBrainPath(channelId);

        if (Files.exists(path)) {
            try (BufferedReader reader = Files.newBufferedReader(path)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (!line.isEmpty()) {
                        brain.add(line);
                    }
                }
            } catch (IOException e) {
                System.err.println("Failed to load brain for channel " + channelId + ": " + e.getMessage());
            }
        }

        brains.put(channelId, brain);
    }

    public synchronized void reloadBrain(long channelId) {
        brainLoaded.remove(channelId);
        brains.remove(channelId);
        loadBrain(channelId);
    }

    public synchronized boolean isEmpty(long channelId) {
        JMegaHal brain = brains.get(channelId);
        return brain == null || brain.getSentence().isEmpty();
    }

    public synchronized void seedFromHistory(long channelId, List<String> messages) {
        JMegaHal brain = brains.computeIfAbsent(channelId, this::newBrain);
        for (String msg : messages) {
            if (msg != null && !msg.trim().isEmpty()) {
                String trimmed = msg.trim();
                if (!ProfanityFilter.containsProfanity(trimmed)) {
                    brain.add(trimmed);
                    appendToBrain(channelId, trimmed);
                }
            }
        }
    }

    public synchronized String generateReply(long channelId) {
        JMegaHal brain = brains.get(channelId);
        return brain != null ? brain.getSentence() : "";
    }

    public synchronized String generateReply(long channelId, String seedWord) {
        JMegaHal brain = brains.get(channelId);
        return brain != null ? brain.getSentence(seedWord) : "";
    }

    public synchronized void train(long channelId, String message) {
        if (message == null || message.trim().isEmpty()) return;
        String trimmed = message.trim();
        if (ProfanityFilter.containsProfanity(trimmed)) return;
        JMegaHal brain = brains.computeIfAbsent(channelId, this::newBrain);
        brain.add(trimmed);
    }

    public synchronized void appendToBrain(long channelId, String message) {
        if (message == null || message.trim().isEmpty()) return;
        String trimmed = message.trim();
        if (ProfanityFilter.containsProfanity(trimmed)) return;
        appendLine(channelId, trimmed, getBrainPath(channelId), "brain");
    }

    public synchronized void appendToAiLog(long channelId, String authorName, String message) {
        if (message == null || message.trim().isEmpty()) return;
        String trimmed = message.trim();
        if (ProfanityFilter.containsProfanity(trimmed)) return;
        appendLine(channelId, "<" + authorName + "> " + trimmed, getAiLogPath(channelId), "AI log");
    }

    public synchronized void appendToAiLog(long channelId, String message) {
        if (message == null || message.trim().isEmpty()) return;
        String trimmed = message.trim();
        if (ProfanityFilter.containsProfanity(trimmed)) return;
        appendLine(channelId, trimmed, getAiLogPath(channelId), "AI log");
    }

    public synchronized void appendBotMessageToAiLog(long channelId, String message) {
        appendBotMessageToAiLog(channelId, message, BOT_MESSAGE_PREFIX);
    }

    /** Per-bot variant: tags the line with this bot's prefix in the shared log. */
    public synchronized void appendBotMessageToAiLog(long channelId, String message, String botPrefix) {
        if (message == null || message.trim().isEmpty()) return;
        String trimmed = stripBotPrefix(message.trim());
        if (trimmed.isEmpty()) return;
        if (ProfanityFilter.containsProfanity(trimmed)) return;
        appendLine(channelId, normalizePrefix(botPrefix) + trimmed, getAiLogPath(channelId), "AI log");
    }

    /**
     * Permanent chat log, debugging only, in the same
     * {@code <DisplayName> message} format as the AI log, but never scrubbed.
     * Retains {@code <MasterOEBot>} lines so bot output can later refine the
     * system prompt. Scrubbers and {@link #scrubAiLog} never touch this file.
     * It is NOT given to the AI; see {@link #getChatLogPath}.
     */
    public synchronized void appendToChatLog(long channelId, String authorName, String message) {
        if (message == null || message.trim().isEmpty()) return;
        String trimmed = message.trim();
        if (ProfanityFilter.containsProfanity(trimmed)) return;
        ensureChatLogInitialized(channelId);
        appendLine(channelId, "<" + authorName + "> " + trimmed, getChatLogPath(channelId), "chat log");
    }

    public synchronized void appendToChatLog(long channelId, String message) {
        if (message == null || message.trim().isEmpty()) return;
        String trimmed = message.trim();
        if (ProfanityFilter.containsProfanity(trimmed)) return;
        ensureChatLogInitialized(channelId);
        appendLine(channelId, trimmed, getChatLogPath(channelId), "chat log");
    }

    public synchronized void appendBotMessageToChatLog(long channelId, String message) {
        appendBotMessageToChatLog(channelId, message, BOT_MESSAGE_PREFIX);
    }

    /** Per-bot variant: tags the line with this bot's prefix in the shared log. */
    public synchronized void appendBotMessageToChatLog(long channelId, String message, String botPrefix) {
        if (message == null || message.trim().isEmpty()) return;
        String trimmed = stripBotPrefix(message.trim());
        if (trimmed.isEmpty()) return;
        if (ProfanityFilter.containsProfanity(trimmed)) return;
        ensureChatLogInitialized(channelId);
        appendLine(channelId, normalizePrefix(botPrefix) + trimmed, getChatLogPath(channelId), "chat log");
    }

    static String normalizePrefix(String botPrefix) {
        if (botPrefix == null || botPrefix.isBlank()) {
            return BOT_MESSAGE_PREFIX;
        }
        String trimmed = botPrefix.trim();
        if (!trimmed.startsWith("<")) {
            trimmed = "<" + trimmed;
        }
        if (!trimmed.endsWith(">")) {
            int end = trimmed.indexOf('>');
            trimmed = end == -1 ? trimmed + ">" : trimmed.substring(0, end + 1);
        }
        return trimmed + " ";
    }

    /**
     * Canonical bot log prefixes (every known bot tag). Used for scrub
     * detection. Human lookalikes such as {@code <MasterOE>} never match —
     * only exact known tags do.
     */
    public static boolean isBotPrefix(String text) {
        return isBotPrefix(text, BotRegistry.ALL_TAGS);
    }

    public static boolean isBotPrefix(String text, java.util.Collection<String> knownTags) {
        if (text == null || knownTags == null) return false;
        for (String tag : knownTags) {
            if (tag != null && startsWithTag(text, tag.trim())) {
                return true;
            }
        }
        return false;
    }

    private static boolean startsWithTag(String text, String tag) {
        if (text == null) return false;
        return text.length() >= tag.length()
                && text.regionMatches(true, 0, tag, 0, tag.length());
    }

    /**
     * Strips any leading {@code <...>} tag AI sometimes prepends to its
     * reply (e.g. {@code <MasterOEBot>}, {@code <MasterOE>}, any username).
     * Output-only: loops to handle repeated prefixes and tolerates leading
     * whitespace. A tag is a leading {@code <}, a non-empty name without
     * brackets/newlines, then {@code >}.
     */
    public static String stripBotPrefix(String text) {
        if (text == null) return "";
        String stripped = text.stripLeading();
        while (stripped.startsWith("<")) {
            int end = stripped.indexOf('>');
            if (end <= 1) {
                break;
            }
            String inside = stripped.substring(1, end);
            if (inside.contains("<") || inside.contains(">")
                    || inside.contains("\n") || inside.contains("\r")
                    || inside.trim().isEmpty()) {
                break;
            }
            stripped = stripped.substring(end + 1).stripLeading();
            if (stripped.isEmpty()) {
                break;
            }
        }
        return stripped;
    }

    public synchronized void ensureAiLogInitialized(long channelId) {
        Path aiLogPath = getAiLogPath(channelId);
        if (Files.exists(aiLogPath)) {
            return;
        }

        try {
            Files.createDirectories(aiLogPath.getParent());
            Files.createFile(aiLogPath);
        } catch (IOException e) {
            System.err.println("Failed to initialize AI log for channel " + channelId + ": " + e.getMessage());
        }
    }

    public synchronized boolean aiLogExists(long channelId) {
        return Files.exists(getAiLogPath(channelId));
    }

    public synchronized void ensureChatLogInitialized(long channelId) {
        Path chatLogPath = getChatLogPath(channelId);
        if (Files.exists(chatLogPath)) {
            return;
        }

        try {
            Files.createDirectories(chatLogPath.getParent());
            Files.createFile(chatLogPath);
        } catch (IOException e) {
            System.err.println("Failed to initialize chat log for channel " + channelId + ": " + e.getMessage());
            return;
        }
        backfillChatLogFromAiLog(channelId);
    }

    public synchronized boolean chatLogExists(long channelId) {
        return Files.exists(getChatLogPath(channelId));
    }

    /**
     * One-time backfill for channels that already have an AI log but no chat
     * log yet (e.g. upgrade from a version without chat logging). Copies
     * existing AI log lines verbatim so no bot history is lost. No-op when the
     * chat log already has content or no AI log exists.
     */
    public synchronized void backfillChatLogFromAiLog(long channelId) {
        Path chatLogPath = getChatLogPath(channelId);
        Path aiLogPath = getAiLogPath(channelId);
        try {
            if (!Files.exists(aiLogPath)) return;
            boolean chatEmpty = !Files.exists(chatLogPath) || Files.size(chatLogPath) == 0;
            if (!chatEmpty) return;
            List<String> lines = Files.readAllLines(aiLogPath);
            if (lines.isEmpty()) return;
            Files.createDirectories(chatLogPath.getParent());
            try (BufferedWriter writer = Files.newBufferedWriter(chatLogPath,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                for (String line : lines) {
                    writer.write(line);
                    writer.newLine();
                }
            }
            System.out.println("Backfilled chat log for channel " + channelId + " with " + lines.size() + " lines from AI log.");
        } catch (IOException e) {
            System.err.println("Failed to backfill chat log for channel " + channelId + ": " + e.getMessage());
        }
    }

    public synchronized List<String> getRecentChatLog(long channelId, int limit) {
        ensureChatLogInitialized(channelId);
        Path path = getChatLogPath(channelId);
        if (Files.exists(path)) {
            return getRecentLines(path, limit, "chat log", channelId);
        }
        return Collections.emptyList();
    }

    private void appendLine(long channelId, String line, Path path, String logName) {
        try {
            Files.createDirectories(path.getParent());
            try (BufferedWriter writer = Files.newBufferedWriter(path,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                writer.write(line);
                writer.newLine();
            }
        } catch (IOException e) {
            System.err.println("Failed to append to " + logName + " for channel " + channelId + ": " + e.getMessage());
        }
    }

    public synchronized List<String> getRecentMessages(long channelId, int limit) {
        if (limit <= 0) {
            return Collections.emptyList();
        }

        Path path = getBrainPath(channelId);
        if (!Files.exists(path)) {
            return Collections.emptyList();
        }

        Deque<String> recentMessages = new ArrayDeque<>(limit);
        try (BufferedReader reader = Files.newBufferedReader(path)) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                if (recentMessages.size() == limit) {
                    recentMessages.removeFirst();
                }
                recentMessages.addLast(trimmed);
            }
        } catch (IOException e) {
            System.err.println("Failed to read recent brain messages for channel " + channelId + ": " + e.getMessage());
            return Collections.emptyList();
        }
        return new ArrayList<>(recentMessages);
    }

    public synchronized List<String> getRecentMessagesForAi(long channelId, int limit) {
        ensureAiLogInitialized(channelId);
        Path path = getAiLogPath(channelId);
        if (Files.exists(path)) {
            return getRecentLines(path, limit, "AI log", channelId);
        }
        return getRecentMessages(channelId, limit);
    }

    /**
     * Reads history backwards from the newest message until the token budget is
     * reached. Always includes the newest message.
     * Accounts for system prompt tokens in budget.
     */
    public synchronized List<String> getRecentMessagesForAiUntilTokenBudget(long channelId, long tokenBudget) {
        return getRecentMessagesForAiUntilTokenBudget(channelId, tokenBudget, null);
    }

    public synchronized List<String> getRecentMessagesForAiUntilTokenBudget(long channelId, long tokenBudget, String systemPrompt) {
        return getRecentMessagesForAiUntilTokenBudget(channelId, tokenBudget, systemPrompt, (String) null);
    }

    /**
     * Variant that estimates with a representative model (per-family calibration), so the
     * gathered history fits a specific tokenizer family. Null model = default estimate.
     */
    public synchronized List<String> getRecentMessagesForAiUntilTokenBudget(long channelId, long tokenBudget, String systemPrompt, String estimateModel) {
        return getRecentMessagesForAiUntilTokenBudget(channelId, tokenBudget, systemPrompt, estimateModel, null);
    }

    /**
     * Conservative variant across candidate models (max calibrated estimate),
     * so gathered history fits the most restrictive tokenizer family.
     * When {@code estimateModels} is null/empty, falls back to {@code estimateModel}.
     */
    public synchronized List<String> getRecentMessagesForAiUntilTokenBudget(long channelId, long tokenBudget, String systemPrompt, String estimateModel, java.util.Collection<String> estimateModels) {
        if (tokenBudget <= 0) {
            return Collections.emptyList();
        }
        ensureAiLogInitialized(channelId);
        Path path = Files.exists(getAiLogPath(channelId)) ? getAiLogPath(channelId) : getBrainPath(channelId);
        if (!Files.exists(path)) {
            return Collections.emptyList();
        }

        List<String> lines = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(path)) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty()) {
                    lines.add(trimmed);
                }
            }
        } catch (IOException e) {
            System.err.println("Failed to read recent AI log messages for channel " + channelId + ": " + e.getMessage());
            return Collections.emptyList();
        }
        if (lines.isEmpty()) {
            return lines;
        }

        long systemTokens = estimateForGather(systemPrompt != null ? systemPrompt + "\n" : null, estimateModel, estimateModels);
        long overheadTokens = estimateForGather("system\nuser\n", estimateModel, estimateModels); // role overhead
        long effectiveBudget = tokenBudget - systemTokens - overheadTokens;
        if (effectiveBudget < 500) effectiveBudget = tokenBudget - systemTokens; // fallback if overhead too large
        if (effectiveBudget <= 0) effectiveBudget = tokenBudget;

        int start = lines.size() - 1;
        long usedTokens = estimateForGather(lines.get(start) + "\n", estimateModel, estimateModels);
        while (start > 0
                && estimateForGather(lines.get(start - 1) + "\n", estimateModel, estimateModels) <= effectiveBudget - usedTokens) {
            start--;
            usedTokens += estimateForGather(lines.get(start) + "\n", estimateModel, estimateModels);
        }
        if (start > 0 || !lines.isEmpty()) {
            List<String> gathered = new ArrayList<>(lines.subList(start, lines.size()));
            String dualEst = PromptTokenizer.formatDualTokenEstimates(String.join("\n", gathered));
            System.out.println("AI history pulled up to " + gathered.size()
                    + " messages (~" + (usedTokens + systemTokens) + " tokens of " + tokenBudget + " budget, system=" + systemTokens + " [" + dualEst + "]).");
            return gathered;
        }
        return new ArrayList<>(lines.subList(start, lines.size()));
    }

    private static long estimateForGather(String text, String estimateModel, java.util.Collection<String> estimateModels) {
        if (text == null || text.isEmpty()) return 0;
        if (estimateModels != null && !estimateModels.isEmpty()) {
            return PromptTokenizer.estimateTokensConservative(text, estimateModels);
        }
        return PromptTokenizer.estimateTokens(text, estimateModel);
    }

    public synchronized void scrubAiLog(long channelId) {
        scrubAiLog(channelId, BotRegistry.ALL_TAGS);
    }

    /** Shared-log variant: scrubs lines from any known bot tag. */
    public synchronized void scrubAiLog(long channelId, java.util.Collection<String> knownTags) {
        Path path = getAiLogPath(channelId);
        if (!Files.exists(path)) return;

        try {
            List<String> lines = Files.readAllLines(path);
            List<String> cleanLines = new ArrayList<>();
            boolean modified = false;

            boolean inBotMessage = false;
            for (String line : lines) {
                String trimmed = line.trim();
                if (trimmed.startsWith("<") && trimmed.contains("> ")) {
                    inBotMessage = isBotPrefix(trimmed, knownTags);
                }

                if (inBotMessage) {
                    modified = true;
                } else {
                    cleanLines.add(line);
                }
            }

            if (modified) {
                Files.write(path, cleanLines);
                System.out.println("Scrubbed AI log for channel " + channelId);
            }
        } catch (IOException e) {
            System.err.println("Failed to scrub AI log for channel " + channelId + ": " + e.getMessage());
        }
    }

    private Path getBrainPath(long channelId) {
        return brainDir.resolve(String.valueOf(channelId)).resolve(BRAIN_FILE);
    }

    private Path getAiLogPath(long channelId) {
        return brainDir.resolve(String.valueOf(channelId)).resolve(AI_LOG_FILE);
    }

    /** Debugging only, kept out of the per-channel AI dir: data/chat-logs/<channelId>.chat.log. */
    Path getChatLogPath(long channelId) {
        return brainDir.resolveSibling("chat-logs").resolve(channelId + CHAT_LOG_EXTENSION);
    }

    private JMegaHal newBrain(long channelId) {
        return new JMegaHal(config == null || config.allowShortMessages(channelId));
    }

    private List<String> getRecentLines(Path path, int limit, String logName, long channelId) {
        if (limit <= 0 || !Files.exists(path)) {
            return Collections.emptyList();
        }

        Deque<String> recentMessages = new ArrayDeque<>(limit);
        try (BufferedReader reader = Files.newBufferedReader(path)) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                if (recentMessages.size() == limit) {
                    recentMessages.removeFirst();
                }
                recentMessages.addLast(trimmed);
            }
        } catch (IOException e) {
            System.err.println("Failed to read recent " + logName + " messages for channel " + channelId + ": " + e.getMessage());
            return Collections.emptyList();
        }
        return new ArrayList<>(recentMessages);
    }
}
