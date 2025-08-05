package lovexyn0827.chatlog.session;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.zip.GZIPOutputStream;

import org.apache.commons.lang3.StringUtils;

import io.netty.util.internal.StringUtil;
import it.unimi.dsi.fastutil.ints.IntSet;
import lovexyn0827.chatlog.config.Options;
import net.minecraft.text.Text;
import net.minecraft.text.TextVisitFactory;
import net.minecraft.util.Util;

public class SessionRecorder {
	private static SessionRecorder current = null;
	private Deque<Session.Line> cachedChatLogs;
	private final LinkedHashMap<UUID, String> uuidToName;
	private final String saveName;
	private final int id;
	private final long startTime;
	private final TimeZone timeZone;
	private long messageCount = 0;
	private final Session.Version version;
	public final boolean multiplayer;
	
	private volatile boolean finalSaving = false;
	private final Thread autosaveWorker;
	
	SessionRecorder(String saveName, boolean multiplayer) {
		this.cachedChatLogs = new ConcurrentLinkedDeque<>();
		this.uuidToName = new LinkedHashMap<>();
		this.id = SessionUtils.allocateId();
		this.saveName = saveName;
		this.startTime = System.currentTimeMillis();
		this.timeZone = TimeZone.getDefault();
		this.version = Session.Version.LATEST;
		this.multiplayer = multiplayer;
		this.autosaveWorker = new AutosaveWorker();
		this.autosaveWorker.start();
		this.writeSummary();
	}
	
	SessionRecorder(Session existing) {
		this.cachedChatLogs = existing.getMessages();
		this.uuidToName = existing.getSendersByUuid();
		Session.Summary metadata = existing.getMetadata();
		this.id = metadata.id;
		this.saveName = metadata.saveName;
		this.startTime = metadata.startTime;
		this.timeZone = metadata.timeZone;
		this.version = metadata.version;
		this.multiplayer = metadata.multiplayer;
		this.autosaveWorker = new AutosaveWorker();
		this.autosaveWorker.start();
	}
	
	public static SessionRecorder start(String saveName, boolean multiplayer) {
		if (Options.newSessionPerMcLaunch) {
			SessionRecorder recorder = current == null ? startAnew(saveName, multiplayer) : current;
			if (Options.gameSessionIndicator) {
				recorder.addWorldIndicator(saveName, multiplayer);
			}
			
			return recorder;
		} else {
			return startAnew(saveName, multiplayer);
		}
	}

	private static SessionRecorder startAnew(String saveName, boolean multiplayer) {
		SessionRecorder recorder = new SessionRecorder(saveName, multiplayer);
		UnsavedChatlogRecovery.markUnsaved(SessionUtils.id2File(recorder.id));
		current = recorder;
		return recorder;
	}

	public static SessionRecorder current() {
		return current;
	}
	
	/**
	 * Called when the player leaves a world, or when the client exits
	 * @param clientExiting {@code true} if the client is exiting
	 */
	public static void end(boolean clientExiting) {
		if (current == null) {
			return;
		}
		
		current.updateSummary();
		if (!Options.newSessionPerMcLaunch || clientExiting) {
			current.end0();
			current = null;
		}
	}

	public void onMessage(UUID sender, Text msg) {
		long timestamp = Util.getEpochTimeMs();
		this.cachedChatLogs.addLast(new Session.Line(sender, msg, timestamp));
		this.uuidToName.computeIfAbsent(sender, (uuid) -> {
			// Naive solution?
			String string = TextVisitFactory.removeFormattingCodes(msg);
			String name = StringUtils.substringBetween(string, "<", ">");
			return name == null ? "[UNSPECIFIED]" : name;
		});
		this.messageCount++;
	}
	
	public void addEvent(Session.Event e) {
		this.cachedChatLogs.add(e);
		this.messageCount++;
	}
	
	private void addWorldIndicator(String saveName, boolean multiplayer) {
		this.cachedChatLogs.add(new Session.WorldIndicator(saveName, multiplayer, Util.getEpochTimeMs()));
	}
	
	void end0() {
		this.finalSaving = true;
		try {
			this.autosaveWorker.join();
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
	}
	
	/**
	 * Only guaranteed to be accurate when invoked on terminated or deserialized Sessions.
	 */
	private Session.Summary toSummary() {
		return new Session.Summary(this.id, this.saveName, this.startTime, Util.getEpochTimeMs(), this.messageCount, 
				this.timeZone, this.multiplayer, this.version);
	}
	
	private boolean writeSummary() {
		return this.toSummary().write();
	}
	
	private void updateSummary() {
		Session.updateSummary(this.toSummary());
	}
	
	private final class AutosaveWorker extends Thread {
		private PrintWriter chatlogWriter;
		private int lastAutoSaveSendererCount = 0;
		private int finalLoops = 2;
		private final File chatlogFile = SessionUtils.id2File(SessionRecorder.this.id);

		protected AutosaveWorker() {
			super("ChatLog Autosave Worker");
			PrintWriter temp;
			try {
				OutputStreamWriter backend = new OutputStreamWriter(new GZIPOutputStream(
						new BufferedOutputStream(new FileOutputStream(this.chatlogFile)), true));
				temp = new PrintWriter(backend);
				temp.println(String.format("%d,%s,%d,%s,%s", 
						SessionRecorder.this.id, StringUtil.escapeCsv(SessionRecorder.this.saveName), 
						SessionRecorder.this.startTime, SessionRecorder.this.timeZone.getID(), 
						SessionRecorder.this.multiplayer));
				temp.flush();
			} catch (IOException e) {
				Session.LOGGER.error("Failed to create temp file for chat logs!");
				e.printStackTrace();
				throw new RuntimeException();
			}
			
			this.chatlogWriter = temp;
		}
		
		private static char getLineTypeMarker(Session.Line l) {
			if (l instanceof Session.Event) {
				return 'E';
			} else if (l instanceof Session.WorldIndicator) {
				return 'W';
			} else {
				return 'M';
			}
		}
		
		@Override
		public void run() {
			while (!SessionRecorder.this.finalSaving || this.finalLoops-- > 0) {
				try {
					List<Map.Entry<UUID, String>> senders = 
							new ArrayList<>(SessionRecorder.this.uuidToName.entrySet());
					List<Map.Entry<UUID, String>> sendersToSave = 
							senders.subList(this.lastAutoSaveSendererCount, senders.size());
					this.lastAutoSaveSendererCount = senders.size();
					for(Map.Entry<UUID, String> e : sendersToSave) {
						this.chatlogWriter.println(String.format("S%s,%s", 
								e.getKey().toString(), 
								StringUtil.escapeCsv(e.getValue())));
					}

					SessionUtils.wrapTextSerialization(() -> {
						while (!SessionRecorder.this.cachedChatLogs.isEmpty()) {
							Session.Line l = SessionRecorder.this.cachedChatLogs.pollFirst();
							this.chatlogWriter.print(getLineTypeMarker(l));
							this.chatlogWriter.println(l.toJson());
						}
					});
					this.chatlogWriter.flush();
					Thread.sleep(Options.realtimeChatlogSaving ? 0 : Options.autoSaveIntervalInMs);
				} catch (Exception e) {
					Session.LOGGER.error("Failed to perform autosave!");
					e.printStackTrace();
				}
			}
			
			this.chatlogWriter.close();
			if (SessionRecorder.this.messageCount == 0) {
				Session.delete(IntSet.of(SessionRecorder.this.id));
				UnsavedChatlogRecovery.markUnsaved(null);
				return;
			}
			
			Session.LOGGER.info("Saved chatlog to: {}", this.chatlogFile);
			UnsavedChatlogRecovery.markUnsaved(null);
		}
	}
}
