package fr.raconteur.chatlogs.process;

import fr.raconteur.chatlogs.ChatLogsMod;
import fr.raconteur.chatlogs.utils.JavaBinaryLocator;
import fr.raconteur.chatlogs.utils.ProcessHelper;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.List;

/**
 * Utility class for spawning the chat log writer process.
 * Handles classpath assembly, argument construction, and process creation.
 */
public class ProcessSpawner {

    /**
     * Spawn the chat log writer process for a session.
     *
     * @param sessionId        unique session identifier
     * @param sessionName      name of the session (server/world)
     * @param isMultiplayer    whether this is a multiplayer session
     * @param txtFilePath      path to the TXT log file
     * @param sessionStartTime session start time in milliseconds
     * @return the spawned Process object
     * @throws IOException if process creation fails
     */
    public static Process spawnWriterProcess(
        long sessionId,
        String sessionName,
        boolean isMultiplayer,
        String txtFilePath,
        long sessionStartTime
    ) throws IOException {

        // 1. Get Java binary
        String javaBinary = JavaBinaryLocator.getJavaBinary();

        // 2. Build classpath
        String classPath = buildClassPath();

        // 3. Build command line arguments
        List<String> command = new ArrayList<>();
        command.add(javaBinary);

        // JVM arguments (optimized for lightweight writer process)
        command.add("-XX:+UseSerialGC");           // Simple GC for small process
        command.add("-Xms16m");                     // Minimal initial heap
        command.add("-Xmx256m");                    // Moderate max heap
        command.add("-cp");
        command.add(classPath);

        // Main class
        command.add("fr.raconteur.chatlogs.writer.ChatLogWriterApp");

        // Application arguments
        command.add("--session-id");
        command.add(String.valueOf(sessionId));
        command.add("--parent-pid");
        command.add(String.valueOf(ProcessHelper.getCurrentProcessId()));
        command.add("--parent-start-time");
        command.add(String.valueOf(ProcessHelper.getCurrentProcessStartTime()));
        command.add("--session-name");
        command.add(sessionName);
        command.add("--is-multiplayer");
        command.add(String.valueOf(isMultiplayer));
        command.add("--txt-file-path");
        command.add(txtFilePath);
        command.add("--session-start-time");
        command.add(String.valueOf(sessionStartTime));

        // 4. Create ProcessBuilder
        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.redirectErrorStream(false); // Keep stdout and stderr separate

        // Log the command
        ChatLogsMod.LOGGER.info("Spawning writer process for session {}", sessionId);
        ChatLogsMod.LOGGER.debug("Command: {}", String.join(" ", command));

        // 5. Start the process
        Process process = processBuilder.start();

        ChatLogsMod.LOGGER.info("Writer process started with PID: {}",
            process.pid());

        return process;
    }

    /**
     * Build the classpath for the writer process.
     * Includes the mod JAR and the SQLite JDBC driver.
     *
     * @return classpath string
     * @throws IOException if classpath construction fails
     */
    private static String buildClassPath() throws IOException {
        List<String> classPathEntries = new ArrayList<>();

        // 1. Add our mod JAR
        try {
            CodeSource codeSource = ChatLogsMod.class.getProtectionDomain().getCodeSource();
            if (codeSource != null) {
                Path modJarPath = Paths.get(codeSource.getLocation().toURI());
                classPathEntries.add(modJarPath.toAbsolutePath().toString());
                ChatLogsMod.LOGGER.debug("Mod JAR: {}", modJarPath);
            } else {
                throw new IOException("Unable to determine mod JAR location");
            }
        } catch (URISyntaxException e) {
            throw new IOException("Invalid mod JAR URI", e);
        }

        // 2. Add SQLite JDBC driver
        // The SQLite driver is included in the mod JAR via the 'include' directive,
        // so we don't need to add it separately. It's available in the mod JAR's classpath.

        // 3. Join with platform-specific separator
        String separator = System.getProperty("path.separator");
        String classPath = String.join(separator, classPathEntries);

        ChatLogsMod.LOGGER.debug("Writer process classpath: {}", classPath);
        return classPath;
    }

    /**
     * Wait for the writer process to send a "ready" signal.
     *
     * @param sessionId session ID to wait for
     * @param timeoutMs timeout in milliseconds
     * @return true if ready signal received, false if timeout
     */
    public static boolean waitForWriterReady(long sessionId, long timeoutMs) {
        long startTime = System.currentTimeMillis();

        while (System.currentTimeMillis() - startTime < timeoutMs) {
            if (fr.raconteur.chatlogs.ipc.ProcessSignalIO.exists("writer_ready_" + sessionId,
                ProcessHelper.getCurrentProcessId())) {
                return true;
            }

            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }

        return false;
    }
}
