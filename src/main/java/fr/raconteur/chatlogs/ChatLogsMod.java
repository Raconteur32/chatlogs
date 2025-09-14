package fr.raconteur.chatlogs;

import net.fabricmc.api.ModInitializer;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import fr.raconteur.chatlogs.session.CrashRecovery;

public final class ChatLogsMod implements ModInitializer {
    public static final Logger LOGGER = LogManager.getLogger("chatlogs");
    public static final ThreadLocal<Boolean> PERMISSIVE_EVENTS = ThreadLocal.withInitial(() -> false);
    
	@Override
	public void onInitialize() {
		LOGGER.info("Initializing Chat Logs mod...");
		
		// Perform crash recovery on startup
		try {
			CrashRecovery.performRecovery();
		} catch (Exception e) {
			LOGGER.error("Failed to perform crash recovery", e);
		}
		
		LOGGER.info("Chat Logs mod initialized successfully");
	}
}