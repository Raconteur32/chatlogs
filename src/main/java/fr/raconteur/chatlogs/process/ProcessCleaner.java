package fr.raconteur.chatlogs.process;

import fr.raconteur.chatlogs.ChatLogsMod;
import fr.raconteur.chatlogs.ipc.ProcessSignalIO;
import fr.raconteur.chatlogs.queue.AckFile;
import fr.raconteur.chatlogs.queue.QueueReader;
import fr.raconteur.chatlogs.utils.ProcessHelper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Handles cleanup of orphaned writer processes and stale queue files.
 * Implements graceful termination with progress monitoring.
 */
public class ProcessCleaner {
    private static final Path QUEUE_DIR = Paths.get("chatlogs", "queue");
    private static final Path IPC_DIR = Paths.get("chatlogs", "ipc");

    private static final int GRACEFUL_SHUTDOWN_TIMEOUT_MS = 30000; // 30 seconds
    private static final int STALL_TIMEOUT_MS = 5000;              // 5 seconds without progress

    /**
     * Clean up orphaned writer processes on startup.
     * This is called when the mod initializes to clean up any processes
     * from previous crashed sessions.
     */
    public static void cleanupOrphanedWriters() {
        ChatLogsMod.LOGGER.info("Checking for orphaned writer processes...");

        if (!Files.exists(IPC_DIR)) {
            ChatLogsMod.LOGGER.debug("IPC directory doesn't exist, no cleanup needed");
            return;
        }

        try {
            // Find all writer info files
            List<Path> writerInfoFiles = Files.list(IPC_DIR)
                .filter(p -> p.getFileName().toString().startsWith("writer_"))
                .filter(p -> p.getFileName().toString().endsWith(".info"))
                .collect(Collectors.toList());

            int orphanedCount = 0;
            for (Path infoFile : writerInfoFiles) {
                String fileName = infoFile.getFileName().toString();
                String sessionIdStr = fileName.replace("writer_", "").replace(".info", "");

                try {
                    long sessionId = Long.parseLong(sessionIdStr);
                    if (cleanupOrphanedWriter(sessionId)) {
                        orphanedCount++;
                    }
                } catch (NumberFormatException e) {
                    ChatLogsMod.LOGGER.warn("Invalid writer info filename: {}", fileName);
                }
            }

            if (orphanedCount > 0) {
                ChatLogsMod.LOGGER.info("Cleaned up {} orphaned writer process(es)", orphanedCount);
            } else {
                ChatLogsMod.LOGGER.debug("No orphaned writer processes found");
            }

        } catch (IOException e) {
            ChatLogsMod.LOGGER.error("Error during orphaned writer cleanup", e);
        }
    }

    /**
     * Clean up a specific orphaned writer process.
     *
     * @param sessionId session ID to check
     * @return true if an orphaned writer was cleaned up
     */
    private static boolean cleanupOrphanedWriter(long sessionId) {
        // Get writer process info
        WriterProcessInfo writerInfo = getWriterProcessInfo(sessionId);
        if (writerInfo == null) {
            ChatLogsMod.LOGGER.debug("No valid writer info for session {}", sessionId);
            cleanupSessionFiles(sessionId);
            return false;
        }

        // Check if writer process is still alive
        if (!ProcessHelper.isProcessAliveWithStartTime(writerInfo.writerPid, writerInfo.writerStartTime)) {
            ChatLogsMod.LOGGER.debug("Writer process {} for session {} is not alive",
                writerInfo.writerPid, sessionId);
            cleanupSessionFiles(sessionId);
            return false;
        }

        // Check if parent process is still alive
        if (ProcessHelper.isProcessAliveWithStartTime(writerInfo.parentPid, writerInfo.parentStartTime)) {
            ChatLogsMod.LOGGER.debug("Parent process {} for session {} is still alive, writer is not orphaned",
                writerInfo.parentPid, sessionId);
            return false;
        }

        // Writer IS orphaned - begin graceful shutdown
        ChatLogsMod.LOGGER.warn("Found orphaned writer process {} for session {} (parent {} died)",
            writerInfo.writerPid, sessionId, writerInfo.parentPid);

        // Check progress
        ProgressInfo progress = getWriterProgress(sessionId);
        if (progress != null) {
            ChatLogsMod.LOGGER.info("Writer progress: {}/{} messages processed",
                progress.lastProcessedId, progress.totalMessages);

            if (progress.lastProcessedId < progress.totalMessages) {
                ChatLogsMod.LOGGER.info("Giving writer process time to finish processing {} remaining messages",
                    progress.totalMessages - progress.lastProcessedId);

                // Attempt graceful shutdown
                if (attemptGracefulShutdown(writerInfo, sessionId, progress)) {
                    ChatLogsMod.LOGGER.info("Orphaned writer {} completed gracefully", writerInfo.writerPid);
                    cleanupSessionFiles(sessionId);
                    return true;
                }
            }
        }

        // If we get here, either all messages are processed or graceful shutdown failed
        ChatLogsMod.LOGGER.warn("Force terminating orphaned writer process {}", writerInfo.writerPid);
        ProcessHelper.destroyProcessForcibly(writerInfo.writerPid);
        cleanupSessionFiles(sessionId);

        return true;
    }

    /**
     * Attempt to gracefully shut down an orphaned writer process.
     *
     * @param writerInfo writer process information
     * @param sessionId  session ID
     * @param progress   current progress information
     * @return true if shutdown completed gracefully
     */
    private static boolean attemptGracefulShutdown(WriterProcessInfo writerInfo, long sessionId, ProgressInfo progress) {
        // Send shutdown signal
        ProcessSignalIO.postAsOtherProcess("shutdown_request_" + sessionId, writerInfo.writerPid);

        long startTime = System.currentTimeMillis();
        long lastAckValue = progress.lastProcessedId;
        int stallCount = 0;

        while (System.currentTimeMillis() - startTime < GRACEFUL_SHUTDOWN_TIMEOUT_MS) {
            // Check if process exited
            if (!ProcessHelper.isProcessAlive(writerInfo.writerPid)) {
                ChatLogsMod.LOGGER.debug("Writer process exited");
                return true;
            }

            // Check for completion signal
            if (ProcessSignalIO.exists("writer_stopped_" + sessionId, writerInfo.writerPid)) {
                ChatLogsMod.LOGGER.debug("Writer sent completion signal");
                return true;
            }

            // Check if making progress
            AckFile ackFile = new AckFile(sessionId);
            if (ackFile.exists()) {
                long currentAck = ackFile.getLastProcessed();
                if (currentAck > lastAckValue) {
                    ChatLogsMod.LOGGER.debug("Writer making progress: processed {} messages", currentAck);
                    lastAckValue = currentAck;
                    stallCount = 0;
                } else {
                    stallCount++;
                    if (stallCount > (STALL_TIMEOUT_MS / 100)) {
                        ChatLogsMod.LOGGER.warn("Writer appears stalled (no progress for {}ms)", STALL_TIMEOUT_MS);
                        return false;
                    }
                }
            }

            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }

        ChatLogsMod.LOGGER.warn("Graceful shutdown timeout after {}ms", GRACEFUL_SHUTDOWN_TIMEOUT_MS);
        return false;
    }

    /**
     * Get writer process information from signal file.
     */
    private static WriterProcessInfo getWriterProcessInfo(long sessionId) {
        Optional<String> writerInfo = ProcessSignalIO.getInfo("writer_" + sessionId);
        if (writerInfo.isEmpty()) {
            return null;
        }

        try {
            // Format: "writerPid:writerStartTime:parentPid:parentStartTime"
            String[] parts = writerInfo.get().split(":");
            if (parts.length != 4) {
                return null;
            }

            long writerPid = Long.parseLong(parts[0]);
            long writerStartTime = Long.parseLong(parts[1]);
            long parentPid = Long.parseLong(parts[2]);
            long parentStartTime = Long.parseLong(parts[3]);

            // Verify writer PID matches start time (not reused)
            if (ProcessHelper.getProcessStartTime(writerPid) != writerStartTime) {
                return null;
            }

            return new WriterProcessInfo(writerPid, writerStartTime, parentPid, parentStartTime);

        } catch (Exception e) {
            ChatLogsMod.LOGGER.error("Error parsing writer info for session {}", sessionId, e);
            return null;
        }
    }

    /**
     * Get current progress information for a writer process.
     */
    private static ProgressInfo getWriterProgress(long sessionId) {
        try {
            AckFile ackFile = new AckFile(sessionId);
            QueueReader queueReader = new QueueReader(sessionId);

            if (!ackFile.exists() || !queueReader.exists()) {
                return null;
            }

            long lastProcessedId = ackFile.getLastProcessed();
            long totalMessages = queueReader.countTotalMessages();

            return new ProgressInfo(lastProcessedId, totalMessages);

        } catch (Exception e) {
            ChatLogsMod.LOGGER.error("Error getting progress for session {}", sessionId, e);
            return null;
        }
    }

    /**
     * Clean up all files related to a session.
     */
    private static void cleanupSessionFiles(long sessionId) {
        // Clean up IPC signal files
        ProcessSignalIO.deleteInfo("writer_" + sessionId);
        // Note: Can't easily delete per-process signals without knowing PIDs

        // Clean up queue files
        try {
            if (Files.exists(QUEUE_DIR)) {
                Path queueFile = QUEUE_DIR.resolve(sessionId + "_messages.jsonl");
                Path ackFile = QUEUE_DIR.resolve(sessionId + "_ack.tmp");

                Files.deleteIfExists(queueFile);
                Files.deleteIfExists(ackFile);

                ChatLogsMod.LOGGER.debug("Cleaned up queue files for session {}", sessionId);
            }
        } catch (IOException e) {
            ChatLogsMod.LOGGER.error("Error cleaning up queue files for session {}", sessionId, e);
        }
    }

    /**
     * Writer process information.
     */
    private static class WriterProcessInfo {
        final long writerPid;
        final long writerStartTime;
        final long parentPid;
        final long parentStartTime;

        WriterProcessInfo(long writerPid, long writerStartTime, long parentPid, long parentStartTime) {
            this.writerPid = writerPid;
            this.writerStartTime = writerStartTime;
            this.parentPid = parentPid;
            this.parentStartTime = parentStartTime;
        }
    }

    /**
     * Progress information for a writer process.
     */
    private static class ProgressInfo {
        final long lastProcessedId;
        final long totalMessages;

        ProgressInfo(long lastProcessedId, long totalMessages) {
            this.lastProcessedId = lastProcessedId;
            this.totalMessages = totalMessages;
        }
    }
}
