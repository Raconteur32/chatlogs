# Crash-Assistant Case Study: Process Management & IPC

## Overview

This document analyzes how the [Crash-Assistant mod](https://github.com/KostromDan/Crash-Assistant) implements a **separate monitoring process** and **interprocess communication (IPC)** to detect and analyze Minecraft crashes. This case study is for understanding how to apply similar patterns to the Chat Logs mod for reliable log writing.

---

## Architecture Overview

Crash-Assistant uses a **two-process architecture**:

1. **Main Process (Minecraft mod)**: Runs inside the game as a Fabric/Forge mod
2. **Monitoring Process (CrashAssistantApp)**: Separate Java process that watches the main process

```
┌─────────────────────────────────────┐
│   Minecraft (Main Process)          │
│   ├─ Fabric/Forge Mod Loader        │
│   └─ Crash Assistant Mod             │
│      └─ Spawns monitoring process   │
└──────────────┬──────────────────────┘
               │ Process spawn + IPC
               │
┌──────────────▼──────────────────────┐
│   CrashAssistantApp (Child Process) │
│   ├─ Monitors parent PID             │
│   ├─ Reads signal files              │
│   └─ Shows GUI on crash              │
└─────────────────────────────────────┘
```

**Key Benefit**: The monitoring process survives even if Minecraft crashes, allowing it to analyze logs and present results to the user.

---

## Process Spawning Mechanism

### 1. Entry Point

**Location**: `JarInJarHelper.launchCrashAssistantApp()` ([JarInJarHelper.java:33](Crash-Assistant/common_config/src/main/java/dev/kostromdan/mods/crash_assistant/common_config/loading_utils/JarInJarHelper.java#L33))

Called during mod initialization when client starts.

### 2. JAR Extraction Process

The monitoring app is **embedded inside the mod JAR** (Jar-in-Jar pattern) and must be extracted before launching:

```java
// Extract embedded app.jar from META-INF/jarjar/
Path tempAppJarPath = extractJarInJar("app.jar", currentProcessData + "_app.jar");

// Copy mod JAR itself for classpath
Path tempModJarPath = tempDir.resolve(currentProcessData + "_mod.jar");
Files.copy(originalModJarPath, tempModJarPath, StandardCopyOption.REPLACE_EXISTING);
```

**Storage location**: `local/crash_assistant/` directory
- Format: `{PID}_{startTime}_app.jar`
- Also creates: `{PID}_{startTime}_mod.jar` (copy of mod JAR)
- Purpose: Each Minecraft instance gets unique temp files

**Why copy files?**
- Allows independent lifecycle (child process can outlive parent)
- Prevents file locking issues on Windows
- Enables cleanup of old crashed instances

### 3. Classpath Assembly

The child process needs access to dependencies:

```java
List<String> classPathEntries = new ArrayList<>();
classPathEntries.add(tempAppJarPath.toString());  // The monitoring app
classPathEntries.add(tempModJarPath.toString());  // Mod JAR (shared code)

// Add required libraries using LibrariesJarLocator
for (Class<?> clazz : ProcessHelper.getNeededForAppClasses()) {
    classPathEntries.add(LibrariesJarLocator.getLibraryJarPath(clazz));
}

String fullClassPath = String.join(System.getProperty("path.separator"), classPathEntries);
```

**Required libraries** ([ProcessHelper.java:70](Crash-Assistant/common_config/src/main/java/dev/kostromdan/mods/crash_assistant/common_config/utils/ProcessHelper.java#L70)):
- `log4j` (logging)
- `gson` (JSON parsing)
- `commons-io` (file operations)
- `jna` (native calls for Windows)

### 4. Arguments Preparation

Arguments are **written to a file** (not passed via command line) to avoid length limits:

```java
List<String> argsList = new ArrayList<>();
argsList.add("-jarPath");
argsList.add(tempAppJarPath.toString());
argsList.add("-parentPID");
argsList.add(Objects.toString(ProcessHelper.getCurrentProcessId()));
argsList.add("-parentStarted");
argsList.add(Objects.toString(ProcessHelper.getCurrentProcessStartTime()));
argsList.add("-platform");
argsList.add(PlatformHelp.platform.toString());
// ... more arguments ...

Path argsFile = Paths.get("local", "crash_assistant", currentProcessData + "_args.info");
Files.write(argsFile, argsList, StandardCharsets.UTF_8);
```

**Key arguments**:
- `parentPID`: Process ID to monitor
- `parentStarted`: Start time for validation (prevents PID reuse detection)
- `platform`: Fabric/Forge/NeoForge
- `minecraftVersion`: For version-specific crash detection
- Memory settings: `parentXms`, `parentXmx`, `systemRAM`

### 5. Process Launch

Using `ProcessBuilder` with optimized JVM flags:

```java
ProcessBuilder crashAssistantAppProcessBuilder = new ProcessBuilder(
    JavaBinaryLocator.getJavaBinary(),  // Same Java as parent
    "-XX:+UseSerialGC",                 // Low-overhead GC
    "-XX:MaxHeapFreeRatio=30",
    "-XX:MinHeapFreeRatio=10",
    "-XX:MaxGCPauseMillis=10000",
    "-Xms8m",                           // Minimal memory footprint
    "-Xmx512m",
    "-cp",
    fullClassPath,
    "dev.kostromdan.mods.crash_assistant.app.class_loading.Boot",
    "--args-file",
    argsFile.toString()
);

Process crashAssistantAppProcess = crashAssistantAppProcessBuilder.start();
ChildProcessLogger.captureOutput(crashAssistantAppProcess);
```

**JVM optimizations**:
- Serial GC: Simpler, less CPU overhead for small app
- Small heap: 8MB min, 512MB max (monitoring app is lightweight)
- Same Java binary: Ensures compatibility

### 6. Output Capture

Child process stdout/stderr are captured via dedicated threads ([ChildProcessLogger.java:75](Crash-Assistant/common_config/src/main/java/dev/kostromdan/mods/crash_assistant/common_config/loading_utils/ChildProcessLogger.java#L75)):

```java
public static void captureOutput(Process p) {
    crashAssistantAppProcess = p;
    ChildProcessLogger err = new ChildProcessLogger(p.getErrorStream(), Level.ERROR);
    ChildProcessLogger out = new ChildProcessLogger(p.getInputStream(), Level.INFO);
    err.anotherChildProcessLogger = out;
    out.anotherChildProcessLogger = err;
    err.start();
    out.start();
}
```

**Key features**:
- Non-blocking: Daemon threads prevent game lag
- Logged to parent's log file: Debugging visibility
- Timeout: Auto-cleanup after 3 seconds if app fails to start

---

## Interprocess Communication (IPC)

Crash-Assistant uses **file-based IPC** via `ProcessSignalIO` class. This is simple, reliable, and cross-platform.

### IPC Mechanism: File-Based Signals

**Location**: `ProcessSignalIO` class ([ProcessSignalIO.java](Crash-Assistant/common_config/src/main/java/dev/kostromdan/mods/crash_assistant/common_config/communication/ProcessSignalIO.java))

**Storage directory**: `local/crash_assistant/`

### File Types

#### 1. Per-Process Signal Files (`.tmp`)

**Format**: `{name}_pid{PID}.tmp`

**Purpose**: Point-to-point communication between specific processes

**Operations**:
```java
// Write signal from current process
ProcessSignalIO.post("game_launched");  // Writes timestamp
ProcessSignalIO.post("renderer", "OpenGL 4.6");  // Writes custom data

// Write signal to another process
ProcessSignalIO.postAsOtherProcess("shutdown", targetPID);

// Read signal from specific process
Optional<String> data = ProcessSignalIO.get("game_launched", somePID);

// Check if signal exists
boolean exists = ProcessSignalIO.exists("crash_detected", parentPID);
```

**Example files**:
- `game_launched_pid12345.tmp` - Contains timestamp when game finished loading
- `renderer_pid12345.tmp` - Contains GPU renderer string
- `loading_error_fml_pid12345.tmp` - Signals mod loading error

#### 2. Shared Info Files (`.info`)

**Format**: `{name}.info`

**Purpose**: Broadcast information to all processes

**Operations**:
```java
// Write shared info
ProcessSignalIO.postInfo("version", "1.2.3");

// Read shared info
Optional<String> version = ProcessSignalIO.getInfo("version");

// Check if shared info exists
boolean hasVersion = ProcessSignalIO.existsInfo("version");
```

**Example files**:
- `{PID}_{startTime}.info` - Child process PID and start time
- `{PID}_{startTime}_args.info` - Arguments file

### Signal Flow Examples

#### Example 1: Detect Game Launch

**Main process (Minecraft)**:
```java
// After game successfully loads (TitleScreenMixin)
ProcessSignalIO.post("game_launched");
```

**Monitoring process**:
```java
while (true) {
    if (ProcessSignalIO.exists("game_launched", parentPID)) {
        gameLaunchedSuccessfully = true;
        LOGGER.info("Game launched successfully");
    }
    Thread.sleep(1000);
}
```

#### Example 2: GPU Renderer Detection

**Main process**:
```java
// Extract renderer from OpenGL context
String renderer = GL11.glGetString(GL11.GL_RENDERER);
ProcessSignalIO.post("renderer", renderer);
```

**Monitoring process**:
```java
Optional<String> rendererData = ProcessSignalIO.get("renderer", parentPID);
if (rendererData.isPresent()) {
    analyzeGPU(rendererData.get());
}
```

#### Example 3: Crash Detection

**Monitoring process loop** ([CrashAssistantApp.java:124](Crash-Assistant/app/src/main/java/dev/kostromdan/mods/crash_assistant/app/CrashAssistantApp.java#L124)):
```java
while (true) {
    // Check if parent process is still alive
    if (Boot.parentStarted == -1 ||
        Boot.parentStarted != ProcessHelper.getProcessStartTime(Boot.parentPID)) {
        LOGGER.info("PID {} stopped. Minecraft JVM appears to have stopped.", Boot.parentPID);
        onMinecraftFinished();  // Analyze logs and show GUI
        return;
    }
    Thread.sleep(1000);
}
```

**Process validation**:
- Not just checking if PID exists
- **Validates start time** to detect PID reuse by OS
- Uses `ProcessHandle.of(pid).info().startInstant()`

### IPC Implementation Details

**File operations** ([ProcessSignalIO.java:53](Crash-Assistant/common_config/src/main/java/dev/kostromdan/mods/crash_assistant/common_config/communication/ProcessSignalIO.java#L53)):

```java
public static void post(String name, String data) {
    String fileName = name + "_pid" + ProcessHelper.getCurrentProcessId() + ".tmp";
    Path filePath = BASE_DIR.resolve(fileName);
    try {
        Files.write(
            filePath,
            data.getBytes(StandardCharsets.UTF_8),
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE
        );
    } catch (IOException e) {
        LOGGER.error("Error while saving data to {}", fileName, e);
    }
}
```

**Characteristics**:
- ✅ **Simple**: Plain text files, easy to debug
- ✅ **Reliable**: Filesystem guarantees atomicity for small writes
- ✅ **Cross-platform**: Works on Windows, Linux, macOS
- ✅ **Fire-and-forget**: No need for handshake/acknowledgment
- ⚠️ **Polling-based**: Monitoring process checks periodically (1 second intervals)
- ⚠️ **Not real-time**: Slight delay in signal propagation

---

## Cleanup & Resource Management

### 1. Old App Process Cleanup

**Function**: `JarInJarHelper.killAndDeleteOldApps()` ([JarInJarHelper.java:251](Crash-Assistant/common_config/src/main/java/dev/kostromdan/mods/crash_assistant/common_config/loading_utils/JarInJarHelper.java#L251))

Called before launching new monitoring process.

**Steps**:
1. Find old `{PID}_{startTime}_app.jar` files
2. Read corresponding `.info` file to get child PID
3. Check if parent process is still alive:
   - If parent dead but child alive → kill child process
4. Delete temp files after 5 second delay

**Why delay deletion?**
- Gives OS time to release file handles
- Prevents "file in use" errors on Windows

### 2. Signal File Cleanup

**Automatic cleanup**:
- `.tmp` files are transient, deleted on next launch
- `.info` files cleaned up when corresponding JAR is deleted

**Manual cleanup**:
- `WinEventCleaner.cleanOldWinEventFiles()` for Windows event logs
- `FileUtils.removeTmpFiles()` for orphaned temp files

### 3. Orphaned Process Detection

Uses process start time validation:

```java
boolean isAppProcessAlive = ProcessHelper.isProcessAlive(app_pid);
long minecraftStartTime = ProcessHelper.getProcessStartTime(minecraft_pid);

if (isAppProcessAlive &&
    app_start_time == ProcessHelper.getProcessStartTime(app_pid) &&
    !(ProcessHelper.isProcessAlive(minecraft_pid) && minecraftStartTime == start_time)) {

    LOGGER.warn("Closed old CrashAssistantApp process");
    ProcessHelper.destroyProcess(app_pid);
}
```

**PID reuse protection**: Comparing start times prevents killing unrelated processes that reused same PID.

---

## Key Takeaways for Chat Logs Mod

### What to Adopt

#### 1. **Separate Process Architecture**
- ✅ Survives main process crashes
- ✅ Can perform cleanup/finalization after crash
- ✅ No risk of corrupting data during crash

#### 2. **File-Based IPC**
- ✅ Simple to implement
- ✅ No external dependencies
- ✅ Easy to debug (can inspect files manually)
- ✅ Cross-platform

**Recommended for Chat Logs**:
```
Main Process → Writes message data to queue file
Writer Process → Reads queue, writes to TXT/SQLite, sends ACK
```

#### 3. **Process Validation Pattern**
```java
long parentPID = ...;
long parentStartTime = ...;

while (true) {
    if (parentStartTime != ProcessHelper.getProcessStartTime(parentPID)) {
        // Parent crashed or restarted
        finalizeAndCleanup();
        break;
    }
    // Continue processing
}
```

#### 4. **Temp File Management**
- Use unique filenames: `{PID}_{timestamp}_{purpose}.tmp`
- Store in dedicated directory: `local/chatlogs_writer/`
- Clean up old files on startup

#### 5. **JVM Optimization for Child Process**
```java
ProcessBuilder writerProcessBuilder = new ProcessBuilder(
    JavaBinaryLocator.getJavaBinary(),
    "-XX:+UseSerialGC",       // Low overhead
    "-Xms16m",                // Small initial heap
    "-Xmx256m",               // Moderate max heap
    "-cp", classPath,
    "fr.raconteur.chatlogs.writer.ChatLogWriterApp",
    "--parent-pid", parentPID,
    "--session-id", sessionId
);
```

### What to Adapt

#### 1. **Communication Protocol**

For Chat Logs, we need **bidirectional** communication:

```
Message Queue File Format:
- chatlogs/queue/{sessionId}_messages.queue
- Append-only, line-based JSON
- Writer process reads, deletes after processing

Acknowledgment File:
- chatlogs/queue/{sessionId}_ack.tmp
- Contains last processed message ID
- Main process can verify progress
```

#### 2. **Graceful Shutdown**

Crash-Assistant only cares about crashes. Chat Logs needs graceful shutdown too:

```java
// Main process signals shutdown
ProcessSignalIO.post("shutdown_request");

// Writer process responds
ProcessSignalIO.postAsOtherProcess("shutdown_complete", parentPID);

// Main process waits for completion
int timeout = 5000;
while (timeout > 0 && !ProcessSignalIO.exists("shutdown_complete", currentPID)) {
    Thread.sleep(100);
    timeout -= 100;
}
```

#### 3. **Crash Recovery**

Writer process can detect crash and finalize incomplete session:

```java
// Writer process loop
while (true) {
    if (!ProcessHelper.isProcessAlive(parentPID)) {
        LOGGER.warn("Parent process crashed, finalizing session");
        appendCrashMarkerToTxtFile();
        closeUnfinishedSqliteSession();
        System.exit(0);
    }
}
```

### What NOT to Adopt

#### ❌ **JAR-in-JAR Extraction**

**Crash-Assistant reason**: Needs GUI libraries, complex classpath

**Chat Logs**: Writer process can be simpler
- Alternative: Use same mod JAR with different main class
- Or: Single-file writer (compile as separate JAR)

#### ❌ **Extensive Logging Capture**

**Crash-Assistant**: Captures child stdout/stderr for debugging

**Chat Logs**: Writer process should be silent (minimal logging)
- Reduces overhead
- Logging to file can cause recursive issues

#### ❌ **Timeout-Based Cleanup**

**Crash-Assistant**: 3-second timeout for app startup

**Chat Logs**: Writer must be more patient
- Messages may come in bursts
- Use inactivity timeout (e.g., 30 seconds) instead

---

## Proposed Architecture for Chat Logs Writer Process

### High-Level Design

```
┌──────────────────────────────────────────┐
│   Minecraft + Chat Logs Mod              │
│   ├─ Captures chat messages              │
│   ├─ Writes to message queue file        │
│   └─ Spawns writer process on session    │
└────────────┬─────────────────────────────┘
             │
             │ IPC via queue files
             │
┌────────────▼─────────────────────────────┐
│   ChatLogWriterApp (Separate Process)    │
│   ├─ Monitors parent process              │
│   ├─ Reads message queue files            │
│   ├─ Writes to TXT + SQLite               │
│   ├─ Sends acknowledgments                │
│   └─ Finalizes on crash/shutdown          │
└──────────────────────────────────────────┘
```

### Message Queue Format

**File**: `chatlogs/queue/{sessionId}_messages.jsonl`

**Format**: JSON Lines (one message per line)

```json
{"id":1,"timestamp":1234567890,"text":"<Alice> Hello","json":"{...}","sender":"Alice"}
{"id":2,"timestamp":1234567891,"text":"<Bob> Hi","json":"{...}","sender":"Bob"}
```

**Properties**:
- Append-only: Main process only appends
- Sequential IDs: For tracking progress
- Self-contained: Each line is valid JSON

### Communication Signals

**Main → Writer**:
- `session_started_{sessionId}.tmp` - Session initialization
- `shutdown_request_{sessionId}.tmp` - Graceful shutdown
- Message queue file grows

**Writer → Main**:
- `writer_ready_{sessionId}.tmp` - Writer process started
- `last_processed_{sessionId}.tmp` - Contains last message ID (updated periodically)
- `writer_stopped_{sessionId}.tmp` - Writer finished

### Lifecycle

#### 1. Session Start
```java
// Main process
long sessionId = db.createSession(...);
ProcessSignalIO.post("session_started_" + sessionId, String.valueOf(sessionId));

// Spawn writer
spawnWriterProcess(sessionId, parentPID, parentStartTime);

// Wait for writer ready
waitForSignal("writer_ready_" + sessionId, 5000);
```

#### 2. Message Logging
```java
// Main process - fast, non-blocking
appendToQueue(sessionId, message);
```

#### 3. Writer Loop
```java
// Writer process
while (true) {
    // Check parent alive
    if (parentCrashed()) {
        finalizeSession();
        break;
    }

    // Check shutdown signal
    if (shutdownRequested()) {
        processRemainingMessages();
        sendStopSignal();
        break;
    }

    // Process messages
    List<Message> newMessages = readQueueFile(lastProcessedId);
    for (Message msg : newMessages) {
        writeTxtFile(msg);
        writeSqliteDb(msg);
        lastProcessedId = msg.id;
    }

    // Update acknowledgment
    updateAckFile(lastProcessedId);

    Thread.sleep(100);  // Poll interval
}
```

#### 4. Graceful Shutdown
```java
// Main process
ProcessSignalIO.post("shutdown_request_" + sessionId);
waitForSignal("writer_stopped_" + sessionId, 10000);

// If timeout, kill writer
if (!writerStopped) {
    ProcessHelper.destroyProcess(writerPID);
}
```

### Advantages Over Current Thread-Based Approach

| Aspect | Current (Thread) | Proposed (Process) |
|--------|------------------|-------------------|
| **Crash safety** | ❌ Dies with main thread | ✅ Survives crash |
| **Data loss risk** | ⚠️ Queue in memory | ✅ Queue on disk |
| **Recovery** | ⚠️ Marker-based | ✅ Automatic |
| **File corruption** | ⚠️ Possible on crash | ✅ Prevented |
| **Debugging** | ⚠️ Limited | ✅ Can inspect queue files |
| **Lock management** | ⚠️ Complex | ✅ Process-level |

---

## Implementation Checklist

### Phase 1: Basic Process Spawning
- [ ] Create `ChatLogWriterApp` main class
- [ ] Implement process spawning in `SimpleSessionRecorder`
- [ ] Add `ProcessHelper` utility (copy from Crash-Assistant)
- [ ] Test process creation and termination

### Phase 2: File-Based IPC
- [ ] Implement `ProcessSignalIO` utility
- [ ] Create message queue file writer
- [ ] Create message queue file reader
- [ ] Test queue file append/read cycles

### Phase 3: Writer Process Logic
- [ ] Implement parent process monitoring
- [ ] Implement queue file processing
- [ ] Add TXT file writing
- [ ] Add SQLite writing
- [ ] Implement acknowledgment updates

### Phase 4: Crash Handling
- [ ] Detect parent crash in writer
- [ ] Implement session finalization
- [ ] Test crash recovery scenarios
- [ ] Verify no data loss

### Phase 5: Cleanup & Polish
- [ ] Implement old queue file cleanup
- [ ] Add orphaned writer detection
- [ ] Optimize polling intervals
- [ ] Add comprehensive logging
- [ ] Test on all platforms (Windows/Linux/macOS)

---

## Conclusion

The Crash-Assistant mod demonstrates a robust **two-process architecture** with **file-based IPC** that can be adapted for the Chat Logs mod. Key insights:

1. **Separate processes provide crash resilience** - Writer survives game crashes
2. **File-based IPC is simple and reliable** - No complex networking or shared memory
3. **Process validation prevents PID reuse bugs** - Start time comparison is crucial
4. **Cleanup and resource management matter** - Handle orphaned processes and temp files
5. **JVM tuning reduces overhead** - Child process can be very lightweight

This architecture would significantly improve the reliability of chat log saving, eliminating the need for complex crash recovery mechanisms and ensuring data integrity even in unexpected shutdown scenarios.
