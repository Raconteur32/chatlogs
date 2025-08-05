package lovexyn0827.chatlog.session;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Properties;

import lovexyn0827.chatlog.PermanentChatLogMod;
import net.fabricmc.loader.api.FabricLoader;

class SessionUtils {
	static void wrapTextSerialization(RunnableWithIOException task) throws IOException {
		try {
			PermanentChatLogMod.PERMISSIVE_EVENTS.set(true);
			task.run();
		} finally {
			PermanentChatLogMod.PERMISSIVE_EVENTS.set(false);
		}
	}
	
	static int allocateId() {
		Properties prop = new Properties();
		File optionFile = FabricLoader.getInstance().getConfigDir().resolve("permanent-chat-logs.prop").toFile();
		if(!optionFile.exists()) {
			try {
				optionFile.createNewFile();
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
		
		try (FileReader fr = new FileReader(optionFile)) {
			prop.load(fr);
		} catch (IOException e) {
			Session.LOGGER.error("Unable to load configurations.");
			e.printStackTrace();
		}
		
		String nextIdStr = prop.computeIfAbsent("nextId", (k) -> "0").toString();
		int id;
		if(!nextIdStr.matches("\\d+")) {
			Session.LOGGER.error("Invalid next ID: {}", nextIdStr);
			id = findNextAvailableId(0);
		} else {
			id = Integer.parseInt(nextIdStr);
			if(!checkAvailability(id)) {
				Session.LOGGER.error("Occupied ID: {}", nextIdStr);
				id = findNextAvailableId(id);
			}
		}
		
		prop.put("nextId", Integer.toString(id + 1));
		try (FileWriter fw = new FileWriter(optionFile)) {
			prop.store(fw, "");
		} catch (IOException e) {
			Session.LOGGER.error("Unable to save configurations.");
			e.printStackTrace();
		}
		
		return id;
	}

	static boolean checkAvailability(int id) {
		// TODO Packed chat logs
		return !id2File(id).exists();
	}
	
	/**
	 * @param from Should not be available.
	 */
	private static int findNextAvailableId(int from) {
		while(!checkAvailability(++from));
		return from;
	}
	
	@FunctionalInterface
	interface RunnableWithIOException {
		void run() throws IOException;
	}

	static File id2File(int id) {
		return new File(Session.CHATLOG_FOLDER, String.format("log-%d.json", id));
	}
}
