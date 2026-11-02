package com.robomwm.ai.pfbots.markov;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaseoDaemonResponderTest {
    @Test
    void paseoInstructionsCoverLogImagesAndSchema() {
        String instructions = PaseoDaemonResponder.PASEO_INSTRUCTIONS;
        assertTrue(instructions != null && !instructions.isBlank());
        assertTrue(instructions.contains("data/markov/<channelId>/ai.log"));
        assertTrue(instructions.contains("reply schema"));
    }

    private static Path fakeBridge(Path dir, Path capture, String script) throws Exception {
        Path bridge = dir.resolve("fake-bridge.sh");
        Files.writeString(bridge, script.replace("CAPTURE", capture.toString()), StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(bridge, PosixFilePermissions.fromString("rwxr-xr-x"));
        return bridge;
    }

    @Test
    void returnsBridgeReplyAndPassesRequestThrough(@TempDir Path dir) throws Exception {
        Path capture = dir.resolve("request.json");
        Path bridge = fakeBridge(dir, capture, "#!/bin/sh\ncat > CAPTURE\nprintf '{\"reply\":\"fake hi\"}'\n");
        PaseoDaemonResponder responder = new PaseoDaemonResponder(
                List.of(bridge.toString()), "opencode/test-model", "bot voice");
        String reply = responder.generateReply(new GenerativeAiRequest(
                List.of("user: hello"), null, List.of(new VisionImage("aGVsbG8=", "image/png")))).get();
        assertEquals("fake hi", reply);
        String sent = Files.readString(capture);
        assertTrue(sent.contains("opencode/test-model"));
        assertTrue(sent.contains("bot voice"));
        assertTrue(sent.contains("hello"));
        assertTrue(sent.contains("aGVsbG8="));
        assertTrue(sent.contains("image/png"));
    }

    @Test
    void passesChannelIdForBridgeReadConfinement(@TempDir Path dir) throws Exception {
        Path capture = dir.resolve("request.json");
        Path bridge = fakeBridge(dir, capture, "#!/bin/sh\ncat > CAPTURE\nprintf '{\"reply\":\"ok\"}'\n");
        PaseoDaemonResponder responder = new PaseoDaemonResponder(
                List.of(bridge.toString()), "opencode/test-model", null);
        responder.generateReply(new GenerativeAiRequest(
                List.of("hi"), null, List.of(), 12345L)).get();
        assertTrue(Files.readString(capture).contains("\"channelId\":\"12345\""));
    }

    @Test
    void passesSnowflakeChannelIdExactlyNotRounded(@TempDir Path dir) throws Exception {
        // Discord snowflakes exceed 2^53: as a JS Number, 1349083001275027559
        // rounds to 1349083001275027500 and confines reads to the wrong dir.
        Path capture = dir.resolve("request.json");
        Path bridge = fakeBridge(dir, capture, "#!/bin/sh\ncat > CAPTURE\nprintf '{\"reply\":\"ok\"}'\n");
        PaseoDaemonResponder responder = new PaseoDaemonResponder(
                List.of(bridge.toString()), "opencode/test-model", null);
        responder.generateReply(new GenerativeAiRequest(
                List.of("hi"), null, List.of(), 1349083001275027559L)).get();
        String sent = Files.readString(capture);
        assertTrue(sent.contains("\"channelId\":\"1349083001275027559\""));
        assertTrue(!sent.contains("1349083001275027500"));
    }

    @Test
    void failingBridgeFailsFuture(@TempDir Path dir) throws Exception {
        Path capture = dir.resolve("request.json");
        Path bridge = fakeBridge(dir, capture, "#!/bin/sh\ncat > CAPTURE\necho boom >&2\nexit 3\n");
        PaseoDaemonResponder responder = new PaseoDaemonResponder(
                List.of(bridge.toString()), "opencode/test-model", null);
        assertThrows(ExecutionException.class,
                () -> responder.generateReply(new GenerativeAiRequest(List.of("hi"))).get());
    }

    @Test
    void emptyReplyFailsFuture(@TempDir Path dir) throws Exception {
        Path capture = dir.resolve("request.json");
        Path bridge = fakeBridge(dir, capture, "#!/bin/sh\ncat > CAPTURE\nprintf '{\"reply\":\"  \"}'\n");
        PaseoDaemonResponder responder = new PaseoDaemonResponder(
                List.of(bridge.toString()), "opencode/test-model", null);
        assertThrows(ExecutionException.class,
                () -> responder.generateReply(new GenerativeAiRequest(List.of("hi"))).get());
    }
}
