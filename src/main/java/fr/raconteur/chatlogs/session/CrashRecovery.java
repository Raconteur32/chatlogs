package fr.raconteur.chatlogs.session;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

import fr.raconteur.chatlogs.ChatLogsMod;
import fr.raconteur.chatlogs.database.SessionDatabase;

/**
 * Handles recovery of chat log files that were being written during a crash
 */
public class CrashRecovery {
    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final File UNSAVED_MARKER = new File(SimpleSessionRecorder.CHATLOG_FOLDER, "unsaved.marker");
    
    /**
     * Check for unsaved logs from previous session and handle them
     * Should be called during mod initialization
     */
    public static void performRecovery() {
        // First, check and close any SQLite sessions that weren't properly ended
        recoverSqliteSessions();
        
        if (!UNSAVED_MARKER.exists()) {
            ChatLogsMod.LOGGER.debug("No unsaved marker found, no recovery needed");
            return;
        }
        
        List<String> unsavedFiles = readUnsavedFiles();
        if (unsavedFiles.isEmpty()) {
            cleanupMarker();
            return;
        }
        
        ChatLogsMod.LOGGER.warn("Crash detected! Found {} unsaved chat log files from previous session:", 
                               unsavedFiles.size());
        
        int recovered = 0;
        int failed = 0;
        
        for (String filePath : unsavedFiles) {
            File file = new File(filePath);
            if (file.exists()) {
                if (finalizeUnsavedLog(file)) {
                    recovered++;
                    ChatLogsMod.LOGGER.info("  ✓ Recovered: {}", file.getName());
                } else {
                    failed++;
                    ChatLogsMod.LOGGER.error("  ✗ Failed to recover: {}", file.getName());
                }
            } else {
                ChatLogsMod.LOGGER.warn("  ? File no longer exists: {}", filePath);
            }
        }
        
        // Clean up marker after processing
        cleanupMarker();
        
        ChatLogsMod.LOGGER.info("Recovery complete: {} files recovered, {} failed", recovered, failed);
    }
    
    /**
     * Read all unsaved file paths from the marker file
     */
    private static List<String> readUnsavedFiles() {
        List<String> unsavedFiles = new ArrayList<>();
        
        try (Scanner scanner = new Scanner(new FileReader(UNSAVED_MARKER))) {
            while (scanner.hasNextLine()) {
                String line = scanner.nextLine().trim();
                if (!line.isEmpty()) {
                    unsavedFiles.add(line);
                }
            }
        } catch (IOException e) {
            ChatLogsMod.LOGGER.error("Failed to read unsaved marker file", e);
        }
        
        return unsavedFiles;
    }
    
    /**
     * Finalize an unsaved log file by adding crash information
     */
    private static boolean finalizeUnsavedLog(File logFile) {
        // Check if file already has an ending (maybe it was recovered before)
        if (hasProperEnding(logFile)) {
            ChatLogsMod.LOGGER.debug("File {} already has proper ending", logFile.getName());
            return true;
        }
        
        try (FileWriter writer = new FileWriter(logFile, true)) {
            writer.write("\n");
            writer.write("=====================================\n");
            writer.write("⚠️  SESSION ENDED UNEXPECTEDLY  ⚠️\n");
            writer.write("Crash detected and recovered automatically\n");
            writer.write("Recovery time: " + LocalDateTime.now().format(TIMESTAMP_FORMAT) + "\n");
            writer.write("=====================================\n");
            
            ChatLogsMod.LOGGER.debug("Successfully finalized crashed log: {}", logFile.getName());
            return true;
            
        } catch (IOException e) {
            ChatLogsMod.LOGGER.error("Failed to finalize crashed log: {}", logFile.getName(), e);
            return false;
        }
    }
    
    /**
     * Check if a log file already has a proper ending
     */
    private static boolean hasProperEnding(File logFile) {
        try (Scanner scanner = new Scanner(logFile)) {
            String lastLine = "";
            while (scanner.hasNextLine()) {
                lastLine = scanner.nextLine();
            }
            
            // Check if the last line indicates the file was properly closed
            return lastLine.contains("=====================================") || 
                   lastLine.contains("Session ended") ||
                   lastLine.contains("ENDED UNEXPECTEDLY");
                   
        } catch (IOException e) {
            ChatLogsMod.LOGGER.error("Failed to check file ending: {}", logFile.getName(), e);
            return false; // Assume it needs recovery if we can't read it
        }
    }
    
    /**
     * Clean up the unsaved marker file
     */
    private static void cleanupMarker() {
        if (UNSAVED_MARKER.exists()) {
            if (UNSAVED_MARKER.delete()) {
                ChatLogsMod.LOGGER.debug("Cleaned up unsaved marker file");
            } else {
                ChatLogsMod.LOGGER.warn("Failed to delete unsaved marker file");
            }
        }
    }
    
    /**
     * Emergency recovery method that can be called manually
     * Useful for debugging or manual recovery scenarios
     */
    public static void forceRecovery() {
        ChatLogsMod.LOGGER.info("Performing forced recovery of all potential unsaved logs...");
        
        // Look for .lock files that might indicate interrupted sessions
        File[] lockFiles = SimpleSessionRecorder.CHATLOG_FOLDER.listFiles(
            (dir, name) -> name.endsWith(".lock")
        );
        
        if (lockFiles != null) {
            for (File lockFile : lockFiles) {
                String logFileName = lockFile.getName().replace(".lock", "");
                File logFile = new File(SimpleSessionRecorder.CHATLOG_FOLDER, logFileName);
                
                if (logFile.exists() && !hasProperEnding(logFile)) {
                    ChatLogsMod.LOGGER.info("Found orphaned log file: {}", logFileName);
                    finalizeUnsavedLog(logFile);
                }
                
                // Clean up the lock file
                lockFile.delete();
            }
        }
    }
    
    /**
     * Get statistics about potential recovery files
     */
    public static RecoveryStats getRecoveryStats() {
        int unsavedCount = 0;
        int orphanedLocks = 0;
        
        if (UNSAVED_MARKER.exists()) {
            unsavedCount = readUnsavedFiles().size();
        }
        
        File[] lockFiles = SimpleSessionRecorder.CHATLOG_FOLDER.listFiles(
            (dir, name) -> name.endsWith(".lock")
        );
        
        if (lockFiles != null) {
            orphanedLocks = lockFiles.length;
        }
        
        return new RecoveryStats(unsavedCount, orphanedLocks);
    }
    
    /**
     * Statistics about recovery status
     */
    public static class RecoveryStats {
        public final int unsavedFiles;
        public final int orphanedLocks;
        
        public RecoveryStats(int unsavedFiles, int orphanedLocks) {
            this.unsavedFiles = unsavedFiles;
            this.orphanedLocks = orphanedLocks;
        }
        
        public boolean hasRecoveryNeeded() {
            return unsavedFiles > 0 || orphanedLocks > 0;
        }
        
        @Override
        public String toString() {
            return String.format("RecoveryStats{unsaved=%d, orphanedLocks=%d}", 
                               unsavedFiles, orphanedLocks);
        }
    }
    
    /**
     * Check for SQLite sessions that weren't properly closed and close them
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
}