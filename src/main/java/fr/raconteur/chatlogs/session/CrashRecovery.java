package fr.raconteur.chatlogs.session;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

import fr.raconteur.chatlogs.ChatLogsMod;
import fr.raconteur.chatlogs.database.SessionDatabase;
import fr.raconteur.chatlogs.process.ProcessCleaner;

/**
 * Handles recovery of chat log files and sessions that were interrupted by crashes.
 * Updated to work with process-based writer architecture.
 */
public class CrashRecovery {
    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * Perform full recovery during mod initialization.
     * This includes:
     * 1. Cleaning up orphaned writer processes
     * 2. Recovering incomplete SQLite sessions
     * 3. Recovering incomplete TXT files
     */
    public static void performRecovery() {
        ChatLogsMod.LOGGER.info("Starting crash recovery...");

        // 1. Kill and clean up orphaned writer processes
        ChatLogsMod.LOGGER.debug("Checking for orphaned writer processes...");
        ProcessCleaner.cleanupOrphanedWriters();

        // 2. Recover SQLite sessions
        ChatLogsMod.LOGGER.debug("Recovering SQLite sessions...");
        recoverSqliteSessions();

        // 3. Recover incomplete TXT files
        ChatLogsMod.LOGGER.debug("Recovering incomplete TXT files...");
        recoverIncompleteTxtFiles();

        ChatLogsMod.LOGGER.info("Crash recovery completed");
    }

    /**
     * Check for SQLite sessions that weren't properly closed and close them.
     */
    private static void recoverSqliteSessions() {
        try {
            SessionDatabase db = SessionDatabase.getInstance();
            List<SessionDatabase.SessionData> sessions = db.getAllSessions();

            int recovered = 0;
            for (SessionDatabase.SessionData session : sessions) {
                // If end_time is 0 or null, the session wasn't properly closed
                if (session.endTime == 0) {
                    ChatLogsMod.LOGGER.warn("Found unclosed SQLite session: {} (ID: {})",
                        session.sessionName, session.id);

                    // Close the session with current timestamp
                    db.endSession(session.id);
                    recovered++;

                    ChatLogsMod.LOGGER.info("  ✓ Closed orphaned SQLite session: {} (ID: {})",
                        session.sessionName, session.id);
                }
            }

            if (recovered > 0) {
                ChatLogsMod.LOGGER.info("SQLite recovery completed: {} sessions closed", recovered);
            } else {
                ChatLogsMod.LOGGER.debug("No orphaned SQLite sessions found");
            }

        } catch (Exception e) {
            ChatLogsMod.LOGGER.error("Failed to recover SQLite sessions", e);
            throw new RuntimeException("Critical error during SQLite session recovery", e);
        }
    }

    /**
     * Find and recover incomplete TXT files.
     * This catches cases where the writer process itself crashed
     * or was killed before writing the footer.
     */
    private static void recoverIncompleteTxtFiles() {
        File[] txtFiles = SimpleSessionRecorder.CHATLOG_FOLDER.listFiles((dir, name) ->
            name.endsWith(".txt"));

        if (txtFiles == null || txtFiles.length == 0) {
            ChatLogsMod.LOGGER.debug("No TXT files found for recovery");
            return;
        }

        int recovered = 0;
        for (File txtFile : txtFiles) {
            // Only check files modified recently (within 24 hours)
            long lastModified = txtFile.lastModified();
            long hoursSinceModified = (System.currentTimeMillis() - lastModified) / (1000 * 60 * 60);

            if (hoursSinceModified > 24) {
                continue; // Skip old files
            }

            if (!hasProperEnding(txtFile)) {
                ChatLogsMod.LOGGER.warn("Found incomplete TXT file: {}", txtFile.getName());
                if (appendCrashMarkerToTxt(txtFile)) {
                    recovered++;
                    ChatLogsMod.LOGGER.info("  ✓ Recovered incomplete TXT file: {}", txtFile.getName());
                } else {
                    ChatLogsMod.LOGGER.error("  ✗ Failed to recover TXT file: {}", txtFile.getName());
                }
            }
        }

        if (recovered > 0) {
            ChatLogsMod.LOGGER.info("TXT recovery completed: {} files recovered", recovered);
        } else {
            ChatLogsMod.LOGGER.debug("No incomplete TXT files found");
        }
    }

    /**
     * Check if a TXT file has a proper ending marker.
     */
    private static boolean hasProperEnding(File txtFile) {
        try (RandomAccessFile file = new RandomAccessFile(txtFile, "r")) {
            long fileSize = file.length();
            if (fileSize == 0) {
                return false;
            }

            // Read last 500 bytes (should contain ending)
            long seekPos = Math.max(0, fileSize - 500);
            file.seek(seekPos);
            byte[] buffer = new byte[(int)(fileSize - seekPos)];
            file.readFully(buffer);
            String ending = new String(buffer, StandardCharsets.UTF_8);

            // Check for various ending markers
            return ending.contains("Session ended:") ||
                   ending.contains("ENDED UNEXPECTEDLY") ||
                   ending.contains("WRITER PROCESS CRASHED") ||
                   ending.contains("=====================================\n"); // Footer line

        } catch (IOException e) {
            ChatLogsMod.LOGGER.error("Failed to check file ending: {}", txtFile.getName(), e);
            return false; // Assume needs recovery if we can't read it
        }
    }

    /**
     * Append a crash marker to an incomplete TXT file.
     */
    private static boolean appendCrashMarkerToTxt(File txtFile) {
        try (FileWriter writer = new FileWriter(txtFile, true)) {
            writer.write("\n");
            writer.write("=====================================\n");
            writer.write("⚠️  WRITER PROCESS CRASHED  ⚠️\n");
            writer.write("Writer process was terminated unexpectedly\n");
            writer.write("Recovery performed by main process\n");
            writer.write("Recovery time: " + LocalDateTime.now().format(TIMESTAMP_FORMAT) + "\n");
            writer.write("=====================================\n");

            ChatLogsMod.LOGGER.debug("Successfully appended crash marker to: {}", txtFile.getName());
            return true;

        } catch (IOException e) {
            ChatLogsMod.LOGGER.error("Failed to append crash marker to: {}", txtFile.getName(), e);
            return false;
        }
    }

    /**
     * Get statistics about potential recovery files.
     */
    public static RecoveryStats getRecoveryStats() {
        int incompleteTxtFiles = 0;
        int unclosedSessions = 0;

        // Count incomplete TXT files
        File[] txtFiles = SimpleSessionRecorder.CHATLOG_FOLDER.listFiles((dir, name) ->
            name.endsWith(".txt"));

        if (txtFiles != null) {
            for (File txtFile : txtFiles) {
                long hoursSinceModified = (System.currentTimeMillis() - txtFile.lastModified()) / (1000 * 60 * 60);
                if (hoursSinceModified <= 24 && !hasProperEnding(txtFile)) {
                    incompleteTxtFiles++;
                }
            }
        }

        // Count unclosed SQLite sessions
        try {
            SessionDatabase db = SessionDatabase.getInstance();
            List<SessionDatabase.SessionData> sessions = db.getAllSessions();
            for (SessionDatabase.SessionData session : sessions) {
                if (session.endTime == 0) {
                    unclosedSessions++;
                }
            }
        } catch (Exception e) {
            ChatLogsMod.LOGGER.error("Failed to get recovery stats", e);
        }

        return new RecoveryStats(incompleteTxtFiles, unclosedSessions);
    }

    /**
     * Statistics about recovery status.
     */
    public static class RecoveryStats {
        public final int incompleteTxtFiles;
        public final int unclosedSessions;

        public RecoveryStats(int incompleteTxtFiles, int unclosedSessions) {
            this.incompleteTxtFiles = incompleteTxtFiles;
            this.unclosedSessions = unclosedSessions;
        }

        public boolean hasRecoveryNeeded() {
            return incompleteTxtFiles > 0 || unclosedSessions > 0;
        }

        @Override
        public String toString() {
            return String.format("RecoveryStats{incompleteTxt=%d, unclosedSessions=%d}",
                incompleteTxtFiles, unclosedSessions);
        }
    }
}
