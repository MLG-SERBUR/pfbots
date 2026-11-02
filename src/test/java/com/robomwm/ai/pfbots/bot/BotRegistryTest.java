package com.robomwm.ai.pfbots.bot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BotRegistryTest {

    @TempDir
    Path tempDir;

    @Test
    void masterFirstAndEachBotOwnsCommands() {
        assertEquals("MasterOEBot", BotRegistry.PROFILES.get(0).key());
        assertTrue(BotRegistry.MasterOEBot.registersCommands());
        assertTrue(BotRegistry.PARAOKA.registersCommands());
    }

    @Test
    void paraokaPromptsRenamed() {
        assertTrue(BotRegistry.PARAOKA.systemPrompt().contains(BotProfile.BOT_NAME_VARIABLE));
        assertTrue(BotRegistry.PARAOKA.resolvePrompt(BotRegistry.PARAOKA.systemPrompt(), null).contains("paraokabot"));
        assertFalse(BotRegistry.PARAOKA.systemPrompt().contains("MasterOEBot"));
        assertTrue(BotRegistry.PARAOKA.secondChancePrompt().contains("<paraokabot>"));
        assertTrue(BotRegistry.PARAOKA.reactionPrompt().contains(BotProfile.BOT_NAME_VARIABLE));
        assertTrue(BotRegistry.PARAOKA.resolvePrompt(BotRegistry.PARAOKA.reactionPrompt(), null).contains("paraokabot"));
        assertEquals("<paraokabot> ", BotRegistry.PARAOKA.botTag());
    }

    @Test
    void allTagsCoverBothBots() {
        assertTrue(BotRegistry.ALL_TAGS.contains("<MasterOEBot>"));
        assertTrue(BotRegistry.ALL_TAGS.contains("<paraokabot>"));
    }

    @Test
    void eachBotDefinesItsOwnPromptsInCode() {
        for (var profile : BotRegistry.PROFILES) {
            assertFalse(profile.systemPrompt() == null || profile.systemPrompt().isBlank(),
                    profile.key() + " must define systemPrompt in code");
            assertFalse(profile.secondChancePrompt() == null || profile.secondChancePrompt().isBlank(),
                    profile.key() + " must define secondChancePrompt in code");
            assertFalse(profile.reactionPrompt() == null || profile.reactionPrompt().isBlank(),
                    profile.key() + " must define reactionPrompt in code");
            assertFalse(profile.paseoSystemPrompt() == null || profile.paseoSystemPrompt().isBlank(),
                    profile.key() + " must define paseoSystemPrompt in code");
            assertTrue(profile.paseoSystemPrompt().contains(BotProfile.BOT_NAME_VARIABLE),
                    profile.key() + " paseoSystemPrompt must contain {{BOT_NAME}}");
            assertTrue(profile.paseoSystemPrompt().contains("data/markov/<channelId>/ai.log"),
                    profile.key() + " paseoSystemPrompt must include the log instructions");
        }
        assertFalse(BotRegistry.MasterOEBot.paseoSystemPrompt().contains("paraokabot"));
        assertFalse(BotRegistry.PARAOKA.paseoSystemPrompt().contains("MasterOEBot"));
        assertTrue(BotRegistry.MasterOEBot.systemPrompt().contains(BotProfile.BOT_NAME_VARIABLE));
        assertTrue(BotRegistry.MasterOEBot.resolvePrompt(BotRegistry.MasterOEBot.systemPrompt(), null).contains("MasterOEBot"));
    }

    @Test
    void promptVariableResolvesToLiveName() {
        assertEquals("hi NewName bye",
                BotRegistry.MasterOEBot.resolvePrompt("hi {{BOT_NAME}} bye", "NewName"));
        assertEquals("hi MasterOEBot bye",
                BotRegistry.MasterOEBot.resolvePrompt("hi {{BOT_NAME}} bye", null));
        assertEquals("hi MasterOEBot bye",
                BotRegistry.MasterOEBot.resolvePrompt("hi {{BOT_NAME}} bye", "  "));
    }

    @Test
    void extraTokensLoadAndSkipPlaceholders() throws IOException {
        Path path = tempDir.resolve("discord.yaml");
        Files.writeString(path, "tokens:\n  - \"real-token\"\n  - \"PUT_SECOND_BOT_TOKEN_HERE\"\n");
        assertEquals(List.of("real-token"), DiscordTokens.loadExtraTokens(path));
    }

    @Test
    void missingTokenFileMeansSingleBot() throws IOException {
        assertEquals(List.of(), DiscordTokens.loadExtraTokens(tempDir.resolve("discord.yaml")));
    }

    @Test
    void nestedDiscordTokensShapeLoads() throws IOException {
        Path path = tempDir.resolve("discord.yaml");
        Files.writeString(path, "discord:\n  tokens:\n    - \"abc\"\n");
        assertEquals(List.of("abc"), DiscordTokens.loadExtraTokens(path));
    }

    @Test
    void dumcordKeyedTokens() throws IOException {
        Path path = tempDir.resolve("dumcord.yml");
        Files.writeString(path, "bots:\n  MasterOEBot: \"m1\"\n  paraokabot: \"PUT_PARAOKA_BOT_TOKEN_HERE\"\n");
        DumcordTokens tokens = DumcordTokens.load(path);
        assertEquals("m1", tokens.tokenFor("MasterOEBot"));
        assertEquals(null, tokens.tokenFor("paraokabot"));
        assertFalse(tokens.isEmpty());
    }

    @Test
    void dumcordOrderedListFallsBackByIndex() throws IOException {
        Path path = tempDir.resolve("dumcord.yml");
        Files.writeString(path, "tokens:\n  - \"m1\"\n  - \"p1\"\n");
        DumcordTokens tokens = DumcordTokens.load(path);
        assertEquals("m1", tokens.tokenFor("MasterOEBot"));
        assertEquals("p1", tokens.tokenFor("paraokabot"));
    }

    @Test
    void dumcordMissingFileIsEmpty() throws IOException {
        assertTrue(DumcordTokens.load(tempDir.resolve("dumcord.yml")).isEmpty());
    }
}
