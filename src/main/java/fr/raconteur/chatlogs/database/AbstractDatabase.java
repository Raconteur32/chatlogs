package fr.raconteur.chatlogs.database;

import fr.raconteur.chatlogs.ChatLogsMod;
import fr.raconteur.chatlogs.session.SimpleSessionRecorder;

import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Scanner;

public abstract class AbstractDatabase {
    private final String dbFileName;
    private final Path dbPath;
    private final Path backupDir;
    private final String versioningResourcePath;
    private Connection connection;
    
    // Inter-process locking
    private RandomAccessFile lockRaf;
    private FileLock processLock;

    public AbstractDatabase(String dbFileName) throws SQLException {
        this.dbFileName = dbFileName;
        
        // Use the same folder as chatlogs
        this.dbPath = SimpleSessionRecorder.CHATLOG_FOLDER.toPath().resolve(dbFileName);
        this.backupDir = SimpleSessionRecorder.CHATLOG_FOLDER.toPath().resolve("backups").resolve(dbFileName.replace(".db", ""));
        this.versioningResourcePath = "database/" + dbFileName.replace(".db", "") + "/";
        
        initializeDatabase();
    }

    private void initializeDatabase() throws SQLException {
        try {
            // Create directories if they don't exist
            Files.createDirectories(dbPath.getParent());
            Files.createDirectories(backupDir);
            
            // Clean up any orphaned lock files first
            cleanupOrphanedLocks();
            
            // Acquire inter-process lock
            acquireProcessLock();
            
            boolean isNewDatabase = !Files.exists(dbPath);
            
            // Create connection
            String url = "jdbc:sqlite:" + dbPath.toString();
            connection = DriverManager.getConnection(url);
            connection.setAutoCommit(false);
            
            ChatLogsMod.LOGGER.info("Connected to database: {}", dbPath);
            
            // Initialize version tracking table
            initializeVersionTable();
            
            // Apply migrations
            applyMigrations(isNewDatabase);
            
        } catch (IOException e) {
            releaseProcessLock();
            throw new SQLException("Failed to initialize database directories", e);
        }
    }

    private void initializeVersionTable() throws SQLException {
        String createVersionTable = """
            CREATE TABLE IF NOT EXISTS db_version (
                version INTEGER PRIMARY KEY,
                applied_at TEXT NOT NULL,
                description TEXT
            )
            """;
        
        try (Statement stmt = connection.createStatement()) {
            stmt.execute(createVersionTable);
        }
    }

    private void applyMigrations(boolean isNewDatabase) throws SQLException {
        int currentVersion = getCurrentVersion();
        List<Integer> availableVersions = getAvailableVersions();
        
        if (availableVersions.isEmpty()) {
            ChatLogsMod.LOGGER.warn("No migration files found for database: {}", dbFileName);
            return;
        }
        
        Collections.sort(availableVersions);
        
        for (Integer version : availableVersions) {
            if (version > currentVersion) {
                applyMigration(version, isNewDatabase && version == availableVersions.get(0));
            }
        }
    }

    private int getCurrentVersion() throws SQLException {
        String query = "SELECT MAX(version) FROM db_version";
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(query)) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    private List<Integer> getAvailableVersions() {
        List<Integer> versions = new ArrayList<>();
        
        // Try to find version files in resources
        int version = 1;
        while (true) {
            String resourcePath = versioningResourcePath + "v" + version + ".sql";
            ChatLogsMod.LOGGER.info("Looking for migration file: {}", resourcePath);
            try (InputStream is = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
                if (is == null) {
                    ChatLogsMod.LOGGER.info("Migration file not found: {}", resourcePath);
                    break;
                }
                ChatLogsMod.LOGGER.info("Found migration file: {}", resourcePath);
                versions.add(version);
                version++;
            } catch (Exception e) {
                throw new RuntimeException("Critical error checking for version file " + resourcePath + ": " + e.getMessage(), e);
            }
        }
        
        ChatLogsMod.LOGGER.info("Found {} migration files for database: {}", versions.size(), dbFileName);
        return versions;
    }

    private void applyMigration(int version, boolean skipBackup) throws SQLException {
        String resourcePath = versioningResourcePath + "v" + version + ".sql";
        
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            if (is == null) {
                throw new SQLException("Migration file not found: " + resourcePath);
            }
            
            // Create backup before migration (except for initial creation)
            if (!skipBackup) {
                createBackup(version);
            }
            
            // Read migration SQL
            String migrationSql;
            try (Scanner scanner = new Scanner(is, "UTF-8")) {
                scanner.useDelimiter("\\A");
                migrationSql = scanner.hasNext() ? scanner.next() : "";
            }
            
            // Execute migration in transaction
            executeInTransaction(() -> {
                try (Statement stmt = connection.createStatement()) {
                    // Split and execute SQL statements
                    String[] statements = migrationSql.split(";");
                    for (String sql : statements) {
                        String trimmedSql = sql.trim();
                        if (!trimmedSql.isEmpty()) {
                            stmt.execute(trimmedSql);
                        }
                    }
                    
                    // Record migration
                    recordMigration(version, "Applied migration v" + version);
                    
                } catch (SQLException e) {
                    throw new RuntimeException("Migration failed for version " + version, e);
                }
            });
            
            ChatLogsMod.LOGGER.info("Applied migration v{} to database: {}", version, dbFileName);
            
        } catch (IOException e) {
            throw new SQLException("Failed to read migration file: " + resourcePath, e);
        }
    }

    private void createBackup(int version) throws SQLException {
        try {
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            String backupFileName = String.format("%s_v%d_%s.db", dbFileName.replace(".db", ""), version - 1, timestamp);
            Path backupPath = backupDir.resolve(backupFileName);
            
            Files.copy(dbPath, backupPath, StandardCopyOption.REPLACE_EXISTING);
            ChatLogsMod.LOGGER.info("Created backup before migration v{}: {}", version, backupPath);
            
        } catch (IOException e) {
            throw new SQLException("Failed to create backup before migration", e);
        }
    }

    private void recordMigration(int version, String description) throws SQLException {
        String insert = "INSERT INTO db_version (version, applied_at, description) VALUES (?, ?, ?)";
        try (PreparedStatement stmt = connection.prepareStatement(insert)) {
            stmt.setInt(1, version);
            stmt.setString(2, LocalDateTime.now().toString());
            stmt.setString(3, description);
            stmt.executeUpdate();
        }
    }

    public void executeInTransaction(Runnable transaction) throws SQLException {
        try {
            transaction.run();
            connection.commit();
        } catch (Exception e) {
            connection.rollback();
            if (e instanceof SQLException) {
                throw (SQLException) e;
            } else {
                throw new SQLException("Transaction failed", e);
            }
        }
    }

    public <T> T executeInTransaction(TransactionCallable<T> transaction) throws SQLException {
        try {
            T result = transaction.call(connection);
            connection.commit();
            return result;
        } catch (Exception e) {
            connection.rollback();
            if (e instanceof SQLException) {
                throw (SQLException) e;
            } else {
                throw new SQLException("Transaction failed", e);
            }
        }
    }

    protected Connection getConnection() {
        return connection;
    }

    public void close() throws SQLException {
        if (connection != null && !connection.isClosed()) {
            connection.close();
            ChatLogsMod.LOGGER.info("Closed database connection: {}", dbFileName);
        }
        releaseProcessLock();
    }

    /**
     * Clean up any orphaned lock files from previous crashed processes
     */
    private void cleanupOrphanedLocks() {
        Path lockPath = Paths.get(dbPath.toString() + ".lock");
        if (Files.exists(lockPath)) {
            try {
                // Try to acquire lock to test if it's orphaned
                try (RandomAccessFile testRaf = new RandomAccessFile(lockPath.toFile(), "rw");
                     FileChannel testChannel = testRaf.getChannel()) {
                    
                    FileLock testLock = testChannel.tryLock();
                    if (testLock != null) {
                        // Lock was acquired, meaning it was orphaned
                        testLock.release();
                        Files.delete(lockPath);
                        ChatLogsMod.LOGGER.info("Cleaned up orphaned database lock file: {}", lockPath);
                    }
                }
            } catch (IOException e) {
                ChatLogsMod.LOGGER.warn("Failed to clean up potential orphaned lock file: {}", lockPath, e);
            }
        }
    }
    
    /**
     * Acquire inter-process lock for the database
     */
    private void acquireProcessLock() throws SQLException {
        try {
            Path lockPath = Paths.get(dbPath.toString() + ".lock");
            lockRaf = new RandomAccessFile(lockPath.toFile(), "rw");
            FileChannel channel = lockRaf.getChannel();
            
            processLock = channel.tryLock();
            if (processLock == null) {
                lockRaf.close();
                throw new SQLException("Minecraft may already be running on this instance. This is not supported by chatlogs mod. (Database locked: " + dbFileName + ")");
            }
            
            // Write PID for debugging purposes
            lockRaf.seek(0);
            lockRaf.writeUTF("PID:" + ProcessHandle.current().pid() + " DB:" + dbFileName);
            
            ChatLogsMod.LOGGER.info("Acquired database process lock: {}", dbFileName);
            
        } catch (IOException e) {
            throw new SQLException("Failed to acquire database process lock: " + dbFileName, e);
        }
    }
    
    /**
     * Release inter-process lock
     */
    private void releaseProcessLock() {
        try {
            if (processLock != null) {
                processLock.release();
                processLock = null;
                ChatLogsMod.LOGGER.debug("Released database process lock: {}", dbFileName);
            }
        } catch (IOException e) {
            ChatLogsMod.LOGGER.warn("Error releasing database process lock: {}", dbFileName, e);
        }
        
        try {
            if (lockRaf != null) {
                lockRaf.close();
                lockRaf = null;
            }
        } catch (IOException e) {
            ChatLogsMod.LOGGER.warn("Error closing database lock file: {}", dbFileName, e);
        }
        
        // Clean up lock file
        try {
            Path lockPath = Paths.get(dbPath.toString() + ".lock");
            if (Files.exists(lockPath)) {
                Files.delete(lockPath);
                ChatLogsMod.LOGGER.debug("Deleted database lock file: {}", lockPath);
            }
        } catch (IOException e) {
            ChatLogsMod.LOGGER.warn("Failed to delete database lock file: {}", dbFileName, e);
        }
    }

    @FunctionalInterface
    public interface TransactionCallable<T> {
        T call(Connection connection) throws SQLException;
    }
}