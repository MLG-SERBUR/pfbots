package com.robomwm.ai.pfbots.markov;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarkovManagerTest {
    @TempDir
    Path tempDir;

    @Test
    void recentMessagesReturnsTailInOriginalOrder() {
        MarkovManager manager = new MarkovManager(null, tempDir);
        long channelId = 123L;

        for (int i = 1; i <= 505; i++) {
            manager.appendToBrain(channelId, "message " + i);
        }

        List<String> recentMessages = manager.getRecentMessages(channelId, 500);

        assertEquals(500, recentMessages.size());
        assertEquals("message 6", recentMessages.get(0));
        assertEquals("message 505", recentMessages.get(499));
    }

    @Test
    void recentMessagesHandlesEmptyAndInvalidLimits() {
        MarkovManager manager = new MarkovManager(null, tempDir);

        assertTrue(manager.getRecentMessages(123L, 500).isEmpty());
        assertTrue(manager.getRecentMessages(123L, 0).isEmpty());
    }

    @Test
    void botMessagesAreStoredWithPromptPrefixInAiLog() {
        MarkovManager manager = new MarkovManager(null, tempDir);
        long channelId = 123L;

        manager.appendToBrain(channelId, "human question?");
        manager.appendToAiLog(channelId, "human question?");
        manager.appendBotMessageToAiLog(channelId, "bot answer");

        assertEquals(List.of("human question?", "<MasterOEBot> bot answer"),
                manager.getRecentMessagesForAi(channelId, 500));
    }

    @Test
    void botMessagesAreNotStoredInBrain() throws Exception {
        MarkovManager manager = new MarkovManager(null, tempDir);
        long channelId = 123L;

        manager.appendBotMessageToAiLog(channelId, "hello there");
        manager.loadBrain(channelId);

        assertEquals(List.of("<MasterOEBot> hello there"), manager.getRecentMessagesForAi(channelId, 500));
        assertTrue(manager.getRecentMessages(channelId, 500).isEmpty());
        assertTrue(Files.notExists(tempDir.resolve(String.valueOf(channelId)).resolve("brain")));
        assertTrue(manager.generateReply(channelId).isEmpty());
    }

    @Test
    void aiRecentMessagesFallBackToBrainWhenAiLogDoesNotExist() {
        MarkovManager manager = new MarkovManager(null, tempDir);
        long channelId = 123L;

        manager.appendToBrain(channelId, "old human line");

        // Should not fall back to brain anymore if log is initialized (even if empty)
        manager.ensureAiLogInitialized(channelId);
        assertTrue(manager.getRecentMessagesForAi(channelId, 500).isEmpty());
        assertTrue(Files.exists(tempDir.resolve(String.valueOf(channelId)).resolve("ai.log")));
    }

    @Test
    void aiLogInitializationNoLongerCopiesBrain() {
        MarkovManager manager = new MarkovManager(null, tempDir);
        long channelId = 123L;

        manager.appendToBrain(channelId, "old human line");
        manager.ensureAiLogInitialized(channelId);
        manager.appendToAiLog(channelId, "new human line");

        assertEquals(List.of("new human line"),
                manager.getRecentMessagesForAi(channelId, 500));
    }

    @Test
    void tokenBudgetPullStopsBeforeExceedingBudgetAndKeepsNewest() {
        MarkovManager manager = new MarkovManager(null, tempDir);
        long channelId = 123L;

        String filler = "lorem ipsum ".repeat(50);
        for (int i = 1; i <= 30; i++) {
            manager.appendToAiLog(channelId, "msg" + i + " " + filler);
        }

        // Budget fits only a few of the large filler messages
        List<String> recent = manager.getRecentMessagesForAiUntilTokenBudget(channelId, 1500);

        assertTrue(recent.size() < 30);
        assertTrue(recent.get(recent.size() - 1).startsWith("msg30 "));
        assertFalse(recent.get(0).startsWith("msg1 "));
        for (int i = 0; i < recent.size() - 1; i++) {
            int current = Integer.parseInt(recent.get(i).split(" ")[0].substring(3));
            int next = Integer.parseInt(recent.get(i + 1).split(" ")[0].substring(3));
            assertEquals(current + 1, next, "messages must stay in original order");
        }
    }

    @Test
    void tokenBudgetPullAlwaysIncludesNewestMessageEvenIfOversized() {
        MarkovManager manager = new MarkovManager(null, tempDir);
        long channelId = 123L;

        manager.appendToAiLog(channelId, "tiny old message");
        manager.appendToAiLog(channelId, "huge newest " + "lorem ipsum ".repeat(200));

        List<String> recent = manager.getRecentMessagesForAiUntilTokenBudget(channelId, 10);

        assertEquals(1, recent.size());
        assertTrue(recent.get(0).startsWith("huge newest "));
    }

    @Test
    void tokenBudgetPullIncludesAllSmallMessages() {
        MarkovManager manager = new MarkovManager(null, tempDir);
        long channelId = 123L;

        for (int i = 1; i <= 20; i++) {
            manager.appendToAiLog(channelId, "short message " + i);
        }

        List<String> recent = manager.getRecentMessagesForAiUntilTokenBudget(channelId, 100000);

        assertEquals(20, recent.size());
        assertEquals("short message 1", recent.get(0));
        assertEquals("short message 20", recent.get(19));
    }

    @Test
    void tokenBudgetPullHandlesEmptyAndInvalidArguments() {
        MarkovManager manager = new MarkovManager(null, tempDir);

        assertTrue(manager.getRecentMessagesForAiUntilTokenBudget(123L, 8000).isEmpty());
        assertTrue(manager.getRecentMessagesForAiUntilTokenBudget(123L, 0).isEmpty());
    }

    @Test
    void countAiLogLinesCountsAppendedLinesAndMissingFile() {
        MarkovManager manager = new MarkovManager(null, tempDir);

        assertEquals(-1, manager.countAiLogLines(123L));
        manager.appendToAiLog(123L, "one");
        manager.appendToAiLog(123L, "two");
        manager.appendToAiLog(123L, "three");
        assertEquals(3, manager.countAiLogLines(123L));
    }
}
