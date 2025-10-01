package fr.raconteur.chatlogs.process;

import fr.raconteur.chatlogs.ChatLogsMod;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;

/**
 * Captures and logs output from a child process (writer process).
 * Based on Crash-Assistant's ChildProcessLogger pattern.
 * <p>
 * Runs daemon threads to capture stdout and stderr without blocking the main game thread.
 */
public class ChildProcessLogger extends Thread {
    private final InputStream inputStream;
    private final boolean isErrorStream;
    private final Process process;
    private ChildProcessLogger otherLogger;

    /**
     * Create a process output logger.
     *
     * @param inputStream   the process output stream to read from
     * @param isErrorStream true if this is stderr, false if stdout
     * @param process       the process being monitored
     */
    private ChildProcessLogger(InputStream inputStream, boolean isErrorStream, Process process) {
        this.inputStream = inputStream;
        this.isErrorStream = isErrorStream;
        this.process = process;
        this.setDaemon(true);
        this.setName("ChatLogWriter-" + (isErrorStream ? "stderr" : "stdout"));
    }

    @Override
    public void run() {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {
            String line;
            while ((line = reader.readLine()) != null) {
                // Log to parent process log
                if (isErrorStream) {
                    ChatLogsMod.LOGGER.error("[Writer Process] {}", line);
                } else {
                    ChatLogsMod.LOGGER.info("[Writer Process] {}", line);
                }

                // Check if process has exited
                if (!process.isAlive()) {
                    break;
                }
            }
        } catch (IOException e) {
            if (process.isAlive()) {
                ChatLogsMod.LOGGER.error("Error reading writer process output", e);
            }
            // If process is dead, IOException is expected
        }

        // Interrupt the other logger when we're done
        if (otherLogger != null && otherLogger.isAlive()) {
            otherLogger.interrupt();
        }
    }

    /**
     * Start capturing output from a process.
     * Creates two daemon threads: one for stdout, one for stderr.
     *
     * @param process the process to capture output from
     */
    public static void captureOutput(Process process) {
        ChildProcessLogger stdoutLogger = new ChildProcessLogger(
            process.getInputStream(),
            false,
            process
        );

        ChildProcessLogger stderrLogger = new ChildProcessLogger(
            process.getErrorStream(),
            true,
            process
        );

        // Link loggers so they can interrupt each other when done
        stdoutLogger.otherLogger = stderrLogger;
        stderrLogger.otherLogger = stdoutLogger;

        stdoutLogger.start();
        stderrLogger.start();

        ChatLogsMod.LOGGER.debug("Started output capture for writer process {}", process.pid());

        // Schedule a check after 5 seconds to see if process started successfully
        new Thread(() -> {
            try {
                Thread.sleep(5000);
                if (!process.isAlive()) {
                    int exitCode = process.exitValue();
                    if (exitCode != 0) {
                        ChatLogsMod.LOGGER.error("Writer process failed to start (exit code: {})", exitCode);
                    } else {
                        ChatLogsMod.LOGGER.info("Writer process completed quickly (exit code: 0)");
                    }
                }
            } catch (InterruptedException e) {
                // Ignore
            }
        }, "WriterProcessStartupCheck").start();
    }
}
