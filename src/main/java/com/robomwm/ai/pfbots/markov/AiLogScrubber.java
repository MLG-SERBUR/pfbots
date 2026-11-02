package com.robomwm.ai.pfbots.markov;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public class AiLogScrubber {
    private static final String BRAIN_DIR = "data/markov";

    public static void main(String[] args) {
        boolean deleteMode = false;
        for (String arg : args) {
            if (arg.equals("--delete")) {
                deleteMode = true;
                break;
            }
        }

        File folder = new File(BRAIN_DIR);
        if (!folder.exists() || !folder.isDirectory()) {
            System.out.println("Brain directory not found: " + BRAIN_DIR);
            return;
        }

        // Per-channel layout: data/markov/<channelId>/ai.log
        File[] dirs = folder.listFiles(File::isDirectory);
        List<File> files = new ArrayList<>();
        if (dirs != null) {
            for (File dir : dirs) {
                File log = new File(dir, "ai.log");
                if (log.exists()) files.add(log);
            }
        }
        if (files.isEmpty()) {
            System.out.println("No ai.log files found.");
            return;
        }

        System.out.println("Starting AI log scrub (" + (deleteMode ? "DELETE" : "CHECK") + " mode)...");
        AtomicInteger totalMatches = new AtomicInteger(0);
        AtomicInteger totalFiles = new AtomicInteger(0);

        for (File file : files) {
            processFile(file.toPath(), totalMatches, deleteMode);
            totalFiles.incrementAndGet();
        }

        System.out.println("\nScrub complete.");
        System.out.println("Files processed: " + totalFiles.get());
        System.out.println("Bot messages " + (deleteMode ? "deleted" : "found") + ": " + totalMatches.get());
    }

    private static void processFile(Path path, AtomicInteger totalMatches, boolean deleteMode) {
        System.out.println("Processing: " + path.getFileName());
        try {
            List<String> lines = Files.readAllLines(path);
            List<String> cleanLines = new ArrayList<>();
            int matchesInFile = 0;

            boolean inBotMessage = false;
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                String trimmed = line.trim();
                if (trimmed.startsWith("<") && trimmed.contains("> ")) {
                    inBotMessage = MarkovManager.isBotPrefix(trimmed);
                }

                if (inBotMessage) {
                    System.out.println("  [MATCH] L" + (i + 1) + ": " + line);
                    matchesInFile++;
                    totalMatches.incrementAndGet();
                } else {
                    cleanLines.add(line);
                }
            }

            if (deleteMode && matchesInFile > 0) {
                Files.write(path, cleanLines);
                System.out.println("  [FIXED] Rewrote " + path.getFileName() + " without " + matchesInFile + " lines.");
            }
        } catch (IOException e) {
            System.err.println("Error processing " + path + ": " + e.getMessage());
        }
    }
}
