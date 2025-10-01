package fr.raconteur.chatlogs.writer;

import fr.raconteur.chatlogs.ipc.ProcessSignalIO;
import fr.raconteur.chatlogs.queue.AckFile;
import fr.raconteur.chatlogs.queue.QueueReader;
import fr.raconteur.chatlogs.queue.QueuedMessage;
import fr.raconteur.chatlogs.utils.ProcessHelper;

import java.io.File;
import java.util.List;
import java.util.Optional;

/**
 * Separate writer process application that consumes queued chat messages
 * and writes them to TXT files and SQLite database.
 * <p>
 * This process runs independently from the main Minecraft process and survives
 * game crashes, ensuring reliable log writing and proper session finalization.
 */
public class ChatLogWriterApp {
    private static final int POLL_INTERVAL_MS = 100;           // Check for new messages every 100ms
    private static final int HEARTBEAT_TIMEOUT_MS = 10000;     // 10 seconds without heartbeat = potential freeze
    private static final int FREEZE_TIMEOUT_MS = 300000;       // 5 minutes frozen = treat as crashed
    private static final int HEARTBEAT_CHECK_INTERVAL_MS = 2000; // Check heartbeat every 2 seconds

    // Command line arguments (parsed in main)
    private static long sessionId;
    private static long parentPid;
    private static long parentStartTime;
    private static String sessionName;
    private static boolean isMultiplayer;
    private static String txtFilePath;
    private static long sessionStartTime;

    // Writer components
    private static QueueReader queueReader;
    private static AckFile ackFile;
    private static TxtFileWriter txtWriter;
    private static SqliteWriter sqliteWriter;

    // State tracking
    private static long lastProcessedId = 0;
    private static long lastHeartbeatTime = System.currentTimeMillis();
    private static long lastHeartbeatCheckTime = System.currentTimeMillis();
    private static boolean parentFrozen = false;

    public static void main(String[] args) {
        // Set up uncaught exception handler for emergency cleanup
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            System.err.println("FATAL: Uncaught exception in writer process");
            throwable.printStackTrace();
            emergencyFinalize();
            System.exit(1);
        });

        try {
            // Parse command line arguments
            parseArguments(args);

            System.out.println("ChatLogWriterApp starting for session " + sessionId);
            System.out.println("  Parent PID: " + parentPid + " (start time: " + parentStartTime + ")");
            System.out.println("  Session: " + sessionName);
            System.out.println("  TXT file: " + txtFilePath);

            // Initialize components
            initializeComponents();

            // Register writer process info for cleanup purposes
            registerWriterInfo();

            // Signal that writer is ready
            ProcessSignalIO.post("writer_ready_" + sessionId);
            System.out.println("Writer process ready, entering main loop");

            // Enter main processing loop
            mainLoop();

        } catch (Exception e) {
            System.err.println("Error in writer process: " + e.getMessage());
            e.printStackTrace();
            emergencyFinalize();
            System.exit(1);
        }
    }

    /**
     * Parse command line arguments.
     */
    private static void parseArguments(String[] args) {
        for (int i = 0; i < args.length - 1; i++) {
            switch (args[i]) {
                case "--session-id":
                    sessionId = Long.parseLong(args[++i]);
                    break;
                case "--parent-pid":
                    parentPid = Long.parseLong(args[++i]);
                    break;
                case "--parent-start-time":
                    parentStartTime = Long.parseLong(args[++i]);
                    break;
                case "--session-name":
                    sessionName = args[++i];
                    break;
                case "--is-multiplayer":
                    isMultiplayer = Boolean.parseBoolean(args[++i]);
                    break;
                case "--txt-file-path":
                    txtFilePath = args[++i];
                    break;
                case "--session-start-time":
                    sessionStartTime = Long.parseLong(args[++i]);
                    break;
            }
        }

        // Validate required arguments
        if (sessionId == 0 || parentPid == 0 || parentStartTime == 0 ||
            sessionName == null || txtFilePath == null || sessionStartTime == 0) {
            throw new IllegalArgumentException("Missing required command line arguments");
        }
    }

    /**
     * Initialize all writer components.
     */
    private static void initializeComponents() throws Exception {
        queueReader = new QueueReader(sessionId);
        ackFile = new AckFile(sessionId);
        txtWriter = new TxtFileWriter(new File(txtFilePath), sessionStartTime);
        sqliteWriter = new SqliteWriter(sessionId);

        // Get last processed ID from ack file (resume if needed)
        lastProcessedId = ackFile.getLastProcessed();
        if (lastProcessedId > 0) {
            System.out.println("Resuming from message ID " + lastProcessedId);
        }

        // Write session header to TXT file
        txtWriter.writeSessionHeader(sessionName);
    }

    /**
     * Register this writer process info for cleanup purposes.
     */
    private static void registerWriterInfo() {
        long writerPid = ProcessHelper.getCurrentProcessId();
        long writerStartTime = ProcessHelper.getCurrentProcessStartTime();

        String writerInfo = String.format("%d:%d:%d:%d",
            writerPid, writerStartTime, parentPid, parentStartTime);

        ProcessSignalIO.postInfo("writer_" + sessionId, writerInfo);
        System.out.println("Registered writer info: PID " + writerPid);
    }

    /**
     * Main processing loop.
     */
    private static void mainLoop() throws Exception {
        while (true) {
            // 1. Check if parent process crashed (highest priority)
            if (!isParentAlive()) {
                System.out.println("Parent process crashed, finalizing session");
                finalizeSessionAsCrashed();
                return;
            }

            // 2. Check heartbeat (but don't exit immediately on freeze)
            checkHeartbeat();

            // 3. Check for shutdown signal
            if (isShutdownRequested()) {
                System.out.println("Shutdown requested, processing remaining messages");
                processRemainingMessages();
                finalizeSessionGracefully();
                return;
            }

            // 4. Process new messages from queue
            processNewMessages();

            // 5. Sleep before next iteration
            Thread.sleep(POLL_INTERVAL_MS);
        }
    }

    /**
     * Check if parent process is still alive.
     */
    private static boolean isParentAlive() {
        if (!ProcessHelper.isProcessAlive(parentPid)) {
            return false;
        }

        long actualStartTime = ProcessHelper.getProcessStartTime(parentPid);
        if (actualStartTime != parentStartTime) {
            System.out.println("Parent PID reused by another process");
            return false;
        }

        return true;
    }

    /**
     * Check heartbeat from parent process.
     */
    private static void checkHeartbeat() {
        long currentTime = System.currentTimeMillis();

        // Only check periodically to reduce file I/O
        if (currentTime - lastHeartbeatCheckTime < HEARTBEAT_CHECK_INTERVAL_MS) {
            return;
        }
        lastHeartbeatCheckTime = currentTime;

        // Check for heartbeat signal
        Optional<String> heartbeat = ProcessSignalIO.get("heartbeat_" + sessionId, parentPid);
        if (heartbeat.isPresent()) {
            try {
                long heartbeatTimestamp = Long.parseLong(heartbeat.get());
                lastHeartbeatTime = heartbeatTimestamp;

                if (parentFrozen) {
                    System.out.println("Parent process resumed after freeze");
                    parentFrozen = false;
                }
            } catch (NumberFormatException e) {
                System.err.println("Invalid heartbeat format: " + heartbeat.get());
            }
        }

        // Check if parent is frozen
        long timeSinceHeartbeat = currentTime - lastHeartbeatTime;
        if (timeSinceHeartbeat > HEARTBEAT_TIMEOUT_MS) {
            if (!parentFrozen) {
                System.out.println("WARNING: Parent process appears frozen (no heartbeat for " +
                    timeSinceHeartbeat + "ms)");
                parentFrozen = true;
            }

            // If frozen for too long, treat as crashed
            if (timeSinceHeartbeat > FREEZE_TIMEOUT_MS) {
                System.out.println("Parent frozen for " + (FREEZE_TIMEOUT_MS / 1000) +
                    " seconds, treating as crashed");
                // Will be handled by caller
                throw new RuntimeException("Parent process frozen for too long");
            }
        }
    }

    /**
     * Check if graceful shutdown was requested.
     */
    private static boolean isShutdownRequested() {
        return ProcessSignalIO.exists("shutdown_request_" + sessionId, parentPid);
    }

    /**
     * Process new messages from the queue.
     */
    private static void processNewMessages() throws Exception {
        List<QueuedMessage> newMessages = queueReader.readMessagesFrom(lastProcessedId);

        if (newMessages.isEmpty()) {
            return; // No new messages
        }

        System.out.println("Processing " + newMessages.size() + " new messages");

        // Write to TXT file (one at a time for safety)
        for (QueuedMessage msg : newMessages) {
            txtWriter.writeMessage(msg);
        }

        // Write to SQLite (batch for performance)
        sqliteWriter.writeMessages(newMessages);

        // Update last processed ID
        lastProcessedId = newMessages.get(newMessages.size() - 1).getId();
        ackFile.updateLastProcessed(lastProcessedId);
    }

    /**
     * Process any remaining messages in the queue.
     * Called during shutdown.
     */
    private static void processRemainingMessages() throws Exception {
        int totalProcessed = 0;
        while (true) {
            List<QueuedMessage> messages = queueReader.readMessagesFrom(lastProcessedId);
            if (messages.isEmpty()) {
                break;
            }

            // Write messages
            for (QueuedMessage msg : messages) {
                txtWriter.writeMessage(msg);
            }
            sqliteWriter.writeMessages(messages);

            lastProcessedId = messages.get(messages.size() - 1).getId();
            ackFile.updateLastProcessed(lastProcessedId);

            totalProcessed += messages.size();
        }

        if (totalProcessed > 0) {
            System.out.println("Processed " + totalProcessed + " remaining messages");
        }
    }

    /**
     * Finalize session gracefully (normal shutdown).
     */
    private static void finalizeSessionGracefully() throws Exception {
        long duration = System.currentTimeMillis() - sessionStartTime;

        txtWriter.writeSessionFooter(duration);
        sqliteWriter.endSession();

        cleanup();

        // Signal completion
        ProcessSignalIO.post("writer_stopped_" + sessionId);
        System.out.println("Session finalized gracefully");
    }

    /**
     * Finalize session after detecting parent crash.
     */
    private static void finalizeSessionAsCrashed() {
        try {
            txtWriter.writeParentCrashMarker();
            sqliteWriter.endSession();
            cleanup();
            System.out.println("Session finalized after parent crash");
        } catch (Exception e) {
            System.err.println("Error during crash finalization: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Emergency finalization (writer process crash).
     */
    private static void emergencyFinalize() {
        System.err.println("Performing emergency finalization");
        try {
            if (txtWriter != null) {
                txtWriter.writeParentCrashMarker(); // Best effort
            }
            if (sqliteWriter != null) {
                sqliteWriter.endSession();
            }
            cleanup();
        } catch (Exception e) {
            System.err.println("Error during emergency finalization: " + e.getMessage());
        }
    }

    /**
     * Clean up resources and signal files.
     */
    private static void cleanup() {
        try {
            if (txtWriter != null) txtWriter.close();
            if (sqliteWriter != null) sqliteWriter.close();
            if (queueReader != null) queueReader.close();
            if (ackFile != null) ackFile.delete();

            // Clean up signal files
            ProcessSignalIO.delete("writer_ready_" + sessionId, ProcessHelper.getCurrentProcessId());
            ProcessSignalIO.delete("heartbeat_" + sessionId, parentPid);
            ProcessSignalIO.delete("shutdown_request_" + sessionId, parentPid);
            ProcessSignalIO.deleteInfo("writer_" + sessionId);

            System.out.println("Cleanup completed");
        } catch (Exception e) {
            System.err.println("Error during cleanup: " + e.getMessage());
        }
    }
}
