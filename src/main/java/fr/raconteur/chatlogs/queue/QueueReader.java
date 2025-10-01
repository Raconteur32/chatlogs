package fr.raconteur.chatlogs.queue;

import com.google.gson.JsonSyntaxException;
import fr.raconteur.chatlogs.ChatLogsMod;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads queued messages from a JSON Lines file.
 * Designed for use by the writer process to consume messages.
 */
public class QueueReader implements AutoCloseable {
    private static final Path QUEUE_DIR = Paths.get("chatlogs", "queue");

    private final long sessionId;
    private final File queueFile;

    /**
     * Create a queue reader for the specified session.
     *
     * @param sessionId unique session identifier
     */
    public QueueReader(long sessionId) {
        this.sessionId = sessionId;
        this.queueFile = QUEUE_DIR.resolve(sessionId + "_messages.jsonl").toFile();

        ChatLogsMod.LOGGER.debug("Created queue reader for session {} at {}", sessionId, queueFile.getPath());
    }

    /**
     * Read all messages from the queue file that have an ID greater than the specified last ID.
     * This allows resuming from where we left off.
     *
     * @param lastProcessedId ID of the last successfully processed message (0 to read from beginning)
     * @return list of new messages, ordered by ID
     * @throws IOException if read fails
     */
    public List<QueuedMessage> readMessagesFrom(long lastProcessedId) throws IOException {
        List<QueuedMessage> messages = new ArrayList<>();

        if (!queueFile.exists()) {
            ChatLogsMod.LOGGER.debug("Queue file does not exist yet for session {}", sessionId);
            return messages;
        }

        try (BufferedReader reader = new BufferedReader(new FileReader(queueFile))) {
            String line;
            int lineNumber = 0;

            while ((line = reader.readLine()) != null) {
                lineNumber++;

                if (line.trim().isEmpty()) {
                    continue; // Skip empty lines
                }

                try {
                    QueuedMessage message = QueuedMessage.fromJsonLine(line);

                    // Only include messages after the last processed ID
                    if (message.getId() > lastProcessedId) {
                        messages.add(message);
                    }

                } catch (JsonSyntaxException e) {
                    ChatLogsMod.LOGGER.error("Corrupted message at line {} in queue file for session {}: {}",
                        lineNumber, sessionId, e.getMessage());
                    // Continue reading - don't let one bad message stop us
                } catch (Exception e) {
                    ChatLogsMod.LOGGER.error("Error parsing message at line {} in queue file for session {}",
                        lineNumber, sessionId, e);
                    // Continue reading
                }
            }
        }

        if (!messages.isEmpty()) {
            ChatLogsMod.LOGGER.debug("Read {} new messages from queue for session {} (after ID {})",
                messages.size(), sessionId, lastProcessedId);
        }

        return messages;
    }

    /**
     * Count the total number of messages in the queue file.
     * Useful for progress tracking.
     *
     * @return total message count
     * @throws IOException if read fails
     */
    public long countTotalMessages() throws IOException {
        if (!queueFile.exists()) {
            return 0;
        }

        long count = 0;
        try (BufferedReader reader = new BufferedReader(new FileReader(queueFile))) {
            while (reader.readLine() != null) {
                count++;
            }
        }
        return count;
    }

    /**
     * Check if the queue file exists.
     *
     * @return true if queue file exists
     */
    public boolean exists() {
        return queueFile.exists();
    }

    /**
     * Get the path to the queue file.
     *
     * @return queue file
     */
    public File getQueueFile() {
        return queueFile;
    }

    /**
     * Get the session ID for this queue reader.
     *
     * @return session ID
     */
    public long getSessionId() {
        return sessionId;
    }

    @Override
    public void close() {
        // No resources to close - we open/close reader on each read
    }
}
