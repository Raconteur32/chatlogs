package fr.raconteur.chatlogs.ipc;

import fr.raconteur.chatlogs.ChatLogsMod;
import fr.raconteur.chatlogs.utils.ProcessHelper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Optional;

/**
 * Utility class for simple file-based inter-process communication.
 * Based on Crash-Assistant's ProcessSignalIO pattern.
 * <p>
 * Each message is stored in the directory {@code chatlogs/ipc} using two naming schemes:
 * </p>
 * <ul>
 *   <li>{@code <name>_pid<PID>.tmp} – per-process files for point-to-point signals.</li>
 *   <li>{@code <name>.info} – shared information visible to any process.</li>
 * </ul>
 * <p>Available operations:</p>
 * <ul>
 *   <li>{@link #post(String, String)} – write arbitrary data to a per-process file.</li>
 *   <li>{@link #post(String)} – write the current timestamp (ms) to a per-process file.</li>
 *   <li>{@link #get(String, long)} – read data from a per-process file.</li>
 *   <li>{@link #exists(String, long)} – check whether a per-process file exists.</li>
 *   <li>{@link #postInfo(String, String)} – write arbitrary data to a shared <code>.info</code> file.</li>
 *   <li>{@link #getInfo(String)} – read data from a shared <code>.info</code> file.</li>
 *   <li>{@link #existsInfo(String)} – check whether a shared <code>.info</code> file exists.</li>
 * </ul>
 */
public final class ProcessSignalIO {
    private static final Path BASE_DIR = Paths.get("chatlogs", "ipc");

    static {
        try {
            Files.createDirectories(BASE_DIR);
        } catch (IOException e) {
            ChatLogsMod.LOGGER.error("Unable to create IPC directory {}", BASE_DIR, e);
        }
    }

    private ProcessSignalIO() {
        // Utility class - no instantiation
    }

    /* ────────────────────────────────────────────────────── */
    /* Per-process TMP files                                 */
    /* ────────────────────────────────────────────────────── */

    /**
     * Writes {@code data} into a file named {@code <name>_pid<CURRENT_PID>.tmp}.
     * If the target file already exists, it will be overwritten.
     *
     * @param name logical identifier for the signal
     * @param data payload to store
     */
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
            ChatLogsMod.LOGGER.error("Error while saving data to {}", fileName, e);
        }
    }

    /**
     * Convenience overload: writes the current system time (ms) as the signal payload.
     *
     * @param name logical identifier for the signal
     */
    public static void post(String name) {
        post(name, Long.toString(System.currentTimeMillis()));
    }

    /**
     * Writes {@code data} into a file named {@code <name>_pid<pid>.tmp} for the specified process.
     * If the target file already exists, it will be overwritten.
     *
     * @param name logical identifier for the signal
     * @param data payload to store
     * @param pid  process ID to target
     */
    public static void postAsOtherProcess(String name, String data, long pid) {
        String fileName = name + "_pid" + pid + ".tmp";
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
            ChatLogsMod.LOGGER.error("Error while saving data to {}", fileName, e);
        }
    }

    /**
     * Convenience overload: writes the current system time (ms) to the specified process's file.
     *
     * @param name logical identifier for the signal
     * @param pid  process ID to target
     */
    public static void postAsOtherProcess(String name, long pid) {
        postAsOtherProcess(name, Long.toString(System.currentTimeMillis()), pid);
    }

    /**
     * Reads the contents of {@code <name>_pid<pid>.tmp}.
     *
     * @param name logical identifier for the signal
     * @param pid  process ID to read from
     * @return optional containing data if file exists and is readable, otherwise empty
     */
    public static Optional<String> get(String name, long pid) {
        String fileName = name + "_pid" + pid + ".tmp";
        Path filePath = BASE_DIR.resolve(fileName);

        if (!Files.isReadable(filePath)) {
            return Optional.empty();
        }
        try {
            byte[] bytes = Files.readAllBytes(filePath);
            String content = new String(bytes, StandardCharsets.UTF_8);
            return Optional.of(content);
        } catch (IOException e) {
            ChatLogsMod.LOGGER.error("Error while reading data from {}", fileName, e);
            return Optional.empty();
        }
    }

    /**
     * Checks whether {@code <name>_pid<pid>.tmp} exists.
     *
     * @param name logical identifier for the signal
     * @param pid  process ID to check for
     * @return true if the signal file exists, false otherwise
     */
    public static boolean exists(String name, long pid) {
        String fileName = name + "_pid" + pid + ".tmp";
        Path filePath = BASE_DIR.resolve(fileName);
        return Files.isRegularFile(filePath);
    }

    /**
     * Deletes {@code <name>_pid<pid>.tmp} if it exists.
     *
     * @param name logical identifier for the signal
     * @param pid  process ID whose signal file to delete
     * @return true if the file was deleted, false if it didn't exist or couldn't be deleted
     */
    public static boolean delete(String name, long pid) {
        String fileName = name + "_pid" + pid + ".tmp";
        Path filePath = BASE_DIR.resolve(fileName);
        try {
            return Files.deleteIfExists(filePath);
        } catch (IOException e) {
            ChatLogsMod.LOGGER.error("Error while deleting signal file {}", fileName, e);
            return false;
        }
    }

    /* ────────────────────────────────────────────────────── */
    /* Shared .info files                                    */
    /* ────────────────────────────────────────────────────── */

    /**
     * Writes {@code data} into a shared file named {@code <name>.info}.
     * The file is overwritten if it already exists.
     *
     * @param name logical identifier (e.g. {@code "version"})
     * @param data payload to store
     */
    public static void postInfo(String name, String data) {
        String fileName = name + ".info";
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
            ChatLogsMod.LOGGER.error("Error while saving info to {}", fileName, e);
        }
    }

    /**
     * Reads the contents of the shared file {@code <name>.info}.
     *
     * @param name logical identifier
     * @return optional containing data if readable; otherwise empty
     */
    public static Optional<String> getInfo(String name) {
        String fileName = name + ".info";
        Path filePath = BASE_DIR.resolve(fileName);

        if (!Files.isReadable(filePath)) {
            return Optional.empty();
        }
        try {
            byte[] bytes = Files.readAllBytes(filePath);
            String content = new String(bytes, StandardCharsets.UTF_8);
            return Optional.of(content);
        } catch (IOException e) {
            ChatLogsMod.LOGGER.error("Error while reading info from {}", fileName, e);
            return Optional.empty();
        }
    }

    /**
     * Checks whether {@code <name>.info} exists.
     *
     * @param name logical identifier
     * @return true if the info file exists, false otherwise
     */
    public static boolean existsInfo(String name) {
        String fileName = name + ".info";
        Path filePath = BASE_DIR.resolve(fileName);
        return Files.isRegularFile(filePath);
    }

    /**
     * Deletes {@code <name>.info} if it exists.
     *
     * @param name logical identifier for the info file to delete
     * @return true if the file was deleted, false if it didn't exist or couldn't be deleted
     */
    public static boolean deleteInfo(String name) {
        String fileName = name + ".info";
        Path filePath = BASE_DIR.resolve(fileName);
        try {
            return Files.deleteIfExists(filePath);
        } catch (IOException e) {
            ChatLogsMod.LOGGER.error("Error while deleting info file {}", fileName, e);
            return false;
        }
    }
}
