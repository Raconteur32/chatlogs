package fr.raconteur.chatlogs.session;

import java.io.File;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import com.google.gson.JsonNull;
import com.mojang.serialization.JsonOps;

import fr.raconteur.chatlogs.ChatLogsMod;
import fr.raconteur.chatlogs.config.ChatLogsConfig;
import fr.raconteur.chatlogs.database.SessionDatabase;
import fr.raconteur.chatlogs.ipc.ProcessSignalIO;
import fr.raconteur.chatlogs.process.ChildProcessLogger;
import fr.raconteur.chatlogs.process.ProcessSpawner;
import fr.raconteur.chatlogs.queue.QueueWriter;
import fr.raconteur.chatlogs.queue.QueuedMessage;
import fr.raconteur.chatlogs.utils.ProcessHelper;
import net.minecraft.text.Text;
import net.minecraft.text.TextCodecs;
import net.minecraft.util.Util;

/**
 * Session recorder that uses a separate writer process for reliable log writing.
 * Features: process-based writing, crash resilience, automatic recovery, heartbeat monitoring
 */
public class SimpleSessionRecorder {
    private static final DateTimeFormatter FILE_TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
    private static final int HEARTBEAT_INTERVAL_MS = 2000; // Send heartbeat every 2 seconds
    private static final int WRITER_READY_TIMEOUT_MS = 5000; // Wait up to 5 seconds for writer to be ready
    private static final int SHUTDOWN_TIMEOUT_MS = 10000; // Wait up to 10 seconds for graceful shutdown

    // Chat logs folder
    public static final File CHATLOG_FOLDER = Util.make(() -> {
        File f = new File("chatlogs");
        boolean success;
        l:
        if(!f.exists()) {
            success = f.mkdir();
        } else if(!f.isDirectory()) {
            for(int i = 0; i < 10; i++) {
                String renameTo = ("chatlogs" + System.currentTimeMillis()) + i;
                if(f.renameTo(new File(renameTo))) {
                    ChatLogsMod.LOGGER.warn("A non-directory file named 'chatlogs' already exists, renaming to {}.", renameTo);
                    success = f.mkdir();
                    break l;
                }
            }

            ChatLogsMod.LOGGER.error("Failed to rename existing file {}, deleting", f.getAbsolutePath());
            if(f.delete()) {
                success = f.mkdir();
            } else {
                success = false;
            }
        } else {
            return f;
        }

        if(success) {
            return f;
        } else {
            ChatLogsMod.LOGGER.error("Unable to create directory for chat logs.");
            throw new RuntimeException("Unable to create directory for chat logs.");
        }
    });

    private static SimpleSessionRecorder current = null;

    private final String sessionName;
    private final boolean isMultiplayer;
    private final File logFile;
    private final long startTime;
    private final long sessionId; // SQLite session ID

    // Process-based writing components
    private Process writerProcess;
    private long writerPid;
    private long writerStartTime;
    private QueueWriter queueWriter;
    private long nextMessageId = 1;

    // Heartbeat
    private ScheduledExecutorService heartbeatExecutor;

    private SimpleSessionRecorder(String sessionName, boolean isMultiplayer) {
        this.sessionName = sessionName;
        this.isMultiplayer = isMultiplayer;
        this.startTime = System.currentTimeMillis();

        // Create log file with timestamp
        String timestamp = LocalDateTime.now().format(FILE_TIMESTAMP_FORMAT);
        String fileName = String.format("%s_%s.txt", sessionName.replaceAll("[^a-zA-Z0-9]", "_"), timestamp);
        this.logFile = new File(CHATLOG_FOLDER, fileName);

        // Create SQLite session
        long tempSessionId = -1;
        try {
            SessionDatabase db = SessionDatabase.getInstance();
            tempSessionId = db.createSession(sessionName, isMultiplayer, this.logFile.getAbsolutePath());
            ChatLogsMod.LOGGER.info("Created SQLite session: {} (ID: {})", sessionName, tempSessionId);
        } catch (Exception e) {
            ChatLogsMod.LOGGER.error("Failed to create SQLite session for: {}", sessionName, e);
            throw new RuntimeException("Critical error: Unable to create SQLite session", e);
        }
        this.sessionId = tempSessionId;

        // Initialize queue writer and spawn writer process
        if (!initializeWriterProcess()) {
            throw new RuntimeException("Failed to initialize writer process for session " + sessionId);
        }

        ChatLogsMod.LOGGER.info("Started chat logging session: {}", fileName);
    }

    /**
     * Initialize the queue writer and spawn the writer process.
     */
    private boolean initializeWriterProcess() {
        try {
            // 1. Create queue writer
            queueWriter = new QueueWriter(sessionId);

            // 2. Spawn writer process
            writerProcess = ProcessSpawner.spawnWriterProcess(
                sessionId,
                sessionName,
                isMultiplayer,
                logFile.getAbsolutePath(),
                startTime
            );

            writerPid = writerProcess.pid();
            writerStartTime = ProcessHelper.getProcessStartTime(writerPid);

            // 3. Capture writer process output
            ChildProcessLogger.captureOutput(writerProcess);

            // 4. Wait for writer to be ready
            boolean ready = ProcessSpawner.waitForWriterReady(sessionId, WRITER_READY_TIMEOUT_MS);
            if (!ready) {
                ChatLogsMod.LOGGER.error("Writer process did not send ready signal within timeout");
                cleanup();
                return false;
            }

            ChatLogsMod.LOGGER.info("Writer process ready (PID: {})", writerPid);

            // 5. Start heartbeat
            startHeartbeat();

            return true;

        } catch (IOException e) {
            ChatLogsMod.LOGGER.error("Failed to spawn writer process", e);
            cleanup();
            return false;
        }
    }

    /**
     * Start the heartbeat executor to periodically send heartbeat signals.
     */
    private void startHeartbeat() {
        heartbeatExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ChatLogs-Heartbeat");
            t.setDaemon(true);
            return t;
        });

        heartbeatExecutor.scheduleAtFixedRate(() -> {
            try {
                ProcessSignalIO.post("heartbeat_" + sessionId);
            } catch (Exception e) {
                ChatLogsMod.LOGGER.error("Failed to send heartbeat", e);
            }
        }, 0, HEARTBEAT_INTERVAL_MS, TimeUnit.MILLISECONDS);

        ChatLogsMod.LOGGER.debug("Started heartbeat for session {}", sessionId);
    }

    /**
     * Stop the heartbeat executor.
     */
    private void stopHeartbeat() {
        if (heartbeatExecutor != null) {
            heartbeatExecutor.shutdown();
            try {
                if (!heartbeatExecutor.awaitTermination(1, TimeUnit.SECONDS)) {
                    heartbeatExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                heartbeatExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
            ChatLogsMod.LOGGER.debug("Stopped heartbeat for session {}", sessionId);
        }
    }

    public static SimpleSessionRecorder start(String sessionName, boolean isMultiplayer) {
        if (current != null) {
            current.end();
        }

        current = new SimpleSessionRecorder(sessionName, isMultiplayer);
        return current;
    }

    public static SimpleSessionRecorder current() {
        return current;
    }

    public static void end() {
        if (current != null) {
            current.shutdown();
            current = null;
        }
    }

    /**
     * Log a chat message by queueing it for the writer process.
     */
    public void logMessage(Text message) {
        if (queueWriter == null) {
            ChatLogsMod.LOGGER.warn("Queue writer not initialized, dropping message");
            return;
        }

        try {
            // Extract data from message
            String messageText = message.getString();

            // Extract sender name using regex patterns
            String senderName = ChatLogsConfig.getInstance().extractSenderName(sessionName, messageText);

            // Serialize message to JSON using TextCodecs
            String messageJson = TextCodecs.CODEC.encodeStart(JsonOps.INSTANCE, message)
                .result()
                .orElse(JsonNull.INSTANCE)
                .toString();

            // Create queued message
            QueuedMessage queuedMsg = new QueuedMessage(
                nextMessageId++,
                System.currentTimeMillis(),
                messageText,
                messageJson,
                senderName
            );

            // Append to queue file (fast, non-blocking)
            queueWriter.appendMessage(queuedMsg);

        } catch (IOException e) {
            ChatLogsMod.LOGGER.error("Failed to queue message for session {}", sessionId, e);
            // Non-critical: message lost but doesn't crash game
        } catch (Exception e) {
            ChatLogsMod.LOGGER.error("Unexpected error queueing message", e);
        }
    }

    /**
     * Shutdown this recorder instance - stops heartbeat and signals writer process to finish.
     */
    private void shutdown() {
        ChatLogsMod.LOGGER.info("Shutting down session recorder for session {}", sessionId);

        // 1. Stop heartbeat
        stopHeartbeat();

        // 2. Close queue writer (no more messages will be queued)
        if (queueWriter != null) {
            queueWriter.close();
        }

        // 3. Send shutdown signal to writer process
        if (writerProcess != null && writerProcess.isAlive()) {
            ProcessSignalIO.post("shutdown_request_" + sessionId);

            // 4. Wait for writer to finish
            boolean completed = waitForWriterCompletion();

            if (!completed) {
                ChatLogsMod.LOGGER.warn("Writer process did not complete gracefully, force terminating");
                ProcessHelper.destroyProcessForcibly(writerPid);
            }
        }

        // 5. Clean up
        cleanup();

        ChatLogsMod.LOGGER.info("Session recorder shutdown complete for session {}", sessionId);
    }

    /**
     * Wait for the writer process to complete its work and exit.
     */
    private boolean waitForWriterCompletion() {
        long startTime = System.currentTimeMillis();

        while (System.currentTimeMillis() - startTime < SHUTDOWN_TIMEOUT_MS) {
            // Check if process exited
            if (!writerProcess.isAlive()) {
                ChatLogsMod.LOGGER.info("Writer process exited naturally");
                return true;
            }

            // Check for completion signal
            if (ProcessSignalIO.exists("writer_stopped_" + sessionId, writerPid)) {
                ChatLogsMod.LOGGER.info("Writer process sent completion signal");

                // Give it a moment to actually exit
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }

                return true;
            }

            // Sleep and check again
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }

        ChatLogsMod.LOGGER.warn("Writer process did not complete within {}ms", SHUTDOWN_TIMEOUT_MS);
        return false;
    }

    /**
     * Clean up resources and signal files.
     */
    private void cleanup() {
        // Close queue writer if not already closed
        if (queueWriter != null) {
            queueWriter.close();
            queueWriter = null;
        }

        // Clean up our signal files (writer process will clean up its own)
        ProcessSignalIO.delete("heartbeat_" + sessionId, ProcessHelper.getCurrentProcessId());
        ProcessSignalIO.delete("shutdown_request_" + sessionId, ProcessHelper.getCurrentProcessId());

        ChatLogsMod.LOGGER.debug("Cleanup completed for session {}", sessionId);
    }

    /**
     * Get the session ID.
     */
    public long getSessionId() {
        return sessionId;
    }

    /**
     * Get the session name.
     */
    public String getSessionName() {
        return sessionName;
    }

    /**
     * Get the log file.
     */
    public File getLogFile() {
        return logFile;
    }

    /**
     * Check if the writer process is still alive.
     */
    public boolean isWriterProcessAlive() {
        return writerProcess != null &&
               writerProcess.isAlive() &&
               ProcessHelper.isProcessAliveWithStartTime(writerPid, writerStartTime);
    }
}
