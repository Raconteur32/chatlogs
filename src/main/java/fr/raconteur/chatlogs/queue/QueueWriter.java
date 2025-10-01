package fr.raconteur.chatlogs.queue;

import fr.raconteur.chatlogs.ChatLogsMod;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Writes queued messages to a JSON Lines file for processing by the writer process.
 * Thread-safe for single-producer usage (main game thread).
 */
public class QueueWriter implements AutoCloseable {
    private static final Path QUEUE_DIR = Paths.get("chatlogs", "queue");

    private final long sessionId;
    private final File queueFile;
    private final BufferedWriter writer;

    /**
     * Create a queue writer for the specified session.
     *
     * @param sessionId unique session identifier
     * @throws IOException if queue file cannot be created
     */
    public QueueWriter(long sessionId) throws IOException {
        this.sessionId = sessionId;

        // Ensure queue directory exists
        Files.createDirectories(QUEUE_DIR);

        // Create queue file
        this.queueFile = QUEUE_DIR.resolve(sessionId + "_messages.jsonl").toFile();
        this.writer = new BufferedWriter(new FileWriter(queueFile, false)); // Overwrite if exists

        ChatLogsMod.LOGGER.debug("Created queue writer for session {} at {}", sessionId, queueFile.getPath());
    }

    /**
     * Append a message to the queue file.
     * This method is fast and non-blocking - it only writes to the OS buffer.
     *
     * @param message message to append
     * @throws IOException if write fails
     */
    public synchronized void appendMessage(QueuedMessage message) throws IOException {
        writer.write(message.toJsonLine());
        writer.newLine();
        // Note: We don't flush here for performance. The OS will buffer writes.
        // Writer process will read from the file even if not fully flushed to disk yet.
    }

    /**
     * Force flush the queue to disk.
     * Normally not needed, but can be useful before critical operations.
     *
     * @throws IOException if flush fails
     */
    public synchronized void flush() throws IOException {
        writer.flush();
    }

    /**
     * Close the queue writer.
     * This flushes any remaining data and closes the file.
     */
    @Override
    public synchronized void close() {
        try {
            if (writer != null) {
                writer.flush();
                writer.close();
                ChatLogsMod.LOGGER.debug("Closed queue writer for session {}", sessionId);
            }
        } catch (IOException e) {
            ChatLogsMod.LOGGER.error("Error closing queue writer for session {}", sessionId, e);
        }
    }

    /**
     * Get the path to the queue file.
     *
     * @return queue file path
     */
    public File getQueueFile() {
        return queueFile;
    }

    /**
     * Get the session ID for this queue writer.
     *
     * @return session ID
     */
    public long getSessionId() {
        return sessionId;
    }
}
