package fr.raconteur.chatlogs.queue;

import fr.raconteur.chatlogs.ChatLogsMod;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

/**
 * Tracks the last successfully processed message ID for a session.
 * This allows the writer process to resume from where it left off if interrupted.
 */
public class AckFile {
    private static final Path QUEUE_DIR = Paths.get("chatlogs", "queue");

    private final long sessionId;
    private final Path ackFilePath;

    /**
     * Create an acknowledgment file tracker for the specified session.
     *
     * @param sessionId unique session identifier
     */
    public AckFile(long sessionId) {
        this.sessionId = sessionId;
        this.ackFilePath = QUEUE_DIR.resolve(sessionId + "_ack.tmp");
    }

    /**
     * Update the last processed message ID.
     * This overwrites the file with the new ID.
     *
     * @param messageId ID of the last successfully processed message
     * @throws IOException if write fails
     */
    public void updateLastProcessed(long messageId) throws IOException {
        // Ensure directory exists
        Files.createDirectories(QUEUE_DIR);

        // Write the message ID as a simple text file
        String content = Long.toString(messageId);
        Files.write(
            ackFilePath,
            content.getBytes(StandardCharsets.UTF_8),
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE
        );
    }

    /**
     * Get the last processed message ID.
     *
     * @return last processed message ID, or 0 if file doesn't exist or is invalid
     */
    public long getLastProcessed() {
        if (!Files.exists(ackFilePath)) {
            return 0;
        }

        try {
            String content = Files.readString(ackFilePath, StandardCharsets.UTF_8).trim();
            return Long.parseLong(content);
        } catch (IOException e) {
            ChatLogsMod.LOGGER.error("Error reading ack file for session {}", sessionId, e);
            return 0;
        } catch (NumberFormatException e) {
            ChatLogsMod.LOGGER.error("Invalid ack file content for session {}", sessionId, e);
            return 0;
        }
    }

    /**
     * Check if the ack file exists.
     *
     * @return true if ack file exists
     */
    public boolean exists() {
        return Files.exists(ackFilePath);
    }

    /**
     * Delete the ack file.
     * Called when session ends successfully.
     *
     * @return true if file was deleted, false if it didn't exist or couldn't be deleted
     */
    public boolean delete() {
        try {
            boolean deleted = Files.deleteIfExists(ackFilePath);
            if (deleted) {
                ChatLogsMod.LOGGER.debug("Deleted ack file for session {}", sessionId);
            }
            return deleted;
        } catch (IOException e) {
            ChatLogsMod.LOGGER.error("Error deleting ack file for session {}", sessionId, e);
            return false;
        }
    }

    /**
     * Get the path to the ack file.
     *
     * @return ack file path
     */
    public Path getPath() {
        return ackFilePath;
    }

    /**
     * Get the session ID for this ack file.
     *
     * @return session ID
     */
    public long getSessionId() {
        return sessionId;
    }
}
