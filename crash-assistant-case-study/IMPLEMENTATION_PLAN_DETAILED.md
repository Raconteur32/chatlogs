# Detailed Implementation Plan: Separate Writer Process for Chat Logs

## Overview
Refactor the Chat Logs mod to use a **separate writer process** instead of a background thread, significantly improving crash resilience and data integrity. The writer process will survive game crashes and properly finalize sessions.

---

## Answers to Key Questions

### Q1: Heartbeat Feature Details

**Purpose**: Distinguish between a crash (process dead) and a freeze (process alive but unresponsive).

**Implementation**:

#### Main Process Side:
```java
// In SimpleSessionRecorder - periodic heartbeat sender
private ScheduledExecutorService heartbeatExecutor;
private static final int HEARTBEAT_INTERVAL_MS = 2000; // Every 2 seconds

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
            LOGGER.error("Failed to send heartbeat", e);
        }
    }, 0, HEARTBEAT_INTERVAL_MS, TimeUnit.MILLISECONDS);
}
```

#### Writer Process Side:
```java
// In ChatLogWriterApp main loop
private static final int HEARTBEAT_TIMEOUT_MS = 10000; // 10 seconds
private long lastHeartbeatTime = System.currentTimeMillis();
private boolean parentFrozen = false;

while (true) {
    // Check for heartbeat signal
    Optional<String> heartbeat = ProcessSignalIO.get("heartbeat_" + sessionId, parentPID);
    if (heartbeat.isPresent()) {
        long heartbeatTimestamp = Long.parseLong(heartbeat.get());
        lastHeartbeatTime = heartbeatTimestamp;

        if (parentFrozen) {
            LOGGER.info("Parent process resumed after freeze");
            parentFrozen = false;
        }
    }

    // Check if parent crashed (process dead)
    if (!ProcessHelper.isProcessAlive(parentPID) ||
        ProcessHelper.getProcessStartTime(parentPID) != parentStartTime) {
        LOGGER.warn("Parent process crashed, finalizing session");
        finalizeSessionAsCrashed();
        break;
    }

    // Check if parent frozen (process alive but no heartbeat)
    long timeSinceHeartbeat = System.currentTimeMillis() - lastHeartbeatTime;
    if (timeSinceHeartbeat > HEARTBEAT_TIMEOUT_MS) {
        if (!parentFrozen) {
            LOGGER.warn("Parent process appears frozen (no heartbeat for {}ms)", timeSinceHeartbeat);
            parentFrozen = true;
            // Continue processing queue - don't exit yet!
        }

        // If frozen for too long (e.g., 5 minutes), consider it dead
        if (timeSinceHeartbeat > 300000) {
            LOGGER.error("Parent frozen for 5+ minutes, treating as crashed");
            finalizeSessionAsCrashed();
            break;
        }
    }

    // Continue normal processing...
}
```

**Behavior on Freeze/Unfreeze**:
1. **During freeze**: Writer process detects missing heartbeat, logs warning, but **continues processing queue**
2. **Queue continues to grow**: Main process might still be writing to queue file even if frozen (OS buffering)
3. **On unfreeze**: Heartbeat resumes, writer logs "resumed" message, continues normally
4. **Long freeze (5+ min)**: Treated as crash, session finalized with marker

**Why this approach?**
- ✅ Doesn't lose data during temporary freezes (GC pauses, world loading, etc.)
- ✅ Eventually finalizes if truly frozen/hung
- ✅ User can still see logs from freeze period when they unfreeze

---

### Q2: Java Binary Location (getJavaBinary)

**Yes, we should use Crash-Assistant's approach!** It's more robust than just using `java.home`.

**Implementation** (based on Crash-Assistant's JavaBinaryLocator):

```java
// src/main/java/fr/raconteur/chatlogs/utils/JavaBinaryLocator.java

public class JavaBinaryLocator {
    private static String cachedJavaBinary = null;

    /**
     * Locates the Java binary executable used by the current process.
     * Based on Crash-Assistant's implementation for maximum compatibility.
     *
     * @return absolute path to java executable
     */
    public static String getJavaBinary() {
        if (cachedJavaBinary != null) {
            return cachedJavaBinary;
        }

        String javaHome = System.getProperty("java.home");
        if (javaHome == null) {
            ChatLogsMod.LOGGER.warn("java.home property not set, falling back to 'java'");
            cachedJavaBinary = "java";
            return cachedJavaBinary;
        }

        // Determine binary name based on OS
        String osName = System.getProperty("os.name").toLowerCase();
        boolean isWindows = osName.contains("win");
        String binaryName = isWindows ? "java.exe" : "java";

        // Try bin/java first (standard location)
        Path binJava = Paths.get(javaHome, "bin", binaryName);
        if (Files.exists(binJava) && Files.isExecutable(binJava)) {
            cachedJavaBinary = binJava.toAbsolutePath().toString();
            ChatLogsMod.LOGGER.debug("Found Java binary at: {}", cachedJavaBinary);
            return cachedJavaBinary;
        }

        // Try jre/bin/java (some JDK installations)
        Path jreBinJava = Paths.get(javaHome, "jre", "bin", binaryName);
        if (Files.exists(jreBinJava) && Files.isExecutable(jreBinJava)) {
            cachedJavaBinary = jreBinJava.toAbsolutePath().toString();
            ChatLogsMod.LOGGER.debug("Found Java binary at: {}", cachedJavaBinary);
            return cachedJavaBinary;
        }

        // Last resort: try to get from current process command
        Optional<String> processCommand = ProcessHandle.current().info().command();
        if (processCommand.isPresent()) {
            String command = processCommand.get();
            if (command.endsWith(binaryName)) {
                cachedJavaBinary = command;
                ChatLogsMod.LOGGER.debug("Using Java binary from process command: {}", cachedJavaBinary);
                return cachedJavaBinary;
            }
        }

        // Ultimate fallback
        ChatLogsMod.LOGGER.warn("Could not locate Java binary, using 'java' from PATH");
        cachedJavaBinary = "java";
        return cachedJavaBinary;
    }
}
```

**Why this approach?**
- ✅ Handles different JDK/JRE layouts
- ✅ Cross-platform (Windows .exe vs Unix)
- ✅ Falls back gracefully
- ✅ Uses same Java as parent (important for compatibility)

---

### Q3: Why Remove TXT Recovery?

**Actually, you're right - we should keep it!** Here's why and how:

**Reasons to KEEP TXT recovery**:
1. **Diagnostic value**: Can manually inspect TXT files to see what was written
2. **Writer process crashes**: If writer itself crashes, TXT might be incomplete
3. **Filesystem issues**: Power loss, disk full, etc. might leave incomplete files
4. **User expectations**: Users might expect recovery markers in TXT files

**Revised approach**:

#### Writer Process Finalizes TXT:
```java
// In ChatLogWriterApp - on normal shutdown
private void finalizeSessionGracefully() {
    processRemainingMessages(); // Flush queue
    txtWriter.writeSessionFooter(calculateDuration());
    sqliteWriter.endSession();
    txtWriter.close();
    sqliteWriter.close();
    ProcessSignalIO.post("writer_stopped_" + sessionId);
}

// On crash detection
private void finalizeSessionAsCrashed() {
    processRemainingMessages(); // Flush what we can
    txtWriter.writeCrashMarker(); // Add crash marker to TXT
    sqliteWriter.endSession();
    txtWriter.close();
    sqliteWriter.close();
    // No "writer_stopped" signal - parent is dead
}

// On writer process crash (uncaught exception)
static {
    Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
        LOGGER.error("Writer process uncaught exception", throwable);
        try {
            // Emergency finalization
            if (txtWriter != null) {
                txtWriter.writeCrashMarker();
                txtWriter.close();
            }
            if (sqliteWriter != null) {
                sqliteWriter.endSession();
                sqliteWriter.close();
            }
        } catch (Exception e) {
            LOGGER.error("Failed emergency finalization", e);
        }
        System.exit(1);
    });
}
```

#### CrashRecovery Handles Incomplete TXT:
```java
// In CrashRecovery.performRecovery() - KEEP THIS LOGIC

public static void performRecovery() {
    // 1. Kill and clean up orphaned writer processes (see Q4 answer)
    cleanupOrphanedWriters();

    // 2. Recover SQLite sessions
    recoverSqliteSessions();

    // 3. Recover incomplete TXT files
    // This catches cases where writer process itself crashed
    recoverIncompleteTxtFiles();
}

private static void recoverIncompleteTxtFiles() {
    // Find TXT files modified recently that don't have proper ending
    File[] txtFiles = CHATLOG_FOLDER.listFiles((dir, name) ->
        name.endsWith(".txt") && !name.endsWith(".txt.lock"));

    if (txtFiles == null) return;

    for (File txtFile : txtFiles) {
        // Only check files modified in last session (within 24 hours)
        long lastModified = txtFile.lastModified();
        if (System.currentTimeMillis() - lastModified > 86400000) {
            continue; // Skip old files
        }

        if (!hasProperEnding(txtFile)) {
            LOGGER.warn("Found incomplete TXT file: {}", txtFile.getName());
            appendCrashMarkerToTxt(txtFile);
        }
    }
}

private static boolean hasProperEnding(File txtFile) {
    // Check last 5 lines for ending markers
    try (RandomAccessFile file = new RandomAccessFile(txtFile, "r")) {
        // Read last 500 bytes (should contain ending)
        long fileSize = file.length();
        if (fileSize == 0) return false;

        long seekPos = Math.max(0, fileSize - 500);
        file.seek(seekPos);
        byte[] buffer = new byte[(int)(fileSize - seekPos)];
        file.readFully(buffer);
        String ending = new String(buffer, StandardCharsets.UTF_8);

        return ending.contains("Session ended:") ||
               ending.contains("ENDED UNEXPECTEDLY") ||
               ending.contains("WRITER PROCESS CRASHED");

    } catch (IOException e) {
        LOGGER.error("Failed to check file ending: {}", txtFile.getName(), e);
        return false; // Assume needs recovery
    }
}
```

**Recovery marker formats**:
- Normal end: `"Session ended: {timestamp}\nDuration: {duration}"`
- Parent crash: `"⚠️ SESSION ENDED UNEXPECTEDLY ⚠️\nParent process crashed"`
- Writer crash: `"⚠️ WRITER PROCESS CRASHED ⚠️\nRecovery performed on: {timestamp}"`

---

### Q4: Orphaned Process Cleanup - Graceful Termination

**You're absolutely right!** Killing orphaned processes immediately could lose data. We need a **graceful cleanup protocol**.

**Revised implementation**:

```java
// In ProcessCleaner.cleanupOrphanedWriters()

public static void cleanupOrphanedWriters() {
    Path queueDir = Paths.get("chatlogs", "queue");
    if (!Files.exists(queueDir)) return;

    try {
        // Find all session queue files
        List<Path> queueFiles = Files.list(queueDir)
            .filter(p -> p.getFileName().toString().endsWith("_messages.jsonl"))
            .collect(Collectors.toList());

        for (Path queueFile : queueFiles) {
            String fileName = queueFile.getFileName().toString();
            long sessionId = extractSessionId(fileName);

            // Check if there's an ack file
            Path ackFile = queueDir.resolve(sessionId + "_ack.tmp");
            if (!Files.exists(ackFile)) {
                LOGGER.debug("No ack file for session {}, skipping", sessionId);
                continue;
            }

            // Read last processed ID from ack
            long lastProcessedId = readLastProcessedId(ackFile);

            // Count total messages in queue
            long totalMessages = countMessagesInQueue(queueFile);

            // Check for orphaned writer process
            Long writerPid = findWriterProcessForSession(sessionId);
            if (writerPid == null) {
                LOGGER.debug("No writer process found for session {}", sessionId);
                continue;
            }

            // Check if writer's parent is still alive
            Long parentPid = getParentPidForWriter(sessionId);
            if (parentPid != null && ProcessHelper.isProcessAlive(parentPid)) {
                // Parent is alive, writer is not orphaned
                continue;
            }

            // Writer IS orphaned - begin graceful shutdown
            LOGGER.warn("Found orphaned writer process {} for session {}", writerPid, sessionId);
            LOGGER.info("Writer progress: {}/{} messages processed", lastProcessedId, totalMessages);

            if (lastProcessedId < totalMessages) {
                LOGGER.info("Giving writer process time to finish processing {} remaining messages",
                    totalMessages - lastProcessedId);

                // Send shutdown signal
                ProcessSignalIO.postAsOtherProcess("shutdown_request_" + sessionId, writerPid);

                // Wait up to 30 seconds for graceful completion
                boolean completed = waitForWriterCompletion(writerPid, sessionId, 30000);

                if (completed) {
                    LOGGER.info("Orphaned writer {} completed gracefully", writerPid);
                } else {
                    LOGGER.warn("Orphaned writer {} did not complete in time, force killing", writerPid);
                    ProcessHelper.destroyProcessForcibly(writerPid);

                    // Mark session as needing recovery
                    markSessionForRecovery(sessionId);
                }
            } else {
                // All messages processed, safe to kill immediately
                LOGGER.info("All messages processed, terminating writer {}", writerPid);
                ProcessHelper.destroyProcess(writerPid);
            }

            // Clean up signal files
            cleanupSignalFiles(sessionId);
        }
    } catch (IOException e) {
        LOGGER.error("Error during orphaned writer cleanup", e);
    }
}

private static boolean waitForWriterCompletion(long writerPid, long sessionId, long timeoutMs) {
    long startTime = System.currentTimeMillis();
    long lastAckValue = -1;
    int stallCount = 0;

    while (System.currentTimeMillis() - startTime < timeoutMs) {
        // Check if process exited
        if (!ProcessHelper.isProcessAlive(writerPid)) {
            LOGGER.info("Writer process exited");
            return true;
        }

        // Check for completion signal
        if (ProcessSignalIO.exists("writer_stopped_" + sessionId, writerPid)) {
            LOGGER.info("Writer sent completion signal");
            return true;
        }

        // Check if making progress
        try {
            Path ackFile = Paths.get("chatlogs", "queue", sessionId + "_ack.tmp");
            if (Files.exists(ackFile)) {
                long currentAck = readLastProcessedId(ackFile);
                if (currentAck > lastAckValue) {
                    LOGGER.debug("Writer making progress: {} messages", currentAck);
                    lastAckValue = currentAck;
                    stallCount = 0;
                } else {
                    stallCount++;
                    if (stallCount > 50) { // 5 seconds of no progress
                        LOGGER.warn("Writer appears stalled (no progress for 5s)");
                        return false;
                    }
                }
            }
        } catch (IOException e) {
            LOGGER.error("Error reading ack file", e);
        }

        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            break;
        }
    }

    return false; // Timeout
}

private static Long findWriterProcessForSession(long sessionId) {
    // Check for writer PID in signal file
    Optional<String> writerInfo = ProcessSignalIO.getInfo("writer_" + sessionId);
    if (writerInfo.isPresent()) {
        try {
            // Format: "writerPid:writerStartTime:parentPid:parentStartTime"
            String[] parts = writerInfo.get().split(":");
            long writerPid = Long.parseLong(parts[0]);
            long writerStartTime = Long.parseLong(parts[1]);

            // Verify PID still matches start time (not reused)
            if (ProcessHelper.getProcessStartTime(writerPid) == writerStartTime) {
                return writerPid;
            }
        } catch (Exception e) {
            LOGGER.error("Error parsing writer info", e);
        }
    }
    return null;
}

private static Long getParentPidForWriter(long sessionId) {
    Optional<String> writerInfo = ProcessSignalIO.getInfo("writer_" + sessionId);
    if (writerInfo.isPresent()) {
        try {
            String[] parts = writerInfo.get().split(":");
            long parentPid = Long.parseLong(parts[2]);
            long parentStartTime = Long.parseLong(parts[3]);

            // Verify PID matches start time
            if (ProcessHelper.getProcessStartTime(parentPid) == parentStartTime) {
                return parentPid;
            }
        } catch (Exception e) {
            LOGGER.error("Error parsing parent info", e);
        }
    }
    return null;
}
```

**Key improvements**:
1. **Progress tracking**: Monitors ack file to see if writer is making progress
2. **Graceful signal**: Sends shutdown_request before killing
3. **Generous timeout**: 30 seconds to process remaining messages
4. **Stall detection**: If no progress for 5 seconds, assume hung
5. **Force kill only as last resort**: After timeout or stall detection
6. **Recovery marking**: If force-killed, marks session for TXT recovery

**Writer process info file**:
When writer starts, it writes:
```java
// In ChatLogWriterApp.main() after initialization
String writerInfo = String.format("%d:%d:%d:%d",
    ProcessHelper.getCurrentProcessId(),
    ProcessHelper.getCurrentProcessStartTime(),
    parentPid,
    parentStartTime
);
ProcessSignalIO.postInfo("writer_" + sessionId, writerInfo);
```

This allows cleanup to:
- Find the writer PID
- Verify it's not a reused PID (via start time)
- Find the parent PID
- Check if parent is still alive

---

## Updated Phase Breakdown

### Phase 1: Foundation - Utility Classes (Est: 2-3 hours)

**1.1 Create ProcessHelper utility** *(same as before)*

**1.2 Create ProcessSignalIO for IPC** *(same as before, plus writer info files)*

**1.3 Create JavaBinaryLocator utility** *(use Crash-Assistant approach above)*

---

### Phase 2: Message Queue Infrastructure (Est: 3-4 hours)

*(Same as before - no changes)*

---

### Phase 3: Writer Process Application (Est: 5-6 hours, +1 hour for heartbeat)

**3.1 Create writer process main class** *(add heartbeat checking)*

**3.2 Implement writer main loop** *(add heartbeat + freeze detection)*

**3.3 Create TxtFileWriter** *(add multiple crash marker types)*

**3.4 Create SqliteWriter** *(same as before)*

**3.5 Add writer info registration**
```java
// In ChatLogWriterApp.main() - after parsing args, before loop
registerWriterInfo(sessionId, parentPid, parentStartTime);
```

---

### Phase 4: Process Spawning (Est: 4-5 hours, +1 hour for heartbeat)

**4.1 Create ProcessSpawner utility** *(same as before)*

**4.2 Create ChildProcessLogger** *(same as before)*

**4.3 Create ProcessCleaner** *(use graceful cleanup approach above)*

---

### Phase 5: Refactor SimpleSessionRecorder (Est: 5-6 hours, +1 hour for heartbeat)

**5.1 Remove thread-based writing** *(same as before)*

**5.2 Add process-based writing** *(add heartbeat executor)*

**5.3 Refactor logMessage()** *(same as before)*

**5.4 Refactor shutdown()** *(add heartbeat stop)*
```java
private void shutdown() {
    // 1. Stop heartbeat
    if (heartbeatExecutor != null) {
        heartbeatExecutor.shutdown();
    }

    // ... rest same as before
}
```

**5.5 Remove old crash recovery code** *(same as before)*

---

### Phase 6: Refactor CrashRecovery (Est: 3-4 hours, +1 hour for TXT recovery)

**6.1 Update crash recovery** *(use graceful cleanup + keep TXT recovery)*

**6.2 Keep TXT-based recovery** *(keep hasProperEnding, appendCrashMarker methods)*

---

### Phase 7-10: *(Same as before)*

---

## Updated Total Estimated Time: 30-40 hours (+5 hours)

---

## Summary of Changes Based on Questions

### ✅ Heartbeat Feature
- Main process sends heartbeat every 2 seconds
- Writer detects freeze (no heartbeat for 10s) but continues processing
- Long freeze (5+ min) treated as crash
- Allows recovery from temporary freezes (GC, world loading)

### ✅ Java Binary Location
- Use Crash-Assistant's robust approach
- Handles different JDK layouts
- Falls back to ProcessHandle command if needed
- Cross-platform compatible

### ✅ TXT Recovery Kept
- Writer process writes crash markers
- CrashRecovery checks incomplete TXT files
- Handles writer process crashes
- Three marker types: normal, parent crash, writer crash

### ✅ Graceful Orphaned Process Cleanup
- Monitors ack file for progress
- Sends shutdown signal first
- Waits up to 30 seconds
- Detects stalls (5s no progress)
- Force kills only as last resort
- Marks session for recovery if force-killed

---

## Risk Mitigation Updates

### Additional High Risk Areas:
1. **Writer process freeze detection**: Heartbeat timeout tuning needed
2. **Orphaned cleanup race conditions**: Writer might finish just as cleanup runs
3. **Ack file concurrency**: Multiple readers (cleanup + monitoring)

### Additional Testing Scenarios:
1. **Freeze/unfreeze**: Pause Minecraft (debugger), verify writer continues
2. **Orphaned cleanup**: Kill parent, start new instance, verify graceful cleanup
3. **Heartbeat loss**: Network lag on multiplayer servers
4. **Writer crash**: Kill -9 writer process, verify TXT recovery works
