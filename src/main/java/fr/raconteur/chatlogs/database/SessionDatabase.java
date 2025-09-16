package fr.raconteur.chatlogs.database;

import fr.raconteur.chatlogs.ChatLogsMod;
import net.minecraft.text.Text;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class SessionDatabase extends AbstractDatabase {
    private static SessionDatabase instance;
    private static final Object INSTANCE_LOCK = new Object();
    
    // Thread safety
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    
    // Prepared statements for performance
    private PreparedStatement insertSessionStmt;
    private PreparedStatement updateSessionStmt;
    private PreparedStatement insertMessageStmt;
    private PreparedStatement updateMessageCountStmt;

    private SessionDatabase() throws SQLException {
        super("sessions.db");
        prepareStatements();
    }

    public static SessionDatabase getInstance() throws SQLException {
        if (instance == null) {
            synchronized (INSTANCE_LOCK) {
                if (instance == null) {
                    instance = new SessionDatabase();
                }
            }
        }
        return instance;
    }

    private void prepareStatements() throws SQLException {
        // Insert new session
        insertSessionStmt = getConnection().prepareStatement(
            "INSERT INTO sessions (session_name, start_time, is_multiplayer, txt_file_path, created_at, updated_at) " +
            "VALUES (?, ?, ?, ?, ?, ?)",
            Statement.RETURN_GENERATED_KEYS
        );
        
        // Update session end time and message count
        updateSessionStmt = getConnection().prepareStatement(
            "UPDATE sessions SET end_time = ?, message_count = ?, updated_at = ? WHERE id = ?"
        );
        
        // Insert message
        insertMessageStmt = getConnection().prepareStatement(
            "INSERT INTO messages (session_id, sender_name, message_text, message_json, timestamp, created_at) " +
            "VALUES (?, ?, ?, ?, ?, ?)"
        );
        
        // Update message count
        updateMessageCountStmt = getConnection().prepareStatement(
            "UPDATE sessions SET message_count = message_count + ?, updated_at = ? WHERE id = ?"
        );
    }

    /**
     * Create a new session and return its ID
     */
    public long createSession(String sessionName, boolean isMultiplayer, String txtFilePath) throws SQLException {
        lock.writeLock().lock();
        try {
            return executeInTransaction(conn -> {
                long currentTime = System.currentTimeMillis();
                
                insertSessionStmt.setString(1, sessionName);
                insertSessionStmt.setLong(2, currentTime);
                insertSessionStmt.setBoolean(3, isMultiplayer);
                insertSessionStmt.setString(4, txtFilePath);
                insertSessionStmt.setLong(5, currentTime);
                insertSessionStmt.setLong(6, currentTime);
                
                int affectedRows = insertSessionStmt.executeUpdate();
                if (affectedRows == 0) {
                    throw new SQLException("Failed to create session, no rows affected");
                }
                
                try (ResultSet rs = insertSessionStmt.getGeneratedKeys()) {
                    if (rs.next()) {
                        long sessionId = rs.getLong(1);
                        ChatLogsMod.LOGGER.info("Created new session: {} (ID: {})", sessionName, sessionId);
                        return sessionId;
                    } else {
                        throw new SQLException("Failed to create session, no ID obtained");
                    }
                }
            });
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Add a single message to a session
     */
    public void addMessage(long sessionId, String senderName, 
                          String messageText, String messageJson) throws SQLException {
        lock.writeLock().lock();
        try {
            executeInTransaction(conn -> {
                long currentTime = System.currentTimeMillis();
                
                // Insert message
                insertMessageStmt.setLong(1, sessionId);
                insertMessageStmt.setString(2, senderName);
                insertMessageStmt.setString(3, messageText);
                insertMessageStmt.setString(4, messageJson);
                insertMessageStmt.setLong(5, currentTime);
                insertMessageStmt.setLong(6, currentTime);
                insertMessageStmt.executeUpdate();
                
                // Update message count
                updateMessageCountStmt.setInt(1, 1);
                updateMessageCountStmt.setLong(2, currentTime);
                updateMessageCountStmt.setLong(3, sessionId);
                updateMessageCountStmt.executeUpdate();
                
                return null;
            });
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Add multiple messages in bulk for better performance
     */
    public void addMessagesBulk(long sessionId, List<MessageData> messages) throws SQLException {
        if (messages.isEmpty()) return;
        
        lock.writeLock().lock();
        try {
            executeInTransaction(conn -> {
                long currentTime = System.currentTimeMillis();
                
                // Batch insert messages
                for (MessageData msg : messages) {
                    insertMessageStmt.setLong(1, sessionId);
                    insertMessageStmt.setString(2, msg.senderName);
                    insertMessageStmt.setString(3, msg.messageText);
                    insertMessageStmt.setString(4, msg.messageJson);
                    insertMessageStmt.setLong(5, msg.timestamp);
                    insertMessageStmt.setLong(6, currentTime);
                    insertMessageStmt.addBatch();
                }
                insertMessageStmt.executeBatch();
                
                // Update message count
                updateMessageCountStmt.setInt(1, messages.size());
                updateMessageCountStmt.setLong(2, currentTime);
                updateMessageCountStmt.setLong(3, sessionId);
                updateMessageCountStmt.executeUpdate();
                
                ChatLogsMod.LOGGER.debug("Added {} messages to session {}", messages.size(), sessionId);
                return null;
            });
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * End a session by setting its end time
     */
    public void endSession(long sessionId) throws SQLException {
        lock.writeLock().lock();
        try {
            executeInTransaction(conn -> {
                long currentTime = System.currentTimeMillis();
                
                updateSessionStmt.setLong(1, currentTime); // end_time
                updateSessionStmt.setNull(2, java.sql.Types.INTEGER); // Don't update message_count here
                updateSessionStmt.setLong(3, currentTime); // updated_at
                updateSessionStmt.setLong(4, sessionId);
                
                int affectedRows = updateSessionStmt.executeUpdate();
                if (affectedRows > 0) {
                    ChatLogsMod.LOGGER.info("Ended session: {}", sessionId);
                } else {
                    ChatLogsMod.LOGGER.warn("Failed to end session: {} (not found)", sessionId);
                }
                return null;
            });
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Get all sessions ordered by start time (newest first)
     */
    public List<SessionData> getAllSessions() throws SQLException {
        lock.readLock().lock();
        try {
            return executeInTransaction(conn -> {
                List<SessionData> sessions = new ArrayList<>();
                String query = "SELECT * FROM sessions ORDER BY start_time DESC";
                
                try (Statement stmt = conn.createStatement();
                     ResultSet rs = stmt.executeQuery(query)) {
                    
                    while (rs.next()) {
                        SessionData session = new SessionData(
                            rs.getLong("id"),
                            rs.getString("session_name"),
                            rs.getLong("start_time"),
                            rs.getLong("end_time"),
                            rs.getBoolean("is_multiplayer"),
                            rs.getString("txt_file_path"),
                            rs.getInt("message_count")
                        );
                        sessions.add(session);
                    }
                }
                
                return sessions;
            });
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Get messages for a specific session
     */
    public List<MessageData> getMessagesForSession(long sessionId) throws SQLException {
        lock.readLock().lock();
        try {
            return executeInTransaction(conn -> {
                List<MessageData> messages = new ArrayList<>();
                String query = "SELECT * FROM messages WHERE session_id = ? ORDER BY timestamp ASC";
                
                try (PreparedStatement stmt = conn.prepareStatement(query)) {
                    stmt.setLong(1, sessionId);
                    
                    try (ResultSet rs = stmt.executeQuery()) {
                        while (rs.next()) {
                            MessageData message = new MessageData(
                                rs.getString("sender_name"),
                                rs.getString("message_text"),
                                rs.getString("message_json"),
                                rs.getLong("timestamp")
                            );
                            messages.add(message);
                        }
                    }
                }
                
                return messages;
            });
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public void close() throws SQLException {
        lock.writeLock().lock();
        try {
            // Close prepared statements
            if (insertSessionStmt != null) insertSessionStmt.close();
            if (updateSessionStmt != null) updateSessionStmt.close();
            if (insertMessageStmt != null) insertMessageStmt.close();
            if (updateMessageCountStmt != null) updateMessageCountStmt.close();
            
            // Close database connection
            super.close();
            
            synchronized (INSTANCE_LOCK) {
                instance = null;
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    // Data classes
    public static class SessionData {
        public final long id;
        public final String sessionName;
        public final long startTime;
        public final long endTime; // 0 if still active
        public final boolean isMultiplayer;
        public final String txtFilePath;
        public final int messageCount;

        public SessionData(long id, String sessionName, long startTime, long endTime, 
                          boolean isMultiplayer, String txtFilePath, int messageCount) {
            this.id = id;
            this.sessionName = sessionName;
            this.startTime = startTime;
            this.endTime = endTime;
            this.isMultiplayer = isMultiplayer;
            this.txtFilePath = txtFilePath;
            this.messageCount = messageCount;
        }
    }

    public static class MessageData {
        public final String senderName;
        public final String messageText;
        public final String messageJson;
        public final long timestamp;

        public MessageData(String senderName, String messageText, 
                          String messageJson, long timestamp) {
            this.senderName = senderName;
            this.messageText = messageText;
            this.messageJson = messageJson;
            this.timestamp = timestamp;
        }
    }
}