package fr.raconteur.chatlogs.utils;

import fr.raconteur.chatlogs.ChatLogsMod;

import java.time.Instant;
import java.util.Optional;

/**
 * Utility class for process management and inspection.
 * Based on Crash-Assistant's ProcessHelper pattern.
 */
public class ProcessHelper {

    /**
     * Get the process ID (PID) of the current process.
     *
     * @return current process ID
     */
    public static long getCurrentProcessId() {
        return ProcessHandle.current().pid();
    }

    /**
     * Get the command used to start the current process.
     *
     * @return optional containing the command, or empty if unavailable
     */
    public static Optional<String> getCurrentProcessCommand() {
        return ProcessHandle.current().info().command();
    }

    /**
     * Get the start time of the current process in milliseconds since epoch.
     *
     * @return start time in milliseconds, or -1 if unavailable
     */
    public static long getCurrentProcessStartTime() {
        return ProcessHandle.current()
            .info()
            .startInstant()
            .map(Instant::toEpochMilli)
            .orElse(-1L);
    }

    /**
     * Get the start time of a specific process in milliseconds since epoch.
     * This is crucial for detecting PID reuse by the operating system.
     *
     * @param pid process ID to query
     * @return start time in milliseconds, or -1 if process not found or unavailable
     */
    public static long getProcessStartTime(long pid) {
        Optional<ProcessHandle> processHandle = ProcessHandle.of(pid);
        if (processHandle.isEmpty()) {
            return -1;
        }
        return processHandle.get()
            .info()
            .startInstant()
            .map(Instant::toEpochMilli)
            .orElse(-1L);
    }

    /**
     * Check if a process with the given PID is currently alive.
     * Note: This doesn't prevent PID reuse! Use getProcessStartTime() to verify identity.
     *
     * @param pid process ID to check
     * @return true if process exists and is alive, false otherwise
     */
    public static boolean isProcessAlive(long pid) {
        Optional<ProcessHandle> processHandle = ProcessHandle.of(pid);
        return processHandle.isPresent() && processHandle.get().isAlive();
    }

    /**
     * Check if a process is alive AND matches the expected start time.
     * This prevents false positives from PID reuse.
     *
     * @param pid process ID to check
     * @param expectedStartTime expected start time in milliseconds since epoch
     * @return true if process is alive and start time matches, false otherwise
     */
    public static boolean isProcessAliveWithStartTime(long pid, long expectedStartTime) {
        if (!isProcessAlive(pid)) {
            return false;
        }
        long actualStartTime = getProcessStartTime(pid);
        return actualStartTime == expectedStartTime;
    }

    /**
     * Attempt to gracefully terminate a process.
     * This sends a termination signal that allows the process to clean up.
     *
     * @param pid process ID to terminate
     * @return true if termination request was sent successfully, false if process not found
     */
    public static boolean destroyProcess(long pid) {
        Optional<ProcessHandle> processHandle = ProcessHandle.of(pid);
        if (processHandle.isEmpty()) {
            ChatLogsMod.LOGGER.warn("Cannot destroy process {}: not found", pid);
            return false;
        }
        boolean success = processHandle.get().destroy();
        if (success) {
            ChatLogsMod.LOGGER.debug("Sent termination signal to process {}", pid);
        } else {
            ChatLogsMod.LOGGER.warn("Failed to send termination signal to process {}", pid);
        }
        return success;
    }

    /**
     * Forcibly terminate a process immediately without cleanup.
     * This should only be used as a last resort when graceful termination fails.
     *
     * @param pid process ID to force kill
     * @return true if kill request was sent successfully, false if process not found
     */
    public static boolean destroyProcessForcibly(long pid) {
        Optional<ProcessHandle> processHandle = ProcessHandle.of(pid);
        if (processHandle.isEmpty()) {
            ChatLogsMod.LOGGER.warn("Cannot force kill process {}: not found", pid);
            return false;
        }
        boolean success = processHandle.get().destroyForcibly();
        if (success) {
            ChatLogsMod.LOGGER.warn("Forcibly killed process {}", pid);
        } else {
            ChatLogsMod.LOGGER.error("Failed to forcibly kill process {}", pid);
        }
        return success;
    }

    /**
     * Get information about the current Java runtime version.
     *
     * @return Java version string
     */
    public static String getJavaVersion() {
        return Runtime.version().toString();
    }
}
