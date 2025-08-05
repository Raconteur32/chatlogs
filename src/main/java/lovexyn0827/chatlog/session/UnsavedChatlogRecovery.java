package lovexyn0827.chatlog.session;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Scanner;

import lovexyn0827.chatlog.config.Options;
import lovexyn0827.chatlog.i18n.I18N;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.toast.SystemToast;

public class UnsavedChatlogRecovery {
	private static final File UNSAVED_MARKER = new File(Session.CHATLOG_FOLDER, "unsaved.marker");

	static void markUnsaved(File unsaved) {
		if (unsaved != null) {
			try (FileWriter fw = new FileWriter(UNSAVED_MARKER)) {
				fw.append(unsaved.getAbsolutePath());
			} catch (IOException e) {
				Session.LOGGER.warn("Unable to create unsaved marker!");
				e.printStackTrace();
			}
		} else {
			UNSAVED_MARKER.delete();
		}
	}

	static File getUnsaved() {
		try (Scanner s = new Scanner(new FileReader(UNSAVED_MARKER))) {
			return new File(s.nextLine());
		} catch (IOException e) {
			return null;
		}
	}

	public static void tryRestoreUnsaved() {
		if (Options.newSessionPerMcLaunch && SessionRecorder.current() != null) {
			return;
		}
		
		File unsaved = getUnsaved();
		if (unsaved == null) {
			return;
		}
		
		boolean success = false;
		Session.Version[] loaders = Session.Version.values();
		// Iterate in an reversed order to ensure that newer versions are prioritized.
		Session.Summary summary = null;
		for (int i = loaders.length - 1; i >= 0; i--) {
			Session.Version ver = loaders[i];
			if ((summary = ver.inferMetadata(unsaved)) != null) {
				break;
			}
		}
		
		if (summary != null) {
			int id = summary.id;
			if (Session.getSessionSummaries().stream().anyMatch((s) -> s.id == id)) {
				success = Session.updateSummary(summary);
			} else {
				success = summary.write();
			}
		}
		
		if (!success) {
			SystemToast warning = new SystemToast(new SystemToast.Type(), 
					I18N.translateAsText("gui.restore.failure"), 
					I18N.translateAsText("gui.restore.failure.desc"));
			MinecraftClient.getInstance().getToastManager().add(warning);
		}
		
		markUnsaved(null);
	}

}
