package com.robomwm.ai.pfbots.markov;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public class BothScrubber {
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

        // Per-channel layout: data/markov/<channelId>/brain and ai.log
        File[] dirs = folder.listFiles(File::isDirectory);
        List<File> brainFiles = new ArrayList<>();
        List<File> aiLogFiles = new ArrayList<>();
        if (dirs != null) {
            for (File dir : dirs) {
                File brain = new File(dir, "brain");
                if (brain.exists()) brainFiles.add(brain);
                File log = new File(dir, "ai.log");
                if (log.exists()) aiLogFiles.add(log);
            }
        }

        System.out.println("Starting total scrub (" + (deleteMode ? "DELETE" : "CHECK") + " mode)...");
        AtomicInteger totalMatches = new AtomicInteger(0);
        AtomicInteger totalFiles = new AtomicInteger(0);

        if (brainFiles != null) {
            for (File file : brainFiles) {
                processFile(file.toPath(), totalMatches, deleteMode);
                totalFiles.incrementAndGet();
            }
        }

        if (aiLogFiles != null) {
            for (File file : aiLogFiles) {
                processFile(file.toPath(), totalMatches, deleteMode);
                totalFiles.incrementAndGet();
            }
        }

        System.out.println("\nTotal scrub complete.");
        System.out.println("Files processed: " + totalFiles.get());
        System.out.println("Profane lines " + (deleteMode ? "deleted" : "found") + ": " + totalMatches.get());
    }

    private static void processFile(Path path, AtomicInteger totalMatches, boolean deleteMode) {
        System.out.println("Processing: " + path.getFileName());
        try {
            List<String> lines = Files.readAllLines(path);
            List<String> cleanLines = new ArrayList<>();
            int matchesInFile = 0;

            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                if (ProfanityFilter.containsProfanity(line)) {
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
