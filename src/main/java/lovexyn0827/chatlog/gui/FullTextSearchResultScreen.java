package lovexyn0827.chatlog.gui;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import lovexyn0827.chatlog.i18n.I18N;
import lovexyn0827.chatlog.session.Session;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.Element;
import net.minecraft.client.gui.Selectable;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.AlwaysSelectedEntryListWidget;
import net.minecraft.client.gui.widget.ElementListWidget;
import net.minecraft.client.util.ChatMessages;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Util;

public class FullTextSearchResultScreen extends Screen {
	private final ConcurrentHashMap<Session.Summary, List<Session.Line>> results;
	private SessionList sessions;
	private MessageList messages;

	protected FullTextSearchResultScreen(ConcurrentHashMap<Session.Summary, List<Session.Line>> results) {
		super(I18N.translateAsText("gui.filter.result"));
		this.results = results;
	}
	
	@Override
	public void init() {
		this.sessions = new SessionList(this.client, this.results.keySet());
		this.addDrawableChild(sessions);
		this.messages = new MessageList(this.client);
		this.addDrawableChild(messages);
	}
	
	@Override
	public void close() {
		this.client.setScreen(new SessionListScreen());
	}
	
	@Override
	public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
		super.render(ctx, mouseX, mouseY, delta);
		ctx.drawCenteredTextWithShadow(this.textRenderer, I18N.translateAsText("gui.filter.result"), 
				this.width / 2, 5, 0xFFFFFFFF);
	}
	
	private class SessionList extends AlwaysSelectedEntryListWidget<SessionList.Entry> {
		public SessionList(MinecraftClient mc, Set<Session.Summary> sessions) {
			super(mc, (int) (FullTextSearchResultScreen.this.width * 0.35), 
					FullTextSearchResultScreen.this.height - 30, 
					20, 32);
			sessions.stream()
					.sorted((s1, s2) -> (int) (s2.startTime - s1.startTime))
					.map(Entry::new)
					.forEach(this::addEntry);
			this.setX(this.getRowLeft());
		}
		
		@Override
		public int getRowLeft() {
			return (int) (FullTextSearchResultScreen.this.width * 0.15 - 10);
		}
		
		@Override
		public int getRowWidth() {
			return this.width;
		}
		
		@Override
		public int getScrollbarPositionX() {
			return FullTextSearchResultScreen.this.width / 2 - 10;
		}

		private class Entry extends AlwaysSelectedEntryListWidget.Entry<Entry> {
			protected final Session.Summary summary;
			private final Text saveName;
			private final Text start;
			private final Text sizeAndTimeLength;
			private long lastClick = 0;
			
			public Entry(Session.Summary info) {
				this.summary = info;
				this.saveName = Text.literal(info.saveName);
				this.start = Text.literal(info.getFormattedStartTime())
						.formatted(Formatting.GRAY);
				long delta = (long) Math.floor((info.endTime - info.startTime) / 1000);
				this.sizeAndTimeLength = Text.literal(String.format(I18N.translate("gui.sizeandtime"), 
						(int) Math.floor(delta / 3600), (int) Math.floor((delta % 3600) / 60), delta % 60, info.size))
						.formatted(Formatting.GRAY);
			}
			
			@Override
			public boolean mouseClicked(double mouseX, double mouseY, int button) {
				FullTextSearchResultScreen.this.messages.setTexts(
						FullTextSearchResultScreen.this.results.get(this.summary));
				if (this.isFocused() && Util.getMeasuringTimeMs() - this.lastClick < 1000) {
					SessionListScreen.loadSession(FullTextSearchResultScreen.this.client, 
							this.summary, FullTextSearchResultScreen.this);
					return true;
				}

				SessionList.this.setFocused(this);
				this.lastClick = Util.getMeasuringTimeMs();
				return true;
			}

			@Override
			public Text getNarration() {
				return this.saveName;
			}

			@Override
			public void render(DrawContext ctx, int i, int y, int x, 
					int width, int height, int var7, int var8, boolean var9, float var10) {
				TextRenderer tr = FullTextSearchResultScreen.this.textRenderer;
				ctx.drawText(tr, this.saveName, x, y, 0xFFFFFFFF, false);
				ctx.drawText(tr, this.start, x, y + 10, 0xFFFFFFFF, false);
				ctx.drawText(tr, this.sizeAndTimeLength, x, y + 20, 0xFFFFFFFF, false);
			}
			
		}
	}
	
	private class MessageList extends ElementListWidget<MessageList.Entry> {
		public MessageList(MinecraftClient mc) {
			super(mc, (int) (FullTextSearchResultScreen.this.width * 0.35), 
					FullTextSearchResultScreen.this.height - 30, 
					20, mc.textRenderer.fontHeight + 1);
			this.setX(this.getRowLeft());
		}
		
		@Override
		public int getRowLeft() {
			return (int) (FullTextSearchResultScreen.this.width * 0.5 + 4);
		}
		
		@Override
		public int getRowWidth() {
			return this.width;
		}
		
		@Override
		public int getScrollbarPositionX() {
			return (int) (FullTextSearchResultScreen.this.width * 0.85);
		}
		
		public void setTexts(List<Session.Line> lines) {
			this.clearEntries();
			this.addEntry(new Entry(I18N.translateAsText("gui.filter.matchcnt", lines.size()).asOrderedText()));
			for (Session.Line l : lines) {
				this.addEntry(new Entry(Text.empty().asOrderedText()));
				ChatMessages.breakRenderedChatMessageLines(l.message, 
						(int) (FullTextSearchResultScreen.this.client.getWindow().getScaledWidth() * 0.35), 
						FullTextSearchResultScreen.this.textRenderer).forEach((t) -> {
							this.addEntry(new Entry(t));
						});
			}
		}

		private class Entry extends ElementListWidget.Entry<Entry> {
			private final OrderedText text;
			
			public Entry(OrderedText text) {
				this.text = text;
			}

			@Override
			public void render(DrawContext ctx, int i, int y, int x, 
					int width, int height, int var7, int var8, boolean var9, float var10) {
				TextRenderer tr = FullTextSearchResultScreen.this.textRenderer;
				ctx.drawText(tr, this.text, x, y, 0xFFFFFFFF, false);
			}

			@Override
			public List<? extends Element> children() {
				return Collections.emptyList();
			}

			@Override
			public List<? extends Selectable> selectableChildren() {
				return Collections.emptyList();
			}
			
		}
	}
}
