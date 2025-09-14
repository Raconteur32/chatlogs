package fr.raconteur.chatlogs;

import net.fabricmc.api.ModInitializer;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public final class ChatLogsMod implements ModInitializer {
    public static final Logger LOGGER = LogManager.getLogger("chatlogs");
    public static final ThreadLocal<Boolean> PERMISSIVE_EVENTS = ThreadLocal.withInitial(() -> false);
    
	@Override
	public void onInitialize() {
	}
}