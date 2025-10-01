package fr.raconteur.chatlogs.writer;

import fr.raconteur.chatlogs.queue.QueuedMessage;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Writes chat messages to a plain text file.
 * Used by the writer process to create human-readable session logs.
 */
public class TxtFileWriter implements AutoCloseable {
    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final File txtFile;
    private final BufferedWriter writer;
    private final long sessionStartTime;

    /**
     * Create a TXT file writer for the specified file.
     *
     * @param txtFile          the TXT log file to write to
     * @param sessionStartTime session start time in milliseconds since epoch
     * @throws IOException if file cannot be opened
     */
    public TxtFileWriter(File txtFile, long sessionStartTime) throws IOException {
        this.txtFile = txtFile;
        this.sessionStartTime = sessionStartTime;
        this.writer = new BufferedWriter(new FileWriter(txtFile, true)); // Append mode
    }

    /**
     * Write the session header to the TXT file.
     *
     * @param sessionName name of the session (server address or world name)
     * @throws IOException if write fails
     */
    public void writeSessionHeader(String sessionName) throws IOException {
        LocalDateTime startTime = LocalDateTime.ofInstant(
            Instant.ofEpochMilli(sessionStartTime),
            ZoneId.systemDefault()
        );

        writer.write("=== Chat Log Session Started ===\n");
        writer.write("Session: " + sessionName + "\n");
        writer.write("Start Time: " + startTime.format(TIMESTAMP_FORMAT) + "\n");
        writer.write("=====================================\n\n");
        writer.flush();
    }

    /**
     * Write a single message to the TXT file.
     *
     * @param message the queued message to write
     * @throws IOException if write fails
     */
    public void writeMessage(QueuedMessage message) throws IOException {
        LocalDateTime messageTime = LocalDateTime.ofInstant(
            Instant.ofEpochMilli(message.getTimestamp()),
            ZoneId.systemDefault()
        );

        String formattedMessage = String.format("[%s] %s\n",
            messageTime.format(TIMESTAMP_FORMAT),
            message.getMessageText()
        );

        writer.write(formattedMessage);
        writer.flush(); // Flush after each message for safety
    }

    /**
     * Write a normal session footer (graceful shutdown).
     *
     * @param durationMs session duration in milliseconds
     * @throws IOException if write fails
     */
    public void writeSessionFooter(long durationMs) throws IOException {
        LocalDateTime endTime = LocalDateTime.now();

        writer.write("\n=====================================\n");
        writer.write("Session ended: " + endTime.format(TIMESTAMP_FORMAT) + "\n");
        writer.write("Duration: " + formatDuration(durationMs) + "\n");
        writer.write("=====================================\n");
        writer.flush();
    }

    /**
     * Write a crash marker indicating parent process crashed.
     *
     * @throws IOException if write fails
     */
    public void writeParentCrashMarker() throws IOException {
        LocalDateTime recoveryTime = LocalDateTime.now();

        writer.write("\n");
        writer.write("=====================================\n");
        writer.write("⚠️  SESSION ENDED UNEXPECTEDLY  ⚠️\n");
        writer.write("Parent process crashed or was terminated\n");
        writer.write("Recovery performed by writer process\n");
        writer.write("Recovery time: " + recoveryTime.format(TIMESTAMP_FORMAT) + "\n");
        writer.write("=====================================\n");
        writer.flush();
    }

    /**
     * Write a crash marker indicating writer process crashed.
     * This is called during crash recovery by the main process.
     *
     * @throws IOException if write fails
     */
    public void writeWriterCrashMarker() throws IOException {
        LocalDateTime recoveryTime = LocalDateTime.now();

        writer.write("\n");
        writer.write("=====================================\n");
        writer.write("⚠️  WRITER PROCESS CRASHED  ⚠️\n");
        writer.write("Writer process was terminated unexpectedly\n");
        writer.write("Recovery performed by main process\n");
        writer.write("Recovery time: " + recoveryTime.format(TIMESTAMP_FORMAT) + "\n");
        writer.write("=====================================\n");
        writer.flush();
    }

    /**
     * Format a duration in milliseconds to a human-readable string.
     *
     * @param millis duration in milliseconds
     * @return formatted duration string (e.g., "2h 15m 30s")
     */
    private String formatDuration(long millis) {
        long seconds = millis / 1000;
        long minutes = seconds / 60;
        long hours = minutes / 60;

        if (hours > 0) {
            return String.format("%dh %dm %ds", hours, minutes % 60, seconds % 60);
        } else if (minutes > 0) {
            return String.format("%dm %ds", minutes, seconds % 60);
        } else {
            return String.format("%ds", seconds);
        }
    }

    /**
     * Close the TXT file writer.
     */
    @Override
    public void close() {
        try {
            if (writer != null) {
                writer.flush();
                writer.close();
            }
        } catch (IOException e) {
            System.err.println("Error closing TXT writer: " + e.getMessage());
        }
    }

    /**
     * Get the TXT file being written to.
     *
     * @return the TXT file
     */
    public File getTxtFile() {
        return txtFile;
    }
}
