package fr.raconteur.chatlogs.utils;

import fr.raconteur.chatlogs.ChatLogsMod;

import java.util.Optional;

/**
 * Utility class to locate the Java binary executable used by the current process.
 * Simplified implementation based on Crash-Assistant's approach.
 */
public class JavaBinaryLocator {
    private static String cachedJavaBinary = null;

    /**
     * Locates the Java binary executable used by the current process.
     * The result is cached after the first successful lookup.
     * <p>
     * This method extracts the Java binary path from the current process command line,
     * ensuring we use the exact same Java executable as the parent process.
     *
     * @return absolute path to java executable
     * @throws RuntimeException if Java binary cannot be determined
     */
    public static String getJavaBinary() {
        if (cachedJavaBinary != null) {
            return cachedJavaBinary;
        }

        // Get the command used to start the current process
        Optional<String> processCommand = ProcessHelper.getCurrentProcessCommand();
        if (processCommand.isPresent()) {
            cachedJavaBinary = processCommand.get();
            ChatLogsMod.LOGGER.debug("Using Java binary from process command: {}", cachedJavaBinary);
            return cachedJavaBinary;
        }

        // Cannot proceed without knowing the Java binary
        throw new RuntimeException("Unable to determine Java binary from current process. " +
            "This is required to spawn the chat log writer process.");
    }

    /**
     * Clear the cached Java binary path.
     * Mainly useful for testing purposes.
     */
    public static void clearCache() {
        cachedJavaBinary = null;
    }
}
