package com.robomwm.ai.pfbots.markov;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaseoReactionResponderTest {
    @Test
    void instructionsMirrorOnlyAndAllowRetraction() {
        String instructions = PaseoReactionResponder.PASEO_REACTION_INSTRUCTIONS;
        assertTrue(instructions.contains("Never invent a new emoji"));
        assertTrue(instructions.contains("REMOVE"));
        assertTrue(instructions.contains("reply schema"));
        assertTrue(instructions.contains("Do not load or use any skill"));
    }

    private static Path fakeBridge(Path dir, Path capture, String script) throws Exception {
        Path bridge = dir.resolve("fake-bridge.sh");
        Files.writeString(bridge, script.replace("CAPTURE", capture.toString()), StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(bridge, PosixFilePermissions.fromString("rwxr-xr-x"));
        return bridge;
    }

    @Test
    void passesChannelIdAndReactionInstructionsToBridge(@TempDir Path dir) throws Exception {
        Path capture = dir.resolve("request.json");
        Path bridge = fakeBridge(dir, capture, "#!/bin/sh\ncat > CAPTURE\nprintf '{\"reply\":\"ADD r1 REMOVE s1\"}'\n");
        PaseoReactionResponder responder = new PaseoReactionResponder(
                new PaseoDaemonResponder(List.of(bridge.toString()), "opencode/test-model", "base prompt"), null, "base prompt");
        String reply = responder.generateReply(new GenerativeAiRequest(
                List.of("r1 messageId=111 reaction=❤️ message=\"hi\"", "s1 messageId=111 reaction=😂 message=\"hi\""),
                "decide reacts", List.of(), 12345L)).get();
        assertEquals("ADD r1 REMOVE s1", reply);
        String sent = Files.readString(capture);
        assertTrue(sent.contains("\"channelId\":\"12345\""));
        assertTrue(sent.contains("Never invent a new emoji"));
        assertTrue(sent.contains("decide reacts"));
        assertTrue(sent.contains("messageId=111"));
    }

    @Test
    void failingBridgeFallsBackToArli(@TempDir Path dir) throws Exception {
        Path capture = dir.resolve("request.json");
        Path bridge = fakeBridge(dir, capture, "#!/bin/sh\ncat > CAPTURE\necho boom >&2\nexit 3\n");
        AtomicBoolean fallbackCalled = new AtomicBoolean(false);
        GenerativeAiResponder fallback = request -> {
            fallbackCalled.set(true);
            return CompletableFuture.completedFuture("r2");
        };
        PaseoReactionResponder responder = new PaseoReactionResponder(
                new PaseoDaemonResponder(List.of(bridge.toString()), "opencode/test-model", null), fallback, null);
        assertEquals("r2", responder.generateReply(new GenerativeAiRequest(List.of("r2"), null, List.of(), 999L)).get());
        assertTrue(fallbackCalled.get());
    }

    @Test
    void garbagePaseoReplyFallsBack(@TempDir Path dir) throws Exception {
        Path capture = dir.resolve("request.json");
        Path bridge = fakeBridge(dir, capture, "#!/bin/sh\ncat > CAPTURE\nprintf '{\"reply\":\"maybe something\"}'\n");
        GenerativeAiResponder fallback = request -> CompletableFuture.completedFuture("NONE");
        PaseoReactionResponder responder = new PaseoReactionResponder(
                new PaseoDaemonResponder(List.of(bridge.toString()), "opencode/test-model", null), fallback, null);
        assertEquals("NONE", responder.generateReply(new GenerativeAiRequest(List.of("r1"), null, List.of(), 999L)).get());
    }

    @Test
    void noneReplyDoesNotFallBack(@TempDir Path dir) throws Exception {
        Path capture = dir.resolve("request.json");
        Path bridge = fakeBridge(dir, capture, "#!/bin/sh\ncat > CAPTURE\nprintf '{\"reply\":\"NONE\"}'\n");
        AtomicBoolean fallbackCalled = new AtomicBoolean(false);
        GenerativeAiResponder fallback = request -> {
            fallbackCalled.set(true);
            return CompletableFuture.completedFuture("r1");
        };
        PaseoReactionResponder responder = new PaseoReactionResponder(
                new PaseoDaemonResponder(List.of(bridge.toString()), "opencode/test-model", null), fallback, null);
        assertEquals("NONE", responder.generateReply(new GenerativeAiRequest(List.of("r1"), null, List.of(), 999L)).get());
        assertFalse(fallbackCalled.get());
    }

    @Test
    void parsesAddAndRemove() {
        assertEquals(Set.of("r1", "r2"), MarkovListener.parseReactionAddIds("ADD r1,r2 REMOVE s1"));
        assertEquals(Set.of("s1"), MarkovListener.parseReactionRemoveIds("ADD r1,r2 REMOVE s1"));
        assertEquals(Set.of("r1"), MarkovListener.parseReactionAddIds("r1"));
        assertEquals(Set.of(), MarkovListener.parseReactionRemoveIds("r1"));
        assertEquals(Set.of("s2"), MarkovListener.parseReactionRemoveIds("RETRACT s2"));
        assertEquals(Set.of(), MarkovListener.parseReactionAddIds("REMOVE r1"));
        assertEquals(Set.of(), MarkovListener.parseReactionAddIds("NONE"));
        assertEquals(Set.of(), MarkovListener.parseReactionRemoveIds(null));
    }
}
