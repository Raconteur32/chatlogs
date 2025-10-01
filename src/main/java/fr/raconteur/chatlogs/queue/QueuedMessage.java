package fr.raconteur.chatlogs.queue;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Represents a queued chat message waiting to be written to disk.
 * Messages are serialized to JSON Lines format for the queue file.
 */
public class QueuedMessage {
    private static final Gson GSON = new GsonBuilder().create();

    private final long id;
    private final long timestamp;
    private final String messageText;
    private final String messageJson;
    private final String senderName;

    /**
     * Create a new queued message.
     *
     * @param id          sequential message ID within this session
     * @param timestamp   when the message was logged (milliseconds since epoch)
     * @param messageText plain text representation of the message
     * @param messageJson full JSON representation of Minecraft Text component
     * @param senderName  extracted sender name (can be null for system messages)
     */
    public QueuedMessage(long id, long timestamp, String messageText, String messageJson, String senderName) {
        this.id = id;
        this.timestamp = timestamp;
        this.messageText = messageText;
        this.messageJson = messageJson;
        this.senderName = senderName;
    }

    public long getId() {
        return id;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public String getMessageText() {
        return messageText;
    }

    public String getMessageJson() {
        return messageJson;
    }

    public String getSenderName() {
        return senderName;
    }

    /**
     * Serialize this message to a single-line JSON string.
     * Format: {"id":1,"timestamp":123456,"text":"...","json":"...","sender":"..."}
     *
     * @return JSON string representation
     */
    public String toJsonLine() {
        JsonObject obj = new JsonObject();
        obj.addProperty("id", id);
        obj.addProperty("timestamp", timestamp);
        obj.addProperty("text", messageText);
        obj.addProperty("json", messageJson);
        if (senderName != null) {
            obj.addProperty("sender", senderName);
        }
        return GSON.toJson(obj);
    }

    /**
     * Deserialize a message from a JSON line string.
     *
     * @param jsonLine single-line JSON string
     * @return parsed QueuedMessage
     * @throws com.google.gson.JsonSyntaxException if parsing fails
     */
    public static QueuedMessage fromJsonLine(String jsonLine) {
        JsonObject obj = JsonParser.parseString(jsonLine).getAsJsonObject();

        long id = obj.get("id").getAsLong();
        long timestamp = obj.get("timestamp").getAsLong();
        String text = obj.get("text").getAsString();
        String json = obj.get("json").getAsString();
        String sender = obj.has("sender") && !obj.get("sender").isJsonNull()
            ? obj.get("sender").getAsString()
            : null;

        return new QueuedMessage(id, timestamp, text, json, sender);
    }

    @Override
    public String toString() {
        return String.format("QueuedMessage{id=%d, timestamp=%d, sender=%s, text=%s}",
            id, timestamp, senderName, messageText);
    }
}
