package fr.raconteur.chatlogs;

import net.fabricmc.api.ModInitializer;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import fr.raconteur.chatlogs.config.ChatLogsConfig;
import fr.raconteur.chatlogs.database.SessionDatabase;
import fr.raconteur.chatlogs.session.CrashRecovery;

public final class ChatLogsMod implements ModInitializer {
    public static final Logger LOGGER = LogManager.getLogger("chatlogs");
    public static final ThreadLocal<Boolean> PERMISSIVE_EVENTS = ThreadLocal.withInitial(() -> false);
    
	@Override
	public void onInitialize() {
		LOGGER.info("Initializing Chat Logs mod...");
		
		// Initialize configuration
		try {
			ChatLogsConfig.getInstance();
			LOGGER.info("Chat logs configuration loaded successfully");
		} catch (Exception e) {
			LOGGER.error("Failed to initialize chat logs configuration", e);
			throw new RuntimeException("Critical error: Unable to initialize configuration", e);
		}
		
		// Initialize SQLite database
		try {
			SessionDatabase.getInstance();
			LOGGER.info("SQLite session database initialized successfully");
		} catch (Exception e) {
			LOGGER.error("Failed to initialize SQLite session database", e);
			throw new RuntimeException("Critical error: Unable to initialize session database", e);
		}
		
		// Perform crash recovery on startup
		try {
			CrashRecovery.performRecovery();
		} catch (Exception e) {
			LOGGER.error("Failed to perform crash recovery", e);
		}
		
		LOGGER.info("Chat Logs mod initialized successfully");
	}
}