package fr.raconteur.chatlogs.session;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import fr.raconteur.chatlogs.ChatLogsMod;
import net.minecraft.text.Text;
import net.minecraft.util.Util;

/**
 * Simplified but robust session recorder that logs chat messages to plain text files
 * Features: file locking, background writing, crash recovery, error handling
 */
public class SimpleSessionRecorder {
    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter FILE_TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
    
    // Reuse the folder logic from original Session.java
    static final File CHATLOG_FOLDER = Util.make(() -> {
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
    
    private static final File UNSAVED_MARKER = new File(CHATLOG_FOLDER, "unsaved.marker");
    
    private static SimpleSessionRecorder current = null;
    private final String sessionName;
    private final File logFile;
    private final File lockFile;
    private final long startTime;
    
    // Background writing components
    private final BlockingQueue<String> messageQueue = new LinkedBlockingQueue<>();
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final Thread writerThread;
    private BufferedWriter writer;
    private FileLock fileLock;
    private RandomAccessFile lockRaf;
    
    private SimpleSessionRecorder(String sessionName) {
        this.sessionName = sessionName;
        this.startTime = System.currentTimeMillis();
        
        // Create log file with timestamp
        String timestamp = LocalDateTime.now().format(FILE_TIMESTAMP_FORMAT);
        String fileName = String.format("%s_%s.txt", sessionName.replaceAll("[^a-zA-Z0-9]", "_"), timestamp);
        this.logFile = new File(CHATLOG_FOLDER, fileName);
        this.lockFile = new File(CHATLOG_FOLDER, fileName + ".lock");
        
        // Initialize file locking and writer
        if (initializeWriter()) {
            markUnsaved();
            this.writerThread = new Thread(this::writerLoop, "ChatLog Writer");
            this.writerThread.setDaemon(true);
            this.writerThread.start();
            
            // Queue session start message
            queueSessionStart();
            ChatLogsMod.LOGGER.info("Started chat logging session: {}", fileName);
        } else {
            this.writerThread = null;
        }
    }
    
    private boolean initializeWriter() {
        try {
            // Create lock file and acquire lock
            this.lockRaf = new RandomAccessFile(lockFile, "rw");
            FileChannel channel = lockRaf.getChannel();
            this.fileLock = channel.tryLock();
            
            if (this.fileLock == null) {
                ChatLogsMod.LOGGER.error("Could not acquire lock for chat log file: {}", logFile.getName());
                this.lockRaf.close();
                return false;
            }
            
            // Initialize writer
            this.writer = new BufferedWriter(new FileWriter(logFile, true));
            return true;
            
        } catch (IOException e) {
            ChatLogsMod.LOGGER.error("Failed to initialize writer for chat log file: {}", logFile.getName(), e);
            cleanup();
            return false;
        }
    }
    
    private void queueSessionStart() {
        String sessionStart = "=== Chat Log Session Started ===\n" +
                             "Session: " + sessionName + "\n" +
                             "Start Time: " + LocalDateTime.now().format(TIMESTAMP_FORMAT) + "\n" +
                             "=====================================\n\n";
        messageQueue.offer(sessionStart);
    }
    
    private void writerLoop() {
        try {
            while (running.get() || !messageQueue.isEmpty()) {
                try {
                    // Process multiple messages per cycle for better throughput
                    List<String> messageBatch = new ArrayList<>();
                    
                    // Wait for at least one message
                    String firstMessage = messageQueue.poll(100, TimeUnit.MILLISECONDS);
                    if (firstMessage != null) {
                        messageBatch.add(firstMessage);
                        
                        // Drain additional messages without waiting (up to 100 per batch)
                        messageQueue.drainTo(messageBatch, 99);
                        
                        // Write all messages in batch
                        for (String message : messageBatch) {
                            writer.write(message);
                        }
                        writer.flush(); // Single flush for the entire batch
                        
                        if (messageBatch.size() > 1) {
                            ChatLogsMod.LOGGER.debug("Processed batch of {} messages", messageBatch.size());
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (IOException e) {
                    ChatLogsMod.LOGGER.error("Error writing to chat log", e);
                    // Continue trying to write other messages
                }
            }
        } finally {
            // Write session end info
            try {
                if (writer != null) {
                    String sessionEnd = "\n=====================================\n" +
                                      "Session ended: " + LocalDateTime.now().format(TIMESTAMP_FORMAT) + "\n" +
                                      "Duration: " + formatDuration(System.currentTimeMillis() - startTime) + "\n" +
                                      "=====================================\n";
                    writer.write(sessionEnd);
                }
            } catch (IOException e) {
                ChatLogsMod.LOGGER.error("Error writing session end", e);
            }
            
            cleanup();
            unmarkUnsaved();
            ChatLogsMod.LOGGER.info("Chat logging session ended: {}", logFile.getName());
        }
    }
    
    private void cleanup() {
        try {
            if (writer != null) {
                writer.close();
                writer = null;
            }
        } catch (IOException e) {
            ChatLogsMod.LOGGER.error("Error closing writer", e);
        }
        
        try {
            if (fileLock != null) {
                fileLock.release();
                fileLock = null;
            }
        } catch (IOException e) {
            ChatLogsMod.LOGGER.error("Error releasing file lock", e);
        }
        
        try {
            if (lockRaf != null) {
                lockRaf.close();
                lockRaf = null;
            }
        } catch (IOException e) {
            ChatLogsMod.LOGGER.error("Error closing lock file", e);
        }
        
        // Clean up lock file
        if (lockFile.exists()) {
            lockFile.delete();
        }
    }
    
    private void markUnsaved() {
        try (FileWriter fw = new FileWriter(UNSAVED_MARKER, true)) {
            fw.append(logFile.getAbsolutePath()).append('\n');
        } catch (IOException e) {
            ChatLogsMod.LOGGER.warn("Unable to create unsaved marker", e);
        }
    }
    
    private void unmarkUnsaved() {
        if (!UNSAVED_MARKER.exists()) {
            return;
        }
        
        List<String> filtered = new ArrayList<>();
        String unsavedFilePath = logFile.getAbsolutePath();
        
        // Read all entries and filter out the current file
        try (Scanner s = new Scanner(new FileReader(UNSAVED_MARKER))) {
            while (s.hasNextLine()) {
                String line = s.nextLine().trim();
                if (!line.equals(unsavedFilePath) && !line.isEmpty()) {
                    filtered.add(line);
                }
            }
        } catch (IOException e) {
            ChatLogsMod.LOGGER.warn("Unable to read unsaved marker", e);
            return;
        }
        
        // Rewrite the marker file without our entry
        try (FileWriter fw = new FileWriter(UNSAVED_MARKER, false)) {
            for (String line : filtered) {
                fw.write(line);
                fw.write('\n');
            }
        } catch (IOException e) {
            ChatLogsMod.LOGGER.warn("Unable to update unsaved marker", e);
        }
    }
    
    public static SimpleSessionRecorder start(String sessionName) {
        if (current != null) {
            current.end();
        }
        
        // Clean up any orphaned lock files before starting new session
        cleanupOrphanedLocks();
        
        current = new SimpleSessionRecorder(sessionName);
        return current;
    }
    
    /**
     * Clean up any orphaned lock files from previous crashed sessions
     */
    private static void cleanupOrphanedLocks() {
        File[] lockFiles = CHATLOG_FOLDER.listFiles((dir, name) -> name.endsWith(".lock"));
        if (lockFiles != null) {
            for (File lockFile : lockFiles) {
                if (lockFile.delete()) {
                    ChatLogsMod.LOGGER.debug("Cleaned up orphaned lock file: {}", lockFile.getName());
                }
            }
        }
    }
    
    public static SimpleSessionRecorder current() {
        return current;
    }
    
    public static void end() {
        if (current != null) {
            current.shutdown();  // Renamed for clarity
            current = null;
        }
    }
    
    public void logMessage(Text message) {
        if (!running.get() || writerThread == null) return;
        
        String timestamp = LocalDateTime.now().format(TIMESTAMP_FORMAT);
        String messageText = message.getString();
        String formattedMessage = String.format("[%s] %s\n", timestamp, messageText);
        
        // Non-blocking queue offer - if queue is full, message is dropped
        if (!messageQueue.offer(formattedMessage)) {
            ChatLogsMod.LOGGER.warn("Chat log message queue is full, dropping message");
        }
    }
    
    /**
     * Shutdown this recorder instance - stops the writer thread and waits for completion
     */
    private void shutdown() {
        running.set(false);
        
        if (writerThread != null) {
            try {
                writerThread.join(5000); // Wait up to 5 seconds for writer to finish
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                ChatLogsMod.LOGGER.warn("Interrupted while waiting for writer thread to finish");
            }
        }
    }
    
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
}