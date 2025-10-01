package fr.raconteur.chatlogs.writer;

import fr.raconteur.chatlogs.database.SessionDatabase;
import fr.raconteur.chatlogs.queue.QueuedMessage;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Writes chat messages to the SQLite database.
 * Used by the writer process to store structured message data.
 */
public class SqliteWriter implements AutoCloseable {
    private final long sessionId;
    private final SessionDatabase database;

    /**
     * Create an SQLite writer for the specified session.
     * The session must already exist in the database (created by main process).
     *
     * @param sessionId unique session identifier
     * @throws SQLException if database connection fails
     */
    public SqliteWriter(long sessionId) throws SQLException {
        this.sessionId = sessionId;
        this.database = SessionDatabase.getInstance();
    }

    /**
     * Write a single message to the database.
     *
     * @param message the queued message to write
     * @throws SQLException if write fails
     */
    public void writeMessage(QueuedMessage message) throws SQLException {
        database.addMessage(
            sessionId,
            message.getSenderName(),
            message.getMessageText(),
            message.getMessageJson()
        );
    }

    /**
     * Write multiple messages in a batch for better performance.
     * This is more efficient than writing messages one at a time.
     *
     * @param messages list of queued messages to write
     * @throws SQLException if write fails
     */
    public void writeMessages(List<QueuedMessage> messages) throws SQLException {
        if (messages.isEmpty()) {
            return;
        }

        // Convert QueuedMessages to SessionDatabase.MessageData format
        List<SessionDatabase.MessageData> messageDataList = new ArrayList<>(messages.size());
        for (QueuedMessage msg : messages) {
            SessionDatabase.MessageData messageData = new SessionDatabase.MessageData(
                msg.getSenderName(),
                msg.getMessageText(),
                msg.getMessageJson(),
                msg.getTimestamp()
            );
            messageDataList.add(messageData);
        }

        // Batch insert
        database.addMessagesBulk(sessionId, messageDataList);
    }

    /**
     * Mark the session as ended by setting its end_time.
     * Called when the session finishes (gracefully or via crash).
     *
     * @throws SQLException if update fails
     */
    public void endSession() throws SQLException {
        database.endSession(sessionId);
    }

    /**
     * Close the SQLite writer.
     * Note: This doesn't close the database connection itself, as it's shared.
     * It just ensures the session is properly finalized.
     */
    @Override
    public void close() {
        // SessionDatabase is a singleton, we don't close it here
        // Just ensure session is ended if not already
        try {
            endSession();
        } catch (SQLException e) {
            System.err.println("Error ending session in SqliteWriter.close(): " + e.getMessage());
        }
    }

    /**
     * Get the session ID for this writer.
     *
     * @return session ID
     */
    public long getSessionId() {
        return sessionId;
    }
}
