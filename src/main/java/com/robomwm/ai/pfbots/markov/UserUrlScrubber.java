package com.robomwm.ai.pfbots.markov;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utility to scrub URLs shared by a specific user from brain logs.
 * It gathers URLs from the per-channel ai.log and removes lines containing
 * them from the same channel's brain file.
 */
public class UserUrlScrubber {
    private static final String BRAIN_DIR = "data/markov";
    private static final Pattern URL_PATTERN = Pattern.compile("https?://\\S+");

    public static void main(String[] args) {
        String targetUser = null;
        boolean deleteMode = false;

        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--scrubuserurls") && i + 1 < args.length) {
                targetUser = args[i + 1];
            } else if (args[i].equals("--delete")) {
                deleteMode = true;
            }
        }

        if (targetUser == null) {
            System.out.println("Usage: --scrubuserurls <username> [--delete]");
            return;
        }

        File folder = new File(BRAIN_DIR);
        if (!folder.exists() || !folder.isDirectory()) {
            System.out.println("Brain directory not found: " + BRAIN_DIR);
            return;
        }

        // Per-channel layout: data/markov/<channelId>/ai.log
        File[] dirs = folder.listFiles(File::isDirectory);
        List<File> logFiles = new ArrayList<>();
        if (dirs != null) {
            for (File dir : dirs) {
                File log = new File(dir, "ai.log");
                if (log.exists()) logFiles.add(log);
            }
        }
        if (logFiles.isEmpty()) {
            System.out.println("No ai.log files found.");
            return;
        }

        String userPrefix = "<" + targetUser + ">";
        System.out.println("Starting user URL scrub for: " + targetUser + " (" + (deleteMode ? "DELETE" : "CHECK") + " mode)...");

        int totalBrainMatches = 0;
        int totalFilesProcessed = 0;

        for (File logFile : logFiles) {
            totalBrainMatches += processChannel(logFile, userPrefix, deleteMode);
            totalFilesProcessed++;
        }

        System.out.println("\nScrub complete.");
        System.out.println("Files processed: " + totalFilesProcessed);
        System.out.println("Brain lines " + (deleteMode ? "removed" : "found") + ": " + totalBrainMatches);
    }

    private static int processChannel(File logFile, String userPrefix, boolean deleteMode) {
        String channelId = logFile.getParentFile().getName();
        File brainFile = new File(logFile.getParent(), "brain");

        if (!brainFile.exists()) {
            return 0;
        }

        try {
            List<String> aiLines = Files.readAllLines(logFile.toPath());
            Set<String> urlsToScrub = new HashSet<>();

            boolean inTargetUserMessage = false;
            for (String line : aiLines) {
                String trimmed = line.trim();
                // Check if this line starts a new message header
                if (trimmed.startsWith("<") && trimmed.contains("> ")) {
                    inTargetUserMessage = trimmed.startsWith(userPrefix);
                }

                if (inTargetUserMessage) {
                    Matcher matcher = URL_PATTERN.matcher(line);
                    while (matcher.find()) {
                        urlsToScrub.add(matcher.group());
                    }
                }
            }

            if (urlsToScrub.isEmpty()) {
                return 0;
            }

            System.out.println("Processing " + channelId + ": Found " + urlsToScrub.size() + " unique URLs from " + userPrefix);

            List<String> brainLines = Files.readAllLines(brainFile.toPath());
            List<String> cleanBrainLines = new ArrayList<>();
            int matchesInFile = 0;

            for (String line : brainLines) {
                boolean containsScrubbedUrl = false;
                for (String url : urlsToScrub) {
                    if (line.contains(url)) {
                        containsScrubbedUrl = true;
                        break;
                    }
                }

                if (containsScrubbedUrl) {
                    System.out.println("  [MATCH] " + brainFile.getName() + ": " + line.trim());
                    matchesInFile++;
                } else {
                    cleanBrainLines.add(line);
                }
            }

            if (deleteMode && matchesInFile > 0) {
                Files.write(brainFile.toPath(), cleanBrainLines);
                System.out.println("  [FIXED] Rewrote " + brainFile.getName() + ", removed " + matchesInFile + " lines.");
            } else if (matchesInFile > 0) {
                System.out.println("  [FOUND] " + matchesInFile + " lines in " + brainFile.getName() + " would be removed.");
            }

            return matchesInFile;

        } catch (IOException e) {
            System.err.println("Error processing channel " + channelId + ": " + e.getMessage());
            return 0;
        }
    }
}
